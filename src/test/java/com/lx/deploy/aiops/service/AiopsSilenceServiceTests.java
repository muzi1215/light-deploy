package com.lx.deploy.aiops.service;

import com.lx.deploy.aiops.client.AiopsClient;
import com.lx.deploy.aiops.client.AiopsDtos.Scope;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceCreateRequest;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceData;
import com.lx.deploy.aiops.config.AiopsProperties;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.mapper.DeploymentTargetMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Environment;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 静默窗建窗判定测试。
 *
 * <p>只盯一件事：<b>什么情况下才会真的向 AIops 发建窗请求</b>。这条判定有两级开关
 * （平台总闸 {@code light-deploy.aiops.enabled} + 环境开关 {@code environment.aiops_enabled}），
 * 顺序是「总闸 → 已有窗幂等 → 环境开关 → 目标机非空」。判错的两种后果都是静默的：
 * 该建的没建（发布期告警照推，值班以为功能没做），或者不该建的建了（测试环境被压掉真问题）。</p>
 *
 * <p>因此这里不碰 HTTP，也不碰 SSH，全部用 mock 把「哪一步短路了、有没有多查一次库」钉死；
 * 真实的建窗/关窗链路已在 2026-11-02 用联调实例跑过（见 {@code docs/AIops联动.md} §9）。</p>
 *
 * @author lx
 * @since 2026-11-02 02:30:00
 */
@ExtendWith(MockitoExtension.class)
class AiopsSilenceServiceTests {

    /** 部署主键。 */
    private static final String DEPLOYMENT_ID = "dep-1";

    /** 环境主键。 */
    private static final String ENVIRONMENT_ID = "env-1";

    /** 目标机主键。 */
    private static final String SERVER_ID = "srv-1";

    @Mock
    private AiopsClient client;

    @Mock
    private DeploymentMapper deploymentMapper;

    @Mock
    private DeploymentTargetMapper deploymentTargetMapper;

    @Mock
    private ServerMapper serverMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private EnvironmentMapper environmentMapper;

    /** 真实配置对象，用例里按需改开关。 */
    private AiopsProperties properties;

    /** 被测服务。 */
    private AiopsSilenceService service;

    @BeforeEach
    void setUp() {
        properties = new AiopsProperties();
        service = new AiopsSilenceService(properties, client, deploymentMapper,
                deploymentTargetMapper, serverMapper, projectMapper, environmentMapper);
    }

