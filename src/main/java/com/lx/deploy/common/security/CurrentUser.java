package com.lx.deploy.common.security;

import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;

/**
 * 当前登录用户上下文。
 *
 * <p>由 {@link AuthInterceptor} 在请求进入时从会话取出并写入 ThreadLocal，
 * 请求结束后清理；业务代码（构建/发布鉴权等）通过本类读取当前操作者，
 * 不依赖请求对象透传。异步任务线程不在请求链路内，不应读取本上下文。</p>
 */
public final class CurrentUser {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    /** 写入当前登录用户（请求开始时由拦截器调用）。 */
    public static void set(LoginUser loginUser) {
        HOLDER.set(loginUser);
    }

    /** 读取当前登录用户，未登录返回 null。 */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 清理 ThreadLocal（请求结束时由拦截器调用，必须执行以防线程复用串号）。 */
    public static void clear() {
        HOLDER.remove();
    }

    /** 当前登录用户主键（user 表 UUID）；未登录返回 null。 */
    public static String getUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.id();
    }

    /** 当前登录用户名；未登录返回 null。 */
    public static String getUsername() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.username();
    }

    /**
     * 强制要求已登录，未登录直接抛 {@code UNAUTHORIZED}。
     *
     * @return 当前登录用户
     * @throws DeployException 未登录时抛出
     */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new DeployException(ResponseCode.UNAUTHORIZED, "请先登录");
        }
        return user;
    }
}
