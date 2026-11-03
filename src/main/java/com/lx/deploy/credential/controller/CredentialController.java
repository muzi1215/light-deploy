package com.lx.deploy.credential.controller;

import com.lx.deploy.common.annotation.OperLog;
import com.lx.deploy.common.enums.audit.BusinessType;
import com.lx.deploy.credential.model.dto.CredentialDto;
import com.lx.deploy.credential.model.vo.CredentialVo;
import com.lx.deploy.credential.service.CredentialService;
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
 * 加密凭据接口。
 *
 * <p>Controller 请求统一使用 DTO，响应统一使用 VO，不直接暴露数据库实体。</p>
 *
 * @author lx
 * @since 2026-09-23 02:13:26
 */
@RestController
@RequestMapping("/credential")
@Tag(name = "凭据管理", description = "SSH / Git / 镜像仓库的加密凭据维护，响应永不返回明文")
public class CredentialController {

    /** 当前模块业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private CredentialService service;

    /**
     * 根据 DTO 非空字段分页查询数据。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求，用于获取 requestId
     * @return VO 分页数据
     */
    @Operation(summary = "凭据分页", description = "按 DTO 非空字段筛选；响应只含密文占位信息，不含明文")
    @GetMapping("/page")
    public ApiResponse<PageResult<CredentialVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                      @ModelAttribute CredentialDto condition,
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
    @Operation(summary = "凭据列表", description = "不分页，适合下拉选择；数据量大时应使用分页接口")
    @GetMapping("/list")
    public ApiResponse<List<CredentialVo>> list(@ModelAttribute CredentialDto condition,
                                               HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 根据主键查询单条加密凭据。
     *
     * @param id 数据主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含敏感字段的单条 VO
     */
    @Operation(summary = "凭据详情", description = "解密只发生在服务端执行阶段，接口只返回不含密文的凭据信息")
    @GetMapping("/detail/{id}")
    public ApiResponse<CredentialVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 根据请求 DTO 新增一条加密凭据。
     *
     * @param dto 新增请求参数，主键和审计字段由服务端维护
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 保存完成后的 VO
     */
    @Operation(summary = "新增凭据", description = "密文入库前加密；审计不记录请求参数，避免明文落日志")
    @OperLog(title = "凭据管理", businessType = BusinessType.INSERT, recordParam = false)
    @PostMapping("/create")
    public ApiResponse<CredentialVo> create(@Valid @RequestBody CredentialDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.create(dto), requestId(request));
    }

    /**
     * 根据路径主键和请求 DTO 修改加密凭据。
     *
     * @param id 待修改数据的主键
     * @param dto 修改请求参数，不能通过请求体修改主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 修改完成后的 VO
     */
    @Operation(summary = "修改凭据", description = "明文凭据留空则沿用原密文，其余字段全量覆盖；审计不记录请求参数")
    @OperLog(title = "凭据管理", businessType = BusinessType.UPDATE, recordParam = false, resourceType = "credential")
    @PutMapping("/update/{id}")
    public ApiResponse<CredentialVo> update(@PathVariable String id,
                                           @Valid @RequestBody CredentialDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 根据主键逻辑删除加密凭据。
     *
     * @param id 待删除数据的主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含业务数据的统一成功响应
     */
    @Operation(summary = "删除凭据", description = "仍被项目或镜像仓库引用时拒绝删除（数据冲突），需先解除绑定")
    @OperLog(title = "凭据管理", businessType = BusinessType.DELETE, resourceType = "credential")
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
