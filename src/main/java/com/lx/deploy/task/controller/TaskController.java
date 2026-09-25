package com.lx.deploy.task.controller;

import com.lx.deploy.task.model.dto.TaskDto;
import com.lx.deploy.task.model.vo.TaskVo;
import com.lx.deploy.task.service.TaskService;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 异步任务接口。
 *
 * <p>Controller 请求统一使用 DTO，响应统一使用 VO，不直接暴露数据库实体。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:00
 */
@RestController
@RequestMapping("/task")
public class TaskController {

    /** 当前模块业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private TaskService service;

    /**
     * 根据 DTO 非空字段分页查询数据。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求，用于获取 requestId
     * @return VO 分页数据
     */
    @GetMapping("/page")
    public ApiResponse<PageResult<TaskVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                      @ModelAttribute TaskDto condition,
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
    @GetMapping("/list")
    public ApiResponse<List<TaskVo>> list(@ModelAttribute TaskDto condition,
                                               HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 根据主键查询单条异步任务。
     *
     * @param id 数据主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含敏感字段的单条 VO
     */
    @GetMapping("/detail/{id}")
    public ApiResponse<TaskVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 根据请求 DTO 新增一条异步任务。
     *
     * @param dto 新增请求参数，主键和审计字段由服务端维护
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 保存完成后的 VO
     */
    @PostMapping("/create")
    public ApiResponse<TaskVo> create(@Valid @RequestBody TaskDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.create(dto), requestId(request));
    }

    /**
     * 根据路径主键和请求 DTO 修改异步任务。
     *
     * @param id 待修改数据的主键
     * @param dto 修改请求参数，不能通过请求体修改主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 修改完成后的 VO
     */
    @PutMapping("/update/{id}")
    public ApiResponse<TaskVo> update(@PathVariable String id,
                                           @Valid @RequestBody TaskDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 根据主键逻辑删除异步任务。
     *
     * @param id 待删除数据的主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含业务数据的统一成功响应
     */
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        service.delete(id);
        return ApiResponse.success(requestId(request));
    }

    /**
     * 取消任务。
     *
     * <p>排队中和执行中的任务允许人工取消，终态任务返回数据冲突异常。
     * 该接口是人工干预任务的唯一入口，任务执行器的超时兜底取消直接调用 Service。</p>
     *
     * @param id 待取消任务的主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 取消后的最新任务 VO
     */
    @PutMapping("/cancel/{id}")
    public ApiResponse<TaskVo> cancel(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.cancel(id), requestId(request));
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
