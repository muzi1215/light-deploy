package com.lx.deploy.aiops.service;

import com.lx.deploy.aiops.client.AiopsClient;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceDetail;
import com.lx.deploy.aiops.config.AiopsProperties;
import com.lx.deploy.aiops.mapper.AiopsEventMapper;
import com.lx.deploy.aiops.model.AiopsEvent;
import com.lx.deploy.aiops.model.vo.AiopsLinkVo;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.model.Environment;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 联动展示聚合测试。
 *
 * <p>被测的是「页面能看到什么」，因此重点不在计算，而在<b>三件事不能错</b>：</p>
 * <ol>
 *   <li>没有窗口时必须给得出原因（总闸关 / 环境关 / 建窗失败），不能让页面空白；</li>
 *   <li>AIops 不可用时只降级成文案，不能连事件流水一起打不开——排障最需要这个页面；</li>
 *   <li>事件顺序要按快照里的 `sequence` 还原，只按时间排会在同一秒连落多条时跳序。</li>
 * </ol>
 *
 * <p>序号与主机都存在 `request_body` 快照里（表里没有这两列），所以还要覆盖「快照缺失/损坏」的兜底。</p>
 *
 * @author lx
 * @since 2026-11-02 03:40:00
 */
@ExtendWith(MockitoExtension.class)
class AiopsLinkServiceTests {

    /** 部署主键。 */
    private static final String DEPLOYMENT_ID = "dep-1";

    /** 环境主键。 */
    private static final String ENVIRONMENT_ID = "env-1";

    /** 窗口主键。 */
    private static final String SILENCE_ID = "26332273427";

    @Mock
    private AiopsClient client;

    @Mock
    private DeploymentMapper deploymentMapper;

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private AiopsEventMapper eventMapper;

    /** 真实配置对象，用例里按需改开关。 */
    private AiopsProperties properties;

    /** 被测服务。 */
    private AiopsLinkService service;

    @BeforeEach
    void setUp() {
        properties = new AiopsProperties();
        properties.setEnabled(true);
        service = new AiopsLinkService(properties, client, deploymentMapper, environmentMapper, eventMapper);
    }

    @Test
    @DisplayName("总闸关闭：不查远程，事件仍展示，文案说明总闸未开")
    void shouldSkipRemoteWhenGlobalSwitchOff() {
        properties.setEnabled(false);
        when(deploymentMapper.selectById(DEPLOYMENT_ID)).thenReturn(deployment(SILENCE_ID));
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment((byte) 1));
        when(eventMapper.selectList(any())).thenReturn(List.of(event("DEPLOYMENT_STARTED", 1, null)));

        AiopsLinkVo vo = service.getByDeployment(DEPLOYMENT_ID);

        assertThat(vo.getSilence()).isNull();
        assertThat(vo.getSilenceMessage()).contains("总闸未开启");
        assertThat(vo.getEvents()).hasSize(1);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("没建过窗 + 环境关：文案指向环境开关，而不是含糊的「无数据」")
    void shouldExplainEnvironmentSwitchOff() {
        when(deploymentMapper.selectById(DEPLOYMENT_ID)).thenReturn(deployment(null));
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment((byte) 0));
        when(eventMapper.selectList(any())).thenReturn(List.of());

        AiopsLinkVo vo = service.getByDeployment(DEPLOYMENT_ID);

