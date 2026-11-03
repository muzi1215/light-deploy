package com.lx.deploy.task.controller;

import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.model.vo.TaskLogExportVo;
import com.lx.deploy.task.model.vo.TaskLogPullVo;
import com.lx.deploy.task.model.vo.TaskLogVo;
import com.lx.deploy.task.service.TaskLogService;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 任务执行日志接口。
 *
 * <p>Controller 请求统一使用 DTO，响应统一使用 VO，不直接暴露数据库实体。</p>
 *
 * <p>唯一的例外是 {@code GET /taskLog/export/{taskId}}：它返回纯文本附件而不是
 * {@code ApiResponse}，因为导出件要能被浏览器直接存成文件，包一层 JSON 反而使下载端
 * 必须多解一层。异常路径仍走全局异常处理器返回标准 JSON 错误体。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:01
 */
@RestController
@RequestMapping("/taskLog")
@Tag(name = "任务日志", description = "任务执行日志的增量拉取、导出与查询，前端执行过程页依赖此模块")
public class TaskLogController {

    /** 当前模块业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private TaskLogService service;

    /**
     * 根据 DTO 非空字段分页查询数据。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求，用于获取 requestId
     * @return VO 分页数据
     */
    @Operation(summary = "日志分页", description = "按 DTO 非空字段筛选；做执行过程实时展示请用 /pull 增量接口")
    @GetMapping("/page")
    public ApiResponse<PageResult<TaskLogVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                      @ModelAttribute TaskLogDto condition,
                                                      HttpServletRequest request) {
        return ApiResponse.success(service.pageByCondition(pageQuery, condition), requestId(request));
    }

    /**
     * 根据 DTO 中的非空业务字段查询全部匹配记录。
     *
     * <p>该接口适合数据量较小的选择列表；大数据量页面应使用分页接口。</p>
     *
     * @param condition DTO 查询条件，字段全部为空时查询所有未删除记录
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含敏感字段的 VO 列表
     */
    @Operation(summary = "日志列表", description = "不分页，用于一次性取某任务的全部日志；实时轮询请用 /pull")
    @GetMapping("/list")
    public ApiResponse<List<TaskLogVo>> list(@ModelAttribute TaskLogDto condition,
                                               HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 按顺序号增量拉取任务日志（页面实时查看执行过程使用）。
     *
     * <p>只返回顺序号大于 lastSeq 的日志，避免分页轮询带来的日志重复与乱序：
     * 前端记住上一次响应里的 lastSeq，本次以它为锚点继续取增量；
     * 响应中的 finished 为 true 时表示任务已结束，前端应停止轮询。</p>
     *
     * @param taskId 任务主键，必填
     * @param lastSeq 已获取到的最大日志顺序号；首次拉取不传
     * @param limit 单次最多返回条数；不传取默认值 200，最大 500
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 增量日志、任务状态与下一次拉取的锚点
     */
    @Operation(summary = "增量拉取日志", description = "只返回 seq > lastSeq 的日志，响应中的 finished=true 表示任务已结束、前端应停止轮询")
    @GetMapping("/pull")
    public ApiResponse<TaskLogPullVo> pull(@RequestParam String taskId,
                                            @RequestParam(required = false) Long lastSeq,
                                            @RequestParam(required = false) Integer limit,
                                            HttpServletRequest request) {
        return ApiResponse.success(service.pull(taskId, lastSeq, limit), requestId(request));
    }

    /**
     * 导出指定任务的全部执行日志为文本附件。
     *
     * <p>这是本模块唯一的非 JSON 响应接口：直接返回 {@code text/plain} 附件，浏览器触发下载。
     * 导出件由服务端生成而不是前端拼装，原因是导出正文要带任务头部摘要（编号 / 类型 / 状态 /
     * 关联业务主键 / 起止时间 / 失败原因），这些信息只有服务端能一次取齐；文件名也统一由服务端
     * 按任务编号生成，避免各页面各拼一套命名规则。</p>
     *
     * <p>本接口不包 {@code ApiResponse}，因此响应体里没有 traceId；追踪仍可通过响应头
     * {@code X-Request-Id} 与后端日志的 MDC 关联。任务不存在等业务异常仍由全局异常处理器
     * 返回标准 JSON 错误体，前端需先判断响应类型再决定是下载还是提示（见前端 useTaskLogViewer）。</p>
     *
     * @param taskId 任务主键，必填
     * @return 附件响应：正文为 UTF-8 纯文本，Content-Disposition 携带文件名
     */
    @Operation(summary = "导出任务日志", description = "返回 text/plain 附件（不包 ApiResponse），正文带任务头部摘要；业务异常仍是标准 JSON")
    @GetMapping("/export/{taskId}")
    public ResponseEntity<byte[]> export(@PathVariable String taskId) {
        TaskLogExportVo export = service.exportText(taskId);
        byte[] body = export.content().getBytes(StandardCharsets.UTF_8);
        // 文件名含中文，必须走 RFC 5987 的 filename* 编码，否则浏览器下载后会出现乱码或被截断。
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(export.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .contentLength(body.length)
                .body(body);
    }

    /**
     * 根据主键查询单条任务执行日志。
     *
     * @param id 数据主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含敏感字段的单条 VO
     */
    @Operation(summary = "日志详情", description = "返回单条任务日志记录")
    @GetMapping("/detail/{id}")
    public ApiResponse<TaskLogVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 根据请求 DTO 新增一条任务执行日志。
     *
     * @param dto 新增请求参数，主键和审计字段由服务端维护
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 保存完成后的 VO
     */
    @Operation(summary = "新增日志", description = "一般由执行器通过 TaskLogService 写入，不供页面直接调用")
    @PostMapping("/create")
    public ApiResponse<TaskLogVo> create(@Valid @RequestBody TaskLogDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.create(dto), requestId(request));
    }

    /**
     * 根据路径主键和请求 DTO 修改任务执行日志。
     *
     * @param id 待修改数据的主键
     * @param dto 修改请求参数，不能通过请求体修改主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 修改完成后的 VO
     */
    @Operation(summary = "修改日志", description = "全量覆盖写入；日志属于执行痕迹，正常链路不修改")
    @PutMapping("/update/{id}")
    public ApiResponse<TaskLogVo> update(@PathVariable String id,
                                           @Valid @RequestBody TaskLogDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 根据主键逻辑删除任务执行日志。
     *
     * @param id 待删除数据的主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含业务数据的统一成功响应
     */
    @Operation(summary = "删除日志", description = "逻辑删除，不物理删除记录")
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        service.delete(id);
        return ApiResponse.success(requestId(request));
    }

    /**
     * 从请求属性读取本次请求的追踪 ID。
     *
     * @param request 当前 HTTP 请求
     * @return 与响应头和 MDC 日志一致的 requestId
     */
    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestTraceFilter.ATTRIBUTE_NAME);
        return requestId == null ? null : requestId.toString();
    }
}
