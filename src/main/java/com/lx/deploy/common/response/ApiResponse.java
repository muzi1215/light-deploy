package com.lx.deploy.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 统一接口响应结构。 */
@Schema(description = "统一接口响应")
public record ApiResponse<T>(
        @Schema(description = "业务状态码，0表示成功") Integer code,
        @Schema(description = "响应消息") String message,
        @Schema(description = "响应数据") T data,
        @Schema(description = "响应时间") LocalDateTime timestamp,
        @Schema(description = "请求追踪ID") String requestId) {

    /**
     * 创建包含业务数据的成功响应。
     *
     * <p>方法使用 {@link ResponseCode#SUCCESS} 的状态码和默认消息，并记录响应生成时间。
     * 调用方应传入请求过滤器生成的 requestId，便于前端问题与后端日志关联。</p>
     *
     * @param data 接口需要返回的业务数据
     * @param requestId 当前请求的追踪ID
     * @param <T> 业务数据类型
     * @return 包含业务数据的统一成功响应
     */
    public static <T> ApiResponse<T> success(T data, String requestId) {
        return of(ResponseCode.SUCCESS, data, requestId);
    }

    /**
     * 创建不包含业务数据的成功响应。
     *
     * @param requestId 当前请求的追踪ID
     * @return data 为 null 的统一成功响应
     */
    public static ApiResponse<Void> success(String requestId) {
        return success(null, requestId);
    }

    /**
     * 使用自定义业务状态码和消息创建失败响应。
     *
     * @param code 业务状态码
     * @param message 面向调用方的错误消息
     * @param requestId 当前请求的追踪ID
     * @param <T> 响应数据泛型，失败响应的数据固定为 null
     * @return 统一失败响应
     */
    public static <T> ApiResponse<T> failure(int code, String message, String requestId) {
        return new ApiResponse<>(code, message, null, LocalDateTime.now(), requestId);
    }

    /**
     * 使用响应码枚举中的默认消息创建失败响应。
     *
     * @param responseCode 响应码枚举，提供状态码和默认消息
     * @param requestId 当前请求的追踪ID
     * @param <T> 响应数据泛型
     * @return 统一失败响应
     */
    public static <T> ApiResponse<T> failure(ResponseCode responseCode, String requestId) {
        return of(responseCode, null, requestId);
    }

    /**
     * 使用响应码枚举和当前业务场景消息创建失败响应。
     *
     * @param responseCode 响应码枚举，提供稳定的业务状态码
     * @param message 当前业务场景的具体错误说明
     * @param requestId 当前请求的追踪ID
     * @param <T> 响应数据泛型
     * @return 统一失败响应
     */
    public static <T> ApiResponse<T> failure(ResponseCode responseCode, String message, String requestId) {
        return new ApiResponse<>(responseCode.getCode(), message, null, LocalDateTime.now(), requestId);
    }

    /**
     * 根据响应码枚举创建统一响应对象。
     *
     * @param responseCode 响应码枚举
     * @param data 需要返回的业务数据
     * @param requestId 当前请求的追踪ID
     * @param <T> 业务数据类型
     * @return 完整的统一响应对象
     */
    public static <T> ApiResponse<T> of(ResponseCode responseCode, T data, String requestId) {
        return new ApiResponse<>(responseCode.getCode(), responseCode.getMessage(), data,
                LocalDateTime.now(), requestId);
    }
}
