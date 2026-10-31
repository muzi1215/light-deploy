package com.lx.deploy.ssh;

import com.lx.deploy.common.crypto.CredentialCrypto;
import com.lx.deploy.common.enums.server.ServerAuthType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshSession;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.StringReader;
import java.security.KeyPair;
import java.util.Collection;

/**
 * SSH 会话工厂：把 server 表里的一行服务器配置，变成一次已认证的 SSH 会话。
 *
 * <p>本类是凭据明文唯一的出现位置。调用方传入包含密文的 {@link Server} 实体，
 * 工厂在建立连接前才解密，认证完成后不再持有明文引用；明文不写日志、不进异常消息、
 * 不缓存到任何字段（AGENTS.md §7）。</p>
 *
 * <p>认证方式按 server.auth_type 分流：1 密码认证直接把明文交给 SSHD 作为密码标识；
 * 2 私钥认证把明文当作 OpenSSH 或 PKCS#8 格式的私钥文本解析成 {@link KeyPair}。
 * 带口令的私钥不在当前支持范围内，解析失败时给出明确提示。</p>
 *
 * <p>每次 open 创建独立的 SshClient 并随会话一起关闭：SSHD 的客户端会各自持有
 * IO 线程与选择器，独立创建可以让连接生命周期完全绑定在一次任务上，不必处理共享客户端的
 * 引用计数。并发上限由任务执行器的线程池约束，客户端数量不会随任务总量增长。</p>
 *
 * @author lx
 * @since 2026-09-23 02:00:00
 */
@Slf4j
@Component
public class SshSessionFactory {

    /** 私钥解析时使用的资源名，仅用于错误提示，不影响格式识别。 */
    private static final String KEY_RESOURCE_NAME = "ssh-private-key";

    /** 凭据加解密组件，用于在建立连接前解密 server.ciphertext。 */
    private final CredentialCrypto credentialCrypto;

    /** TCP 建连等待时间（毫秒）。 */
    private final int connectTimeoutMs;

    /** 认证等待时间（毫秒）。 */
    private final int authTimeoutMs;

    /** 单条远程命令的等待时间（秒），透传给 SshSession。 */
    private final int commandTimeoutSeconds;

    /** 耗时类远程命令（克隆、构建、镜像操作、发布）的等待时间（秒），透传给 SshSession。 */
    private final int longCommandTimeoutSeconds;

    /** 单条命令输出保留的最大字符数，透传给 SshSession。 */
    private final int maxOutputChars;

    /**
     * 创建 SSH 会话工厂。
     *
     * @param credentialCrypto     平台统一凭据加解密组件
     * @param connectTimeoutMs     TCP 建连等待时间（毫秒），默认 10000
     * @param authTimeoutMs        SSH 认证等待时间（毫秒），默认 10000
     * @param commandTimeoutSeconds 秒级命令等待时间（秒），默认 120
     * @param longCommandTimeoutSeconds 耗时类命令等待时间（秒），默认 1800
     * @param maxOutputChars       单条命令输出保留的最大字符数，默认 20000
     */
    public SshSessionFactory(CredentialCrypto credentialCrypto,
                            @Value("${light-deploy.ssh.connect-timeout-ms:10000}") int connectTimeoutMs,
                            @Value("${light-deploy.ssh.auth-timeout-ms:10000}") int authTimeoutMs,
                            @Value("${light-deploy.ssh.command-timeout-seconds:120}") int commandTimeoutSeconds,
                            @Value("${light-deploy.ssh.long-command-timeout-seconds:1800}") int longCommandTimeoutSeconds,
                            @Value("${light-deploy.ssh.max-output-chars:20000}") int maxOutputChars) {
        this.credentialCrypto = credentialCrypto;
        this.connectTimeoutMs = connectTimeoutMs;
        this.authTimeoutMs = authTimeoutMs;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.longCommandTimeoutSeconds = longCommandTimeoutSeconds;
        this.maxOutputChars = maxOutputChars;
    }

