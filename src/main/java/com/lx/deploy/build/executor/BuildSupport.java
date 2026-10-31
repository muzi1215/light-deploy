package com.lx.deploy.build.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.deploy.build.model.BuildArtifact;
import com.lx.deploy.common.crypto.CredentialCrypto;
import com.lx.deploy.common.enums.build.ArtifactType;
import com.lx.deploy.common.enums.credential.CredentialType;
import com.lx.deploy.common.enums.project.BuildMode;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.common.util.ComposeInjection;
import com.lx.deploy.common.util.GitAuthSupport;
import com.lx.deploy.common.util.RegistryAuthSupport;
import com.lx.deploy.common.util.SecretMasker;
import com.lx.deploy.common.util.ShellQuote;
import com.lx.deploy.credential.mapper.CredentialMapper;
import com.lx.deploy.credential.model.Credential;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.registry.mapper.RegistryMapper;
import com.lx.deploy.registry.model.Registry;
import com.lx.deploy.release.service.ReleaseRetentionExecutor;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshCommandTimeoutException;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.service.TaskLogService;
import com.lx.deploy.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 构建执行的共用支撑：工作区准备、代码拉取、Docker 构建/推送/验证、产物转存、
 * 配置转存、日志与进度、字符串/路径工具，以及所有「与构建什么无关」的零件。
 *
 * <p>三个构建 Service（{@link DockerBuilder} / {@link JarPrebuildBuilder} / {@link ScriptBuildService}）
 * 各自只持有「自己这一类构建专属」的逻辑，凡是多处复用、与运行时/构建模式无关的能力都集中在这里，
 * 避免三个 Service 各抄一份导致行为分化。</p>
 *
 * <p>本类不持有任何运行时分支判断，也不感知编排：它只提供「怎么做」的零件，什么时候做由 Service 与编排器决定。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Slf4j
@Component
public class BuildSupport {

    /** 阶段名：建立连接。 */
    static final String STAGE_CONNECT = "建立连接";
    /** 阶段名：准备工作区。 */
    static final String STAGE_PREPARE = "准备工作区";
    /** 阶段名：拉取代码。 */
    static final String STAGE_CLONE = "拉取代码";
    /** 阶段名：执行构建。 */
    static final String STAGE_BUILD = "执行构建";
    /** 阶段名：推送产物。 */
    static final String STAGE_PUSH = "推送产物";
    /** 阶段名：收尾。 */
    static final String STAGE_FINISH = "收尾";

    /** 产物持久目录内存放随行配置文件的子目录名，与目标机 JAR 工作目录的 config 目录对应。 */
    static final String CONFIG_SUBDIR = "config";

    /**
     * 热修包内 jar 名的兜底值，仅在拿不到产物真实文件名时使用。
     *
     * <p>正常情况下热修包里的 jar <b>沿用构建产物自己的文件名</b>（如 {@code auth-tool.jar}）：
     * 该名字就是 {@code artifact_path} 定位到的那个文件的名字，属于既成事实，
     * 既不必从 pom 的 {@code <finalName>} 反推（要处理属性插值、模块/根 pom 优先级），
     * 也不必让运维在配置里再抄一遍（与 {@code artifact_path} 构成两个真相来源）。
     * 好处是运维解开热修包一眼能认出是哪个服务的包，而不是满目录同名 {@code app.jar}。</p>
     */
    static final String DEFAULT_JAR_NAME = "app.jar";

    /** 热修包内平铺 Dockerfile 的文件名，由项目仓库提供（约定与 dockerfile_path 同目录）。 */
    static final String HOTFIX_DOCKERFILE = "Dockerfile.hotfix";

    /** 热修包内热修编排文件名，由项目仓库提供（与 dockerfile_path 同目录，RUN 形态可缺省）。 */
    static final String HOTFIX_COMPOSE = "docker-compose.hotfix.yml";

    /** 热修包 tar.gz 文件名；发布端据此后缀判定「必须始终下发」，与源码上下文区分。 */
    static final String HOTFIX_KIT_FILE = "hotfix-kit.tar.gz";

    /**
     * 产物持久目录下的版本标识文件名。
     *
     * <p>与目标机工作目录下的同名文件一起，构成「这份产物是哪个版本」的机器可读依据。
     * 两边取值口径必须一致（见 {@code DeployTaskHandler} 的同名常量），
     * 不允许一边写版本号、另一边写提交短码。</p>
     */
    static final String VERSION_MARKER = "VERSION";

    /** 版本标识里 built_at 的时间格式，与部署日志的时间格式保持一致，便于人眼对照。 */
    static final DateTimeFormatter VERSION_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Git 鉴权相关的常量与地址拼装（令牌注入密码位、剥离 userinfo、非交互前缀、SSH 前缀）
    // 统一放在 GitAuthSupport：项目连通性测试要在同一批地址与凭据上做同样的处理，
    // 两处各写一套必然在「谁该补 oauth2」「谁该剔掉多余用户名」上分叉，
    // 表现为「测试说通、构建却失败」这类最难查的问题。

    /** 连接成功后的进度百分比。 */
    static final int PROGRESS_CONNECTED = 5;
    /** 工作区就绪后的进度百分比。 */
    static final int PROGRESS_PREPARED = 15;
    /** 代码拉取完成后的进度百分比。 */
    static final int PROGRESS_CLONED = 35;
    /** 构建进行中的进度百分比。 */
    static final int PROGRESS_BUILDING = 55;
    /** 推送/登记中的进度百分比。 */
    static final int PROGRESS_PUSHING = 80;
    /** 全部完成的进度百分比。 */
    static final int PROGRESS_DONE = 100;

    /** 单条命令输出最多写入日志的行数，避免镜像层输出刷屏。 */
    static final int MAX_OUTPUT_LINES = 30;

    /**
     * 命令失败时最多写入日志的错误输出行数。
     *
     * <p>比输出上限略宽：Java 类错误（如 {@code NoSuchMethodError}）的首行只有一句
     * 「找不到方法」，点名具体类与 jar 的 {@code at xxx.yyy(Zzz.java:行号)} 全在后面几行，
     * 只留首行等于没有定位信息。</p>
     */
    static final int MAX_ERROR_LINES = 40;

    /**
     * 构建类命令的日志行数上限（汇总回显、失败输出、超时补录共用）。
     *
     * <p>比普通命令宽一档：一次 Maven 构建动辄上百行，30 行只够看到开头，
     * 出问题时既看不到编译错误所在模块，也看不到最终 BUILD FAILURE 的摘要。</p>
     */
    static final int MAX_BUILD_OUTPUT_LINES = 200;

    /** 失败摘要保留的末尾行数：命令失败的原因（failed to solve 等）一律排在最后几行。 */
    static final int MAX_ERROR_HINT_LINES = 5;

    /**
     * 构建命令实时回传的行数上限。
     *
     * <p>实时回传是逐行落库的，必须封顶：首次构建的依赖下载输出可达数千行，
     * 不封顶会把日志表刷爆、页面也难以翻阅。超过后不再逐行回传，末尾仍会汇总补录。</p>
     */
    static final int MAX_STREAM_OUTPUT_LINES = 500;

    /** 构建机启动级验证默认观察窗口（秒）：容器以 {@code docker run -d} 后台起来，窗口内逐秒 inspect 判活，持续 Running 即视为通过。 */
    static final int DEFAULT_DOCKER_VERIFY_TIMEOUT_SECONDS = 30;

    /** JAR 预构建模式的 Maven 构建镜像（与项目 Dockerfile 的 builder 阶段同版本，复用已拉取的镜像）。 */
    static final String MAVEN_BUILDER_IMAGE = "maven:3.9.9-eclipse-temurin-8";

    /** JAR 预构建模式挂载到构建机的 Maven 本地仓库缓存目录，加速增量构建（首次全量下载，之后复用）。 */
    static final String M2_CACHE_HOST_DIR = "/lx/light-deploy/.m2";

    /**
     * 构建进程组 PID 文件的路径前缀。
     *
     * <p>放在 /tmp 而不是工作区：工作区是 git 仓库副本，任何多余文件都会出现在
     * {@code git status} 里；同时路径按任务 ID 隔离，同一台构建机上的并发任务互不干扰。</p>
     */
    static final String BUILD_PID_FILE_PREFIX = "/tmp/light-deploy-build-";

    /** 构建机缺少 setsid 时的报错特征，用于降级重跑一次不带进程组包装的命令。 */
    static final String SETSID_MISSING = "setsid: command not found";

    /** 从 docker inspect 输出中提取 sha256 摘要的正则。 */
    static final Pattern SHA256_PATTERN = Pattern.compile("sha256:[a-f0-9]{64}");

    /**
     * 非交互式 SSH 会话的环境兜底前缀。
     *
     * <p>平台通过 SSH 执行命令时不加载 /etc/profile 与 /etc/profile.d/*.sh，
     * PATH 通常只有 /usr/bin:/bin 等基础目录，导致「机器上已装、但靠 profile 导出 PATH」的
     * 工具（最典型是 mvn）在构建时找不到。这里先 source 一次登录环境把 PATH 引入；
     * 输出与错误一律丢弃，避免登录脚本的欢迎信息污染构建日志。
     * 用 {@code ;} 而非 {@code &&} 结尾：profile 不存在或返回非零不影响后续命令。</p>
     */
    static final String PROFILE_SOURCE_COMMAND = ". /etc/profile >/dev/null 2>&1; ";

    /** 构建命令可执行性预检的哨兵输出：命令可用。 */
    static final String CMD_PROBE_OK = "LIGHT_DEPLOY_CMD_OK";

    /** 构建命令可执行性预检的哨兵输出：命令未找到。 */
    static final String CMD_PROBE_MISSING = "LIGHT_DEPLOY_CMD_MISSING";

    /** SSH 会话工厂：负责解密服务器凭据并建立已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /** 凭据加解密组件：唯一的解密点，明文只在建连/登录前短暂存在。 */
    private final CredentialCrypto credentialCrypto;

    /** 凭据 Mapper：读取 Git / 仓库凭据密文（解密由 CredentialCrypto 完成）。 */
    private final CredentialMapper credentialMapper;

    /** 镜像仓库 Mapper：读取仓库地址与命名空间。 */
    private final RegistryMapper registryMapper;

    /** 服务器 Mapper：读取构建机连接配置。 */
    private final ServerMapper serverMapper;

    /** 任务服务：汇报执行阶段与进度。 */
    private final TaskService taskService;

    /** 任务日志服务：把执行过程写入 lds_task_log。 */
    private final TaskLogService taskLogService;

    /** 发布版本保留策略执行器：构建收尾时按项目保留数回收超额的历史版本。 */
    private final ReleaseRetentionExecutor retentionExecutor;

    /** JSON 解析器：解析 build_config_json。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建构建支撑组件。
     *
     * @param sshSessionFactory SSH 会话工厂
     * @param credentialCrypto  凭据加解密组件
     * @param credentialMapper  凭据 Mapper
     * @param registryMapper    镜像仓库 Mapper
     * @param serverMapper      服务器 Mapper
     * @param taskService       任务服务
     * @param taskLogService    任务日志服务
     * @param retentionExecutor 发布版本保留策略执行器
     */
    public BuildSupport(SshSessionFactory sshSessionFactory,
                       CredentialCrypto credentialCrypto,
                       CredentialMapper credentialMapper,
                       RegistryMapper registryMapper,
                       ServerMapper serverMapper,
                       TaskService taskService,
                       TaskLogService taskLogService,
                       ReleaseRetentionExecutor retentionExecutor) {
        this.sshSessionFactory = sshSessionFactory;
        this.credentialCrypto = credentialCrypto;
        this.credentialMapper = credentialMapper;
        this.registryMapper = registryMapper;
        this.serverMapper = serverMapper;
        this.taskService = taskService;
        this.taskLogService = taskLogService;
        this.retentionExecutor = retentionExecutor;
    }

