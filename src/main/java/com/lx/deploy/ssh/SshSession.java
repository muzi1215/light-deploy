package com.lx.deploy.ssh;

import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一次已认证 SSH 会话的命令执行封装。
 *
 * <p>生命周期与一次远程操作绑定，必须由使用方以 try-with-resources 方式关闭，
 * 保证连接和底层 SshClient 不泄漏。本类不是线程安全的：一个会话同一时刻
 * 只允许一个线程执行命令，任务执行器已按"同一服务器同一时刻一个任务"约束并发。</p>
 *
 * <p>使用方式：由 {@link SshSessionFactory#open} 创建，调用 {@link #exec} 执行命令，
 * 执行结果的非零退出码不在本类抛异常，只有连接与通道层面的失败才抛
 * {@link ResponseCode#REMOTE_EXECUTION_ERROR}。</p>
 *
 * @author lx
 * @since 2026-09-06 01:20:00
 */
@Slf4j
public class SshSession implements AutoCloseable {

    /** 已认证的底层 SSH 会话。 */
    private final ClientSession session;

    /** 会话所属客户端，关闭会话后需要一并关闭以释放线程与选择器。 */
    private final org.apache.sshd.client.SshClient client;

    /** 单条命令的等待超时秒数。 */
    private final int commandTimeoutSeconds;

    /** 命令输出的最大保留字节数，超出部分截断，避免大输出撑爆内存和日志。 */
    private final int maxOutputBytes;

    /** 目标服务器 ID，仅用于日志定位。 */
    @Getter
    private final String serverId;

    /** 目标地址，仅用于日志定位。 */
    @Getter
    private final String host;

    /**
     * 构造已就绪的 SSH 会话包装。
     *
     * @param session               已完成认证的底层会话
     * @param client                创建该会话的 SshClient，关闭时一并释放
     * @param serverId              目标服务器 ID
     * @param host                  目标地址，用于日志
     * @param commandTimeoutSeconds 单条命令等待超时秒数，必须大于 0
     * @param maxOutputBytes        命令输出保留上限字节数，必须大于 0
     */
    SshSession(ClientSession session, org.apache.sshd.client.SshClient client, String serverId, String host,
               int commandTimeoutSeconds, int maxOutputBytes) {
        this.session = session;
        this.client = client;
        this.serverId = serverId;
        this.host = host;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * 在目标服务器上执行一条命令。
     *
     * <p>通过 exec 通道执行并等待通道关闭，读取标准输出与标准错误。
     * 非零退出码视为业务结果正常返回，由调用方判断；
     * 通道打开失败、等待超时或读写异常抛出远程执行异常。</p>
     *
     * @param command 要在远端执行的命令文本，不允许为空
     * @return 命令执行结果，输出已按上限截断并完成敏感信息脱敏
     * @throws DeployException 命令为空、通道执行失败或等待超时
     */
    public SshCommandResult exec(String command) {
        if (command == null || command.isBlank()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "远程命令不能为空");
        }
        // exec 通道每次执行独立创建，命令之间互不影响输出缓冲。
        try (ChannelExec channel = session.createExecChannel(command);
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             ByteArrayOutputStream err = new ByteArrayOutputStream()) {

            channel.setOut(out);
            channel.setErr(err);
            // open 之后等待通道关闭；TIMEOUT 也作为可等待事件，避免命令挂死时无限阻塞。
            channel.open().verify(commandTimeoutSeconds * 1000L);
            Set<ClientChannelEvent> events = channel.waitFor(
                    EnumSet.of(ClientChannelEvent.CLOSED, ClientChannelEvent.TIMEOUT),
                    commandTimeoutSeconds * 1000L);
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "远程命令执行超时（" + commandTimeoutSeconds + " 秒）：" + command);
            }

            String stdout = limit(out.toString(StandardCharsets.UTF_8));
            String stderr = limit(err.toString(StandardCharsets.UTF_8));
            // getExitStatus 在远端未回传退出码时为 null，用 -1 表达"未知"，与"成功返回 0"区分。
            Integer exitStatus = channel.getExitStatus();
            int exitCode = exitStatus == null ? -1 : exitStatus;
            SshCommandResult raw = new SshCommandResult(command, exitCode, stdout, stderr,
                    out.size() > maxOutputBytes || err.size() > maxOutputBytes);
            // 输出可能包含远端环境变量或命令行中的凭据，返回前统一脱敏。
            return new SshCommandResult(command, raw.exitCode(),
                    SshCommandResult.maskSecrets(raw.stdout()),
                    SshCommandResult.maskSecrets(raw.stderr()), raw.truncated());
        } catch (IOException e) {
            log.warn("远程命令执行失败，serverId={}, host={}, command={}", serverId, host, command, e);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "远程命令执行失败：" + e.getMessage());
        }
    }

    /**
     * 关闭 SSH 会话并释放底层客户端。
     *
     * <p>会话与客户端分别关闭且互不阻断：会话关闭失败仍要尝试关闭客户端，
     * 否则线程池会随任务执行逐步泄漏。关闭异常只记录不抛出，
     * 避免覆盖任务本身的失败原因。</p>
     */
    @Override
    public void close() {
        try {
            session.close(false);
        } catch (Exception e) {
            log.warn("关闭 SSH 会话失败，serverId={}", serverId, e);
        }
        try {
            client.close(false);
        } catch (Exception e) {
            log.warn("关闭 SSH 客户端失败，serverId={}", serverId, e);
        }
    }

    /**
     * 限制命令输出长度。
     *
     * @param text 原始输出文本
     * @return 超过上限时截断并追加省略标记，否则原样返回
     */
    private String limit(String text) {
        if (text == null) {
            return "";
        }
        // 按字符数近似控制长度，避免为统计字节数再复制一份字符串。
        return text.length() > maxOutputBytes ? text.substring(0, maxOutputBytes) + "...(输出已截断)" : text;
    }
}
