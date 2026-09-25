package com.lx.deploy.deployment.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.common.constants.DeploymentTargetStatus;
import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.constants.HealthStatus;
import com.lx.deploy.common.enums.project.DeployStrategy;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.mapper.DeploymentTargetMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.deployment.service.DeploymentService;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.mapper.ProjectEnvironmentTargetMapper;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Environment;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.project.model.ProjectEnvironmentTarget;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import com.lx.deploy.task.executor.TaskHandler;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.service.TaskLogService;
import com.lx.deploy.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署任务处理器。
 *
 * <p>这是「部署」类型异步任务（{@code TaskType.DEPLOY}）的真实执行者，由 {@link TaskExecutor} 调度。
 * 一条 deployment 记录对应一条 DEPLOY 任务，bizId 即 deployment.id；同一条 deployment
 * 在发布与回滚两个阶段共用一个处理器，靠主任务状态区分本次该发 new_version 还是 old_version。</p>
 *
 * <p>执行链路：读取部署与项目/环境/版本 → 按策略切分批次 → 逐目标
 * DOCKER（docker pull + run）/ JAR（SFTP 分发 + systemd 重启）/ STATIC（SFTP 分发 + Nginx reload）
 * → 健康检查（HTTP / TCP）→ 回调 DeploymentService 落状态。
 * 任一批次失败时：滚动策略中止并把剩余目标置为已跳过，全量策略继续跑完再汇总，
 * 保证「失败之后机器上是什么状态」永远可以从目标明细里看出来。
 * 汇总后只要有一个目标失败，部署记录与任务同时置为失败——两者结论必须一致，
 * 否则页面会出现「日志里有 ERROR、任务却是成功」的自相矛盾。</p>
 *
 * <p>互斥键为 {@code deploy:部署ID}：同一条部署的发布与回滚不会并发执行，
 * 不同服务器上的部署仍可并发（AGENTS.md §1）。</p>
 *
 * <p><b>回滚日志的口径</b>：回滚是「把线上退回到一个已知版本」的动作，日志必须能独立回答
 * 「什么时候开始、当前是什么版本、退到哪个版本、有没有成功、服务起没起来、什么时候结束」，
 * 因此开场写开始时间与参与目标数，每个目标写「当前版本 → 回滚到」，重启成功留一条
 * is-active 记录，收尾单独写一条「回滚成功/回滚失败 + 完成时间」。
 * 不能只靠目标行的零散 INFO 与执行器结尾那句「任务执行成功」——那句话不区分发布与回滚。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Slf4j
@Component
public class DeployTaskHandler implements TaskHandler {

    /** 任务类型编码，与 TaskType.DEPLOY 保持一致。 */
    private static final String TASK_TYPE = TaskType.DEPLOY.getCode();

    /** 互斥键前缀，保证同一条部署串行执行。 */
    private static final String LOCK_KEY_PREFIX = "deploy:";

    /** 应用在目标机部署根目录下的子目录名，平台约定，不单独提供配置项。 */
    private static final String APPS_SUBDIR = "apps";

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
     * <p>与构建机产物持久目录下的同名文件一起，构成「这份产物/这台机器是哪个版本」的依据。
     * 两边取值口径必须一致（见 {@code BuildTaskHandler} 的同名常量），
     * 且一律写平台原始版本号，不做任何美化——它要能被机器比对。</p>
     */
    private static final String VERSION_MARKER = "VERSION";

    /** 供目标机脚本读取的服务信息文件名，位于工作目录的 exec 子目录下。 */
    private static final String DEPLOY_CONF = "deploy.conf";

    /** 阶段名：准备。 */
    private static final String STAGE_PREPARE = "准备";
    /** 阶段名：分发。 */
    private static final String STAGE_DELIVER = "分发";
    /** 阶段名：启动。 */
    private static final String STAGE_START = "启动";
    /** 阶段名：健康检查。 */
    private static final String STAGE_HEALTH = "健康检查";
    /** 阶段名：收尾。 */
    private static final String STAGE_FINISH = "收尾";

    /** 准备完成后的进度百分比。 */
    private static final int PROGRESS_PREPARED = 5;
    /** 进度计算区间：批次执行占用 5 到 90。 */
    private static final int PROGRESS_RANGE_START = 5;
    /** 批次执行占用的进度区间上限。 */
    private static final int PROGRESS_RANGE_END = 90;
    /** 全部完成的进度百分比。 */
    private static final int PROGRESS_DONE = 100;

    /** 单条命令输出最多写入日志的行数，避免镜像层输出刷屏。 */
    private static final int MAX_OUTPUT_LINES = 30;

    /**
     * 回滚日志里的时间格式。
     *
     * <p>回滚是「把线上退回到一个已知版本」的动作，使用者需要凭日志回答
     * 「什么时候开始、什么时候结束、退到哪个版本」，因此时间必须写进正文，
     * 不能只依赖日志行的落库时间（页面按分钟展示时看不出先后差异）。</p>
     */
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 部署 Mapper：读取本次部署主记录。 */
    private final DeploymentMapper deploymentMapper;

    /** 部署目标 Mapper：读取待执行的目标列表。 */
    private final DeploymentTargetMapper deploymentTargetMapper;

    /** 部署服务：回调状态流转，处理器自身不直接写业务状态。 */
    private final DeploymentService deploymentService;

    /** 项目 Mapper：读取运行时与三段 JSON 配置。 */
    private final ProjectMapper projectMapper;

    /** 环境 Mapper：读取滚动批次大小。 */
    private final EnvironmentMapper environmentMapper;

    /** 项目环境目标 Mapper：读取目标差异化运行配置。 */
    private final ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper;

    /** 发布版本 Mapper：读取镜像标签、产物路径与构建机。 */
    private final ReleaseRecordMapper releaseRecordMapper;

    /** 服务器 Mapper：读取目标服务器与构建机连接配置。 */
    private final ServerMapper serverMapper;

    /** SSH 会话工厂：建立到构建机与目标机的已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /** 健康检查探测器：发布完成后探测服务是否可用。 */
    private final HealthChecker healthChecker;

    /** 任务服务：汇报执行阶段与进度。 */
    private final TaskService taskService;

    /** 任务日志服务：把执行过程写入 lds_task_log。 */
    private final TaskLogService taskLogService;

