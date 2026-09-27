package com.lx.deploy.deployment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.common.constants.DeploymentTargetStatus;
import com.lx.deploy.common.constants.HealthStatus;
import com.lx.deploy.common.enums.project.DeployStrategy;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.deployment.executor.DeployContext;
import com.lx.deploy.deployment.mapper.DeploymentTargetMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.deployment.model.vo.DeploymentTargetVo;
import com.lx.deploy.deployment.model.vo.DeploymentVo;
import com.lx.deploy.deployment.model.vo.RollbackTargetPreviewVo;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Environment;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.task.mapper.TaskMapper;
import com.lx.deploy.task.model.Task;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 部署 VO 组装器：把部署实体、它的目标行与各类关联名称拼成对外响应对象。
 *
 * <p>这一块原先分散在 {@code DeploymentService} 尾部，约两百行纯粹的「读数据 + 拼字段」代码。
 * 它与 CRUD、状态流转没有业务耦合，却要把项目名、环境名、版本、目标计数、当前版本、
 * 回滚可行性、关联任务标识统统补齐——正因为<b>字段越加越多</b>，
 * 才更应该单独成类，让 {@code DeploymentService} 回到「业务编排」本身。</p>
 *
 * <p>组装过程中唯一带业务判断的字段是回滚可行性，它直接委托
 * {@link DeploymentRollbackService#evaluateRollback}，不做第二份实现：
 * 列表、详情、回滚预览三处看到的「能否回滚」必须是同一个结论。</p>
 *
 * @author lx
 * @since 2026-09-27 20:30:00
 */
@Service
public class DeploymentVoAssembler {

    /** 失败原因写入数据库前的长度上限，避免超长文本塞满 error_message。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;


    /** 部署目标 Mapper：读取本次部署的目标行用于汇总计数与版本推断。 */
    private final DeploymentTargetMapper deploymentTargetMapper;

    /** 项目 Mapper：补充项目名称。 */
    private final ProjectMapper projectMapper;

    /** 环境 Mapper：补充环境名称。 */
    private final EnvironmentMapper environmentMapper;

    /** 发布版本 Mapper：补充版本号。 */
    private final ReleaseRecordMapper releaseRecordMapper;

    /** 服务器 Mapper：补充目标明细里的服务器名称与地址。 */
    private final ServerMapper serverMapper;

    /** 任务 Mapper：关联出驱动本次部署的异步任务。 */
    private final TaskMapper taskMapper;

    /** 回滚判定服务：同一个判定结论同时供 VO 与回滚接口使用。 */
    private final DeploymentRollbackService rollbackService;

    /**
     * 注入组装所需的跨模块 Mapper 与回滚判定服务。
     *
     * @param deploymentTargetMapper 部署目标 Mapper
     * @param projectMapper          项目 Mapper
     * @param environmentMapper      环境 Mapper
     * @param releaseRecordMapper    发布版本 Mapper
     * @param serverMapper           服务器 Mapper
     * @param taskMapper             任务 Mapper
     * @param rollbackService        回滚判定服务
     */
    public DeploymentVoAssembler(DeploymentTargetMapper deploymentTargetMapper,
                                 ProjectMapper projectMapper,
                                 EnvironmentMapper environmentMapper,
                                 ReleaseRecordMapper releaseRecordMapper,
                                 ServerMapper serverMapper,
                                 TaskMapper taskMapper,
                                 DeploymentRollbackService rollbackService) {
        this.deploymentTargetMapper = deploymentTargetMapper;
        this.projectMapper = projectMapper;
        this.environmentMapper = environmentMapper;
        this.releaseRecordMapper = releaseRecordMapper;
        this.serverMapper = serverMapper;
        this.taskMapper = taskMapper;
        this.rollbackService = rollbackService;
    }

    /**
     * 查询一次部署的全部目标实体，按发布顺序升序。
     *
     * @param deploymentId 部署主键
     * @return 目标实体列表
     */
    public List<DeploymentTarget> listTargetsEntity(String deploymentId) {
        return deploymentTargetMapper.selectList(new LambdaQueryWrapper<DeploymentTarget>()
                .eq(DeploymentTarget::getDeploymentId, deploymentId)
                .orderByAsc(DeploymentTarget::getDeployOrder)
                .orderByAsc(DeploymentTarget::getCreateTime));
    }

    /**
     * 将部署记录转换为接口响应 VO。
     *
     * <p>补充项目名、环境名、版本号、策略与状态文案、目标汇总计数以及关联任务标识。</p>
     *
     * @param entity 数据库实体
     * @return 可安全返回给 Controller 的响应对象
     */
    public DeploymentVo toVo(Deployment entity) {
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
        DeploymentRollbackService.RollbackEvaluation evaluation = rollbackService.evaluateRollback(entity, targets);
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
    public String resolveCurrentVersion(Integer status, List<DeploymentTarget> targets) {
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
            // 本次动作完成后机器上跑的版本：发布成功是 new_version，回滚完成是 old_version。
            // 与 DeploymentTargetPlanner 同源，必须取 targetVersionOf——
            // currentVersionOf 是「本次被换掉的版本」，用在这里是反的。
            String version = DeployContext.targetVersionOf(target, rolledBack);
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
    public DeploymentTargetVo toTargetVo(DeploymentTarget entity) {
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
    public RollbackTargetPreviewVo toRollbackTargetPreview(DeploymentRollbackService.RollbackDecision decision) {
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
    public String describeStrategy(String code) {
        DeployStrategy strategy = DeployStrategy.fromCode(code);
        return strategy == null ? code : strategy.getMessage();
    }

    /**
     * 截断失败原因到数据库列允许的长度上限。
     *
     * @param message 原始失败原因
     * @return 截断后的失败原因；入参为空时返回默认文案
     */
    public String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return "发布失败";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : message;
    }
}
