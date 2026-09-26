package com.lx.deploy.task.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.crypto.CredentialCrypto;
import com.lx.deploy.common.enums.credential.CredentialType;
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
import com.lx.deploy.project.model.ProjectEnvironmentTarget;
import com.lx.deploy.project.mapper.ProjectEnvironmentTargetMapper;
import com.lx.deploy.registry.mapper.RegistryMapper;
import com.lx.deploy.registry.model.Registry;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.service.TaskLogService;
import com.lx.deploy.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目连通性测试任务处理器。
 *
 * <p>回答一个构建前必须知道、但只有等到构建失败才会暴露的问题：**这台构建机现在能不能拉到代码、
 * 能不能推上镜像**。构建链路里三个最容易「第一次跑才暴雷」的环节——Git 仓库不可达/凭据失效、
 * 分支名写错、镜像仓库登录失败——都在这里提前验证，且**全程只读**：不写工作区、不构建、不推送。</p>
 *
 * <p>执行位置是项目的**构建机**（{@code project.build_server_id}），不是平台所在机器：
 * 平台机器常常不装 git / docker，网络策略也与构建机不同，在那上面测出来的结论说明不了构建能否成功。</p>
 *
 * <p>必需项失败直接判定任务失败（Git 仓库不可达、凭据无效、分支不存在、镜像仓库登录失败）；
 * 非必需项（mvn 未安装、curl 不可用、非 DOCKER 运行时无镜像仓库）只记 INFO，
 * 不把「环境缺组件」误报成「连不通」。判定口径与 {@code ServerTestTaskHandler} 保持一致。</p>
 *
 * <p>凭据处理与构建链路**共用同一份实现**（{@link GitAuthSupport} / {@link RegistryAuthSupport}）：
 * 令牌同样落在 URL 密码位、镜像仓库密码同样走 {@code --password-stdin}、命令写日志前同样过
 * {@link SecretMasker}。测试说通、构建却失败，通常就是两处实现分了叉。</p>
 *
 * <p>互斥键取构建机（{@code build-server:构建机ID}），与 BUILD 任务同键：测试期间会执行
 * {@code docker login}（写同机 {@code ~/.docker/config.json}），与正在推送的构建并发会互相登出，
 * 必须串行。取不到构建机时回落到项目级互斥键，保证同项目不会同时跑两次测试。</p>
 *
 * @author lx
 * @since 2026-09-26 02:30:00
 */
@Slf4j
@Component
public class ProjectConnectTestTaskHandler implements TaskHandler {

    /** 任务类型编码，与 TaskType.PROJECT_TEST 保持一致。 */
    private static final String TASK_TYPE = TaskType.PROJECT_TEST.getCode();

    /** 主互斥键前缀：与 BUILD 任务同键，保证同一台构建机上的测试与构建不并发。 */
    private static final String BUILD_SERVER_LOCK_PREFIX = "build-server:";

    /** 回退互斥键前缀：读不到项目档案（已被删除等）时保证同项目仍串行。 */
    private static final String PROJECT_LOCK_PREFIX = "project-test:";

    /** 单条命令输出最多写入几条日志，避免大仓库的分支列表把任务日志刷屏。 */
    private static final int MAX_OUTPUT_LINES = 30;

    /** 阶段名：连接构建机。 */
    private static final String STAGE_CONNECT = "连接构建机";
    /** 阶段名：工具链检查。 */
    private static final String STAGE_TOOLCHAIN = "工具链检查";
    /** 阶段名：代码仓库校验。 */
    private static final String STAGE_GIT = "代码仓库校验";
    /** 阶段名：镜像仓库校验。 */
    private static final String STAGE_REGISTRY = "镜像仓库校验";
    /** 阶段名：目标机运行前置检查（JAR 运行时校验 java 可用）。 */
    private static final String STAGE_TARGET = "目标机前置检查";
    /** 阶段名：收尾。 */
    private static final String STAGE_FINISH = "完成";

    /** 工具链探测命令：只取版本行，避免多余输出。 */
    private static final String CMD_GIT_VERSION = "git --version 2>&1 | head -1";
    /** Docker 探测命令。 */
    private static final String CMD_DOCKER_VERSION = "docker --version 2>&1 | head -1";
    /** Maven 探测命令（非必需，仅提示）。 */
    private static final String CMD_MAVEN_VERSION = "mvn -v 2>&1 | head -1";

    /** 镜像仓库 v2 端点探测结果的标记前缀。 */
    private static final String REGISTRY_PROBE_MARKER = "LIGHT_DEPLOY_REGISTRY_HTTP=";

    /** 从探测输出中提取 HTTP 状态码。 */
    private static final Pattern REGISTRY_HTTP_CODE = Pattern.compile(REGISTRY_PROBE_MARKER + "(\\d{3})");

    /** 分支名等 ref 允许的字符集，防止被数据库里改脏的值带出 shell 元字符。 */
    private static final Pattern SAFE_REF = Pattern.compile("[^A-Za-z0-9._/-]");

    /** java 路径允许的字符集：路径会被拼进远程命令，含 shell 元字符的值一律不拼、直接判失败。 */
    private static final Pattern SAFE_JAVA_BIN = Pattern.compile("[A-Za-z0-9._/-]+");

