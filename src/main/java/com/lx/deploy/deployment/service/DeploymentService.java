package com.lx.deploy.deployment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.aiops.model.AiopsEventType;
import com.lx.deploy.aiops.service.AiopsEventPublisher;
import com.lx.deploy.aiops.service.AiopsSilenceService;
import com.lx.deploy.common.util.TimeRangeSupport;
import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.common.constants.DeploymentTargetStatus;
import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.constants.HealthStatus;
import com.lx.deploy.common.constants.ReleaseStatus;
import com.lx.deploy.common.enums.project.DeployStrategy;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.common.security.OwnerGuard;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.mapper.DeploymentTargetMapper;
import com.lx.deploy.deployment.executor.DeployContext;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.deployment.model.dto.DeploymentDto;
import com.lx.deploy.deployment.model.vo.DeploymentTargetVo;
import com.lx.deploy.deployment.model.vo.DeploymentVo;
import com.lx.deploy.deployment.model.vo.RollbackPreviewVo;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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

    /** 回滚判定服务：发起回滚与回滚预览共用同一份前置判定。 */
    private final DeploymentRollbackService rollbackService;

    /** VO 组装器：把实体与各类关联名称拼成对外响应对象。 */
    private final DeploymentVoAssembler voAssembler;

    /** 发布目标展开器：解析策略、裁剪目标并推断每台机器当前版本。 */
    private final DeploymentTargetPlanner targetPlanner;

    /** AIops 事件发布器：把发布生命周期结论同步给 AIops（失败不影响本地状态）。 */
    private final AiopsEventPublisher aiopsEventPublisher;

    /** AIops 静默窗服务：人工取消时立即关窗，别让窗口挂到自然过期。 */
    private final AiopsSilenceService aiopsSilenceService;

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
     * @param rollbackService                回滚判定服务
     * @param voAssembler                    VO 组装器
     * @param targetPlanner                  发布目标展开器
     * @param aiopsEventPublisher            AIops 事件发布器
     * @param aiopsSilenceService            AIops 静默窗服务
     */
    public DeploymentService(DeploymentTargetMapper deploymentTargetMapper,
                             ProjectMapper projectMapper,
                             EnvironmentMapper environmentMapper,
                             ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper,
                             ReleaseRecordMapper releaseRecordMapper,
                             ServerMapper serverMapper,
                             TaskMapper taskMapper,
                             TaskService taskService,
                             ArtifactProbe artifactProbe,
                             DeploymentRollbackService rollbackService,
                             DeploymentVoAssembler voAssembler,
                             DeploymentTargetPlanner targetPlanner,
                             AiopsEventPublisher aiopsEventPublisher,
                             AiopsSilenceService aiopsSilenceService) {
        this.deploymentTargetMapper = deploymentTargetMapper;
        this.projectMapper = projectMapper;
        this.environmentMapper = environmentMapper;
        this.projectEnvironmentTargetMapper = projectEnvironmentTargetMapper;
        this.releaseRecordMapper = releaseRecordMapper;
        this.serverMapper = serverMapper;
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.artifactProbe = artifactProbe;
        this.rollbackService = rollbackService;
        this.voAssembler = voAssembler;
        this.targetPlanner = targetPlanner;
        this.aiopsEventPublisher = aiopsEventPublisher;
        this.aiopsSilenceService = aiopsSilenceService;
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
        List<DeploymentVo> records = page.getRecords().stream().map(voAssembler::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配部署记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<DeploymentVo> listByCondition(DeploymentDto condition) {
        return list(buildQueryWrapper(condition)).stream().map(voAssembler::toVo).toList();
    }

    /**
     * 根据主键查询并返回部署响应对象（含目标汇总与关联任务标识）。
     *
     * @param id 部署主键
     * @return 含项目名、环境名、版本号、状态文案与任务标识的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public DeploymentVo getRequiredById(String id) {
        return voAssembler.toVo(getRequiredEntityById(id));
    }

    /**
     * 查询一次部署的全部目标明细。
     *
     * @param deploymentId 部署主键
     * @return 目标响应列表，按部署时固化的发布顺序升序（与执行器的执行顺序同源）
     * @throws DeployException 部署不存在时抛出
     */
    public List<DeploymentTargetVo> listTargets(String deploymentId) {
        getRequiredEntityById(deploymentId);
        return deploymentTargetMapper.selectList(new LambdaQueryWrapper<DeploymentTarget>()
                .eq(DeploymentTarget::getDeploymentId, deploymentId)
                .orderByAsc(DeploymentTarget::getDeployOrder)
                .orderByAsc(DeploymentTarget::getCreateTime))
                .stream().map(voAssembler::toTargetVo).toList();
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
        // 发版约束：只有项目负责人能发起部署，防止项目被随便发版。
        OwnerGuard.assertOwner(project.getOwnerId(), project.getOwnerName(), project.getCode());
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
        String strategy = targetPlanner.resolveStrategy(dto.getStrategy(), environment);

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
        List<ProjectEnvironmentTarget> selected = targetPlanner.selectByStrategy(bindings, strategy);

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
        Map<String, String> currentVersions = targetPlanner.resolveCurrentVersions(project.getId(), environment.getId());

        // 落主任务，初始为排队，策略与操作者在此固化。
        Deployment deployment = new Deployment();
        deployment.setProjectId(project.getId());
        deployment.setEnvironmentId(environment.getId());
        deployment.setReleaseId(release.getId());
        deployment.setStrategy(strategy);
        // 强制覆盖开关：前端未传时按 false 处理（字段 NOT NULL DEFAULT 0，不能插 NULL）。
        deployment.setForceOverwriteConfig(Boolean.TRUE.equals(dto.getForceOverwriteConfig()));
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
            // 发布顺序随快照固化：bindings 已按 deploy_order 升序取出，这里的值就是执行顺序。
            // 必须在创建时写入——执行器若改按 create_time 排序，同一秒插入的多条目标会并列，
            // 顺序会退化成随机 UUID 顺序（详见 DeploymentTarget.deployOrder 的说明）。
            target.setDeployOrder(binding.getDeployOrder());
            target.setStatus(DeploymentTargetStatus.QUEUED);
            target.setOldVersion(currentVersions.get(server.getId()));
            target.setNewVersion(release.getVersion());
            target.setImageTag(release.getImageTag());
            deploymentTargetMapper.insert(target);
        }

        // 生成驱动发布的异步任务，bizId 指向 deployment 主键。
        TaskVo taskVo = createDeployTask(deployment.getId());
        DeploymentVo vo = voAssembler.toVo(deployment);
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
            List<DeploymentTarget> targets = voAssembler.listTargetsEntity(id);
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
        // 换强制覆盖开关：前端显式传了该字段且值与原记录不同才同步，不传则保留原值（创建时已落库）。
        Boolean nextOverwrite = dto.getForceOverwriteConfig();
        if (nextOverwrite != null && !nextOverwrite.equals(deployment.getForceOverwriteConfig())) {
            deployment.setForceOverwriteConfig(nextOverwrite);
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
     * 页面上也找不到任何说明。现在改为在入口处按 {@link DeploymentRollbackService#evaluateRollback} 的结论抛异常，
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
        // 发版约束：回滚同样只能由项目负责人发起。
        assertProjectOwner(deployment.getProjectId());
        List<DeploymentTarget> targets = voAssembler.listTargetsEntity(id);
        // 入口先判定：状态是否允许、有没有历史版本、历史版本的产物是否还在。
        DeploymentRollbackService.RollbackEvaluation evaluation = rollbackService.evaluateRollback(deployment, targets);
        if (!evaluation.rollbackable()) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, evaluation.blockReason());
        }
        // 可回滚的目标重置为排队，等待执行器按 old_version 重新发布；
        // 退不回去的目标记为跳过并写清原因，而不是判成失败——机器上本来就没有可退的版本。
        for (DeploymentRollbackService.RollbackDecision decision : evaluation.decisions()) {
            DeploymentTarget target = decision.target();
            if (!decision.rollbackable()) {
                rollbackService.skipForRollback(target, voAssembler.truncate(decision.skipReason()));
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
        DeploymentVo vo = voAssembler.toVo(deployment);
        vo.setTaskId(taskVo.getId());
        vo.setTaskNo(taskVo.getTaskNo());
        return vo;
    }

    /**
     * 预览一次回滚会发生什么，不做任何状态变更。
     *
     * <p>回答三个问题：总共几台、哪几台会退回历史版本、哪几台会被跳过以及为什么。
     * 判定结果与 {@link #rollback(String)} 完全同源（同一个 {@link DeploymentRollbackService#evaluateRollback}），
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
        List<DeploymentTarget> targets = voAssembler.listTargetsEntity(id);
        DeploymentRollbackService.RollbackEvaluation evaluation = rollbackService.evaluateRollback(deployment, targets);
        RollbackPreviewVo vo = new RollbackPreviewVo();
        vo.setRollbackable(evaluation.rollbackable());
        vo.setBlockReason(evaluation.blockReason());
        vo.setRollbackCount(evaluation.rollbackCount());
        vo.setSkippedCount(evaluation.skippedCount());
        vo.setTargetTotal(targets.size());
        vo.setTargets(evaluation.decisions().stream().map(voAssembler::toRollbackTargetPreview).toList());
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
        // 发版约束：取消同样只能由项目负责人发起。
        assertProjectOwner(deployment.getProjectId());
        if (!DeploymentStatus.canCancel(deployment.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "当前状态为 " + DeploymentStatus.describe(deployment.getStatus()) + "，不允许取消");
        }
        deployment.setStatus(DeploymentStatus.CANCELED);
        deployment.setFinishedAt(LocalDateTime.now());
        updateById(deployment);
        // 未结束的目标随主任务取消。
        for (DeploymentTarget target : voAssembler.listTargetsEntity(id)) {
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
        // 取消同样要关窗并上报：窗口挂到自然过期，会让这次「没发成功」的发布继续压着告警，
        // 期间的真实故障就被掩掉了。
        aiopsSilenceService.closeForDeployment(id, "人工取消发布");
        aiopsEventPublisher.publish(AiopsEventType.CANCELLED, deployment, null, null, "人工取消发布");
        return voAssembler.toVo(deployment);
    }

    /**
     * 校验当前登录用户是否为指定项目的负责人。
     *
     * <p>构建/发布/回滚/取消共用同一发版约束（见 {@code OwnerGuard}）：项目负责人才能操作。
     * 项目不存在时按「未指派负责人」放行，避免历史脏数据把入口彻底锁死。</p>
     *
     * @param projectId 项目主键
     */
    private void assertProjectOwner(String projectId) {
        Project project = projectMapper.selectById(projectId);
        OwnerGuard.assertOwner(
                project == null ? null : project.getOwnerId(),
                project == null ? null : project.getOwnerName(),
                project == null ? projectId : project.getCode());
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
        aiopsEventPublisher.publish(AiopsEventType.STARTED, deployment, null, null, null);
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
        target.setErrorMessage(voAssembler.truncate(errorMessage));
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
        target.setErrorMessage(voAssembler.truncate(reason));
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
        aiopsEventPublisher.publish(AiopsEventType.SUCCEEDED, deployment, null, null, null);
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
        deployment.setErrorMessage(voAssembler.truncate(errorMessage));
        updateById(deployment);
        aiopsEventPublisher.publish(AiopsEventType.FAILED, deployment, null, null, errorMessage);
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
        aiopsEventPublisher.publish(AiopsEventType.ROLLBACK_SUCCEEDED, deployment, null, null, null);
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
        deployment.setErrorMessage(voAssembler.truncate("回滚失败：" + errorMessage));
        updateById(deployment);
        aiopsEventPublisher.publish(AiopsEventType.ROLLBACK_FAILED, deployment, null, null, errorMessage);
    }

    // ==================== 私有辅助 ====================

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
        // 创建时间区间：闭区间，开始晚于截止由 TimeRangeSupport 直接判参数错误。
        TimeRangeSupport.apply(wrapper, Deployment::getCreateTime, condition);
        wrapper.orderByDesc(Deployment::getCreateTime);
        return wrapper;
    }

}
