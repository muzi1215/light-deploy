package com.lx.deploy.task.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 任务日志级别枚举。
 *
 * <p>任务执行日志保存在 lds_task_log.content 中，与服务器本地 logs 文件互相独立：
 * 本地日志面向运维排障，任务日志面向页面展示执行过程。</p>
 */
@Getter
public enum TaskLogLevel {

    /** 调试信息，用于问题定位。 */
    DEBUG("DEBUG", "调试"),
    /** 普通执行过程信息。 */
    INFO("INFO", "信息"),
    /** 警告信息，不影响任务继续执行。 */
    WARN("WARN", "警告"),
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
