package com.lx.deploy.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lx.deploy.common.constants.ReleaseStatus;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 发布版本保留策略。
 *
 * <p>回答「项目保留多少个可发布版本」：`project.retain_release_count` 此前只落库不被任何逻辑消费，
 * 构建机上的产物目录因此只增不减。本服务把该字段真正接进构建收尾：每次构建成功后算出超额的
 * 历史版本，交给执行器去删磁盘产物，再由本服务把记录置为「已废弃」。</p>
 *
 * <p>两条安全边界，缺一不可：</p>
 * <ul>
 *   <li><b>保护窗口</b>：最近 {@link #RECENT_DEPLOY_SCAN} 次部署引用过的版本一律不回收。
 *       回滚是按 `old_version` 重新发布，一旦把「某台机器正跑着的版本」清掉，回滚与新发都会断；
 *       宁可少删，不可错删。</li>
 *   <li><b>路径白名单</b>：待删目录必须由「构建机产物持久目录 / 项目编码 / 版本号」三段拼成，
 *       且版本号只允许 {@code [A-Za-z0-9._-]}。版本号来自库记录而非用户输入，但 {@code rm -rf}
 *       的目标路径仍做一次校验，杜绝路径穿越。</li>
 * </ul>
 *
 * <p>本服务只做数据决策，不持有 SSH 会话：删磁盘属于远程执行，由构建执行器完成。</p>
 *
 * @author lx
 * @since 2026-09-26
 */
@Service
public class ReleaseRetentionService {

    /** 保护窗口大小：按创建时间倒序扫描的部署条数，它们引用的版本不参与回收。 */
    private static final int RECENT_DEPLOY_SCAN = 20;

    /** 安全版本号：磁盘删除前的校验依据，含斜杠或点点一律不删。 */
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9._-]+");

    /** 发布版本 Mapper。 */
    private final ReleaseRecordMapper releaseRecordMapper;

    /** 部署记录 Mapper，用于算保护窗口。 */
    private final DeploymentMapper deploymentMapper;

    /** 服务器 Mapper，用于取构建机上的产物持久目录。 */
    private final ServerMapper serverMapper;

    /**
     * 注入数据访问依赖。
     *
     * @param releaseRecordMapper 发布版本 Mapper
     * @param deploymentMapper    部署记录 Mapper
     * @param serverMapper        服务器 Mapper
     */
    public ReleaseRetentionService(ReleaseRecordMapper releaseRecordMapper,
                                   DeploymentMapper deploymentMapper,
                                   ServerMapper serverMapper) {
        this.releaseRecordMapper = releaseRecordMapper;
        this.deploymentMapper = deploymentMapper;
        this.serverMapper = serverMapper;
    }

    /**
     * 计算本次应回收的发布版本。
     *
     * <p>排序口径：先按版本序号倒序、再按创建时间倒序。序号为空的历史版本在 MySQL 倒序下排最后，
     * 正好落在「最先被回收」的位置，与「越旧越先清」的直觉一致。</p>
     *
     * <p><b>配额只对「占用构建机磁盘」的版本计数</b>：镜像类运行时（DOCKER）的产物是镜像仓库里的 tag，
     * 既不落在构建机的产物持久目录、平台也没有删除它的能力，却始终能被再次发布。把它算进配额的结果是，
     * 这类版本被置为「已废弃」从而从新建部署的版本下拉里消失，而它的镜像在仓库里好好的 ——
     * 白白损失一个可用版本，还没省下一字节磁盘。</p>
     *
     * <p>过滤必须发生在配额计算<b>之前</b>（判据见 {@link #occupiesBuildDisk}）：否则不占磁盘的版本会挤占名额，
     * 把本该保留的文件类版本提前挤进「超额」区间而被误删。</p>
     *
     * <p>选择在 Java 侧过滤而不是写进 SQL：SQL 里 {@code runtime_type <> 'DOCKER'} 对 runtime_type 为 NULL 的
     * 行求值是 NULL（不成立），会把运行时为空的历史记录整批漏掉；此处要的是一条明确可调的判据，
     * 而不是依赖三值逻辑的隐式行为。</p>
     *
     * <p>保留数未配置（空或小于等于 0）时<b>不做任何回收</b>——没有明确配额就删东西风险太高。</p>
     *
     * @param project 项目，提供主键、编码与保留版本数
     * @return 待回收清单；无需回收时为空列表
     */
    public List<RecycleItem> plan(Project project) {
        Integer retain = project.getRetainReleaseCount();
        if (retain == null || retain <= 0) {
            return List.of();
        }
        List<ReleaseRecord> all = releaseRecordMapper.selectList(new LambdaQueryWrapper<ReleaseRecord>()
                .eq(ReleaseRecord::getProjectId, project.getId())
                .eq(ReleaseRecord::getStatus, ReleaseStatus.RELEASABLE)
                .orderByDesc(ReleaseRecord::getVersionSeq)
                .orderByDesc(ReleaseRecord::getCreateTime))
                .stream()
                .filter(this::occupiesBuildDisk)
                .toList();
        if (all.size() <= retain) {
            return List.of();
        }
        Set<String> protectedIds = recentDeployReleaseIds(project.getId());
        List<RecycleItem> items = new ArrayList<>();
        for (ReleaseRecord record : all.subList(retain, all.size())) {
            if (protectedIds.contains(record.getId())) {
                continue;
            }
            items.add(new RecycleItem(record.getId(), record.getVersion(),
                    record.getBuildServerId(), artifactDir(project, record)));
        }
        return items;
    }

    /**
     * 判断该版本是否占用构建机磁盘——只有占磁盘的版本才计入配额、才有资格被回收。
     *
     * <p>镜像类运行时（DOCKER）的产出是镜像，构建时不会往产物持久目录落任何文件
     * （登记版本时对 DOCKER 只写 image_tag / image_digest，从不写 artifact_path），
     * 因此 {@link #artifactDir} 对它恒返回 null，回收阶段本来也无目录可删 ——
     * 它从未消耗过这份配额，就不该被配额清出榜单。</p>
     *
     * <p><b>运行时为空的历史记录按「占磁盘」处理</b>：这类记录基本都是早期文件类版本，
     * 若按「不占磁盘」放行，它们在构建机上的产物目录就再没有回收机会，等于留成永久垃圾。
     * 形态无法确定时选择保守回收，与本服务「宁可少删、不可错删」的整体取向一致。</p>
     *
     * @param record 待判断的发布版本记录
     * @return true 表示该版本占用构建机磁盘，计入配额并可被回收
     */
    private boolean occupiesBuildDisk(ReleaseRecord record) {
        RuntimeType runtimeType = RuntimeType.fromCode(record.getRuntimeType());
        // 非 DOCKER 运行时（文件类与历史空形态）一律占磁盘，计入配额。
        if (runtimeType != RuntimeType.DOCKER) {
            return true;
        }
        // DOCKER 默认不占配额（产物是镜像仓库 tag，平台无法也无需清理）；但 COMPOSE + 指定 work_dir 形态
        // 会往构建机产物目录落一份构建上下文 tarball，这类版本确实占用磁盘，应计入配额并被回收，
        // 否则历史 tarball 永不清理会悄悄吃满构建机磁盘。
        return StringUtils.hasText(record.getContextArtifactPath());
    }

    /**
     * 把已回收磁盘产物的版本置为「已废弃」。
     *
     * <p>置废弃而不是物理删除：记录留着可追溯，且不做物理删除符合发布版本模块的既有约定。
     * 废弃后不再出现在新建部署的版本下拉里（下拉只列「可发布」），避免出现「看得到却发不了」。</p>
     *
     * @param releaseIds 已确认产物回收完成的版本主键
     * @return 实际更新条数
     */
    public int markDeprecated(List<String> releaseIds) {
        if (releaseIds == null || releaseIds.isEmpty()) {
            return 0;
        }
        return releaseRecordMapper.update(null, new LambdaUpdateWrapper<ReleaseRecord>()
                .set(ReleaseRecord::getStatus, ReleaseStatus.DEPRECATED)
                .in(ReleaseRecord::getId, releaseIds));
    }

    /**
     * 取保护窗口内的版本主键集合。
     *
     * <p>按创建时间倒序扫最近若干条部署：能被回滚到的版本必然在近期部署里出现过，
     * 这个窗口足以覆盖「各环境每台机器当前跑着的版本」。</p>
     *
     * @param projectId 项目主键
     * @return 不参与回收的版本主键集合
     */
    private Set<String> recentDeployReleaseIds(String projectId) {
        List<Deployment> recent = deploymentMapper.selectList(new LambdaQueryWrapper<Deployment>()
                .eq(Deployment::getProjectId, projectId)
                .orderByDesc(Deployment::getCreateTime)
                .last("LIMIT " + RECENT_DEPLOY_SCAN));
        Set<String> ids = new HashSet<>();
        for (Deployment deployment : recent) {
            if (StringUtils.hasText(deployment.getReleaseId())) {
                ids.add(deployment.getReleaseId());
            }
        }
        return ids;
    }

    /**
     * 拼出版本在构建机上的产物目录；信息不全或版本号不安全时返回 null（跳过磁盘删除）。
     *
     * @param project 项目，提供编码
     * @param record  版本记录，提供版本号与构建机
     * @return 构建机上的绝对路径；无法确定时返回 null
     */
    private String artifactDir(Project project, ReleaseRecord record) {
        String version = record.getVersion();
        if (!StringUtils.hasText(version) || !SAFE_VERSION.matcher(version.strip()).matches()) {
            return null;
        }
        if (!StringUtils.hasText(record.getBuildServerId())) {
            return null;
        }
        Server server = serverMapper.selectById(record.getBuildServerId());
        if (server == null || !StringUtils.hasText(server.getBuildArtifactDir())) {
            return null;
        }
        return server.getBuildArtifactDir().strip() + "/" + project.getCode() + "/" + version.strip();
    }

    /**
     * 一条待回收版本：包含执行器删磁盘所需的全部信息。
     *
     * @param releaseId 版本主键
     * @param version   版本号，仅用于日志与目录拼装
     * @param serverId  产物所在的构建机主键，可能为空
     * @param dir       构建机上的产物目录绝对路径；为 null 时只标记废弃、不删磁盘
     */
    public record RecycleItem(String releaseId, String version, String serverId, String dir) {
    }
}
