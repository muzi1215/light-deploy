package com.lx.deploy.server.enums;

import lombok.Getter;

/**
 * 服务器环境标签枚举。
 *
 * <p>环境标签不是服务器运行状态，也不代替项目环境绑定关系；它只表示服务器的默认归属环境，
 * 用于服务器列表展示和基础筛选。正式发布目标仍应以项目环境目标配置为准。</p>
 */
@Getter
public enum EnvironmentTag {

    /** 开发环境服务器。 */
    DEV(1, "开发环境"),
    /** 测试环境服务器。 */
    TEST(2, "测试环境"),
    /** 生产环境服务器。 */
    PROD(3, "生产环境");

    private final Integer code;
    private final String message;

    /**
     * 初始化环境标签枚举。
     *
     * @param code 数据库存储的稳定编码
     * @param message 面向页面和日志的中文说明
     */
    EnvironmentTag(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码转换为环境枚举。
     *
     * @param code 数据库存储的环境标签编码
     * @return 对应环境；传入未知编码时返回 null
     */
    public static EnvironmentTag fromCode(Integer code) {
        for (EnvironmentTag tag : values()) {
            if (tag.code.equals(code)) {
                return tag;
            }
        }
        return null;
    }
}
