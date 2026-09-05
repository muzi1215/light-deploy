package com.lx.deploy.task.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 异步任务状态枚举。
 *
 * <p>任务状态流转为单向推进：QUEUED（排队）→ RUNNING（执行中）→ SUCCESS / FAILED（终态），
 * RUNNING 状态可被人工或系统取消进入 CANCELED（终态）。终态任务不允许再次流转。</p>
 */
@Getter
public enum TaskStatus {

    /** 排队中，等待任务执行器调度。 */
    QUEUED("QUEUED", "排队中"),
    /** 执行中，由任务执行器持有并更新进度。 */
    RUNNING("RUNNING", "执行中"),
    /** 执行成功，终态。 */
    SUCCESS("SUCCESS", "成功"),
    /** 执行失败，终态，失败原因记录在 error_message。 */
    FAILED("FAILED", "失败"),
    /** 已取消，终态。 */
    CANCELED("CANCELED", "已取消");

    private final String code;
    private final String message;

    /**
     * 初始化任务状态。
     *
     * @param code 数据库存储的任务状态编码
     * @param message 面向页面和日志的中文说明
     */
    TaskStatus(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找任务状态。
     *
     * @param code 数据库或请求传入的任务状态编码
     * @return 对应任务状态；编码为空或不受支持时返回 null
     */
    public static TaskStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(status -> status.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断状态是否为终态。
     *
     * <p>终态任务（SUCCESS/FAILED/CANCELED）不允许再次流转，也不允许删除以外的修改。</p>
     *
     * @param code 任务状态编码；为空时按非终态处理
     * @return 终态返回 true
     */
    public static boolean isFinal(String code) {
        return code != null && (SUCCESS.code.equals(code) || FAILED.code.equals(code) || CANCELED.code.equals(code));
    }
}
