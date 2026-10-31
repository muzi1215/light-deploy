package com.lx.deploy.deployment.executor;

import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAR 运行时发布服务：固定工作目录上的原地替换 + systemd 重启。
 *
 * <p>形态见 AGENTS.md §3.1：工作目录固定不变（不按版本建子目录），发版是替换目录里的 jar，
 * systemd 单元只在首次部署生成、之后绝不重写——运维手工调过的 {@code -Xmx}、
 * {@code --spring.config.location} 必须被尊重。因此「初始化」与「更新」是两个严格分离的动作，
 * 本服务里 {@code ensureInitialized} 只在工作目录还没有 jar 时才真正建单元。</p>
 *
 * <p>发版时序不可调换（AGENTS.md §24.7）：备份旧 jar → 投放新 jar → <b>复核 SHA256</b> →
 * 写 VERSION → 刷新 deploy.conf → {@code systemctl restart}。
 * 校验与写标识都在重启之前：此刻发现 jar 损坏还能无损还原，一旦重启就变成了线上事故。
 * 校验失败先还原备份再抛异常。</p>
 *
 * <p>本服务只负责「在一台机器上完成一次替换」，不负责批次编排与状态回调。</p>
 *
 * @author lx
 * @since 2026-09-27 19:40:00
 */
@Slf4j
@Service
public class JarDeployService {

    /** 平台内置 JAR 部署脚本在 classpath 中的目录。 */
    private static final String SCRIPT_DIR = "scripts/jar-deploy/";

    /** 初始化脚本：建立工作目录并注册 systemd 服务。 */
    private static final String SCRIPT_INIT = "jar-init.py";

    /** 更新脚本：留在目标机供运维手工应急更新。 */
    private static final String SCRIPT_UPDATE = "jar-update.py";

    /** 回滚脚本：留在目标机供运维手工应急回滚。 */
    private static final String SCRIPT_FALLBACK = "jar-fallback.py";

    /** JAR 工作目录下存放配置文件的子目录名，jar 与配置分离部署时使用。 */
    private static final String DEFAULT_CONFIG_DIR = "config";

    /** 默认配置文件名，用于 systemd 的 --spring.config.location。 */
    private static final String DEFAULT_CONFIG_FILE = "application.yml";

    /** 默认激活的 Spring profile。 */
    private static final String DEFAULT_PROFILES = "prod";

    /**
     * 工作目录下的版本标识文件名。
     *
     * <p>与构建机产物持久目录下的同名文件一起，构成「这台机器是哪个版本」的依据。
     * 两边口径必须一致，且一律写平台原始版本号——它要能被机器比对。</p>
     */
    private static final String VERSION_MARKER = "VERSION";

    /** 供目标机脚本读取的服务信息文件名，位于工作目录的 exec 子目录下。 */
    private static final String DEPLOY_CONF = "deploy.conf";

    /** 部署支撑组件：任务日志、产物下载与通用工具。 */
    private final DeploySupport support;

    /** SSH 会话工厂：建立到目标机与构建机的已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /** 服务器 Mapper：读取构建机档案以下载随版本转存的配置文件。 */
    private final ServerMapper serverMapper;

    /**
     * 创建 JAR 发布服务。
     *
     * @param support           部署支撑组件
     * @param sshSessionFactory SSH 会话工厂
     * @param serverMapper      服务器 Mapper
     */
    public JarDeployService(DeploySupport support, SshSessionFactory sshSessionFactory,
                            ServerMapper serverMapper) {
        this.support = support;
        this.sshSessionFactory = sshSessionFactory;
        this.serverMapper = serverMapper;
    }