    /** 项目 Mapper，用于读取项目档案（构建机、仓库地址、运行时、凭据绑定）。 */
    private final ProjectMapper projectMapper;

    /** 部署目标 Mapper，用于取项目的目标机（JAR 运行时的 java 前置校验要逐台执行）。 */
    private final ProjectEnvironmentTargetMapper targetMapper;

    /** JSON 解析器：读取运行配置里的 java_bin（项目级与目标级都可能配）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 服务器 Mapper，用于读取构建机。 */
    private final ServerMapper serverMapper;

    /** 镜像仓库 Mapper，用于读取仓库地址与凭据绑定。 */
    private final RegistryMapper registryMapper;

    /** 凭据 Mapper，用于读取 Git / 镜像仓库凭据。 */
    private final CredentialMapper credentialMapper;

    /** 凭据加解密工具，仅在使用前短暂解密。 */
    private final CredentialCrypto credentialCrypto;

    /** SSH 会话工厂，负责解密服务器凭据并建立已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /** 任务服务，用于汇报执行阶段与进度。 */
    private final TaskService taskService;

    /** 任务日志服务，用于把每一步执行过程写入 lds_task_log。 */
    private final TaskLogService taskLogService;

    /**
     * 创建项目连通性测试处理器。
     *
     * @param projectMapper      项目 Mapper
     * @param targetMapper       部署目标 Mapper
     * @param serverMapper       服务器 Mapper
     * @param registryMapper     镜像仓库 Mapper
     * @param credentialMapper   凭据 Mapper
     * @param credentialCrypto   凭据加解密工具
     * @param sshSessionFactory  SSH 会话工厂
     * @param taskService        任务服务
     * @param taskLogService     任务日志服务
     */
    public ProjectConnectTestTaskHandler(ProjectMapper projectMapper,
                                         ProjectEnvironmentTargetMapper targetMapper,
                                         ServerMapper serverMapper,
                                         RegistryMapper registryMapper,
                                         CredentialMapper credentialMapper,
                                         CredentialCrypto credentialCrypto,
                                         SshSessionFactory sshSessionFactory,
                                         TaskService taskService,
                                         TaskLogService taskLogService) {
        this.projectMapper = projectMapper;
        this.targetMapper = targetMapper;
        this.serverMapper = serverMapper;
        this.registryMapper = registryMapper;
        this.credentialMapper = credentialMapper;
        this.credentialCrypto = credentialCrypto;
        this.sshSessionFactory = sshSessionFactory;
        this.taskService = taskService;
        this.taskLogService = taskLogService;
    }

    /**
     * 该处理器负责的任务类型。
     *
     * @return PROJECT_TEST
     */
    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    /**
     * 互斥键：优先与构建任务同键（同一台构建机上测试与构建串行）。
     *
     * <p>bizId 是项目 ID，构建机要从项目档案里读。读不到（项目已被删除、数据被改脏）时回落到
     * 项目级互斥键，不能因为读不到就返回 null——那样同一项目可以同时发起多次测试。</p>
     *
     * @param task 待执行任务，bizId 为项目主键
     * @return 形如 {@code build-server:构建机ID} 的互斥键；读不到构建机时为 {@code project-test:项目ID}
     */
    @Override
    public String lockKey(Task task) {
        try {
            Project project = projectMapper.selectById(task.getBizId());
            if (project != null && StringUtils.hasText(project.getBuildServerId())) {
                return BUILD_SERVER_LOCK_PREFIX + project.getBuildServerId();
            }
        } catch (Exception e) {
            log.warn("读取项目构建机用于锁键失败，回落到项目级互斥键，projectId={}", task.getBizId(), e);
        }
        return PROJECT_LOCK_PREFIX + task.getBizId();
    }

