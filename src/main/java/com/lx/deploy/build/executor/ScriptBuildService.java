package com.lx.deploy.build.executor;

import com.lx.deploy.build.model.BuildArtifact;
import com.lx.deploy.common.enums.build.ArtifactType;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshCommandTimeoutException;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.task.model.Task;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 脚本构建服务：JAR / STATIC 运行时由用户显式给出 build_command，平台负责执行、采集文件产物与配置、计算 SHA256。
 *
 * <p>只负责「把某个版本的产物在构建机本地构建出来并落盘」这一件事：编译细节完全交给用户配置的命令，
 * 平台只做环境前缀装配、可执行性预检、实时回传、超时终止进程组、产物定位与转存、配置转存、元数据登记。</p>
 *
 * <p>所有与「构建什么无关」的零件（工作区/克隆/日志/进度/路径字符串/镜像无关工具等）都委托给
 * {@link BuildSupport}，本服务只持有「脚本类文件产物构建」这一专属编排逻辑，及其专属的
 * 构建命令执行/预检/工具链日志方法。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Component
public class ScriptBuildService {

    /** 构建支撑组件：日志、进度、路径/字符串工具、配置转存等共用零件。 */
    private final BuildSupport support;

    /**
     * 创建脚本构建服务。
     *
     * @param support 构建支撑组件
     */
    public ScriptBuildService(BuildSupport support) {
        this.support = support;
    }

