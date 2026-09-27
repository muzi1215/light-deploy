package com.lx.deploy.build.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.deploy.build.mapper.BuildMapper;
import com.lx.deploy.build.model.Build;
import com.lx.deploy.build.model.BuildArtifact;
import com.lx.deploy.build.service.BuildService;
import com.lx.deploy.common.constants.BuildStatus;
import com.lx.deploy.common.crypto.CredentialCrypto;
import com.lx.deploy.common.enums.build.ArtifactType;
import com.lx.deploy.common.enums.credential.CredentialType;
import com.lx.deploy.common.enums.project.BuildMode;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.common.util.GitAuthSupport;
import com.lx.deploy.common.util.RegistryAuthSupport;
import com.lx.deploy.common.util.SecretMasker;
import com.lx.deploy.credential.mapper.CredentialMapper;
import com.lx.deploy.credential.model.Credential;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.registry.mapper.RegistryMapper;
import com.lx.deploy.registry.model.Registry;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.release.service.ReleaseRetentionExecutor;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshCommandTimeoutException;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import com.lx.deploy.task.executor.TaskHandler;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.service.TaskLogService;
import com.lx.deploy.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 构建任务处理器。
 *
 * <p>这是「构建」类型异步任务（{@code TaskType.BUILD}）的真实执行者，由 {@link TaskExecutor} 调度。
 * 一条 build 记录对应一条 BUILD 任务，bizId 即 build.id。</p>
 *
 * <p>执行链路：建立 SSH → 在构建机创建工作区 → 拉取私有仓库代码（Token / SSH Key 鉴权）→
 * 按运行时与构建模式执行构建 → DOCKER 推送镜像并取摘要 / JAR·STATIC 计算产物 SHA256 →
 * 调用 {@link BuildService} 登记状态、产物与发布版本。每个阶段都先汇报进度并写日志，
 * 阶段前检查任务是否已被取消；任何一步失败都把 build 置为失败，且日志/异常绝不含有凭据。</p>
 *
 * <p>互斥键为 {@code build-server:构建机ID}，保证同一台构建机同一时刻只有一个构建任务，
 * 避免 docker build / 磁盘抢占（AGENTS.md §1）。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Slf4j
@Component
public class BuildTaskHandler implements TaskHandler {

    /** 任务类型编码，与 TaskType.BUILD 保持一致。 */
    private static final String TASK_TYPE = TaskType.BUILD.getCode();

    /** 互斥键前缀，保证同一构建机串行执行。 */
    private static final String LOCK_KEY_PREFIX = "build-server:";

    /** 阶段名：建立连接。 */
    private static final String STAGE_CONNECT = "建立连接";
    /** 阶段名：准备工作区。 */
    private static final String STAGE_PREPARE = "准备工作区";
    /** 阶段名：拉取代码。 */
    private static final String STAGE_CLONE = "拉取代码";
    /** 阶段名：执行构建。 */
    private static final String STAGE_BUILD = "执行构建";
    /** 阶段名：推送产物。 */
    private static final String STAGE_PUSH = "推送产物";
    /** 阶段名：收尾。 */
    private static final String STAGE_FINISH = "收尾";

    /** 产物持久目录内存放随行配置文件的子目录名，与目标机 JAR 工作目录的 config 目录对应。 */
    private static final String CONFIG_SUBDIR = "config";

    /**
     * 产物持久目录下的版本标识文件名。
     *
     * <p>与目标机工作目录下的同名文件一起，构成「这份产物是哪个版本」的机器可读依据。
     * 两边取值口径必须一致（见 {@code DeployTaskHandler} 的同名常量），
     * 不允许一边写版本号、另一边写提交短码。</p>
     */
    private static final String VERSION_MARKER = "VERSION";

    /** 版本标识里 built_at 的时间格式，与部署日志的时间格式保持一致，便于人眼对照。 */
    private static final DateTimeFormatter VERSION_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Git 鉴权相关的常量与地址拼装（令牌注入密码位、剥离 userinfo、非交互前缀、SSH 前缀）
    // 统一放在 GitAuthSupport：项目连通性测试要在同一批地址与凭据上做同样的处理，
    // 两处各写一套必然在「谁该补 oauth2」「谁该剔掉多余用户名」上分叉，
    // 表现为「测试说通、构建却失败」这类最难查的问题。

    /** 连接成功后的进度百分比。 */
    private static final int PROGRESS_CONNECTED = 5;
    /** 工作区就绪后的进度百分比。 */
    private static final int PROGRESS_PREPARED = 15;
    /** 代码拉取完成后的进度百分比。 */
    private static final int PROGRESS_CLONED = 35;
    /** 构建进行中的进度百分比。 */
    private static final int PROGRESS_BUILDING = 55;
    /** 推送/登记中的进度百分比。 */
    private static final int PROGRESS_PUSHING = 80;
    /** 全部完成的进度百分比。 */
    private static final int PROGRESS_DONE = 100;

    /** 单条命令输出最多写入日志的行数，避免镜像层输出刷屏。 */
    private static final int MAX_OUTPUT_LINES = 30;

    /**
     * 命令失败时最多写入日志的错误输出行数。
     *
     * <p>比输出上限略宽：Java 类错误（如 {@code NoSuchMethodError}）的首行只有一句
     * 「找不到方法」，点名具体类与 jar 的 {@code at xxx.yyy(Zzz.java:行号)} 全在后面几行，
     * 只留首行等于没有定位信息。</p>
     */
    private static final int MAX_ERROR_LINES = 40;

    /**
     * 构建类命令的日志行数上限（汇总回显、失败输出、超时补录共用）。
     *
     * <p>比普通命令宽一档：一次 Maven 构建动辄上百行，30 行只够看到开头，
     * 出问题时既看不到编译错误所在模块，也看不到最终 BUILD FAILURE 的摘要。</p>
     */
    private static final int MAX_BUILD_OUTPUT_LINES = 200;

