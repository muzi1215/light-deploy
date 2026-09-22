package com.lx.deploy.common.enums.project;

import lombok.Getter;

import java.util.Arrays;

/**
 * 部署策略枚举。
 *
 * <p>默认策略为 SINGLE_TARGET，即一个项目在一个环境只绑定一台服务器。
 * 多副本项目可选择 ALL_AT_ONCE 全量发布或 ROLLING 按批次滚动发布，
 * 任一批次失败后停止后续批次。</p>
 *
 * <p>对应数据库字段 environment.deploy_strategy。</p>
 *
 * @author lx
 * @since 2026-09-05 19:32:43
 */
@Getter
public enum DeployStrategy {

    /** 单目标部署，一个环境只部署到一台服务器。 */
    SINGLE_TARGET("SINGLE_TARGET", "单目标部署"),

    /** 全量发布，所有目标主机同时更新。 */
    ALL_AT_ONCE("ALL_AT_ONCE", "全量发布"),

    /** 滚动发布，按批次大小逐批更新目标主机。 */
    ROLLING("ROLLING", "滚动发布");

    private final String code;
    private final String message;

    /**
     * 初始化部署策略。
     *
     * @param code 数据库存储的策略编码
     * @param message 面向页面和日志的中文说明
     */
    DeployStrategy(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找部署策略。
     *
     * @param code 数据库或请求传入的策略编码
     * @return 对应部署策略；编码为空或不受支持时返回 null
     */
    public static DeployStrategy fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(strategy -> strategy.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
