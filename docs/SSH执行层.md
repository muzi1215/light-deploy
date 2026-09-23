# SSH 执行层文档

> 模块包：`com.lx.deploy.ssh` ｜ 对应数据表：无（消费 `server.ciphertext`） ｜ 状态：已完成并真机验证通过

## 1. 模块定位

SSH 执行层是全平台**唯一的远程操作出口**。构建、发布、健康检查、回滚这些动作最终都要落到「在目标机上执行命令」，这一层负责把 `server` 表里的一行服务器配置变成一次可用的远程执行能力。

- 本层提供：凭据解密、SSH 建连与认证（密码 / 私钥）、命令执行与结果封装、输出脱敏、连接关闭；
- 本层不提供：任务调度、状态流转、日志落库（这些属于任务模块），也不理解任何业务语义（不认识 mvn、docker、systemctl）；
- 本层不落库、不写任务日志：它只负责「执行并把结果如实返回」，是否算失败由上层按业务语义判断。

设计边界的关键判断：**`SshSessionFactory` 是凭据明文唯一出现的地方**。凭据在建立连接前才解密，认证完成后不再持有明文引用，明文不进日志、不进异常消息、不缓存到字段（AGENTS.md §7）。

## 2. 数据表结构

本层不拥有数据表，只读取 `server` 表的以下字段：

| 字段 | 用途 |
| --- | --- |
| `host` / `ssh_port` | 连接地址 |
| `username` | 登录用户 |
| `auth_type` | 认证类型：1 密码、2 私钥，见 `ServerAuthType` |
| `ciphertext` | AES-GCM 密文，建连前由 `CredentialCrypto.decrypt` 解密 |

## 3. 枚举设计

本层不新增枚举，复用 `com.lx.deploy.common.enums.server.ServerAuthType`（1 密码 / 2 私钥）与 `ResponseCode.REMOTE_EXECUTION_ERROR(5001)`。

## 4. 代码结构

```text
com/lx/deploy/ssh/
├── SshSessionFactory.java   # 凭据解密 + 建连认证，返回已认证会话（唯一解密点）
├── SshSession.java          # 已认证会话的命令执行封装（AutoCloseable）
└── SshCommandResult.java    # 单条命令结果 record + 敏感信息脱敏
```

## 5. 接口清单

本层不对外暴露 HTTP 接口（Controller 不直接调用它，只由任务处理器调用）。对外契约是 Java API：

| 方法 | 说明 |
| --- | --- |
| `SshSessionFactory.open(Server server)` | 建立并认证一条会话，失败抛 `REMOTE_EXECUTION_ERROR`；调用方必须 try-with-resources |
| `SshSession.exec(String command)` | 执行一条命令，返回 `SshCommandResult`（含退出码、stdout、stderr、是否截断） |
| `SshSession.close()` | 关闭会话与客户端，异常只记录不上抛 |
| `SshCommandResult.success()` | 退出码是否为 0 |
| `SshCommandResult.summary()` | 单行摘要（退出码 + 输出首行），用于日志 |

## 6. 核心业务规则与代码逻辑

### 6.1 建连与认证流程

```java
// 1. 参数先校验完整，避免把配置错误当成网络故障排查
ServerAuthType authType = ServerAuthType.fromCode(server.getAuthType()...);
// 2. 解密：只在建立连接前执行一次，结果不做任何形式的缓存
String credential = credentialCrypto.decrypt(server.getCiphertext());
// 3. 客户端随会话一起关闭，保证一次任务结束后不残留 IO 线程
SshClient client = SshClient.setUpDefaultClient();
client.start();
ClientSession session = client.connect(username, host, port).verify(connectTimeoutMs).getSession();
applyIdentity(session, authType, credential);   // 密码或私钥
session.auth().verify(authTimeoutMs);
```

失败时统一 `closeQuietly(client)` 再抛异常：半开的连接如果不关，SSHD 的 IO 线程会随任务累积泄漏。

### 6.2 两种认证方式的处理差异

- **密码认证**：`session.addPasswordIdentity(明文)`，由 SSHD 完成密钥交换；
- **私钥认证**：用 `SecurityUtils.getKeyPairResourceParser()` 解析私钥文本为 `KeyPair`，再 `addPublicKeyIdentity`。解析器传 `FilePasswordProvider.EMPTY`——遇到带口令的私钥会直接抛错，比静默返回空集合更容易定位；错误提示里明确告知「当前仅支持未加密的 OpenSSH/PKCS#8 私钥」。

### 6.3 为什么不共享 SshClient

`SshSession.close()` 会同时关闭会话与客户端。共享客户端就必须引入引用计数，才能判断「最后一个会话关闭时才关客户端」。当前并发上限由任务执行器的线程池约束（默认 6），客户端数量不会随任务总量增长，因此选择「一次操作一个客户端」，让连接生命周期完全绑定在一次任务上，代码简单且不会泄漏。

### 6.4 退出码语义与脱敏

- **非零退出码不抛异常**：`test -d`、`systemctl is-active`、`grep` 返回非零本身就是有效结论，是否算失败必须由上层按业务语义决定；
- 只有通道打开失败、等待超时、读写异常才抛 `REMOTE_EXECUTION_ERROR`；
- 命令输出在返回前统一经过 `SshCommandResult.maskSecrets()`：按 `password|token|secret|access_key|private_key` 等键名做正则遮盖。这是兜底策略，不能替代「命令本身不要回显凭据」的编写约束。

### 6.5 超时与输出上限

全部走配置，不硬编码：

```yaml
light-deploy:
  ssh:
    connect-timeout-ms: 10000       # TCP 建连等待
    auth-timeout-ms: 10000          # 认证等待
    command-timeout-seconds: 120    # 单条命令等待
    max-output-chars: 20000         # 单条命令输出保留上限，超出截断
```

`exec` 在等待通道关闭时把 `TIMEOUT` 也放进可等待事件集：命令挂死时会命中超时分支抛出明确异常，而不是无限阻塞工作线程。

## 7. 与其他模块的关系

- **服务器模块**：消费方。只读取 `server` 实体（含密文），不修改任何服务器数据；
- **任务模块**：使用者。`ServerTestTaskHandler` 通过本层执行探活命令，并把结果写进任务日志；
- **后续构建/发布模块**：都通过本层执行远程命令，不各自写 SSH 代码；
- 本层不认识业务：它不知道什么是构建、什么是发布。

## 8. 已知边界与后续演进

- **私钥口令**：不支持带口令的私钥，需要时引入自定义 `FilePasswordProvider` 从凭据表读取口令；
- **SFTP 未实现**：`sshd-sftp` 依赖已引入，但 SFTP 上传下载（发布产物传输）尚未落地，将在发布模块一并实现；
- **无连接池**：每次操作新建客户端，连接建立开销（约 100~300ms）在单次任务中可忽略；若后续出现大量短连接场景（如批量健康检查）再考虑引入会话复用；
- **不解析交互式输出**：只做 exec 通道的一次性执行，不支持交互式 shell、不支持长驻进程（构建、发布日志靠命令输出重定向或文件轮询，见后续构建模块设计）；
- **主机密钥未校验**：`SshSessionFactory` 没有调用 `setServerKeyVerifier(...)`，因此沿用 SSHD 客户端的默认放行策略，不比对本地 `known_hosts`，中间人无法被发现。内网可信环境可接受；接入公网或合规要求时需要显式设置校验器（按已知指纹或 known_hosts 比对），这是一处明确的安全债。