    /**
     * 执行项目连通性测试。
     *
     * <p>流程：校验项目与构建机 → SSH 登录构建机 → 工具链检查 → Git 仓库校验 →
     * 镜像仓库校验（仅 DOCKER 且绑定了仓库）→ 汇总结论。任必需项失败即抛异常，由执行器判任务失败。</p>
     *
     * @param task 已进入执行中状态的任务，bizId 为项目主键
     * @throws DeployException 任务数据异常、项目/构建机不可用、必需探测项失败时抛出
     */
    @Override
    public void execute(Task task) {
        String projectId = task.getBizId();
        if (!StringUtils.hasText(projectId)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目连通性测试任务缺少目标项目");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "目标项目不存在或已被删除");
        }
        // 停用项目不参与构建与发布，测试属于构建前预检，同样拒绝执行。
        if (project.getStatus() == null || project.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "目标项目已停用，不允许执行连通性测试");
        }
        RuntimeType runtimeType = RuntimeType.fromCode(project.getRuntimeType());
        if (runtimeType == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目的运行时类型非法：" + project.getRuntimeType());
        }
        // 构建机必须由项目档案指定：测试的意义就在于「在真正构建的那台机器上验证」，
        // 缺了它就只能连平台自己的机器，测出来的结论说明不了构建能否成功。
        if (!StringUtils.hasText(project.getBuildServerId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "项目未绑定构建机，无法执行连通性测试；请先在项目编辑页选择构建机");
        }
        Server buildServer = serverMapper.selectById(project.getBuildServerId());
        if (buildServer == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "项目绑定的构建机不存在或已被删除，请重新选择");
        }
        if (buildServer.getStatus() == null || buildServer.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "项目绑定的构建机已停用，请更换构建机");
        }

        String target = buildServer.getUsername() + "@" + buildServer.getHost() + ":" + buildServer.getSshPort();
        appendLog(task, STAGE_CONNECT, TaskLogLevel.INFO,
                "开始校验项目 " + project.getCode() + "（" + project.getName() + "）的构建前置条件，"
                        + "执行机器：" + buildServer.getServerName() + "（" + target + "）");
        appendLog(task, STAGE_CONNECT, TaskLogLevel.INFO, "运行时：" + runtimeType.getCode()
                + "，构建模式：" + project.getBuildMode());
        if (!reportProgress(task, STAGE_CONNECT, 5)) {
            return;
        }

        try (SshSession session = sshSessionFactory.open(buildServer)) {
            appendLog(task, STAGE_CONNECT, TaskLogLevel.INFO, "SSH 连接与认证成功");
            reportProgress(task, STAGE_CONNECT, 15);

            // 1) 工具链：缺 git 一定构建不了，缺 docker 只有 DOCKER 运行时致命。
            if (!checkToolchain(task, session, runtimeType)) {
                return;
            }
            // 2) 代码仓库：ls-remote 等价于真克隆前的认证 + 可达 + 分支存在性校验，秒级完成且不落盘。
            checkGitRepository(task, session, project);
            // 3) 镜像仓库：仅有镜像产物的运行时需要。
            checkRegistry(task, session, project, runtimeType);
            // 4) 目标机运行前置：JAR 运行时 java 必须在每台目标机上真实可用。
            checkJavaOnTargets(task, project, runtimeType);
            // 5) 结论：把各段的结论写清楚，运维只看结尾这几行就能判断这次测试成没成。
            taskService.updateProgress(task.getId(), STAGE_FINISH, 100);
            appendLog(task, STAGE_FINISH, TaskLogLevel.INFO,
                    "项目连通性测试通过：代码仓库可拉取"
                            + (isImageRuntime(project, runtimeType) ? "、镜像仓库可登录" : "")
                            + (runtimeType == RuntimeType.JAR ? "、目标机 java 可用" : "")
                            + "，可以放心发起构建");
        } catch (DeployException e) {
            // 业务异常原样上抛，保留「分支不存在」「镜像仓库登录失败」这类可操作的具体原因。
            throw e;
        } catch (Exception e) {
            log.warn("项目连通性测试执行异常，projectId={}", projectId, e);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "项目连通性测试执行失败：" + e.getMessage());
        }
    }

    /**
     * 检查构建机上的构建工具链。
     *
     * <p>git 必需（没有它连代码都拉不下来）；DOCKER 运行时 docker 必需；mvn 只在提示层——
     * 构建命令也可能是 npm、gradle，无法据项目档案断定，因此缺失只给补装建议，不判失败。</p>
     *
     * @param task        当前任务
     * @param session     已认证的 SSH 会话
     * @param runtimeType 项目运行时类型
     * @return true 表示可以继续；false 表示任务已被取消，调用方应立即停止
     * @throws DeployException git 或 docker 缺失时抛出
     */
    private boolean checkToolchain(Task task, SshSession session, RuntimeType runtimeType) {
        if (!reportProgress(task, STAGE_TOOLCHAIN, 25)) {
            return false;
        }
        runRequiredProbe(task, session, CMD_GIT_VERSION, "git",
                "构建机未安装 git，无法拉取代码：请在构建机执行 yum install -y git（或 apt-get install -y git）");
        if (runtimeType == RuntimeType.DOCKER) {
            runRequiredProbe(task, session, CMD_DOCKER_VERSION, "docker",
                    "DOCKER 运行时需要 docker：请在构建机安装 Docker 并确认 dockerd 已启动");
        } else {
            appendLog(task, STAGE_TOOLCHAIN, TaskLogLevel.INFO,
                    "运行时为 " + runtimeType.getCode() + "，不产出镜像，跳过 docker 检查");
        }
        // 可选：缺失只提示，不判失败。
        SshCommandResult maven = session.exec(CMD_MAVEN_VERSION);
        if (maven.success() && StringUtils.hasText(maven.stdout())) {
            appendLog(task, STAGE_TOOLCHAIN, TaskLogLevel.INFO, "mvn：" + firstLine(maven.stdout()));
        } else {
            appendLog(task, STAGE_TOOLCHAIN, TaskLogLevel.INFO,
                    "构建机未检测到 Maven（mvn）。若项目的构建命令用不到 Maven 可忽略；"
                            + "若用到，请安装（yum install -y maven）或在服务器档案的「构建环境初始化脚本」里补 PATH");
        }
        return true;
    }

    /**
     * 执行一条必需的工具链探测命令。
     *
     * @param task        当前任务
     * @param session     已认证会话
     * @param command     探测命令
     * @param label       组件名，用于日志与失败消息
     * @param advice      缺失时的可操作建议
     * @throws DeployException 命令非零退出时抛出
     */
    private void runRequiredProbe(Task task, SshSession session, String command, String label, String advice) {
        SshCommandResult result = session.exec(command);
        if (result.success() && StringUtils.hasText(result.stdout())) {
            appendLog(task, STAGE_TOOLCHAIN, TaskLogLevel.INFO, firstLine(result.stdout()));
            return;
        }
        throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "构建机缺少 " + label + "：" + advice);
    }

    /**
     * 校验代码仓库：可达性、凭据有效性、分支存在性。
     *
     * <p>用 {@code git ls-remote} 而不是真克隆：它走完整的网络 + 认证链路（同样在 URL 密码位带令牌、
     * 同样用 GIT_SSH_COMMAND 加载私钥），但不下载对象、不占磁盘、不留下工作区，
     * 秒级即可给出「能不能拉」的结论。</p>
     *
     * <p>分支不存在时 {@code git ls-remote --heads <url> <branch>} 会**成功返回且输出为空**，
     * 所以不能只看退出码——这正是构建时「克隆出一个空目录」或「报分支不存在」的根因，
     * 必须在测试阶段就拦下。</p>
     *
     * @param task    当前任务
     * @param session 已认证会话
     * @param project 项目（含 gitUrl / gitBranch / gitCredentialId）
     * @throws DeployException 未配置仓库地址、凭据类型与协议不匹配、仓库不可达、分支不存在时抛出
     */
    private void checkGitRepository(Task task, SshSession session, Project project) {
        if (!reportProgress(task, STAGE_GIT, 45)) {
            return;
        }
        String gitUrl = project.getGitUrl();
        String sourceType = project.getSourceType() == null ? "GIT" : project.getSourceType();
        if (!"GIT".equals(sourceType)) {
            appendLog(task, STAGE_GIT, TaskLogLevel.INFO,
                    "项目代码来源为 " + sourceType + "（上传产物），不涉及代码仓库拉取，跳过仓库校验");
            return;
        }
        if (!StringUtils.hasText(gitUrl)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目未配置 Git 仓库地址，无法校验");
        }
        String branch = sanitizeRef(project.getGitBranch());

        // 一次构建探测所需的一切：命令（含凭据）、用于日志的脱敏命令、用完要删的临时私钥文件。
        GitProbe probe = buildGitProbe(task, session, project, gitUrl, branch);
        try {
            appendLog(task, STAGE_GIT, TaskLogLevel.INFO, "执行仓库探测：" + probe.displayCommand());
            // ls-remote 在超大仓库上也可能耗时（要协商 ref 列表），按秒级命令上限执行即可。
            SshCommandResult result = session.exec(probe.command());
            if (!result.success()) {
                writeOutput(task, STAGE_GIT, SecretMasker.maskSecret(result.stderr()), TaskLogLevel.INFO);
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "代码仓库不可访问或凭据无效：" + firstLine(SecretMasker.maskSecret(result.stderr())));
            }
            if (!StringUtils.hasText(result.stdout())) {
                // 退出码为 0 但没有任何 ref：配了分支就是分支不存在，没配就是仓库为空。
                if (StringUtils.hasText(branch)) {
                    throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                            "代码仓库可访问，但分支 " + branch + " 不存在；请核对项目的 Git 分支配置");
                }
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "代码仓库可访问，但没有任何分支（空仓库）；请先推送代码或更换仓库");
            }
            writeOutput(task, STAGE_GIT, result.stdout(), TaskLogLevel.INFO);
            String ref = firstLine(result.stdout());
            appendLog(task, STAGE_GIT, TaskLogLevel.INFO,
                    "代码仓库校验通过：" + GitAuthSupport.stripUserInfo(gitUrl)
                            + (StringUtils.hasText(branch) ? "，分支 " + branch + " 存在" : "（未指定分支，使用仓库默认分支）")
                            + "，最新引用：" + ref);
        } finally {
            // 私钥文件绝不能留在构建机上：无论成功失败都删除。
            deleteQuietly(task, session, probe.secretFile());
        }
    }

    /**
     * 拼装代码仓库探测命令。
     *
     * <p>三种鉴权形态与构建链路完全一致（共用 {@link GitAuthSupport}）：
     * HTTPS + GIT_TOKEN 把令牌注入 URL 密码位、SSH + SSH_KEY 把私钥写到临时文件后用
     * GIT_SSH_COMMAND 引用、无凭据则按公开仓库直接探测。</p>
     *
     * @param task    当前任务
     * @param session 已认证会话，SSH_KEY 类型时用于写私钥文件
     * @param project 项目
     * @param gitUrl  仓库地址
     * @param branch  已清洗的分支名，可为 null
     * @return 探测命令、脱敏展示命令与临时私钥文件路径
     * @throws DeployException 凭据不存在、类型不符或与仓库协议不匹配时抛出
     */
    private GitProbe buildGitProbe(Task task, SshSession session, Project project, String gitUrl, String branch) {
        String refOption = StringUtils.hasText(branch) ? branch + " " : "";
        String prefix = GitAuthSupport.NO_PROMPT_PREFIX + "git ls-remote --heads ";
        if (!StringUtils.hasText(project.getGitCredentialId())) {
            // 无凭据：仅公开仓库可探测。同样禁掉交互提示，私有仓库会直接报出明确原因而不是「等密码」。
            return new GitProbe(prefix + gitUrl + " " + refOption,
                    prefix + GitAuthSupport.stripUserInfo(gitUrl) + " " + refOption, null);
        }
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
            return new GitProbe(prefix + GitAuthSupport.buildTokenUrl(gitUrl, plaintext) + " " + refOption,
                    prefix + GitAuthSupport.stripUserInfo(gitUrl) + " " + refOption, null);
        }
        if (type == CredentialType.SSH_KEY) {
            if (GitAuthSupport.isHttpProtocol(gitUrl)) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "SSH_KEY 凭据仅适用于 SSH 协议仓库（如 git@gitee.com:组/仓库.git），当前地址为 HTTPS 形式");
            }
            String keyFile = "/tmp/ld_git_key_probe_" + project.getCode() + "_" + task.getBizId();
            writeSecretFile(task, session, keyFile, plaintext, STAGE_GIT);
            return new GitProbe(
                    GitAuthSupport.NO_PROMPT_PREFIX + GitAuthSupport.sshCommandPrefix(keyFile)
                            + "git ls-remote --heads " + gitUrl + " " + refOption,
                    GitAuthSupport.NO_PROMPT_PREFIX + "GIT_SSH_COMMAND='ssh -i " + keyFile + "' git ls-remote --heads "
                            + gitUrl + " " + refOption,
                    keyFile);
        }
        throw new DeployException(ResponseCode.VALIDATION_ERROR,
                "Git 凭据类型必须为 GIT_TOKEN 或 SSH_KEY，当前为 " + credential.getType());
    }

    /**
     * 校验镜像仓库：服务可达性与凭据认证。
     *
     * <p>只对**会产出镜像**的运行时执行（DOCKER 且绑定了仓库）。判定分两层：
     * 有凭据时以 {@code docker login} 成功为准（认证过了就说明网络与凭据都没问题）；
     * 无凭据（公共仓库）时退化为探测 {@code /v2/} 端点是否响应。</p>
     *
     * <p>仓库地址不带协议前缀（登记约定），探测时 https 优先、失败再试 http，
     * 兼容内网 insecure registry。</p>
     *
     * @param task        当前任务
     * @param session     已认证会话
     * @param project     项目
     * @param runtimeType 运行时类型
     * @throws DeployException 绑定的仓库不存在、凭据缺失、登录失败或仓库不可达时抛出
     */
    private void checkRegistry(Task task, SshSession session, Project project, RuntimeType runtimeType) {
        if (!reportProgress(task, STAGE_REGISTRY, 70)) {
            return;
        }
        if (!isImageRuntime(project, runtimeType)) {
            appendLog(task, STAGE_REGISTRY, TaskLogLevel.INFO,
                    "运行时为 " + runtimeType.getCode() + "，不产出镜像，跳过镜像仓库校验");
            return;
        }
        Registry registry = registryMapper.selectById(project.getRegistryId());
        if (registry == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "项目绑定的镜像仓库不存在或已被删除，请重新选择");
        }
        String host = registry.getRegistryUrl();
        if (!StringUtils.hasText(host)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "镜像仓库未配置仓库地址，无法校验");
        }
        appendLog(task, STAGE_REGISTRY, TaskLogLevel.INFO,
                "镜像仓库：" + registry.getName() + "（" + host + "），命名空间：" + registry.getNamespace());

        String passwordFile = null;
        String loginHost = null;
        try {
            if (StringUtils.hasText(registry.getCredentialId())) {
                Credential credential = credentialMapper.selectById(registry.getCredentialId());
                if (credential == null) {
                    throw new DeployException(ResponseCode.DATA_NOT_FOUND, "镜像仓库绑定的凭据不存在");
                }
                String plaintext = credentialCrypto.decrypt(credential.getEncryptedValue());
                String[] up = RegistryAuthSupport.parseUserPassword(plaintext);
                passwordFile = "/tmp/ld_registry_pwd_probe_" + task.getBizId();
                writeSecretFile(task, session, passwordFile, up[1], STAGE_REGISTRY);
                appendLog(task, STAGE_REGISTRY, TaskLogLevel.INFO,
                        "执行 docker login " + host + " -u " + up[0] + "（密码经 stdin 传入，不落命令行与日志）");
                SshCommandResult login = session.exec(
                        RegistryAuthSupport.dockerLoginCommand(host, up[0], passwordFile));
                if (!login.success()) {
                    writeOutput(task, STAGE_REGISTRY, maskSecret(login.stderr()), TaskLogLevel.INFO);
                    throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                            "镜像仓库登录失败：" + firstLine(maskSecret(login.stderr())));
                }
                loginHost = host;
                appendLog(task, STAGE_REGISTRY, TaskLogLevel.INFO, "镜像仓库登录成功，凭据有效且具备访问权限");
            } else {
                appendLog(task, STAGE_REGISTRY, TaskLogLevel.INFO,
                        "未绑定仓库凭据（按公共仓库处理），改为探测仓库服务是否可达");
            }
            probeRegistryEndpoint(task, session, host);
        } finally {
            // 密码文件与登录态成功失败都要清：失败路径更不能把明文密码留在构建机磁盘上。
            deleteQuietly(task, session, passwordFile);
            if (loginHost != null) {
                logoutQuietly(task, session, loginHost);
            }
        }
    }

    /**
     * 探测镜像仓库的 {@code /v2/} 端点是否响应。
     *
     * <p>结论只是补充：能响应（200/401/403 都算服务在）记 INFO；连不上或构建机没有 curl
     * 都只记 INFO 提示，**不判失败**——有凭据时 {@code docker login} 成功已经证明仓库可达，
     * 不该因为缺一个 curl 把已经通过的测试判成失败。</p>
     *
     * @param task    当前任务
     * @param session 已认证会话
     * @param host    仓库地址（不含协议前缀）
     */
    private void probeRegistryEndpoint(Task task, SshSession session, String host) {
        // https 优先，返回 000（连接失败）时再试 http，兼容内网 insecure registry。
        String command = "(h=$(curl -s -o /dev/null -w '%{http_code}' -m 10 https://" + host + "/v2/ 2>/dev/null); "
                + "if [ \"$h\" = \"000\" ]; then "
                + "h=$(curl -s -o /dev/null -w '%{http_code}' -m 10 http://" + host + "/v2/ 2>/dev/null); fi; "
                + "echo " + REGISTRY_PROBE_MARKER + "$h)";
        SshCommandResult result = session.exec(command);
        String output = result.stdout() == null ? "" : result.stdout();
        Matcher matcher = REGISTRY_HTTP_CODE.matcher(output);
        if (!matcher.find()) {
            appendLog(task, STAGE_REGISTRY, TaskLogLevel.INFO,
                    "未能探测仓库 HTTP 端点（构建机可能未安装 curl），跳过该项；不影响本阶段结论");
            return;
        }
        String code = matcher.group(1);
        if ("200".equals(code) || "401".equals(code) || "403".equals(code)) {
            appendLog(task, STAGE_REGISTRY, TaskLogLevel.INFO,
                    "仓库服务可达：/v2/ 返回 " + code + "（401/403 表示服务正常并要求认证）");
            return;
        }
        appendLog(task, STAGE_REGISTRY, TaskLogLevel.INFO,
                "仓库 HTTP 端点探测返回 " + code + "（000 表示连接失败）；"
                        + "若登录已成功可忽略，否则请检查网络连通性与仓库地址（当前配置：" + host + "）");
    }

    /**
     * 校验每台目标机上的 java 是否可用（仅 JAR 运行时）。
     *
     * <p>这一项<b>不在构建机上做</b>：java 是应用运行时的依赖，跑在目标机上。构建机装了什么 java
     * 与目标机毫无关系，所以在构建机上探 java 探不出问题，必须逐台连目标机。</p>
     *
     * <p>典型故障：项目运行配置把 {@code java_bin} 填成 A 机的 JDK 路径，分发到 B 机时 systemd
     * 单元里的 ExecStart 指向一个不存在的可执行文件，服务以 {@code status=203/EXEC} 起不来。
     * 而单元只在首次部署生成、之后不重写，这类错误一旦发生就要上机手工改，代价远高于发版前探一次。</p>
     *
     * <p>判定口径：配了 {@code java_bin} 就 {@code test -x} 该路径；没配就 {@code command -v java}
     * 看 PATH 里有没有。任一目标机不可用即判任务失败，并把机器名与路径写进失败原因，
     * 同时给出「在部署目标上按机器覆盖」的解决方向。</p>
     *
     * @param task        当前任务
     * @param project     项目（提供项目级运行配置）
     * @param runtimeType 运行时类型
     * @throws DeployException 目标机 java 不可用、或配置的 java_bin 不合法/不存在时抛出
     */
    private void checkJavaOnTargets(Task task, Project project, RuntimeType runtimeType) {
        if (runtimeType != RuntimeType.JAR) {
            appendLog(task, STAGE_TARGET, TaskLogLevel.INFO,
                    "运行时为 " + runtimeType.getCode() + "，不依赖目标机 JDK，跳过 java 检查");
            return;
        }
        if (!reportProgress(task, STAGE_TARGET, 85)) {
            return;
        }
        List<ProjectEnvironmentTarget> bindings = targetMapper.selectList(
                new LambdaQueryWrapper<ProjectEnvironmentTarget>()
                        .eq(ProjectEnvironmentTarget::getProjectId, project.getId())
                        .eq(ProjectEnvironmentTarget::getStatus, EnableStatus.ENABLED));
        if (bindings.isEmpty()) {
            appendLog(task, STAGE_TARGET, TaskLogLevel.INFO,
                    "项目尚未绑定任何启用的部署目标，跳过 java 检查（发布前请先绑定目标服务器）");
            return;
        }
        // 同一台机器可能被多个环境绑定：会话按机器缓存，避免重复建连。
        Map<String, SshSession> sessions = new HashMap<>();
        try {
            for (ProjectEnvironmentTarget binding : bindings) {
                Server server = serverMapper.selectById(binding.getServerId());
                if (server == null) {
                    appendLog(task, STAGE_TARGET, TaskLogLevel.INFO,
                            "部署目标关联的服务器不存在，跳过该目标");
                    continue;
                }
                if (server.getStatus() == null || server.getStatus() != EnableStatus.ENABLED) {
                    appendLog(task, STAGE_TARGET, TaskLogLevel.INFO,
                            "目标服务器 " + server.getServerName() + " 已停用，跳过该目标");
                    continue;
                }
                String javaBin = resolveJavaBin(project, binding);
                SshSession target = sessions.computeIfAbsent(server.getId(),
                        id -> sshSessionFactory.open(server));
                requireJava(task, target, server, javaBin);
            }
        } finally {
            for (SshSession session : sessions.values()) {
                try {
                    session.close();
                } catch (Exception e) {
                    log.warn("关闭目标机 SSH 会话失败", e);
                }
            }
        }
    }

    /**
     * 在单台目标机上确认 java 可用。
     *
     * @param task    当前任务
     * @param session 目标机会话
     * @param server  目标服务器，用于失败文案
     * @param javaBin 配置的 java 路径；为空表示交给平台自动探测
     * @throws DeployException java 不存在或不可执行时抛出
     */
    private void requireJava(Task task, SshSession session, Server server, String javaBin) {
        String label = server.getServerName() + "（" + server.getHost() + "）";
        if (StringUtils.hasText(javaBin)) {
            if (!SAFE_JAVA_BIN.matcher(javaBin).matches()) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "目标机 " + label + " 配置的 java 路径含非法字符：" + javaBin
                                + "；请只填写绝对路径（如 /usr/lib/jdk-17/bin/java），各机器 JDK 路径不同时在部署目标上按机器覆盖");
            }
            SshCommandResult result = session.exec("test -x " + javaBin + " && echo yes || echo no");
            if (!result.success() || !"yes".equals(result.stdout().strip())) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "配置的 java 路径在目标机 " + label + " 上不存在或不可执行：" + javaBin
                                + "；systemd 启动时会以 status=203/EXEC 失败。各机器 JDK 路径不同时，"
                                + "请清空项目级 java_bin 由平台按机器自动探测，或在对应部署目标上单独覆盖");
            }
            SshCommandResult version = session.exec(javaBin + " -version 2>&1 | head -1");
            appendLog(task, STAGE_TARGET, TaskLogLevel.INFO,
                    label + " java 可用（配置路径 " + javaBin + "）："
                            + (version.success() && StringUtils.hasText(version.stdout())
                            ? firstLine(version.stdout()) : "版本读取失败（不影响可执行判定）"));
            return;
        }
        SshCommandResult result = session.exec("command -v java >/dev/null 2>&1 && echo yes || echo no");
        if (!result.success() || !"yes".equals(result.stdout().strip())) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "目标机 " + label + " 的 PATH 中找不到 java，且项目运行配置未指定 java_bin；"
                            + "JAR 运行时的服务将无法启动，请在该机器安装 JDK 或在运行配置里填写 java 绝对路径");
        }
        SshCommandResult version = session.exec("java -version 2>&1 | head -1");
        appendLog(task, STAGE_TARGET, TaskLogLevel.INFO,
                label + " java 可用（PATH 自动探测）："
                        + (version.success() && StringUtils.hasText(version.stdout())
                        ? firstLine(version.stdout()) : "版本读取失败（不影响可执行判定）"));
    }

    /**
     * 解析某条部署目标生效的 java 路径：目标级运行配置覆盖项目级。
     *
     * <p>只取 {@code java_bin} 一个键，不解析整个运行配置：这里要的只是「java 在哪」，
     * 引入完整的配置模型会把测试与发布执行器耦合在一起。</p>
     *
     * @param project 项目，提供项目级运行配置
     * @param binding 部署目标，提供目标级差异化运行配置
     * @return 生效的 java 路径；都没配时返回 null（交给平台自动探测）
     */
    private String resolveJavaBin(Project project, ProjectEnvironmentTarget binding) {
        String targetLevel = readJsonField(binding.getRuntimeConfigJson(), "java_bin");
        if (StringUtils.hasText(targetLevel)) {
            return targetLevel.strip();
        }
        String projectLevel = readJsonField(project.getRuntimeConfigJson(), "java_bin");
        return StringUtils.hasText(projectLevel) ? projectLevel.strip() : null;
    }

    /**
     * 从 JSON 配置里读一个字符串字段；配置为空、不是对象或该键不存在时返回 null。
     *
     * @param json  JSON 文本，允许为空
     * @param field 字段名
     * @return 字段值；取不到时返回 null
     */
    private String readJsonField(String json, String field) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode value = root.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            log.warn("解析运行配置 JSON 失败，field={}", field, e);
            return null;
        }
    }

    /**
     * 判断项目本次是否需要推送镜像。
     *
     * @param project     项目
     * @param runtimeType 运行时类型
     * @return true 表示 DOCKER 运行时且绑定了镜像仓库
     */
    private boolean isImageRuntime(Project project, RuntimeType runtimeType) {
        return runtimeType == RuntimeType.DOCKER && StringUtils.hasText(project.getRegistryId());
    }

    /**
     * 清洗 ref（分支名），只保留安全字符。
     *
     * @param ref 数据库中的分支名，可为空
     * @return 清洗后的分支名；原值为空或清洗后为空时返回 null
     */
    private String sanitizeRef(String ref) {
        if (!StringUtils.hasText(ref)) {
            return null;
        }
        String cleaned = SAFE_REF.matcher(ref.strip()).replaceAll("");
        return StringUtils.hasText(cleaned) ? cleaned : null;
    }

    /**
     * 把敏感文本写入构建机临时文件（权限 600，base64 中转，日志不回显内容）。
     *
     * @param task    当前任务
     * @param session 已认证会话
     * @param path    目标文件路径
     * @param content 敏感内容明文
     * @param stage   日志所属阶段，与调用方所在阶段一致
     * @throws DeployException 写入失败时抛出
     */
    private void writeSecretFile(Task task, SshSession session, String path, String content, String stage) {
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        SshCommandResult result = session.exec("echo " + b64 + " | base64 -d > " + path + " && chmod 600 " + path);
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "写入临时凭据文件失败：" + firstLine(result.stderr()));
        }
        appendLog(task, stage, TaskLogLevel.INFO, "已写入临时凭据文件（内容不落日志）：" + path);
    }

    /**
     * 删除构建机上的临时文件，失败只记日志。
     *
     * <p>在 {@code finally} 中调用，因此**绝不抛异常**：一旦抛出会覆盖真正的失败原因。</p>
     *
     * @param task    当前任务
     * @param session 已认证会话
     * @param path    文件路径；为 null 时直接返回
     */
    private void deleteQuietly(Task task, SshSession session, String path) {
        if (!StringUtils.hasText(path)) {
            return;
        }
        try {
            if (!session.exec("rm -f " + path).success()) {
                appendLog(task, STAGE_FINISH, TaskLogLevel.INFO, "临时凭据文件清理失败，请人工删除 " + path);
            }
        } catch (Exception e) {
            log.warn("清理临时凭据文件异常，path={}", path, e);
        }
    }

    /**
     * 登出镜像仓库，失败只记日志。
     *
     * @param task    当前任务
     * @param session 已认证会话
     * @param host    仓库地址
     */
    private void logoutQuietly(Task task, SshSession session, String host) {
        try {
            if (!session.exec(RegistryAuthSupport.dockerLogoutCommand(host)).success()) {
                appendLog(task, STAGE_REGISTRY, TaskLogLevel.INFO, "docker logout 失败（不影响本次结论）");
            }
        } catch (Exception e) {
            log.warn("docker logout 异常，host={}", host, e);
        }
    }

    /**
     * 把命令输出按行写入任务日志。
     *
     * @param task    当前任务
     * @param stage   日志所属阶段
     * @param output  命令输出，允许为空
     * @param level   日志级别
     */
    private void writeOutput(Task task, String stage, String output, TaskLogLevel level) {
        if (!StringUtils.hasText(output)) {
            return;
        }
        String[] lines = output.strip().split("\\R");
        for (int i = 0; i < lines.length && i < MAX_OUTPUT_LINES; i++) {
            appendLog(task, stage, level, lines[i]);
        }
        if (lines.length > MAX_OUTPUT_LINES) {
            appendLog(task, stage, TaskLogLevel.INFO,
                    "（输出共 " + lines.length + " 行，仅保留前 " + MAX_OUTPUT_LINES + " 行）");
        }
    }

    /**
     * 取文本第一行，用于日志与错误摘要。
     *
     * @param text 原始文本，允许为空
     * @return 去掉首尾空白后的第一行；无内容时返回「无详细信息」
     */
    private String firstLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "无详细信息";
        }
        String stripped = text.strip();
        int idx = stripped.indexOf('\n');
        return idx < 0 ? stripped : stripped.substring(0, idx);
    }

    /**
     * 脱敏命令文本（实现见 {@link SecretMasker}）。
     *
     * @param text 待脱敏文本
     * @return 脱敏后的文本
     */
    private String maskSecret(String text) {
        return SecretMasker.maskSecret(text);
    }

    /**
     * 汇报任务阶段与进度。
     *
     * <p>任务可能在执行过程中被人工取消或删除，此时状态流转会被拒绝；这不是执行异常，
     * 而是「应该停止后续远程操作」的信号，返回 false 由调用方停止。</p>
     *
     * @param task     当前任务
     * @param stage    要汇报的阶段名
     * @param progress 要汇报的进度百分比
     * @return true 表示任务仍在执行中
     */
    private boolean reportProgress(Task task, String stage, int progress) {
        try {
            taskService.updateProgress(task.getId(), stage, progress);
            return true;
        } catch (Exception e) {
            appendLog(task, stage, TaskLogLevel.INFO, "任务已不在执行中，停止后续远程操作：" + e.getMessage());
            return false;
        }
    }

    /**
     * 写入一条任务日志。
     *
     * <p>日志写入失败不影响测试结论，只记录 WARN，避免因为日志表问题把可达的仓库判成不可达。</p>
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
            log.warn("写入项目连通性测试日志失败，taskId={}，原因：{}", task.getId(), e.getMessage());
        }
    }

    /**
     * 代码仓库探测所需的命令与临时文件。
     *
     * @param command        实际执行命令（含凭据，禁止直接写日志）
     * @param displayCommand 脱敏后的展示命令，可直接写日志
     * @param secretFile     需要在使用后删除的临时私钥文件路径；无则为 null
     */
    private record GitProbe(String command, String displayCommand, String secretFile) {
    }
}
