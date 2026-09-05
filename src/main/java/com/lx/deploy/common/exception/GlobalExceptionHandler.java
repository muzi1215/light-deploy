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

/** 统一处理 Controller 层异常，避免业务接口重复包装错误。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DeployException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    /** 处理可预期的部署业务异常。 */
    public ApiResponse<Void> handleBusiness(DeployException ex, HttpServletRequest request) {
        return ApiResponse.failure(ex.getCode(), ex.getMessage(), requestId(request));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    /** 汇总并返回请求参数校验错误。 */
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
        return ApiResponse.failure(ResponseCode.VALIDATION_ERROR, message, requestId(request));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    /** 记录未处理异常并返回统一系统错误。 */
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
