package com.lx.deploy.user.controller;

import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.security.LoginUser;
import com.lx.deploy.common.web.RequestTraceFilter;
import com.lx.deploy.user.model.dto.LoginRequest;
import com.lx.deploy.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台鉴权接口（过渡期）。
 *
 * <p>提供登录、登出与当前用户信息三个接口。登录成功后登录用户写入 HttpSession（Spring Session 落 Redis），
 * 后续请求由 {@code AuthInterceptor} 校验会话。{@code /auth/login} 与 {@code /auth/logout} 在 WebConfig 中
 * 被排除在拦截器之外，本身不需要已登录。</p>
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "后台鉴权", description = "过渡期本地账号登录、登出与当前用户信息")
public class AuthController {

    private final AuthService authService;

    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 账号密码登录。
     *
     * @param request 登录请求（用户名 + 密码）
     * @param httpRequest 当前 HTTP 请求，用于创建会话与读取 requestId
     * @return 登录成功后的用户快照
     */
    @Operation(summary = "后台登录", description = "账号密码校验，成功后写入会话")
    @PostMapping("/login")
    public ApiResponse<LoginUser> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        LoginUser user = authService.login(request.getUsername(), request.getPassword());
        // 创建会话并写入登录用户；Spring Session 自动将该会话落 Redis，前端凭 SESSION cookie 维持登录态。
        httpRequest.getSession(true).setAttribute(
                com.lx.deploy.common.security.AuthInterceptor.SESSION_KEY, user);
        return ApiResponse.success(user, requestId(httpRequest));
    }

    /**
     * 登出。
     *
     * @param httpRequest 当前 HTTP 请求
     * @return 统一成功响应
     */
    @Operation(summary = "后台登出", description = "失效当前会话")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.success(requestId(httpRequest));
    }

    /**
     * 获取当前登录用户。
     *
     * <p>未登录时返回 4002（由拦截器拦截），前端据此跳转登录页；已登录时返回用户快照。
     * 本接口不在白名单内，必须经过拦截器，因此能可靠反映登录态。</p>
     *
     * @param httpRequest 当前 HTTP 请求
     * @return 当前登录用户，或 4002
     */
    @Operation(summary = "当前登录用户", description = "已登录返回用户快照，未登录返回 4002")
    @GetMapping("/me")
    public ApiResponse<LoginUser> me(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        LoginUser user = session == null ? null
                : (LoginUser) session.getAttribute(
                        com.lx.deploy.common.security.AuthInterceptor.SESSION_KEY);
        return ApiResponse.success(user, requestId(httpRequest));
    }

    /** 从请求属性读取本次请求追踪 ID。 */
    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestTraceFilter.ATTRIBUTE_NAME);
        return requestId == null ? null : requestId.toString();
    }
}
