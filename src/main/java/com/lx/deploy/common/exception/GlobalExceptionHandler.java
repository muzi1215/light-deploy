package com.lx.deploy.common.exception;

import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.common.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 统一处理 Controller 层异常，避免业务接口重复包装错误。
 *
 * <p>三类异常各有日志口径：可预期的 {@link DeployException} 与参数校验异常记 WARN 且不打堆栈
 * （它们不是缺陷，堆栈只会淹没真正的故障），未预期异常记 ERROR 并保留完整堆栈。三者<b>都必须落日志</b>
 * ——静默返回统一响应会让请求失败在控制台与日志文件中完全不可见，前端持有的 `requestId`
 * 也就失去了「拿去日志里检索同一次请求」的意义（AGENTS.md §14）。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理可预期的部署业务异常。
     *
     * <p>只记一行 WARN（业务码 + 请求路径 + 原因），不打印堆栈：这类异常是「预期内的拒绝」，
     * 例如参数不合法、状态冲突、不可回滚，堆栈对定位没有帮助。requestId 由 MDC 自动带入日志格式，
     * 不需要手工拼接。</p>
     *
     * @param ex      业务异常，携带业务码与面向用户展示的原因
     * @param request 当前请求，用于记录请求路径
     * @return 统一失败响应，requestId 回填供前端与日志关联检索
     */
    @ExceptionHandler(DeployException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusiness(DeployException ex, HttpServletRequest request) {
        log.error("业务异常，uri={}，code={}，原因：{}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        return ApiResponse.failure(ex.getCode(), ex.getMessage(), requestId(request));
    }

    /**
     * 汇总并返回请求参数校验错误。
     *
     * <p>同样只记 WARN 不打堆栈：校验失败是调用方参数问题，字段级原因已足够定位。
     * 日志里的原因与响应体中的 message 是同一份拼接结果，便于对照。</p>
     *
     * @param ex      校验异常（{@link MethodArgumentNotValidException} 或 {@link BindException}）
     * @param request 当前请求，用于记录请求路径
     * @return 统一失败响应，message 为「字段: 原因」的拼接结果
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(Exception ex, HttpServletRequest request) {
        String message;
        if (ex instanceof MethodArgumentNotValidException validation) {
            message = validation.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining(", "));
        } else {
            BindException validation = (BindException) ex;
            message = validation.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining(", "));
        }
        log.error("参数校验失败，uri={}，原因：{}", request.getRequestURI(), message);
        return ApiResponse.failure(ResponseCode.VALIDATION_ERROR, message, requestId(request));
    }

    /**
     * 记录未处理异常并返回统一系统错误。
     *
     * <p>这是兜底分支：走到这里说明异常不在预期范围内，因此必须记 ERROR 并保留完整堆栈，
     * 这是唯一能定位代码缺陷的地方。异常详情不向前端暴露，只回传 requestId 供检索。</p>
     *
     * @param ex      未预期的异常
     * @param request 当前请求，用于记录请求路径
     * @return 统一失败响应，不携带异常详情
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request error, uri={}", request.getRequestURI(), ex);
        return ApiResponse.failure(ResponseCode.SYSTEM_ERROR, requestId(request));
    }

    /** 从当前请求中读取追踪ID。 */
    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestTraceFilter.ATTRIBUTE_NAME);
        return requestId == null ? null : requestId.toString();
    }
}
