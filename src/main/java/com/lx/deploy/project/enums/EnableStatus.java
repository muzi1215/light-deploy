package com.lx.deploy.project.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 业务启用状态枚举。
 *
 * <p>项目、项目环境部署目标等业务表共用的启用状态。
 * 只表示资源是否允许参与部署，不能用来表达开发、测试或生产环境。</p>
 */
@Getter
public enum EnableStatus {

    /** 启用，允许参与构建和发布。 */
    ENABLED("ENABLED", "启用"),
    /** 停用，保留数据但不允许参与构建和发布。 */
    DISABLED("DISABLED", "停用");

    private final String code;
    private final String message;

    /**
     * 初始化启用状态。
     *
     * @param code 数据库存储的状态编码
     * @param message 面向页面和日志的中文说明
     */
    EnableStatus(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找启用状态。
     *
     * @param code 数据库或请求传入的状态编码
     * @return 对应状态；编码为空或不受支持时返回 null
     */
    public static EnableStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(status -> status.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
