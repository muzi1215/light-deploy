package com.lx.deploy.aiops.client;

import com.lx.deploy.aiops.client.AiopsDtos.Envelope;
import com.lx.deploy.aiops.client.AiopsDtos.EventData;
import com.lx.deploy.aiops.client.AiopsDtos.EventRequest;
import com.lx.deploy.aiops.client.AiopsDtos.HealthData;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceCloseRequest;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceCreateRequest;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceData;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceDetail;
import com.lx.deploy.aiops.config.AiopsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * AIops 对外接口客户端。
 *
 * <p>只负责「把报文发出去、把响应解出来」，不做任何业务判断与降级决策——
 * 降级策略统一放在 {@code AiopsService}，避免同一个策略散落在两层。</p>
 *
 * <p><b>注意：</b>AIops 的业务失败（如令牌错误）同样以 HTTP 200 返回，
 * 只是响应体 code 非 200。因此不能只看 HTTP 状态码，必须解包 {@link Envelope#code()}。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
@Slf4j
@Component
public class AiopsClient {

    private static final ParameterizedTypeReference<Envelope<SilenceData>> SILENCE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Envelope<SilenceDetail>> SILENCE_DETAIL_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Envelope<EventData>> EVENT_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Envelope<HealthData>> HEALTH_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final AiopsProperties properties;
    private final RestClient restClient;

    public AiopsClient(AiopsProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        // 自己 new builder 而不是注入 RestClient.Builder：本项目没有引入 restclient starter，
        // 容器里没有该 Bean，注入会让应用在启用联动时直接起不来。
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 连通性探测。
     *
     * @return 对端可用返回 true；任何异常都返回 false，不抛出
     */
    public boolean ping() {
        try {
            Envelope<HealthData> envelope = restClient.get()
                    .uri("/openapi/v1/health")
                    .headers(this::applyAuth)
                    .retrieve()
                    .body(HEALTH_TYPE);
            HealthData data = unwrap(envelope, "探测 AIops");
            return data != null && "UP".equalsIgnoreCase(data.status());
        } catch (RuntimeException e) {
            log.debug("[AIops] 连通性探测失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建静默窗。
     *
     * @param request 建窗请求
     * @return 静默窗数据；业务失败或网络异常抛 {@link AiopsException}
     */
    public SilenceData createSilence(SilenceCreateRequest request) {
        try {
            Envelope<SilenceData> envelope = restClient.post()
                    .uri("/openapi/v1/silences")
                    .headers(this::applyAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(SILENCE_TYPE);
            return unwrap(envelope, "创建静默窗");
        } catch (RestClientException e) {
            throw new AiopsException("创建静默窗失败：" + e.getMessage(), e);
        }
    }

    /**
     * 关闭静默窗。
     *
     * @param silenceId AIops 返回的静默窗 ID
     * @param reason    关闭原因
     * @return 静默窗数据；业务失败或网络异常抛 {@link AiopsException}
     */
    public SilenceData closeSilence(String silenceId, String reason) {
        try {
            Envelope<SilenceData> envelope = restClient.post()
                    .uri("/openapi/v1/silences/{id}/close", silenceId)
                    .headers(this::applyAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SilenceCloseRequest(reason))
                    .retrieve()
                    .body(SILENCE_TYPE);
            return unwrap(envelope, "关闭静默窗");
        } catch (RestClientException e) {
            throw new AiopsException("关闭静默窗失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查询静默窗详情。
     *
     * <p>只读，供页面展示「这个窗口覆盖哪几台、什么时候关的、为什么关」。
     * 调用方必须容忍它失败——AIops 不可用时页面应当退化成「只有窗口 ID」，
     * 而不是整个联动详情打不开。</p>
     *
     * @param silenceId AIops 返回的静默窗 ID
     * @return 静默窗详情；业务失败或网络异常抛 {@link AiopsException}
     */
    public SilenceDetail getSilence(String silenceId) {
        try {
            Envelope<SilenceDetail> envelope = restClient.get()
                    .uri("/openapi/v1/silences/{id}", silenceId)
                    .headers(this::applyAuth)
                    .retrieve()
                    .body(SILENCE_DETAIL_TYPE);
            return unwrap(envelope, "查询静默窗");
        } catch (RestClientException e) {
            throw new AiopsException("查询静默窗失败：" + e.getMessage(), e);
        }
    }

    /**
     * 上报发布事件。
     *
     * @param eventId 本次投递唯一标识，作为幂等头
     * @param request 事件报文
     * @return 事件接收数据；业务失败或网络异常抛 {@link AiopsException}
     */
    public EventData pushEvent(String eventId, EventRequest request) {
        try {
            Envelope<EventData> envelope = restClient.post()
                    .uri("/openapi/v1/deployment-events")
                    .headers(this::applyAuth)
                    .header("Idempotency-Key", eventId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(EVENT_TYPE);
            return unwrap(envelope, "上报发布事件");
        } catch (RestClientException e) {
            throw new AiopsException("上报发布事件失败：" + e.getMessage(), e);
        }
    }

    /** 统一注入服务间凭据：令牌走 Authorization，调用方标识走自定义头。 */
    private void applyAuth(HttpHeaders headers) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getServiceToken());
        headers.set("X-Service-Id", properties.getServiceId());
    }

    /**
     * 解包统一响应外壳。
     *
     * @param envelope 响应外壳
     * @param action   动作描述，用于拼错误信息
     * @param <T>      业务数据类型
     * @return 业务数据
     */
    private <T> T unwrap(Envelope<T> envelope, String action) {
        if (envelope == null) {
            throw new AiopsException(-1, action + "失败：AIops 返回空响应");
        }
        Integer code = envelope.code();
        if (code == null || code != 200) {
            throw new AiopsException(code == null ? -1 : code,
                    action + "失败：" + (envelope.message() == null ? "未知错误" : envelope.message()));
        }
        return envelope.data();
    }
}
