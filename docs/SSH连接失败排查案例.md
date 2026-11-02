# SSH 连接失败排查案例（macOS 本地网络隐私拦截）

> 案例类型：环境/操作系统层网络拦截 ｜ 模块：`com.lx.deploy.ssh`（SshSessionFactory）
> 发生时间：2026-11-02 ｜ 平台运行环境：macOS（Spring Boot 4.1.0，平台在 Mac 裸跑非容器）
> 结论：根因不是平台代码、不是网络不通、不是代理，而是 **macOS 15+（Sequoia）本地网络隐私权限** 按「负责应用」拦截了局域网连接。

---

## 1. 问题现象

在平台「服务器 → 测试连接」时，单台目标机始终连接失败，任务日志报：

```text
ERROR[系统] 任务执行失败：SSH 连接失败：No route to host
```

完整异常链（节选）：

```text
org.apache.sshd.common.SshException:
  DefaultConnectFuture[root@/192.168.64.132:22]: Failed (NoRouteToHostException)
  ...
  at org.apache.sshd.client.config.hosts.DefaultConfigFileHostEntryResolver...
  at org.apache.sshd.common.io.nio2.Nio2Connector.connect(Nio2Connector.java:88)
```

但同机其它手段都是通的：

- `ping 192.168.64.132` → 0% packet loss；
- `nc -zv 192.168.64.132 22` → `Connection to 192.168.64.132 port 22 [tcp/ssh] succeeded!`；
- `traceroute -n 192.168.64.132` → 1 跳直达（192.168.64.132 0.788 ms）；
- 平台连公网 MySQL / Redis / AIops（公网 IP）全部正常，唯独连这台局域网目标机失败。

特征矛盾点：**网络层全通，但平台进程内建连失败**。

---

## 2. 环境信息

| 项 | 值 |
| --- | --- |
| 平台进程 | 在 macOS 上裸跑（`java` 进程，非 Docker 容器），`lsof -nP -iTCP:2027` 可见 LISTEN |
| 平台依赖 | Apache SSHD `sshd-core-2.18.0`，仅含 `io/nio2/`，默认 `Nio2Connector` |
| 目标机 | `192.168.64.132:22`（macOS 本地虚拟机，非物理邻居） |
| Mac 网卡拓扑 | `en0` 在 `192.168.3.0/24`（WiFi 主网卡）；`192.168.64.0/24` 挂在 `bridge100`（虚拟机桥接，Mac 上的 VM 子网） |
| 启动平台的应用 | IntelliJ IDEA（由 IDEA 内 Run 启动 `mvn spring-boot:run`） |

`route get 192.168.64.132` 输出：`interface: bridge100`，无 `utun`（VPN）介入，说明路由本身正常。

---

## 3. 排查过程（含 5 次误判与推翻）

### 误判 1：VPN 接管了路由
- 推测：VPN 把 `192.168.64.0/24` 引到了不存在的隧道。
- 推翻：`route get 192.168.64.132` 显示走 `bridge100`、列表里无 `utun`，VPN 未介入。

### 误判 2：Docker 容器网络隔离
- 推测：平台跑在容器里，容器网络命名空间看不到宿主 `bridge100` 子网。
- 推翻：目标机上 `docker: command not found`；`lsof` 明确显示 `java` 进程 **裸跑在 Mac**，并非容器。

### 误判 3：当时没联网
- 推测：触发测试连接的瞬间网络抖动。
- 推翻：日志时间戳 `21:25:45` 与 `nc` 测试时间 `21:17` 同期，`nc`/`ping` 当时均通。

### 误判 4：IFSCOPE 源地址问题
- 推测：macOS 按源地址范围（IFSCOPE）选路，源地址不在 `bridge100` 子网导致不可达。
- 推翻：`ifconfig bridge100` 显示有 `inet 192.168.64.1`，源地址属于该子网，选路正常。

### 误判 5：SOCKS 系统代理劫持
- 证据误导：用户在终端跑 `new java.net.Socket("192.168.64.132", 22).close()` 时，异常栈出现
  `java.net.SocksSocketImpl.connect(...)`，像是被 `socksProxyHost` 代理接管。
- 推测：SSHD 客户端走了系统 SOCKS 代理，导致连不通；于是提出「在 SshSessionFactory 里配一个绕过代理的 socket 工厂强制直连」。
- 推翻（关键）：反编译 `sshd-core-2.18.0.jar` 确认：
  - 只有 `io/nio2/`，无 `io/mina/`；默认 `Nio2ServiceFactoryFactory` → `Nio2Connector`；
  - `Nio2Connector.connect` 仅做 `AsynchronousSocketChannel.open() → connect(address)`，**天然不认 `socksProxyHost`/`http.proxyHost`**；
  - `SshClient.setSocketFactory` 在 2.18 已移除；没有任何 sshd 类读取这些系统属性。
  - 即 SSHD 的 NIO2 通道**永远是直连**，不存在被 SOCKS 劫持的可能。
  - 那条 `SocksSocketImpl` 只是 JDK `java.net.Socket` 的默认实现类名（即使直连也会打印该类名），与是否真走代理无关 —— 是个误导性的类名。

