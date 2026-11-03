package com.lx.deploy.audit.controller;

import com.lx.deploy.audit.model.dto.AuditLogDto;
import com.lx.deploy.audit.model.vo.AuditLogVo;
import com.lx.deploy.audit.service.AuditLogService;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志接口。
 *
 * <p>只提供分页查询与详情：日志由 {@code @OperLog} 注解驱动的切面写入，
 * 不开放任何写接口，避免审计记录被事后改写或清理。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
@RestController
@RequestMapping("/auditLog")
@Tag(name = "操作审计", description = "审计日志的分页查询与详情，只读不写")
public class AuditLogController {

    /** 当前模块业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private AuditLogService service;

    /**
     * 按条件分页查询操作日志。
     *
     * @param pageQuery 页码和每页数量
     * @param condition 查询条件，支持操作人、模块、类型、请求方式、结果与时间区间
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 操作日志分页数据，按操作时间倒序
     */
    @Operation(summary = "操作日志分页", description = "支持操作人、模块、类型、请求方式、结果与时间区间筛选，按操作时间倒序")
    @GetMapping("/page")
    public ApiResponse<PageResult<AuditLogVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                    @ModelAttribute AuditLogDto condition,
                                                    HttpServletRequest request) {
        return ApiResponse.success(service.pageByCondition(pageQuery, condition), requestId(request));
    }

    /**
     * 查询单条操作日志详情。
     *
     * @param id 日志主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 含请求参数与返回结果的日志详情
     */
    @Operation(summary = "操作日志详情", description = "返回含请求参数与返回结果的单条审计记录")
    @GetMapping("/detail/{id}")
    public ApiResponse<AuditLogVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
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
