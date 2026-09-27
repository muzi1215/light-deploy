package com.lx.deploy.deployment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.common.constants.DeploymentTargetStatus;
import com.lx.deploy.common.enums.project.DeployStrategy;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.deployment.executor.DeployContext;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.project.model.Environment;
import com.lx.deploy.project.model.ProjectEnvironmentTarget;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布目标展开器：决定「这次发哪几台」以及「每台机器上现在跑的是什么版本」。
 *
 * <p>这三件事（策略解析、按策略裁剪目标、推断每台机器当前版本）属于发布前的规划阶段，
 * 与「创建部署记录」「状态流转」不是同一件事。放在一个服务里时，
 * 它们被夹在创建流程的中间，改一眼要上下翻几百行。</p>
 *
 * <p><b>{@code old_version} 是回滚能否成立的关键</b>：它必须忠实反映「这台机器此刻真正跑的版本」。
 * 取错了的后果不是显示错误，而是回滚会退到一个错误的版本上去。
 * 因此这里的推断逻辑单独成类，任何调整都发生在一个能被整体审阅的文件里。</p>
 *
 * @author lx
 * @since 2026-09-27 20:40:00
 */
@Service
public class DeploymentTargetPlanner {

    /** 回溯历史部署以推断「当前版本」时的最大扫描条数，防止无边界查询。 */
    private static final int OLD_VERSION_SCAN_LIMIT = 10;

    /** 部署 Mapper：扫描历史部署记录。 */
    private final DeploymentMapper deploymentMapper;

    /** VO 组装器：读取历史部署的目标行。 */
    private final DeploymentVoAssembler voAssembler;

    /**
     * 注入数据访问依赖。
     *
     * @param deploymentMapper 部署 Mapper
     * @param voAssembler      VO 组装器
     */
    public DeploymentTargetPlanner(DeploymentMapper deploymentMapper, DeploymentVoAssembler voAssembler) {
        this.deploymentMapper = deploymentMapper;
        this.voAssembler = voAssembler;
    }

    /**
     * 解析部署策略：请求覆盖优先，其次环境默认，都没有时退化为单目标。
     *
     * @param requested   请求传入的策略编码，允许为空
     * @param environment 目标环境，提供默认策略
     * @return 合法的策略编码
     * @throws DeployException 请求传入的策略编码不受支持时抛出
     */
    public String resolveStrategy(String requested, Environment environment) {
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
    public List<ProjectEnvironmentTarget> selectByStrategy(List<ProjectEnvironmentTarget> bindings, String strategy) {
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
     * @return 服务器 ID 到当前版本的映射；查不到表示该机器尚无平台纳管版本
     */
    public Map<String, String> resolveCurrentVersions(String projectId, String environmentId) {
        // 用普通条件构造器而不是链式包装：这里需要 last("LIMIT n") 收敛扫描范围，
        // 链式包装的 last 语义不稳定，显式构造再交给 ServiceImpl.list 更可控。
        LambdaQueryWrapper<Deployment> wrapper = new LambdaQueryWrapper<Deployment>()
                .eq(Deployment::getProjectId, projectId)
                .eq(Deployment::getEnvironmentId, environmentId)
                .in(Deployment::getStatus, DeploymentStatus.SUCCESS, DeploymentStatus.ROLLED_BACK)
                .orderByDesc(Deployment::getCreateTime)
                .last("LIMIT " + OLD_VERSION_SCAN_LIMIT);
        List<Deployment> recent = deploymentMapper.selectList(wrapper);
        Map<String, String> versions = new HashMap<>();
        for (Deployment past : recent) {
            for (DeploymentTarget target : voAssembler.listTargetsEntity(past.getId())) {
                // 只有真正发布成功过的目标才算「机器在跑这个版本」。
                if (target.getStatus() == null || target.getStatus() != DeploymentTargetStatus.SUCCESS) {
                    continue;
                }
                if (versions.containsKey(target.getServerId())) {
                    continue;
                }
                // 这里求的是「那次部署<b>结束后</b>机器在跑的版本」，不是「那次部署时被换掉的版本」：
                //   成功的部署把机器带到 new_version；已回滚的部署把机器带回 old_version。
                // 必须用 targetVersionOf（本次写入磁盘的版本），用 currentVersionOf 会取到相反的一端——
                // 首次发布那次 old_version 为空，会推断出「这台机器没有纳管版本」，
                // 于是之后每一次发布的 old_version 都为空，回滚永远被判成首次发布。
                String effective = DeployContext.targetVersionOf(target,
                        past.getStatus() != null && past.getStatus() == DeploymentStatus.ROLLED_BACK);
                // 空值不是有效结论：不写入 Map，让更早的历史仍有机会给出这台机器的版本。
                // 写入 null 会因为 containsKey 成立而屏蔽掉更旧但有效的记录。
                if (StringUtils.hasText(effective)) {
                    versions.put(target.getServerId(), effective);
                }
            }
        }
        return versions;
    }
}