    /**
     * 根据服务器配置建立并认证一条 SSH 会话。
     *
     * <p>调用方必须用 try-with-resources 关闭返回的会话，否则连接与 IO 线程会泄漏。
     * 连接、认证、私钥解析任一环节失败都抛出远程执行异常，异常消息中只包含
     * host / port / 用户名等非敏感信息。</p>
     *
     * @param server 服务器配置实体，必须包含 host、sshPort、username、authType 和 ciphertext 密文
     * @return 已认证的 SSH 会话，可直接执行命令
     * @throws DeployException 服务器配置不完整、凭据无法解密、私钥无法解析或连接认证失败时抛出
     */
    public SshSession open(Server server) {
        // 建连参数先做完整校验，避免把配置错误当成网络故障排查。
        String serverId = server.getId();
        String host = server.getHost();
        String username = server.getUsername();
        Integer port = server.getSshPort();
        if (!StringUtils.hasText(host) || !StringUtils.hasText(username) || port == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "服务器地址、SSH 端口和登录用户不能为空");
        }
        // 认证类型必须受支持，未知编码无法判断凭据是密码还是私钥。
        ServerAuthType authType = ServerAuthType.fromCode(server.getAuthType() == null
                ? null : server.getAuthType().intValue());
        if (authType == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "服务器认证类型必须为 1（密码）或 2（私钥）");
        }
        // 密文缺失时无需尝试连接，直接给出可操作的提示。
        if (!StringUtils.hasText(server.getCiphertext())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "服务器未配置 SSH 凭据，请重新保存密码或私钥");
        }

        // 解密：只在建立连接前执行一次，解密结果不做任何形式的缓存。
        String credential = credentialCrypto.decrypt(server.getCiphertext());

        // 客户端随会话一起关闭，保证一次任务结束后不残留 IO 线程。
        SshClient client = SshClient.setUpDefaultClient();
        // 禁用 ~/.ssh/config 解析：平台是服务端，连哪个 host/port/user 完全由服务器档案决定，
        // 不该被运行账号本地的 Host / ProxyCommand / IdentityFile 规则改写。否则同一份档案
        // 换台机器跑出来的行为不同，运维本地配置会静默覆盖平台意图；且 SSHD 默认会去读该文件，
        // 在受限环境（沙箱 / 无家目录权限的容器）下直接抛 EPERM，表现为「SSH 连接失败」。
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        try {
            client.start();
            // 先建立 TCP 与协议协商，再提交认证标识；顺序与 SSHD 的会话状态机一致。
            ClientSession session = client.connect(username, host, port).verify(connectTimeoutMs).getSession();
            applyIdentity(session, authType, credential);
            session.auth().verify(authTimeoutMs);
            log.info("SSH 连接成功，serverId={}, host={}, port={}, authType={}",
                    serverId, host, port, authType.getMessage());
            return new SshSession(session, client, serverId, host, commandTimeoutSeconds,
                    longCommandTimeoutSeconds, maxOutputChars);
        } catch (DeployException e) {
            // 私钥解析失败等业务异常：先把客户端关掉再原样抛出，保留具体原因。
            closeQuietly(client);
            throw e;
        } catch (Exception e) {
            // 建连或认证失败：关闭客户端，避免半开的连接与线程泄漏。
            closeQuietly(client);
            log.warn("SSH 连接失败，serverId={}, host={}, port={}, authType={}",
                    serverId, host, port, authType.getMessage(), e);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "SSH 连接失败：" + rootMessage(e));
        }
    }

    /**
     * 按认证类型向会话提交身份标识。
     *
     * @param session    已建立但尚未认证的 SSH 会话
     * @param authType   认证类型，密码或私钥
     * @param credential 解密后的凭据明文：密码文本或私钥文本
     * @throws DeployException 私钥文本无法解析为密钥对时抛出
     */
    private void applyIdentity(ClientSession session, ServerAuthType authType, String credential) {
        if (authType == ServerAuthType.PASSWORD) {
            // 密码认证：SSHD 会自行完成密码交换，凭据不会出现在日志中。
            session.addPasswordIdentity(credential);
            return;
        }
        // 私钥认证：一行私钥文本可能对应多个密钥对（例如同时含公钥信息），全部提交。
        for (KeyPair keyPair : loadKeyPairs(credential)) {
            session.addPublicKeyIdentity(keyPair);
        }
    }

    /**
     * 把私钥文本解析为密钥对集合。
     *
     * <p>使用 SSHD 内置的解析器，支持 OpenSSH 新格式与 PKCS#8 未加密私钥。
     * 带口令的私钥需要额外的口令提供者，当前不支持，解析失败时提示用户改用未加密私钥。</p>
     *
     * @param privateKeyText 解密后的私钥文本
     * @return 解析出的密钥对集合，保证非空
     * @throws DeployException 私钥格式不受支持、内容被截断或私钥带有口令时抛出
     */
    private Collection<KeyPair> loadKeyPairs(String privateKeyText) {
        KeyPairResourceParser parser = SecurityUtils.getKeyPairResourceParser();
        try (StringReader reader = new StringReader(privateKeyText)) {
            // 口令提供者传 EMPTY：遇到加密私钥会直接抛错，比静默返回空集合更容易定位。
            Collection<KeyPair> keyPairs = parser.loadKeyPairs(null,
                    NamedResource.ofName(KEY_RESOURCE_NAME), FilePasswordProvider.EMPTY, reader);
            if (keyPairs == null || keyPairs.isEmpty()) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "私钥内容无法解析出密钥对，请确认粘贴的是完整的未加密私钥");
            }
            return keyPairs;
        } catch (DeployException e) {
            throw e;
        } catch (Exception e) {
            // 解析异常信息可能包含文件名或算法细节，但不含私钥内容，可以安全外抛。
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "私钥解析失败：" + rootMessage(e) + "；当前仅支持未加密的 OpenSSH/PKCS#8 私钥");
        }
    }

    /**
     * 静默关闭客户端。
     *
     * <p>用于建连失败后的资源回收，关闭异常只记录不上抛，避免覆盖真正的失败原因。</p>
     *
     * @param client 待关闭的 SSHD 客户端
     */
    private void closeQuietly(SshClient client) {
        try {
            client.close(false);
        } catch (Exception e) {
            log.warn("关闭 SSH 客户端失败", e);
        }
    }

    /**
     * 提取异常链最内层的消息。
     *
     * <p>SSHD 把真实原因（连接被拒、认证失败、主机不可达）包在外层异常里，
     * 只取最内层消息才能让页面提示可读。</p>
     *
     * @param throwable 原始异常
     * @return 最内层异常消息；全部为空时返回异常类名
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        // 沿 cause 链向内查找，直到没有更内层的原因。
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return StringUtils.hasText(message) ? message : current.getClass().getSimpleName();
    }
}
