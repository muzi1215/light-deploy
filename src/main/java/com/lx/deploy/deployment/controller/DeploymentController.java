package com.lx.deploy.deployment.controller;

import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import com.lx.deploy.deployment.model.dto.DeploymentDto;
import com.lx.deploy.deployment.model.vo.DeploymentTargetVo;
import com.lx.deploy.deployment.model.vo.DeploymentVo;
import com.lx.deploy.deployment.model.vo.RollbackPreviewVo;
import com.lx.deploy.deployment.service.DeploymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部署任务接口。
 *
 * <p>Controller 只负责接收请求、校验、调用 {@link DeploymentService} 并包装统一响应，
 * 不直接写库、不拼查询条件、不执行任何 SSH / Docker 远程操作（AGENTS.md §12）。</p>
 *
 * <p>创建部署（POST /create）与回滚（PUT /rollback/{id}）都会同步触发一条 DEPLOY 异步任务，
 * 响应携带 taskId / taskNo，前端据此轮询任务日志展示实时进度。
 * 回滚预览（GET /rollback-preview/{id}）是纯只读接口，不改变任何状态、不触发任务。</p>
 *
 * <p>路径风格遵循 AGENTS.md §12.2：动作在前、{id} 在后。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@RestController
@RequestMapping("/deployment")
public class DeploymentController {

    /** 部署业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private DeploymentService service;

    /**
     * 根据 DTO 非空字段分页查询部署记录。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件（项目、环境、版本、状态）
     * @param request   当前 HTTP 请求，用于获取 requestId
     * @return 部署 VO 分页数据
     */
    @GetMapping("/page")
    public ApiResponse<PageResult<DeploymentVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                      @ModelAttribute DeploymentDto condition,
                                                      HttpServletRequest request) {
        return ApiResponse.success(service.pageByCondition(pageQuery, condition), requestId(request));
    }

    /**
     * 根据 DTO 非空字段查询全部匹配部署记录。
     *
     * @param condition DTO 查询条件
     * @param request   当前 HTTP 请求
     * @return 部署 VO 列表
     */
    @GetMapping("/list")
    public ApiResponse<List<DeploymentVo>> list(@ModelAttribute DeploymentDto condition,
                                                HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 根据主键查询单条部署（含目标汇总与关联任务标识）。
     *
     * @param id      部署主键
     * @param request 当前 HTTP 请求
     * @return 部署 VO
     */
    @GetMapping("/detail/{id}")
    public ApiResponse<DeploymentVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 查询一次部署的全部目标明细。
     *
     * @param id      部署主键
     * @param request 当前 HTTP 请求
     * @return 部署目标 VO 列表
     */
    @GetMapping("/targets/{id}")
    public ApiResponse<List<DeploymentTargetVo>> targets(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.listTargets(id), requestId(request));
    }

    /**
     * 创建并触发一次部署。
     *
     * @param dto     部署请求（项目、环境、版本必填，可选覆盖策略）
     * @param request 当前 HTTP 请求
     * @return 刚创建并触发的部署 VO（含 taskId / taskNo）
     */
    @PostMapping("/create")
    public ApiResponse<DeploymentVo> create(@Valid @RequestBody DeploymentDto dto, HttpServletRequest request) {
        return ApiResponse.success(service.createAndTrigger(dto), requestId(request));
    }

    /**
     * 修改部署（仅排队中允许更换版本或策略）。
     *
     * @param id      部署主键
     * @param dto     修改请求（可选 releaseId / strategy）
     * @param request 当前 HTTP 请求
     * @return 修改后的部署 VO
     */
    @PutMapping("/update/{id}")
    public ApiResponse<DeploymentVo> update(@PathVariable String id,
                                            @RequestBody DeploymentDto dto,
                                            HttpServletRequest request) {
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 回滚一次已成功的部署。
     *
     * @param id      部署主键
     * @param request 当前 HTTP 请求
     * @return 进入回滚中的部署 VO（含新的 taskId / taskNo）
     */
    @PutMapping("/rollback/{id}")
    public ApiResponse<DeploymentVo> rollback(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.rollback(id), requestId(request));
    }

    /**
     * 预览一次回滚会发生什么。
     *
     * <p>只读接口，不产生任何状态变更、不触发任务。回滚按钮在真正提交前先调这里，
     * 把「哪几台会退回哪个版本、哪几台跳过及原因」摆给使用者确认。</p>
     *
     * @param id      部署主键
     * @param request 当前 HTTP 请求
     * @return 回滚预览（总结论 + 逐目标版本对照）
     */
    @GetMapping("/rollback-preview/{id}")
    public ApiResponse<RollbackPreviewVo> rollbackPreview(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.rollbackPreview(id), requestId(request));
    }

    /**
     * 取消一次排队中或发布中的部署。
     *
     * @param id      部署主键
     * @param request 当前 HTTP 请求
     * @return 取消后的部署 VO
     */
    @PutMapping("/cancel/{id}")
    public ApiResponse<DeploymentVo> cancel(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.cancel(id), requestId(request));
    }

    /**
     * 删除部署（仅终态允许）。
     *
     * @param id      部署主键
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