    /**
     * 执行 JAR / STATIC 运行时构建并采集文件产物。
     *
     * <p>脚本部署（SCRIPT_DEPLOY）要求 build_command 显式给出；进入模块目录执行后按 artifact_path 通配定位产物，
     * 计算 SHA256 供部署校验一致性。</p>
     *
     * @param ctx 构建上下文（含 task / session / buildServer / project / config / runtimeType / workspacePath / artifactBase / commit / version / artifacts）
     * @throws DeployException 构建命令缺失、构建失败或产物未找到时抛出
     */
    public void build(BuildContext ctx) {
        Task task = ctx.task();
        SshSession session = ctx.session();
        Server buildServer = ctx.buildServer();
        Project project = ctx.project();
        BuildConfig config = ctx.config();
        RuntimeType runtimeType = ctx.runtimeType();
        String workspacePath = ctx.workspacePath();
        String artifactBase = ctx.artifactBase();
        String commit = ctx.commit();
        String releaseVersion = ctx.version();
        List<BuildArtifact> artifacts = ctx.artifacts();
        if (!support.reportProgress(task, BuildSupport.STAGE_BUILD, BuildSupport.PROGRESS_BUILDING)) {
            return;
        }
        if (!StringUtils.hasText(config.getBuildCommand())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "脚本部署（SCRIPT_DEPLOY）必须在 build_config_json.build_command 中显式给出构建命令");
        }
        if (!StringUtils.hasText(config.getArtifactPath())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    runtimeType + " 运行时必须在 build_config_json.artifact_path 中给出产物相对路径");
        }
        // 模块目录：见 resolveModuleDir——填了 module_dir 用它，否则回落 module_name，都没有则仓库根。
        String moduleDir = support.resolveModuleDir(config);
        String buildDir = StringUtils.hasText(moduleDir) ? workspacePath + "/" + moduleDir : workspacePath;

        // 命令前缀：`. /etc/profile` 兜底把登录态 PATH 引入非交互会话；紧随其后的机器级
        // 「构建环境初始化脚本」用于补充 profile 里没有的路径（如 maven 的 bin 目录）。
        // 预检与真正执行使用同一份前缀，避免「预检说没有、实际加了前缀就能跑」的误判。
        String envPrefix = support.buildEnvPrefix(buildServer);

        // 构建前先确认命令本身能找到：非交互式 SSH 会话不加载 /etc/profile，PATH 里常缺 mvn，
        // 直接执行只会得到「bash: mvn: command not found」，很难定位是「没装」还是「没配 PATH」。
        // 因此先探测首词是否可执行，给出明确原因与处理办法，再执行真正的构建命令。
        checkBuildCommandExecutable(task, session, envPrefix, config.getBuildCommand());

        // 先自证工具链版本（仅 Maven 命令）：JDK 版本错配类报错只能靠这条日志定位到具体 JVM。
        logBuildToolchain(task, session, envPrefix, buildDir, firstCommandToken(config.getBuildCommand()));

        // 构建命令走「长命令超时 + 实时行回传 + 超时终止远端进程组」，
        // 项目级 command_timeout_seconds 可覆盖全局的长命令超时。
        int timeoutSeconds = support.resolveBuildTimeout(config, session);
        long startedAt = System.currentTimeMillis();
        SshCommandResult result = runBuildCommand(task, session, buildDir, envPrefix,
                config.getBuildCommand(), timeoutSeconds);
        long costSeconds = (System.currentTimeMillis() - startedAt) / 1000;
        if (!result.success()) {
            // 失败输出完整落日志，异常消息只留首行摘要。
            support.writeError(task, BuildSupport.STAGE_BUILD, result.stderr(), BuildSupport.MAX_BUILD_OUTPUT_LINES);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "构建命令执行失败（耗时 " + costSeconds + " 秒）：" + support.firstLine(result.stderr()));
        }
        appendBuildSuccessSummary(task, result, costSeconds);

        // 产物定位：相对仓库根优先，找不到再按模块目录回落。
        // 两种口径都支持，是因为仓库里的填写习惯不统一——多模块仓库习惯从仓库根写全路径
        // （如 auth-tool/linux/auth-tool-7888/auth-tool.jar），单模块仓库习惯只写模块内相对路径，
        // 只认一种会让另一半配置"填了却找不到产物"。
        String actualFile = support.locateFile(session, workspacePath, config.getArtifactPath());
        if (!StringUtils.hasText(actualFile) && !buildDir.equals(workspacePath)) {
            actualFile = support.locateFile(session, buildDir, config.getArtifactPath());
        }
        if (!StringUtils.hasText(actualFile)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "未找到构建产物，请检查 artifact_path：" + config.getArtifactPath()
                            + "（已按仓库根与模块目录两种口径查找）");
        }
        // 计算 SHA256。
        SshCommandResult shaResult = session.exec("sha256sum " + actualFile + " | awk '{print $1}'");
        String sha = shaResult.success() && StringUtils.hasText(shaResult.stdout()) ? shaResult.stdout().strip() : null;
        String fileName = actualFile.contains("/") ? actualFile.substring(actualFile.lastIndexOf('/') + 1) : actualFile;
        // 版本号由调用方一次性定稿：产物持久目录名必须与版本记录、镜像 tag 用同一个值。
        String version = releaseVersion;

        // 产物必须先落到持久目录：工作区在任务收尾时会被整体删除，
        // 若直接登记工作区路径，发布阶段下载到的就是一个已不存在的路径。
        // 目录型产物（如前端 dist）与文件型产物统一用 cp -r 处理。
        String persistDir = artifactBase + "/" + project.getCode() + "/" + version;
        SshCommandResult copyResult = session.exec("mkdir -p " + persistDir + " && cp -r " + actualFile + " " + persistDir + "/");
        if (!copyResult.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "产物转存到持久目录失败：" + support.firstLine(copyResult.stderr()));
        }
        String persistedPath = persistDir + "/" + fileName;

        // 版本标识必须写在持久目录里而不是工作区：工作区在任务收尾时会被整体删除，
        // 写在工作区的标识随构建结束一起消失，等于没写。
        writeArtifactVersionMarker(task, session, persistDir, releaseVersion, fileName, runtimeType, commit);

        // 配置文件转存到产物同级的 config 目录：JAR 运行时 application.yml 与 jar 分离部署，
        // 发布阶段据此投放到目标机工作目录的 config 子目录，日常发版只换 jar 不碰配置。
        List<String> configFiles = support.collectConfigFiles(task, session, config, workspacePath, buildDir, persistDir);

        BuildArtifact artifact = new BuildArtifact();
        artifact.setArtifactType(runtimeType == RuntimeType.JAR ? ArtifactType.JAR.getCode() : ArtifactType.STATIC.getCode());
        artifact.setArtifactName(fileName);
        artifact.setArtifactTag(version);
        artifact.setArtifactDigest(sha);
        artifact.setMetadataJson(support.buildMetadata(persistedPath, persistDir, configFiles));
        artifacts.add(artifact);
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                "产物已转存至 " + persistedPath + "，SHA256 " + (sha == null ? "未知" : sha));
        if (!configFiles.isEmpty()) {
            support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                    "随产物转存配置文件 " + String.join("、", configFiles));
        }
    }

    /**
     * 在构建机产物持久目录写入版本标识文件。
     *
     * <p>产物文件本身不带任何平台标识：jar 内部不注入 MANIFEST（Spring Boot fat jar 的嵌套 jar
     * 依赖 ZIP 的 STORED 布局，用 {@code jar --update} 重打包一旦改变压缩方式，会静默产出
     * 「启动即失败」的产物；而且重打包会改变 SHA256，与登记的产物摘要语义冲突）。
     * 结果是运维在构建机上看到 {@code auth-tool.jar} 无法判断它是哪个版本，
     * 只能靠目录名或回平台反查。这里补一份纯文本标识，让产物自己带上版本。</p>
     *
     * <p>写成 key=value 多行而不是只写版本号：排查时往往还需要提交、运行时与产物文件名，
     * 一次看全可以省掉一次平台查询。</p>
     *
     * <p>写入失败只记日志、不影响构建结论——标识是辅助信息，
     * 不该因为它挡住一次已经成功的构建。</p>
     *
     * @param task           当前任务
     * @param session        已认证会话
     * @param persistDir     产物持久目录
     * @param releaseVersion 定稿的发布版本号
     * @param fileName       产物文件名
     * @param runtimeType    运行时类型
     * @param commit         提交 ID，可为空
     */
    void writeArtifactVersionMarker(Task task, SshSession session, String persistDir,
                                    String releaseVersion, String fileName,
                                    RuntimeType runtimeType, String commit) {
        String content = "version=" + releaseVersion + "\n"
                + "commit=" + (StringUtils.hasText(commit) ? commit : "unknown") + "\n"
                + "runtime=" + runtimeType + "\n"
                + "artifact=" + fileName + "\n"
                + "built_at=" + java.time.LocalDateTime.now().format(BuildSupport.VERSION_TIME_FORMAT) + "\n";
        String command = "printf '%s' " + support.shellQuote(content) + " > " + persistDir + "/" + BuildSupport.VERSION_MARKER;
        SshCommandResult result = session.exec(command);
        if (!result.success()) {
            support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                    "写入 " + BuildSupport.VERSION_MARKER + " 失败（不影响构建结果）：" + support.firstLine(result.stderr()));
            return;
        }
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                "已写入版本标识 " + persistDir + "/" + BuildSupport.VERSION_MARKER);
    }

    /**
     * 执行构建命令：独立进程组 + 实时输出 + 超时终止。
     *
     * <p>三项能力叠在一起，都是为了解决「构建超过 120 秒就只剩一句超时、日志一片空白」的问题：</p>
     * <ol>
     *   <li><b>实时回传</b>：Maven 每产出一行就落一条任务日志，页面能看到进度滚动。
     *       原来输出要等通道关闭才读取，命中超时则缓冲区直接丢弃，页面上除了「执行超时」什么也看不到；</li>
     *   <li><b>独立进程组</b>：用 {@code setsid} 起新会话并把组长 PID 写入 /tmp，
     *       超时后可以 {@code kill -9 -PGID} 连 mvn 及其子进程一起终止，
     *       避免「任务已判失败、远端还在往即将被删除的工作区里写」；</li>
     *   <li><b>长命令超时</b>：默认走全局长命令超时（30 分钟档），项目级可覆盖。</li>
     * </ol>
     *
     * @param task           当前任务
     * @param session        已认证的构建机会话
     * @param buildDir       构建命令的执行目录
     * @param envPrefix      与可执行性预检一致的环境前缀
     * @param buildCommand   用户配置的构建命令
     * @param timeoutSeconds 超时预算秒数
     * @return 命令执行结果，退出码非 0 由调用方判定
     * @throws DeployException 超时时抛出，抛出前已终止远端进程组并落好已收到的输出
     */
    SshCommandResult runBuildCommand(Task task, SshSession session, String buildDir, String envPrefix,
                                     String buildCommand, int timeoutSeconds) {
        String plainCmd = "cd " + buildDir + " && " + envPrefix + buildCommand;
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                "执行构建命令（超时上限 " + timeoutSeconds + " 秒，输出实时回传）：" + plainCmd);
        String pidFile = BuildSupport.BUILD_PID_FILE_PREFIX + task.getId() + ".pid";
        // -w 让 setsid 等待子进程并回传其退出码：进程组组长场景下 setsid 会 fork，
        // 不带 -w 时父进程会立刻退出，通道提前关闭，构建就被误判成"瞬间成功"。
        String wrapped = "setsid -w sh -c " + support.shellQuote("echo $$ > " + pidFile + "; " + plainCmd);
        SshCommandResult result;
        try {
            result = session.exec(wrapped, timeoutSeconds, support.buildOutputConsumer(task));
        } catch (SshCommandTimeoutException e) {
            support.terminateRemoteProcessGroup(task, session, pidFile);
            support.writeTimeoutOutput(task, BuildSupport.STAGE_BUILD, e.getPartialResult());
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "构建命令执行超时（" + e.getTimeoutSeconds()
                            + " 秒，可在 build_config_json.command_timeout_seconds 放宽上限）");
        }
        // 极端精简的构建机可能没有 setsid（util-linux 被裁剪）：降级为不带进程组包装再跑一次，
        // 并如实说明"本次超时后无法终止远端进程"，而不是让用户对着一句 setsid 找不到发懵。
        if (!result.success() && support.isSetsidMissing(result)) {
            support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                    "构建机缺少 setsid 命令，改以普通方式执行：本次构建超时后无法终止远端进程组");
            return session.exec(plainCmd, timeoutSeconds, support.buildOutputConsumer(task));
        }
        return result;
    }

    /**
     * 构建成功后写汇总，并补录实时回传没覆盖到的尾部输出。
     *
     * <p>不再整段回显 stdout：输出已经实时落过一遍，再全量打一遍等于同一段日志出现两次。
     * 只有行数超过实时回传上限（后面的行没展示过）时才补录末尾若干行。</p>
     *
     * @param task        当前任务
     * @param result      构建命令结果
     * @param costSeconds 命令耗时秒数
     */
    void appendBuildSuccessSummary(Task task, SshCommandResult result, long costSeconds) {
        String output = StringUtils.hasText(result.stdout()) ? result.stdout() : result.stderr();
        long lines = StringUtils.hasText(output) ? output.strip().lines().count() : 0;
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                "构建命令执行成功：耗时 " + costSeconds + " 秒，共 " + lines + " 行输出");
        if (lines > BuildSupport.MAX_STREAM_OUTPUT_LINES) {
            support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                    "实时回传已达上限 " + BuildSupport.MAX_STREAM_OUTPUT_LINES + " 行，补录末尾 " + BuildSupport.MAX_BUILD_OUTPUT_LINES + " 行：");
            support.writeLines(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                    support.tailLines(output, BuildSupport.MAX_BUILD_OUTPUT_LINES), BuildSupport.MAX_BUILD_OUTPUT_LINES);
        }
    }

    /**
     * 构建命令可执行性预检：首词找不到时立即失败并给出处理办法。
     *
     * <p>直接执行构建命令时，缺失的工具只会让 bash 抛「command not found」，用户无法区分
     * 「没装」与「装了但不在非交互式会话的 PATH 里」。这里用与正式执行相同的前缀探测首词：
     * PATH 形式的命令用 {@code command -v} 查，含 / 的路径形式用 {@code test -x} 判断；
     * 探测不通过直接抛业务异常，任务日志里保留具体命令与三种处理办法。</p>
     *
     * <p>前缀里已包含 profile 兜底，因此「profile 里配了 Maven 路径」的机器会正常通过预检。</p>
     *
     * @param task         当前任务
     * @param session      已认证的构建机会话
     * @param envPrefix    与正式执行一致的环境前缀
     * @param buildCommand 用户配置的构建命令
     * @throws DeployException 命令首词在构建机上不可执行时抛出
     */
    void checkBuildCommandExecutable(Task task, SshSession session, String envPrefix, String buildCommand) {
        String token = firstCommandToken(buildCommand);
        if (!StringUtils.hasText(token)) {
            // 取不到首词（空命令等）时不拦截，交给后续执行与既有校验兜底。
            return;
        }
        boolean pathForm = token.contains("/");
        String probe = pathForm
                ? "test -x " + token + " && echo " + BuildSupport.CMD_PROBE_OK + " || echo " + BuildSupport.CMD_PROBE_MISSING
                : "command -v " + token + " >/dev/null 2>&1 && echo " + BuildSupport.CMD_PROBE_OK
                        + " || echo " + BuildSupport.CMD_PROBE_MISSING;
        SshCommandResult result = session.exec(envPrefix + probe);
        if (result.stdout().contains(BuildSupport.CMD_PROBE_MISSING)) {
            String reason = pathForm ? "该路径不存在或没有执行权限：" + token
                    : "该命令不在构建机的 PATH 中（非交互式会话不加载 /etc/profile 等登录环境）：" + token;
            support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.ERROR, "构建命令预检失败，" + reason);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "构建命令不可执行（" + reason + "）。三种处理方式："
                            + "① 安装该工具（如 Maven：yum install -y maven）；"
                            + "② 在服务器档案的「构建环境初始化脚本」里补 PATH（如 export PATH=/opt/apache-maven-3.8.8/bin:$PATH）；"
                            + "③ 在 build_command 里直接写工具绝对路径（如 /opt/apache-maven-3.8.8/bin/mvn -B clean package）");
        }
    }

    /**
     * 把构建工具链版本写进日志（仅 Maven 命令）。
     *
     * <p>{@code mvn -v} 一次性输出 Maven 版本、Maven home 与它实际使用的 JDK 版本，
     * 是「JDK 版本错配」类问题的唯一自证信息：形如
     * {@code NoSuchMethodError: java.nio.ByteBuffer.mark()Ljava/nio/ByteBuffer;} 的报错，
     * 根因是用 JDK 9+ 编译的类跑在 JDK 8 上，而报错本身不会说明是哪个 jar、哪个 JVM 干的。
     * 记一条版本日志后，这类问题不必再登录构建机手工复现。</p>
     *
     * @param task      当前任务
     * @param session   已认证的构建机会话
     * @param envPrefix 与正式执行一致的环境前缀
     * @param buildDir  构建命令的执行目录（{@code ./mvnw} 这类相对路径必须在该目录下才有意义）
     * @param token     构建命令的首词，非 Maven 命令时直接跳过
     */
    void logBuildToolchain(Task task, SshSession session, String envPrefix,
                          String buildDir, String token) {
        if (!support.isMavenExecutable(token)) {
            return;
        }
        SshCommandResult result = session.exec("cd " + buildDir + " && " + envPrefix + token + " -v");
        if (!result.success()) {
            // 版本探测属于信息类日志，失败不拦截构建，原因交给后续正式执行的报错。
            return;
        }
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO, "构建工具链版本：");
        support.writeOutput(task, BuildSupport.STAGE_BUILD, result.stdout());
    }

    /**
     * 取构建命令的首个可执行词，跳过 {@code KEY=VALUE} 形式的环境变量前缀。
     *
     * <p>例如 {@code JAVA_HOME=/usr/lib/jdk1.8.0_121 mvn -B clean package} 的首词是 mvn，
     * 而不是 JAVA_HOME 赋值——否则预检会把环境变量名当成命令去查。</p>
     *
     * @param command 完整构建命令
     * @return 首词；命令为空或全是变量赋值时返回 null
     */
    String firstCommandToken(String command) {
        if (!StringUtils.hasText(command)) {
            return null;
        }
        for (String token : command.strip().split("\\s+")) {
            if (support.isEnvAssignment(token)) {
                continue;
            }
            return token;
        }
        return null;
    }
}