        assertThat(vo.getEnvironmentAiopsEnabled()).isFalse();
        assertThat(vo.getSilenceMessage()).contains("未开启 AIops 联动");
        assertThat(vo.getEvents()).isEmpty();
        verify(client, never()).getSilence(any());
    }

    @Test
    @DisplayName("有窗口：映射远程详情，状态按 expired 优先")
    void shouldMapRemoteSilence() {
        when(deploymentMapper.selectById(DEPLOYMENT_ID)).thenReturn(deployment(SILENCE_ID));
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment((byte) 1));
        when(eventMapper.selectList(any())).thenReturn(List.of());
        when(client.getSilence(SILENCE_ID)).thenReturn(new SilenceDetail(
                SILENCE_ID, "light-deploy:silence:" + DEPLOYMENT_ID, "auth-7888", "lx-prod",
                List.of("srv-1"), List.of("47.103.12.12"), List.of("服务异常"), "light-deploy",
                1, false, "2026-11-02T02:31:13", "2026-11-02T03:11:13",
                "2026-11-02T02:32:26", "发布成功"));

        AiopsLinkVo vo = service.getByDeployment(DEPLOYMENT_ID);

        assertThat(vo.getSilence()).isNotNull();
        assertThat(vo.getSilence().getSilenceId()).isEqualTo(SILENCE_ID);
        assertThat(vo.getSilence().getStatusText()).isEqualTo("已关闭");
        assertThat(vo.getSilence().getCloseReason()).isEqualTo("发布成功");
        assertThat(vo.getSilence().getHosts()).containsExactly("47.103.12.12");
    }

    @Test
    @DisplayName("远程查询失败：只降级成文案，事件流水照常返回")
    void shouldDegradeWhenRemoteFails() {
        when(deploymentMapper.selectById(DEPLOYMENT_ID)).thenReturn(deployment(SILENCE_ID));
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment((byte) 1));
        when(client.getSilence(SILENCE_ID)).thenThrow(new RuntimeException("connection refused"));
        when(eventMapper.selectList(any())).thenReturn(List.of(event("DEPLOYMENT_SUCCEEDED", 4, null)));

        AiopsLinkVo vo = service.getByDeployment(DEPLOYMENT_ID);

        assertThat(vo.getSilence()).isNull();
        assertThat(vo.getSilenceMessage()).contains("查询失败").contains("connection refused");
        // 这一条是用例的核心：AIops 挂了，页面必须还能看到「这次发布发生过什么」
        assertThat(vo.getEvents()).hasSize(1);
    }

    @Test
    @DisplayName("事件按快照 sequence 排序并解析主机；快照损坏时按落库顺序兜底")
    void shouldOrderEventsBySnapshotSequence() {
        when(deploymentMapper.selectById(DEPLOYMENT_ID)).thenReturn(deployment(null));
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment((byte) 1));
        // 故意倒序返回，模拟只按 create_time 排出来的顺序
        when(eventMapper.selectList(any())).thenReturn(List.of(
                event("DEPLOYMENT_STARTED", 2, "{\"sequence\":2,\"server\":null}"),
                event("DEPLOYMENT_PREPARED", 1, "{\"sequence\":1,\"server\":null}"),
                event("DEPLOYMENT_HOST_SUCCESS", 3,
                        "{\"sequence\":3,\"server\":{\"serverId\":\"srv-1\",\"host\":\"47.103.12.12\"}}"),
                event("DEPLOYMENT_SUCCEEDED", 4, "not-a-json")));

        AiopsLinkVo vo = service.getByDeployment(DEPLOYMENT_ID);

        assertThat(vo.getEvents()).extracting("sequence").containsExactly(1, 2, 3, 4);
        assertThat(vo.getEvents()).extracting("eventType")
                .containsExactly("DEPLOYMENT_PREPARED", "DEPLOYMENT_STARTED",
                        "DEPLOYMENT_HOST_SUCCESS", "DEPLOYMENT_SUCCEEDED");
        // 主机来自快照里的 server 节点
        assertThat(vo.getEvents().get(2).getHost()).isEqualTo("47.103.12.12");
        assertThat(vo.getEvents().get(2).getServerId()).isEqualTo("srv-1");
        // 坏快照不阻断页面，只是退化成落库顺序
        assertThat(vo.getEvents().get(3).getEventTypeLabel()).isEqualTo("发布成功");
    }

    /**
     * 构造部署记录。
     *
     * @param silenceId 静默窗 ID，为空表示本次没建窗
     * @return 部署记录
     */
    private Deployment deployment(String silenceId) {
        Deployment deployment = new Deployment();
        deployment.setId(DEPLOYMENT_ID);
        deployment.setProjectId("project-1");
        deployment.setEnvironmentId(ENVIRONMENT_ID);
        deployment.setAiopsSilenceId(silenceId);
        return deployment;
    }

    /**
     * 构造环境记录。
     *
     * @param aiopsEnabled 环境级开关
     * @return 环境记录
     */
    private Environment environment(Byte aiopsEnabled) {
        Environment environment = new Environment();
        environment.setId(ENVIRONMENT_ID);
        environment.setCode("lx-prod");
        environment.setName("生产环境");
        environment.setAiopsEnabled(aiopsEnabled);
        return environment;
    }

    /**
     * 构造事件记录。
     *
     * @param eventType   事件类型编码
     * @param fallbackSeq 落库顺序（用于断言快照缺失时的兜底）
     * @param requestBody 报文快照
     * @return 事件记录
     */
    private AiopsEvent event(String eventType, int fallbackSeq, String requestBody) {
        AiopsEvent event = new AiopsEvent();
        event.setId("evt-" + fallbackSeq);
        event.setDeploymentId(DEPLOYMENT_ID);
        event.setEventType(eventType);
        event.setIdempotencyKey(DEPLOYMENT_ID + ":" + eventType);
        event.setStatus(1);
        event.setRetryCount(0);
        event.setRequestBody(requestBody);
        event.setCreateTime(LocalDateTime.of(2026, 11, 2, 2, 31, fallbackSeq));
        event.setUpdateTime(LocalDateTime.of(2026, 11, 2, 2, 31, fallbackSeq));
        return event;
    }
}
