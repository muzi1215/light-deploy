package com.lx.deploy.build.executor;

import com.lx.deploy.build.mapper.BuildMapper;
import com.lx.deploy.build.model.Build;
import com.lx.deploy.build.model.BuildArtifact;
import com.lx.deploy.build.service.BuildService;
import com.lx.deploy.common.enums.build.ArtifactType;
import com.lx.deploy.common.enums.project.BuildMode;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.task.executor.TaskHandler;
import com.lx.deploy.task.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 构建任务编排器。
 *
 * <p>这是「构建」类型异步任务（{@code TaskType.BUILD}）的调度入口，由 {@code TaskExecutor} 调用。
 * 一条 build 记录对应一条 BUILD 任务，bizId 即 build.id。</p>
 *
 * <p>本类只做编排，不碰任何具体的构建动作：建连、准备工作区、拉代码由 {@link BuildSupport} 提供零件，
 * 三类构建（DOCKER / JAR_PREBUILD / 脚本文件产物）分别委托 {@link DockerBuilder} /
 * {@link JarPrebuildBuilder} / {@link ScriptBuildService}，本类按运行时与构建模式一次 switch 分发。
 * 这样拆是因为三者的入参、产物形态、镜像/文件差异巨大（DOCKER 推镜像、JAR_PREBUILD 先出 jar 再极简镜像、
 * 脚本产物只出文件），塞在一个类里会让「改 Docker 逻辑」误伤 JAR 链路。</p>
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

    /** 构建 Mapper：读取 build 记录（跨模块只依赖 Mapper，不依赖对方 Service）。 */
    private final BuildMapper buildMapper;

    /** 构建服务：维护 build 状态与产物、发布版本（执行器只回调，不自己写库）。 */
    private final BuildService buildService;

    /** 项目 Mapper：读取运行时、构建模式、构建配置与代码来源。 */
    private final ProjectMapper projectMapper;

    /** 服务器 Mapper：读取构建机连接配置。 */
    private final ServerMapper serverMapper;

    /** 构建支撑组件：工作区准备、代码拉取、共用工具与日志/进度。 */
    private final BuildSupport buildSupport;

    /** DOCKER 运行时构建服务（PROJECT_DOCKERFILE / LAYERED_DOCKER）。 */
    private final DockerBuilder dockerBuilder;

    /** JAR 预构建（B1）模式的 DOCKER 构建服务。 */
    private final JarPrebuildBuilder jarPrebuildBuilder;

    /** 脚本构建服务（JAR / STATIC 运行时文件产物）。 */
    private final ScriptBuildService scriptBuildService;

    /**
     * 创建构建任务处理器。
     *
     * @param buildMapper          构建 Mapper
     * @param buildService         构建服务
     * @param projectMapper        项目 Mapper
     * @param serverMapper         服务器 Mapper
     * @param buildSupport         构建支撑组件
     * @param dockerBuilder        DOCKER 构建服务
     * @param jarPrebuildBuilder   JAR 预构建服务
     * @param scriptBuildService   脚本构建服务
     */
    public BuildTaskHandler(BuildMapper buildMapper,
                           BuildService buildService,
                           ProjectMapper projectMapper,
                           ServerMapper serverMapper,
                           BuildSupport buildSupport,
                           DockerBuilder dockerBuilder,
                           JarPrebuildBuilder jarPrebuildBuilder,
                           ScriptBuildService scriptBuildService) {
        this.buildMapper = buildMapper;
        this.buildService = buildService;
        this.projectMapper = projectMapper;
        this.serverMapper = serverMapper;
        this.buildSupport = buildSupport;
        this.dockerBuilder = dockerBuilder;
        this.jarPrebuildBuilder = jarPrebuildBuilder;
        this.scriptBuildService = scriptBuildService;
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
        if (runtimeType == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目的运行时非法：" + project.getRuntimeType());
        }
        if (buildMode == null) {
            // 平台模板模式已下线（Dockerfile 改由项目自带），存量记录会走到这里。
            // 这里必须指名该改什么，否则用户只看到「构建模式非法」却无从下手。
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "项目的构建模式「" + project.getBuildMode() + "」不受支持；"
                            + "平台模板已下线，Dockerfile 与 Compose 文件请由项目仓库提供，"
                            + "并在项目里改用「项目Dockerfile」或「分层构建」模式");
        }
        // ARTIFACT_ONLY 不做任何构建：产物由调用方通过 POST /build/upload 上传登记。
        // 走到这里说明有人对这类项目误发起了构建任务，与其在执行阶段失败，不如在入口说清正确入口。
        if (buildMode == BuildMode.ARTIFACT_ONLY) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "构建模式 ARTIFACT_ONLY（仅发布产物）不发起构建任务，请通过产物上传接口 POST /build/upload 登记版本");
        }
        // 工作区与产物目录取自构建机档案：目录属于具体机器而不是平台全局配置，
        // 由服务器页面维护。未配置时给出可操作的提示，不在代码里猜一个默认路径兜底。
        String workspaceBase = buildSupport.requireDir(buildServer.getBuildWorkspaceDir(), "构建工作区根目录");
        String artifactBase = buildSupport.requireDir(buildServer.getBuildArtifactDir(), "构建产物持久目录");

        // 解析构建配置 JSON，字段缺失由后续阶段按需判定。
        BuildConfig config = buildSupport.parseBuildConfig(project.getBuildConfigJson());

        // 工作区路径在 try 外声明，便于 finally/异常路径清理。
        final String workspacePath = workspaceBase + "/" + project.getCode() + "_" + buildId;

        String target = buildServer.getUsername() + "@" + buildServer.getHost() + ":" + buildServer.getSshPort();
        buildSupport.appendLog(task, BuildSupport.STAGE_CONNECT, TaskLogLevel.INFO, "开始连接构建机 " + target);
        if (!buildSupport.reportProgress(task, BuildSupport.STAGE_CONNECT, 2)) {
            return;
        }

        // 会话外提为 null，进入 try 后再赋值；catch/finally 都能访问，便于异常路径清理与关闭。
        SshSession session = null;
        try {
            session = buildSupport.openSession(buildServer);
            buildSupport.appendLog(task, BuildSupport.STAGE_CONNECT, TaskLogLevel.INFO, "SSH 连接与认证成功");
            buildSupport.reportProgress(task, BuildSupport.STAGE_CONNECT, BuildSupport.PROGRESS_CONNECTED);
            // 标记 build 进入构建中，与任务执行中状态对齐。
            buildService.startBuild(buildId);

            // 1) 准备工作区：每次全新创建，避免旧产物污染。
            buildSupport.prepareWorkspace(task, session, workspacePath);

            // 2) 拉取代码（含私有仓库鉴权）。
            String commit = buildSupport.cloneRepository(task, session, project, config, workspacePath);
            // 克隆后即回填实际提交 ID：即便后续构建/推送失败，也能在 build 记录上溯源本次尝试的 commit。
            // （原 cloneRepository 内部调用 buildService.captureCommit，重构后统一在此捕获，行为一致）
            buildService.captureCommit(buildId, commit);

            // 2.1) 定稿本次构建的发布版本号。镜像 tag、构建机产物持久目录、版本记录三处共用
            //      同一个字符串，所以在这里算一次往下传；中途重新生成会让三者对不上。
            BuildService.ReleaseVersion releaseVersion = buildService.resolveReleaseVersion(
                    project.getId(), project.getCode(), commit, build.getVersionSeq());
            buildSupport.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO, "本次构建版本号 " + releaseVersion.version());

            // 3) 按运行时与构建模式执行构建，并收集产物。
            List<BuildArtifact> artifacts = new ArrayList<>();
            String composeContent = null;
            String contextArtifactPath = null;
            // 不可变上下文：把本次构建的所有运行时输入打包，在编排器与各构建 Service 之间传递。
            BuildContext ctx = new BuildContext(task, session, project, config, workspacePath, commit,
                    releaseVersion.version(), artifacts, buildServer, artifactBase, runtimeType, buildMode,
                    build.getRebuildBaseImage());
            if (runtimeType == RuntimeType.DOCKER) {
                if (buildMode == BuildMode.JAR_PREBUILD) {
                    // B1：平台先出 jar，再用极简镜像打包，目标机只纯 run。
                    composeContent = jarPrebuildBuilder.build(ctx);
                    // B1 的目标机既无源码也无 maven，源码上下文对它毫无意义；改产「平铺热修包」
                    // （产物原名 jar + Dockerfile.hotfix + hotfix compose + 配置 + README），
                    // 随发布下发到目标机，保证平台/仓库都不可用时仍能就地 docker build 把服务救回来。
                    contextArtifactPath = buildSupport.captureHotfixKit(task, session, project, config, workspacePath,
                            artifactBase, releaseVersion.version());
                } else {
                    composeContent = dockerBuilder.build(ctx);
                    // COMPOSE + 指定 work_dir 形态要往目标机下发构建上下文，先把它打成 tarball 落构建机产物目录。
                    contextArtifactPath = buildSupport.captureBuildContext(task, session, buildServer, workspacePath,
                            artifactBase, project, config, releaseVersion.version());
                }
                // 配置转存：DOCKER 的产物是镜像，走不到 buildFileArtifact 那条「产物 + 配置一起落盘」的路径，
                // 但目标机上的 compose 一样会挂 ./config。这里单独补一次，保证发布端有配置可投。
                buildSupport.persistVersionConfig(task, session, project, config, workspacePath,
                        artifactBase, releaseVersion.version());
            } else {
                scriptBuildService.build(ctx);
            }

            // 4) 登记状态、产物与发布版本。
            ReleaseRecord release = assembleRelease(project, runtimeType, commit, releaseVersion, artifacts,
                    composeContent, contextArtifactPath);
            buildService.completeSuccess(buildId, commit, artifacts, release);
            buildSupport.reportProgress(task, BuildSupport.STAGE_FINISH, BuildSupport.PROGRESS_DONE);
            buildSupport.appendLog(task, BuildSupport.STAGE_FINISH, TaskLogLevel.INFO, "构建完成，已生成发布版本 " + release.getVersion());
            // 5) 按项目保留版本数回收超额历史版本：先删磁盘再置废弃，失败不影响本次构建结论。
            buildSupport.applyRetentionPolicy(task, project, buildServer, session);
            buildSupport.cleanupWorkspace(task, session, workspacePath);
        } catch (Exception e) {
            // 异常路径：先清理工作区，再把 build 置为失败，最后上抛由执行器判任务失败。
            if (session != null) {
                buildSupport.cleanupWorkspace(task, session, workspacePath);
            }
            if (StringUtils.hasText(buildId)) {
                buildService.completeFailure(buildId, buildSupport.safeMessage(e));
            }
            if (e instanceof DeployException) {
                throw e;
            }
            log.warn("构建任务执行异常，buildId={}", buildId, e);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "构建执行失败：" + buildSupport.safeMessage(e));
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
     * @param composeContent DOCKER 运行时项目自带的 compose 文件原文，可为 null
     * @return 待落库的发布版本记录
     */
    private ReleaseRecord assembleRelease(Project project, RuntimeType runtimeType, String commit,
                                         BuildService.ReleaseVersion releaseVersion, List<BuildArtifact> artifacts,
                                         String composeContent, String contextArtifactPath) {
        ReleaseRecord release = new ReleaseRecord();
        release.setProjectId(project.getId());
        release.setVersion(releaseVersion.version());
        release.setVersionSeq(releaseVersion.seq());
        release.setCommitId(commit);
        release.setRuntimeType(runtimeType.getCode());
        if (runtimeType == RuntimeType.DOCKER) {
            release.setComposeContent(composeContent);
            // 构建上下文 tarball 路径：COMPOSE + 指定 work_dir 时下发到目标机供上机重建；无则留空。
            release.setContextArtifactPath(contextArtifactPath);
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
                release.setArtifactPath(buildSupport.extractPath(a.getMetadataJson()));
                release.setSha256(a.getArtifactDigest());
            }
        }
        return release;
    }
}
