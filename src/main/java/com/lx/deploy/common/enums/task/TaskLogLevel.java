package com.lx.deploy.common.enums.task;

import lombok.Getter;

import java.util.Arrays;

/**
 * 任务日志级别枚举。
 *
 * <p>只保留 INFO 与 ERROR 两级：任务日志面向页面展示执行过程，级别过多会让配色、
 * 过滤和检索都变复杂；而"警告"类信息在部署场景里要么可忽略（归 INFO）、要么已经
 * 是失败（归 ERROR），没有独立存在的中间态。任务日志同时会同步打印到应用日志
 * （见 TaskLogService），两级也让服务端日志里的故障检索更直接：只看 ERROR 即可。</p>
 *
 * <p>对应数据库字段 lds_task_log.log_level。</p>
 *
 * @author lx
 * @since 2026-09-05 19:32:43
 */
@Getter
public enum TaskLogLevel {

    /** 普通执行过程信息，含可忽略的提示与非致命异常。 */
    INFO("INFO", "信息"),
    /** 错误信息，通常导致任务失败。 */
    ERROR("ERROR", "错误");

    private final String code;
    private final String message;

    /**
     * 初始化日志级别。
     *
     * @param code 数据库存储的日志级别编码
     * @param message 面向页面和日志的中文说明
     */
    TaskLogLevel(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找日志级别。
     *
     * @param code 数据库或请求传入的日志级别编码
     * @return 对应日志级别；编码为空或不受支持时返回 null
     */
    public static TaskLogLevel fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(level -> level.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
