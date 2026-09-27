package com.lx.deploy.deployment.executor;

import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.task.model.Task;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 单个部署目标的一次发布（或回滚）入参载体。
 *
 * <p>部署执行原先是 {@code DeployTaskHandler} 里一组参数很长的方法调用：
 * JAR 要 7 个参数、STATIC 要 6 个、DOCKER 要 5 个，且彼此不同。拆出按运行时的
 * Service 之后，如果不收敛入参，三个 Service 的方法签名会各自带一长串参数，
 * 调用方仍要记住「JAR 多传一个 target」「DOCKER 不要 target」这类差别。</p>
 *
 * <p>本载体把「一次目标级发布需要知道的全部事实」一次性打包，三个运行时 Service
 * 统一暴露 {@code deploy(DeployContext)}，编排层只剩一次 switch。
 * 它只做数据传递与版本号语义换算，不含任何远程操作。</p>
 *
 * <p><b>版本号的两种语义</b>：这是本载体存在的第二个理由。
 * 发布与回滚共用同一批代码，但「哪个版本是要写上去的、哪个版本是现在正跑着的」
 * 在两个方向上是相反的，散落在各 Service 里各算一次迟早算错。
 * 这里集中定义两个方法，Service 一律取用，不再自行判断 {@code rollingBack}。</p>
 *
 * <p><b>两处入口必须同值</b>：{@code targetVersion()} 取自部署目标快照的字段，
 * 而 {@code release().getVersion()} 取自 {@code release_record} 表。
 * 发布执行器确实是按后者去下载产物的，两者不是天然相等——
 * 正常发布时取的是 {@code deployment.release_id} 对应的记录，
 * 若与目标快照的 {@code new_version} 不一致，磁盘上写下的 VERSION 标识会与平台记录错位，
 * 而这类错位要到「回滚时找不到能退的版本」才暴露。
 * 因此构造时就做一次对齐校验，让不一致在发布之前炸掉，而不是等到回滚。</p>
 *
 * @param task         当前 DEPLOY 任务，写日志与进度用
 * @param deployment   部署主记录
 * @param project      项目，提供运行时类型与编码
 * @param server       目标服务器
 * @param target       待发布的目标快照，提供新旧版本号
 * @param config       合并目标差异后的运行配置
 * @param release      本次实际要发布的版本记录（回滚时为 old_version 对应的记录）
 * @param rollingBack  本次是否为回滚
 * @author lx
 * @since 2026-09-27 19:40:00
 */
public record DeployContext(Task task,
                            Deployment deployment,
                            Project project,
                            Server server,
                            DeploymentTarget target,
                            DeployConfig config,
                            ReleaseRecord release,
                            boolean rollingBack) {

    /**
     * 紧凑构造：校验「本次要写的版本」在两处入口是否一致。
     *
     * <p>只在两边都有文本时比对——首次部署的目标快照可能没有 {@code new_version}，
     * 那种情况属于「平台尚未记录版本」，不应被这里拦住。</p>
     *
     * @throws NullPointerException task / target / release 为空时抛出（编排层装配错误）
     * @throws DeployException      快照记录的版本与 {@code release_record} 的版本不一致时抛出
     */
    public DeployContext {
        Objects.requireNonNull(task, "task 不能为空");
        Objects.requireNonNull(target, "target 不能为空");
        Objects.requireNonNull(release, "release 不能为空");
        String declared = rollingBack ? target.getOldVersion() : target.getNewVersion();
        String actual = release.getVersion();
        if (StringUtils.hasText(declared) && StringUtils.hasText(actual)
                && !declared.equals(actual)) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "版本记录与部署快照不一致：快照声明要发布 " + declared + "，实际取到版本记录 "
                            + actual + "，请重新发起部署");
        }
    }

    /**
     * 本次要写到磁盘上的版本号。
     *
     * <p>正常发布是 {@code new_version}（本次要上的版本）；
     * 回滚是 {@code old_version}（本次要退回去的版本）。
     * 二者在发布与回滚两个方向上是互换的，因此不能在任何 Service 里写死取哪个字段。</p>
     *
     * @return 本次发布完成后磁盘上应有的版本号；为空表示平台未记录（首次发布的目标常见）
     */
    public String targetVersion() {
        return targetVersionOf(target, rollingBack);
    }

    /**
     * 磁盘上当前正在运行、本次即将被替换掉的版本号。
     *
     * <p>正常发布时是 {@code old_version}；回滚时磁盘上跑的正是 {@code new_version}。
     * 备份命名用这个值——备份下来的必须标清楚「它装的是哪个版本」，
     * 否则回滚时只能靠时间戳猜。</p>
     *
     * @return 被替换版本的版本号；为空表示首次部署（磁盘上还没有本项目的产物）
     */
    public String currentVersion() {
        return currentVersionOf(target, rollingBack);
    }

    /**
     * 「本次要写上去的版本」的静态取法，供没有 {@link DeployContext} 的读场景复用。
     *
     * <p>{@code DeploymentService} 在算「部署完成后这台机器上跑的是哪个版本」时
     * 需要同一套方向判断。若在那里写一份三元表达式，两个地方迟早改歪一边，
     * 而改歪的表现是页面显示的版本与机器上的 VERSION 文件对不上——不会报错，只会误导。</p>
     *
     * <p>它同时也是「<b>这次动作完成后</b>机器上运行的版本」，因此推断下一次发布的
     * {@code old_version}（{@code DeploymentTargetPlanner}）与页面展示的当前版本
     * （{@code DeploymentVoAssembler}）都必须用它。<b>不要误用 {@link #currentVersionOf}</b>——
     * 那是相反的一端，误用的表现是「每次发布都被判成首次发布、无法回滚」。</p>
     *
     * @param target      部署目标快照
     * @param rollingBack 是否为回滚
     * @return 本次应写入磁盘的版本号；快照未记录时返回 null
     */
    public static String targetVersionOf(DeploymentTarget target, boolean rollingBack) {
        return rollingBack ? target.getOldVersion() : target.getNewVersion();
    }

    /**
     * 「磁盘上正被替换掉的版本」的静态取法，语义同 {@link #currentVersion()}。
     *
     * <p><b>只用于备份命名与「回滚前机器上跑的版本」这类「即将被替换」的语境。</b>
     * 要求「动作完成后机器在跑什么」时请用 {@link #targetVersionOf}。</p>
     *
     * @param target      部署目标快照
     * @param rollingBack 是否为回滚
     * @return 被替换版本的版本号；快照未记录时返回 null
     */
    public static String currentVersionOf(DeploymentTarget target, boolean rollingBack) {
        return rollingBack ? target.getNewVersion() : target.getOldVersion();
    }
}
