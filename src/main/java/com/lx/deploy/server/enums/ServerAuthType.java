package com.lx.deploy.server.enums;

import lombok.Getter;

/**
 * SSH 认证类型枚举。
 *
 * <p>数据库只保存稳定的数字编码，业务代码使用枚举区分密码和私钥认证。
 * 两种认证方式的 AES-GCM 加密内容都保存在 server.ciphertext 字段中。</p>
 */
@Getter
public enum ServerAuthType {

    /** 使用 SSH 用户名和密码建立连接。 */
    PASSWORD(1, "密码认证"),
    /** 使用 SSH 用户名和私钥建立连接。 */
    PRIVATE_KEY(2, "私钥认证");

    private final Integer code;
    private final String message;

    /**
     * 初始化 SSH 认证类型。
     *
     * @param code 数据库存储的认证类型编码
     * @param message 面向页面和日志的中文说明
     */
    ServerAuthType(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找 SSH 认证类型。
     *
     * <p>该方法只负责编码转换，不负责校验凭证内容是否为空。新增或修改服务器时，
     * Service 仍需根据认证类型检查密码或私钥内容。</p>
     *
     * @param code 数据库存储的认证类型编码
     * @return 对应认证类型；编码为空或不受支持时返回 null
     */
    public static ServerAuthType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ServerAuthType authType : values()) {
            if (authType.code.equals(code)) {
                return authType;
            }
        }
        return null;
    }
}
