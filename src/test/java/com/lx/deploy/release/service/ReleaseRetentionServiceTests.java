package com.lx.deploy.release.service;

import com.lx.deploy.common.constants.ReleaseStatus;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 版本保留回收计划测试。
 *
 * <p>只测 {@link ReleaseRetentionService#plan} 的计算口径，不碰 SSH 与磁盘删除
 * （那部分在 {@link ReleaseRetentionExecutor}，需要真机会话）。回收逻辑一旦算错就是
 * 「还能发布的版本被判废弃」或「磁盘上的历史产物永远没人清」，且两者都不报错、不易察觉，
 * 因此用这三个 Mapper 全 mock 的纯内存用例把口径钉住。</p>
 *
 * <p>另一条底线是保护窗口必须始终生效：最近部署引用过的版本一律不回收（否则会把某台机器
 * 正跑着的版本清掉，回滚与新发同时断掉）。最后一条用例专门看住它，避免后续调整回收判据时把它改坏。</p>
 *
 * @author lx
 * @since 2026-09-28
 */
@ExtendWith(MockitoExtension.class)
class ReleaseRetentionServiceTests {

    /** 构建机主键，与 {@link #buildServer()} 配套。 */
    private static final String BUILD_SERVER_ID = "bs-1";

    /** 项目主键。 */
    private static final String PROJECT_ID = "project-1";

    @Mock
    private ReleaseRecordMapper releaseRecordMapper;

    @Mock
    private DeploymentMapper deploymentMapper;

    @Mock
    private ServerMapper serverMapper;

    /** 被测服务。 */
    private ReleaseRetentionService service;

    /**
     * 每个用例前重建被测对象，并给出「无近期部署 / 构建机已配产物目录」的默认桩；
     * 用 lenient 是因为并非每个用例都走到这两处（配额没超限时根本不算保护窗口）。
     */
    @BeforeEach
    void setUp() {
        service = new ReleaseRetentionService(releaseRecordMapper, deploymentMapper, serverMapper);
        lenient().when(deploymentMapper.selectList(any())).thenReturn(List.of());
        lenient().when(serverMapper.selectById(BUILD_SERVER_ID)).thenReturn(buildServer());
    }

    /**
     * 镜像类版本不占配额：DOCKER 版本被排除在计数之外，文件类版本因此不会被提前挤进超额区间。
     *
     * <p>修复前，v4（DOCKER）会占掉一个名额，导致 v2 落到超额区间被误回收；
     * 修复后配额只对占磁盘的三个文件类版本计数，只有最旧的 v1 被回收。</p>
     */
    @Test
    @DisplayName("DOCKER 版本不占配额，文件类版本不被提前挤出")
    void dockerVersionsDoNotOccupyRetentionQuota() {
        givenReleases(
                release("r4", "v4", 4, "DOCKER", null),
                release("r3", "v3", 3, "JAR", BUILD_SERVER_ID),
                release("r2", "v2", 2, "JAR", BUILD_SERVER_ID),
                release("r1", "v1", 1, "JAR", BUILD_SERVER_ID));

        List<ReleaseRetentionService.RecycleItem> items = service.plan(project(2));

        assertThat(items).extracting(ReleaseRetentionService.RecycleItem::version)
                .containsExactly("v1");
    }

    /**
     * 全是镜像类版本时不回收任何东西：一个项目的历史本来就没占磁盘，回收无从谈起。
     */
    @Test
    @DisplayName("全部为 DOCKER 版本时不回收任何版本")
    void allDockerVersionsAreNeverRecycled() {
        givenReleases(
                release("r3", "v3", 3, "DOCKER", null),
                release("r2", "v2", 2, "DOCKER", null),
                release("r1", "v1", 1, "DOCKER", null));

        List<ReleaseRetentionService.RecycleItem> items = service.plan(project(1));

        assertThat(items).isEmpty();
    }

    /**
     * 带构建上下文 tarball 的 DOCKER 版本要占配额：COMPOSE + 指定 work_dir 形态会往构建机落 tarball，
     * 它确实占磁盘，应被计入配额；否则这类版本的历史 tarball 永不回收会吃满磁盘。
     */
    @Test
    @DisplayName("DOCKER 版本带上下文tarball时占配额，可被回收")
    void dockerVersionWithContextTarballOccupiesQuota() {
        ReleaseRecord dockerWithContext = release("r4", "v4", 4, "DOCKER", null);
        dockerWithContext.setContextArtifactPath("/opt/light-deploy/artifacts/demo/v4/build-context.tar.gz");
        givenReleases(
                dockerWithContext,
                release("r3", "v3", 3, "JAR", BUILD_SERVER_ID),
                release("r2", "v2", 2, "JAR", BUILD_SERVER_ID),
                release("r1", "v1", 1, "JAR", BUILD_SERVER_ID));

        List<ReleaseRetentionService.RecycleItem> items = service.plan(project(2));

        // DOCKER 版本占掉一个名额 -> 最旧的两个文件类版本被回收。
        assertThat(items).extracting(ReleaseRetentionService.RecycleItem::version)
                .containsExactly("v2", "v1");
    }

    /**
     * 运行时为空的历史记录按「占磁盘」处理：它们是早期文件类版本，
     * 若因形态未知就放行，构建机上的产物目录再没有回收机会，等于留成永久垃圾。
     */
    @Test
    @DisplayName("runtime_type 为空仍参与配额（宁可保守回收）")
    void nullRuntimeTypeStillCountsAsDiskArtifact() {
        givenReleases(
                release("r3", "v3", 3, null, BUILD_SERVER_ID),
                release("r2", "v2", 2, "JAR", BUILD_SERVER_ID),
                release("r1", "v1", 1, "JAR", BUILD_SERVER_ID));

        List<ReleaseRetentionService.RecycleItem> items = service.plan(project(1));

        assertThat(items).extracting(ReleaseRetentionService.RecycleItem::version)
                .containsExactly("v2", "v1");
    }

    /**
     * 保护窗口不受本次调整影响：最近部署引用过的版本即便落在超额区间也要留下。
     */
    @Test
    @DisplayName("保护窗口内的版本不参与回收")
    void versionsInRecentDeploymentsAreProtected() {
        givenReleases(
                release("r3", "v3", 3, "JAR", BUILD_SERVER_ID),
                release("r2", "v2", 2, "JAR", BUILD_SERVER_ID),
                release("r1", "v1", 1, "JAR", BUILD_SERVER_ID));
        Deployment recent = new Deployment();
        recent.setReleaseId("r1");
        when(deploymentMapper.selectList(any())).thenReturn(List.of(recent));

        List<ReleaseRetentionService.RecycleItem> items = service.plan(project(1));

        assertThat(items).extracting(ReleaseRetentionService.RecycleItem::version)
                .containsExactly("v2");
    }

    /**
     * 装填 mock 查询返回的版本列表。
     *
     * @param releases 按版本序号倒序排列的版本记录
     */
    private void givenReleases(ReleaseRecord... releases) {
        when(releaseRecordMapper.selectList(any())).thenReturn(List.of(releases));
    }

    /**
     * 构造一个保留版本数已配置的项目。
     *
     * @param retain 保留版本数
     * @return 项目实体
     */
    private Project project(int retain) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setCode("demo");
        project.setRetainReleaseCount(retain);
        return project;
    }

    /**
     * 构造一条版本记录。
     *
     * @param id             主键
     * @param version        版本号
     * @param versionSeq     版本序号
     * @param runtimeType    运行时类型，可为 null 模拟早期数据
     * @param buildServerId  构建机主键，镜像类版本恒为空
     * @return 版本记录
     */
    private ReleaseRecord release(String id, String version, int versionSeq,
                                  String runtimeType, String buildServerId) {
        ReleaseRecord record = new ReleaseRecord();
        record.setId(id);
        record.setProjectId(PROJECT_ID);
        record.setVersion(version);
        record.setVersionSeq(versionSeq);
        record.setRuntimeType(runtimeType);
        record.setBuildServerId(buildServerId);
        record.setStatus(ReleaseStatus.RELEASABLE);
        record.setCreateTime(LocalDateTime.now());
        return record;
    }

    /**
     * 构造一台配了产物持久目录的构建机。
     *
     * @return 服务器实体
     */
    private Server buildServer() {
        Server server = new Server();
        server.setId(BUILD_SERVER_ID);
        server.setBuildArtifactDir("/opt/light-deploy/artifacts");
        return server;
    }
}
