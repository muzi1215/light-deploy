package com.lx.deploy.build.controller;

import com.lx.deploy.common.annotation.OperLog;
import com.lx.deploy.common.enums.audit.BusinessType;
import com.lx.deploy.build.model.dto.BuildDto;
import com.lx.deploy.build.model.vo.BuildArtifactVo;
import com.lx.deploy.build.model.vo.BuildVo;
import com.lx.deploy.build.service.ArtifactUploadService;
import com.lx.deploy.build.service.BuildService;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 构建任务接口。
 *
 * <p>Controller 只负责接收请求、校验、调用 Service 并包装统一响应，不直接写库或执行远程操作。
 * 创建构建（POST /create）会同步触发一条 BUILD 异步任务，响应携带 taskId / taskNo，前端据此轮询任务日志。</p>
 *
 * <p>产物上传（POST /upload）是另一条独立的入口：用于「本地已经构建好的包直接登记为版本」的场景
 * （项目来源 UPLOAD + 构建模式 ARTIFACT_ONLY），它不产生构建任务，上传完成即可发布。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@RestController
@RequestMapping("/build")
@Tag(name = "构建管理", description = "构建记录查询、创建触发与本地产物上传")
public class BuildController {

    /** 构建业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private BuildService service;

    /** 产物上传服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private ArtifactUploadService uploadService;

    /**
     * 根据 DTO 非空字段分页查询构建记录。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求，用于获取 requestId
     * @return 构建 VO 分页数据
     */
    @Operation(summary = "构建记录分页", description = "按 DTO 非空字段筛选，按创建时间倒序")
    @GetMapping("/page")
    public ApiResponse<PageResult<BuildVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                 @ModelAttribute BuildDto condition,
                                                 HttpServletRequest request) {
        return ApiResponse.success(service.pageByCondition(pageQuery, condition), requestId(request));
    }

    /**
     * 根据 DTO 中的非空业务字段查询全部匹配构建记录。
     *
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求
     * @return 构建 VO 列表
     */
    @Operation(summary = "构建记录列表", description = "不分页，适合下拉与轻量列表；数据量大时应使用分页接口")
    @GetMapping("/list")
    public ApiResponse<List<BuildVo>> list(@ModelAttribute BuildDto condition, HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 根据主键查询单条构建（含关联任务标识）。
     *
     * @param id 构建主键
     * @param request 当前 HTTP 请求
     * @return 构建 VO
     */
    @Operation(summary = "构建详情", description = "含关联任务标识，前端据此拉起任务日志")
    @GetMapping("/detail/{id}")
    public ApiResponse<BuildVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 查询某次构建的全部产物。
     *
     * @param id 构建主键
     * @param request 当前 HTTP 请求
     * @return 构建产物 VO 列表
     */
    @Operation(summary = "构建产物列表", description = "查询该次构建产出的全部文件记录")
    @GetMapping("/artifacts/{id}")
    public ApiResponse<List<BuildArtifactVo>> artifacts(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.listArtifacts(id), requestId(request));
    }

    /**
     * 创建并触发一次构建。
     *
     * @param dto 构建请求（项目 ID 必填，可选覆盖构建机）
     * @param request 当前 HTTP 请求
     * @return 刚创建并触发的构建 VO（含 taskId / taskNo）
     */
    @Operation(summary = "创建构建", description = "写入一条 BUILD 异步任务后立即返回 taskId，实际构建由调度器取任务执行")
    @OperLog(title = "构建管理", businessType = BusinessType.EXECUTE)
    @PostMapping("/create")
    public ApiResponse<BuildVo> create(@Valid @RequestBody BuildDto dto, HttpServletRequest request) {
        return ApiResponse.success(service.createAndTrigger(dto), requestId(request));
    }

    /**
     * 上传本地已构建好的产物，登记为一个可直接发布的版本。
     *
     * <p>适用项目来源为 UPLOAD、构建模式为 ARTIFACT_ONLY 且运行时为 JAR / STATIC 的项目：
     * 前端 {@code npm run build} 后打包上传，或本机打好的 jar 直接上传，平台不做任何构建。
     * 上传是同步的：只是传文件与登记版本，没有构建那样的分钟级等待。</p>
     *
     * <p>受支持的格式：{@code .zip} / {@code .tar.gz} / {@code .tgz}（静态产物）、{@code .jar}（JAR 运行时）。</p>
     *
     * @param projectId  目标项目 ID
     * @param versionSeq 版本序号，可为空（为空时项目内自动递增取号）
     * @param file       产物文件
     * @param request    当前 HTTP 请求
     * @return 本次上传登记的构建 VO
     */
    @Operation(summary = "上传产物", description = "把本地已构建好的包登记为可直接发布的版本，同步完成、不产生构建任务")
    @OperLog(title = "构建管理", businessType = BusinessType.EXECUTE)
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ApiResponse<BuildVo> upload(@RequestParam("projectId") String projectId,
                                       @RequestParam(value = "versionSeq", required = false) Integer versionSeq,
                                       @RequestPart("file") MultipartFile file,
                                       HttpServletRequest request) {
        return ApiResponse.success(uploadService.upload(projectId, versionSeq, file), requestId(request));
    }

    /**
     * 修改构建（仅排队中允许重新指定构建机）。
     *
     * @param id 构建主键
     * @param dto 修改请求（可选 buildServerId）
     * @param request 当前 HTTP 请求
     * @return 修改后的构建 VO
     */
    @Operation(summary = "修改构建", description = "仅排队中的构建允许重新指定构建机；未传 buildServerId 时不做任何变更")
    @OperLog(title = "构建管理", businessType = BusinessType.UPDATE, resourceType = "build")
    @PutMapping("/update/{id}")
    public ApiResponse<BuildVo> update(@PathVariable String id,
                                       @RequestBody BuildDto dto,
                                       HttpServletRequest request) {
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 删除构建（仅终态允许）。
     *
     * @param id 构建主键
     * @param request 当前 HTTP 请求
     * @return 不包含业务数据的统一成功响应
     */
    @Operation(summary = "删除构建", description = "仅终态（成功/失败）可删除，执行中的请先取消")
    @OperLog(title = "构建管理", businessType = BusinessType.DELETE, resourceType = "build")
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
