package com.lx.deploy.common.exception;

import com.lx.deploy.common.response.ResponseCode;
import lombok.Getter;

/** 可预期的业务异常。 */
@Getter
public class DeployException extends RuntimeException {

    /**
     * -- GETTER --
     * 获取业务状态码。
     */
    private final int code;

    /** 使用通用业务错误码创建异常。 */
    public DeployException(String message) {
        this(ResponseCode.BUSINESS_ERROR, message);
    }

    /** 使用自定义业务码创建异常。 */
    public DeployException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 使用响应码默认消息创建异常。 */
    public DeployException(ResponseCode responseCode) {
        this(responseCode.getCode(), responseCode.getMessage());
    }

    /** 使用响应码和场景消息创建异常。 */
    public DeployException(ResponseCode responseCode, String message) {
        this(responseCode.getCode(), message);
    }

}
