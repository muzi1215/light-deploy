package com.lx.deploy.deployment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.common.constants.DeploymentTargetStatus;
import com.lx.deploy.common.constants.ReleaseStatus;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.deployment.mapper.DeploymentTargetMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署回滚判定服务。
 *
 * <p>回答一个问题：这次部署现在能不能回滚，以及哪些目标会真的退回去。
 * 这部分原先与 CRUD、状态回调一起挤在 {@code DeploymentService} 里，而它是全模块
 * <b>最精密、最不允许被顺手改动</b>的一块——AGENTS.md §24.5 的三档前置判定
 * 「状态允许 / 有历史版本 / 历史版本产物仍在」缺一档就会把机器推到无法回退的状态。</p>
 *
 * <p>拆出来的直接收益是把精密逻辑与日常 CRUD 物理隔离：改「发布列表多返回一个字段」
 * 不会误触这里的判定顺序，改判定也不会在几百行的文件里被淹没。</p>
 *
 * <p><b>判定与执行必须同源</b>：回滚执行、回滚预览、列表与详情里的「可否回滚」标记，
 * 全部取 {@link #evaluateRollback} 的一份结论。因此本类只依赖 Mapper，不依赖
 * {@code DeploymentService}——单向依赖，避免双向引用把调用链绕成死结。</p>
 *
 * @author lx
 * @since 2026-09-27 20:30:00
 */
@Service
public class DeploymentRollbackService {

    /** 部署目标 Mapper：判定所需的 old_version 快照与跳过状态写回。 */
    private final DeploymentTargetMapper deploymentTargetMapper;

    /** 发布版本 Mapper：确认历史版本的产物是否仍可被重新分发。 */
    private final ReleaseRecordMapper releaseRecordMapper;

    /**
     * 注入回滚判定所需的数据访问依赖。
     *
     * @param deploymentTargetMapper 部署目标 Mapper
     * @param releaseRecordMapper    发布版本 Mapper
     */
    public DeploymentRollbackService(DeploymentTargetMapper deploymentTargetMapper,
                                     ReleaseRecordMapper releaseRecordMapper) {
        this.deploymentTargetMapper = deploymentTargetMapper;
        this.releaseRecordMapper = releaseRecordMapper;
    }

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
    public record RollbackDecision(DeploymentTarget target, boolean rollbackable,
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
    public record RollbackEvaluation(boolean rollbackable, String blockReason, int rollbackCount, int skippedCount,
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
    public RollbackEvaluation evaluateRollback(Deployment deployment, List<DeploymentTarget> targets) {
        if (!DeploymentStatus.canRollback(deployment.getStatus())) {
            return new RollbackEvaluation(false,
                    "当前状态为 " + DeploymentStatus.describe(deployment.getStatus()) + "，只有发布成功的部署允许回滚",
                    0, 0, List.of());
        }
        List<RollbackDecision> decisions = inspectTargets(deployment.getProjectId(), targets);
        int rollbackCount = 0;
        int skippedCount = 0;
        String firstReason = null;
        for (RollbackDecision decision : decisions) {
            if (decision.rollbackable()) {
                rollbackCount++;
                continue;
            }
            skippedCount++;
            if (firstReason == null && StringUtils.hasText(decision.skipReason())) {
                // 优先报「产物已不存在」：它比「首次发布」更需要使用者去处理产物。
                firstReason = decision.skipReason();
            }
        }
        if (rollbackCount > 0) {
            return new RollbackEvaluation(true, null, rollbackCount, skippedCount, decisions);
        }
        if (decisions.isEmpty()) {
            return new RollbackEvaluation(false, "本次发布没有变更过任何目标，没有可回滚的内容", 0, 0, decisions);
        }
        if (StringUtils.hasText(firstReason)) {
            return new RollbackEvaluation(false, firstReason + "，无法回滚", 0, skippedCount, decisions);
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
        Map<String, Availability> availabilityCache = new HashMap<>();
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
            Availability probe = availabilityCache.computeIfAbsent(version,
                    key -> probeRollbackArtifact(projectId, key));
            if (!probe.available()) {
                decisions.add(new RollbackDecision(target, false, probe.reason(), version));
                continue;
            }
            decisions.add(new RollbackDecision(target, true, null, null));
        }
        return decisions;
    }

    /**
     * 判断某个历史版本能否被重新发布，不可用时给出可读原因。
     *
     * <p>平台回滚按版本记录重新分发产物，正式来源是<b>构建机的产物持久目录</b>（见 AGENTS.md §24.10），
     * 因此「能不能回滚」等价于「这个版本的产物还拿不拿得到」。每档不可用的原因各自成文，不再笼统说
     * 「产物已不存在」——后者对使用者没有任何行动价值，「已被保留策略回收，请重新构建」才有。</p>
     *
     * <p><b>为什么必须看 status（2026-09-28 补）</b>：{@code ReleaseRetentionService} 会把超出
     * {@code project.retain_release_count} 的历史版本置为「已废弃」，并<b>删掉构建机上的产物目录</b>，
     * 但 {@code release_record.artifact_path} 这个字段的值仍然留着。只看字段非空会得出「可以回滚」
     * 的结论，用户点下去要等到 SFTP 下载阶段才失败，而那时部分目标可能已经被改过。
     * <b>宁可在按钮前判不能退，也不要让人点了才知道。</b></p>
     *
     * <p><b>DOCKER 不受 status 约束</b>：它的产物是镜像仓库里的 tag，不在构建机磁盘上，
     * 保留策略对它只有「置废弃标记」这一个动作——{@code ReleaseRetentionService.artifactDir}
     * 会因为 {@code buildServerId} 为空而返回 null，只标记废弃、不删任何东西。
     * 因此对 DOCKER 加 status 判定，等于无端砍掉一个镜像明明还在、本来能回滚的版本。</p>
     *
     * <p>目标机 exec/bak 里的备份不作为回滚来源：它在发布中途失败时由平台自动还原（应急副本），
     * 不是版本仓库。注意「因为备份名只有时间戳、无法对应平台版本才不用它」是过时理由——
     * 备份名现已内嵌平台版本号（{@code {jar名}_bak_{时间戳}_{版本号}.jar}），
     * JAR 缺少本机回滚快路径是<b>功能缺口</b>，不是不可行。</p>
     *
     * @param projectId 项目主键
     * @param version   历史版本号
     * @return 探测结果；不可用时携带面向页面的中文原因
     */
    private Availability probeRollbackArtifact(String projectId, String version) {
        ReleaseRecord release = releaseRecordMapper.selectOne(new LambdaQueryWrapper<ReleaseRecord>()
                .eq(ReleaseRecord::getProjectId, projectId)
                .eq(ReleaseRecord::getVersion, version)
                .orderByDesc(ReleaseRecord::getCreateTime)
                .last("LIMIT 1"));
        if (release == null) {
            return Availability.no("历史版本 " + version + " 的发布记录不存在，平台无法定位该版本的产物");
        }
        RuntimeType runtimeType = RuntimeType.fromCode(release.getRuntimeType());
        if (runtimeType == RuntimeType.DOCKER) {
            // 镜像在仓库里，只要还记得 tag 就能重新 pull；保留策略是否标记过废弃不影响这条路径。
            return StringUtils.hasText(release.getImageTag())
                    ? Availability.ok()
                    : Availability.no("历史版本 " + version + " 未记录镜像标签，无法从镜像仓库重新拉取");
        }
        // JAR / STATIC：产物在构建机磁盘上，被保留策略回收后 artifact_path 指向的目录已经不存在。
        Integer status = release.getStatus();
        if (status == null || status != ReleaseStatus.RELEASABLE) {
            String artifact = runtimeType == RuntimeType.STATIC ? "静态产物" : "jar 产物";
            return Availability.no("历史版本 " + version + " 状态为「" + ReleaseStatus.describe(status)
                    + "」，超出项目保留版本数后其" + artifact
                    + "已从构建机回收，无法回滚：请重新构建该版本后再回滚");
        }
        if (!StringUtils.hasText(release.getBuildServerId()) || !StringUtils.hasText(release.getArtifactPath())) {
            return Availability.no("历史版本 " + version + " 缺少构建机或产物路径记录，无法重新分发");
        }
        return Availability.ok();
    }

    /**
     * 单个历史版本的可用性探测结果。
     *
     * <p>用 record 而不是 boolean：判定与文案必须同源产出。交给调用方自己拼原因的话，
     * 「为什么不能回滚」会随着调用点增多漂移成好几种说法，而三处展示（按钮态、预览清单、
     * 目标明细）本该是同一句话。</p>
     *
     * @param available true 表示该版本仍可被重新发布
     * @param reason    不可用时的中文原因（面向页面展示）；可用时为空
     */
    private record Availability(boolean available, String reason) {

        /** 该版本可被重新发布。 */
        private static Availability ok() {
            return new Availability(true, null);
        }

        /** 该版本不可被重新发布，给出原因。 */
        private static Availability no(String reason) {
            return new Availability(false, reason);
        }
    }

    /**
     * 把目标标记为「回滚跳过」并写明原因。
     *
     * <p>入参必须是已截断到数据库列上限的文本：本类不持有截断规则，
     * 长度约束统一由 {@link DeploymentVoAssembler#truncate(String)} 定义，
     * 由调用方在调用前处理。这样「多长算超长」在全局只有一处答案。</p>
     *
     * @param target 目标实体
     * @param reason 跳过原因（面向页面展示，调用方已截断）
     */
    public void skipForRollback(DeploymentTarget target, String reason) {
        target.setStatus(DeploymentTargetStatus.SKIPPED);
        target.setErrorMessage(reason);
        deploymentTargetMapper.updateById(target);
    }
}
