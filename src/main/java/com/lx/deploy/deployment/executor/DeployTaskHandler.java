package com.lx.deploy.deployment.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.lx.deploy.task.executor.TaskHandler;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 部署任务处理器：批次编排与状态收敛。
 *
 * <p>这是「部署」类型异步任务（{@code TaskType.DEPLOY}）的调度入口，由 {@code TaskExecutor} 调用。
 * 一条 deployment 记录对应一条 DEPLOY 任务，bizId 即 deployment.id；同一条 deployment
 * 在发布与回滚两个阶段共用一个处理器，靠主任务状态区分本次该发 new_version 还是 old_version。</p>
 *
 * <p><b>本类只做编排，不碰任何具体的发布动作</b>：怎么把镜像跑起来、怎么换 jar、怎么换静态目录，
 * 分别在 {@link DockerDeployService} / {@link JarDeployService} / {@link StaticDeployService} 里。
 * 三者对外都是 {@code deploy(DeployContext)}，本类按运行时一次 switch 分发。
 * 这样拆是因为三者的入参、产物形态、回滚方式完全不同（JAR 要备份单文件、STATIC 要换整个目录、
 * DOCKER 什么都不用备份），塞在一个类里会让「改 Docker 逻辑」误伤 JAR 链路。</p>
 *
 * <p>任一批次失败时：滚动策略中止并把剩余目标置为已跳过，全量策略继续跑完再汇总，
 * 保证「失败之后机器上是什么状态」永远可以从目标明细里看出来。
 * 汇总后只要有一个目标失败，部署记录与任务同时置为失败——两者结论必须一致，
 * 否则页面会出现「日志里有 ERROR、任务却是成功」的自相矛盾。</p>
 *
 * <p>互斥键为 {@code deploy:部署ID}：同一条部署的发布与回滚不会并发执行，
 * 不同服务器上的部署仍可并发（AGENTS.md §1）。</p>
 *
 * <p><b>回滚日志的口径</b>：回滚是「把线上退回到一个已知版本」的动作，日志必须能独立回答
 * 「什么时候开始、当前是什么版本、退到哪个版本、有没有成功、服务起没起来、什么时候结束」，
 * 因此开场写开始时间与参与目标数，每个目标写「当前版本 → 回滚到」，收尾单独写一条
 * 「回滚成功/回滚失败 + 完成时间」。
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

    /** 准备完成后的进度百分比。 */
    private static final int PROGRESS_PREPARED = 5;
    /** 进度计算区间：批次执行占用 5 到 90。 */
    private static final int PROGRESS_RANGE_START = 5;
    /** 批次执行占用的进度区间上限。 */
    private static final int PROGRESS_RANGE_END = 90;
    /** 全部完成的进度百分比。 */
    private static final int PROGRESS_DONE = 100;

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

    /** 服务器 Mapper：读取目标服务器档案。 */
    private final ServerMapper serverMapper;

    /** 部署支撑组件：任务日志与进度汇报。 */
    private final DeploySupport support;

    /** JAR 运行时发布服务。 */
    private final JarDeployService jarDeployService;

    /** STATIC 运行时发布服务。 */
    private final StaticDeployService staticDeployService;

    /** DOCKER 运行时发布服务。 */
    private final DockerDeployService dockerDeployService;

    /** 健康检查探测器：发布完成后探测服务是否可用。 */
    private final HealthChecker healthChecker;

    /** 任务服务：汇报执行阶段与进度。 */
    private final TaskService taskService;

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
     * @param support                       部署支撑组件
     * @param jarDeployService              JAR 发布服务
     * @param staticDeployService           STATIC 发布服务
     * @param dockerDeployService           DOCKER 发布服务
     * @param healthChecker                 健康检查探测器
     * @param taskService                   任务服务
     */
    public DeployTaskHandler(DeploymentMapper deploymentMapper,
                             DeploymentTargetMapper deploymentTargetMapper,
                             DeploymentService deploymentService,
                             ProjectMapper projectMapper,
                             EnvironmentMapper environmentMapper,
                             ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper,
                             ReleaseRecordMapper releaseRecordMapper,
                             ServerMapper serverMapper,
                             DeploySupport support,
                             JarDeployService jarDeployService,
                             StaticDeployService staticDeployService,
                             DockerDeployService dockerDeployService,
                             HealthChecker healthChecker,
                             TaskService taskService) {
        this.deploymentMapper = deploymentMapper;
        this.deploymentTargetMapper = deploymentTargetMapper;
        this.deploymentService = deploymentService;
        this.projectMapper = projectMapper;
        this.environmentMapper = environmentMapper;
        this.projectEnvironmentTargetMapper = projectEnvironmentTargetMapper;
        this.releaseRecordMapper = releaseRecordMapper;
        this.serverMapper = serverMapper;
        this.support = support;
        this.jarDeployService = jarDeployService;
        this.staticDeployService = staticDeployService;
        this.dockerDeployService = dockerDeployService;
        this.healthChecker = healthChecker;
        this.taskService = taskService;
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
     * @throws DeployException 部署数据异常、目标服务器不可用，
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
        boolean rollingBack = support.isRollingBack(deployment);
        int targetTotal = (int) countTargets(deploymentId);

        // 只处理仍处于排队的目标：回滚时被排除的目标保持已跳过，不会重复处理。
        // 顺序按创建部署时固化的 deploy_order：这里必须用快照字段，不能用 create_time。
        // 同一秒插入的多条目标 create_time 完全并列，MySQL 对并列行不保证稳定顺序，
        // 实际由随机 UUID 主键决定，会表现为「配了顺序 1 却先发顺序 2」（滚动分批时后果最明显）。
        List<DeploymentTarget> targets = deploymentTargetMapper.selectList(
                new LambdaQueryWrapper<DeploymentTarget>()
                        .eq(DeploymentTarget::getDeploymentId, deploymentId)
                        .eq(DeploymentTarget::getStatus, DeploymentTargetStatus.QUEUED)
                        .orderByAsc(DeploymentTarget::getDeployOrder)
                        .orderByAsc(DeploymentTarget::getCreateTime));

        if (rollingBack) {
            // 回滚的开场必须交代清楚：什么时候开始、总共几台、这次真能退回去几台。
            // 「另外几台为什么不退」由 Service 在目标行上写明了原因，这里只报数量，避免日志重复长文案。
            support.appendLog(task, DeploySupport.STAGE_PREPARE, TaskLogLevel.INFO,
                    "开始回滚部署 " + deploymentId + "，开始时间 " + support.nowText());
            support.appendLog(task, DeploySupport.STAGE_PREPARE, TaskLogLevel.INFO,
                    "回滚策略 " + deployment.getStrategy()
                            + "，本次回滚 " + targets.size() + "/" + targetTotal + " 个目标"
                            + (targetTotal > targets.size()
                            ? "，另有 " + (targetTotal - targets.size()) + " 个目标无历史版本或本次未变更，保持原版本不变"
                            : ""));
        } else {
            support.appendLog(task, DeploySupport.STAGE_PREPARE, TaskLogLevel.INFO,
                    "开始执行部署 " + deploymentId
                            + "，策略 " + deployment.getStrategy()
                            + "，目标数 " + targetTotal);
        }

        if (targets.isEmpty()) {
            support.appendLog(task, DeploySupport.STAGE_FINISH, TaskLogLevel.INFO,
                    rollingBack ? "没有待回滚的目标，本次回滚无实际变更" : "没有待执行的目标，直接收敛本次部署");
            // 没有目标可发（例如回滚时全部被排除）：直接收敛为成功，没有失败可汇总。
            finishDeployment(deploymentId, rollingBack, null);
            return;
        }
        if (!reportProgress(task, DeploySupport.STAGE_PREPARE, PROGRESS_PREPARED)) {
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
            support.appendLog(task, DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
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
                if (!reportProgress(task, DeploySupport.STAGE_DELIVER, progress)) {
                    // 任务已被取消：停止后续目标，已执行的部分保留现场由人工确认。
                    support.appendLog(task, DeploySupport.STAGE_FINISH, TaskLogLevel.INFO, "任务已不在执行中，停止后续发布");
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
                support.appendLog(task, DeploySupport.STAGE_FINISH, TaskLogLevel.INFO,
                        "回滚成功：已成功回滚 " + total + " 个目标到历史版本，服务启动正常"
                                + (skipped > 0 ? "；另有 " + skipped + " 个目标保持原版本不变" : "")
                                + "，完成时间 " + support.nowText());
            } else {
                support.appendLog(task, DeploySupport.STAGE_FINISH, TaskLogLevel.ERROR,
                        "回滚失败：" + failureSummary + "，完成时间 " + support.nowText() + "，请人工确认目标机当前版本");
            }
        }
        finishDeployment(deploymentId, rollingBack, failureSummary);
        if (failureSummary != null) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, failureSummary);
        }
        taskService.updateProgress(task.getId(), DeploySupport.STAGE_FINISH, PROGRESS_DONE);
    }

    // ==================== 目标级执行 ====================

    /**
     * 在单个目标上完成一次发布（或回滚），并落库目标状态。
     *
     * <p>本方法不向外抛异常：单个目标的失败被收敛为目标状态与失败原因，
     * 由调用方决定是否中止后续批次。这样「哪台机器失败、为什么失败」始终有记录。</p>
     *
     * <p>真正的动作按运行时委托给对应的发布服务，本方法只负责
     * 前置校验、组装上下文、健康检查与状态回调。</p>
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
            support.appendLog(task, DeploySupport.STAGE_DELIVER, TaskLogLevel.ERROR, "目标服务器不存在，跳过该目标");
            return false;
        }
        if (server.getStatus() == null || server.getStatus() != EnableStatus.ENABLED) {
            deploymentService.markTargetFailed(target.getId(), "目标服务器已停用", HealthStatus.NOT_CHECKED);
            support.appendLog(task, DeploySupport.STAGE_DELIVER, TaskLogLevel.ERROR,
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
            support.appendLog(task, DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "回滚目标 " + server.getServerName() + "(" + server.getHost() + ")"
                            + "：当前版本 " + displayVersion(target.getNewVersion())
                            + " → 回滚到 " + displayVersion(target.getOldVersion())
                            + "，开始时间 " + support.nowText());
        } else {
            support.appendLog(task, DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "开始在 " + server.getServerName() + "(" + server.getHost() + ") 上发布 "
                            + displayVersion(target.getNewVersion()));
        }
        try {
            if (runtimeType == null) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目的运行时类型非法：" + project.getRuntimeType());
            }
            // 本次实际要发的版本：回滚时是 old_version，正常发布时是 new_version。
            ReleaseRecord versionRelease = resolveVersionRelease(project, deployment, target, rollingBack);
            DeployContext ctx = new DeployContext(task, deployment, project, server, target,
                    config, versionRelease, rollingBack);
            switch (runtimeType) {
                case DOCKER -> dockerDeployService.deploy(ctx);
                case JAR -> jarDeployService.deploy(ctx);
                case STATIC -> staticDeployService.deploy(ctx);
            }
            if (healthConfig != null) {
                HealthChecker.HealthCheckResult result = healthChecker.check(healthConfig, server);
                healthStatus = result.healthy() ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY;
                if (result.healthy()) {
                    support.appendLog(task, DeploySupport.STAGE_HEALTH, TaskLogLevel.INFO,
                            "健康检查通过（" + result.detail() + "）");
                    // 通过同样取证：发布日志要能回答「当时机器上确实跑着这个版本」，
                    // 而不是只在失败时才去目标机上找原因。
                    if (runtimeType == RuntimeType.JAR) {
                        support.appendServiceDiagnosis(task, server, config, project, true);
                    }
                } else {
                    // JAR 运行时补 systemd 诊断：is-active 说活着却连不上端口时，
                    // 只有目标机自己的 status 输出能解释矛盾，光看平台侧的探测结论无从下手。
                    if (runtimeType == RuntimeType.JAR) {
                        support.appendServiceDiagnosis(task, server, config, project, false);
                    }
                    throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                            "健康检查不通过（" + result.detail() + "）");
                }
            }
            deploymentService.markTargetSuccess(target.getId(), healthStatus);
            if (rollingBack) {
                // 「是否启动成功」是回滚结论的一部分：只有重启并通过健康检查才会走到这里，
                // 把这两个事实写进同一行，使用者不必再去翻上面的启动日志确认。
                support.appendLog(task, DeploySupport.STAGE_FINISH, TaskLogLevel.INFO,
                        "目标 " + server.getServerName() + " 回滚成功，服务启动正常（当前版本 "
                                + displayVersion(target.getOldVersion()) + "）");
            } else {
                support.appendLog(task, DeploySupport.STAGE_FINISH, TaskLogLevel.INFO,
                        "目标 " + server.getServerName() + " 发布成功");
            }
            return true;
        } catch (Exception e) {
            // 失败原因不含凭据：所有远程输出在 SshCommandResult 中已做脱敏。
            deploymentService.markTargetFailed(target.getId(), safeMessage(e), healthStatus);
            support.appendLog(task, DeploySupport.STAGE_FINISH, TaskLogLevel.ERROR,
                    rollingBack
                            ? "目标 " + server.getServerName() + " 回滚失败：" + safeMessage(e)
                            : "目标 " + server.getServerName() + " 发布失败：" + safeMessage(e));
            return false;
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
        for (int i = 0; i < targets.size(); i += size) {
            batches.add(new ArrayList<>(targets.subList(i, Math.min(i + size, targets.size()))));
        }
        return batches;
    }

    /**
     * 把指定批次之后的全部目标置为已跳过。
     *
     * <p>滚动策略在某批失败后调用：剩余机器保持旧版本，不参与本次变更，
     * 因此回滚时它们也会被排除——跳过不是「没处理」，而是一个有明确含义的终态。</p>
     *
     * @param task      当前任务
     * @param batches   全部分批
     * @param fromIndex 起始批次下标（含）
     */
    private void skipRemaining(Task task, List<List<DeploymentTarget>> batches, int fromIndex) {
        for (int i = fromIndex; i < batches.size(); i++) {
            for (DeploymentTarget target : batches.get(i)) {
                deploymentService.skipTarget(target.getId(), "前序批次失败，滚动策略中止，本目标保持旧版本");
                support.appendLog(task, DeploySupport.STAGE_FINISH, TaskLogLevel.INFO,
                        "目标 " + target.getServerId() + " 因前序批次失败被跳过");
            }
        }
    }

    /**
     * 收敛部署主记录状态。
     *
     * @param deploymentId   部署主键
     * @param rollingBack    本次是否为回滚
     * @param failureSummary 失败汇总；为空表示全部成功
     */
    private void finishDeployment(String deploymentId, boolean rollingBack, String failureSummary) {
        if (rollingBack) {
            if (failureSummary == null) {
                deploymentService.markRolledBack(deploymentId);
            } else {
                deploymentService.markRollbackFailed(deploymentId, failureSummary);
            }
            return;
        }
        if (failureSummary == null) {
            deploymentService.markSuccess(deploymentId);
        } else {
            deploymentService.markFailed(deploymentId, failureSummary);
        }
    }

    /**
     * 汇总失败目标的原因。
     *
     * @param deploymentId 部署主键
     * @param failed       失败目标数
     * @param total        参与目标数
     * @return 面向页面与日志的失败摘要
     */
    private String buildFailureSummary(String deploymentId, int failed, int total) {
        List<DeploymentTarget> failedTargets = deploymentTargetMapper.selectList(
                new LambdaQueryWrapper<DeploymentTarget>()
                        .eq(DeploymentTarget::getDeploymentId, deploymentId)
                        .eq(DeploymentTarget::getStatus, DeploymentTargetStatus.FAILED));
        StringBuilder builder = new StringBuilder(total + " 个目标中 " + failed + " 个失败");
        for (DeploymentTarget target : failedTargets) {
            builder.append("\n- ").append(target.getServerId()).append("：")
                    .append(target.getErrorMessage() == null ? "未知原因" : target.getErrorMessage());
        }
        return builder.toString();
    }

    // ==================== 配置解析 ====================

    /**
     * 解析运行配置。
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

    // ==================== 进度与辅助 ====================

    /**
     * 汇报进度。
     *
     * <p>任务被取消时 updateProgress 会抛异常，这里收敛为 false 让调用方停止后续目标，
     * 而不是让「取消」变成一条失败记录。</p>
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
            support.appendLog(task, stage, TaskLogLevel.INFO, "任务已不在执行中，停止后续操作：" + e.getMessage());
            return false;
        }
    }

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
}
