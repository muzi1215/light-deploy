package com.lx.deploy.project.controller;

import com.lx.deploy.common.annotation.OperLog;
import com.lx.deploy.common.enums.audit.BusinessType;
import com.lx.deploy.project.model.dto.EnvironmentDto;
import com.lx.deploy.project.model.vo.EnvironmentVo;
import com.lx.deploy.project.service.EnvironmentService;
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
 * 部署环境接口。
 *
 * <p>Controller 请求统一使用 DTO，响应统一使用 VO，不直接暴露数据库实体。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@RestController
@RequestMapping("/environment")
@Tag(name = "环境管理", description = "部署环境（测试、生产等发布语义）的维护")
public class EnvironmentController {

    /** 当前模块业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private EnvironmentService service;

    /**
     * 根据 DTO 非空字段分页查询数据。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求，用于获取 requestId
     * @return VO 分页数据
     */
    @Operation(summary = "环境分页", description = "按 DTO 非空字段筛选，按创建时间倒序")
    @GetMapping("/page")
    public ApiResponse<PageResult<EnvironmentVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                      @ModelAttribute EnvironmentDto condition,
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
    @Operation(summary = "环境列表", description = "不分页，适合下拉与轻量列表；数据量大时应使用分页接口")
    @GetMapping("/list")
    public ApiResponse<List<EnvironmentVo>> list(@ModelAttribute EnvironmentDto condition,
                                               HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 根据主键查询单条部署环境。
     *
     * @param id 数据主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含敏感字段的单条 VO
     */
    @Operation(summary = "环境详情", description = "返回单条环境配置")
    @GetMapping("/detail/{id}")
    public ApiResponse<EnvironmentVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 根据请求 DTO 新增一条部署环境。
     *
     * @param dto 新增请求参数，主键和审计字段由服务端维护
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 保存完成后的 VO
     */
    @Operation(summary = "新增环境", description = "环境只描述发布语义，本身不持有任何机器；机器由部署目标绑定")
    @OperLog(title = "环境管理", businessType = BusinessType.INSERT)
    @PostMapping("/create")
    public ApiResponse<EnvironmentVo> create(@Valid @RequestBody EnvironmentDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.create(dto), requestId(request));
    }

    /**
     * 根据路径主键和请求 DTO 修改部署环境。
     *
     * @param id 待修改数据的主键
     * @param dto 修改请求参数，不能通过请求体修改主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 修改完成后的 VO
     */
    @Operation(summary = "修改环境", description = "全量覆盖写入：未提交的字段会被置空，调用方须提交完整环境对象")
    @OperLog(title = "环境管理", businessType = BusinessType.UPDATE, resourceType = "environment")
    @PutMapping("/update/{id}")
    public ApiResponse<EnvironmentVo> update(@PathVariable String id,
                                           @Valid @RequestBody EnvironmentDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 根据主键逻辑删除部署环境。
     *
     * @param id 待删除数据的主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含业务数据的统一成功响应
     */
    @Operation(summary = "删除环境", description = "逻辑删除；仍被部署目标引用时拒绝删除（数据冲突）")
    @OperLog(title = "环境管理", businessType = BusinessType.DELETE, resourceType = "environment")
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
