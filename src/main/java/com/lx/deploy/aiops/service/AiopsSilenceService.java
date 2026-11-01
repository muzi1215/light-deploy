package com.lx.deploy.aiops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.deploy.aiops.client.AiopsClient;
import com.lx.deploy.aiops.client.AiopsDtos.Scope;
import com.lx.deploy.aiops.client.AiopsDtos.ServerRef;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceCreateRequest;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceData;
import com.lx.deploy.aiops.config.AiopsProperties;
import com.lx.deploy.aiops.util.AiopsTime;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.mapper.DeploymentTargetMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Environment;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 发布静默窗服务：在发布期间把目标机的「预期内告警」压下去，发布结束立刻恢复。
 *
 * <p><b>为什么需要：</b>重启服务、替换 jar、重建容器，必然触发 AIops 的可用性告警。
 * 没有静默窗，每次发布都会推一堆告警给值班同学，几次之后大家就会养成「发布期间的告警先不管」的习惯——
 * 真正的故障恰好就被这个习惯漏掉了。静默窗把「发布引起的、预期内的」与「真问题」分开：
 * 窗内只压制通知，告警照常入库，窗外的告警一条不少。</p>
 *
 * <p><b>安全边界（三重）：</b></p>
 * <ol>
 *   <li>精确到主机：只为本次发布的目标机开窗，同环境的其他机器照常告警；</li>
 *   <li>带自动过期：到期时间按「预计发布时长 + 目标数余量」估算，AIops 侧还有硬上限兜底；
 *       即使平台在发布中途崩溃，窗口也会自己到期，不会形成永久静默；</li>
 *   <li>服务端否决权：磁盘、内核、数据库这类底线告警由 AIops 侧硬性排除，
 *       平台即使把它们写进请求也不会生效。</li>
 * </ol>
 *
 * <p><b>降级原则：</b>{@code failOpen=true}（默认）时建窗失败只降级不阻断——AIops 挂了不能连带
 * 让发布也发不出去（AGENTS.md 容灾底线：服务可用性高于平台功能完整）；设为 false 时才阻断发布。</p>
 *
 * <p><b>两级开关（缺一不可）：</b></p>
 * <ol>
 *   <li>{@code light-deploy.aiops.enabled}＝平台级总闸，关掉后整个联动能力（建窗 / 关窗 / 事件推送 /
 *       补偿任务）全部停摆，此时环境上的开关怎么设都不会生效；</li>
 *   <li>{@code environment.aiops_enabled}＝环境级开关，决定「这个环境的发布要不要建窗」。
 *       典型用法：生产环境建窗、测试环境不建窗（测试环境本来就一天发十次，告警压不压无所谓）。</li>
 * </ol>
 * <p>换句话说：总闸说「平台有没有这个能力」，环境开关说「这台业务要不要用这个能力」。
 * 两级都开着才建窗，任一级关着都静默跳过（不报错、不打【注意】，因为这是人为配置而非故障）。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
@Slf4j
@Service
public class AiopsSilenceService {

    /** 幂等键前缀：同一条部署重复建窗时 AIops 回放已有窗口，不会开出第二个。 */
    private static final String KEY_PREFIX = "light-deploy:silence:";

    /** AIops 侧静默窗硬上限（分钟），与对端 SILENCE_MAX_MINUTES 保持一致。 */
    private static final int HARD_MAX_MINUTES = 120;

    /** 每多一台目标增加的余量（分钟）：多机滚动发布的总时长与目标数正相关。 */
    private static final int PER_TARGET_MINUTES = 3;

    private final AiopsProperties properties;
    private final AiopsClient client;
    private final DeploymentMapper deploymentMapper;
    private final DeploymentTargetMapper deploymentTargetMapper;
    private final ServerMapper serverMapper;
    private final ProjectMapper projectMapper;
    private final EnvironmentMapper environmentMapper;

    public AiopsSilenceService(AiopsProperties properties,
                              AiopsClient client,
                              DeploymentMapper deploymentMapper,
                              DeploymentTargetMapper deploymentTargetMapper,
                              ServerMapper serverMapper,
                              ProjectMapper projectMapper,
                              EnvironmentMapper environmentMapper) {
        this.properties = properties;
        this.client = client;
        this.deploymentMapper = deploymentMapper;
        this.deploymentTargetMapper = deploymentTargetMapper;
        this.serverMapper = serverMapper;
        this.projectMapper = projectMapper;
        this.environmentMapper = environmentMapper;
    }