### 最终定位（决定性证据）
1. 用 `jshell` 直接 `new Socket(host,22).close()`（用户态裸 Socket）**成功** → 用户终端进程有局域网权限；
2. 平台进程用 SSHD `Nio2Connector` 异步直连 **失败**，且同样报 `NoRouteToHostException`（`SshSessionFactory.java:150` 日志）；
3. 同一台 Mac、同一个目标、同一时刻：**用户终端进程能连、平台 java 进程不能连**。
4. 结合 macOS Sequoia（15+）**本地网络隐私（Local Network Privacy）** 的行为特征：
   - 按「负责应用（responsible app）」判定，未获「本地网络」授权的应用发起的**局域网**连接会被系统拦截并回报 `NoRouteToHostException`；
   - **回环地址（127.0.0.1）与公网地址豁免**，只有私有局域网（`192.168.x`、`10.x`、`172.16-31.x`）会被拦；
   - 这完美解释了「连公网 MySQL/Redis/AIops 正常、唯独连 `192.168.64.132` 失败」。

**根因**：启动平台 java 进程的「负责应用」（IntelliJ IDEA）**没有「本地网络」系统授权**，导致平台进程对局域网目标机的 TCP 建连被 macOS 内核直接拒绝。

---

## 4. 根因分析

- macOS 14 起引入、15（Sequoia）默认启用的 **Local Network Privacy**，把「发现/访问本地网络设备」纳入权限管理。
- 权限绑定在**启动网络连接的应用**上，而不是绑定在 java 二进制上。IDEA 启动的 JVM 子进程继承 IDEA 的权限状态。
- 系统对未授权应用的局域网连接**不弹窗、不报错到应用层语义**，而是直接让内核建连失败，表现为 `NoRouteToHostException` —— 这与「网络真的不通」的报错完全一致，极具迷惑性。
- 因此 `ping`/`nc`/`traceroute`（终端 app 已授权）全通，但平台（IDEA 未授权）进程内建连失败。

> 关于 SOCKS 代理误判的延伸：SSHD 2.18 的 NIO2 连接器根本不读取 `socksProxyHost`，所以「配直连 socket 工厂」的方案在技术上是不必要的（且 SSHD 2.18 已移除 `setSocketFactory` API）。排查到此应排除代理因素，转向操作系统权限层。

---

## 5. 解决方案

### 方案 A（推荐）：给「负责应用」补本地网络授权
1. 打开 **系统设置 → 隐私与安全性 → 本地网络**；
2. 找到启动平台的那个应用（本例是 **IntelliJ IDEA**），将开关打开；
3. **完全退出并重启该应用**（授权在进程启动时读取，不重启不生效）；
4. 在 IDEA 内重新启动平台，再点「测试连接」即可成功。

### 方案 B：从已获授权的 Terminal 启动平台
- 终端 app 通常默认拥有本地网络权限。直接在 Terminal 跑：
  ```bash
  mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=2027
  ```
- 由 Terminal 启动的 JVM 继承 Terminal 的授权，局域网连接即通。

### 方案 C（生产部署环境通常无需处理）
- 服务器部署（systemd / Docker / 真实 Linux 目标机）不存在 macOS 本地网络隐私机制；
- 该问题**只在开发者本机 macOS 上调试时**出现，不影响线上发布链路。

> 注意：本问题**不需要改任何平台代码**。SSHD 直连行为正确，拦截发生在操作系统内核，代码侧无解、也无需解。

---

## 6. 验证结果

- 重启并获得「本地网络」授权的 IDEA 启动平台后，「测试连接」对 `192.168.64.132` 成功；
- 同一进程下，此前失败的目标机连接恢复正常，公网连接不受影响；
- 确认 SSHD 走 `Nio2Connector` 直连，无代理、无 socket 工厂改动。

---

## 7. 经验教训 / 避坑清单

1. **`NoRouteToHostException` 在 macOS 上 ≠ 网络不通**：当 `ping`/`nc` 都通、但某个特定进程内建连失败时，优先怀疑 **macOS 本地网络隐私授权**，而不是路由/VPN/代理。
2. **区分「用户终端」与「应用进程」的权限**：诊断要在**失败进程所在的应用上下文**里验证，而不仅是用户终端。本案 `jshell` 成功但平台失败，正是权限绑定在「负责应用」上的铁证。
3. **`SocksSocketImpl` 类名是误导**：JDK `java.net.Socket` 默认实现就叫 `SocksSocketImpl`，即使直连也会出现在栈里，不能据此断定走了 SOCKS 代理。
4. **SSHD 2.18 不读系统代理**：`Nio2Connector` 直接 `AsynchronousSocketChannel` 直连；「配绕过代理的 socket 工厂」在 2.18 既无 API 也无必要。
5. **回环与公网豁免、局域网被拦**：这是 macOS 本地网络隐私的典型拆分，可用来快速判断是不是这个坑（公网通 + 局域网不通 = 高度疑似）。
6. **授权要重启应用才生效**：macOS 的本地网络授权在进程启动时读取，改设置后必须完全退出重启应用。

---

## 8. 相关定位（本次排查涉及的文件/类）

| 位置 | 说明 |
| --- | --- |
| `src/main/java/com/lx/deploy/ssh/SshSessionFactory.java` | `client.connect(username, host, port).verify(connectTimeoutMs)`（约 136/150 行）；无代理、无 socket 工厂；已显式禁用 `ProxyCommand` |
| `sshd-core-2.18.0.jar` | 仅 `io/nio2/`，`Nio2Connector.connect` 仅 `AsynchronousSocketChannel.open() → connect(address)` |
| `docs/SSH执行层.md` | SSH 执行层模块文档（建连/认证/超时口径） |

> 本案结论为**环境/OS 层经验**，不构成平台代码约束，故未写入 AGENTS.md；仅在此案留存，供后续本机 macOS 调试时快速对照。
