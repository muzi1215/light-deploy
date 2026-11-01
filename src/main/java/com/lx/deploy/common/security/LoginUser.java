package com.lx.deploy.common.security;

import java.io.Serializable;

/**
 * 登录用户快照，存入 HttpSession（Spring Session 落 Redis）。
 *
 * <p>只保留展示与鉴权必要字段，绝不携带密码哈希等敏感信息。
 * 后续接入统一权限中心后，{@code id} 可平滑替换为 external_user_id。</p>
 */
public record LoginUser(String id, String username, String nickname) implements Serializable {

    /** 无参构造仅供反序列化使用，业务代码不应调用。 */
    public LoginUser {
        // record 自带全参构造；这里不额外定义无参构造，序列化由 Spring Session 的 JDK 序列化完成。
    }
}
