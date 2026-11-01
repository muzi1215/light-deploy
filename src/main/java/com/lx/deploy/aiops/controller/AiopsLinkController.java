package com.lx.deploy.aiops.controller;

import com.lx.deploy.aiops.model.vo.AiopsLinkVo;
import com.lx.deploy.aiops.service.AiopsLinkService;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布联动只读接口。
 *
 * <p>只回答一个页面问题：「这次发布在 AIops 侧建了什么窗口、推了哪些事件、送到了没有」。
 * 因此只有查询，没有任何写操作——建窗与推事件的入口都在发布链路内部，
 * 不允许页面手工补建（补建会绕开幂等键与降级判断）。</p>
 *
 * <p>不标注 {@code @OperLog}：审计只记状态变更，翻看联动详情不该在操作日志里刷屏。</p>
 *
 * @author lx
 * @since 2026-11-02 03:10:00
 */
@RestController
@RequestMapping("/aiops")
public class AiopsLinkController {

    /** 联动查询服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private AiopsLinkService service;

    /**
     * 查询一次发布的联动全景（静默窗 + 事件流水）。
     *
     * @param deploymentId 部署主键
     * @param request      当前 HTTP 请求，用于取 requestId
     * @return 联动详情；静默窗与事件各自可为空，原因在 `silenceMessage` 里
     */
    @GetMapping("/link/{deploymentId}")
    public ApiResponse<AiopsLinkVo> link(@PathVariable String deploymentId, HttpServletRequest request) {
        return ApiResponse.success(service.getByDeployment(deploymentId), requestId(request));
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
