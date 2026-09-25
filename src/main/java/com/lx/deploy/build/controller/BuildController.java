package com.lx.deploy.build.controller;

import com.lx.deploy.build.model.dto.BuildDto;
import com.lx.deploy.build.model.vo.BuildArtifactVo;
import com.lx.deploy.build.model.vo.BuildVo;
import com.lx.deploy.build.service.BuildService;
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
 * 构建任务接口。
 *
 * <p>Controller 只负责接收请求、校验、调用 {@link BuildService} 并包装统一响应，不直接写库或执行远程操作。
 * 创建构建（POST）会同步触发一条 BUILD 异步任务，响应携带 taskId / taskNo，前端据此轮询任务日志。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@RestController
@RequestMapping("/build")
public class BuildController {

    /** 构建业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private BuildService service;

    /**
     * 根据 DTO 非空字段分页查询构建记录。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求，用于获取 requestId
     * @return 构建 VO 分页数据
     */
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
    @GetMapping("/detail/{id}")
    public ApiResponse<BuildVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 查询某次构建的全部产物。
     *
     * @param id 构建主键
     * @param request 当前 HTTP 请求
     * @return 构件产物 VO 列表
     */
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
    @PostMapping("/create")
    public ApiResponse<BuildVo> create(@Valid @RequestBody BuildDto dto, HttpServletRequest request) {
        return ApiResponse.success(service.createAndTrigger(dto), requestId(request));
    }

    /**
     * 修改构建（仅排队中允许重新指定构建机）。
     *
     * @param id 构建主键
     * @param dto 修改请求（可选 buildServerId）
     * @param request 当前 HTTP 请求
     * @return 修改后的构建 VO
     */
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
