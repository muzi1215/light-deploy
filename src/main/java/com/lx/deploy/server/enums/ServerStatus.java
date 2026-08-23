package com.lx.deploy.server.enums;

import lombok.Getter;

/**
 * 服务器启用状态枚举。
 *
 * <p>数据库保存数字编码，Java 代码使用枚举表达业务语义，避免在业务代码中散落魔法数字。</p>
 */
@Getter
public enum ServerStatus {

    /** 服务器允许被平台连接和参与部署。 */
    ENABLED(1, "启用"),
    /** 服务器暂时不允许被平台选为部署目标。 */
    DISABLED(0, "停用");

    private final Integer code;
    private final String message;

    /**
     * 初始化服务器状态枚举。
     *
     * @param code 数据库存储值
     * @param message 面向页面和日志的中文说明
     */
    ServerStatus(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码转换为枚举。
     *
     * @param code 数据库存储的状态编码
     * @return 对应状态；传入未知编码时返回 null
     */
    public static ServerStatus fromCode(Integer code) {
        for (ServerStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
