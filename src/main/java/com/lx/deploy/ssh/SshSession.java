package com.lx.deploy.ssh;

import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.common.util.ShellQuote;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
 * <p>命令按耗时分成两类，超时是两套值而不是一套：
 * {@link #exec} 用于探活、检目录、systemctl 这类秒级命令；
 * {@link #execLong} 用于克隆仓库、Maven 构建、镜像构建与推送这类分钟级命令。
 * 首次构建要从仓库下载上百 MB 依赖，用秒级超时会把它误判成卡死。</p>
 *
 * @author lx
 * @since 2026-09-06 01:20:00
 */
@Slf4j
public class SshSession implements AutoCloseable {

    /** 实时输出队列容量：只用于削峰，满队列直接丢行并计数，绝不阻塞 SSHD 读线程。 */
    private static final int STREAM_QUEUE_CAPACITY = 4096;

    /** 等待实时输出消费线程排空的时间上限（毫秒）。 */
    private static final long STREAM_DRAIN_WAIT_MS = 3000L;

    /** 已认证的底层 SSH 会话。 */
    private final ClientSession session;

    /** 会话所属客户端，关闭会话后需要一并关闭以释放线程与选择器。 */
    private final org.apache.sshd.client.SshClient client;

    /** 单条命令的等待超时秒数。 */
    private final int commandTimeoutSeconds;

    /** 耗时类命令的等待超时秒数（克隆、构建、镜像操作、发布）。 */
    @Getter
    private final int longCommandTimeoutSeconds;

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
     * @param session                  已完成认证的底层会话
     * @param client                   创建该会话的 SshClient，关闭时一并释放
     * @param serverId                 目标服务器 ID
     * @param host                     目标地址，用于日志
     * @param commandTimeoutSeconds    秒级命令的等待超时秒数，必须大于 0
     * @param longCommandTimeoutSeconds 耗时类命令的等待超时秒数，必须大于 0
     * @param maxOutputBytes           命令输出保留上限字节数，必须大于 0
     */
    SshSession(ClientSession session, org.apache.sshd.client.SshClient client, String serverId, String host,
               int commandTimeoutSeconds, int longCommandTimeoutSeconds, int maxOutputBytes) {
        this.session = session;
        this.client = client;
        this.serverId = serverId;
        this.host = host;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.longCommandTimeoutSeconds = longCommandTimeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * 以默认（秒级）超时执行一条命令。
     *
     * @param command 要在远端执行的命令文本，不允许为空
     * @return 命令执行结果，输出已按上限截断并完成敏感信息脱敏
     * @throws DeployException 命令为空、通道执行失败或等待超时
     */
    public SshCommandResult exec(String command) {
        return exec(command, commandTimeoutSeconds, null);
    }

    /**
     * 以指定超时执行一条命令。
     *
     * @param command        要在远端执行的命令文本，不允许为空
     * @param timeoutSeconds 等待超时秒数，非正数时回落到默认超时
     * @return 命令执行结果
     * @throws DeployException 命令为空、通道执行失败或等待超时
     */
    public SshCommandResult exec(String command, int timeoutSeconds) {
        return exec(command, timeoutSeconds, null);
    }

    /**
     * 以长命令超时执行一条命令（克隆、构建、镜像操作、发布等分钟级命令）。
     *
     * @param command 要在远端执行的命令文本，不允许为空
     * @return 命令执行结果
     * @throws DeployException 命令为空、通道执行失败或等待超时
     */
    public SshCommandResult execLong(String command) {
        return exec(command, longCommandTimeoutSeconds, null);
    }

    /**
     * 以长命令超时执行一条命令，并把标准输出按行实时回调。
     *
     * <p>用于构建这类长任务：页面能看到构建进度滚动，而不是干等十几分钟后一次性看到结果。</p>
     *
     * @param command             要在远端执行的命令文本，不允许为空
     * @param stdoutLineConsumer  标准输出的逐行消费者，在独立线程中调用（不得阻塞）
     * @return 命令执行结果
     * @throws DeployException 命令为空、通道执行失败或等待超时
     */
    public SshCommandResult execLong(String command, Consumer<String> stdoutLineConsumer) {
        return exec(command, longCommandTimeoutSeconds, stdoutLineConsumer);
    }

    /**
     * 在目标服务器上执行一条命令，可指定超时并实时回传标准输出。
     *
     * <p>通过 exec 通道执行并等待通道关闭，读取标准输出与标准错误。
     * 非零退出码视为业务结果正常返回，由调用方判断；
     * 通道打开失败或读写异常抛出远程执行异常，**等待超时抛
     * {@link SshCommandTimeoutException} 并带上已收到的输出快照**，
     * 便于调用方把卡住前的日志落下来定位。</p>
     *
     * @param command            要在远端执行的命令文本，不允许为空
     * @param timeoutSeconds     等待超时秒数，非正数时回落到默认超时
     * @param stdoutLineConsumer 标准输出的逐行消费者；为 null 表示不实时回传
     * @return 命令执行结果，输出已按上限截断并完成敏感信息脱敏
     * @throws DeployException 命令为空、通道执行失败或等待超时
     */
    public SshCommandResult exec(String command, int timeoutSeconds, Consumer<String> stdoutLineConsumer) {
        if (command == null || command.isBlank()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "远程命令不能为空");
        }
        // 非正数视为未指定：避免调用方传 0 时变成"立刻就超时"这种难以理解的失败。
        int timeout = timeoutSeconds > 0 ? timeoutSeconds : commandTimeoutSeconds;
        OutputBuffer out = new OutputBuffer();
        OutputBuffer err = new OutputBuffer();
        LineStream stream = stdoutLineConsumer == null ? null : new LineStream(stdoutLineConsumer);
        // exec 通道每次执行独立创建，命令之间互不影响输出缓冲。
        try (ChannelExec channel = session.createExecChannel(command)) {
            // 需要实时回传时给 stdout 套一层分流：既按行回调，也保留全量文本供最终结果与超时快照使用。
            channel.setOut(stream == null ? out : new LineForwardingOutputStream(out, stream));
            // 构建/推送等命令的进度与错误都走 stderr（BuildKit 行为），只回传 stdout 会看不到关键日志，
            // 因此 stderr 也套一层分流：既保留全量文本供结果与超时快照，也逐行回传实时日志。
            channel.setErr(stream == null ? err : new LineForwardingOutputStream(err, stream));
            if (stream != null) {
                stream.start();
            }
            // open 之后等待通道关闭；TIMEOUT 也作为可等待事件，避免命令挂死时无限阻塞。
            channel.open().verify(timeout * 1000L);
            Set<ClientChannelEvent> events = channel.waitFor(
                    EnumSet.of(ClientChannelEvent.CLOSED, ClientChannelEvent.TIMEOUT), timeout * 1000L);
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                // 先关通道再取快照：通道还开着时 SSHD 读线程仍在写缓冲，且远端进程仍在产出。
                closeChannelQuietly(channel);
                SshCommandResult partial = snapshot(command, out, err, -1);
                log.warn("远程命令执行超时，serverId={}, host={}, timeout={}s, 已收到输出 {} 字节，command={}",
                        serverId, host, timeout, partial.stdout().length(), command);
                throw new SshCommandTimeoutException(timeout, partial);
            }
            SshCommandResult snapshot = snapshot(command, out, err, exitCode(channel));
            // 输出可能包含远端环境变量或命令行中的凭据，返回前统一脱敏。
            return new SshCommandResult(command, snapshot.exitCode(),
                    SshCommandResult.maskSecrets(snapshot.stdout()),
                    SshCommandResult.maskSecrets(snapshot.stderr()), snapshot.truncated());
        } catch (IOException e) {
            log.warn("远程命令执行失败，serverId={}, host={}, command={}", serverId, host, command, e);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "远程命令执行失败：" + e.getMessage());
        } finally {
            // 排空已入队的输出行：保证超时/结束时页面上的日志是完整的，且顺序与命令实际输出一致。
            if (stream != null) {
                stream.stop();
            }
        }
    }

    /**
     * 读取通道退出码。
     *
     * <p>{@code getExitStatus} 在远端未回传退出码时为 null，用 -1 表达"未知"，与"成功返回 0"区分。</p>
     *
     * @param channel 已关闭或已结束的 exec 通道
     * @return 退出码，未知时为 -1
     */
    private int exitCode(ChannelExec channel) {
        Integer exitStatus = channel.getExitStatus();
        return exitStatus == null ? -1 : exitStatus;
    }

    /**
     * 取当前输出快照并组装结果对象。
     *
     * @param command  命令文本
     * @param out      标准输出缓冲
     * @param err      标准错误缓冲
     * @param exitCode 退出码
     * @return 结果对象（未脱敏，脱敏由调用方在返回前统一处理）
     */
    private SshCommandResult snapshot(String command, OutputBuffer out, OutputBuffer err, int exitCode) {
        String stdout = limit(out.text());
        String stderr = limit(err.text());
        return new SshCommandResult(command, exitCode, stdout, stderr,
                out.size() > maxOutputBytes || err.size() > maxOutputBytes);
    }

    /**
     * 静默关闭超时通道。
     *
     * @param channel 待关闭的 exec 通道
     */
    private void closeChannelQuietly(ChannelExec channel) {
        try {
            channel.close(false);
        } catch (Exception e) {
            log.debug("关闭超时通道失败，serverId={}，原因：{}", serverId, e.getMessage());
        }
    }

    /**
     * 通过 SFTP 把远端文件下载到本地指定路径。
     *
     * <p>JAR / STATIC 发布的「构建机 → 平台 → 目标机」中转链路的下载端：产物不需要在构建机与目标机之间
     * 直接互信，统一由平台落盘一份临时副本再上传，避免在所有目标机上都配置构建机的免密通道。
     * 本地父目录不存在时自动创建；同名文件直接覆盖。</p>
     *
     * @param remotePath 远端文件绝对路径，不允许为空
     * @param localPath  本地落盘路径，父目录会自动创建，已存在则覆盖
     * @return 实际写入的字节数
     * @throws DeployException 路径为空、SFTP 通道打开失败、远端文件不存在或读写失败时抛出
     */
    public long sftpDownload(String remotePath, Path localPath) {
        if (remotePath == null || remotePath.isBlank() || localPath == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "SFTP 下载路径不能为空");
        }
        // 通道与输入流都在 try 内声明：下载结束立刻关闭，不占用服务器的 SFTP 子系统连接数。
        try (SftpClient sftp = openSftpClient();
             InputStream in = sftp.read(remotePath)) {
            Path parent = localPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return Files.copy(in, localPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("SFTP 下载失败，serverId={}, host={}, remotePath={}", serverId, host, remotePath, e);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "SFTP 下载失败：" + rootMessage(e));
        }
    }

    /**
     * 通过 SFTP 把本地文件上传到远端指定路径。
     *
     * <p>上传前不自动创建远端父目录：目录属于部署动作的一部分，由调用方显式执行
     * {@code mkdir -p} 后再上传，这样目录权限与属主在部署脚本里可见、可控。</p>
     *
     * @param localPath  本地文件绝对路径，必须已存在
     * @param remotePath 远端目标绝对路径，不允许为空
     * @throws DeployException 路径为空、本地文件不存在、SFTP 通道打开失败或上传失败时抛出
     */
    public void sftpUpload(Path localPath, String remotePath) {
        if (localPath == null || remotePath == null || remotePath.isBlank()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "SFTP 上传路径不能为空");
        }
        if (!Files.isRegularFile(localPath)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "待上传的本地文件不存在：" + localPath);
        }
        try (SftpClient sftp = openSftpClient()) {
            // put 默认使用 Write/Create/Truncate 模式，远端同名文件会被整体替换。
            sftp.put(localPath, remotePath);
        } catch (IOException e) {
            log.warn("SFTP 上传失败，serverId={}, host={}, remotePath={}", serverId, host, remotePath, e);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "SFTP 上传失败：" + rootMessage(e));
        }
    }

    /**
     * 上传大文件：优先用 exec 通道的 {@code cat > 文件} 流式管道，失败时回落 SFTP。
     *
     * <p><b>为什么不用 SFTP 打底</b>：SFTP 是有窗口和逐包确认的应用层协议，
     * 在高延迟链路上吞吐会被往返时间压住——实测同一条链路上 74 MB 走 SFTP 要 325 s
     * （约 0.23 MB/s），而裸管道由 TCP 自己做流控，通常能贴近实际带宽。
     * 产物发布是平台里唯一的大文件传输场景，值得为它单独走一条快路径。</p>
     *
     * <p><b>为什么保留回落</b>：管道依赖远端有 {@code cat}（POSIX 必备）且 exec 通道
     * 允许写标准输入。个别加固过的机器会禁掉这两者，此时退回 SFTP 至少还能发布成功。
     * 两种方式写完都有 {@code sha256} 校验兜底，不会静默写入半截文件。</p>
     *
     * @param localPath  本地文件绝对路径，必须已存在
     * @param remotePath 远端目标绝对路径，不允许为空
     * @return 实际使用的传输方式描述，供任务日志展示
     * @throws DeployException 路径非法、本地文件不存在，或两种方式都失败时抛出
     */
    public String uploadLarge(Path localPath, String remotePath) {
        if (localPath == null || remotePath == null || remotePath.isBlank()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "上传路径不能为空");
        }
        if (!Files.isRegularFile(localPath)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "待上传的本地文件不存在：" + localPath);
        }
        try {
            streamUpload(localPath, remotePath);
            return "流式管道";
        } catch (Exception e) {
            log.warn("流式上传失败，回落 SFTP，serverId={}, host={}, remotePath={}, 原因：{}",
                    serverId, host, remotePath, e.getMessage());
            sftpUpload(localPath, remotePath);
            return "SFTP 回落";
        }
    }

    /**
     * 通过 exec 通道把本地文件流直接写进远端文件。
     *
     * <p>写完必须关闭标准输入：远端 {@code cat} 要读到 EOF 才会退出，
     * 不关就会一直等到超时。写入前远端已经截断目标文件，因此调用方一律
     * 先写临时名再 {@code mv}，避免中途失败留下半截产物。</p>
     *
     * @param localPath  本地文件绝对路径
     * @param remotePath 远端目标绝对路径
     * @throws Exception 通道打开失败、写入失败、超时或远端非零退出时抛出
     */
    private void streamUpload(Path localPath, String remotePath) throws Exception {
        long timeoutMs = longCommandTimeoutSeconds * 1000L;
        OutputBuffer err = new OutputBuffer();
        try (ChannelExec channel = session.createExecChannel("cat > " + ShellQuote.quote(remotePath));
             InputStream in = Files.newInputStream(localPath)) {
            channel.setOut(new OutputBuffer());
            channel.setErr(err);
            channel.open().verify(timeoutMs);
            try (OutputStream remote = channel.getInvertedIn()) {
                in.transferTo(remote);
            }
            Set<ClientChannelEvent> events = channel.waitFor(
                    EnumSet.of(ClientChannelEvent.CLOSED, ClientChannelEvent.TIMEOUT), timeoutMs);
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                closeChannelQuietly(channel);
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "流式上传超时（" + longCommandTimeoutSeconds + " s）");
            }
            int code = exitCode(channel);
            if (code != 0) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "流式上传失败，远端退出码 " + code + "：" + limit(err.text()));
            }
        }
    }

    /**
     * 打开一个绑定在当前会话上的 SFTP 客户端。
     *
     * <p>每次调用新建一条子系统通道，调用方必须以 try-with-resources 关闭，
     * 否则会耗尽服务器允许的 SFTP 通道数。</p>
     *
     * @return 已就绪的 SFTP 客户端
     * @throws DeployException 通道打开失败时抛出
     */
    private SftpClient openSftpClient() {
        try {
            return SftpClientFactory.instance().createSftpClient(session);
        } catch (IOException e) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "打开 SFTP 通道失败：" + rootMessage(e));
        }
    }

    /**
     * 提取异常链最内层的消息。
     *
     * <p>SFTP 异常常把真实原因（文件不存在、权限不足）包在外层，取最内层消息才可读。</p>
     *
     * @param throwable 原始异常
     * @return 最内层异常消息；为空时返回异常类名
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
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

    /**
     * 命令输出的字节缓冲。
     *
     * <p>写入方是 SSHD 读线程，读取方是任务线程（正常结束取结果、超时取快照），
     * 二者存在并发可能，因此读写都加锁——超时路径必须在通道关闭后立刻拿到一份完整快照。</p>
     */
    private static final class OutputBuffer extends OutputStream {

        /** 实际承载字节的缓冲。 */
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        /**
         * 取当前内容的文本快照。
         *
         * @return UTF-8 解码后的文本
         */
        synchronized String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        /**
         * 取当前已写入的字节数，用于判断输出是否被截断。
         *
         * @return 字节数
         */
        synchronized int size() {
            return buffer.size();
        }
    }

    /**
     * 把标准输出按行分流：一份写回全量缓冲，一份按行交给实时消费者。
     *
     * <p>按字节累积到换行符再解码，避免多字节字符（中文构建日志）被分片写坏；
     * 行尾的 {@code \r\n} 一并去掉，否则页面每行会多一个空行。</p>
     */
    private static final class LineForwardingOutputStream extends OutputStream {

        /** 换行符。 */
        private static final byte LF = '\n';

        /** 全量输出缓冲，保留完整文本供结果与快照使用。 */
        private final OutputStream tee;

        /** 逐行消费者。 */
        private final Consumer<String> lineConsumer;

        /** 尚未凑满一行的尾部字节。 */
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        LineForwardingOutputStream(OutputStream tee, Consumer<String> lineConsumer) {
            this.tee = tee;
            this.lineConsumer = lineConsumer;
        }

        @Override
        public void write(int b) throws IOException {
            tee.write(b);
            pending.write(b);
            if (b == LF) {
                flushLine();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            tee.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                pending.write(b[i]);
                if (b[i] == LF) {
                    flushLine();
                }
            }
        }

        /** 把 pending 中的整行交给消费者并清空。 */
        private void flushLine() {
            String line = pending.toString(StandardCharsets.UTF_8);
            pending.reset();
            int end = line.length();
            if (end > 0 && line.charAt(end - 1) == '\n') {
                end--;
            }
            if (end > 0 && line.charAt(end - 1) == '\r') {
                end--;
            }
            lineConsumer.accept(line.substring(0, end));
        }
    }

    /**
     * 实时输出的搬运工：SSHD 读线程只入队，独立线程负责写日志。
     *
     * <p>任务日志要落库，单条写入毫秒级；若直接在 SSHD 读线程里写库，远端输出突发时会把读线程拖慢，
     * TCP 窗口被填满后反压到远端进程，构建反而变慢甚至假死。因此中间加一层队列削峰，
     * 队列满时丢行并计数，宁可少几行日志也不阻塞命令执行。</p>
     */
    private final class LineStream implements Consumer<String> {

        /** 待写日志的行队列。 */
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(STREAM_QUEUE_CAPACITY);

        /** 下游消费者（任务日志写入）。 */
        private final Consumer<String> sink;

        /** 消费线程。 */
        private final Thread worker;

        /** 队列溢出丢弃的行数，结束时汇总告警。 */
        private final AtomicInteger dropped = new AtomicInteger();

        /** 消费线程的运行标志，置 false 后把队列排空即退出。 */
        private volatile boolean running = true;

        LineStream(Consumer<String> sink) {
            this.sink = sink;
            this.worker = new Thread(this::drain, "ssh-stdout-stream-" + serverId);
            // 守护线程：任务异常终止时不阻止 JVM 退出。
            this.worker.setDaemon(true);
        }

        /** 启动消费线程。 */
        void start() {
            worker.start();
        }

        @Override
        public void accept(String line) {
            if (!queue.offer(line)) {
                dropped.incrementAndGet();
            }
        }

        /** 结束消费线程并等待队列排空，保证日志完整且顺序正确。 */
        void stop() {
            running = false;
            try {
                worker.join(STREAM_DRAIN_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int lost = dropped.get();
            if (lost > 0) {
                log.warn("实时输出队列溢出，丢弃 {} 行日志，serverId={}", lost, serverId);
            }
        }

        /** 消费循环：轮询取行写日志，队列空且已停止时退出。 */
        private void drain() {
            while (running || !queue.isEmpty()) {
                String line;
                try {
                    line = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (line == null) {
                    continue;
                }
                try {
                    sink.accept(line);
                } catch (RuntimeException e) {
                    // 日志写入失败不影响命令执行：远端命令已经在跑，不能因为日志问题把它判成失败。
                    log.warn("实时输出写入失败，serverId={}，原因：{}", serverId, e.getMessage());
                }
            }
        }
    }
}