    /**
     * 建窗结果。
     *
     * @param opened   是否真的开出窗口（未启用、已存在、降级时为 false）
     * @param degraded 是否因为 AIops 不可用而降级
     * @param silenceId 静默窗 ID，未开窗时为空
     * @param message  面向任务日志的说明文案
     */
    public record SilenceOutcome(boolean opened, boolean degraded, String silenceId, String message) {

        /**
         * 未开窗（未启用 / 已存在）。
         *
         * @param message 说明文案
         * @return 未开窗结果
         */
        public static SilenceOutcome skipped(String message) {
            return new SilenceOutcome(false, false, null, message);
        }
    }

    /**
     * 关窗结果。
     *
     * @param hadWindow 本次部署是否开过静默窗（没开过就不该发「已关窗」事件）
     * @param closed    是否已成功关闭；关窗失败时为 false，等待自动过期
     * @param silenceId 静默窗 ID
     */
    public record CloseOutcome(boolean hadWindow, boolean closed, String silenceId) {

        /**
         * 没有窗口可关。
         *
         * @return 空结果
         */
        public static CloseOutcome none() {
            return new CloseOutcome(false, true, null);
        }
    }

    /**
     * 为本次部署开静默窗。
     *
     * <p>只覆盖本次部署的目标机；回滚与发布共用同一条链路，因此回滚也会开窗——
     * 回滚同样会重启服务，同样会产生预期内告警。</p>
     *
     * <p><b>开关判定顺序：</b>平台总闸 → 是否已有窗 → 环境开关 → 目标机是否存在。
     * 环境不开窗属于「配置决定」，返回 {@link SilenceOutcome#skipped} 而不是降级，
     * 因此任务日志里不会出现【注意】，也不会推送 PREPARED 事件。</p>
     *
     * @param deployment 部署主记录
     * @return 建窗结果；降级时 {@code degraded=true}
     * @throws DeployException {@code failOpen=false} 且建窗失败时抛出
     */
    public SilenceOutcome openForDeployment(Deployment deployment) {
        // 一级开关：平台总闸。关掉后联动能力整体停摆，环境上的开关不再有意义。
        if (!properties.isEnabled()) {
            return SilenceOutcome.skipped("AIops 联动未启用，跳过静默窗");
        }
        if (StringUtils.hasText(deployment.getAiopsSilenceId())) {
            return SilenceOutcome.skipped("本次部署已存在静默窗（" + deployment.getAiopsSilenceId() + "），不重复创建");
        }
        // 二级开关：环境级。先取环境（下面组装 Scope 也要用），再判该环境是否允许建窗。
        Environment environment = environmentMapper.selectById(deployment.getEnvironmentId());
        if (environment == null) {
            return SilenceOutcome.skipped("部署所属环境已不存在，跳过静默窗");
        }
        if (environment.getAiopsEnabled() == null || environment.getAiopsEnabled() != 1) {
            return SilenceOutcome.skipped("环境「" + environment.getName() + "」未开启 AIops 联动，跳过静默窗");
        }
        List<Server> servers = listTargetServers(deployment.getId());
        if (servers.isEmpty()) {
            return SilenceOutcome.skipped("没有可用的目标服务器，跳过静默窗");
        }
        Project project = projectMapper.selectById(deployment.getProjectId());
        String idempotencyKey = KEY_PREFIX + deployment.getId();
        int ttlMinutes = resolveTtlMinutes(servers.size());
        SilenceCreateRequest request = new SilenceCreateRequest(
                idempotencyKey,
                buildReason(deployment, project),
                new Scope(project == null ? null : project.getCode(),
                        environment.getCode(),
                        servers.stream().map(s -> new ServerRef(s.getId(), s.getHost())).toList()),
                properties.getAlertCategories(),
                properties.getNeverSilence(),
                AiopsTime.iso(null),
                AiopsTime.isoAfterMinutes(ttlMinutes));
        try {
            SilenceData data = client.createSilence(request);
            deployment.setAiopsSilenceId(data.silenceId());
            deploymentMapper.updateById(deployment);
            log.info("[AIops] 静默窗已开 - 部署={}, 窗口={}, 目标机={}, 到期={}, 生效类型={}, 否决类型={}",
                    deployment.getId(), data.silenceId(), servers.size(), data.expiresAt(),
                    data.appliedCategories(), data.ignoredCategories());
            return new SilenceOutcome(true, false, data.silenceId(),
                    "已开静默窗 " + data.silenceId() + "，覆盖 " + servers.size() + " 台目标机，到期 "
                            + data.expiresAt()
                            + (data.duplicated() != null && data.duplicated() ? "（幂等复用）" : ""));
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[AIops] 建静默窗失败 - 部署={}, failOpen={}, 原因={}",
                    deployment.getId(), properties.isFailOpen(), reason);
            if (!properties.isFailOpen()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "创建 AIops 静默窗失败，已按配置阻断发布：" + reason);
            }
            return new SilenceOutcome(false, true, null,
                    "AIops 不可用，本次发布不静默（告警照常推送）：" + reason);
        }
    }

    /**
     * 关闭本次部署的静默窗。
     *
     * <p>尽力而为：关窗失败只记日志，不抛异常也不阻断收尾。原因是窗口本身带自动过期，
     * 最坏情况是多静默几分钟，而不是发布流程被卡住。</p>
     *
     * @param deploymentId 部署主键
     * @param reason       关闭原因，写进静默窗留痕
     * @return 关窗结果；没开过窗时 {@code hadWindow=false}
     */
    public CloseOutcome closeForDeployment(String deploymentId, String reason) {
        if (!properties.isEnabled() || !StringUtils.hasText(deploymentId)) {
            return CloseOutcome.none();
        }
        Deployment deployment = deploymentMapper.selectById(deploymentId);
        if (deployment == null || !StringUtils.hasText(deployment.getAiopsSilenceId())) {
            return CloseOutcome.none();
        }
        try {
            client.closeSilence(deployment.getAiopsSilenceId(), reason);
            log.info("[AIops] 静默窗已关闭 - 部署={}, 窗口={}, 原因={}",
                    deploymentId, deployment.getAiopsSilenceId(), reason);
            return new CloseOutcome(true, true, deployment.getAiopsSilenceId());
        } catch (RuntimeException e) {
            log.warn("[AIops] 关闭静默窗失败，等待自动过期 - 部署={}, 窗口={}, 原因={}",
                    deploymentId, deployment.getAiopsSilenceId(), e.getMessage());
            return new CloseOutcome(true, false, deployment.getAiopsSilenceId());
        }
    }

    /**
     * 读取本次部署的目标服务器档案，用于建窗时给出主机地址。
     *
     * <p>主机地址是跨平台对齐的关键：平台与 AIops 各有独立的服务器主键，
     * 只有主机 IP 是两边共有的事实，静默窗按它匹配告警（见 AIops 侧 match_silence）。</p>
     *
     * @param deploymentId 部署主键
     * @return 目标服务器列表，可能为空
     */
    private List<Server> listTargetServers(String deploymentId) {
        List<DeploymentTarget> targets = deploymentTargetMapper.selectList(
                new LambdaQueryWrapper<DeploymentTarget>()
                        .eq(DeploymentTarget::getDeploymentId, deploymentId));
        List<String> serverIds = targets.stream()
                .map(DeploymentTarget::getServerId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (serverIds.isEmpty()) {
            return List.of();
        }
        List<Server> servers = serverMapper.selectBatchIds(serverIds);
        List<Server> result = new ArrayList<>();
        for (Server server : servers) {
            if (StringUtils.hasText(server.getHost())) {
                result.add(server);
            }
        }
        return result;
    }

    /**
     * 计算静默窗时长。
     *
     * <p>基线时长 + 每台目标的余量，并压到 AIops 的硬上限以内——
     * 超出部分会被对端截断，与其让对端告警，不如这里就算准。</p>
     *
     * @param targetCount 目标机数量
     * @return 时长（分钟）
     */
    private int resolveTtlMinutes(int targetCount) {
        int minutes = properties.getSilenceTtlMinutes() + PER_TARGET_MINUTES * Math.max(0, targetCount - 1);
        return Math.min(minutes, HARD_MAX_MINUTES);
    }

    /**
     * 组装静默原因，会展示在 AIops 的静默窗与告警详情里。
     *
     * @param deployment 部署主记录
     * @param project    项目，允许为空
     * @return 原因文案
     */
    private String buildReason(Deployment deployment, Project project) {
        String projectName = project == null ? deployment.getProjectId() : project.getName();
        return StringUtils.hasText(deployment.getOperatorName())
                ? "发布中：" + projectName + " 由 " + deployment.getOperatorName() + " 发起发布"
                : "发布中：" + projectName;
    }
}