    /**
     * 建立到构建机的已认证 SSH 会话。
     *
     * <p>连接细节统一收口在支撑组件，避免在编排器里散落会话工厂调用。</p>
     *
     * @param buildServer 构建机档案，提供连接配置
     * @return 已认证会话
     */
    SshSession openSession(Server buildServer) {
        return sshSessionFactory.open(buildServer);
    }

    /**
     * 在构建机上创建独立工作区。
     *
     * <p>先删除可能残留的同名目录（上次失败未清理），再新建，保证每次构建从干净状态开始。</p>
     *
     * @param task          当前任务
     * @param session       已认证的 SSH 会话
     * @param workspacePath 本次构建的工作区绝对路径
     * @throws DeployException 工作区创建失败时抛出
     */
    void prepareWorkspace(Task task, SshSession session, String workspacePath) {
        if (!reportProgress(task, STAGE_PREPARE, PROGRESS_PREPARED)) {
            return;
        }
        appendLog(task, STAGE_PREPARE, TaskLogLevel.INFO, "创建工作区 " + workspacePath);
        SshCommandResult result = session.exec("rm -rf " + workspacePath + " && mkdir -p " + workspacePath);
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "构建机工作区创建失败：" + firstLine(result.stderr()));
        }
    }

    /**
     * 拉取 Git 仓库代码。
     *
     * <p>根据仓库地址协议与凭据类型选择鉴权方式：HTTPS + GIT_TOKEN 把 Token 嵌入 URL（日志脱敏）；
     * SSH 协议或 SSH_KEY 类型凭据把私钥写到临时文件并用 GIT_SSH_COMMAND 引用。克隆后取 HEAD 提交 ID。</p>
     *
     * @param task          当前任务
     * @param session       已认证的 SSH 会话
     * @param project       项目（含 gitUrl / gitBranch / gitCredentialId）
     * @param config        构建配置（当前未用到 module 维度，预留）
     * @param workspacePath 工作区路径，仓库克隆到此目录
     * @return 实际构建所用的 Git 提交 ID（可能为 null，直接发布产物场景）
     * @throws DeployException 来源非 GIT、凭据缺失/类型不符或克隆失败时抛出
     */
    String cloneRepository(Task task, SshSession session, Project project,
                           BuildConfig config, String workspacePath) {
        if (!reportProgress(task, STAGE_CLONE, PROGRESS_CLONED)) {
            return null;
        }
        // v1 只支持 GIT 来源；UPLOAD 需 ARTIFACT_ONLY，尚未实现。
        String sourceType = project.getSourceType() == null ? "GIT" : project.getSourceType();
        if (!"GIT".equals(sourceType)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "v1 仅支持 GIT 来源构建（UPLOAD 需 ARTIFACT_ONLY 模式，暂未实现）");
        }
        if (!StringUtils.hasText(project.getGitUrl())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目未配置 Git 仓库地址");
        }

        // 拼装克隆命令：凭据仅在此处短暂参与，绝不写入日志。
        CloneCommand clone = buildCloneCommand(task, session, project, workspacePath);
        appendLog(task, STAGE_CLONE, TaskLogLevel.INFO, "执行克隆：" + maskSecret(clone.command()));
        SshCommandResult result;
        try {
            // 克隆是耗时命令（大仓库可达几分钟），按长命令超时等待，而不是 120 秒的秒级超时。
            result = session.execLong(clone.command());
        } catch (SshCommandTimeoutException e) {
            // git 的克隆进度写在 stderr，超时前收到的部分必须落日志：否则无法判断是网络慢还是卡在认证。
            writeError(task, STAGE_CLONE, maskSecret(e.getPartialResult().stderr()), MAX_BUILD_OUTPUT_LINES);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "Git 克隆超时（" + e.getTimeoutSeconds() + " 秒）：" + maskSecret(project.getGitUrl()));
        }
        if (!result.success()) {
            // stderr 完整落日志：克隆失败的原因常常不在首行（认证提示、仓库/分支纠错建议都在后面）。
            writeError(task, STAGE_CLONE, maskSecret(result.stderr()), MAX_BUILD_OUTPUT_LINES);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "Git 克隆失败：" + firstLine(result.stderr()));
        }
        // 克隆进度由 git 写在 stderr，且非交互式会话（非 TTY）下默认静默，stdout 永远是空的。
        // 因此成功时不能只回显 stdout（会出现「（命令无输出）」的假象），要显式给一条结论。
        appendLog(task, STAGE_CLONE, TaskLogLevel.INFO, "代码拉取成功：" + maskSecret(project.getGitUrl()));
        writeCommandOutput(task, STAGE_CLONE, result);

        // 令牌形态的地址会被 git 原样写进工作区的 .git/config，克隆成功后立刻复位成不带凭据的地址。
        if (clone.cleanUrl() != null) {
            resetRemoteUrl(task, session, workspacePath, clone.cleanUrl());
        }

        // 取实际提交 ID，用于发布版本号与溯源。
        SshCommandResult commitResult = session.exec("git -C " + workspacePath + " rev-parse HEAD");
        if (commitResult.success() && StringUtils.hasText(commitResult.stdout())) {
            String commit = commitResult.stdout().strip();
            appendLog(task, STAGE_CLONE, TaskLogLevel.INFO, "代码拉取完成，当前提交：" + commit);
            return commit;
        }
        appendLog(task, STAGE_CLONE, TaskLogLevel.INFO, "未能读取 Git 提交 ID（直接发布产物场景可忽略）");
        return null;
    }

    /**
     * 拼装 Git 克隆命令（含凭据）。
     *
     * <p>三种鉴权形态：HTTPS + GIT_TOKEN 把令牌注入 URL 的密码位、SSH + SSH_KEY 把私钥写到临时文件
     * 并用 GIT_SSH_COMMAND 引用、无凭据直接克隆（仅公开仓库）。命令前统一加
     * {@link GitAuthSupport#NO_PROMPT_PREFIX}，避免 git 在非交互会话里转去索要凭据、把真实原因掩盖成
     * {@code could not read Password}。</p>
     *
     * @param task    当前任务，仅用于写关键步骤日志
     * @param session 已认证会话，SSH_KEY 类型时用于写私钥文件
     * @param project 项目
     * @param workspacePath 本次构建的工作区绝对路径
     * @return 克隆命令与其配套信息（调用方负责脱敏后再写日志）
     * @throws DeployException 凭据类型与仓库协议不匹配时抛出
     */
    CloneCommand buildCloneCommand(Task task, SshSession session, Project project, String workspacePath) {
        String gitUrl = project.getGitUrl();
        // 有凭据时解密并使用；公开仓库直接克隆。
        if (StringUtils.hasText(project.getGitCredentialId())) {
            Credential credential = credentialMapper.selectById(project.getGitCredentialId());
            if (credential == null) {
                throw new DeployException(ResponseCode.DATA_NOT_FOUND, "项目绑定的 Git 凭据不存在");
            }
            String plaintext = credentialCrypto.decrypt(credential.getEncryptedValue());
            CredentialType type = CredentialType.fromCode(credential.getType());
            if (type == CredentialType.GIT_TOKEN) {
                if (!GitAuthSupport.isHttps(gitUrl)) {
                    throw new DeployException(ResponseCode.VALIDATION_ERROR,
                            "GIT_TOKEN 凭据仅适用于 HTTPS 协议仓库，请改用 SSH 地址或 SSH_KEY 凭据");
                }
                // 令牌注进 URL 的密码位（形态见 GitAuthSupport.buildTokenUrl），明文不落日志。
                // 同时带出「不带凭据的干净地址」：git 会把含令牌的完整地址写进 .git/config，克隆成功后要复位。
                return new CloneCommand(
                        GitAuthSupport.NO_PROMPT_PREFIX + "git clone "
                                + GitAuthSupport.branchOption(project.getGitBranch())
                                + GitAuthSupport.buildTokenUrl(gitUrl, plaintext) + " " + workspacePath,
                        GitAuthSupport.stripUserInfo(gitUrl));
            }
            if (type == CredentialType.SSH_KEY) {
                // SSH 地址形如 git@host:组/仓库.git。配成 HTTPS 地址时私钥根本不会被使用，
                // 只会拿到「认证失败 / 仓库不存在」这类误导性报错，所以在这里直接拦下。
                if (GitAuthSupport.isHttpProtocol(gitUrl)) {
                    throw new DeployException(ResponseCode.VALIDATION_ERROR,
                            "SSH_KEY 凭据仅适用于 SSH 协议仓库（如 git@gitee.com:组/仓库.git），当前地址为 HTTPS 形式");
                }
                // 私钥写到临时文件，用 GIT_SSH_COMMAND 引用；写文件时私钥以 base64 传输避免换行问题。
                String keyFile = "/tmp/ld_git_key_" + project.getCode() + "_" + task.getBizId();
                writeGitKey(task, session, keyFile, plaintext);
                // SSH 协议下凭据不进 URL，无需复位 remote url。
                return new CloneCommand(
                        GitAuthSupport.NO_PROMPT_PREFIX + GitAuthSupport.sshCommandPrefix(keyFile) + "git clone "
                                + GitAuthSupport.branchOption(project.getGitBranch()) + gitUrl + " " + workspacePath,
                        null);
            }
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "Git 凭据类型必须为 GIT_TOKEN 或 SSH_KEY，当前为 " + credential.getType());
        }
        // 无凭据：仅公开仓库可克隆。同样禁掉交互提示，私有仓库会立刻报出明确原因而不是「等密码」。
        return new CloneCommand(
                GitAuthSupport.NO_PROMPT_PREFIX + "git clone "
                        + GitAuthSupport.branchOption(project.getGitBranch()) + gitUrl + " " + workspacePath,
                null);
    }

    /**
     * 把工作区的 remote url 复位成不带凭据的地址。
     *
     * <p>{@code git clone https://oauth2:令牌@主机/路径} 会把**含明文令牌的完整地址**写进
     * 工作区的 {@code .git/config}（{@code remote.origin.url}）。工作区在任务收尾时虽会整体删除，
     * 但从克隆成功到清理之间令牌是明文落盘的，因此在克隆成功后立即复位。</p>
     *
     * <p>复位失败只记 INFO 日志、不影响构建结论：构建已经成功，缩小凭据存续面属尽力而为。</p>
     *
     * @param task          当前任务
     * @param session       已认证会话
     * @param workspacePath 工作区路径
     * @param cleanUrl      不带凭据的仓库地址
     */
    void resetRemoteUrl(Task task, SshSession session, String workspacePath, String cleanUrl) {
        SshCommandResult result = session.exec(
                "git -C " + workspacePath + " remote set-url origin " + cleanUrl);
        if (result.success()) {
            appendLog(task, STAGE_CLONE, TaskLogLevel.INFO, "已复位 remote url 为不带凭据的地址");
        } else {
            appendLog(task, STAGE_CLONE, TaskLogLevel.INFO,
                    "复位 remote url 失败（不影响构建）：" + firstLine(result.stderr()));
        }
    }

    /**
     * 克隆命令与其配套信息。
     *
     * @param command  完整可执行的克隆命令（含凭据，调用方必须脱敏后再写日志）
     * @param cleanUrl 克隆成功后应复位成的不带凭据地址；无需复位时为 null（SSH 协议与公开仓库）
     */
    private record CloneCommand(String command, String cleanUrl) {
    }

    /**
     * 把 Git SSH 私钥写入构建机临时文件（base64 中转，避免私钥换行破坏单条命令）。
     *
     * @param task      当前任务
     * @param session   已认证会话
     * @param keyFile   目标私钥文件路径
     * @param plaintext 私钥明文（仅在本方法内短暂存在）
     */
    void writeGitKey(Task task, SshSession session, String keyFile, String plaintext) {
        writeSecretFile(task, session, keyFile, plaintext, "Git SSH 私钥", STAGE_CLONE);
    }

    /**
     * 把敏感文本写入构建机临时文件（权限 600，base64 中转）。
     *
     * <p>用 base64 而不是 heredoc：私钥、密码都可能含换行或引号，直接拼进单条命令会被 shell 吃掉，
     * base64 后只剩字母数字与 {@code +/=}，拼命令安全。文件权限设为 600，避免同机其他用户读取。</p>
     *
     * <p>日志只写文件路径与用途说明，**绝不回显内容**。</p>
     *
     * @param task      当前任务，用于写日志
     * @param session   已认证会话
     * @param path      目标文件路径
     * @param content   敏感内容明文
     * @param label     用途标签，仅用于日志文案（如「Git SSH 私钥」）
     * @param stage     日志所属阶段，与调用方所在阶段一致
     * @throws DeployException 写入失败时抛出
     */
    void writeSecretFile(Task task, SshSession session, String path, String content, String label, String stage) {
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String cmd = "echo " + b64 + " | base64 -d > " + path + " && chmod 600 " + path;
        SshCommandResult result = session.exec(cmd);
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "写入" + label + "到构建机临时文件失败：" + firstLine(result.stderr()));
        }
        appendLog(task, stage, TaskLogLevel.INFO, "已写入" + label + "（内容不落日志）到 " + path);
    }

    /**
     * 把构建上下文打成 tarball 持久化到构建机产物目录，供 COMPOSE 形态指定 work_dir 时下发到目标机重建。
     *
     * <p>仅 DOCKER 运行时在 {@code buildDocker} 成功后、工作区清理前调用。
     * tarball 是「够用就好」的重建素材，不是整仓快照：</p>
     * <ul>
     *   <li><b>配了 {@code module_dir} 就只打「根 pom + 该模块目录」</b>：整仓打包会把兄弟模块的源码
     *       一起下发到目标机，运维在机器上看到一堆跟本次发布无关的目录（多模块仓库尤其明显）。
     *       兄弟模块缺失不影响重建——子模块 pom 若没有依赖到它们，用 {@code -f <module>/pom.xml}
     *       单模块自己就能构建起来；</li>
     *   <li><b>剔除 {@code logs} 与 {@code *.log}</b>：日志是运行时沉淀物，既不属于源码，
     *       也可能混入其他服务历史上的输出，下发过去只会污染目标机、让人误判；</li>
     *   <li>同时排除 .git 与常见构建产物目录（target / node_modules / dist / build 等）。</li>
     * </ul>
     * <p>docker build 的上下文目录仍是仓库根语义（含根 pom + 目标模块），运维解包后即可
     * {@code docker build -f <dockerfile> .}；Dockerfile 通常就在目标模块目录内，随之一并落入 tarball。</p>
     *
     * <p>打包失败只记 WARN 不阻断：上下文是「应急上机重建」的辅助材料，一次已成功的镜像构建不应因为它而失败；
     * 返回 null 表示本次未产出上下文 tarball，发布阶段据此跳过下发。</p>
     *
     * @param task           当前任务
     * @param session        已认证的构建机会话
     * @param buildServer    构建机档案，仅用于日志语义
     * @param workspacePath  仓库工作区绝对路径（即 docker build 上下文目录）
     * @param artifactBase   构建机产物持久目录，已校验非空
     * @param project        项目，提供编码
     * @param config         构建配置，提供模块目录（module_dir / module_name）用于收敛打包范围
     * @param releaseVersion 发布版本号，作为产物持久目录名
     * @return 构建机上的 tarball 绝对路径；打包失败或无需打包时返回 null
     */
    String captureBuildContext(Task task, SshSession session, Server buildServer,
                              String workspacePath, String artifactBase,
                              Project project, BuildConfig config, String releaseVersion) {
        String persistDir = artifactBase.strip() + "/" + project.getCode() + "/" + releaseVersion;
        String tarPath = persistDir + "/build-context.tar.gz";
        // 排除项：版本控制、构建产物与运行时垃圾（日志 / IDE 配置），避免把几十~几百 MB 的无用内容打进 tarball。
        String excludes = "--exclude=.git --exclude=node_modules --exclude=target "
                + "--exclude=dist --exclude=build --exclude=logs --exclude=.idea "
                + "--exclude='*.class' --exclude='*.jar' --exclude='*.war' --exclude='*.log'";
        // 打包范围：多模块仓库收敛到「根 pom + 本次部署的模块」，其余模块不下发。
        // 目录口径必须与执行构建命令时的 cd 目录同源（见 resolveModuleDir），否则会出现
        // 「构建在子模块里执行、上下文却把整仓源码下发到目标机」的割裂。
        String moduleDir = resolveModuleDir(config);
        String sources = StringUtils.hasText(moduleDir) ? "pom.xml " + shellQuote(moduleDir) : ".";
        String cmd = "mkdir -p " + shellQuote(persistDir)
                + " && cd " + shellQuote(workspacePath)
                + " && tar czf " + shellQuote(tarPath) + " " + excludes + " " + sources;
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "打包构建上下文到 " + tarPath);
        SshCommandResult result = session.exec(cmd);
        if (!result.success()) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "构建上下文打包失败（不影响镜像发布）：" + briefError(result.stderr())
                            + "；若提示找不到 pom.xml，请确认 module_dir / module_name 填的是真实存在的子模块目录");
            return null;
        }
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "构建上下文已打包 " + tarPath);
        return tarPath;
    }

    /**
     * 把「平铺热修包」打到构建机产物目录，供平台或镜像仓库不可用时在目标机就地重建。
     *
     * <p>与 {@link #captureBuildContext} 的关键差异是<b>必须包含 jar</b>：B1 的目标机没有源码、
     * 也没有 maven，运维手里唯一能替换的就是这个 jar。因此上下文被收敛成「jar + 一个平铺
     * Dockerfile + 可选 hotfix compose + 可选配置 + README」，解包后直接
     * {@code docker build -f Dockerfile.hotfix .}，<b>不需要还原仓库目录树</b>——
     * 那恰恰是平台已经挂掉、运维最慌的时候最不该做的事。</p>
     *
     * <p>包内所有编排文件都由项目仓库提供（约定放在 dockerfile_path 同目录），平台只做收集与打包，
     * 不生成任何 Dockerfile / compose，与「平台不猜项目编排」的硬约束一致。</p>
     *
     * <p>缺 {@code Dockerfile.hotfix} 视为项目未启用该能力：记日志后返回 null，不影响构建结论——
     * 热修包是「应急素材」，不该让一次成功的镜像构建因为它失败。</p>
     *
     * @param task           当前任务
     * @param session        已认证构建机会话
     * @param project        项目，提供编码
     * @param config         构建配置，提供 dockerfile_path / artifact_path / module_dir
     * @param workspacePath  仓库工作区绝对路径
     * @param artifactBase   构建机产物持久目录
     * @param releaseVersion 发布版本号，作为产物持久目录名
     * @return 热修包 tar.gz 的绝对路径；未启用或打包失败时返回 null
     */
    String captureHotfixKit(Task task, SshSession session, Project project, BuildConfig config,
                           String workspacePath, String artifactBase, String releaseVersion) {
        String dockerfileDir = parentDir(config.getDockerfilePath());
        if (!StringUtils.hasText(dockerfileDir)) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "未配置 dockerfile_path，跳过热修包（平台不可用时该版本无法就地重建）");
            return null;
        }
        String hotfixDockerfile = dockerfileDir + "/" + HOTFIX_DOCKERFILE;
        if (!exists(session, workspacePath + "/" + hotfixDockerfile)) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "项目未提供 " + hotfixDockerfile + "，跳过热修包（平台不可用时该版本无法就地重建）");
            return null;
        }
        String jarFile = locateJarArtifact(session, workspacePath, config);
        if (!StringUtils.hasText(jarFile)) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "未定位到 jar 产物，跳过热修包");
            return null;
        }
        // 包内沿用产物自己的文件名（auth-tool.jar）：这是既成事实，不从 pom 反推、不让运维另配。
        String jarName = fileNameOf(jarFile);

        String persistDir = artifactBase.strip() + "/" + project.getCode() + "/" + releaseVersion;
        String staging = persistDir + "/hotfix-kit";
        String tarPath = persistDir + "/" + HOTFIX_KIT_FILE;
        // 重置暂存目录：上次失败可能留下半成品，混进本次包会让运维拿到错误内容。
        if (!session.exec("rm -rf " + shellQuote(staging) + " && mkdir -p "
                + shellQuote(staging + "/" + CONFIG_SUBDIR)).success()) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "创建热修包暂存目录失败，跳过热修包");
            return null;
        }
        // 平铺 Dockerfile 用 COPY *.jar，与具体 jar 名解耦，产物改名后无需同步改 Dockerfile。
        if (!copyIfPresent(session, jarFile, staging + "/" + jarName)) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "复制 jar 到热修包失败，跳过热修包");
            return null;
        }
        copyIfPresent(session, workspacePath + "/" + hotfixDockerfile, staging + "/" + HOTFIX_DOCKERFILE);

        // hotfix 编排（可选）：RUN 形态项目可以没有；缺失时 README 只给 docker build + docker run。
        boolean hasCompose = copyIfPresent(session,
                workspacePath + "/" + dockerfileDir + "/" + HOTFIX_COMPOSE, staging + "/" + HOTFIX_COMPOSE);
        // 配置（可选）：解包后由 compose 的 ./config 挂载到 /app/config，改配置不必重建镜像。
        copyIfPresent(session,
                workspacePath + "/" + dockerfileDir + "/application.yml",
                staging + "/" + CONFIG_SUBDIR + "/application.yml");

        String readme = buildHotfixReadme(project, releaseVersion, jarName, hasCompose);
        if (!session.exec(ShellQuote.heredocWrite(staging + "/README.md", readme)).success()) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "写入热修包 README 失败，跳过热修包");
            return null;
        }

        // 在暂存目录内打包（-C staging .），解包后文件直接平铺落到目标机 build-context/，不套一层目录。
        SshCommandResult pack = session.exec("tar czf " + shellQuote(tarPath)
                + " -C " + shellQuote(staging) + " . && rm -rf " + shellQuote(staging));
        if (!pack.success()) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "热修包打包失败（不影响镜像发布）：" + briefError(pack.stderr()));
            return null;
        }
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "热修包已生成 " + tarPath
                + "（" + jarName + " / " + HOTFIX_DOCKERFILE + (hasCompose ? " / " + HOTFIX_COMPOSE : "")
                + "，平台不可用时下发目标机就地重建）");
        return tarPath;
    }

    /**
     * 取相对路径的父目录；不含斜杠时返回空串（表示仓库根）。
     *
     * @param path 相对仓库根的路径，如 {@code auth-tool/Docker/Dockerfile.jre}
     * @return 父目录，如 {@code auth-tool/Docker}；无可切分时返回空串
     */
    String parentDir(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String trimmed = path.strip();
        int idx = trimmed.lastIndexOf('/');
        return idx < 0 ? "" : trimmed.substring(0, idx);
    }

    /**
     * 从绝对路径中取文件名，取不到时回落 {@link #DEFAULT_JAR_NAME}。
     *
     * <p>热修包里的 jar 名直接沿用构建产物自己的文件名（如 {@code auth-tool.jar}）。
     * 该名字由 artifact_path 定位到的文件决定，属既成事实：比读 pom 的 {@code <finalName>} 可靠
     * （后者可能是 {@code ${project.artifactId}} 这类属性引用，还要处理模块 pom 与根 pom 的优先级），
     * 也比再让运维配一个 {@code jar_name} 好（与 artifact_path 构成两个会打架的真相来源）。</p>
     *
     * @param path 绝对路径，如 {@code /workspace/auth-tool/linux/auth-tool-7888/auth-tool.jar}
     * @return 文件名，如 {@code auth-tool.jar}；路径为空或无文件名时返回 {@link #DEFAULT_JAR_NAME}
     */
    String fileNameOf(String path) {
        if (!StringUtils.hasText(path)) {
            return DEFAULT_JAR_NAME;
        }
        String trimmed = path.strip();
        String name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return StringUtils.hasText(name) ? name : DEFAULT_JAR_NAME;
    }

    /**
     * 判断构建机上某个绝对路径是否存在。
     *
     * @param session 已认证会话
     * @param absPath 绝对路径
     * @return 存在返回 true
     */
    boolean exists(SshSession session, String absPath) {
        return session.exec("test -e " + shellQuote(absPath)).success();
    }

    /**
     * 源文件存在才复制，不存在直接返回 false（不抛异常，用于可选素材）。
     *
     * @param session 已认证会话
     * @param from    源绝对路径
     * @param to      目标绝对路径
     * @return 复制成功返回 true；源不存在或复制失败返回 false
     */
    boolean copyIfPresent(SshSession session, String from, String to) {
        return exists(session, from) && session.exec("cp " + shellQuote(from) + " " + shellQuote(to)).success();
    }

    /**
     * 按 artifact_path 定位 jar：仓库根优先，未命中再回落模块目录。
     *
     * <p>构建与热修包打包共用这一份定位口径，避免「镜像里装的是 A、热修包里装的是 B」。
     * 调用方须先保证 {@code artifact_path} 非空。</p>
     *
     * @param session       已认证会话
     * @param workspacePath 仓库根工作区绝对路径
     * @param config        构建配置
     * @return jar 的绝对路径；未找到返回 null
     */
    String locateJarArtifact(SshSession session, String workspacePath, BuildConfig config) {
        if (config == null || !StringUtils.hasText(config.getArtifactPath())) {
            return null;
        }
        String jarFile = locateFile(session, workspacePath, config.getArtifactPath());
        if (!StringUtils.hasText(jarFile)) {
            String moduleDir = resolveModuleDir(config);
            if (StringUtils.hasText(moduleDir)) {
                jarFile = locateFile(session, workspacePath + "/" + moduleDir, config.getArtifactPath());
            }
        }
        return jarFile;
    }

    /**
     * 生成热修包内的 README：只写命令级自救指引，不生成任何编排文件（编排由项目提供）。
     *
     * @param project        项目，提供编码
     * @param releaseVersion 版本号，写进 README 便于人工比对
     * @param jarName        包内 jar 文件名（沿用产物原名，如 {@code auth-tool.jar}），用于换包章节
     * @param hasCompose     包内是否带了 hotfix compose（RUN 形态可能没有）
     * @return README 正文
     */
    String buildHotfixReadme(Project project, String releaseVersion, String jarName, boolean hasCompose) {
        String code = project.getCode();
        String image = code + ":hotfix";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(code).append(" 应急重建包（").append(releaseVersion).append("）\n\n")
                .append("> 用途：平台或镜像仓库不可用时，在本目录就地重建镜像并启动；")
                .append("不依赖平台、不依赖构建机、不需要源码。\n\n")
                .append("## 一键（推荐）\n\n```bash\ncd build-context\n");
        if (hasCompose) {
            sb.append("docker compose -p ").append(code)
                    .append(" -f ").append(HOTFIX_COMPOSE).append(" up -d --build\n");
        } else {
            sb.append("docker build -f ").append(HOTFIX_DOCKERFILE).append(" -t ").append(image).append(" .\n")
                    .append("docker rm -f ").append(code).append(" 2>/dev/null || true\n")
                    .append("docker run -d --restart=always --name ").append(code)
                    .append(" <端口/挂载参数，可从 docker inspect 旧容器取得> ").append(image).append("\n");
        }
        sb.append("```\n\n");
        if (hasCompose) {
            sb.append("## 分两步（便于单独查看构建日志）\n\n```bash\n")
                    .append("docker build -f ").append(HOTFIX_DOCKERFILE).append(" -t ").append(image).append(" .\n")
                    .append("docker compose -p ").append(code)
                    .append(" -f ").append(HOTFIX_COMPOSE).append(" up -d\n```\n\n");
        }
        sb.append("## 应急换包（只换 jar，不碰其他文件）\n\n")
                .append("```bash\ncd build-context\n")
                .append("cp /path/to/new.jar ./").append(jarName).append("   # 覆盖同名文件\n")
                .append("docker compose -p ").append(code).append(" -f ").append(HOTFIX_COMPOSE)
                .append(" up -d --build\n```\n\n")
                .append("> **必须保持文件名 `").append(jarName).append("` 不变**：`").append(HOTFIX_DOCKERFILE)
                .append("` 用 `COPY *.jar` 定位 jar，目录里出现两个 jar 会直接报错（more than one source file）。\n")
                .append("> 旧包不要在同一目录留 `.jar` 备份，挪到 `bak/` 子目录再放新包。\n\n")
                .append("## 注意\n\n")
                .append("- 配置通过 `./config` 挂载到 `/app/config`；改配置后需 `docker compose restart` 才生效。\n")
                .append("- 手工构建的镜像 tag 平台不认识；平台恢复后请补一次正规部署，让平台记录与线上对齐。\n");
        return sb.toString();
    }

    /**
     * 解析本次构建对应的模块目录：module_dir → module_name → 空（仓库根）。
     *
     * <p>必须全平台只用这一份实现：决定「cd 到哪个目录执行构建命令」的 {@code buildFileArtifact}
     * 与决定「哪些源码进上下文 tarball」的 {@code captureBuildContext} 若各自取一套值，就会出现
     * 「构建在子模块里执行、下发到目标机的却是整仓源码」的割裂——运维上机看到一堆跟本次发布无关的兄弟模块。</p>
     *
     * <p>回落 module_name 是必要的：UI 上多数多模块仓库目录名与模块名同名，习惯只填 module_name
     * （前端「模块目录」的说明也是「默认等于子模块名」），只认 module_dir 会让这类项目悄悄退回整仓打包。</p>
     *
     * @param config 构建配置，可能为 null（此时按仓库根处理）
     * @return 相对仓库根的模块目录，仓库根返回空串
     */
    String resolveModuleDir(BuildConfig config) {
        if (config == null) {
            return "";
        }
        if (StringUtils.hasText(config.getModuleDir())) {
            return config.getModuleDir().trim();
        }
        if (StringUtils.hasText(config.getModuleName())) {
            return config.getModuleName().trim();
        }
        return "";
    }

    /**
     * 读取项目自带的 docker-compose.yml 原文。
     *
     * <p>「显式配置了路径」与「顺着默认文件名找」的失败语义完全不同，必须区别对待：</p>
     * <ul>
     *   <li><b>显式配置却读不到</b>：路径写错或文件没提交，直接报错。静默跳过会把错误推迟到
     *       发布阶段才爆——而那时往往是在半夜发版，排障成本翻好几倍；</li>
     *   <li><b>默认文件不存在</b>：项目压根不用 compose（绝大多数 {@code docker run} 项目如此），
     *       静默返回 null，不能因为缺一个可选文件就让构建失败。</li>
     * </ul>
     *
     * <p>读到内容后统一交给 {@link ComposeInjection#requireUsable} 校验：缺少版本占位符的 compose
     * 会让容器始终拉同一个 tag，而发布流程全程报成功——这类错误必须在构建期拦下来。</p>
     *
     * @param task          当前任务
     * @param session       已认证会话
     * @param config        构建配置，提供 compose_file_path
     * @param workspacePath 仓库工作区绝对路径
     * @return compose 原文；项目未使用 compose 时返回 null
     * @throws DeployException 显式配置的路径读不到，或内容缺少版本占位符时抛出
     */
    String readComposeFile(Task task, SshSession session, BuildConfig config, String workspacePath) {
        boolean explicit = StringUtils.hasText(config.getComposeFilePath());
        String relative = explicit ? config.getComposeFilePath().strip() : ComposeInjection.COMPOSE_FILE;
        SshCommandResult result = session.exec("cat " + shellQuote(workspacePath + "/" + relative));
        if (!result.success()) {
            if (!explicit) {
                return null;
            }
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "读取 compose 文件失败（仓库路径 " + relative + "）：" + briefError(result.stderr())
                            + "。请确认该文件已提交到仓库，且 compose_file_path 写的是相对仓库根目录的路径");
        }
        String content = ComposeInjection.requireUsable(result.stdout());
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                "已快照 compose 文件 " + relative + "（" + content.length() + " 字符）");
        return content;
    }

    /**
     * 把远程命令的错误输出压成适合放进异常文案的一行。
     *
     * @param stderr 命令标准错误输出，可为 null
     * @return 去除换行并截断后的文本；无内容时返回「无错误输出」
     */
    String briefError(String stderr) {
        if (!StringUtils.hasText(stderr)) {
            return "无错误输出";
        }
        String flat = stderr.strip().replace("\n", " ").replace("\r", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /**
     * 读取构建机上必须配置的目录，未配置时抛出可操作的校验错误。
     *
     * <p>目录在服务器页面维护、数据库列带默认值，正常情况不会为空；
     * 这里只兜住历史数据或手工改库造成的空值，并明确指向「去服务器页面配置」，
     * 而不是在代码里猜一个路径静默兜底——后者会把问题藏进远程命令的失败里，难以定位。</p>
     *
     * @param value 服务器档案中的目录值，允许为 null
     * @param label 目录用途名称，用于错误提示
     * @return 去掉首尾空白后的目录路径
     * @throws DeployException 目录未配置时抛出
     */
    String requireDir(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "构建机未配置" + label + "，请在服务器页面补充该目录后重新构建");
        }
        return value.strip();
    }

    // ==================== Docker 相关辅助 ====================

    /**
     * 在构建机上执行 docker build。
     *
     * @param session       已认证会话
     * @param workspacePath 构建上下文根目录
     * @param dockerfileOpt -f / --build-arg 等选项
     * @param fullImage     目标镜像全名（含 tag）
     * @throws DeployException 构建失败时抛出
     */
    void dockerBuild(Task task, SshSession session, String workspacePath, String dockerfileOpt, String fullImage) {
        String cmd = "cd " + workspacePath + " && docker build " + dockerfileOpt + " -t " + fullImage + " .";
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "执行 docker build（输出实时回传）：" + cmd);
        // 镜像构建是耗时命令（拉基础镜像、装依赖、编译），按长命令超时等待，并实时回传逐行输出。
        SshCommandResult result = session.execLong(cmd, streamingLogConsumer(task, STAGE_BUILD));
        if (!result.success()) {
            String raw = StringUtils.hasText(result.stderr()) ? result.stderr() : result.stdout();
            // 摘要取末尾而不是首行：buildkit 第一行固定是 "#0 building with ... instance using docker driver"，
            // 那只是开场白，真正的失败（failed to solve / 基础镜像拉不动 / COPY 找不到文件）排在最后。
            String hint = tailLines(raw, MAX_ERROR_HINT_LINES);
            // 把完整 stderr/stdout 落日志：docker build 的失败原因全在 stderr，摘要之外这里补全细节。
            writeError(task, STAGE_BUILD, raw, MAX_BUILD_OUTPUT_LINES);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "docker build 失败：" + hint);
        }
        // 实时回传已落过一遍，仅当输出超上限时补录末尾，避免同一段日志重复刷屏。
        fillTailIfTruncated(task, STAGE_BUILD, result);
    }

    /**
     * 登录（如需）并推送镜像，结束后登出。
     *
     * @param task    当前任务
     * @param session 已认证会话
     * @param project 项目（用于取 registry_id）
     * @param fullImage 待推送镜像全名
     * @throws DeployException 推送失败时抛出
     */
    void dockerPush(Task task, SshSession session, Project project, String fullImage) {
        if (!reportProgress(task, STAGE_PUSH, PROGRESS_PUSHING)) {
            return;
        }
        // 登录（如配置了仓库凭据）；成功后推送，最后无论如何登出并清理密码文件。
        RegistryLogin login = loginRegistry(task, session, project);
        try {
            appendLog(task, STAGE_PUSH, TaskLogLevel.INFO, "推送镜像 " + fullImage);
            // 实时回传逐行输出：分层镜像逐层推送，进度能看着走，而不是干等后一次性看到结果。
            SshCommandResult pushResult = session.execLong("docker push " + fullImage, streamingLogConsumer(task, STAGE_PUSH));
            if (!pushResult.success()) {
                String hint = StringUtils.hasText(pushResult.stderr()) ? firstLine(pushResult.stderr()) : firstLine(pushResult.stdout());
                writeError(task, STAGE_PUSH, StringUtils.hasText(pushResult.stderr()) ? pushResult.stderr() : pushResult.stdout(),
                        MAX_BUILD_OUTPUT_LINES);
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "docker push 失败：" + hint);
            }
            fillTailIfTruncated(task, STAGE_PUSH, pushResult);
        } finally {
            // 密码文件与登录态**成功失败都要清**：推送失败时更不能把明文密码留在构建机磁盘上。
            logoutRegistry(task, session, login);
        }
    }

    /**
     * 分层构建「跳过基础重建」时，确认仓库中已有稳定基础镜像，否则 app 构建的 FROM $BASE_IMAGE 会失败。
     *
     * <p>私有仓库需要先登录再拉取（拉取失败也可能是网络/权限，不只「不存在」），因此统一走登录 + pull，
     * pull 失败即 fail-fast 给出「请先勾选重新构建基础镜像」的可操作提示，而不是让 app 构建跑到一半
     * 才因 FROM 找不到基础镜像而失败。</p>
     *
     * @param task        当前任务
     * @param session     已认证会话
     * @param project     项目（取 registry_id 解析仓库与凭据）
     * @param stableBase  稳定基础镜像全名（如 registry.../app:base）
     */
    void requireStableBaseImage(Task task, SshSession session, Project project, String stableBase) {
        RegistryLogin login = loginRegistry(task, session, project);
        try {
            appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                    "跳过基础重建：先拉取稳定基础镜像确认存在 " + stableBase);
            SshCommandResult pull = session.execLong("docker pull " + stableBase, streamingLogConsumer(task, STAGE_BUILD));
            if (!pull.success()) {
                String hint = StringUtils.hasText(pull.stderr()) ? firstLine(pull.stderr()) : firstLine(pull.stdout());
                writeError(task, STAGE_BUILD, StringUtils.hasText(pull.stderr()) ? pull.stderr() : pull.stdout(),
                        MAX_BUILD_OUTPUT_LINES);
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "复用基础镜像失败：仓库中不存在稳定基础镜像 " + stableBase
                                + "，请先勾选「重新构建基础镜像」执行一次构建以创建它。原始错误：" + hint);
            }
            fillTailIfTruncated(task, STAGE_BUILD, pull);
        } finally {
            logoutRegistry(task, session, login);
        }
    }

    /**
     * 登录镜像仓库（如配置了凭据）。无仓库 / 无凭据时返回 null（无需登录）。
     *
     * <p>登录用于私有仓库：推送与「跳过基础重建时确认稳定 base 存在」都需要先登录。
     * 返回登录上下文（host + 密码临时文件路径），调用方必须在 finally 中 {@link #logoutRegistry} 释放。</p>
     *
     * @param task    当前任务
     * @param session 已认证会话
     * @param project 项目（取 registry_id 解析仓库与凭据）
     * @return 登录上下文；无需登录时返回 null
     */
    private RegistryLogin loginRegistry(Task task, SshSession session, Project project) {
        if (!StringUtils.hasText(project.getRegistryId())) {
            return null;
        }
        Registry registry = registryMapper.selectById(project.getRegistryId());
        if (registry == null || !StringUtils.hasText(registry.getCredentialId())) {
            return null;
        }
        Credential credential = credentialMapper.selectById(registry.getCredentialId());
        if (credential == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "镜像仓库绑定的凭据不存在");
        }
        String plaintext = credentialCrypto.decrypt(credential.getEncryptedValue());
        String[] up = RegistryAuthSupport.resolveRegistryCredentials(plaintext, credential.getMetadataJson());
        String loginHost = registry.getRegistryUrl();
        // 密码经文件 + stdin 传给 docker，**不走命令行**：`docker login -p <密码>` 会把明文
        // 留在构建机的进程列表（ps -ef）里，同机其他用户可直接看到。
        String passwordFile = "/tmp/ld_registry_pwd_" + task.getBizId();
        writeSecretFile(task, session, passwordFile, up[1], "镜像仓库密码", STAGE_PUSH);
        appendLog(task, STAGE_PUSH, TaskLogLevel.INFO,
                "docker login " + loginHost + "（密码经 stdin 传入，不落命令行与日志）");
        SshCommandResult loginResult = session.exec(
                RegistryAuthSupport.dockerLoginCommand(loginHost, up[0], passwordFile));
        if (!loginResult.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "镜像仓库登录失败：" + firstLine(loginResult.stderr()));
        }
        return new RegistryLogin(loginHost, passwordFile);
    }

    /**
     * 登出镜像仓库并清理密码临时文件，绝不抛异常（失败只告警，不覆盖原始失败原因）。
     *
     * @param task   当前任务
     * @param session 已认证会话
     * @param login  登录上下文；为 null（无需登录）时直接返回
     */
    private void logoutRegistry(Task task, SshSession session, RegistryLogin login) {
        if (login == null) {
            return;
        }
        cleanupRegistryCredential(task, session, login.passwordFile(), login.host());
    }

    /** 镜像仓库登录上下文：host 与密码临时文件路径，供 {@link #logoutRegistry} 释放。 */
    private record RegistryLogin(String host, String passwordFile) {
    }

    /**
     * 构建机启动级验证：把刚构建好的镜像真正跑起来，确认不是"构建成功但一启动就崩"的缺陷镜像。
     *
     * <p>方案 C 的核心闸门：验证不过的镜像绝不上推，把"跑不起来"拦在构建机，
     * 而不是等发布到目标机半夜才爆。验证只覆盖<b>启动级</b>——容器在验证窗口内持续存活即视为通过；
     * 需要连数据库/外部接口的<b>业务级</b>验证交给目标机发布后做（见 DockerDeployService）。</p>
     *
     * <p><b>为什么用「分离启动 + docker inspect 判活」而不是「timeout 包裹前台 docker run 看退出码」：</b>
     * 旧实现靠 {@code timeout -k 5 Ns docker run ...} 的退出码判定（124 表示窗口内一直活着）。
     * 但这条链路极其脆弱：当容器收到 SIGTERM 后没能及时退出、{@code timeout} 升级到 SIGKILL 时，
     * 远端进程是<b>被信号终止</b>的，sshd 只回传 {@code exit-signal} 而非 {@code exit-status}，
     * 于是 SSHD 的 {@code ChannelExec.getExitStatus()} 返回 null，平台按 -1 处理并判为失败——
     * 明明容器已稳定存活整个窗口，却被误判（实测 Python/Flask 镜像即中招，Java 因对 SIGTERM 退出快侥幸走 124）。</p>
     *
     * <p>新实现把"是否持续存活"交给 Docker 自己的运行态：{@code docker run -d} 只表达「容器能否创建」
     * （退出码确定，不再掺信号语义），窗口内逐秒 {@code docker inspect .State.Running} 判活，
     * 结束后一次性读 {@code Running / ExitCode / OOMKilled} 给结论。这样既不依赖退出码与信号，
     * 还能明确区分「启动即崩（ExitCode≠0）」与「被 OOM 杀（OOMKilled=true）」两种真实缺陷。</p>
     *
     * <p>为何用"持续存活"而非探活端口：项目自带 Dockerfile 形态各异，可能用 distroless（无 bash、
     * 无 /dev/tcp），硬编码探活会误杀正常镜像。持续存活是最通用的"没崩"信号。</p>
     *
     * <p>外部依赖陷阱：若应用启动就必须连数据库/三方接口、而构建机网络不通，容器会启动失败退出，
     * 这里会<b>误判</b>为镜像缺陷。这类项目在 build_config_json 设 {@code docker_verify_on_build=false}，
     * 跳过构建机验证、完全依赖目标机业务级验证——这是已知取舍，不是 bug。</p>
     *
     * @param task      当前任务
     * @param session   已认证的构建机会话
     * @param fullImage 刚构建好的镜像全名（含 tag）
     * @param config    构建配置（取验证开关与超时）
     */
    void dockerRunVerify(Task task, SshSession session, String fullImage, BuildConfig config) {
        if (config.getDockerVerifyOnBuild() != null && !config.getDockerVerifyOnBuild()) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "build_config_json.docker_verify_on_build=false，跳过构建机启动级验证（交由目标机业务级验证）");
            return;
        }
        int timeout = (config.getDockerVerifyTimeoutSeconds() != null && config.getDockerVerifyTimeoutSeconds() > 0)
                ? config.getDockerVerifyTimeoutSeconds() : DEFAULT_DOCKER_VERIFY_TIMEOUT_SECONDS;
        String containerName = "ld_verify_" + task.getBizId();
        // 先清掉可能残留的同名验证容器（上一次中断未清理），避免 "name already in use"。
        session.execLong("docker rm -f " + containerName + " || true");

        // 1) 分离启动：退出码只表达「容器是否成功创建」，不再承载「窗口内是否存活」——后者是信号语义，
        //    一旦 timeout 用 SIGKILL 收尾，sshd 回传 exit-signal，退出码直接丢失（曾误判 exit=-1）。
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                "启动级验证：构建机本地启动镜像 " + fullImage + "（" + timeout
                        + " 秒窗口内持续存活即视为通过，验证容器自动清理）");
        SshCommandResult start = session.execLong("docker run -d --name " + containerName + " " + fullImage);
        if (!start.success()) {
            String raw = StringUtils.hasText(start.stderr()) ? start.stderr() : start.stdout();
            writeError(task, STAGE_BUILD, raw, MAX_BUILD_OUTPUT_LINES);
            session.execLong("docker rm -f " + containerName + " || true");
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "启动级验证失败：镜像无法创建容器（" + describeExit(start.exitCode()) + "）：" + firstLine(raw));
        }

        // 2) 窗口内逐秒判活：容器一旦退出立即跳出循环，不必干等满窗口；窗口结束读一次结构化状态。
        //    用 POSIX while 计数而非 seq，避免精简镜像/受限环境缺 seq 时循环被静默跳过、误判通过。
        String probe = "i=0; while [ $i -lt " + timeout + " ]; do "
                + "r=$(docker inspect -f '{{.State.Running}}' " + containerName + " 2>/dev/null); "
                + "[ \"$r\" = \"true\" ] || break; sleep 1; i=$((i+1)); done; "
                + "docker inspect -f 'RUNNING={{.State.Running}} EXIT={{.State.ExitCode}} OOM={{.State.OOMKilled}}' "
                + containerName + " 2>/dev/null";
        SshCommandResult state = session.exec(probe, timeout + 60);

        // 3) 无论成败都抓一次容器日志（务必在删除容器之前）：启动期报错（端口占用、依赖缺失、Python 堆栈）
        //    比一句"退出码 N"有用得多，直接落进构建日志供运维定位。
        SshCommandResult containerLogs = session.exec("docker logs --tail 60 " + containerName + " 2>&1");
        if (StringUtils.hasText(containerLogs.stdout())) {
            writeOutput(task, STAGE_BUILD, containerLogs.stdout());
        }
        // 4) 判活结束，清理验证容器（成功失败都清，绝不残留在构建机）。
        session.execLong("docker rm -f " + containerName + " || true");

        String line = firstLine(state.stdout());
        if (!StringUtils.hasText(line)) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "启动级验证失败：无法读取容器运行状态（容器可能未创建或已消失），请检查镜像 "
                            + fullImage + " 与构建机 docker 状态");
        }
        boolean running = "true".equalsIgnoreCase(tagValue(line, "RUNNING="));
        boolean oomKilled = "true".equalsIgnoreCase(tagValue(line, "OOM="));
        String exitCode = tagValue(line, "EXIT=");
        if (running) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "启动级验证通过：镜像在 " + timeout + " 秒窗口内持续运行");
            return;
        }
        if (oomKilled) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "启动级验证失败：容器在验证窗口内被 OOM 杀死（ExitCode=" + exitCode
                            + "，OOMKilled=true）。请降低应用内存占用，或加大构建机内存/交换分区后重试；"
                            + "如确属资源受限可设 docker_verify_on_build=false 由目标机兜底");
        }
        if ("0".equals(exitCode)) {
            // 容器在窗口内自行正常退出（多为一次性任务或正常 shutdown）：放行但提示，与旧实现口径一致。
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "启动级验证通过：容器在 " + timeout + " 秒窗口内自行退出（ExitCode=0，请确认是否为预期行为）");
            return;
        }
        throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                "启动级验证失败：容器启动后退出（ExitCode=" + exitCode
                        + "），请查看上方容器日志；若为数据库/外部接口不可达导致，"
                        + "请在 build_config_json 设 docker_verify_on_build=false");
    }

    /**
     * 从 inspect 的结构化输出里取某个 token 的值（如从 {@code RUNNING=true} 取 {@code true}）。
     *
     * <p>判定只认结构化字段而非解析人读文本，避免 docker inspect 模板输出顺序/空格变化导致误判。</p>
     *
     * @param line 形如 {@code RUNNING=true EXIT=0 OOM=false} 的一行
     * @param tag  带等号的键，如 {@code RUNNING=}
     * @return 键后的值；行内无该键时返回 null
     */
    private static String tagValue(String line, String tag) {
        if (line == null || line.isBlank()) {
            return null;
        }
        for (String token : line.trim().split("\\s+")) {
            if (token.startsWith(tag)) {
                return token.substring(tag.length());
            }
        }
        return null;
    }

    /**
     * 把退出码转成可读描述：-1 明确说明是"未获取到退出码"而非进程真的返回 -1。
     *
     * @param code 退出码
     * @return 可读描述
     */
    private static String describeExit(int code) {
        return code == -1 ? "未获取到退出码，进程可能被信号终止" : "退出码 " + code;
    }

    /**
     * 清理镜像仓库登录痕迹：删除密码临时文件并登出。
     *
     * <p>本方法在 {@code finally} 中调用，因此**绝不抛异常**——一旦抛出就会覆盖掉原始失败原因，
     * 让页面上的报错从「docker push 失败」变成「清理失败」，反而查不到真正的问题。
     * 清理失败只记 INFO 日志并提示人工检查。</p>
     *
     * @param task         当前任务
     * @param session      已认证会话
     * @param passwordFile 密码临时文件路径；未创建过时为 null
     * @param loginHost    本次登录的仓库地址；未登录过时为 null
     */
    void cleanupRegistryCredential(Task task, SshSession session, String passwordFile, String loginHost) {
        try {
            if (passwordFile != null && !session.exec("rm -f " + passwordFile).success()) {
                appendLog(task, STAGE_PUSH, TaskLogLevel.INFO,
                        "清理镜像仓库密码临时文件失败，请人工删除 " + passwordFile);
            }
        } catch (Exception e) {
            log.warn("清理镜像仓库密码临时文件异常，taskId={}", task.getId(), e);
        }
        try {
            if (loginHost != null && !session.exec(RegistryAuthSupport.dockerLogoutCommand(loginHost)).success()) {
                appendLog(task, STAGE_PUSH, TaskLogLevel.INFO, "docker logout 失败（不影响构建结论）");
            }
        } catch (Exception e) {
            log.warn("docker logout 异常，taskId={}", task.getId(), e);
        }
    }

    /**
     * 取镜像摘要（RepoDigests）。
     *
     * @param session   已认证会话
     * @param fullImage 镜像全名
     * @return sha256:... 或 null
     */
    String dockerDigest(SshSession session, String fullImage) {
        SshCommandResult result = session.exec("docker inspect --format='{{index .RepoDigests 0}}' " + fullImage + " 2>/dev/null");
        if (result.success() && StringUtils.hasText(result.stdout())) {
            Matcher m = SHA256_PATTERN.matcher(result.stdout());
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }

    /**
     * 组装镜像类产物记录。
     */
    BuildArtifact imageArtifact(ArtifactType type, String fullImage, String tag, String digest) {
        BuildArtifact artifact = new BuildArtifact();
        artifact.setArtifactType(type.getCode());
        artifact.setArtifactName(fullImage);
        artifact.setArtifactTag(tag);
        artifact.setArtifactDigest(digest);
        return artifact;
    }

    /**
     * 解析镜像仓库地址：优先用 build_config_json.image_repository，否则由 registry 推导。
     */
    String resolveImageRepository(Project project, BuildConfig config) {
        if (StringUtils.hasText(config.getImageRepository())) {
            return config.getImageRepository();
        }
        if (!StringUtils.hasText(project.getRegistryId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "未配置镜像仓库（project.registry_id 与 build_config_json.image_repository 皆为空）");
        }
        Registry registry = registryMapper.selectById(project.getRegistryId());
        if (registry == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "绑定的镜像仓库不存在");
        }
        String repo = registry.getRegistryUrl();
        if (StringUtils.hasText(registry.getNamespace())) {
            repo = repo + "/" + registry.getNamespace();
        }
        String imageName = StringUtils.hasText(config.getModuleName()) ? config.getModuleName() : project.getCode();
        return repo + "/" + imageName;
    }

    /**
     * 解析 build_config_json；为空或非 JSON 时返回空配置（不报错）。
     */
    BuildConfig parseBuildConfig(String json) {
        BuildConfig config = new BuildConfig();
        if (!StringUtils.hasText(json)) {
            return config;
        }
        try {
            return objectMapper.readValue(json, BuildConfig.class);
        } catch (Exception e) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "build_config_json 不是合法 JSON：" + e.getMessage());
        }
    }

    /**
     * 把 build_config_json.config_paths 指向的配置文件转存到产物同级 config 目录。
     *
     * <p><b>JAR 与 DOCKER 都要用</b>：配置与产物分离部署，配置不随版本目录漂移。
     * JAR 运行时由 {@code buildFileArtifact} 顺手转存（产物本来就要落盘）；
     * DOCKER 运行时的产物是镜像、不落盘，由 {@link #persistVersionConfig} 单独补一次，
     * 否则目标机那份 {@code ./config} 永远是空的。</p>
     *
     * <p>定位口径与产物一致（仓库根优先、模块目录回落）；单个文件找不到只记日志不中断构建，
     * 因为配置文件缺失不该让一次成功的构建判失败——是否阻断由发布阶段的服务启动结果决定。</p>
     *
     * @param task         当前任务
     * @param session      已认证会话
     * @param config       构建配置
     * @param workspacePath 仓库根工作区路径
     * @param buildDir     模块目录
     * @param persistDir   产物持久目录
     * @return 成功转存的配置文件名列表
     */
    List<String> collectConfigFiles(Task task, SshSession session, BuildConfig config,
                                    String workspacePath, String buildDir, String persistDir) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(config.getConfigPaths())) {
            return result;
        }
        String configDir = persistDir + "/" + CONFIG_SUBDIR;
        boolean dirReady = false;
        for (String raw : config.getConfigPaths().split(",")) {
            String pattern = raw.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            String found = locateFile(session, workspacePath, pattern);
            if (!StringUtils.hasText(found) && !buildDir.equals(workspacePath)) {
                found = locateFile(session, buildDir, pattern);
            }
            if (!StringUtils.hasText(found)) {
                appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                        "未找到配置文件 " + pattern + "，本次发布不会携带该配置");
                continue;
            }
            if (!dirReady) {
                SshCommandResult mkdir = session.exec("mkdir -p " + configDir);
                if (!mkdir.success()) {
                    throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                            "创建配置转存目录失败：" + firstLine(mkdir.stderr()));
                }
                dirReady = true;
            }
            SshCommandResult copy = session.exec("cp -r " + found + " " + configDir + "/");
            if (!copy.success()) {
                appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                        "配置文件转存失败，已跳过：" + pattern);
                continue;
            }
            result.add(found.contains("/") ? found.substring(found.lastIndexOf('/') + 1) : found);
        }
        return result;
    }

    /**
     * 把配置转存到「版本目录 / config」，供 DOCKER 发布时投放到目标机 {@code <work_dir>/config}。
     *
     * <p><b>为什么 DOCKER 要单独来一次</b>：镜像类产物不落构建机磁盘，{@code buildFileArtifact}
     * 那条「产物 + 配置一起落盘」的路径根本不会执行，于是目标机上 compose 挂的 {@code ./config}
     * 始终是个空目录——运维看到一个空目录，只能自己抄一份配置进去，还可能改错文件不生效。
     * 这里按与 JAR 相同的约定落盘，发布端（{@code DeploySupport#versionConfigDir}）就能用同一套逻辑取。</p>
     *
     * <p>配置是增强项：没配 {@code config_paths}、文件找不到、复制失败都只记日志，
     * 绝不让一次成功的镜像构建因为配置文件而判失败。</p>
     *
     * @param task           当前任务
     * @param session        已认证构建机会话
     * @param project        项目，提供编码与模块信息
     * @param config         构建配置，提供 config_paths / module_dir
     * @param workspacePath  仓库根工作区绝对路径
     * @param artifactBase   构建机产物持久目录
     * @param releaseVersion 本次发布版本号（与镜像 tag、版本记录同值）
     */
    void persistVersionConfig(Task task, SshSession session, Project project, BuildConfig config,
                             String workspacePath, String artifactBase, String releaseVersion) {
        if (!StringUtils.hasText(config.getConfigPaths())) {
            return;
        }
        String moduleDir = resolveModuleDir(config);
        String buildDir = StringUtils.hasText(moduleDir) ? workspacePath + "/" + moduleDir : workspacePath;
        String persistDir = artifactBase.strip() + "/" + project.getCode() + "/" + releaseVersion;
        List<String> files = collectConfigFiles(task, session, config, workspacePath, buildDir, persistDir);
        if (!files.isEmpty()) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "随版本转存配置文件 " + String.join("、", files)
                            + "（发布时投放到目标机 config 目录，目标机已存在则不覆盖）");
        }
    }

    /**
     * 组装产物元数据 JSON。
     *
     * <p>发布阶段据此取产物绝对路径与随行配置目录，比解析多个字段更不容易散。</p>
     *
     * @param persistedPath 产物在持久目录中的绝对路径
     * @param persistDir    本次版本的持久目录
     * @param configFiles   随行配置文件名列表，为空表示没有配置
     * @return 元数据 JSON 字符串
     * @throws DeployException 序列化失败时抛出
     */
    String buildMetadata(String persistedPath, String persistDir, List<String> configFiles) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", persistedPath);
        if (!configFiles.isEmpty()) {
            metadata.put("config_dir", persistDir + "/" + CONFIG_SUBDIR);
            metadata.put("config_files", configFiles);
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "产物元数据序列化失败：" + e.getMessage());
        }
    }

    /**
     * 从产物 metadata_json 取出构建机上的绝对路径。
     */
    String extractPath(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(metadataJson);
            com.fasterxml.jackson.databind.JsonNode path = node.get("path");
            return path == null ? null : path.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按项目保留版本数回收超额历史版本。
     *
     * <p>顺序固定为「先删磁盘产物，再置记录废弃」：反过来的话记录已不可见、磁盘却还占着，
     * 运维只能上机手工找目录。回收<b>不是</b>构建成功的必要条件——任何一步失败都只记日志，
     * 不影响本次构建结论，否则会把「磁盘清理不顺」误报成「构建失败」。</p>
     *
     * <p>回收清单由 {@link com.lx.deploy.release.service.ReleaseRetentionService} 给出，
     * 其中已排除最近部署引用过的版本，因此不会把某台机器正跑着的版本清掉
     * （那会让回滚与新发同时断掉）。具体执行已下沉到 {@link ReleaseRetentionExecutor}，
     * 产物上传链路复用同一份实现。</p>
     *
     * @param task        当前任务，用于写回收日志
     * @param project     项目，提供编码与保留版本数
     * @param buildServer 本次构建所用构建机，用于复用已有会话
     * @param session     本次构建的已认证会话
     */
    void applyRetentionPolicy(Task task, Project project, Server buildServer, SshSession session) {
        // 回收实现放在 ReleaseRetentionExecutor：产物上传链路也要走同一套判断，
        // 「删哪些、能不能删」在全局必须只有一处定义。
        ReleaseRetentionExecutor.RetentionReport report;
        try {
            report = retentionExecutor.execute(project, buildServer, session);
        } catch (Exception e) {
            // 理论上不该发生（执行器内部已吞异常），这里兜住是为了「磁盘清理不顺」绝不影响构建结论。
            log.error("版本回收执行异常（不影响构建结论），projectId={}", project.getId(), e);
            appendLog(task, STAGE_FINISH, TaskLogLevel.ERROR,
                    "版本回收异常（不影响本次构建）：" + safeMessage(e));
            return;
        }
        for (String message : report.messages()) {
            appendLog(task, STAGE_FINISH, message.startsWith("已") ? TaskLogLevel.INFO : TaskLogLevel.ERROR, message);
        }
    }

    /**
     * 清理工作区（成功或失败路径）。
     *
     * @param task          当前任务，用于写清理日志
     * @param session       已认证会话
     * @param workspacePath 本次构建的工作区绝对路径
     */
    void cleanupWorkspace(Task task, SshSession session, String workspacePath) {
        try {
            session.exec("rm -rf " + workspacePath);
            appendLog(task, STAGE_FINISH, TaskLogLevel.INFO, "已清理工作区 " + workspacePath);
        } catch (Exception e) {
            log.warn("清理构建工作区失败，workspacePath={}", workspacePath, e);
        }
    }

    // ==================== 日志/进度辅助 ====================

    /**
     * 汇报任务阶段与进度；任务已不在执行中时返回 false。
     */
    boolean reportProgress(Task task, String stage, int progress) {
        try {
            taskService.updateProgress(task.getId(), stage, progress);
            return true;
        } catch (Exception e) {
            appendLog(task, stage, TaskLogLevel.INFO, "任务已不在执行中，停止后续操作：" + e.getMessage());
            return false;
        }
    }

    /**
     * 写入一条任务日志（持久化到 lds_task_log）。
     *
     * <p>日志写入失败不影响构建结论，只记录 WARN，避免因为日志表问题把可达的构建判成失败。</p>
     *
     * @param task    当前任务
     * @param stage   日志所属阶段
     * @param level   日志级别
     * @param content 日志内容（已脱敏）
     */
    void appendLog(Task task, String stage, TaskLogLevel level, String content) {
        TaskLogDto dto = new TaskLogDto();
        dto.setTaskId(task.getId());
        dto.setStage(stage);
        dto.setLogLevel(level.getCode());
        dto.setContent(content);
        try {
            taskLogService.create(dto);
        } catch (Exception e) {
            log.warn("写入构建日志失败，taskId={}，原因：{}", task.getId(), e.getMessage());
        }
    }

    /**
     * 把命令输出按行写入任务日志（带 task）。
     */
    void writeOutput(Task task, String stage, String output) {
        if (!StringUtils.hasText(output)) {
            appendLog(task, stage, TaskLogLevel.INFO, "（命令无输出）");
            return;
        }
        writeLines(task, stage, TaskLogLevel.INFO, output, MAX_OUTPUT_LINES);
    }

    /**
     * 把命令的失败输出（stderr）完整写入任务日志。
     *
     * <p>失败时异常消息只带 stderr 首行作为摘要，定位细节会丢：Java 抛
     * {@code NoSuchMethodError} 这类错误时，首行只有一句「找不到方法」，
     * 真正点名问题类与 jar 的 {@code at xxx.yyy(Zzz.java:行号)} 全在后面。
     * 因此 stderr 单独落日志，列表页看摘要、日志页翻细节。</p>
     *
     * @param task     当前任务
     * @param stage    日志所属阶段
     * @param error    命令的 stderr（调用方按需脱敏）
     * @param maxLines 最多写入的行数：构建类命令用 {@link #MAX_BUILD_OUTPUT_LINES}，其余用 {@link #MAX_ERROR_LINES}
     */
    void writeError(Task task, String stage, String error, int maxLines) {
        if (!StringUtils.hasText(error)) {
            return;
        }
        // 错误原因排在末尾，且实时流通常已经写过开头，这里保留头部只会重复一遍并挤掉真错。
        writeLines(task, stage, TaskLogLevel.ERROR, error, maxLines, true);
    }

    /**
     * 把命令的 stdout 与 stderr 都写进日志（成功场景）。
     *
     * <p>git 的克隆进度、Maven 的告警都写在 stderr：只回显 stdout 时，命令明明干成了事，
     * 日志却显示「（命令无输出）」，容易被误读成没生效。stdout 有内容时以 stdout 为准
     * （构建输出量大，不重复刷屏），stdout 为空才回落到 stderr。</p>
     *
     * @param task   当前任务
     * @param stage  日志所属阶段
     * @param result 命令执行结果
     */
    void writeCommandOutput(Task task, String stage, SshCommandResult result) {
        if (!StringUtils.hasText(result.stdout()) && StringUtils.hasText(result.stderr())) {
            writeLines(task, stage, TaskLogLevel.INFO, result.stderr(), MAX_OUTPUT_LINES);
            return;
        }
        writeOutput(task, stage, result.stdout());
    }

    /**
     * 构建命令的环境前缀：登录态环境兜底 + 机器级构建环境初始化脚本。
     *
     * <p>两者都拼在 {@code cd 工作目录} 之后、用户构建命令之前，作用于同一个 shell，
     * 因此脚本里 {@code export} 的变量对构建命令可见。</p>
     *
     * @param buildServer 构建机档案，可能为 null（理论上不会，调用方已校验）
     * @return 形如 {@code ". /etc/profile >/dev/null 2>&1; export PATH=...; "} 的前缀
     */
    String buildEnvPrefix(Server buildServer) {
        StringBuilder prefix = new StringBuilder(PROFILE_SOURCE_COMMAND);
        String envScript = buildServer == null ? null : buildServer.getBuildEnvScript();
        if (StringUtils.hasText(envScript)) {
            // 单行脚本，原样拼接；内容由用户在服务器页面维护，平台不解析（运维可写任意 shell 片段）。
            prefix.append(envScript.strip()).append("; ");
        }
        return prefix.toString();
    }

    /**
     * 取文本末尾若干行。
     *
     * <p>超时与「实时回传已封顶」两种场景下，有价值的是最后几行（正在做什么、报了什么错），而不是开头。</p>
     *
     * @param text     原始文本
     * @param maxLines 保留的行数
     * @return 末尾若干行拼成的文本；总行数不超过上限时原样返回
     */
    String tailLines(String text, int maxLines) {
        List<String> lines = text.strip().lines().toList();
        if (lines.size() <= maxLines) {
            return text;
        }
        return String.join("\n", lines.subList(lines.size() - maxLines, lines.size()));
    }

    /**
     * 取文本最后一行（用于超时摘要）。
     *
     * @param text 原始文本
     * @return 最后一行；为空时返回空串
     */
    String lastLine(String text) {
        List<String> lines = text.strip().lines().toList();
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }

    /**
     * 把值转成 shell 单引号字面量。
     *
     * <p>用于把含空格的构建命令交给 {@code sh -c}：内层已有的单引号按 {@code '\''} 规则转义，
     * 保证命令语义不被外层引号破坏。</p>
     *
     * @param value 原始值
     * @return 可直接拼进 shell 命令的引号包裹值
     */
    String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
            if (isEnvAssignment(token)) {
                continue;
            }
            return token;
        }
        return null;
    }

    /**
     * 判断单个词是不是 {@code KEY=VALUE} 形式的环境变量赋值。
     *
     * @param token 待判断的词
     * @return true 表示形如 JAVA_HOME=/path 的环境变量前缀
     */
    boolean isEnvAssignment(String token) {
        int index = token.indexOf('=');
        if (index <= 0) {
            return false;
        }
        return token.substring(0, index).matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    /**
     * 按「路径 + 通配」定位单个文件。
     *
     * <p>只做一次查找，返回首个匹配项的绝对路径；未匹配返回 null。
     * 调用方负责按仓库根、模块目录的顺序多次尝试。</p>
     *
     * @param session  已认证会话
     * @param baseDir  查找基准目录（仓库根或模块目录）
     * @param pattern  相对路径，支持 * 通配
     * @return 匹配到的绝对路径，未找到返回 null
     */
    String locateFile(SshSession session, String baseDir, String pattern) {
        SshCommandResult result = session.exec("ls -1 " + baseDir + "/" + pattern + " 2>/dev/null | head -1");
        return result.success() && StringUtils.hasText(result.stdout()) ? result.stdout().strip() : null;
    }

    /**
     * 生成构建输出的逐行消费者，并对实时回传的行数封顶。
     *
     * <p>实时回传是逐行落库的，首次构建的依赖下载输出可达数千行，不封顶会刷爆日志表、
     * 页面也难以翻阅；超过上限后只留一条提示，命令结束后仍会补录末尾输出。</p>
     *
     * @param task 当前任务
     * @return 逐行消费者
     */
    Consumer<String> buildOutputConsumer(Task task) {
        return streamingLogConsumer(task, STAGE_BUILD);
    }

    /**
     * 生成指定阶段的实时日志逐行消费者，并对回传行数封顶。
     *
     * <p>与 {@link #buildOutputConsumer} 同源，差别只在写入的阶段：构建日志落 STAGE_BUILD，
     * 推送日志落 STAGE_PUSH，避免"构建"阶段里混进推送输出、页面按阶段筛选时串台。</p>
     *
     * @param task  当前任务
     * @param stage 日志所属阶段（STAGE_BUILD / STAGE_PUSH 等）
     * @return 逐行消费者
     */
    Consumer<String> streamingLogConsumer(Task task, String stage) {
        AtomicInteger received = new AtomicInteger();
        return line -> {
            int index = received.incrementAndGet();
            if (index <= MAX_STREAM_OUTPUT_LINES) {
                appendLog(task, stage, TaskLogLevel.INFO, line);
            } else if (index == MAX_STREAM_OUTPUT_LINES + 1) {
                appendLog(task, stage, TaskLogLevel.INFO,
                        "（实时输出已超过 " + MAX_STREAM_OUTPUT_LINES + " 行，后续不再逐行展示，命令结束后补录末尾输出）");
            }
        };
    }

    /**
     * 实时回传超过上限后，补录命令末尾输出，避免末尾关键信息（如 push 摘要、digest）因封顶丢失。
     *
     * @param task   当前任务
     * @param stage  日志阶段
     * @param result 已执行命令的结果（stdout/stderr 仍保留全量文本）
     */
    void fillTailIfTruncated(Task task, String stage, SshCommandResult result) {
        String output = StringUtils.hasText(result.stdout()) ? result.stdout() : result.stderr();
        long lines = StringUtils.hasText(output) ? output.strip().lines().count() : 0;
        if (lines > MAX_STREAM_OUTPUT_LINES) {
            appendLog(task, stage, TaskLogLevel.INFO,
                    "实时回传已达上限 " + MAX_STREAM_OUTPUT_LINES + " 行，补录末尾 " + MAX_BUILD_OUTPUT_LINES + " 行：");
            writeLines(task, stage, TaskLogLevel.INFO, tailLines(output, MAX_BUILD_OUTPUT_LINES), MAX_BUILD_OUTPUT_LINES);
        }
    }

    /**
     * 判断命令失败原因是否为「构建机没有 setsid」。
     *
     * <p>不同 shell 的报错文案不一致（bash 是 {@code setsid: command not found}，
     * dash 是 {@code sh: 1: setsid: not found}），所以按关键词组合判断而不是整句匹配。</p>
     *
     * @param result 命令执行结果
     * @return 退出码非 0 且错误输出符合 setsid 缺失特征时返回 true
     */
    boolean isSetsidMissing(SshCommandResult result) {
        String stderr = result.stderr() == null ? "" : result.stderr();
        return stderr.contains("setsid") && (stderr.contains("not found") || stderr.contains("No such file"));
    }

    /**
     * 终止构建命令所在的远端进程组。
     *
     * <p>命令由 {@code setsid} 起在新会话里，组长 PID 即进程组 PGID 且已写入 /tmp，
     * 因此 {@code kill -9 -PGID} 能连 mvn 与其 fork 出的子进程一起终止。
     * 终止失败不覆盖超时结论：只补一条说明，后续清理照常执行。</p>
     *
     * @param task    当前任务
     * @param session 已认证的构建机会话
     * @param pidFile 记录进程组组长 PID 的文件路径
     */
    void terminateRemoteProcessGroup(Task task, SshSession session, String pidFile) {
        try {
            // PID 大于 1 才动手：文件损坏或读到 0/1 时宁可放弃，也不能对 1 号进程的进程组下杀手。
            SshCommandResult kill = session.exec("PID=$(cat " + pidFile + " 2>/dev/null); "
                    + "if [ -n \"$PID\" ] && [ \"$PID\" -gt 1 ]; then "
                    + "kill -9 -$PID 2>/dev/null && echo KILLED || echo GONE; "
                    + "else echo NONE; fi; rm -f " + pidFile);
            if (kill.stdout() != null && kill.stdout().contains("KILLED")) {
                appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "已终止远端构建进程组，工作区可以安全清理");
            } else {
                appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "远端构建进程已退出，无需终止");
            }
        } catch (Exception e) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "终止远端构建进程失败（" + safeMessage(e) + "），清理工作区可能受残留进程影响");
        }
    }

    /**
     * 超时时把已收到的输出落日志。
     *
     * <p>构建命令是实时回传的，行已经在上面出现过，因此这里只给结论与最后一行；
     * 当行数超过实时回传上限时，补录末尾若干行——超时后这几行是唯一的定位线索。
     * 原来超时会直接丢弃输出缓冲，页面上只剩一句「执行超时」，无法区分「慢」与「卡死」。</p>
     *
     * @param task    当前任务
     * @param stage   日志所属阶段
     * @param partial 超时前的输出快照
     */
    void writeTimeoutOutput(Task task, String stage, SshCommandResult partial) {
        String output = StringUtils.hasText(partial.stdout()) ? partial.stdout() : partial.stderr();
        if (!StringUtils.hasText(output)) {
            appendLog(task, stage, TaskLogLevel.ERROR,
                    "超时前没有任何输出：命令可能卡在建立连接、等待锁或下载首个依赖上");
            return;
        }
        long lines = output.strip().lines().count();
        appendLog(task, stage, TaskLogLevel.ERROR,
                "超时前已收到 " + lines + " 行输出，最后一行：" + lastLine(output));
        if (lines > MAX_STREAM_OUTPUT_LINES) {
            appendLog(task, stage, TaskLogLevel.ERROR,
                    "补录末尾 " + MAX_BUILD_OUTPUT_LINES + " 行：");
            writeLines(task, stage, TaskLogLevel.ERROR,
                    tailLines(output, MAX_BUILD_OUTPUT_LINES), MAX_BUILD_OUTPUT_LINES);
        }
    }

    /**
     * 解析构建命令的超时预算。
     *
     * <p>项目级 {@code build_config_json.command_timeout_seconds} 优先：首次全量构建（下载依赖）
     * 与增量构建可能相差一个数量级，不同项目共用一套全局值容易误伤；
     * 未配置或非正数时回落到全局长命令超时。</p>
     *
     * @param config  构建配置
     * @param session 已认证会话（承载全局长命令超时）
     * @return 超时秒数，恒大于 0
     */
    int resolveBuildTimeout(BuildConfig config, SshSession session) {
        Integer override = config.getCommandTimeoutSeconds();
        return override != null && override > 0 ? override : session.getLongCommandTimeoutSeconds();
    }

    /**
     * 把命令中的凭据替换为 *** 用于日志展示（实现见 {@link SecretMasker}）。
     *
     * @param command 待脱敏的命令
     * @return 脱敏后的文本
     */
    String maskSecret(String command) {
        return SecretMasker.maskSecret(command);
    }

    /**
     * 判断构建命令首词是否是 Maven 可执行文件。
     *
     * <p>三种写法都要认：裸命令 {@code mvn}、绝对路径 {@code /opt/apache-maven-3.8.8/bin/mvn}、
     * 仓库内的包装脚本 {@code ./mvnw}。</p>
     *
     * @param token 构建命令首词，可为 null
     * @return 是 Maven 命令返回 true
     */
    boolean isMavenExecutable(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        return "mvn".equals(token) || token.endsWith("/mvn")
                || "mvnw".equals(token) || token.endsWith("/mvnw");
    }

    /**
     * 按行写日志，超出上限时截断并说明省略了多少行。
     *
     * @param task     当前任务
     * @param stage    日志所属阶段
     * @param level    日志级别
     * @param text     原始文本，按任意换行符切分
     * @param maxLines 最多写入的行数
     */
    void writeLines(Task task, String stage, TaskLogLevel level, String text, int maxLines) {
        writeLines(task, stage, level, text, maxLines, false);
    }

    /**
     * keepTail=true 时保留<b>末尾</b> N 行，专供错误输出使用。
     *
     * <p>命令失败的原因（{@code failed to solve: ...}、基础镜像拉不动、{@code COPY} 找不到文件）
     * 一律排在输出<b>最后</b>；保留前 N 行等于把真正的错误丢掉，日志里只剩开场白与进度。
     * 而且实时流往往已经把开头那部分写进日志了，再写一遍头部是纯重复。</p>
     */
    void writeLines(Task task, String stage, TaskLogLevel level, String text, int maxLines, boolean keepTail) {
        String[] lines = text.strip().split("\\R");
        int from = keepTail ? Math.max(0, lines.length - maxLines) : 0;
        int to = keepTail ? lines.length : Math.min(lines.length, maxLines);
        for (int i = from; i < to; i++) {
            appendLog(task, stage, level, lines[i]);
        }
        if (lines.length > maxLines) {
            appendLog(task, stage, level, keepTail
                    ? "（输出共 " + lines.length + " 行，仅保留末尾 " + maxLines + " 行：失败原因通常在最后）"
                    : "（输出共 " + lines.length + " 行，仅保留前 " + maxLines + " 行）");
        }
    }

    /**
     * 把异常转为安全描述（去除可能残留的凭据片段）。
     */
    String safeMessage(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 2000 ? msg.substring(0, 2000) + "..." : msg;
    }

    /**
     * 取命令输出第一行（用于错误摘要）。
     */
    String firstLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "无详细信息";
        }
        String t = text.strip();
        int idx = t.indexOf('\n');
        return idx < 0 ? t : t.substring(0, idx);
    }
}
