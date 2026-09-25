package com.lx.deploy.ssh;

import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import lombok.Getter;

/**
 * 远程命令执行超时。
 *
 * <p>与普通远程执行异常的区别是：本异常**携带超时前已收到的输出**。
 * 超长命令（首次构建下载依赖、镜像拉取）卡住时，已产出的那几十行输出是唯一的定位线索——
 * 原来超时直接丢弃缓冲区，页面上只剩「执行超时」一句，无法判断是「慢」还是「卡死」。</p>
 *
 * <p>异常消息只带命令与超时秒数，完整输出由调用方通过 {@link #getPartialResult()} 自行落日志，
 * 避免把几千行输出塞进任务失败原因字段。</p>
 *
 * @author lx
 * @since 2026-09-25 05:40:00
 */
@Getter
public class SshCommandTimeoutException extends DeployException {

    /** 触发超时的等待秒数。 */
    private final int timeoutSeconds;

    /** 超时瞬间已收到的输出快照，退出码固定为 -1。 */
    private final SshCommandResult partialResult;

    /**
     * 创建超时异常。
     *
     * @param timeoutSeconds 触发超时的等待秒数
     * @param partialResult  超时前已收到的输出快照
     */
    public SshCommandTimeoutException(int timeoutSeconds, SshCommandResult partialResult) {
        super(ResponseCode.REMOTE_EXECUTION_ERROR,
                "远程命令执行超时（" + timeoutSeconds + " 秒）：" + commandOf(partialResult));
        this.timeoutSeconds = timeoutSeconds;
        this.partialResult = partialResult;
    }

    /**
     * 取快照中的命令文本，为空时降级为占位符，保证异常消息可读。
     *
     * @param partialResult 输出快照
     * @return 命令文本或占位符
     */
    private static String commandOf(SshCommandResult partialResult) {
        return partialResult == null || partialResult.command() == null ? "命令" : partialResult.command();
    }
}
