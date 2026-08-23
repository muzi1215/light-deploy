package com.lx.deploy.common.response;

import lombok.Getter;

/** 统一响应码。业务模块可以在对应号段继续补充场景码。 */
@Getter
public enum ResponseCode {

    SUCCESS(200, "success"),
    BUSINESS_ERROR(4000, "业务处理失败"),
    VALIDATION_ERROR(4001, "请求参数校验失败"),
    UNAUTHORIZED(4002, "未登录或登录已失效"),
    FORBIDDEN(4003, "无权执行当前操作"),
    DATA_NOT_FOUND(4004, "数据不存在"),
    DATA_CONFLICT(4005, "数据状态冲突"),
    SYSTEM_ERROR(5000, "系统内部错误"),
    REMOTE_EXECUTION_ERROR(5001, "远程命令执行失败"),
    EXTERNAL_SERVICE_ERROR(5002, "外部服务调用失败");

    private final int code;
    private final String message;

    /**
     * 创建响应码枚举项。
     *
     * @param code 对外稳定的业务状态码
     * @param message 未提供场景化消息时使用的默认消息
     */
    ResponseCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

}
