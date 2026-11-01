package com.lx.deploy.common.security;

import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录态拦截器。
 *
 * <p>对所有未放行的请求校验会话中是否存在登录用户；不存在则抛 {@code UNAUTHORIZED(4002)}，
 * 由全局异常处理器统一返回。登录用户信息写入 {@link CurrentUser} 供业务层读取，
 * 请求结束（含异常路径）在 {@link #afterCompletion} 中清理，避免线程复用串号。</p>
 *
 * <p>登录、登出、接口文档等白名单路径由 {@code WebConfig} 配置排除，不经过本拦截器。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 会话中存放登录用户的键。 */
    public static final String SESSION_KEY = "LOGIN_USER";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession(false);
        LoginUser user = session == null ? null : (LoginUser) session.getAttribute(SESSION_KEY);
        if (user == null) {
            throw new DeployException(ResponseCode.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        CurrentUser.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUser.clear();
    }
}