    @Test
    @DisplayName("平台总闸关闭：直接跳过，不查环境、不发请求")
    void shouldSkipWhenGlobalSwitchOff() {
        properties.setEnabled(false);

        AiopsSilenceService.SilenceOutcome outcome = service.openForDeployment(deployment(null));

        assertThat(outcome.opened()).isFalse();
        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.message()).contains("AIops 联动未启用");
        // 总闸是二级判定的前置条件，关掉后连库都不该查
        verifyNoInteractions(environmentMapper, deploymentTargetMapper, client);
    }

    @Test
    @DisplayName("环境开关关闭：跳过建窗，跳过文案带环境名，且不算降级")
    void shouldSkipWhenEnvironmentSwitchOff() {
        properties.setEnabled(true);
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment((byte) 0));

        AiopsSilenceService.SilenceOutcome outcome = service.openForDeployment(deployment(null));

        assertThat(outcome.opened()).isFalse();
        // 配置决定 ≠ 故障：degraded 必须为 false，否则任务日志会误打【注意】
        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.message()).contains("测试环境").contains("未开启 AIops 联动");
        verify(client, never()).createSilence(any());
    }

    @Test
    @DisplayName("环境开关未配置（null）：按关闭处理，避免拆箱 NPE")
    void shouldSkipWhenEnvironmentSwitchNull() {
        properties.setEnabled(true);
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment(null));

        AiopsSilenceService.SilenceOutcome outcome = service.openForDeployment(deployment(null));

        assertThat(outcome.opened()).isFalse();
        assertThat(outcome.degraded()).isFalse();
        verify(client, never()).createSilence(any());
    }

    @Test
    @DisplayName("环境记录不存在：跳过，不继续查目标机")
    void shouldSkipWhenEnvironmentMissing() {
        properties.setEnabled(true);
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(null);

        AiopsSilenceService.SilenceOutcome outcome = service.openForDeployment(deployment(null));

        assertThat(outcome.opened()).isFalse();
        assertThat(outcome.message()).contains("环境");
        verifyNoInteractions(deploymentTargetMapper, client);
    }

    @Test
    @DisplayName("已有窗口：幂等短路，不查环境也不重复建窗")
    void shouldSkipWhenWindowAlreadyOpened() {
        properties.setEnabled(true);

        AiopsSilenceService.SilenceOutcome outcome = service.openForDeployment(deployment("silence-old"));

        assertThat(outcome.opened()).isFalse();
        assertThat(outcome.message()).contains("silence-old");
        verifyNoInteractions(environmentMapper, deploymentTargetMapper, client);
    }

    @Test
    @DisplayName("两级开关都开：建窗、回填窗口 ID、请求里带环境编码与主机")
    void shouldOpenWindowWhenBothSwitchesOn() {
        properties.setEnabled(true);
        Deployment deployment = deployment(null);
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment((byte) 1));
        when(deploymentTargetMapper.selectList(any())).thenReturn(List.of(target()));
        when(serverMapper.selectByIds(any())).thenReturn(List.of(server()));
        when(client.createSilence(any())).thenReturn(
                new SilenceData("silence-new", 1, null, "2026-11-02T03:00:00+08:00",
                        List.of("服务异常"), List.of("MySQL慢查询"), false));

        AiopsSilenceService.SilenceOutcome outcome = service.openForDeployment(deployment);

        assertThat(outcome.opened()).isTrue();
        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.silenceId()).isEqualTo("silence-new");
        // 窗口 ID 必须写回部署记录，否则收尾时无从关窗，只能等自动过期
        assertThat(deployment.getAiopsSilenceId()).isEqualTo("silence-new");
        verify(deploymentMapper).updateById(deployment);

        // 请求体里的环境编码与主机是对端匹配静默的唯一依据，缺一不可
        ArgumentCaptor<SilenceCreateRequest> captor = ArgumentCaptor.forClass(SilenceCreateRequest.class);
        verify(client).createSilence(captor.capture());
        Scope scope = captor.getValue().scope();
        assertThat(scope.environmentCode()).isEqualTo("test");
        assertThat(scope.servers()).hasSize(1);
        assertThat(scope.servers().getFirst().host()).isEqualTo("10.0.0.9");
    }

    @Test
    @DisplayName("两级开关都开但建窗失败：fail-open 下降级，不阻断发布")
    void shouldDegradeWhenCreateFailsAndFailOpen() {
        properties.setEnabled(true);
        Deployment deployment = deployment(null);
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment((byte) 1));
        when(deploymentTargetMapper.selectList(any())).thenReturn(List.of(target()));
        when(serverMapper.selectByIds(any())).thenReturn(List.of(server()));
        when(client.createSilence(any())).thenThrow(new RuntimeException("connection refused"));

        AiopsSilenceService.SilenceOutcome outcome = service.openForDeployment(deployment);

        assertThat(outcome.opened()).isFalse();
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.message()).contains("connection refused");
        // 失败时不写窗口 ID，收尾阶段的关窗逻辑才不会拿空 ID 去调对端
        assertThat(deployment.getAiopsSilenceId()).isNull();
        verify(deploymentMapper, never()).updateById(any(Deployment.class));
    }

    /**
     * 构造待发布的部署记录。
     *
     * @param silenceId 已有的静默窗 ID，为空表示本次还没建过窗
     * @return 部署记录
     */
    private Deployment deployment(String silenceId) {
        Deployment deployment = new Deployment();
        deployment.setId(DEPLOYMENT_ID);
        deployment.setProjectId("project-1");
        deployment.setEnvironmentId(ENVIRONMENT_ID);
        deployment.setOperatorName("lx");
        deployment.setAiopsSilenceId(silenceId);
        return deployment;
    }

    /**
     * 构造环境记录。
     *
     * @param aiopsEnabled 环境级开关，允许为 null（表示历史数据没配）
     * @return 环境记录
     */
    private Environment environment(Byte aiopsEnabled) {
        Environment environment = new Environment();
        environment.setId(ENVIRONMENT_ID);
        environment.setCode("test");
        environment.setName("测试环境");
        environment.setAiopsEnabled(aiopsEnabled);
        return environment;
    }

    /** @return 本次部署的一个目标快照 */
    private DeploymentTarget target() {
        DeploymentTarget target = new DeploymentTarget();
        target.setDeploymentId(DEPLOYMENT_ID);
        target.setServerId(SERVER_ID);
        return target;
    }

    /** @return 目标服务器档案 */
    private Server server() {
        Server server = new Server();
        server.setId(SERVER_ID);
        server.setHost("10.0.0.9");
        return server;
    }
}