    /** JSON 解析器：解析与合并运行/健康检查配置。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建部署任务处理器。
     *
     * @param deploymentMapper              部署 Mapper
     * @param deploymentTargetMapper        部署目标 Mapper
     * @param deploymentService             部署服务
     * @param projectMapper                 项目 Mapper
     * @param environmentMapper             环境 Mapper
     * @param projectEnvironmentTargetMapper 项目环境目标 Mapper
     * @param releaseRecordMapper           发布版本 Mapper
     * @param serverMapper                  服务器 Mapper
     * @param sshSessionFactory             SSH 会话工厂
     * @param healthChecker                 健康检查探测器
     * @param taskService                   任务服务
     * @param taskLogService                任务日志服务
     */
    public DeployTaskHandler(DeploymentMapper deploymentMapper,
                             DeploymentTargetMapper deploymentTargetMapper,
                             DeploymentService deploymentService,
                             ProjectMapper projectMapper,
                             EnvironmentMapper environmentMapper,
                             ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper,
                             ReleaseRecordMapper releaseRecordMapper,
                             ServerMapper serverMapper,
                             SshSessionFactory sshSessionFactory,
                             HealthChecker healthChecker,
                             TaskService taskService,
                             TaskLogService taskLogService) {
        this.deploymentMapper = deploymentMapper;
        this.deploymentTargetMapper = deploymentTargetMapper;
        this.deploymentService = deploymentService;
        this.projectMapper = projectMapper;
        this.environmentMapper = environmentMapper;
        this.projectEnvironmentTargetMapper = projectEnvironmentTargetMapper;
        this.releaseRecordMapper = releaseRecordMapper;
        this.serverMapper = serverMapper;
        this.sshSessionFactory = sshSessionFactory;
        this.healthChecker = healthChecker;
        this.taskService = taskService;
        this.taskLogService = taskLogService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 该处理器负责的任务类型。
     *
     * @return DEPLOY
     */
    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    /**
     * 互斥键：同一条部署的发布与回滚串行执行。
     *
     * @param task 待执行任务，bizId 为 deployment 主键
     * @return 形如 {@code deploy:部署ID} 的互斥键
     */
    @Override
    public String lockKey(Task task) {
        return LOCK_KEY_PREFIX + task.getBizId();
    }

    /**
     * 执行部署任务。
     *
     * <p>全流程按批次推进，每个目标独立捕获异常：单台机器失败不影响其余目标的结论记录，
     * 但会影响策略是否继续（滚动中止、全量跑完）。最终按汇总结果收敛部署主记录状态，
     * 只要存在失败目标就把异常抛出，由执行器把任务一并判为失败。</p>
     *
     * @param task 已进入执行中状态的 DEPLOY 任务，bizId 为 deployment.id
     * @throws DeployException 部署数据异常、目标服务器不可用、分发或启动失败，
     *                         以及存在失败目标（此时部署已置为失败，异常仅用于让任务结论一致）时抛出
     */
    @Override
    public void execute(Task task) {
        String deploymentId = task.getBizId();
        if (!StringUtils.hasText(deploymentId)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "部署任务缺少关联的 deployment.id");
        }
        Deployment deployment = deploymentMapper.selectById(deploymentId);
        if (deployment == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署记录不存在或已被删除");
        }
        Project project = projectMapper.selectById(deployment.getProjectId());
        if (project == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署目标项目不存在或已被删除");
        }
        Environment environment = environmentMapper.selectById(deployment.getEnvironmentId());
        if (environment == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署目标环境不存在或已被删除");
        }
        // 回滚与主发布共用本处理器：主任务处于回滚中时，每个目标发的是它自己的 old_version。
        boolean rollingBack = deployment.getStatus() != null
                && deployment.getStatus() == DeploymentStatus.ROLLING_BACK;
        int targetTotal = (int) countTargets(deploymentId);

        // 只处理仍处于排队的目标：回滚时被排除的目标保持已跳过，不会重复处理。
        List<DeploymentTarget> targets = deploymentTargetMapper.selectList(
                new LambdaQueryWrapper<DeploymentTarget>()
                        .eq(DeploymentTarget::getDeploymentId, deploymentId)
                        .eq(DeploymentTarget::getStatus, DeploymentTargetStatus.QUEUED)
                        .orderByAsc(DeploymentTarget::getCreateTime));

        if (rollingBack) {
            // 回滚的开场必须交代清楚：什么时候开始、总共几台、这次真能退回去几台。
            // 「另外几台为什么不退」由 Service 在目标行上写明了原因，这里只报数量，避免日志重复长文案。
            appendLog(task, STAGE_PREPARE, TaskLogLevel.INFO,
                    "开始回滚部署 " + deploymentId + "，开始时间 " + nowText());
            appendLog(task, STAGE_PREPARE, TaskLogLevel.INFO,
                    "回滚策略 " + deployment.getStrategy()
                            + "，本次回滚 " + targets.size() + "/" + targetTotal + " 个目标"
                            + (targetTotal > targets.size()
                            ? "，另有 " + (targetTotal - targets.size()) + " 个目标无历史版本或本次未变更，保持原版本不变"
                            : ""));
        } else {
            appendLog(task, STAGE_PREPARE, TaskLogLevel.INFO,
                    "开始执行部署 " + deploymentId
                            + "，策略 " + deployment.getStrategy()
                            + "，目标数 " + targetTotal);
        }

        if (targets.isEmpty()) {
            appendLog(task, STAGE_FINISH, TaskLogLevel.INFO,
                    rollingBack ? "没有待回滚的目标，本次回滚无实际变更" : "没有待执行的目标，直接收敛本次部署");
            // 没有目标可发（例如回滚时全部被排除）：直接收敛为成功，没有失败可汇总。
            finishDeployment(deploymentId, rollingBack, null);
            return;
        }
        if (!reportProgress(task, STAGE_PREPARE, PROGRESS_PREPARED)) {
            return;
        }

        // 运行配置与健康检查配置在创建时已固化到项目上，此处按目标做差异覆盖。
        DeployConfig baseConfig = parseDeployConfig(project.getRuntimeConfigJson());
        HealthConfig healthConfig = parseHealthConfig(project.getHealthConfigJson());
        deploymentService.markDeploying(deploymentId);

        List<List<DeploymentTarget>> batches = buildBatches(targets, deployment.getStrategy(),
                environment.getBatchSize());
        int total = targets.size();
        int finished = 0;
        int failed = 0;
        boolean aborted = false;

        for (int batchIndex = 0; batchIndex < batches.size() && !aborted; batchIndex++) {
            List<DeploymentTarget> batch = batches.get(batchIndex);
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "开始第 " + (batchIndex + 1) + "/" + batches.size() + " 批，含 " + batch.size() + " 个目标");
            int failedInBatch = 0;
            for (DeploymentTarget target : batch) {
                boolean ok = deployTarget(task, deployment, project, target, baseConfig, healthConfig, rollingBack);
                finished++;
                if (!ok) {
                    failed++;
                    failedInBatch++;
                }
                int progress = PROGRESS_RANGE_START
                        + (PROGRESS_RANGE_END - PROGRESS_RANGE_START) * finished / total;
                if (!reportProgress(task, STAGE_DELIVER, progress)) {
                    // 任务已被取消：停止后续目标，已执行的部分保留现场由人工确认。
                    appendLog(task, STAGE_FINISH, TaskLogLevel.INFO, "任务已不在执行中，停止后续发布");
                    return;
                }
            }
            // 滚动策略：任一批次失败即中止，剩余目标置为已跳过（保持旧版本，回滚时也会排除它们）。
            if (failedInBatch > 0 && isRolling(deployment.getStrategy())) {
                aborted = true;
                skipRemaining(task, batches, batchIndex + 1);
            }
        }