    /**
     * 在目标机上完成一次 jar 替换并重启服务。
     *
     * <p>发布与回滚走同一条路径：区别只是 {@code ctx.release()} 指向的版本不同
     * （回滚时由编排层解析成 {@code old_version} 对应的版本记录）。</p>
     *
     * @param ctx 目标级发布上下文
     * @throws DeployException 产物缺失、下载/投放失败、完整性校验失败或 systemd 操作失败时抛出
     */
    public void deploy(DeployContext ctx) {
        ReleaseRecord release = ctx.release();
        String artifactPath = release.getArtifactPath();
        if (!StringUtils.hasText(artifactPath)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + ctx.targetVersion() + " 没有产物路径，无法按 JAR 运行时发布");
        }
        String artifactFileName = support.baseName(artifactPath);
        // 固定工作目录：运行配置显式给出优先，未配置时回落到「部署根目录 / 项目编码」。
        String workDir = support.normalize(ctx.config().getWorkDir(),
                support.deployBase(ctx.server()) + "/" + ctx.project().getCode());
        String serviceName = StringUtils.hasText(ctx.config().getServiceName())
                ? ctx.config().getServiceName() : ctx.project().getCode();
        String configDirName = StringUtils.hasText(ctx.config().getConfigDir())
                ? ctx.config().getConfigDir() : DEFAULT_CONFIG_DIR;
        String configFileName = StringUtils.hasText(ctx.config().getConfigFile())
                ? ctx.config().getConfigFile() : DEFAULT_CONFIG_FILE;
        String profiles = StringUtils.hasText(ctx.config().getSpringProfilesActive())
                ? ctx.config().getSpringProfilesActive() : DEFAULT_PROFILES;