    /**
     * 构建命令实时回传的行数上限。
     *
     * <p>实时回传是逐行落库的，必须封顶：首次构建的依赖下载输出可达数千行，
     * 不封顶会把日志表刷爆、页面也难以翻阅。超过后不再逐行回传，末尾仍会汇总补录。</p>
     */
    private static final int MAX_STREAM_OUTPUT_LINES = 500;

    /**
     * 构建进程组 PID 文件的路径前缀。
     *
     * <p>放在 /tmp 而不是工作区：工作区是 git 仓库副本，任何多余文件都会出现在
     * {@code git status} 里；同时路径按任务 ID 隔离，同一台构建机上的并发任务互不干扰。</p>
     */
    private static final String BUILD_PID_FILE_PREFIX = "/tmp/light-deploy-build-";

    /** 构建机缺少 setsid 时的报错特征，用于降级重跑一次不带进程组包装的命令。 */
    private static final String SETSID_MISSING = "setsid: command not found";

    /** 从 docker inspect 输出中提取 sha256 摘要的正则。 */
    private static final Pattern SHA256_PATTERN = Pattern.compile("sha256:[a-f0-9]{64}");

    /**
     * 非交互式 SSH 会话的环境兜底前缀。
     *
     * <p>平台通过 SSH 执行命令时不加载 /etc/profile 与 /etc/profile.d/*.sh，
     * PATH 通常只有 /usr/bin:/bin 等基础目录，导致「机器上已装、但靠 profile 导出 PATH」的
     * 工具（最典型是 mvn）在构建时找不到。这里先 source 一次登录环境把 PATH 引入；
     * 输出与错误一律丢弃，避免登录脚本的欢迎信息污染构建日志。
     * 用 {@code ;} 而非 {@code &&} 结尾：profile 不存在或返回非零不影响后续命令。</p>
     */
    private static final String PROFILE_SOURCE_COMMAND = ". /etc/profile >/dev/null 2>&1; ";

    /** 构建命令可执行性预检的哨兵输出：命令可用。 */
    private static final String CMD_PROBE_OK = "LIGHT_DEPLOY_CMD_OK";

    /** 构建命令可执行性预检的哨兵输出：命令未找到。 */
    private static final String CMD_PROBE_MISSING = "LIGHT_DEPLOY_CMD_MISSING";

    /** 构建 Mapper：读取 build 记录（跨模块只依赖 Mapper，不依赖对方 Service）。 */
    private final BuildMapper buildMapper;

    /** 构建服务：维护 build 状态与产物、发布版本（执行器只回调，不自己写库）。 */
    private final BuildService buildService;

    /** 项目 Mapper：读取运行时、构建模式、构建配置与代码来源。 */
    private final ProjectMapper projectMapper;

    /** 服务器 Mapper：读取构建机连接配置。 */
    private final ServerMapper serverMapper;

    /** 镜像仓库 Mapper：读取仓库地址与命名空间。 */
    private final RegistryMapper registryMapper;

    /** 凭据 Mapper：读取 Git / 仓库凭据密文（解密由 CredentialCrypto 完成）。 */
    private final CredentialMapper credentialMapper;

    /** 凭据加解密组件：唯一的解密点，明文只在建连/登录前短暂存在。 */
    private final CredentialCrypto credentialCrypto;

    /** SSH 会话工厂：负责解密服务器凭据并建立已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /** 任务服务：汇报执行阶段与进度。 */
    private final TaskService taskService;

    /** 任务日志服务：把执行过程写入 lds_task_log。 */
    private final TaskLogService taskLogService;

    /** JSON 解析器：解析 build_config_json。 */
    private final ObjectMapper objectMapper;

    /** 发布版本保留策略执行器：构建收尾时按项目保留数回收超额的历史版本。 */
    private final ReleaseRetentionExecutor retentionExecutor;

    /**
     * 创建构建任务处理器。
     *
     * <p>工作区与产物目录不再由配置项注入：它们是「构建机上的目录」，不同构建机的磁盘布局
     * 可能不同，因此统一从服务器档案按机器读取，在服务器页面维护，改目录不再需要改配置文件并重启平台。</p>
     *
     * @param buildMapper         构建 Mapper
     * @param buildService        构建服务
     * @param projectMapper       项目 Mapper
     * @param serverMapper        服务器 Mapper
     * @param registryMapper      镜像仓库 Mapper
     * @param credentialMapper    凭据 Mapper
     * @param credentialCrypto    凭据加解密组件
     * @param sshSessionFactory   SSH 会话工厂
     * @param taskService         任务服务
     * @param taskLogService      任务日志服务
     * @param retentionExecutor 发布版本保留策略执行器
     */
    public BuildTaskHandler(BuildMapper buildMapper,
                            BuildService buildService,
                            ProjectMapper projectMapper,
                            ServerMapper serverMapper,
                            RegistryMapper registryMapper,
                            CredentialMapper credentialMapper,
                            CredentialCrypto credentialCrypto,
                            SshSessionFactory sshSessionFactory,
                            TaskService taskService,
                            TaskLogService taskLogService,
                            ReleaseRetentionExecutor retentionExecutor) {
        this.buildMapper = buildMapper;
        this.buildService = buildService;
        this.projectMapper = projectMapper;
        this.serverMapper = serverMapper;
        this.registryMapper = registryMapper;
        this.credentialMapper = credentialMapper;
        this.credentialCrypto = credentialCrypto;
        this.sshSessionFactory = sshSessionFactory;
        this.taskService = taskService;
        this.taskLogService = taskLogService;
        this.objectMapper = new ObjectMapper();
        this.retentionExecutor = retentionExecutor;
    }