        // 有目标失败时，部署记录与任务必须给出同一个结论。
        // 早先只把部署置为失败、处理器仍正常返回，于是任务管理里显示「成功」，
        // 任务日志最后一行却是「任务执行成功」，中间夹着目标级的 ERROR，自相矛盾。
        // 这里汇总失败原因后主动抛异常，由执行器统一把任务收敛为失败并写入原因。
        String failureSummary = failed > 0 ? buildFailureSummary(deploymentId, failed, total) : null;
        if (rollingBack) {
            // 回滚的收尾结论单独写一条：使用者翻日志时要能一眼看到「退到了哪个版本、服务起没起来、什么时候结束」，
            // 而不是自己去数目标行的结果。跳过数量在这里补报，因为开场时只知道「有几台不参与」。
            int skipped = targetTotal - total;
            if (failureSummary == null) {
                appendLog(task, STAGE_FINISH, TaskLogLevel.INFO,
                        "回滚成功：已成功回滚 " + total + " 个目标到历史版本，服务启动正常"
                                + (skipped > 0 ? "；另有 " + skipped + " 个目标保持原版本不变" : "")
                                + "，完成时间 " + nowText());
            } else {
                appendLog(task, STAGE_FINISH, TaskLogLevel.ERROR,
                        "回滚失败：" + failureSummary + "，完成时间 " + nowText() + "，请人工确认目标机当前版本");
            }
        }
        finishDeployment(deploymentId, rollingBack, failureSummary);
        if (failureSummary != null) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, failureSummary);
        }
        taskService.updateProgress(task.getId(), STAGE_FINISH, PROGRESS_DONE);
    }

    // ==================== 目标级执行 ====================

    /**
     * 在单个目标上完成一次发布（或回滚），并落库目标状态。
     *
     * <p>本方法不向外抛异常：单个目标的失败被收敛为目标状态与失败原因，
     * 由调用方决定是否中止后续批次。这样「哪台机器失败、为什么失败」始终有记录。</p>
     *
     * @param task          当前任务
     * @param deployment    部署主记录
     * @param project       项目
     * @param target        待执行目标
     * @param baseConfig    项目级运行配置
     * @param healthConfig  健康检查配置，为空表示不检查
     * @param rollingBack   本次是否为回滚
     * @return true 表示目标发布成功；false 表示失败（状态与原因已落库）
     */
    private boolean deployTarget(Task task, Deployment deployment, Project project, DeploymentTarget target,
                                 DeployConfig baseConfig, HealthConfig healthConfig, boolean rollingBack) {
        Server server = serverMapper.selectById(target.getServerId());
        if (server == null) {
            deploymentService.markTargetFailed(target.getId(), "目标服务器不存在或已被删除", HealthStatus.NOT_CHECKED);
            appendLog(task, STAGE_DELIVER, TaskLogLevel.ERROR, "目标服务器不存在，跳过该目标");
            return false;
        }
        if (server.getStatus() == null || server.getStatus() != EnableStatus.ENABLED) {
            deploymentService.markTargetFailed(target.getId(), "目标服务器已停用", HealthStatus.NOT_CHECKED);
            appendLog(task, STAGE_DELIVER, TaskLogLevel.ERROR,
                    "目标服务器 " + server.getServerName() + " 已停用，跳过该目标");
            return false;
        }
        // 目标差异化配置：只覆盖本次用到的字段，未覆盖部分沿用项目级配置。
        DeployConfig config = mergeTargetConfig(baseConfig, deployment, server.getId());
        RuntimeType runtimeType = resolveRuntimeType(project, target);
        Integer healthStatus = HealthStatus.NOT_CHECKED;

        deploymentService.markTargetRunning(target.getId());
        if (rollingBack) {
            // 回滚必须把「从什么版本退到什么版本」写全：new_version 是本次发布上线、现在正跑着的版本，
            // old_version 是本次要退回去的版本。只看「开始发布 vX」无法判断这是升级还是回退。
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "回滚目标 " + server.getServerName() + "(" + server.getHost() + ")"
                            + "：当前版本 " + displayVersion(target.getNewVersion())
                            + " → 回滚到 " + displayVersion(target.getOldVersion())
                            + "，开始时间 " + nowText());
        } else {
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "开始在 " + server.getServerName() + "(" + server.getHost() + ") 上发布 "
                            + displayVersion(target.getNewVersion()));
        }
        try {
            if (runtimeType == null) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目的运行时类型非法：" + project.getRuntimeType());
            }
            // 本次实际要发的版本：回滚时是 old_version，正常发布时是 new_version。
            ReleaseRecord versionRelease = resolveVersionRelease(project, deployment, target, rollingBack);
            switch (runtimeType) {
                case DOCKER -> deployDocker(task, server, project, config, versionRelease);
                case JAR -> deployJar(task, server, project, config, versionRelease, target, deployment);
                case STATIC -> deployStatic(task, server, project, config, versionRelease, deployment);
            }
            if (healthConfig != null) {
                HealthChecker.HealthCheckResult result = healthChecker.check(healthConfig, server.getHost());
                healthStatus = result.healthy() ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY;
                if (result.healthy()) {
                    appendLog(task, STAGE_HEALTH, TaskLogLevel.INFO, "健康检查通过（" + result.detail() + "）");
                } else {
                    throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                            "健康检查不通过（" + result.detail() + "）");
                }
            }
            deploymentService.markTargetSuccess(target.getId(), healthStatus);
            if (rollingBack) {
                // 「是否启动成功」是回滚结论的一部分：只有 systemd 重启并通过健康检查才会走到这里，
                // 把这两个事实写进同一行，使用者不必再去翻上面的启动日志确认。
                appendLog(task, STAGE_FINISH, TaskLogLevel.INFO,
                        "目标 " + server.getServerName() + " 回滚成功，服务启动正常（当前版本 "
                                + displayVersion(target.getOldVersion()) + "）");
            } else {
                appendLog(task, STAGE_FINISH, TaskLogLevel.INFO,
                        "目标 " + server.getServerName() + " 发布成功");
            }
            return true;
        } catch (Exception e) {
            // 失败原因不含凭据：所有远程输出在 SshCommandResult 中已做脱敏。
            deploymentService.markTargetFailed(target.getId(), safeMessage(e), healthStatus);
            appendLog(task, STAGE_FINISH, TaskLogLevel.ERROR,
                    rollingBack
                            ? "目标 " + server.getServerName() + " 回滚失败：" + safeMessage(e)
                            : "目标 " + server.getServerName() + " 发布失败：" + safeMessage(e));
            return false;
        }
    }

    /**
     * DOCKER 运行时发布：拉取镜像并以新容器启动。
     *
     * @param task     当前任务
     * @param server   目标服务器
     * @param project  项目（用于默认容器名）
     * @param config   运行配置（容器名、端口映射、附加参数）
     * @param release  本次要发布的版本
     * @throws DeployException 镜像标签缺失、拉取失败或启动失败时抛出
     */
    private void deployDocker(Task task, Server server, Project project, DeployConfig config, ReleaseRecord release) {
        String image = release.getImageTag();
        if (!StringUtils.hasText(image)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + release.getVersion() + " 没有镜像标签，无法按 DOCKER 运行时发布");
        }
        String containerName = StringUtils.hasText(config.getContainerName())
                ? config.getContainerName() : project.getCode();
        try (SshSession session = sshSessionFactory.open(server)) {
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "拉取镜像 " + image);
            // 拉取镜像可能上千 MB，按长命令超时等待，不能用秒级超时。
            SshCommandResult pull = session.execLong("docker pull " + image);
            if (!pull.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "docker pull 失败：" + firstLine(pull.stderr()));
            }
            writeOutput(task, STAGE_DELIVER, pull.stdout());
            // 先移除同名旧容器：容器名是发布幂等的关键，不删除会导致 run 直接失败。
            session.exec("docker rm -f " + containerName + " >/dev/null 2>&1 || true");
            String runCommand = "docker run -d --restart=always --name " + containerName
                    + portOptions(config) + extraArgs(config) + " " + image;
            appendLog(task, STAGE_START, TaskLogLevel.INFO, "启动容器 " + containerName);
            // 容器启动可能包含镜像解压与首次初始化（如 Spring Boot 解包），按长命令超时等待。
            SshCommandResult run = session.execLong(runCommand);
            if (!run.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "docker run 失败：" + firstLine(run.stderr()));
            }
            // 容器启动成功不代表进程还在：立刻确认一次运行状态，避免把「启动即退出」报成成功。
            SshCommandResult inspect = session.exec(
                    "docker inspect -f '{{.State.Running}}' " + containerName + " 2>/dev/null");
            if (!inspect.success() || !"true".equalsIgnoreCase(inspect.stdout().strip())) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "容器启动后未处于运行态，请检查应用启动日志（docker logs " + containerName + "）");
            }
        }
    }

    /**
     * JAR 运行时发布：从构建机下载产物 → 上传到目标机 → systemd 重启服务。
     *
     * @param task     当前任务
     * @param server   目标服务器
     * @param project  项目
     * @param config   运行配置（部署根目录、JVM 参数、服务名）
     * @param release  本次要发布的版本
     * @param target   当前目标（提供版本号）
     * @param deployment 当前部署（用于临时目录命名）
     * @throws DeployException 构建机缺失、产物下载/上传失败或 systemd 操作失败时抛出
     */
    private void deployJar(Task task, Server server, Project project, DeployConfig config,
                           ReleaseRecord release, DeploymentTarget target, Deployment deployment) {
        String artifactPath = release.getArtifactPath();
        if (!StringUtils.hasText(artifactPath)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + release.getVersion() + " 没有产物路径，无法按 JAR 运行时发布");
        }
        String artifactFileName = artifactPath.contains("/")
                ? artifactPath.substring(artifactPath.lastIndexOf('/') + 1) : artifactPath;
        // 固定工作目录：运行配置显式给出优先，未配置时回落到「部署根目录 / 项目编码」。
        // 与按版本建子目录的区别在于目录固定不变，发版是原地替换 jar。
        String workDir = normalize(config.getWorkDir(), deployBase(server) + "/" + project.getCode());
        String serviceName = StringUtils.hasText(config.getServiceName())
                ? config.getServiceName() : project.getCode();
        String configDirName = StringUtils.hasText(config.getConfigDir())
                ? config.getConfigDir() : DEFAULT_CONFIG_DIR;
        String configFileName = StringUtils.hasText(config.getConfigFile())
                ? config.getConfigFile() : DEFAULT_CONFIG_FILE;
        String profiles = StringUtils.hasText(config.getSpringProfilesActive())
                ? config.getSpringProfilesActive() : DEFAULT_PROFILES;

        List<Path> localFiles = new ArrayList<>();
        try {
            Path jarFile = downloadArtifact(task, release, artifactPath, artifactFileName, deployment);
            localFiles.add(jarFile);
            Map<String, Path> configFiles = downloadConfigFiles(task, release, deployment, artifactPath);
            localFiles.addAll(configFiles.values());

            try (SshSession session = sshSessionFactory.open(server)) {
                String javaBin = StringUtils.hasText(config.getJavaBin())
                        ? config.getJavaBin() : detectJavaBin(session);
                ensureInitialized(task, session, workDir, serviceName, artifactFileName,
                        javaBin, config.getJvmOptions(), profiles, configDirName, configFileName);
                uploadConfigFiles(task, session, workDir + "/" + configDirName, configFiles);
                // jar 名沿用工作目录里已有的名字：同一实例不该出现两个 jar，
                // 产物文件名带版本号时也不会在工作目录里越堆越多。
                String jarName = resolveJarName(session, workDir, artifactFileName);
                // 备份先于覆盖：备份下来的是「被换下去的那个包」，同时把它的版本标识一并存下，
                // 使每个备份都能对应到平台版本号，而不只是一个无法解读的时间戳。
                String backupPath = backupCurrentJar(task, session, workDir, jarName);
                String tmpName = jarName + ".uploading";
                appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "投放 " + jarName + " 到 " + workDir);
                session.sftpUpload(jarFile, workDir + "/" + tmpName);
                SshCommandResult move = session.exec(
                        "mv -f " + workDir + "/" + tmpName + " " + workDir + "/" + jarName);
                if (!move.success()) {
                    throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                            "替换 jar 失败：" + firstLine(move.stderr()));
                }
                // 校验放在重启之前：jar 已上盘、服务尚未重启，此刻发现损坏可以无损还原。
                verifyJarDigest(task, session, workDir, jarName, release, backupPath);
                // 标识与磁盘对齐后再重启：重启失败时磁盘上已经是新版本，
                // 标识若停留在旧版本会与磁盘错位，反而误导排查。
                writeVersionMarker(task, session, workDir, release);
                refreshDeployConfVersion(task, session, workDir, release.getVersion());
                restartService(task, session, serviceName);
            }
        } finally {
            for (Path file : localFiles) {
                deleteQuietly(file);
            }
        }
    }

    /**
     * 下载随版本转存的配置文件。
     *
     * <p>配置目录约定在产物同级的 config 子目录，由构建阶段按 build_config_json.config_paths 转存。
     * 没有配置文件时返回空集合，发布流程照常进行——配置缺失不该阻断发版，
     * 是否真的起得来由服务启动与健康检查判定。</p>
     *
     * @param task         当前任务
     * @param release      版本记录（提供构建机 ID）
     * @param deployment   当前部署（用于临时目录命名）
     * @param artifactPath 构建机上的产物绝对路径
     * @return 配置文件名到本地临时文件的映射
     * @throws DeployException 本地临时文件创建失败时抛出
     */
    private Map<String, Path> downloadConfigFiles(Task task, ReleaseRecord release,
                                                  Deployment deployment, String artifactPath) {
        Map<String, Path> result = new LinkedHashMap<>();
        if (!StringUtils.hasText(release.getBuildServerId()) || !artifactPath.contains("/")) {
            return result;
        }
        Server buildServer = serverMapper.selectById(release.getBuildServerId());
        if (buildServer == null) {
            return result;
        }
        String configDir = artifactPath.substring(0, artifactPath.lastIndexOf('/')) + "/" + DEFAULT_CONFIG_DIR;
        try (SshSession buildSession = sshSessionFactory.open(buildServer)) {
            SshCommandResult ls = buildSession.exec("ls -1 " + configDir + " 2>/dev/null");
            if (!ls.success() || !StringUtils.hasText(ls.stdout())) {
                return result;
            }
            for (String line : ls.stdout().split("\n")) {
                String fileName = line.strip();
                if (fileName.isEmpty()) {
                    continue;
                }
                Path local = Files.createTempFile("ld-deploy-" + deployment.getId() + "-", "-" + fileName);
                buildSession.sftpDownload(configDir + "/" + fileName, local);
                result.put(fileName, local);
                appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "已从构建机下载配置文件 " + fileName);
            }
        } catch (IOException e) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "创建平台本地临时文件失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 初始化 JAR 工作目录，并把内置脚本投放到目标机。
     *
     * <p>判定是否首次部署的依据是工作目录里是否已有 jar：没有就视为首次，由脚本建立目录结构、
     * 写入 systemd 单元（jar 与配置分离）、留下 deploy.conf 与 update/fallback 脚本。
     * 已经初始化过则直接返回，绝不重写 systemd 单元——运维手工调整过的启动参数必须被尊重。</p>
     *
     * <p>与是否首次无关，exec/ 下的三份内置脚本（init/update/fallback）每次发布都重新投放：
     * 它们是平台资产，版本必须随平台走，否则目标机会长期停留在一份旧脚本上，
     * 而运维应急发版/回滚恰恰依赖这几份脚本。</p>
     *
     * @param task       当前任务
     * @param session    已连接的 SSH 会话
     * @param workDir    目标机固定工作目录
     * @param serviceName systemd 服务名
     * @param jarName    jar 文件名
     * @param javaBin    java 可执行文件路径
     * @param jvmOptions JVM 参数，可为空
     * @param profiles   spring.profiles.active
     * @param configDir  配置目录名
     * @param configFile 配置文件名
     * @throws DeployException 目标机缺少 python3 或初始化脚本执行失败时抛出
     */
    private void ensureInitialized(Task task, SshSession session, String workDir, String serviceName,
                                   String jarName, String javaBin, String jvmOptions,
                                   String profiles, String configDir, String configFile) {
        String execDir = workDir + "/exec";
        // 内置脚本每次发布都重新投放：脚本是平台资产，版本必须随平台走。
        // 只在首次部署时投放会让目标机长期停留在一份旧脚本上，
        // 而运维应急时正是靠 exec 下的这几份脚本手工发版/回滚。
        SshCommandResult mkdir = session.exec("mkdir -p " + execDir);
        if (!mkdir.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "创建脚本目录失败：" + firstLine(mkdir.stderr()));
        }
        uploadScript(session, execDir, SCRIPT_INIT);
        uploadScript(session, execDir, SCRIPT_UPDATE);
        uploadScript(session, execDir, SCRIPT_FALLBACK);

        SshCommandResult probe = session.exec(
                "test -d " + workDir + " && ls -1 " + workDir + "/*.jar >/dev/null 2>&1 && echo yes || echo no");
        if (probe.success() && "yes".equals(probe.stdout().strip())) {
            appendLog(task, STAGE_PREPARE, TaskLogLevel.INFO, "工作目录已初始化，跳过：" + workDir);
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
        appendLog(task, STAGE_PREPARE, TaskLogLevel.INFO,
                "目标机 python3：" + firstLine(version.stdout()));
        appendLog(task, STAGE_PREPARE, TaskLogLevel.INFO, "首次部署，初始化工作目录 " + workDir);
        String command = "python3 " + execDir + "/" + SCRIPT_INIT
                + " --work-dir " + shellQuote(workDir)
                + " --jar-name " + shellQuote(jarName)
                + " --service-name " + shellQuote(serviceName)
                + " --java-bin " + shellQuote(javaBin)
                + " --jvm-opts " + shellQuote(jvmOptions == null ? "" : jvmOptions)
                + " --profiles " + shellQuote(profiles)
                + " --config-dir " + shellQuote(configDir)
                + " --config-file " + shellQuote(configFile);
        SshCommandResult result = session.exec(command);
        writeOutput(task, STAGE_PREPARE, result.stdout());
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "工作目录初始化失败：" + firstLine(result.stderr()));
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
     * @throws DeployException 脚本缺失或读取失败时抛出
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

    /**
     * 投放配置文件到工作目录的 config 子目录。
     *
     * <p>目标机已存在同名配置时跳过，不覆盖：配置与 jar 分离的意义就在于配置由运维自己维护，
     * 平台不该在每次发版时把仓库里的配置推到生产环境覆盖现场改动。</p>
     *
     * @param task      当前任务
     * @param session   已连接的 SSH 会话
     * @param targetDir 目标机配置目录
     * @param files     配置文件名到本地临时文件的映射
     * @throws DeployException 上传失败时抛出
     */
    private void uploadConfigFiles(Task task, SshSession session, String targetDir, Map<String, Path> files) {
        if (files.isEmpty()) {
            return;
        }
        SshCommandResult mkdir = session.exec("mkdir -p " + targetDir);
        if (!mkdir.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "创建配置目录失败：" + firstLine(mkdir.stderr()));
        }
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            String target = targetDir + "/" + entry.getKey();
            SshCommandResult exists = session.exec("test -f " + target + " && echo yes || echo no");
            if (exists.success() && "yes".equals(exists.stdout().strip())) {
                appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                        "目标机已存在 " + entry.getKey() + "，本次不覆盖");
                continue;
            }
            session.sftpUpload(entry.getValue(), target);
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "已投放配置文件 " + target);
        }
    }

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
            String existing = ls.stdout().strip();
            return existing.contains("/") ? existing.substring(existing.lastIndexOf('/') + 1) : existing;
        }
        return artifactFileName;
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
     * 指向的是替换者而不是包里的版本——{@code auth-tool_bak_20260925203000v1.jar} 装的是
     * 「20:30 那次发布换下去的那个包」。版本段取目标机上的 {@code VERSION}（磁盘事实），
     * 不取平台库里的快照：库快照在手工换包之后会失真。此前把 VERSION 复制成
     * {@code {备份文件名}.version}，信息虽等价，但 {@code ls} 看不见、还要多养一个文件，
     * 直接内嵌就不必再 {@code cat} 或 {@code sha256sum} 才能知道是哪个版本。</p>
     *
     * <p>取不到版本时名字退化为 {@code {jar名}_bak_{时间戳}.jar}，<b>不猜版本</b>——记错版本比不记版本更危险。
     * 同一秒内同版本重名（理论上仅并发部署）时追加 {@code _2}、{@code _3}，
     * 该序号只是防撞名，与版本无关。</p>
     *
     * @param task    当前任务
     * @param session 已连接的 SSH 会话
     * @param workDir 工作目录
     * @param jarName jar 文件名
     * @return 备份文件的绝对路径；工作目录此前没有 jar（首次投放）时返回 null
     * @throws DeployException 备份失败时抛出
     */
    private String backupCurrentJar(Task task, SshSession session, String workDir, String jarName) {
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
                    "备份现有 jar 失败：" + firstLine(result.stderr()));
        }
        if ("none".equals(backupPath)) {
            // 首次投放：工作目录里还没有 jar，没有可备份的内容。
            return null;
        }
        appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                "已备份现有 jar 到 exec/bak（" + backupPath.substring(backupPath.lastIndexOf('/') + 1) + "）");
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
     * @param task       当前任务
     * @param session    已连接的 SSH 会话
     * @param workDir    工作目录
     * @param jarName    jar 文件名
     * @param release    本次发布的版本记录
     * @param backupPath 本次发布前的备份路径，校验失败时用于还原；可为 null
     * @throws DeployException 摘要不一致时抛出，抛出前已还原发布前的 jar
     */
    private void verifyJarDigest(Task task, SshSession session, String workDir, String jarName,
                                 ReleaseRecord release, String backupPath) {
        String expected = release.getSha256();
        if (!StringUtils.hasText(expected)) {
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "版本 " + release.getVersion() + " 未登记产物摘要，跳过完整性校验");
            return;
        }
        SshCommandResult result = session.exec(
                "sha256sum " + workDir + "/" + jarName + " 2>/dev/null | awk '{print $1}'");
        String actual = result.success() ? result.stdout().strip() : "";
        if (!StringUtils.hasText(actual)) {
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "目标机无法计算产物摘要（可能缺少 sha256sum），跳过完整性校验");
            return;
        }
        if (!expected.equalsIgnoreCase(actual)) {
            // 先还原再抛出：磁盘上此刻是损坏的 jar，不还原会让机器停在
            // 「服务跑着旧版本、磁盘放着坏包」的错位状态——下次重启必然起不来，
            // 而失败原因只写着完整性校验失败，很难想到磁盘上留着坏包。
            restorePreviousJar(task, session, workDir, jarName, backupPath);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "产物完整性校验失败：期望 SHA256 " + expected + "，目标机实际 " + actual
                            + "，传输可能已损坏");
        }
        appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "产物完整性校验通过（SHA256 " + actual + "）");
    }

    /**
     * 用备份还原工作目录的 jar，用于产物校验失败时回到发布前状态。
     *
     * <p>还原失败只记日志、不抛出：调用方紧接着要抛的是「完整性校验失败」，
     * 那才是根因；用还原异常覆盖它会让使用者看到的是结果而不是原因。</p>
     *
     * @param task       当前任务
     * @param session    已连接的 SSH 会话
     * @param workDir    工作目录
     * @param jarName    jar 文件名
     * @param backupPath 备份文件绝对路径，为空表示无可还原的备份
     */
    private void restorePreviousJar(Task task, SshSession session, String workDir,
                                    String jarName, String backupPath) {
        if (!StringUtils.hasText(backupPath)) {
            appendLog(task, STAGE_DELIVER, TaskLogLevel.ERROR,
                    "产物完整性校验失败且无可用备份，工作目录中的 " + jarName + " 已损坏，请手工从平台重新发布");
            return;
        }
        SshCommandResult result = session.exec("cp -p " + backupPath + " " + workDir + "/" + jarName);
        if (result.success()) {
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "已还原发布前的 " + jarName);
        } else {
            appendLog(task, STAGE_DELIVER, TaskLogLevel.ERROR,
                    "还原发布前的 " + jarName + " 失败，请手工从 exec/bak 恢复：" + firstLine(result.stderr()));
        }
    }

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
     * @param task    当前任务
     * @param session 已连接的 SSH 会话
     * @param workDir 工作目录
     * @param release 本次实际发布的版本记录（回滚时是 old_version 对应的记录）
     */
    private void writeVersionMarker(Task task, SshSession session, String workDir, ReleaseRecord release) {
        String content = "version=" + release.getVersion() + "\n"
                + "commit=" + (StringUtils.hasText(release.getCommitId()) ? release.getCommitId() : "unknown") + "\n"
                + "runtime=" + release.getRuntimeType() + "\n"
                + "deployed_at=" + nowText() + "\n";
        String command = "printf '%s' " + shellQuote(content)
                + " > " + workDir + "/" + VERSION_MARKER;
        SshCommandResult result = session.exec(command);
        if (!result.success()) {
            // 标识是辅助信息，写不进去不影响本次发布是否成功，但必须留痕。
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "写入 " + VERSION_MARKER + " 失败（不影响发布结果）：" + firstLine(result.stderr()));
            return;
        }
        appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                "已登记目标机当前版本 " + release.getVersion());
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
     * @param task    当前任务
     * @param session 已连接的 SSH 会话
     * @param workDir 工作目录
     * @param version 本次发布的版本号
     */
    private void refreshDeployConfVersion(Task task, SshSession session, String workDir, String version) {
        String conf = workDir + "/exec/" + DEPLOY_CONF;
        String command = "if [ -f " + conf + " ]; then "
                + "grep -v '^version=' " + conf + " > " + conf + ".tmp && "
                + "printf 'version=%s\\n' " + shellQuote(version) + " >> " + conf + ".tmp && "
                + "mv -f " + conf + ".tmp " + conf + "; fi";
        SshCommandResult result = session.exec(command);
        if (!result.success()) {
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "刷新 " + DEPLOY_CONF + " 的版本信息失败（不影响发布结果）：" + firstLine(result.stderr()));
            return;
        }
        appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                "已同步 " + DEPLOY_CONF + " 版本信息为 " + version);
    }

    /**
     * 重启 systemd 服务并确认处于 active 状态。
     *
     * @param task        当前任务
     * @param session     已连接的 SSH 会话
     * @param serviceName systemd 服务名
     * @throws DeployException 重启失败或重启后未处于 active 时抛出
     */
    private void restartService(Task task, SshSession session, String serviceName) {
        appendLog(task, STAGE_START, TaskLogLevel.INFO, "重启 systemd 服务 " + serviceName);
        // 重启是同步等待服务起停的动作，应用初始化慢时可能超过秒级超时，按长命令超时等待。
        SshCommandResult restart = session.execLong("systemctl restart " + serviceName);
        if (!restart.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "systemctl 重启失败：" + firstLine(restart.stderr()));
        }
        SshCommandResult active = session.exec("systemctl is-active " + serviceName);
        if (!active.success() || !"active".equalsIgnoreCase(active.stdout().strip())) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "服务重启后未处于 active 状态，请检查 journalctl -u " + serviceName);
        }
        // 重启成功与否原本只在失败时才有痕迹，成功是「静默通过」的。
        // 回滚场景要明确回答「服务有没有起来」，因此成功也必须留一条可核对的记录。
        appendLog(task, STAGE_START, TaskLogLevel.INFO,
                "服务 " + serviceName + " 启动成功（systemctl is-active = active）");
    }

    /**
     * 把参数转成 shell 单引号字面量，避免空格与引号破坏命令行。
     *
     * @param value 原始值，允许为空
     * @return 可直接拼进 shell 命令的引号包裹值
     */
    private String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * STATIC 运行时发布：从构建机下载产物（目录先打包）→ 上传到目标机 → 解压到 Nginx 根目录并 reload。
     *
     * @param task        当前任务
     * @param server      目标服务器
     * @param project     项目
     * @param config      运行配置（静态根目录）
     * @param release     本次要发布的版本
     * @param deployment  当前部署（用于临时文件命名）
     * @throws DeployException 产物下载/上传失败、解压失败或 Nginx reload 失败时抛出
     */
    private void deployStatic(Task task, Server server, Project project, DeployConfig config,
                              ReleaseRecord release, Deployment deployment) {
        String artifactPath = release.getArtifactPath();
        if (!StringUtils.hasText(artifactPath)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + release.getVersion() + " 没有产物路径，无法按 STATIC 运行时发布");
        }
        String staticRoot = StringUtils.hasText(config.getStaticRoot())
                ? config.getStaticRoot() : "/usr/share/nginx/html/" + project.getCode();
        Path localFile = null;
        try {
            // 目录型产物（前端 dist）先在构建机上打包，SFTP 只传单个文件，避免逐文件传输的握手开销。
            String remotePackage = prepareStaticPackage(task, release, artifactPath, deployment);
            String fileName = remotePackage.contains("/")
                    ? remotePackage.substring(remotePackage.lastIndexOf('/') + 1) : remotePackage;
            localFile = downloadArtifact(task, release, remotePackage, fileName, deployment);
            try (SshSession session = sshSessionFactory.open(server)) {
                String remoteTmp = "/tmp/" + fileName;
                session.exec("mkdir -p " + staticRoot);
                appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "上传静态产物到 " + remoteTmp);
                session.sftpUpload(localFile, remoteTmp);
                String extractCommand = isArchive(fileName)
                        ? "tar -xzf " + remoteTmp + " -C " + staticRoot
                        : "cp " + remoteTmp + " " + staticRoot + "/";
                SshCommandResult extract = session.exec(extractCommand + " && rm -f " + remoteTmp);
                if (!extract.success()) {
                    throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                            "静态产物释放失败：" + firstLine(extract.stderr()));
                }
                // Nginx 只在存在时 reload：同一次发布可能部署到尚未安装 Nginx 的机器上。
                SshCommandResult hasNginx = session.exec("command -v nginx >/dev/null 2>&1 && echo yes || echo no");
                if (hasNginx.success() && "yes".equalsIgnoreCase(hasNginx.stdout().strip())) {
                    SshCommandResult reload = session.exec("nginx -t && nginx -s reload");
                    if (!reload.success()) {
                        throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                                "Nginx reload 失败：" + firstLine(reload.stderr()));
                    }
                    appendLog(task, STAGE_START, TaskLogLevel.INFO, "Nginx 已 reload，静态目录 " + staticRoot);
                } else {
                    appendLog(task, STAGE_START, TaskLogLevel.INFO,
                            "目标机未检测到 nginx，已释放静态产物但未 reload：" + staticRoot);
                }
            }
        } finally {
            deleteQuietly(localFile);
        }
    }

    // ==================== 产物分发辅助 ====================

    /**
     * 从构建机下载产物到平台本地临时文件。
     *
     * <p>采用「构建机 → 平台 → 目标机」中转而不是服务器间直传：
     * 目标机不需要持有构建机的免密通道，凭据与网络策略都收敛在平台侧。</p>
     *
     * @param task         当前任务
     * @param release      版本记录（提供构建机 ID）
     * @param remotePath   构建机上的产物绝对路径
     * @param fileName     产物文件名
     * @param deployment   当前部署（用于临时目录命名）
     * @return 平台本地的临时文件
     * @throws DeployException 版本缺少构建机、下载失败时抛出
     */
    private Path downloadArtifact(Task task, ReleaseRecord release, String remotePath,
                                  String fileName, Deployment deployment) {
        if (!StringUtils.hasText(release.getBuildServerId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + release.getVersion() + " 未记录构建机（多为历史版本），请重新构建后再发布");
        }
        Server buildServer = serverMapper.selectById(release.getBuildServerId());
        if (buildServer == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "版本记录的构建机不存在，无法下载产物");
        }
        try (SshSession buildSession = sshSessionFactory.open(buildServer)) {
            Path localFile = Files.createTempFile("ld-deploy-" + deployment.getId() + "-", "-" + fileName);
            long size = buildSession.sftpDownload(remotePath, localFile);
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "已从构建机下载产物 " + remotePath + "（" + size + " 字节）");
            return localFile;
        } catch (IOException e) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "创建平台本地临时文件失败：" + e.getMessage());
        }
    }

    /**
     * 在构建机上把静态产物准备成单个可传输文件。
     *
     * @param task        当前任务
     * @param release     版本记录（提供构建机 ID）
     * @param artifactPath 构建机上的产物路径，可能是目录也可能是文件
     * @param deployment  当前部署（用于打包文件名）
     * @return 构建机上待下载的绝对路径
     * @throws DeployException 构建机缺失或打包失败时抛出
     */
    private String prepareStaticPackage(Task task, ReleaseRecord release, String artifactPath, Deployment deployment) {
        if (isArchive(artifactPath)) {
            // 本身就是归档文件，直接下载即可。
            return artifactPath;
        }
        if (!StringUtils.hasText(release.getBuildServerId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + release.getVersion() + " 未记录构建机，无法打包静态产物");
        }
        Server buildServer = serverMapper.selectById(release.getBuildServerId());
        if (buildServer == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "版本记录的构建机不存在");
        }
        try (SshSession buildSession = sshSessionFactory.open(buildServer)) {
            // 目录与单文件都打成一个 tar.gz：统一后处理逻辑，目标机侧不必再区分。
            String packagePath = "/tmp/ld-static-" + deployment.getId() + ".tar.gz";
            String command = "test -d " + artifactPath
                    + " && tar -czf " + packagePath + " -C " + artifactPath + " ."
                    + " || tar -czf " + packagePath + " -C $(dirname " + artifactPath + ") $(basename " + artifactPath + ")";
            SshCommandResult result = buildSession.exec(command);
            if (!result.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "静态产物打包失败：" + firstLine(result.stderr()));
            }
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "静态产物已打包为 " + packagePath);
            return packagePath;
        }
    }

    // ==================== 批次编排 ====================

    /**
     * 按策略把目标切分为批次。
     *
     * <p>单目标与全量都是「一批」：单目标本来就只有一个目标，全量的语义是「不做批次中止」，
     * 因此失败后仍要跑完剩余目标；滚动按环境配置的批次大小切分，任一批失败即中止后续批次。</p>
     *
     * @param targets   待执行目标，已按发布顺序升序
     * @param strategy  部署策略编码
     * @param batchSize 滚动批次大小，允许为空
     * @return 批次列表，顺序与目标顺序一致
     */
    private List<List<DeploymentTarget>> buildBatches(List<DeploymentTarget> targets, String strategy, Integer batchSize) {
        if (!isRolling(strategy)) {
            return List.of(targets);
        }
        int size = batchSize == null || batchSize < 1 ? 1 : batchSize;
        List<List<DeploymentTarget>> batches = new ArrayList<>();
        for (int index = 0; index < targets.size(); index += size) {
            batches.add(new ArrayList<>(targets.subList(index, Math.min(index + size, targets.size()))));
        }
        return batches;
    }

    /**
     * 把中止后尚未执行的目标置为已跳过。
     *
     * @param task        当前任务
     * @param batches     全部批次
     * @param fromIndex   从哪个批次开始被跳过
     */
    private void skipRemaining(Task task, List<List<DeploymentTarget>> batches, int fromIndex) {
        for (int index = fromIndex; index < batches.size(); index++) {
            for (DeploymentTarget target : batches.get(index)) {
                deploymentService.skipTarget(target.getId(), "滚动发布前序批次失败，本目标未执行");
                appendLog(task, STAGE_FINISH, TaskLogLevel.INFO, "目标已跳过（前序批次失败）");
            }
        }
    }

    /**
     * 汇总本次执行结果并收敛部署主记录状态。
     *
     * @param deploymentId   部署主键
     * @param rollingBack    本次是否为回滚
     * @param failureSummary 失败原因汇总；为空表示没有失败目标，收敛为成功
     */
    private void finishDeployment(String deploymentId, boolean rollingBack, String failureSummary) {
        if (failureSummary != null) {
            if (rollingBack) {
                deploymentService.markRollbackFailed(deploymentId, failureSummary);
            } else {
                deploymentService.markFailed(deploymentId, failureSummary);
            }
            return;
        }
        if (rollingBack) {
            deploymentService.markRolledBack(deploymentId);
        } else {
            deploymentService.markSuccess(deploymentId);
        }
    }

    /**
     * 汇总失败目标，拼出部署记录与任务共用的失败原因。
     *
     * <p>失败原因以目标明细为准：{@code deployment_target.error_message} 是唯一事实来源，
     * 这里只取第一条失败目标带在结论里。任务列表的失败原因是一行文本，放不下完整清单，
     * 完整清单仍由部署记录的目标明细承载。</p>
     *
     * @param deploymentId 部署主键
     * @param failed       失败目标数
     * @param total        本次参与执行的目标数
     * @return 形如「共 2 个目标，其中 1 个失败：阿里云轻量服务器 → 健康检查不通过」的文本
     */
    private String buildFailureSummary(String deploymentId, int failed, int total) {
        String summary = "共 " + total + " 个目标，其中 " + failed + " 个失败";
        DeploymentTarget first = deploymentTargetMapper.selectOne(
                new LambdaQueryWrapper<DeploymentTarget>()
                        .eq(DeploymentTarget::getDeploymentId, deploymentId)
                        .eq(DeploymentTarget::getStatus, DeploymentTargetStatus.FAILED)
                        .orderByAsc(DeploymentTarget::getCreateTime)
                        .last("LIMIT 1"));
        if (first == null) {
            return summary;
        }
        Server server = serverMapper.selectById(first.getServerId());
        String name = server == null ? first.getServerId() : server.getServerName();
        String cause = StringUtils.hasText(first.getErrorMessage()) ? first.getErrorMessage() : "原因未记录";
        return summary + "：" + name + " → " + cause;
    }

    // ==================== 配置解析 ====================

    /**
     * 解析项目级运行配置。
     *
     * @param json 项目 runtime_config_json，允许为空
     * @return 运行配置；JSON 为空时返回带默认值的配置对象
     * @throws DeployException JSON 非法时抛出
     */
    private DeployConfig parseDeployConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return new DeployConfig();
        }
        try {
            return objectMapper.readValue(json, DeployConfig.class);
        } catch (Exception e) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "runtime_config_json 不是合法 JSON：" + e.getMessage());
        }
    }

    /**
     * 解析健康检查配置。
     *
     * @param json 项目 health_config_json，允许为空（表示不做健康检查）
     * @return 健康检查配置；不配置时返回 null
     * @throws DeployException JSON 非法时抛出
     */
    private HealthConfig parseHealthConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, HealthConfig.class);
        } catch (Exception e) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "health_config_json 不是合法 JSON：" + e.getMessage());
        }
    }

    /**
     * 用部署目标的差异化配置覆盖项目级配置。
     *
     * <p>覆盖以 JSON 键为单位：目标只需写差异字段，未写的字段沿用项目配置。
     * 没有绑定差异化配置时直接返回项目配置，不做多余的解析。</p>
     *
     * @param baseConfig  项目级运行配置
     * @param deployment  当前部署（提供项目与环境）
     * @param serverId    目标服务器 ID
     * @return 合并后的运行配置
     * @throws DeployException 目标配置 JSON 非法时抛出
     */
    private DeployConfig mergeTargetConfig(DeployConfig baseConfig, Deployment deployment, String serverId) {
        ProjectEnvironmentTarget binding = projectEnvironmentTargetMapper.selectOne(
                new LambdaQueryWrapper<ProjectEnvironmentTarget>()
                        .eq(ProjectEnvironmentTarget::getProjectId, deployment.getProjectId())
                        .eq(ProjectEnvironmentTarget::getEnvironmentId, deployment.getEnvironmentId())
                        .eq(ProjectEnvironmentTarget::getServerId, serverId)
                        .last("LIMIT 1"));
        if (binding == null || !StringUtils.hasText(binding.getRuntimeConfigJson())) {
            return baseConfig;
        }
        try {
            ObjectNode merged = (ObjectNode) objectMapper.readTree(
                    objectMapper.writeValueAsString(baseConfig));
            JsonNode override = objectMapper.readTree(binding.getRuntimeConfigJson());
            if (override.isObject()) {
                merged.setAll((ObjectNode) override);
            }
            return objectMapper.treeToValue(merged, DeployConfig.class);
        } catch (Exception e) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "部署目标的 runtime_config_json 合并失败：" + e.getMessage());
        }
    }

    /**
     * 解析本次实际使用的运行时类型。
     *
     * @param project 项目
     * @param target  目标（预留：后续可按目标覆盖运行时）
     * @return 运行时类型；编码非法时返回 null
     */
    private RuntimeType resolveRuntimeType(Project project, DeploymentTarget target) {
        return RuntimeType.fromCode(project.getRuntimeType());
    }

    /**
     * 解析某个目标本次要发布的版本记录。
     *
     * <p>正常发布直接使用部署绑定的版本；回滚时按目标的 old_version 反查对应版本记录，
     * 拿到该版本的镜像标签或产物路径，否则无法知道「旧版本到底是什么」。</p>
     *
     * @param project     项目
     * @param deployment  部署主记录
     * @param target      当前目标
     * @param rollingBack 本次是否为回滚
     * @return 本次要发布的版本记录
     * @throws DeployException 版本记录缺失或历史版本查不到时抛出
     */
    private ReleaseRecord resolveVersionRelease(Project project, Deployment deployment,
                                                DeploymentTarget target, boolean rollingBack) {
        if (!rollingBack) {
            ReleaseRecord release = releaseRecordMapper.selectById(deployment.getReleaseId());
            if (release == null) {
                throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署绑定的发布版本不存在");
            }
            return release;
        }
        String oldVersion = target.getOldVersion();
        if (!StringUtils.hasText(oldVersion)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "该目标没有历史版本，无法回滚");
        }
        ReleaseRecord old = releaseRecordMapper.selectOne(new LambdaQueryWrapper<ReleaseRecord>()
                .eq(ReleaseRecord::getProjectId, project.getId())
                .eq(ReleaseRecord::getVersion, oldVersion)
                .orderByDesc(ReleaseRecord::getCreateTime)
                .last("LIMIT 1"));
        if (old == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND,
                    "找不到历史版本 " + oldVersion + " 的发布记录，无法回滚");
        }
        return old;
    }

    // ==================== 命令拼装与远程写入 ====================

    /**
     * 拼装 docker run 的端口映射参数。
     *
     * @param config 运行配置
     * @return 形如 {@code " -p 8080:8080 -p 9090:9090"} 的参数片段；未配置时返回空串
     */
    private String portOptions(DeployConfig config) {
        if (!StringUtils.hasText(config.getPortMappings())) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String mapping : config.getPortMappings().split(",")) {
            String trimmed = mapping.trim();
            if (!trimmed.isEmpty()) {
                builder.append(" -p ").append(trimmed);
            }
        }
        return builder.toString();
    }

    /**
     * 拼装附加 docker run 参数。
     *
     * @param config 运行配置
     * @return 附加参数片段；未配置时返回空串
     */
    private String extraArgs(DeployConfig config) {
        return StringUtils.hasText(config.getExtraDockerArgs()) ? " " + config.getExtraDockerArgs().trim() : "";
    }

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
                    "写入远程文件失败（" + path + "）：" + firstLine(result.stderr()));
        }
    }

    // ==================== 日志与字符串辅助 ====================

    /**
     * 统计某个部署的目标总数。
     *
     * @param deploymentId 部署主键
     * @return 目标数量
     */
    private long countTargets(String deploymentId) {
        return deploymentTargetMapper.selectCount(new LambdaQueryWrapper<DeploymentTarget>()
                .eq(DeploymentTarget::getDeploymentId, deploymentId));
    }

    /**
     * 汇报任务阶段与进度；任务已不在执行中时返回 false。
     *
     * @param task     当前任务
     * @param stage    阶段名
     * @param progress 进度百分比
     * @return true 表示仍处于执行中，可继续
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
     * 写入一条任务日志。
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
            log.warn("写入部署日志失败，taskId={}，原因：{}", task.getId(), e.getMessage());
        }
    }

    /**
     * 把命令输出按行写入任务日志。
     *
     * @param task   当前任务
     * @param stage  阶段名
     * @param output 命令标准输出
     */
    private void writeOutput(Task task, String stage, String output) {
        if (!StringUtils.hasText(output)) {
            return;
        }
        String[] lines = output.strip().split("\\R");
        for (int i = 0; i < lines.length && i < MAX_OUTPUT_LINES; i++) {
            appendLog(task, stage, TaskLogLevel.INFO, lines[i]);
        }
    }

    /**
     * 判断路径是否为归档文件。
     *
     * @param path 文件或目录路径
     * @return true 表示 tar.gz / tgz 归档
     */
    private boolean isArchive(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
    }

    /**
     * 推导应用在目标机上的部署根目录。
     *
     * <p>默认落在「目标机部署根目录」的 apps 子目录下：部署根目录由服务器页面按机器配置，
     * apps 是平台约定的子目录名，两者拼出的绝对路径不再有写死在代码里的部分。
     * 服务器档案未配置部署根目录时直接报错——代码不持有兜底路径，
     * 与其用一个大概对的目录把应用发到意外位置，不如让运维显式补齐配置。</p>
     *
     * @param server 目标服务器
     * @return 应用部署根目录绝对路径
     * @throws DeployException 目标服务器未配置部署根目录时抛出
     */
    private String deployBase(Server server) {
        String base = server.getAgentBaseDir();
        if (!StringUtils.hasText(base)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "目标服务器未配置部署根目录，请在服务器页面补充该目录后重新发布");
        }
        return base.strip() + "/" + APPS_SUBDIR;
    }

    /**
     * 规范化目录路径：去掉首尾空白与结尾斜杠，避免拼出 // 形式的目录。
     *
     * <p>空值时回落到调用方给出的默认路径，自己不定义任何绝对路径。</p>
     *
     * @param path     原始路径，允许为空
     * @param fallback 路径为空时使用的默认路径
     * @return 规范化后的路径
     */
    private String normalize(String path, String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
        }
        String trimmed = path.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * 判断是否为滚动策略。
     *
     * @param strategy 部署策略编码
     * @return true 表示 ROLLING
     */
    private boolean isRolling(String strategy) {
        return DeployStrategy.ROLLING.getCode().equals(strategy);
    }

    /**
     * 把异常转为安全描述（截断长度，避免超长文本入库失败）。
     *
     * @param e 原始异常
     * @return 安全描述
     */
    private String safeMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 2000 ? message.substring(0, 2000) + "..." : message;
    }

    /**
     * 取命令输出第一行（用于错误摘要）。
     *
     * @param text 原始输出
     * @return 第一行；为空时返回默认文案
     */
    private String firstLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "无详细信息";
        }
        String trimmed = text.strip();
        int index = trimmed.indexOf('\n');
        return index < 0 ? trimmed : trimmed.substring(0, index);
    }

    /**
     * 当前时间文本，用于把时间写进回滚日志正文。
     *
     * @return 形如 2026-09-25 17:53:00 的时间文本
     */
    private String nowText() {
        return LocalDateTime.now().format(LOG_TIME_FORMAT);
    }

    /**
     * 版本号展示兜底。
     *
     * <p>首次发布的目标没有 old_version（回滚时这些目标已被 Service 排除，
     * 但仍可能在日志里被引用），这里统一转成可读文案，避免日志出现「回滚到 null」。</p>
     *
     * @param version 版本号，允许为空
     * @return 版本号；为空时返回「（无历史版本）」
     */
    private String displayVersion(String version) {
        return StringUtils.hasText(version) ? version : "（无历史版本）";
    }

    /**
     * 删除平台本地临时文件，失败只记录不抛出。
     *
     * @param file 待删除文件，允许为 null
     */
    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除部署临时文件失败，file={}", file, e);
        }
    }
}
