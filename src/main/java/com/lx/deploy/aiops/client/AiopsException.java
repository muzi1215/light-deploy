package com.lx.deploy.aiops.client;

/**
 * AIops 调用异常。
 *
 * <p>刻意与 {@code DeployException} 区分：AIops 是外部系统，它失败不是业务校验失败，
 * 不能把对端的 500 或超时翻译成面向用户的「参数不合法」。上层捕获本异常后按
 * {@code light-deploy.aiops.fail-open} 决定是继续发布还是阻断。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
public class AiopsException extends RuntimeException {

    /** 对端业务码；-1 表示未拿到业务响应（网络/超时/非 200 HTTP）。 */
    private final int code;

    public AiopsException(int code, String message) {
        super(message);
        this.code = code;
    }

    public AiopsException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    public int getCode() {
        return code;
    }
}