    /**
     * 该处理器负责的任务类型。
     *
     * @return BUILD
     */
    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    /**
     * 互斥键：同一台构建机上的构建必须串行执行。
     *
     * @param task 待执行任务，bizId 为目标构建机主键
     * @return 形如 {@code build-server:构建机ID} 的互斥键
     */
    @Override
    public String lockKey(Task task) {
        return LOCK_KEY_PREFIX + task.getBizId();
    }

    /**
     * 执行构建任务。
     *
     * <p>全流程在 SSH 会话内完成；会话用 try-with-resources 管理，无论成功失败都关闭，避免连接泄漏。
     * 任一阶段异常都会被收敛为构建失败（build 置为失败、任务交给执行器判失败），且异常信息已脱敏。</p>
     *
     * @param task 已进入执行中状态的 BUILD 任务，bizId 为 build.id
     * @throws DeployException 任务数据异常、连接失败、克隆/构建/推送失败时抛出
     */
    @Override
    public void execute(Task task) {
        // bizId 即 build.id；缺失说明任务数据异常，无法继续。
        String buildId = task.getBizId();
        if (!StringUtils.hasText(buildId)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "构建任务缺少关联的 build.id");
        }
        // 读取 build 与项目，缺任一个都直接失败。
        Build build = buildMapper.selectById(buildId);
        if (build == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "构建记录不存在或已被删除");
        }
        Project project = projectMapper.selectById(build.getProjectId());
        if (project == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "构建目标项目不存在或已被删除");
        }
        Server buildServer = serverMapper.selectById(build.getBuildServerId());
        if (buildServer == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "构建机不存在或已被删除");
        }
        if (buildServer.getStatus() == null || buildServer.getStatus() != 1) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "构建机已停用，不允许执行构建");
        }

        // 解析运行时与构建模式，二者是后续所有分支的总开关。
        RuntimeType runtimeType = RuntimeType.fromCode(project.getRuntimeType());
        BuildMode buildMode = BuildMode.fromCode(project.getBuildMode());
        if (runtimeType == null || buildMode == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目的运行时或构建模式非法");
        }
        // ARTIFACT_ONLY 不做任何构建：产物由调用方通过 POST /build/upload 上传登记。
        // 走到这里说明有人对这类项目误发起了构建任务，与其在执行阶段失败，不如在入口说清正确入口。
        if (buildMode == BuildMode.ARTIFACT_ONLY) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "构建模式 ARTIFACT_ONLY（仅发布产物）不发起构建任务，请通过产物上传接口 POST /build/upload 登记版本");
        }
        // 工作区与产物目录取自构建机档案：目录属于具体机器而不是平台全局配置，
        // 由服务器页面维护。未配置时给出可操作的提示，不在代码里猜一个默认路径兜底。
        String workspaceBase = requireDir(buildServer.getBuildWorkspaceDir(), "构建工作区根目录");
        String artifactBase = requireDir(buildServer.getBuildArtifactDir(), "构建产物持久目录");

        // 解析构建配置 JSON，字段缺失由后续阶段按需判定。
        BuildConfig config = parseBuildConfig(project.getBuildConfigJson());

        // 工作区路径在 try 外声明，便于 finally/异常路径清理。
        final String workspacePath = workspaceBase + "/" + project.getCode() + "_" + buildId;

        String target = buildServer.getUsername() + "@" + buildServer.getHost() + ":" + buildServer.getSshPort();
        appendLog(task, STAGE_CONNECT, TaskLogLevel.INFO, "开始连接构建机 " + target);
        if (!reportProgress(task, STAGE_CONNECT, 2)) {
            return;
        }

        // 会话外提为 null，进入 try 后再赋值；catch/finally 都能访问，便于异常路径清理与关闭。
        SshSession session = null;
        try {
            session = sshSessionFactory.open(buildServer);
            appendLog(task, STAGE_CONNECT, TaskLogLevel.INFO, "SSH 连接与认证成功");
            reportProgress(task, STAGE_CONNECT, PROGRESS_CONNECTED);
            // 标记 build 进入构建中，与任务执行中状态对齐。
            buildService.startBuild(buildId);

            // 1) 准备工作区：每次全新创建，避免旧产物污染。
            prepareWorkspace(task, session, workspacePath);

            // 2) 拉取代码（含私有仓库鉴权）。
            String commit = cloneRepository(task, session, project, config, workspacePath);

            // 2.1) 定稿本次构建的发布版本号。镜像 tag、构建机产物持久目录、版本记录三处共用
            //      同一个字符串，所以在这里算一次往下传；中途重新生成会让三者对不上。
            BuildService.ReleaseVersion releaseVersion = buildService.resolveReleaseVersion(
                    project.getId(), project.getCode(), commit, build.getVersionSeq());
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "本次构建版本号 " + releaseVersion.version());

            // 3) 按运行时与构建模式执行构建，并收集产物。
            List<BuildArtifact> artifacts = new ArrayList<>();
            if (runtimeType == RuntimeType.DOCKER) {
                buildDocker(task, session, project, config, runtimeType, buildMode, workspacePath, commit,
                        releaseVersion.version(), artifacts);
            } else {
                buildFileArtifact(task, session, buildServer, project, config, runtimeType, workspacePath,
                        artifactBase, commit, releaseVersion.version(), artifacts);
            }

            // 4) 登记状态、产物与发布版本。
            ReleaseRecord release = assembleRelease(project, runtimeType, commit, releaseVersion, artifacts);
            buildService.completeSuccess(buildId, commit, artifacts, release);
            taskService.updateProgress(task.getId(), STAGE_FINISH, PROGRESS_DONE);
            appendLog(task, STAGE_FINISH, TaskLogLevel.INFO, "构建完成，已生成发布版本 " + release.getVersion());
            // 5) 按项目保留版本数回收超额历史版本：先删磁盘再置废弃，失败不影响本次构建结论。
            applyRetentionPolicy(task, project, buildServer, session);
            cleanupWorkspace(task, session, workspacePath);
        } catch (Exception e) {
            // 异常路径：先清理工作区，再把 build 置为失败，最后上抛由执行器判任务失败。
            if (session != null) {
                cleanupWorkspace(task, session, workspacePath);
            }
            if (StringUtils.hasText(buildId)) {
                buildService.completeFailure(buildId, safeMessage(e));
            }
            if (e instanceof DeployException) {
                throw e;
            }
            log.warn("构建任务执行异常，buildId={}", buildId, e);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "构建执行失败：" + safeMessage(e));
        } finally {
            // 手动关闭会话，避免连接/IO 线程泄漏；关闭失败只告警不影响结论。
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignore) {
                    log.warn("关闭构建机 SSH 会话失败，buildId={}", buildId, ignore);
                }
            }
        }
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
    private void prepareWorkspace(Task task, SshSession session, String workspacePath) {
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
    private String cloneRepository(Task task, SshSession session, Project project,
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
            buildService.captureCommit(task.getBizId(), commit);
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
    private CloneCommand buildCloneCommand(Task task, SshSession session, Project project, String workspacePath) {
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
    private void resetRemoteUrl(Task task, SshSession session, String workspacePath, String cleanUrl) {
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
    private void writeGitKey(Task task, SshSession session, String keyFile, String plaintext) {
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
    private void writeSecretFile(Task task, SshSession session, String path, String content, String label, String stage) {
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
     * 执行 DOCKER 运行时构建并推送镜像。
     *
     * <p>按构建模式分支：PROJECT_DOCKERFILE 用项目自带 Dockerfile；LAYERED_DOCKER 先推基础镜像再推应用镜像；
     * PLATFORM_TEMPLATE 由平台生成 Maven 多阶段模板。推送成功后取镜像摘要登记产物。</p>
     *
     * @param task         当前任务
     * @param session      已认证会话
     * @param project      项目
     * @param config       构建配置
     * @param runtimeType  运行时（固定 DOCKER）
     * @param buildMode    构建模式
     * @param workspacePath 工作区路径
     * @param commit       提交 ID，仅用于日志与溯源
     * @param releaseVersion 发布版本号，由调用方定稿，作为镜像 tag
     * @param artifacts    构建产物收集列表（方法内追加）
     * @throws DeployException 构建/推送失败时抛出
     */
    private void buildDocker(Task task, SshSession session, Project project, BuildConfig config,
                             RuntimeType runtimeType, BuildMode buildMode,
                             String workspacePath, String commit, String releaseVersion,
                             List<BuildArtifact> artifacts) {
        if (!reportProgress(task, STAGE_BUILD, PROGRESS_BUILDING)) {
            return;
        }
        String imageRepo = resolveImageRepository(project, config);
        // 版本号由调用方一次性定稿：镜像 tag 必须与版本记录、产物目录用同一个值。
        String tag = releaseVersion;
        String fullImage = imageRepo + ":" + tag;
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "构建镜像 " + fullImage + "（模式 " + buildMode.getCode() + "）");

        String dockerfileOption;
        if (buildMode == BuildMode.PROJECT_DOCKERFILE) {
            if (!StringUtils.hasText(config.getDockerfilePath())) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "PROJECT_DOCKERFILE 模式需要 build_config_json.dockerfile_path");
            }
            dockerfileOption = "-f " + config.getDockerfilePath();
        } else if (buildMode == BuildMode.LAYERED_DOCKER) {
            if (!StringUtils.hasText(config.getBaseDockerfilePath()) || !StringUtils.hasText(config.getDockerfilePath())) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "LAYERED_DOCKER 模式需要 build_config_json.base_dockerfile_path 与 dockerfile_path");
            }
            // 先构建并推送基础镜像，应用镜像基于它构建。
            String baseImage = imageRepo + ":base-" + tag;
            dockerBuild(task, session, workspacePath, "-f " + config.getBaseDockerfilePath(), baseImage);
            dockerPush(task, session, project, baseImage);
            artifacts.add(imageArtifact(ArtifactType.BASE_IMAGE, baseImage, tag, dockerDigest(session, baseImage)));
            // 应用镜像引用基础镜像。
            dockerfileOption = "-f " + config.getDockerfilePath() + " --build-arg BASE_IMAGE=" + baseImage;
        } else {
            // PLATFORM_TEMPLATE：平台生成 Maven 多阶段模板（v1 面向 Java/Maven 应用）。
            String template = generatePlatformDockerfile(project, config);
            writeRemoteFile(session, workspacePath + "/Dockerfile.platform", template);
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "已生成平台内置 Dockerfile（Maven 多阶段模板）");
            dockerfileOption = "-f Dockerfile.platform";
        }

        dockerBuild(task, session, workspacePath, dockerfileOption, fullImage);
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "镜像构建完成");

        dockerPush(task, session, project, fullImage);
        String digest = dockerDigest(session, fullImage);
        artifacts.add(imageArtifact(ArtifactType.APP_IMAGE, fullImage, tag, digest));
        appendLog(task, STAGE_PUSH, TaskLogLevel.INFO, "镜像已推送：" + fullImage
                + (StringUtils.hasText(digest) ? "，摘要 " + digest : ""));
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
    private String requireDir(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "构建机未配置" + label + "，请在服务器页面补充该目录后重新构建");
        }
        return value.strip();
    }

    /**
     * 执行 JAR / STATIC 运行时构建并采集文件产物。
     *
     * <p>脚本部署（SCRIPT_DEPLOY）要求 build_command 显式给出；进入模块目录执行后按 artifact_path 通配定位产物，
     * 计算 SHA256 供部署校验一致性。</p>
     *
     * @param task         当前任务
     * @param session      已认证会话
     * @param project      项目
     * @param config       构建配置
     * @param runtimeType  运行时 JAR / STATIC
     * @param workspacePath 工作区路径
     * @param artifactBase  构建机产物持久目录，来自构建机档案且已校验非空
     * @param commit       提交 ID，仅用于日志与溯源
     * @param releaseVersion 发布版本号，由调用方定稿，作为构建机产物持久目录名
     * @param artifacts    产物收集列表
     * @throws DeployException 构建命令缺失、构建失败或产物未找到时抛出
     */
    private void buildFileArtifact(Task task, SshSession session, Server buildServer, Project project, BuildConfig config,
                                   RuntimeType runtimeType, String workspacePath, String artifactBase,
                                   String commit, String releaseVersion, List<BuildArtifact> artifacts) {
        if (!reportProgress(task, STAGE_BUILD, PROGRESS_BUILDING)) {
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
        // 模块目录：填写 module_dir 用 module_dir，否则用 module_name，都没有则仓库根目录。
        String moduleDir = StringUtils.hasText(config.getModuleDir()) ? config.getModuleDir()
                : (StringUtils.hasText(config.getModuleName()) ? config.getModuleName() : "");
        String buildDir = StringUtils.hasText(moduleDir) ? workspacePath + "/" + moduleDir : workspacePath;

        // 命令前缀：`. /etc/profile` 兜底把登录态 PATH 引入非交互会话；紧随其后的机器级
        // 「构建环境初始化脚本」用于补充 profile 里没有的路径（如 maven 的 bin 目录）。
        // 预检与真正执行使用同一份前缀，避免「预检说没有、实际加了前缀就能跑」的误判。
        String envPrefix = buildEnvPrefix(buildServer);

        // 构建前先确认命令本身能找到：非交互式 SSH 会话不加载 /etc/profile，PATH 里常缺 mvn，
        // 直接执行只会得到「bash: mvn: command not found」，很难定位是「没装」还是「没配 PATH」。
        // 因此先探测首词是否可执行，给出明确原因与处理办法，再执行真正的构建命令。
        checkBuildCommandExecutable(task, session, envPrefix, config.getBuildCommand());

        // 先自证工具链版本（仅 Maven 命令）：JDK 版本错配类报错只能靠这条日志定位到具体 JVM。
        logBuildToolchain(task, session, envPrefix, buildDir, firstCommandToken(config.getBuildCommand()));

        // 构建命令走「长命令超时 + 实时行回传 + 超时终止远端进程组」，
        // 项目级 command_timeout_seconds 可覆盖全局的长命令超时。
        int timeoutSeconds = resolveBuildTimeout(config, session);
        long startedAt = System.currentTimeMillis();
        SshCommandResult result = runBuildCommand(task, session, buildDir, envPrefix,
                config.getBuildCommand(), timeoutSeconds);
        long costSeconds = (System.currentTimeMillis() - startedAt) / 1000;
        if (!result.success()) {
            // 失败输出完整落日志，异常消息只留首行摘要。
            writeError(task, STAGE_BUILD, result.stderr(), MAX_BUILD_OUTPUT_LINES);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "构建命令执行失败（耗时 " + costSeconds + " 秒）：" + firstLine(result.stderr()));
        }
        appendBuildSuccessSummary(task, result, costSeconds);

        // 产物定位：相对仓库根优先，找不到再按模块目录回落。
        // 两种口径都支持，是因为仓库里的填写习惯不统一——多模块仓库习惯从仓库根写全路径
        // （如 auth-tool/linux/auth-tool-7888/auth-tool.jar），单模块仓库习惯只写模块内相对路径，
        // 只认一种会让另一半配置"填了却找不到产物"。
        String actualFile = locateFile(session, workspacePath, config.getArtifactPath());
        if (!StringUtils.hasText(actualFile) && !buildDir.equals(workspacePath)) {
            actualFile = locateFile(session, buildDir, config.getArtifactPath());
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
                    "产物转存到持久目录失败：" + firstLine(copyResult.stderr()));
        }
        String persistedPath = persistDir + "/" + fileName;

        // 版本标识必须写在持久目录里而不是工作区：工作区在任务收尾时会被整体删除，
        // 写在工作区的标识随构建结束一起消失，等于没写。
        writeArtifactVersionMarker(task, session, persistDir, releaseVersion, fileName, runtimeType, commit);

        // 配置文件转存到产物同级的 config 目录：JAR 运行时 application.yml 与 jar 分离部署，
        // 发布阶段据此投放到目标机工作目录的 config 子目录，日常发版只换 jar 不碰配置。
        List<String> configFiles = collectConfigFiles(task, session, config, workspacePath, buildDir, persistDir);

        BuildArtifact artifact = new BuildArtifact();
        artifact.setArtifactType(runtimeType == RuntimeType.JAR ? ArtifactType.JAR.getCode() : ArtifactType.STATIC.getCode());
        artifact.setArtifactName(fileName);
        artifact.setArtifactTag(version);
        artifact.setArtifactDigest(sha);
        artifact.setMetadataJson(buildMetadata(persistedPath, persistDir, configFiles));
        artifacts.add(artifact);
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                "产物已转存至 " + persistedPath + "，SHA256 " + (sha == null ? "未知" : sha));
        if (!configFiles.isEmpty()) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
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
    private void writeArtifactVersionMarker(Task task, SshSession session, String persistDir,
                                            String releaseVersion, String fileName,
                                            RuntimeType runtimeType, String commit) {
        String content = "version=" + releaseVersion + "\n"
                + "commit=" + (StringUtils.hasText(commit) ? commit : "unknown") + "\n"
                + "runtime=" + runtimeType + "\n"
                + "artifact=" + fileName + "\n"
                + "built_at=" + LocalDateTime.now().format(VERSION_TIME_FORMAT) + "\n";
        String command = "printf '%s' " + shellQuote(content) + " > " + persistDir + "/" + VERSION_MARKER;
        SshCommandResult result = session.exec(command);
        if (!result.success()) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "写入 " + VERSION_MARKER + " 失败（不影响构建结果）：" + firstLine(result.stderr()));
            return;
        }
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                "已写入版本标识 " + persistDir + "/" + VERSION_MARKER);
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
    private SshCommandResult runBuildCommand(Task task, SshSession session, String buildDir, String envPrefix,
                                             String buildCommand, int timeoutSeconds) {
        String plainCmd = "cd " + buildDir + " && " + envPrefix + buildCommand;
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                "执行构建命令（超时上限 " + timeoutSeconds + " 秒，输出实时回传）：" + plainCmd);
        String pidFile = BUILD_PID_FILE_PREFIX + task.getId() + ".pid";
        // -w 让 setsid 等待子进程并回传其退出码：进程组组长场景下 setsid 会 fork，
        // 不带 -w 时父进程会立刻退出，通道提前关闭，构建就被误判成"瞬间成功"。
        String wrapped = "setsid -w sh -c " + shellQuote("echo $$ > " + pidFile + "; " + plainCmd);
        SshCommandResult result;
        try {
            result = session.exec(wrapped, timeoutSeconds, buildOutputConsumer(task));
        } catch (SshCommandTimeoutException e) {
            terminateRemoteProcessGroup(task, session, pidFile);
            writeTimeoutOutput(task, STAGE_BUILD, e.getPartialResult());
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "构建命令执行超时（" + e.getTimeoutSeconds()
                            + " 秒，可在 build_config_json.command_timeout_seconds 放宽上限）");
        }
        // 极端精简的构建机可能没有 setsid（util-linux 被裁剪）：降级为不带进程组包装再跑一次，
        // 并如实说明"本次超时后无法终止远端进程"，而不是让用户对着一句 setsid 找不到发懵。
        if (!result.success() && isSetsidMissing(result)) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "构建机缺少 setsid 命令，改以普通方式执行：本次构建超时后无法终止远端进程组");
            return session.exec(plainCmd, timeoutSeconds, buildOutputConsumer(task));
        }
        return result;
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
    private boolean isSetsidMissing(SshCommandResult result) {
        String stderr = result.stderr() == null ? "" : result.stderr();
        return stderr.contains("setsid") && (stderr.contains("not found") || stderr.contains("No such file"));
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
    private Consumer<String> buildOutputConsumer(Task task) {
        AtomicInteger received = new AtomicInteger();
        return line -> {
            int index = received.incrementAndGet();
            if (index <= MAX_STREAM_OUTPUT_LINES) {
                appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, line);
            } else if (index == MAX_STREAM_OUTPUT_LINES + 1) {
                appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                        "（实时输出已超过 " + MAX_STREAM_OUTPUT_LINES + " 行，后续不再逐行展示，命令结束后补录末尾输出）");
            }
        };
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
    private void appendBuildSuccessSummary(Task task, SshCommandResult result, long costSeconds) {
        String output = StringUtils.hasText(result.stdout()) ? result.stdout() : result.stderr();
        long lines = StringUtils.hasText(output) ? output.strip().lines().count() : 0;
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                "构建命令执行成功：耗时 " + costSeconds + " 秒，共 " + lines + " 行输出");
        if (lines > MAX_STREAM_OUTPUT_LINES) {
            appendLog(task, STAGE_BUILD, TaskLogLevel.INFO,
                    "实时回传已达上限 " + MAX_STREAM_OUTPUT_LINES + " 行，补录末尾 " + MAX_BUILD_OUTPUT_LINES + " 行：");
            writeLines(task, STAGE_BUILD, TaskLogLevel.INFO,
                    tailLines(output, MAX_BUILD_OUTPUT_LINES), MAX_BUILD_OUTPUT_LINES);
        }
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
    private void terminateRemoteProcessGroup(Task task, SshSession session, String pidFile) {
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
    private void writeTimeoutOutput(Task task, String stage, SshCommandResult partial) {
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
    private int resolveBuildTimeout(BuildConfig config, SshSession session) {
        Integer override = config.getCommandTimeoutSeconds();
        return override != null && override > 0 ? override : session.getLongCommandTimeoutSeconds();
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
    private String tailLines(String text, int maxLines) {
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
    private String lastLine(String text) {
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
    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
    private String buildEnvPrefix(Server buildServer) {
        StringBuilder prefix = new StringBuilder(PROFILE_SOURCE_COMMAND);
        String envScript = buildServer == null ? null : buildServer.getBuildEnvScript();
        if (StringUtils.hasText(envScript)) {
            // 单行脚本，原样拼接；内容由用户在服务器页面维护，平台不解析（运维可写任意 shell 片段）。
            prefix.append(envScript.strip()).append("; ");
        }
        return prefix.toString();
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
    private void checkBuildCommandExecutable(Task task, SshSession session, String envPrefix, String buildCommand) {
        String token = firstCommandToken(buildCommand);
        if (!StringUtils.hasText(token)) {
            // 取不到首词（空命令等）时不拦截，交给后续执行与既有校验兜底。
            return;
        }
        boolean pathForm = token.contains("/");
        String probe = pathForm
                ? "test -x " + token + " && echo " + CMD_PROBE_OK + " || echo " + CMD_PROBE_MISSING
                : "command -v " + token + " >/dev/null 2>&1 && echo " + CMD_PROBE_OK
                        + " || echo " + CMD_PROBE_MISSING;
        SshCommandResult result = session.exec(envPrefix + probe);
        if (result.stdout().contains(CMD_PROBE_MISSING)) {
            String reason = pathForm ? "该路径不存在或没有执行权限：" + token
                    : "该命令不在构建机的 PATH 中（非交互式会话不加载 /etc/profile 等登录环境）：" + token;
            appendLog(task, STAGE_BUILD, TaskLogLevel.ERROR, "构建命令预检失败，" + reason);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "构建命令不可执行（" + reason + "）。三种处理方式："
                            + "① 安装该工具（如 Maven：yum install -y maven）；"
                            + "② 在服务器档案的「构建环境初始化脚本」里补 PATH（如 export PATH=/opt/apache-maven-3.8.8/bin:$PATH）；"
                            + "③ 在 build_command 里直接写工具绝对路径（如 /opt/apache-maven-3.8.8/bin/mvn -B clean package）");
        }
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
    private String firstCommandToken(String command) {
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
    private boolean isEnvAssignment(String token) {
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
    private String locateFile(SshSession session, String baseDir, String pattern) {
        SshCommandResult result = session.exec("ls -1 " + baseDir + "/" + pattern + " 2>/dev/null | head -1");
        return result.success() && StringUtils.hasText(result.stdout()) ? result.stdout().strip() : null;
    }

    /**
     * 把 build_config_json.config_paths 指向的配置文件转存到产物同级 config 目录。
     *
     * <p>JAR 运行时才有意义：application.yml 与 jar 分离部署，配置不随版本目录漂移。
     * 定位口径与产物一致（仓库根优先、模块目录回落）；单个文件找不到只记日志不中断构建，
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
    private List<String> collectConfigFiles(Task task, SshSession session, BuildConfig config,
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
    private String buildMetadata(String persistedPath, String persistDir, List<String> configFiles) {
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
    private void dockerBuild(Task task, SshSession session, String workspacePath, String dockerfileOpt, String fullImage) {
        String cmd = "cd " + workspacePath + " && docker build " + dockerfileOpt + " -t " + fullImage + " .";
        // 镜像构建是耗时命令（拉基础镜像、装依赖、编译），按长命令超时等待。
        SshCommandResult result = session.execLong(cmd);
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "docker build 失败：" + firstLine(result.stderr()));
        }
        // 镜像构建日志同样按构建类上限回显（普通命令的 30 行会让构建错误被截掉）。
        if (StringUtils.hasText(result.stdout())) {
            writeLines(task, STAGE_BUILD, TaskLogLevel.INFO, result.stdout(), MAX_BUILD_OUTPUT_LINES);
        }
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
    private void dockerPush(Task task, SshSession session, Project project, String fullImage) {
        if (!reportProgress(task, STAGE_PUSH, PROGRESS_PUSHING)) {
            return;
        }
        String loginHost = null;
        String passwordFile = null;
        try {
            if (StringUtils.hasText(project.getRegistryId())) {
                Registry registry = registryMapper.selectById(project.getRegistryId());
                if (registry != null && StringUtils.hasText(registry.getCredentialId())) {
                    Credential credential = credentialMapper.selectById(registry.getCredentialId());
                    if (credential == null) {
                        throw new DeployException(ResponseCode.DATA_NOT_FOUND, "镜像仓库绑定的凭据不存在");
                    }
                    String plaintext = credentialCrypto.decrypt(credential.getEncryptedValue());
                    String[] up = RegistryAuthSupport.parseUserPassword(plaintext);
                    loginHost = registry.getRegistryUrl();
                    // 密码经文件 + stdin 传给 docker，**不走命令行**：`docker login -p <密码>` 会把明文
                    // 留在构建机的进程列表（ps -ef）里，同机其他用户可直接看到。
                    passwordFile = "/tmp/ld_registry_pwd_" + task.getBizId();
                    writeSecretFile(task, session, passwordFile, up[1], "镜像仓库密码", STAGE_PUSH);
                    appendLog(task, STAGE_PUSH, TaskLogLevel.INFO,
                            "docker login " + loginHost + "（密码经 stdin 传入，不落命令行与日志）");
                    SshCommandResult loginResult = session.exec(
                            RegistryAuthSupport.dockerLoginCommand(loginHost, up[0], passwordFile));
                    if (!loginResult.success()) {
                        throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                                "镜像仓库登录失败：" + firstLine(loginResult.stderr()));
                    }
                }
            }
            appendLog(task, STAGE_PUSH, TaskLogLevel.INFO, "推送镜像 " + fullImage);
            SshCommandResult pushResult = session.execLong("docker push " + fullImage);
            if (!pushResult.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "docker push 失败：" + firstLine(pushResult.stderr()));
            }
            // 推送日志按构建类上限回显：分层镜像逐层推送，30 行往往只够看到第一层。
            if (StringUtils.hasText(pushResult.stdout())) {
                writeLines(task, STAGE_PUSH, TaskLogLevel.INFO, pushResult.stdout(), MAX_BUILD_OUTPUT_LINES);
            }
        } finally {
            // 密码文件与登录态**成功失败都要清**：推送失败时更不能把明文密码留在构建机磁盘上。
            cleanupRegistryCredential(task, session, passwordFile, loginHost);
        }
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
    private void cleanupRegistryCredential(Task task, SshSession session, String passwordFile, String loginHost) {
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
    private String dockerDigest(SshSession session, String fullImage) {
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
    private BuildArtifact imageArtifact(ArtifactType type, String fullImage, String tag, String digest) {
        BuildArtifact artifact = new BuildArtifact();
        artifact.setArtifactType(type.getCode());
        artifact.setArtifactName(fullImage);
        artifact.setArtifactTag(tag);
        artifact.setArtifactDigest(digest);
        return artifact;
    }

    // ==================== 配置/字符串辅助 ====================

    /**
     * 解析 build_config_json；为空或非 JSON 时返回空配置（不报错）。
     */
    private BuildConfig parseBuildConfig(String json) {
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
     * 解析镜像仓库地址：优先用 build_config_json.image_repository，否则由 registry 推导。
     */
    private String resolveImageRepository(Project project, BuildConfig config) {
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
     * 生成平台内置 Dockerfile（Maven 多阶段模板，v1 面向 Java 应用）。
     */
    private String generatePlatformDockerfile(Project project, BuildConfig config) {
        String moduleDir = StringUtils.hasText(config.getModuleDir()) ? config.getModuleDir()
                : (StringUtils.hasText(config.getModuleName()) ? config.getModuleName() : "");
        String buildCommand = StringUtils.hasText(config.getBuildCommand())
                ? config.getBuildCommand() : "mvn clean package -DskipTests";
        String jarGlob = StringUtils.hasText(moduleDir) ? "/src/" + moduleDir + "/target/*.jar" : "/src/target/*.jar";
        return "FROM maven:3.9-eclipse-temurin-21 AS build\n"
                + "WORKDIR /src\n"
                + "COPY . .\n"
                + "RUN " + buildCommand + "\n"
                + "FROM eclipse-temurin:21-jre\n"
                + "WORKDIR /app\n"
                + "COPY --from=build /src" + jarGlob + " app.jar\n"
                + "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n";
    }

    /**
     * 把文本写到构建机指定文件（heredoc，避免大段内容逐行拼命令）。
     */
    private void writeRemoteFile(SshSession session, String path, String content) {
        // 内容可能含单引号/特殊字符，用 base64 中转最稳。
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String cmd = "echo " + b64 + " | base64 -d > " + path;
        SshCommandResult result = session.exec(cmd);
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "写入平台 Dockerfile 失败：" + firstLine(result.stderr()));
        }
    }

    /**
     * 组装发布版本记录（build 成功后由 BuildService 写入并置为可发布）。
     *
     * <p>版本号与序号取调用方在克隆后定稿的那一份，不在这里重新生成——镜像 tag 与产物目录
     * 已经按它落地，重新生成会让版本记录指向不存在的产物。</p>
     *
     * @param project        目标项目
     * @param runtimeType    运行时类型
     * @param commit         提交 ID
     * @param releaseVersion 本次构建定稿的版本序号与版本号
     * @param artifacts      本次构建产出的全部产物
     * @return 待落库的发布版本记录
     */
    private ReleaseRecord assembleRelease(Project project, RuntimeType runtimeType, String commit,
                                          BuildService.ReleaseVersion releaseVersion, List<BuildArtifact> artifacts) {
        ReleaseRecord release = new ReleaseRecord();
        release.setProjectId(project.getId());
        release.setVersion(releaseVersion.version());
        release.setVersionSeq(releaseVersion.seq());
        release.setCommitId(commit);
        release.setRuntimeType(runtimeType.getCode());
        if (runtimeType == RuntimeType.DOCKER) {
            // 取应用镜像产物填 image_tag / image_digest。
            for (BuildArtifact a : artifacts) {
                if (ArtifactType.APP_IMAGE.getCode().equals(a.getArtifactType())) {
                    release.setImageTag(a.getArtifactName());
                    release.setImageDigest(a.getArtifactDigest());
                }
            }
        } else {
            // 文件类：取第一个产物的路径与 SHA256。
            if (!artifacts.isEmpty()) {
                BuildArtifact a = artifacts.get(0);
                release.setArtifactPath(extractPath(a.getMetadataJson()));
                release.setSha256(a.getArtifactDigest());
            }
        }
        return release;
    }

    /**
     * 从产物 metadata_json 取出构建机上的绝对路径。
     */
    private String extractPath(String metadataJson) {
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
    private void applyRetentionPolicy(Task task, Project project, Server buildServer, SshSession session) {
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
    private void cleanupWorkspace(Task task, SshSession session, String workspacePath) {
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
    private boolean reportProgress(Task task, String stage, int progress) {
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
    private void appendLog(Task task, String stage, TaskLogLevel level, String content) {
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
    private void writeOutput(Task task, String stage, String output) {
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
    private void writeError(Task task, String stage, String error, int maxLines) {
        if (!StringUtils.hasText(error)) {
            return;
        }
        writeLines(task, stage, TaskLogLevel.ERROR, error, maxLines);
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
    private void writeCommandOutput(Task task, String stage, SshCommandResult result) {
        if (!StringUtils.hasText(result.stdout()) && StringUtils.hasText(result.stderr())) {
            writeLines(task, stage, TaskLogLevel.INFO, result.stderr(), MAX_OUTPUT_LINES);
            return;
        }
        writeOutput(task, stage, result.stdout());
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
    private void logBuildToolchain(Task task, SshSession session, String envPrefix,
                                   String buildDir, String token) {
        if (!isMavenExecutable(token)) {
            return;
        }
        SshCommandResult result = session.exec("cd " + buildDir + " && " + envPrefix + token + " -v");
        if (!result.success()) {
            // 版本探测属于信息类日志，失败不拦截构建，原因交给后续正式执行的报错。
            return;
        }
        appendLog(task, STAGE_BUILD, TaskLogLevel.INFO, "构建工具链版本：");
        writeOutput(task, STAGE_BUILD, result.stdout());
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
    private boolean isMavenExecutable(String token) {
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
    private void writeLines(Task task, String stage, TaskLogLevel level, String text, int maxLines) {
        String[] lines = text.strip().split("\\R");
        for (int i = 0; i < lines.length && i < maxLines; i++) {
            appendLog(task, stage, level, lines[i]);
        }
        if (lines.length > maxLines) {
            appendLog(task, stage, level,
                    "（输出共 " + lines.length + " 行，仅保留前 " + maxLines + " 行）");
        }
    }

    /**
     * 把异常转为安全描述（去除可能残留的凭据片段）。
     */
    private String safeMessage(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 2000 ? msg.substring(0, 2000) + "..." : msg;
    }

    /**
     * 取命令输出第一行（用于错误摘要）。
     */
    private String firstLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "无详细信息";
        }
        String t = text.strip();
        int idx = t.indexOf('\n');
        return idx < 0 ? t : t.substring(0, idx);
    }

    /**
     * 把命令中的凭据替换为 *** 用于日志展示（实现见 {@link SecretMasker}）。
     *
     * @param command 待脱敏的命令
     * @return 脱敏后的文本
     */
    private String maskSecret(String command) {
        return SecretMasker.maskSecret(command);
    }
}
