package com.lx.deploy.deployment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.common.constants.DeploymentTargetStatus;
import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.constants.HealthStatus;
import com.lx.deploy.common.constants.ReleaseStatus;
import com.lx.deploy.common.enums.project.DeployStrategy;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.mapper.DeploymentTargetMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.deployment.model.dto.DeploymentDto;
import com.lx.deploy.deployment.model.vo.DeploymentTargetVo;
import com.lx.deploy.deployment.model.vo.DeploymentVo;
import com.lx.deploy.deployment.model.vo.RollbackPreviewVo;
import com.lx.deploy.deployment.model.vo.RollbackTargetPreviewVo;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.mapper.ProjectEnvironmentTargetMapper;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Environment;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.project.model.ProjectEnvironmentTarget;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.release.service.ArtifactProbe;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.task.mapper.TaskMapper;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskDto;
import com.lx.deploy.task.model.vo.TaskVo;
import com.lx.deploy.task.service.TaskService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。职责分为两部分：</p>
 * <ul>
 *   <li><b>触发与查询</b>：{@link #createAndTrigger(DeploymentDto)} 校验项目/环境/版本/拓扑，
 *       把启用目标快照成 deployment_target 并生成 DEPLOY 异步任务；{@link #rollback(String)}
 *       与 {@link #cancel(String)} 提供回滚与人工取消入口；</li>
 *   <li><b>状态维护</b>：发布执行器在关键节点回调本服务的目标流转方法与主任务收尾方法，
 *       执行器本身不直接写业务表（与 BuildTaskHandler → BuildService 的分工一致）。</li>
 * </ul>
 *
 * <p>跨模块只注入对方 Mapper 与 TaskService，不注入对方 Service，避免 Service 之间循环依赖。</p>
 *
 * <p>关键设计：目标列表在<b>创建时快照</b>。发布记录必须忠实反映「当时发了哪些机器」，
 * 后续修改项目拓扑不能追溯性地改变已有部署记录，否则回滚会发到错误的机器上。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Service
public class DeploymentService extends ServiceImpl<DeploymentMapper, Deployment> {

    /** 失败原因写入数据库前的长度上限，避免超长文本塞满 error_message。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    /** 回溯历史部署以推断「当前版本」时的最大扫描条数，防止无边界查询。 */
    private static final int OLD_VERSION_SCAN_LIMIT = 10;

    /** 部署目标 Mapper：快照目标、查询明细、状态流转。 */
    private final DeploymentTargetMapper deploymentTargetMapper;

    /** 项目 Mapper：校验项目存在/启用，读取运行时与运行/健康检查配置。 */
    private final ProjectMapper projectMapper;

    /** 环境 Mapper：校验环境存在，读取默认部署策略与批次大小。 */
    private final EnvironmentMapper environmentMapper;

    /** 项目环境目标 Mapper：读取本次发布要展开的目标拓扑。 */
    private final ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper;

    /** 发布版本 Mapper：校验版本存在且可发布，读取镜像标签或产物路径。 */
    private final ReleaseRecordMapper releaseRecordMapper;

    /** 服务器 Mapper：校验目标服务器存在且启用，供 VO 补充服务器名称。 */
    private final ServerMapper serverMapper;

    /** 任务 Mapper：部署详情返回时关联出驱动本次发布的异步任务。 */
    private final TaskMapper taskMapper;

    /** 任务服务：创建驱动发布/回滚的异步任务，强制从排队状态开始。 */
    private final TaskService taskService;

    /** 产物存在性预检：确认版本的落盘产物还在，避免发布任务跑到一半才发现取不到包。 */
    private final ArtifactProbe artifactProbe;

    /**
     * 注入部署模块与跨模块数据访问依赖。
     *
     * @param deploymentTargetMapper         部署目标 Mapper
     * @param projectMapper                  项目 Mapper
     * @param environmentMapper              环境 Mapper
     * @param projectEnvironmentTargetMapper 项目环境目标 Mapper
     * @param releaseRecordMapper            发布版本 Mapper
     * @param serverMapper                   服务器 Mapper
     * @param taskMapper                     任务 Mapper
     * @param taskService                    任务服务
     * @param artifactProbe                  产物存在性预检
     */
    public DeploymentService(DeploymentTargetMapper deploymentTargetMapper,
                             ProjectMapper projectMapper,
                             EnvironmentMapper environmentMapper,
                             ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper,
                             ReleaseRecordMapper releaseRecordMapper,
                             ServerMapper serverMapper,
                             TaskMapper taskMapper,
                             TaskService taskService,
                             ArtifactProbe artifactProbe) {
        this.deploymentTargetMapper = deploymentTargetMapper;
        this.projectMapper = projectMapper;
        this.environmentMapper = environmentMapper;
        this.projectEnvironmentTargetMapper = projectEnvironmentTargetMapper;
        this.releaseRecordMapper = releaseRecordMapper;
        this.serverMapper = serverMapper;
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.artifactProbe = artifactProbe;
    }

    // ==================== 查询接口 ====================

    /**
     * 根据 DTO 中的非空条件分页查询部署记录。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<DeploymentVo> pageByCondition(PageQuery pageQuery, DeploymentDto condition) {
        Page<Deployment> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<DeploymentVo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配部署记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<DeploymentVo> listByCondition(DeploymentDto condition) {
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询并返回部署响应对象（含目标汇总与关联任务标识）。
     *
     * @param id 部署主键
     * @return 含项目名、环境名、版本号、状态文案与任务标识的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public DeploymentVo getRequiredById(String id) {
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 查询一次部署的全部目标明细。
     *
     * @param deploymentId 部署主键
     * @return 目标响应列表，按发布顺序（创建顺序）升序
     * @throws DeployException 部署不存在时抛出
     */
    public List<DeploymentTargetVo> listTargets(String deploymentId) {
        getRequiredEntityById(deploymentId);
        return deploymentTargetMapper.selectList(new LambdaQueryWrapper<DeploymentTarget>()
                .eq(DeploymentTarget::getDeploymentId, deploymentId)
                .orderByAsc(DeploymentTarget::getCreateTime))
                .stream().map(this::toTargetVo).toList();
    }

    // ==================== 触发类接口 ====================

    /**
     * 创建一次部署并同步触发异步任务。
     *
     * <p>流程：校验项目启用 → 校验环境存在 → 校验版本属于该项目且为「可发布」→
     * 解析策略（请求覆盖优先，否则取环境默认）→ 按项目环境目标展开启用目标（单目标只取主目标）→
     * 校验目标服务器仍启用 → 防重（该项目环境已有进行中部署）→ 落 deployment（排队）+
     * deployment_target（含 old_version 快照）→ 生成 DEPLOY 任务。</p>
     *
     * <p>old_version 的推断是回滚能否成立的关键：回溯该项目环境最近的历史部署，
     * 取每台机器「真正生效过的版本」——成功的部署取 new_version，已回滚的部署取 old_version
     * （回滚之后机器跑的就是旧版本）。首次发布时为空。</p>
     *
     * <p><b>本方法必须在单个事务内完成</b>：deployment、deployment_target 与 DEPLOY 任务
     * 是同一次发布的三个组成部分，任何一步失败都要一起回滚。曾经因为没有事务，
     * 目标快照插入失败时主记录已经落库，留下一条「永远不会有关联任务」的孤儿部署，
     * 而且它的排队中状态还会挡住后续所有发布（提示已有进行中的部署）。</p>
     *
     * @param dto 部署请求，包含项目、环境与版本，可选覆盖策略
     * @return 创建并触发的部署响应对象（含 taskId / taskNo）
     * @throws DeployException 项目/环境/版本不合法、无可用目标、目标服务器停用或存在进行中部署时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public DeploymentVo createAndTrigger(DeploymentDto dto) {
        // 项目必须存在且启用。
        Project project = projectMapper.selectById(dto.getProjectId());
        if (project == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "目标项目不存在");
        }
        if (project.getStatus() == null || project.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "目标项目已停用，不允许发起部署");
        }
        // 环境必须存在。
        Environment environment = environmentMapper.selectById(dto.getEnvironmentId());
        if (environment == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "目标环境不存在");
        }
        // 版本必须存在、属于该项目，且处于可发布状态。
        ReleaseRecord release = releaseRecordMapper.selectById(dto.getReleaseId());
        if (release == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "发布版本不存在");
        }
        if (!project.getId().equals(release.getProjectId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "发布版本 " + release.getVersion() + " 不属于该项目，不允许跨项目部署");
        }
        if (release.getStatus() == null || release.getStatus() != ReleaseStatus.RELEASABLE) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "发布版本当前状态为 " + ReleaseStatus.describe(release.getStatus()) + "，只允许发布「可发布」版本");
        }
        // 产物存在性预检：版本记录还在，不代表构建机上的产物还在（换过构建机、被保留策略回收都会让它消失）。
        // 不预检的话要到任务「下载产物」阶段才失败，那时部署记录与目标快照都已落库，页面上看像一次失败的发布。
        artifactProbe.requireExists(release);
        // 策略：请求覆盖优先，否则取环境默认，环境也未配置时退化为单目标。
        String strategy = resolveStrategy(dto.getStrategy(), environment);

        // 展开本次要发的目标：只取启用绑定，按 deploy_order 升序（与页面配置的发布顺序一致）。
        List<ProjectEnvironmentTarget> bindings = projectEnvironmentTargetMapper.selectList(
                new LambdaQueryWrapper<ProjectEnvironmentTarget>()
                        .eq(ProjectEnvironmentTarget::getProjectId, project.getId())
                        .eq(ProjectEnvironmentTarget::getEnvironmentId, environment.getId())
                        .eq(ProjectEnvironmentTarget::getStatus, EnableStatus.ENABLED)
                        .orderByAsc(ProjectEnvironmentTarget::getDeployOrder)
                        .orderByAsc(ProjectEnvironmentTarget::getCreateTime));
        if (bindings.isEmpty()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "该项目在环境 " + environment.getName() + " 下没有启用的部署目标，请先在部署目标中绑定服务器");
        }
        List<ProjectEnvironmentTarget> selected = selectByStrategy(bindings, strategy);

        // 防重：同一项目环境已有进行中的部署时不重复触发，避免两台机器同时抢同一批目标。
        long inProgress = lambdaQuery()
                .eq(Deployment::getProjectId, project.getId())
                .eq(Deployment::getEnvironmentId, environment.getId())
                .in(Deployment::getStatus, DeploymentStatus.QUEUED, DeploymentStatus.DEPLOYING,
                        DeploymentStatus.ROLLING_BACK)
                .count();
        if (inProgress > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "该项目在该环境下已有进行中的部署，请等待其结束后再发起");
        }

        // 推断每台机器当前生效的版本，作为本次的 old_version（回滚依据）。
        Map<String, String> currentVersions = resolveCurrentVersions(project.getId(), environment.getId());

        // 落主任务，初始为排队，策略与操作者在此固化。
        Deployment deployment = new Deployment();
        deployment.setProjectId(project.getId());
        deployment.setEnvironmentId(environment.getId());
        deployment.setReleaseId(release.getId());
        deployment.setStrategy(strategy);
        deployment.setStatus(DeploymentStatus.QUEUED);
        // 操作者：过渡期没有登录态，接入统一权限中心后再从认证信息填充，此处不猜测用户名。
        if (!save(deployment)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "创建部署记录失败");
        }

        // 落目标快照：逐台校验服务器仍可用，再写入目标行。
        for (ProjectEnvironmentTarget binding : selected) {
            Server server = serverMapper.selectById(binding.getServerId());
            if (server == null) {
                throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署目标绑定的服务器不存在，请检查项目部署目标配置");
            }
            if (server.getStatus() == null || server.getStatus() != EnableStatus.ENABLED) {
                throw new DeployException(ResponseCode.DATA_CONFLICT,
                        "部署目标服务器 " + server.getServerName() + " 已停用，不允许发布");
            }
            DeploymentTarget target = new DeploymentTarget();
            target.setDeploymentId(deployment.getId());
            target.setServerId(server.getId());
            target.setStatus(DeploymentTargetStatus.QUEUED);
            target.setOldVersion(currentVersions.get(server.getId()));
            target.setNewVersion(release.getVersion());
            target.setImageTag(release.getImageTag());
            deploymentTargetMapper.insert(target);
        }

        // 生成驱动发布的异步任务，bizId 指向 deployment 主键。
        TaskVo taskVo = createDeployTask(deployment.getId());
        DeploymentVo vo = toVo(deployment);
        vo.setTaskId(taskVo.getId());
        vo.setTaskNo(taskVo.getTaskNo());
        return vo;
    }

    /**
     * 根据路径主键和请求 DTO 修改部署。
     *
     * <p>部署记录绝大多数字段由执行链路维护，可修改范围被严格限制：
     * 仅允许在「排队」状态下更换发布版本或调整策略（相当于重新定义这次发布要做什么）。
     * 已开始或已结束的部署不允许改，避免执行器读到与用户预期不一致的目标版本。</p>
     *
     * @param id  待修改的部署主键
     * @param dto 包含可选 releaseId / strategy 的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在、非排队状态或版本/策略不合法时抛出
     */
    public DeploymentVo update(String id, DeploymentDto dto) {
        Deployment deployment = getRequiredEntityById(id);
        if (deployment.getStatus() == null || deployment.getStatus() != DeploymentStatus.QUEUED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "仅排队中的部署允许修改，当前状态为 " + DeploymentStatus.describe(deployment.getStatus()));
        }
        // 换版本：重新校验存在性与可发布状态，并同步刷新所有目标的 new_version 与镜像标签。
        if (StringUtils.hasText(dto.getReleaseId()) && !dto.getReleaseId().equals(deployment.getReleaseId())) {
            ReleaseRecord release = releaseRecordMapper.selectById(dto.getReleaseId());
            if (release == null) {
                throw new DeployException(ResponseCode.DATA_NOT_FOUND, "发布版本不存在");
            }
            if (!deployment.getProjectId().equals(release.getProjectId())) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR, "发布版本不属于该部署所属项目");
            }
            if (release.getStatus() == null || release.getStatus() != ReleaseStatus.RELEASABLE) {
                throw new DeployException(ResponseCode.DATA_CONFLICT,
                        "发布版本当前状态为 " + ReleaseStatus.describe(release.getStatus()) + "，只允许发布「可发布」版本");
            }
            deployment.setReleaseId(release.getId());
            updateById(deployment);
            // 目标尚未开始执行，可以安全地整体换成新版本的版本号与镜像标签。
            List<DeploymentTarget> targets = listTargetsEntity(id);
            for (DeploymentTarget target : targets) {
                target.setNewVersion(release.getVersion());
                target.setImageTag(release.getImageTag());
                deploymentTargetMapper.updateById(target);
            }
        }
        // 换策略：只影响执行器的批次编排，不需要改动已快照的目标列表。
        if (StringUtils.hasText(dto.getStrategy())) {
            if (DeployStrategy.fromCode(dto.getStrategy()) == null) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "部署策略必须为 SINGLE_TARGET、ALL_AT_ONCE 或 ROLLING");
            }
            deployment.setStrategy(dto.getStrategy());
            updateById(deployment);
        }
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除部署记录。
     *
     * <p>只有终态（成功/失败/已取消/已回滚）的部署允许删除，
     * 避免删掉正在执行的部署导致执行器持有悬空记录。</p>
     *
     * @param id 部署主键
     * @throws DeployException 数据不存在或部署未结束时抛出
     */
    public void delete(String id) {
        Deployment deployment = getRequiredEntityById(id);
        if (!DeploymentStatus.isFinal(deployment.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "部署尚未结束，不允许删除");
        }
        // 目标随主任务一并逻辑删除，保证明细不留孤儿数据。
        deploymentTargetMapper.delete(new LambdaQueryWrapper<DeploymentTarget>()
                .eq(DeploymentTarget::getDeploymentId, id));
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除部署记录失败");
        }
    }

    /**
     * 回滚一次已成功的部署。
     *
     * <p>前置条件：主任务状态为「成功」，且至少有一个目标真的可以回滚。回滚的做法是
     * <b>复用同一批目标行</b>：把真正变更过的目标重置为排队（未动过的目标保持已跳过/排队原样），
     * 再生成一个 DEPLOY 任务；执行器识别到主任务处于「回滚中」后，按 old_version 而不是
     * new_version 重新发布。没有历史版本的目标会跳过回滚并记录原因，
     * 而不是把它判成失败——机器上本来就没有平台纳管的版本，无从回滚。</p>
     *
     * <p><b>不可回滚时必须明确拒绝，而不是静默成功。</b>曾经的做法是「一个目标都回滚不了时
     * 直接把状态置为已回滚」——使用者看到「已回滚」，机器上却一个字节都没变，
     * 页面上也找不到任何说明。现在改为在入口处按 {@link #evaluateRollback} 的结论抛异常，
     * 前端把原因展示在回滚按钮上，后端把同一句话作为错误提示返回。</p>
     *
     * <p>目标行重置、主任务状态切换与 DEPLOY 任务生成必须在同一事务内完成，
     * 否则可能出现「状态已是回滚中但任务没建起来」的僵死部署。</p>
     *
     * @param id 部署主键
     * @return 进入回滚中的部署响应对象（含新的 taskId / taskNo）
     * @throws DeployException 部署不存在、状态不允许回滚、无任何可回滚目标或创建任务失败时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public DeploymentVo rollback(String id) {
        Deployment deployment = getRequiredEntityById(id);
        List<DeploymentTarget> targets = listTargetsEntity(id);
        // 入口先判定：状态是否允许、有没有历史版本、历史版本的产物是否还在。
        RollbackEvaluation evaluation = evaluateRollback(deployment, targets);
        if (!evaluation.rollbackable()) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, evaluation.blockReason());
        }
        // 可回滚的目标重置为排队，等待执行器按 old_version 重新发布；
        // 退不回去的目标记为跳过并写清原因，而不是判成失败——机器上本来就没有可退的版本。
        for (RollbackDecision decision : evaluation.decisions()) {
            DeploymentTarget target = decision.target();
            if (!decision.rollbackable()) {
                skipForRollback(target, decision.skipReason());
                continue;
            }
            target.setStatus(DeploymentTargetStatus.QUEUED);
            target.setHealthStatus(HealthStatus.NOT_CHECKED);
            target.setErrorMessage(null);
            target.setStartedAt(null);
            target.setFinishedAt(null);
            deploymentTargetMapper.updateById(target);
        }
        deployment.setStatus(DeploymentStatus.ROLLING_BACK);
        deployment.setFinishedAt(null);
        deployment.setErrorMessage(null);
        updateById(deployment);
        TaskVo taskVo = createDeployTask(id);
        DeploymentVo vo = toVo(deployment);
        vo.setTaskId(taskVo.getId());
        vo.setTaskNo(taskVo.getTaskNo());
        return vo;
    }

    /**
     * 预览一次回滚会发生什么，不做任何状态变更。
     *
     * <p>回答三个问题：总共几台、哪几台会退回历史版本、哪几台会被跳过以及为什么。
     * 判定结果与 {@link #rollback(String)} 完全同源（同一个 {@link #evaluateRollback}），
     * 因此预览里看到的清单，就是提交后真正会执行的清单——不存在「预览说能退、提交说不能」。</p>
     *
     * <p>不可回滚时同样返回结论而不是抛异常：前端需要把 blockReason 展示在弹窗里，
     * 让使用者知道为什么这次退不回去，而不是只弹一句失败的提示。</p>
     *
     * @param id 部署主键
     * @return 回滚预览（总结论 + 逐目标明细）
     * @throws DeployException 部署不存在时抛出
     */
    public RollbackPreviewVo rollbackPreview(String id) {
        Deployment deployment = getRequiredEntityById(id);
        List<DeploymentTarget> targets = listTargetsEntity(id);
        RollbackEvaluation evaluation = evaluateRollback(deployment, targets);
        RollbackPreviewVo vo = new RollbackPreviewVo();
        vo.setRollbackable(evaluation.rollbackable());
        vo.setBlockReason(evaluation.blockReason());
        vo.setRollbackCount(evaluation.rollbackCount());
        vo.setSkippedCount(evaluation.skippedCount());
        vo.setTargetTotal(targets.size());
        vo.setTargets(evaluation.decisions().stream().map(this::toRollbackTargetPreview).toList());
        return vo;
    }

    /**
     * 取消一次部署。
     *
     * <p>排队中与发布中的部署可取消；终态取消无意义，回滚中不允许取消
     * （回滚必须走完，中途取消会让目标停留在新旧版本混合状态）。
     * 取消同时收敛未结束的目标：排队与发布中的目标置为已取消，已成功/已失败的目标保留原状，
     * 让运维看清「取消之前到底改了哪几台」。</p>
     *
     * @param id 部署主键
     * @return 取消后的部署响应对象
     * @throws DeployException 部署不存在或状态不允许取消时抛出
     */
    public DeploymentVo cancel(String id) {
        Deployment deployment = getRequiredEntityById(id);
        if (!DeploymentStatus.canCancel(deployment.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "当前状态为 " + DeploymentStatus.describe(deployment.getStatus()) + "，不允许取消");
        }
        deployment.setStatus(DeploymentStatus.CANCELED);
        deployment.setFinishedAt(LocalDateTime.now());
        updateById(deployment);
        // 未结束的目标随主任务取消。
        for (DeploymentTarget target : listTargetsEntity(id)) {
            Integer status = target.getStatus();
            if (status == null || status == DeploymentTargetStatus.QUEUED
                    || status == DeploymentTargetStatus.DEPLOYING) {
                target.setStatus(DeploymentTargetStatus.CANCELED);
                target.setFinishedAt(LocalDateTime.now());
                deploymentTargetMapper.updateById(target);
            }
        }
        // 同步取消驱动本次发布的异步任务；已结束的任务会被任务服务拒绝，异常在此忽略。
        cancelDeployTask(id);
        return toVo(deployment);
    }

    // ==================== 发布执行器回调 ====================

    /**
     * 标记部署进入发布中（或保持回滚中）。
     *
     * <p>执行器开始真正操作目标服务器前调用。排队中的部署进入发布中；
     * 已处于回滚中的部署保持回滚中状态不变——回滚与发布共用同一条执行链路，
     * 主任务状态是执行器判断「这次该发 new_version 还是 old_version」的唯一依据。</p>
     *
     * @param deploymentId 部署主键
     */
    public void markDeploying(String deploymentId) {
        Deployment deployment = getRequiredEntityById(deploymentId);
        if (deployment.getStatus() == null || deployment.getStatus() == DeploymentStatus.QUEUED) {
            deployment.setStatus(DeploymentStatus.DEPLOYING);
        }
        // 重新开始时清空上一轮的结束时间与失败原因，避免展示到过期结论。
        deployment.setStartedAt(LocalDateTime.now());
        deployment.setFinishedAt(null);
        deployment.setErrorMessage(null);
        updateById(deployment);
    }

    /**
     * 标记某个目标进入发布中。
     *
     * @param targetId 目标主键
     */
    public void markTargetRunning(String targetId) {
        DeploymentTarget target = getRequiredTargetById(targetId);
        target.setStatus(DeploymentTargetStatus.DEPLOYING);
        target.setStartedAt(LocalDateTime.now());
        target.setFinishedAt(null);
        target.setErrorMessage(null);
        deploymentTargetMapper.updateById(target);
    }

    /**
     * 标记某个目标发布成功。
     *
     * @param targetId     目标主键
     * @param healthStatus 健康检查结果数字编码，取值见 {@code HealthStatus}；未配置检查时传 0 未检查
     */
    public void markTargetSuccess(String targetId, Integer healthStatus) {
        DeploymentTarget target = getRequiredTargetById(targetId);
        target.setStatus(DeploymentTargetStatus.SUCCESS);
        target.setHealthStatus(healthStatus);
        target.setFinishedAt(LocalDateTime.now());
        target.setErrorMessage(null);
        deploymentTargetMapper.updateById(target);
    }

    /**
     * 标记某个目标发布失败。
     *
     * <p>发布动作失败与健康检查不健康都走本方法：对使用者而言「发布成功但服务起不来」
     * 同样是失败，只是原因不同，因此统一收敛为失败并由 health_status 区分细节。</p>
     *
     * @param targetId     目标主键
     * @param errorMessage 失败原因（面向页面展示，禁止含敏感信息）
     * @param healthStatus 健康检查结果数字编码，未配置检查时传 0 未检查
     */
    public void markTargetFailed(String targetId, String errorMessage, Integer healthStatus) {
        DeploymentTarget target = getRequiredTargetById(targetId);
        target.setStatus(DeploymentTargetStatus.FAILED);
        target.setHealthStatus(healthStatus);
        target.setFinishedAt(LocalDateTime.now());
        target.setErrorMessage(truncate(errorMessage));
        deploymentTargetMapper.updateById(target);
    }

    /**
     * 把某个目标置为已跳过。
     *
     * <p>滚动发布某批失败后，尚未执行的后续目标走本方法：它们保持旧版本未被改动，
     * 记为「已跳过」而不是「失败」，回滚时据此排除，不会把从未更新过的机器回退。</p>
     *
     * @param targetId 目标主键
     * @param reason   跳过原因（面向页面展示）
     */
    public void skipTarget(String targetId, String reason) {
        DeploymentTarget target = getRequiredTargetById(targetId);
        target.setStatus(DeploymentTargetStatus.SKIPPED);
        target.setFinishedAt(LocalDateTime.now());
        target.setErrorMessage(truncate(reason));
        deploymentTargetMapper.updateById(target);
    }

    /**
     * 标记部署整体成功。
     *
     * @param deploymentId 部署主键
     */
    public void markSuccess(String deploymentId) {
        Deployment deployment = getRequiredEntityById(deploymentId);
        deployment.setStatus(DeploymentStatus.SUCCESS);
        deployment.setFinishedAt(LocalDateTime.now());
        deployment.setErrorMessage(null);
        updateById(deployment);
    }

    /**
     * 标记部署整体失败。
     *
     * @param deploymentId 部署主键
     * @param errorMessage 失败原因（面向页面展示，禁止含敏感信息）
     */
    public void markFailed(String deploymentId, String errorMessage) {
        Deployment deployment = getRequiredEntityById(deploymentId);
        deployment.setStatus(DeploymentStatus.FAILED);
        deployment.setFinishedAt(LocalDateTime.now());
        deployment.setErrorMessage(truncate(errorMessage));
        updateById(deployment);
    }

    /**
     * 标记部署已回滚完成。
     *
     * @param deploymentId 部署主键
     */
    public void markRolledBack(String deploymentId) {
        Deployment deployment = getRequiredEntityById(deploymentId);
        deployment.setStatus(DeploymentStatus.ROLLED_BACK);
        deployment.setFinishedAt(LocalDateTime.now());
        deployment.setErrorMessage(null);
        updateById(deployment);
    }

    /**
     * 标记回滚失败。
     *
     * <p>回滚失败不回到「成功」：此时机器处于新旧版本混合状态，
     * 必须让页面看到一个明确的失败结论与人工介入提示。</p>
     *
     * @param deploymentId 部署主键
     * @param errorMessage 失败原因（面向页面展示，禁止含敏感信息）
     */
    public void markRollbackFailed(String deploymentId, String errorMessage) {
        Deployment deployment = getRequiredEntityById(deploymentId);
        deployment.setStatus(DeploymentStatus.FAILED);
        deployment.setFinishedAt(LocalDateTime.now());
        deployment.setErrorMessage(truncate("回滚失败：" + errorMessage));
        updateById(deployment);
    }

    // ==================== 私有辅助 ====================

    /**
     * 单个目标的回滚判定明细。
     *
     * <p>判定只做一次：{@link #evaluateRollback} 用它聚合出总结论，{@link #rollback(String)}
     * 用它决定每个目标是重置还是跳过，{@link #rollbackPreview(String)} 用它渲染逐台清单。
     * 三处消费同一份明细，避免出现「预览说能退、执行时说退不了」。</p>
     *
     * @param target         目标实体
     * @param rollbackable   true 表示该目标可以回滚到自己的 old_version
     * @param skipReason     不可回滚的面向页面原因；可回滚时为空
     * @param missingVersion 因产物缺失而不可回滚时缺失的版本号；其它情况为空
     */
    private record RollbackDecision(DeploymentTarget target, boolean rollbackable,
                                    String skipReason, String missingVersion) {
    }

    /**
     * 回滚可行性判定结果。
     *
     * <p>判定与执行必须使用同一份结论，否则会出现「按钮可点但接口报错」或
     * 「按钮置灰但接口能跑」的不一致。{@link #evaluateRollback} 是唯一判定入口，
     * {@link #rollback(String)}、{@link #rollbackPreview(String)} 与列表/详情的 VO 填充都取它的结果。</p>
     *
     * @param rollbackable  是否允许发起回滚
     * @param blockReason   不可回滚的原因；可回滚时为空
     * @param rollbackCount 真正可回滚的目标数（变更过且有可用历史版本）
     * @param skippedCount  需要跳过回滚的目标数（变更过但无历史版本或历史版本产物已缺失）
     * @param decisions     逐目标判定明细；状态不允许回滚时为空列表
     */
    private record RollbackEvaluation(boolean rollbackable, String blockReason, int rollbackCount, int skippedCount,
                                      List<RollbackDecision> decisions) {
    }

    /**
     * 判定一次部署当前能否发起回滚，并给出不可回滚的原因。
     *
     * <p>判定顺序即使用者会遇到问题的顺序，原因文案也按此优先级给出：</p>
     * <ol>
     *   <li>状态不是「成功」——只有发布成功的部署才有「新版本」可退，失败/已取消/已回滚
     *       与进行中的部署都不允许重复回滚；</li>
     *   <li>变更过的目标全都没有 old_version——首次发布时目标机上没有平台纳管的版本；</li>
     *   <li>有 old_version，但对应版本记录已查不到（或该版本缺少分发所需的信息）——
     *       平台回滚是「重新发布 old_version」，靠的是版本记录与构建机上的产物，
     *       而不是目标机 exec/bak 里的手工备份包，所以这一档必须在入口拦下，
     *       否则只能等执行到一半才失败，机器已经动了半截。</li>
     * </ol>
     *
     * <p>只要存在<b>至少一个</b>可回滚目标就算可回滚：部分回滚是允许的，
     * 没有历史版本的机器会被跳过并在目标明细与任务日志里写明，不会把从未更新过的机器回退。</p>
     *
     * @param deployment 部署主记录
     * @param targets    该部署的全部目标行
     * @return 判定结果；可回滚时 rollbackCount 必大于 0
     */
    private RollbackEvaluation evaluateRollback(Deployment deployment, List<DeploymentTarget> targets) {
        if (!DeploymentStatus.canRollback(deployment.getStatus())) {
            return new RollbackEvaluation(false,
                    "当前状态为 " + DeploymentStatus.describe(deployment.getStatus()) + "，只有发布成功的部署允许回滚",
                    0, 0, List.of());
        }
        List<RollbackDecision> decisions = inspectTargets(deployment.getProjectId(), targets);
        int rollbackCount = 0;
        int skippedCount = 0;
        String unavailableVersion = null;
        for (RollbackDecision decision : decisions) {
            if (decision.rollbackable()) {
                rollbackCount++;
                continue;
            }
            skippedCount++;
            if (unavailableVersion == null) {
                // 优先报「产物已不存在」：它比「首次发布」更需要使用者去处理产物。
                unavailableVersion = decision.missingVersion();
            }
        }
        if (rollbackCount > 0) {
            return new RollbackEvaluation(true, null, rollbackCount, skippedCount, decisions);
        }
        if (decisions.isEmpty()) {
            return new RollbackEvaluation(false, "本次发布没有变更过任何目标，没有可回滚的内容", 0, 0, decisions);
        }
        if (unavailableVersion != null) {
            return new RollbackEvaluation(false,
                    "历史版本 " + unavailableVersion + " 的发布记录或构建产物已不存在，无法回滚",
                    0, skippedCount, decisions);
        }
        return new RollbackEvaluation(false,
                "本次发布为首次发布，目标机上没有平台纳管的历史版本，无法回滚", 0, skippedCount, decisions);
    }

    /**
     * 逐个目标判定能否回滚到它自己的 old_version。
     *
     * <p>只判定本次<b>真正变更过</b>的目标：排队与已跳过的目标从未被更新，
     * 回滚它们没有意义，因此既不计入可回滚，也不计入跳过。</p>
     *
     * <p>同一版本可能被多台机器引用，逐个查库会放大成 N 次查询，因此按版本号缓存一次判定结果。</p>
     *
     * @param projectId 项目主键
     * @param targets   该部署的全部目标行
     * @return 逐目标判定明细；顺序与入参一致
     */
    private List<RollbackDecision> inspectTargets(String projectId, List<DeploymentTarget> targets) {
        Map<String, Boolean> availabilityCache = new HashMap<>();
        List<RollbackDecision> decisions = new ArrayList<>();
        for (DeploymentTarget target : targets) {
            if (DeploymentTargetStatus.isUntouched(target.getStatus())) {
                // 排队/已跳过的目标本次没有变更过，回滚它没有意义，保持原状。
                continue;
            }
            if (!StringUtils.hasText(target.getOldVersion())) {
                // 首次发布：该机器此前没有平台纳管的版本，记为跳过而不是失败。
                decisions.add(new RollbackDecision(target, false,
                        "首次发布，该目标没有平台纳管的历史版本", null));
                continue;
            }
            String version = target.getOldVersion();
            Boolean available = availabilityCache.computeIfAbsent(version,
                    key -> historyArtifactAvailable(projectId, key));
            if (!Boolean.TRUE.equals(available)) {
                decisions.add(new RollbackDecision(target, false,
                        "历史版本 " + version + " 的产物已不存在，跳过回滚", version));
                continue;
            }
            decisions.add(new RollbackDecision(target, true, null, null));
        }
        return decisions;
    }

    /**
     * 判断某个历史版本是否仍可被重新发布。
     *
     * <p>平台回滚不读目标机 exec/bak 里的手工备份包（那些包的命名只有时间戳、无法对应到平台版本），
     * 而是按版本记录重新分发产物，因此「能不能回滚」等价于「这个版本的产物还拿不拿得到」：</p>
     * <ul>
     *   <li>DOCKER：只需镜像标签，镜像仍在仓库即可重新 pull；</li>
     *   <li>JAR / STATIC：需要构建机 ID 与产物路径，二者缺一就没法建立 SFTP 下载。</li>
     * </ul>
     *
     * @param projectId 项目主键
     * @param version   历史版本号
     * @return true 表示该版本仍可重新发布
     */
    private boolean historyArtifactAvailable(String projectId, String version) {
        ReleaseRecord release = releaseRecordMapper.selectOne(new LambdaQueryWrapper<ReleaseRecord>()
                .eq(ReleaseRecord::getProjectId, projectId)
                .eq(ReleaseRecord::getVersion, version)
                .orderByDesc(ReleaseRecord::getCreateTime)
                .last("LIMIT 1"));
        if (release == null) {
            return false;
        }
        RuntimeType runtimeType = RuntimeType.fromCode(release.getRuntimeType());
        if (runtimeType == RuntimeType.DOCKER) {
            return StringUtils.hasText(release.getImageTag());
        }
        return StringUtils.hasText(release.getBuildServerId()) && StringUtils.hasText(release.getArtifactPath());
    }

    /**
     * 把目标标记为「回滚跳过」并写明原因。
     *
     * @param target 目标实体
     * @param reason 跳过原因（面向页面展示）
     */
    private void skipForRollback(DeploymentTarget target, String reason) {
        target.setStatus(DeploymentTargetStatus.SKIPPED);
        target.setErrorMessage(truncate(reason));
        deploymentTargetMapper.updateById(target);
    }

    /**
     * 查询数据库实体（部署）。
     *
     * @param id 部署主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private Deployment getRequiredEntityById(String id) {
        Deployment entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署记录不存在");
        }
        return entity;
    }

    /**
     * 查询数据库实体（部署目标）。
     *
     * @param id 目标主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private DeploymentTarget getRequiredTargetById(String id) {
        DeploymentTarget entity = deploymentTargetMapper.selectById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署目标不存在");
        }
        return entity;
    }

    /**
     * 查询一次部署的全部目标实体。
     *
     * @param deploymentId 部署主键
     * @return 目标实体列表，按创建顺序升序
     */
    private List<DeploymentTarget> listTargetsEntity(String deploymentId) {
        return deploymentTargetMapper.selectList(new LambdaQueryWrapper<DeploymentTarget>()
                .eq(DeploymentTarget::getDeploymentId, deploymentId)
                .orderByAsc(DeploymentTarget::getCreateTime));
    }

    /**
     * 解析部署策略：请求覆盖优先，其次环境默认，都没有时退化为单目标。
     *
     * @param requested   请求传入的策略编码，允许为空
     * @param environment 目标环境，提供默认策略
     * @return 合法的策略编码
     * @throws DeployException 请求传入的策略编码不受支持时抛出
     */
    private String resolveStrategy(String requested, Environment environment) {
        if (StringUtils.hasText(requested)) {
            if (DeployStrategy.fromCode(requested) == null) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "部署策略必须为 SINGLE_TARGET、ALL_AT_ONCE 或 ROLLING，当前为 " + requested);
            }
            return requested;
        }
        String defaultStrategy = environment.getDeployStrategy();
        if (DeployStrategy.fromCode(defaultStrategy) != null) {
            return defaultStrategy;
        }
        return DeployStrategy.SINGLE_TARGET.getCode();
    }

    /**
     * 按策略裁剪目标列表。
     *
     * <p>单目标策略只发一台：优先取主目标（is_primary=1），没有主目标时取发布顺序最靠前的一台，
     * 保证「一个环境一台机器」的语义即使配置有歧义也能得到确定结果。
     * 全量与滚动策略使用全部启用目标。</p>
     *
     * @param bindings 该项目环境下已启用的目标绑定，已按发布顺序升序
     * @param strategy 部署策略编码
     * @return 本次实际参与发布的目标绑定
     */
    private List<ProjectEnvironmentTarget> selectByStrategy(List<ProjectEnvironmentTarget> bindings, String strategy) {
        if (!DeployStrategy.SINGLE_TARGET.getCode().equals(strategy)) {
            return bindings;
        }
        for (ProjectEnvironmentTarget binding : bindings) {
            if (binding.getIsPrimary() != null && binding.getIsPrimary() == 1) {
                return List.of(binding);
            }
        }
        return List.of(bindings.get(0));
    }

    /**
     * 推断该项目环境下每台服务器当前生效的版本。
     *
     * <p>回溯最近若干次历史部署（含成功与已回滚），按时间从新到旧遍历：
     * 成功的部署让机器跑到 new_version，已回滚的部署让机器回到 old_version。
     * 每台机器只取第一次命中的结果，即它当前真正在跑的版本。</p>
     *
     * @param projectId     项目主键
     * @param environmentId 环境主键
     * @return 服务器 ID 到当前版本的映射；值为空串/空表示该机器尚无平台纳管版本
     */
    private Map<String, String> resolveCurrentVersions(String projectId, String environmentId) {
        // 用普通条件构造器而不是链式包装：这里需要 last("LIMIT n") 收敛扫描范围，
        // 链式包装的 last 语义不稳定，显式构造再交给 ServiceImpl.list 更可控。
        LambdaQueryWrapper<Deployment> wrapper = new LambdaQueryWrapper<Deployment>()
                .eq(Deployment::getProjectId, projectId)
                .eq(Deployment::getEnvironmentId, environmentId)
                .in(Deployment::getStatus, DeploymentStatus.SUCCESS, DeploymentStatus.ROLLED_BACK)
                .orderByDesc(Deployment::getCreateTime)
                .last("LIMIT " + OLD_VERSION_SCAN_LIMIT);
        List<Deployment> recent = list(wrapper);
        Map<String, String> versions = new HashMap<>();
        for (Deployment past : recent) {
            for (DeploymentTarget target : listTargetsEntity(past.getId())) {
                // 只有真正发布成功过的目标才算「机器在跑这个版本」。
                if (target.getStatus() == null || target.getStatus() != DeploymentTargetStatus.SUCCESS) {
                    continue;
                }
                if (versions.containsKey(target.getServerId())) {
                    continue;
                }
                // 已回滚的部署：机器最终跑的是回滚前的版本；否则跑的是本次发布的新版本。
                String effective = past.getStatus() != null && past.getStatus() == DeploymentStatus.ROLLED_BACK
                        ? target.getOldVersion() : target.getNewVersion();
                versions.put(target.getServerId(), effective);
            }
        }
        return versions;
    }

    /**
     * 创建驱动本次部署（或回滚）的 DEPLOY 异步任务。
     *
     * @param deploymentId 部署主键，作为任务 bizId
     * @return 刚创建的任务 VO
     */
    private TaskVo createDeployTask(String deploymentId) {
        TaskDto taskDto = new TaskDto();
        taskDto.setTaskType(TaskType.DEPLOY.getCode());
        taskDto.setBizId(deploymentId);
        return taskService.create(taskDto);
    }

    /**
     * 取消驱动该部署的未完成任务。
     *
     * <p>任务已结束（成功/失败/已取消）时任务服务会拒绝，这里忽略异常：
     * 部署的取消结论已经落库，不应该被任务的当前状态反过来阻断。</p>
     *
     * @param deploymentId 部署主键
     */
    private void cancelDeployTask(String deploymentId) {
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getTaskType, TaskType.DEPLOY.getCode())
                .eq(Task::getBizId, deploymentId));
        for (Task task : tasks) {
            try {
                taskService.cancel(task.getId());
            } catch (DeployException ignored) {
                // 任务已处于终态，视为已收敛。
            }
        }
    }

    /**
     * 将数据库实体转换为接口响应 VO。
     *
     * <p>补充项目名、环境名、版本号、策略与状态文案、目标汇总计数以及关联任务标识。</p>
     *
     * @param entity 数据库实体
     * @return 可安全返回给 Controller 的响应对象
     */
    private DeploymentVo toVo(Deployment entity) {
        DeploymentVo vo = new DeploymentVo();
        BeanUtils.copyProperties(entity, vo);
        vo.setStatusText(DeploymentStatus.describe(entity.getStatus()));
        vo.setStrategyText(describeStrategy(entity.getStrategy()));
        // 关联名称按需查询，缺省不报错（项目/环境可能已被删除）。
        if (StringUtils.hasText(entity.getProjectId())) {
            Project project = projectMapper.selectById(entity.getProjectId());
            if (project != null) {
                vo.setProjectName(project.getName());
            }
        }
        if (StringUtils.hasText(entity.getEnvironmentId())) {
            Environment environment = environmentMapper.selectById(entity.getEnvironmentId());
            if (environment != null) {
                vo.setEnvironmentName(environment.getName());
            }
        }
        if (StringUtils.hasText(entity.getReleaseId())) {
            ReleaseRecord release = releaseRecordMapper.selectById(entity.getReleaseId());
            if (release != null) {
                vo.setVersion(release.getVersion());
            }
        }
        // 目标汇总：一次查出全部目标后在内存计数，避免为三个计数发三次 SQL。
        List<DeploymentTarget> targets = listTargetsEntity(entity.getId());
        int success = 0;
        int failed = 0;
        for (DeploymentTarget target : targets) {
            if (target.getStatus() != null && target.getStatus() == DeploymentTargetStatus.SUCCESS) {
                success++;
            }
            if (target.getStatus() != null && target.getStatus() == DeploymentTargetStatus.FAILED) {
                failed++;
            }
        }
        vo.setTargetTotal(targets.size());
        vo.setTargetSuccess(success);
        vo.setTargetFailed(failed);
        // 机器当前实际运行的版本。version（来自 release_id）是「这次动作针对的发布版本」的快照，
        // 回滚后也不能改写——改了就与发布日志、产物路径、release_record 全部对不上，
        // 所以当前版本另用一个字段承载，页面在已回滚时于版本号下方补一行「当前 xxx」。
        // 判定所需的目标数据上面已全部查出，这里不产生额外查询。
        vo.setCurrentVersion(resolveCurrentVersion(entity.getStatus(), targets));
        // 回滚可行性随列表一起返回：前端据此决定回滚按钮是置灰还是可点，
        // 并把不可回滚的原因直接显示在按钮上，避免「点了才被拒绝」。
        // 数量一并带出（判定时已算出，不产生额外查询），列表无需再为每行调预览接口。
        RollbackEvaluation evaluation = evaluateRollback(entity, targets);
        vo.setRollbackable(evaluation.rollbackable());
        vo.setRollbackBlockReason(evaluation.blockReason());
        vo.setRollbackTargetCount(evaluation.rollbackCount());
        vo.setRollbackSkippedCount(evaluation.skippedCount());
        // 关联出驱动本次部署的异步任务，便于前端直接拉取日志。
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getTaskType, TaskType.DEPLOY.getCode())
                .eq(Task::getBizId, entity.getId())
                .orderByDesc(Task::getCreateTime)
                .last("LIMIT 1"));
        if (task != null) {
            vo.setTaskId(task.getId());
            vo.setTaskNo(task.getTaskNo());
        }
        return vo;
    }

    /**
     * 求本次部署各目标机器当前实际运行的版本。
     *
     * <p>只统计 {@code status == SUCCESS} 的目标：被跳过的目标（首次发布没有可退版本）与失败的
     * 目标都没有真正换成新版本，不能混进来。机器当前跑哪个版本取决于本次动作是什么：
     * 发布成功取 {@code new_version}，回滚完成取 {@code old_version}——回滚就是把机器退回老版本。
     * 这与回滚预览里 currentVersion/targetVersion 的取法同源（见 {@code toRollbackTargetVo}）。</p>
     *
     * <p><b>只有全部成功目标的当前版本一致时才返回值。</b>多目标不一致时（部分回滚、部分首次发布）
     * 返回 null，页面宁可不显示——一个版本号会被误读成「所有机器都是它」，
     * 逐台的真实版本到目标明细里看。</p>
     *
     * @param status  部署主任务状态，见 {@link DeploymentStatus}
     * @param targets 本次部署的全部目标
     * @return 各成功目标一致的当前版本；状态未终结、没有成功目标或各目标版本不一致时返回 null
     */
    private String resolveCurrentVersion(Integer status, List<DeploymentTarget> targets) {
        boolean rolledBack = status != null && status == DeploymentStatus.ROLLED_BACK;
        boolean deployed = status != null && status == DeploymentStatus.SUCCESS;
        // 排队中、发布中、回滚中都不给结论：此刻机器上的版本可能正处在切换中间态；
        // 失败与已取消同理，没有「当前版本」这一确定事实。
        if (!rolledBack && !deployed) {
            return null;
        }
        String current = null;
        for (DeploymentTarget target : targets) {
            if (target.getStatus() == null || target.getStatus() != DeploymentTargetStatus.SUCCESS) {
                continue;
            }
            String version = rolledBack ? target.getOldVersion() : target.getNewVersion();
            if (!StringUtils.hasText(version)) {
                continue;
            }
            if (current == null) {
                current = version;
            } else if (!current.equals(version)) {
                // 各目标版本不一致：返回 null 让页面不显示，避免以偏概全。
                return null;
            }
        }
        return current;
    }

    /**
     * 将目标实体转换为响应 VO。
     *
     * @param entity 目标实体
     * @return 含服务器名称、地址与状态文案的响应对象
     */
    private DeploymentTargetVo toTargetVo(DeploymentTarget entity) {
        DeploymentTargetVo vo = new DeploymentTargetVo();
        BeanUtils.copyProperties(entity, vo);
        vo.setStatusText(DeploymentTargetStatus.describe(entity.getStatus()));
        vo.setHealthStatusText(HealthStatus.describe(entity.getHealthStatus()));
        if (StringUtils.hasText(entity.getServerId())) {
            Server server = serverMapper.selectById(entity.getServerId());
            if (server != null) {
                vo.setServerName(server.getServerName());
                vo.setServerHost(server.getHost());
            }
        }
        return vo;
    }

    /**
     * 将回滚判定明细转换为预览 VO。
     *
     * <p>currentVersion 取目标行的 new_version：那是本次发布真正推上线的版本，也就是
     * 回滚前机器上正在跑的版本；targetVersion 取 old_version，即这次要退回去的版本。</p>
     *
     * @param decision 逐目标判定明细
     * @return 含服务器名称、地址与版本对照的预览对象
     */
    private RollbackTargetPreviewVo toRollbackTargetPreview(RollbackDecision decision) {
        DeploymentTarget target = decision.target();
        RollbackTargetPreviewVo vo = new RollbackTargetPreviewVo();
        vo.setTargetId(target.getId());
        vo.setServerId(target.getServerId());
        vo.setCurrentVersion(target.getNewVersion());
        vo.setTargetVersion(target.getOldVersion());
        vo.setRollbackable(decision.rollbackable());
        vo.setSkipReason(decision.skipReason());
        if (StringUtils.hasText(target.getServerId())) {
            Server server = serverMapper.selectById(target.getServerId());
            if (server != null) {
                vo.setServerName(server.getServerName());
                vo.setServerHost(server.getHost());
            }
        }
        return vo;
    }

    /**
     * 将策略编码转换为中文描述。
     *
     * @param code 策略编码，允许为空
     * @return 策略中文描述；未识别时返回原始编码
     */
    private String describeStrategy(String code) {
        DeployStrategy strategy = DeployStrategy.fromCode(code);
        return strategy == null ? code : strategy.getMessage();
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * <p>失败原因不适合作为等值过滤条件，因此不参与查询；默认按创建时间倒序，
     * 让最近发起的部署排在最前。</p>
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<Deployment> buildQueryWrapper(DeploymentDto condition) {
        LambdaQueryWrapper<Deployment> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper.orderByDesc(Deployment::getCreateTime);
        }
        wrapper.eq(StringUtils.hasText(condition.getProjectId()),
                Deployment::getProjectId, condition.getProjectId());
        wrapper.eq(StringUtils.hasText(condition.getEnvironmentId()),
                Deployment::getEnvironmentId, condition.getEnvironmentId());
        wrapper.eq(StringUtils.hasText(condition.getReleaseId()),
                Deployment::getReleaseId, condition.getReleaseId());
        wrapper.eq(condition.getStatus() != null, Deployment::getStatus, condition.getStatus());
        wrapper.orderByDesc(Deployment::getCreateTime);
        return wrapper;
    }

    /**
     * 截断失败原因到数据库列允许的长度上限。
     *
     * @param message 原始失败原因
     * @return 截断后的失败原因；入参为空时返回默认文案
     */
    private String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return "发布失败";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : message;
    }
}