        List<Path> localFiles = new ArrayList<>();
        try {
            Path jarFile = support.downloadArtifact(ctx.task(), release, artifactPath,
                    artifactFileName, ctx.deployment());
            localFiles.add(jarFile);
            Map<String, Path> configFiles = downloadConfigFiles(ctx, artifactPath);
            localFiles.addAll(configFiles.values());

            try (SshSession session = sshSessionFactory.open(ctx.server())) {
                String javaBin = StringUtils.hasText(ctx.config().getJavaBin())
                        ? ctx.config().getJavaBin() : detectJavaBin(session);
                ensureInitialized(ctx, session, workDir, serviceName, artifactFileName,
                        javaBin, ctx.config().getJvmOptions(), profiles, configDirName, configFileName);
                uploadConfigFiles(ctx, session, workDir + "/" + configDirName, configFiles);
                // jar 名沿用工作目录里已有的名字：同一实例不该出现两个 jar，
                // 产物文件名带版本号时也不会在工作目录里越堆越多。
                String jarName = resolveJarName(session, workDir, artifactFileName);
                String backupPath;
                if (artifactUpToDate(ctx, session, workDir, jarName)) {
                    // 目标机上的 jar 已经是本次要发的那一个（重复发布、失败重试都会命中）：
                    // 74 MB 在 1.8 Mbps 链路上要传 5 分钟，而比对摘要只是一次目标机本地 sha256sum。
                    // 此时既不备份也不上传——被替换者与替换者是同一个包，备份没有意义。
                    support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                            "目标机已存在相同产物（SHA256 " + ctx.release().getSha256() + "），跳过上传与备份");
                    backupPath = null;
                } else {
                    // 备份先于覆盖：备份下来的是「被换下去的那个包」，同时把它的版本标识一并存下，
                    // 使每个备份都能对应到平台版本号，而不只是一个无法解读的时间戳。
                    backupPath = backupCurrentJar(ctx, session, workDir, jarName);
                    String tmpName = jarName + ".uploading";
                    long size = jarFile.toFile().length();
                    support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                            "开始投放 " + jarName + "（" + (size > 0 ? support.readableSize(size) : "未知大小")
                                    + "）到 " + workDir);
                    long startedAt = System.currentTimeMillis();
                    String method = session.uploadLarge(jarFile, workDir + "/" + tmpName);
                    long seconds = Math.max(1L, (System.currentTimeMillis() - startedAt) / 1000L);
                    support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                            "投放完成（" + method + "，耗时 " + seconds + " s，"
                                    + support.transferRate(size, seconds) + "）");
                    SshCommandResult move = session.exec(
                            "mv -f " + workDir + "/" + tmpName + " " + workDir + "/" + jarName);
                    if (!move.success()) {
                        throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                                "替换 jar 失败：" + support.firstLine(move.stderr()));
                    }
                }
                // 校验放在重启之前：jar 已上盘、服务尚未重启，此刻发现损坏可以无损还原。
                verifyJarDigest(ctx, session, workDir, jarName, backupPath);
                // 标识与磁盘对齐后再重启：重启失败时磁盘上已经是新版本，
                // 标识若停留在旧版本会与磁盘错位，反而误导排查。
                writeVersionMarker(ctx, session, workDir);
                refreshDeployConfVersion(ctx, session, workDir);
                restartService(ctx, session, serviceName);
            }
        } finally {
            for (Path file : localFiles) {
                support.deleteQuietly(file);
            }
        }
    }

    // ==================== 工作目录初始化 ====================

    /**
     * 初始化 JAR 工作目录，并把内置脚本投放到目标机。
     *
     * <p>判定是否首次部署的依据是工作目录里是否已有 jar：没有就视为首次，由脚本建立目录结构、
     * 写入 systemd 单元（jar 与配置分离）、留下 deploy.conf 与 update/fallback 脚本。
     * 已经初始化过则直接返回，绝不重写 systemd 单元——运维手工调整过的启动参数必须被尊重。</p>
     *
     * <p>与是否首次无关，exec/ 下的三份内置脚本每次发布都重新投放：
     * 它们是平台资产，版本必须随平台走，否则目标机会长期停留在一份旧脚本上，
     * 而运维应急发版/回滚恰恰依赖这几份脚本。</p>
     *
     * @param ctx          发布上下文
     * @param session      已连接的 SSH 会话
     * @param workDir      目标机固定工作目录
     * @param serviceName  systemd 服务名
     * @param jarName      jar 文件名
     * @param javaBin      java 可执行文件路径
     * @param jvmOptions   JVM 参数，可为空
     * @param profiles     spring.profiles.active
     * @param configDir    配置目录名
     * @param configFile   配置文件名
     * @throws DeployException 目标机缺少 python3 或初始化脚本执行失败时抛出
     */
    private void ensureInitialized(DeployContext ctx, SshSession session, String workDir, String serviceName,
                                   String jarName, String javaBin, String jvmOptions,
                                   String profiles, String configDir, String configFile) {
        String execDir = workDir + "/exec";
        SshCommandResult mkdir = session.exec("mkdir -p " + execDir);
        if (!mkdir.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "创建脚本目录失败：" + support.firstLine(mkdir.stderr()));
        }
        uploadScript(session, execDir, SCRIPT_INIT);
        uploadScript(session, execDir, SCRIPT_UPDATE);
        uploadScript(session, execDir, SCRIPT_FALLBACK);

        SshCommandResult probe = session.exec(
                "test -d " + workDir + " && ls -1 " + workDir + "/*.jar >/dev/null 2>&1 && echo yes || echo no");
        if (probe.success() && "yes".equals(probe.stdout().strip())) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_PREPARE, TaskLogLevel.INFO,
                    "工作目录已初始化，跳过：" + workDir);
            return;
        }
        // 初始化依赖目标机的 python3：脚本本身无第三方依赖，但不假设机器装了什么。
        SshCommandResult python = session.exec("command -v python3 || echo none");
        if (!python.success() || !StringUtils.hasText(python.stdout()) || "none".equals(python.stdout().strip())) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "目标机未安装 python3，无法执行 JAR 初始化脚本，请先在目标机安装 python3");
        }
        // 回显解释器版本：老机器上的 python3 可能是 3.6，脚本一旦用了更高版本的语法或参数，
        // 报错信息只是一句 TypeError，没有版本信息很难定位。
        SshCommandResult version = session.exec("python3 --version 2>&1 || echo unknown");
        support.appendLog(ctx.task(), DeploySupport.STAGE_PREPARE, TaskLogLevel.INFO,
                "目标机 python3：" + support.firstLine(version.stdout()));
        support.appendLog(ctx.task(), DeploySupport.STAGE_PREPARE, TaskLogLevel.INFO,
                "首次部署，初始化工作目录 " + workDir);
        String command = "python3 " + execDir + "/" + SCRIPT_INIT
                + " --work-dir " + support.shellQuote(workDir)
                + " --jar-name " + support.shellQuote(jarName)
                + " --service-name " + support.shellQuote(serviceName)
                + " --java-bin " + support.shellQuote(javaBin)
                + " --jvm-opts " + support.shellQuote(jvmOptions == null ? "" : jvmOptions)
                + " --profiles " + support.shellQuote(profiles)
                + " --config-dir " + support.shellQuote(configDir)
                + " --config-file " + support.shellQuote(configFile);
        SshCommandResult result = session.exec(command);
        support.writeOutput(ctx.task(), DeploySupport.STAGE_PREPARE, result.stdout());
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "工作目录初始化失败：" + support.firstLine(result.stderr()));
        }
    }

    /**
     * 把内置脚本写到目标机指定目录。
     *
     * @param session   已连接的 SSH 会话
     * @param targetDir 目标目录
     * @param name      脚本文件名
     * @throws DeployException 写入失败时抛出
     */
    private void uploadScript(SshSession session, String targetDir, String name) {
        writeRemoteFile(session, targetDir + "/" + name, loadScript(name));
    }

    /**
     * 从 classpath 读取内置脚本内容。
     *
     * @param name 脚本文件名
     * @return 脚本文本
     * @throws DeployException 脚本不存在或读取失败时抛出
     */
    private String loadScript(String name) {
        ClassPathResource resource = new ClassPathResource(SCRIPT_DIR + name);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR,
                    "读取内置部署脚本失败（" + name + "）：" + e.getMessage());
        }
    }

    // ==================== 配置分发 ====================

    /**
     * 下载随版本转存的配置文件（委托 {@link DeploySupport#downloadConfigFiles}）。
     *
     * <p>JAR 的配置目录位置由产物路径直接推得：{@code {产物所在目录}/config}，由构建阶段按
     * {@code build_config_json.config_paths} 转存。没有配置文件时返回空集合，发布照常进行——
     * 配置缺失不该阻断发版，是否真的起得来由服务启动与健康检查判定。</p>
     *
     * <p><b>下载与投放的实现放在 {@link DeploySupport}，JAR 与 DOCKER 共用一份</b>：两边各写一份的
     * 下场是「JAR 会判断目标机已存在不覆盖、DOCKER 却直接覆盖」这类悄悄分化。</p>
     *
     * @param ctx          发布上下文
     * @param artifactPath 构建机上的产物绝对路径
     * @return 配置文件名到本地临时文件的映射
     * @throws DeployException 本地临时文件创建失败时抛出
     */
    private Map<String, Path> downloadConfigFiles(DeployContext ctx, String artifactPath) {
        ReleaseRecord release = ctx.release();
        if (!StringUtils.hasText(release.getBuildServerId()) || !artifactPath.contains("/")) {
            return Map.of();
        }
        Server buildServer = serverMapper.selectById(release.getBuildServerId());
        String configDir = artifactPath.substring(0, artifactPath.lastIndexOf('/'))
                + "/" + DeploySupport.CONFIG_SUBDIR;
        return support.downloadConfigFiles(ctx.task(), buildServer, configDir);
    }

    /**
     * 投放配置文件到工作目录的 config 子目录（委托 {@link DeploySupport#uploadConfigFiles}）。
     *
     * <p>目标机已存在同名配置时跳过、不覆盖：配置与 jar 分离的意义就在于配置由运维自己维护，
     * 平台不该在每次发版时把仓库里的配置推到生产环境覆盖现场改动。</p>
     *
     * @param ctx       发布上下文
     * @param session   已连接的 SSH 会话
     * @param targetDir 目标机配置目录
     * @param files     配置文件名到本地临时文件的映射
     * @throws DeployException 上传失败时抛出
     */
    private void uploadConfigFiles(DeployContext ctx, SshSession session, String targetDir, Map<String, Path> files) {
        support.uploadConfigFiles(ctx.task(), session, targetDir, files);
    }

    // ==================== jar 替换与校验 ====================

    /**
     * 确定本次投放使用的 jar 文件名。
     *
     * <p>工作目录里已有 jar 时沿用它的名字（同一实例只保留一个 jar），否则用产物原文件名。</p>
     *
     * @param session          已连接的 SSH 会话
     * @param workDir          工作目录
     * @param artifactFileName 产物文件名
     * @return 本次投放使用的 jar 文件名
     */
    private String resolveJarName(SshSession session, String workDir, String artifactFileName) {
        SshCommandResult ls = session.exec("ls -1 " + workDir + "/*.jar 2>/dev/null | head -1");
        if (ls.success() && StringUtils.hasText(ls.stdout())) {
            return support.baseName(ls.stdout().strip());
        }
        return artifactFileName;
    }

    /**
     * 判断目标机工作目录里的 jar 是否已经是本次要发的那一个。
     *
     * <p>重复发布同一版本、或发布失败后重试时，目标机上往往已经放着完全相同的包。
     * 此时再传一遍大文件纯属浪费：上传 74 MB 在窄带上要几分钟，
     * 而比对摘要只是在目标机上跑一次 {@code sha256sum}（秒级）。</p>
     *
     * <p>三种情况一律按「需要上传」处理（保守方向）：版本未登记摘要、
     * 目标机上还没有 jar、目标机算不出摘要。跳过上传不会跳过后续的
     * {@link #verifyJarDigest}——命中本方法只说明摘要本来就一致。</p>
     *
     * @param ctx     发布上下文，提供版本登记的摘要
     * @param session 已连接的 SSH 会话
     * @param workDir 工作目录
     * @param jarName jar 文件名
     * @return 目标机现有 jar 的摘要与版本登记一致时为 true
     */
    private boolean artifactUpToDate(DeployContext ctx, SshSession session, String workDir, String jarName) {
        String expected = ctx.release().getSha256();
        if (!StringUtils.hasText(expected)) {
            return false;
        }
        SshCommandResult result = session.exec(
                "sha256sum " + workDir + "/" + jarName + " 2>/dev/null | awk '{print $1}'");
        String actual = result.success() ? result.stdout().strip() : "";
        return StringUtils.hasText(actual) && expected.equalsIgnoreCase(actual);
    }

    /**
     * 把工作目录里的现有 jar 备份到 exec/bak，备份名内嵌被替换包的平台版本号。
     *
     * <p>命名格式 {@code {jar名}_bak_{时间戳}_{版本号}.jar}，例如
     * {@code auth-tool_bak_20260925222851_auth-7888-v6-c9bb25d3.jar}。以
     * 「{jar名}_bak_ + 14 位时间戳」开头，字典序即时间序；保留 .jar 后缀，
     * {@code jar-fallback.py} 的还原规则据此识别。</p>
     *
     * <p><b>版本号必须写进文件名，而不是另存一个伴随文件。</b>备份名里的时间戳是「执行替换的时刻」，
     * 指向的是替换者而不是包里的版本。版本段取目标机上的 {@code VERSION}（磁盘事实），
     * 不取平台库里的快照：库快照在手工换包之后会失真。</p>
     *
     * <p>取不到版本时名字退化为 {@code {jar名}_bak_{时间戳}.jar}，<b>不猜版本</b>——记错版本比不记版本更危险。
     * 同一秒内同版本重名时追加 {@code _2}、{@code _3}，该序号只是防撞名，与版本无关。</p>
     *
     * @param ctx     发布上下文
     * @param session 已连接的 SSH 会话
     * @param workDir 工作目录
     * @param jarName jar 文件名
     * @return 备份文件的绝对路径；工作目录此前没有 jar（首次投放）时返回 null
     * @throws DeployException 备份失败时抛出
     */
    private String backupCurrentJar(DeployContext ctx, SshSession session, String workDir, String jarName) {
        String source = workDir + "/" + jarName;
        String bakDir = workDir + "/exec/bak";
        String versionFile = workDir + "/" + VERSION_MARKER;
        int dot = jarName.lastIndexOf('.');
        String base = dot > 0 ? jarName.substring(0, dot) : jarName;
        String ext = dot > 0 ? jarName.substring(dot) : "";
        // 版本读取、防撞名探测与备份在同一条命令里完成，避免并发部署在同一秒内抢同一个文件名。
        // 版本段是可选件：老工作目录可能还没写过 VERSION，缺它只能退化为不带版本的名字，不能挡住备份本身。
        // tr 同时兜住 VERSION 被手工改脏的情况：版本段只允许字母数字与 . _ -，防止带出 / 把备份写到别的目录。
        String cmd = "if [ -f " + source + " ]; then mkdir -p " + bakDir + "; "
                + "ts=$(date +%Y%m%d%H%M%S); "
                + "ver=$(grep '^version=' " + versionFile + " 2>/dev/null | head -1 | cut -d= -f2- "
                + "| tr -cd 'A-Za-z0-9._-'); "
                + "name=\"" + base + "_bak_${ts}\"; "
                + "if [ -n \"$ver\" ]; then name=\"${name}_${ver}\"; fi; "
                + "dst=\"" + bakDir + "/${name}" + ext + "\"; n=2; "
                + "while [ -f \"$dst\" ]; do dst=\"" + bakDir + "/${name}_${n}" + ext + "\"; n=$((n+1)); done; "
                + "cp -p " + source + " \"$dst\" && echo \"$dst\"; else echo none; fi";
        SshCommandResult result = session.exec(cmd);
        String backupPath = result.stdout().strip();
        if (!result.success() || !StringUtils.hasText(backupPath)) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "备份现有 jar 失败：" + support.firstLine(result.stderr()));
        }
        if ("none".equals(backupPath)) {
            // 首次投放：工作目录里还没有 jar，没有可备份的内容。
            return null;
        }
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                "已备份现有 jar 到 exec/bak（" + support.baseName(backupPath) + "）");
        return backupPath;
    }

    /**
     * 校验目标机上的产物与版本登记的摘要是否一致。
     *
     * <p>产物要经「构建机 → 平台 → 目标机」两跳传输，任何一跳损坏都会留下一个能落盘、
     * 但起不来的 jar。此前只在版本记录里登记摘要、发版时不校验，损坏只能等重启后
     * 表现为「应用启动异常」才暴露，很难联想到是传输问题。</p>
     *
     * <p>登记摘要为空（历史版本未登记）或目标机算不出摘要时跳过并写明原因：
     * 这两种情况都不是产物损坏，不能因此阻断发布。</p>
     *
     * @param ctx        发布上下文
     * @param session    已连接的 SSH 会话
     * @param workDir    工作目录
     * @param jarName    jar 文件名
     * @param backupPath 本次发布前的备份路径，校验失败时用于还原；可为 null
     * @throws DeployException 摘要不一致时抛出，抛出前已还原发布前的 jar
     */
    private void verifyJarDigest(DeployContext ctx, SshSession session, String workDir,
                                 String jarName, String backupPath) {
        String expected = ctx.release().getSha256();
        if (!StringUtils.hasText(expected)) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "版本 " + ctx.targetVersion() + " 未登记产物摘要，跳过完整性校验");
            return;
        }
        SshCommandResult result = session.exec(
                "sha256sum " + workDir + "/" + jarName + " 2>/dev/null | awk '{print $1}'");
        String actual = result.success() ? result.stdout().strip() : "";
        if (!StringUtils.hasText(actual)) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "目标机无法计算产物摘要（可能缺少 sha256sum），跳过完整性校验");
            return;
        }
        if (!expected.equalsIgnoreCase(actual)) {
            // 先还原再抛出：磁盘上此刻是损坏的 jar，不还原会让机器停在
            // 「服务跑着旧版本、磁盘放着坏包」的错位状态——下次重启必然起不来。
            restorePreviousJar(ctx, session, workDir, jarName, backupPath);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "产物完整性校验失败：期望 SHA256 " + expected + "，目标机实际 " + actual
                            + "，传输可能已损坏");
        }
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                "产物完整性校验通过（SHA256 " + actual + "）");
    }

    /**
     * 用备份还原工作目录的 jar，用于产物校验失败时回到发布前状态。
     *
     * <p>还原失败只记日志、不抛出：调用方紧接着要抛的是「完整性校验失败」，
     * 那才是根因；用还原异常覆盖它会让使用者看到的是结果而不是原因。</p>
     *
     * @param ctx        发布上下文
     * @param session    已连接的 SSH 会话
     * @param workDir    工作目录
     * @param jarName    jar 文件名
     * @param backupPath 备份文件绝对路径，为空表示无可还原的备份
     */
    private void restorePreviousJar(DeployContext ctx, SshSession session, String workDir,
                                    String jarName, String backupPath) {
        if (!StringUtils.hasText(backupPath)) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.ERROR,
                    "产物完整性校验失败且无可用备份，工作目录中的 " + jarName + " 已损坏，请手工从平台重新发布");
            return;
        }
        SshCommandResult result = session.exec("cp -p " + backupPath + " " + workDir + "/" + jarName);
        if (result.success()) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "已还原发布前的 " + jarName);
        } else {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.ERROR,
                    "还原发布前的 " + jarName + " 失败，请手工从 exec/bak 恢复：" + support.firstLine(result.stderr()));
        }
    }

    // ==================== 版本标识与重启 ====================

    /**
     * 在目标机工作目录写入当前版本标识。
     *
     * <p>回答一个平台之外的问题：这台机器上跑的是哪个版本。平台可达时看部署记录即可，
     * 但平台宕机、或运维直接登录机器排查时，工作目录里只有一个不带任何标识的 jar，
     * 只能靠文件时间或大小猜。</p>
     *
     * <p>一律写平台原始版本号（如 {@code auth-7888-v3-887b603c}），不做展示美化——
     * 它要能被脚本与人工直接比对，与 {@code release_record.version} 同源。</p>
     *
     * @param ctx     发布上下文
     * @param session 已连接的 SSH 会话
     * @param workDir 工作目录
     */
    private void writeVersionMarker(DeployContext ctx, SshSession session, String workDir) {
        ReleaseRecord release = ctx.release();
        String content = "version=" + ctx.targetVersion() + "\n"
                + "commit=" + (StringUtils.hasText(release.getCommitId()) ? release.getCommitId() : "unknown") + "\n"
                + "runtime=" + release.getRuntimeType() + "\n"
                + "deployed_at=" + support.nowText() + "\n";
        String command = "printf '%s' " + support.shellQuote(content) + " > " + workDir + "/" + VERSION_MARKER;
        SshCommandResult result = session.exec(command);
        if (!result.success()) {
            // 标识是辅助信息，写不进去不影响本次发布是否成功，但必须留痕。
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "写入 " + VERSION_MARKER + " 失败（不影响发布结果）：" + support.firstLine(result.stderr()));
            return;
        }
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                "已登记目标机当前版本 " + ctx.targetVersion());
    }

    /**
     * 刷新目标机 exec/deploy.conf 里的 version 行。
     *
     * <p>deploy.conf 是目标机脚本（update / fallback）的服务信息入口，此前只记
     * service_name / jar_name / work_dir，不记版本，脚本只能报「退了一步」。
     * 补上 version 后，脚本可以打印「当前 v5 → 回退到 v4」。</p>
     *
     * <p>文件不存在时不创建：deploy.conf 由 init 脚本生成，凭空造一份只能猜 service_name，
     * 猜错会让应急脚本指向错误的服务。重写走「过滤旧行 + 追加新行 + 原子替换」，
     * 不使用 {@code sed -i}，避免不同发行版的 -i 参数差异与特殊字符转义问题。</p>
     *
     * @param ctx     发布上下文
     * @param session 已连接的 SSH 会话
     * @param workDir 工作目录
     */
    private void refreshDeployConfVersion(DeployContext ctx, SshSession session, String workDir) {
        String version = ctx.targetVersion();
        String conf = workDir + "/exec/" + DEPLOY_CONF;
        String command = "if [ -f " + conf + " ]; then "
                + "grep -v '^version=' " + conf + " > " + conf + ".tmp && "
                + "printf 'version=%s\\n' " + support.shellQuote(version) + " >> " + conf + ".tmp && "
                + "mv -f " + conf + ".tmp " + conf + "; fi";
        SshCommandResult result = session.exec(command);
        if (!result.success()) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "刷新 " + DEPLOY_CONF + " 的版本信息失败（不影响发布结果）：" + support.firstLine(result.stderr()));
            return;
        }
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                "已同步 " + DEPLOY_CONF + " 版本信息为 " + version);
    }

    /**
     * 重启 systemd 服务并确认处于 active 状态。
     *
     * @param ctx         发布上下文
     * @param session     已连接的 SSH 会话
     * @param serviceName systemd 服务名
     * @throws DeployException 重启失败或重启后未处于 active 时抛出
     */
    private void restartService(DeployContext ctx, SshSession session, String serviceName) {
        support.appendLog(ctx.task(), DeploySupport.STAGE_START, TaskLogLevel.INFO,
                "重启 systemd 服务 " + serviceName);
        // 重启是同步等待服务起停的动作，应用初始化慢时可能超过秒级超时，按长命令超时等待。
        SshCommandResult restart = session.execLong("systemctl restart " + serviceName);
        if (!restart.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "systemctl 重启失败：" + support.firstLine(restart.stderr()));
        }
        SshCommandResult active = session.exec("systemctl is-active " + serviceName);
        if (!active.success() || !"active".equalsIgnoreCase(active.stdout().strip())) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "服务重启后未处于 active 状态，请检查 journalctl -u " + serviceName);
        }
        // 重启成功与否原本只在失败时才有痕迹，成功是「静默通过」的。
        // 回滚场景要明确回答「服务有没有起来」，因此成功也必须留一条可核对的记录。
        support.appendLog(ctx.task(), DeploySupport.STAGE_START, TaskLogLevel.INFO,
                "服务 " + serviceName + " 启动成功（systemctl is-active = active）");
    }

    // ==================== 远程辅助 ====================

    /**
     * 检测目标机上的 java 可执行文件路径。
     *
     * <p>systemd 的 ExecStart 不继承登录 shell 的 PATH，写死 /usr/bin/java 在自装 JDK 的机器上会失败，
     * 因此先探测真实路径，探测不到时退回约定路径。</p>
     *
     * @param session 已连接的 SSH 会话
     * @return java 可执行文件路径
     */
    private String detectJavaBin(SshSession session) {
        SshCommandResult result = session.exec("command -v java || echo /usr/bin/java");
        String path = result.success() && StringUtils.hasText(result.stdout()) ? result.stdout().strip() : "";
        return StringUtils.hasText(path) ? path : "/usr/bin/java";
    }

    /**
     * 把文本写到目标机指定文件（base64 中转，避免引号与换行破坏单条命令）。
     *
     * @param session 已连接的 SSH 会话
     * @param path    目标绝对路径
     * @param content 文件内容
     * @throws DeployException 写入失败时抛出
     */
    private void writeRemoteFile(SshSession session, String path, String content) {
        String base64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        SshCommandResult result = session.exec("echo " + base64 + " | base64 -d > " + path);
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "写入远程文件失败（" + path + "）：" + support.firstLine(result.stderr()));
        }
    }
}
