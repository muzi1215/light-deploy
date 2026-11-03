package com.lx.deploy.project.controller;

import com.lx.deploy.common.annotation.OperLog;
import com.lx.deploy.common.enums.audit.BusinessType;
import com.lx.deploy.project.model.dto.ProjectEnvironmentTargetDto;
import com.lx.deploy.project.model.vo.EnvironmentOptionVo;
import com.lx.deploy.project.model.vo.ProjectEnvironmentTargetVo;
import com.lx.deploy.project.service.ProjectEnvironmentTargetService;
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

import java.util.List;

/**
 * 项目环境部署目标接口。
 *
 * <p>Controller 请求统一使用 DTO，响应统一使用 VO，不直接暴露数据库实体。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@RestController
@RequestMapping("/projectEnvironmentTarget")
@Tag(name = "部署目标", description = "项目+环境与目标服务器的绑定关系，发布只认这里的启用绑定")
public class ProjectEnvironmentTargetController {

    /** 当前模块业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private ProjectEnvironmentTargetService service;

    /**
     * 根据 DTO 非空字段分页查询数据。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求，用于获取 requestId
     * @return VO 分页数据
     */
    @Operation(summary = "部署目标分页", description = "按 DTO 非空字段筛选，按创建时间倒序")
    @GetMapping("/page")
    public ApiResponse<PageResult<ProjectEnvironmentTargetVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                      @ModelAttribute ProjectEnvironmentTargetDto condition,
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
    @Operation(summary = "部署目标列表", description = "不分页，适合下拉与轻量列表；数据量大时应使用分页接口")
    @GetMapping("/list")
    public ApiResponse<List<ProjectEnvironmentTargetVo>> list(@ModelAttribute ProjectEnvironmentTargetDto condition,
                                               HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 查询指定项目已绑定启用目标的环境选项。
     *
     * <p>与 {@code /list} 的区别：本接口面向「新建部署」的选环境下拉，只返回能真正发布的环境，
     * 并带回启用目标台数（页面渲染为「生产环境（2 台）」）。项目没有启用绑定时返回空列表，
     * 而不是全量环境——全量环境里绝大多数与当前项目无关，选了只会在提交阶段报错。</p>
     *
     * @param projectId 项目主键，必填
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 环境选项列表；projectId 为空时返回空列表
     */
    @Operation(summary = "可选环境下拉", description = "只返回该项目已绑定启用目标的环境并带回目标台数；没有绑定时返回空列表")
    @GetMapping("/env-options")
    public ApiResponse<List<EnvironmentOptionVo>> envOptions(@RequestParam String projectId,
                                                             HttpServletRequest request) {
        return ApiResponse.success(service.listEnvironmentOptions(projectId), requestId(request));
    }

    /**
     * 根据主键查询单条项目环境部署目标。
     *
     * @param id 数据主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含敏感字段的单条 VO
     */
    @Operation(summary = "部署目标详情", description = "返回单条项目环境与服务器的绑定记录")
    @GetMapping("/detail/{id}")
    public ApiResponse<ProjectEnvironmentTargetVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 根据请求 DTO 新增一条项目环境部署目标。
     *
     * @param dto 新增请求参数，主键和审计字段由服务端维护
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 保存完成后的 VO
     */
    @Operation(summary = "新增部署目标", description = "把一台服务器绑定到 项目+环境；同一环境可绑多台，is_primary 决定主目标")
    @OperLog(title = "部署目标", businessType = BusinessType.INSERT)
    @PostMapping("/create")
    public ApiResponse<ProjectEnvironmentTargetVo> create(@Valid @RequestBody ProjectEnvironmentTargetDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.create(dto), requestId(request));
    }

    /**
     * 根据路径主键和请求 DTO 修改项目环境部署目标。
     *
     * @param id 待修改数据的主键
     * @param dto 修改请求参数，不能通过请求体修改主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 修改完成后的 VO
     */
    @Operation(summary = "修改部署目标", description = "全量覆盖写入；置为主目标时会自动取消同项目同环境下其它目标的主目标标记")
    @OperLog(title = "部署目标", businessType = BusinessType.UPDATE, resourceType = "projectEnvironmentTarget")
    @PutMapping("/update/{id}")
    public ApiResponse<ProjectEnvironmentTargetVo> update(@PathVariable String id,
                                           @Valid @RequestBody ProjectEnvironmentTargetDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 根据主键逻辑删除项目环境部署目标。
     *
     * @param id 待删除数据的主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含业务数据的统一成功响应
     */
    @Operation(summary = "删除部署目标", description = "相当于在发布链路上摘掉这台机器；逻辑删除，不物理删除记录")
    @OperLog(title = "部署目标", businessType = BusinessType.DELETE, resourceType = "projectEnvironmentTarget")
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
