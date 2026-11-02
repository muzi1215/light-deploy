package com.lx.deploy.aiops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AIops 联动配置。
 *
 * <p>全部配置集中在 {@code light-deploy.aiops} 下，通过 application.yaml 或环境变量注入。
 * 默认 {@code enabled=false}：没有部署 AIops 的环境，平台行为与接入前完全一致，
 * 不会因为缺少对端而报错或阻塞发布。</p>
 *
 * <p><b>降级原则：</b>{@code failOpen=true} 表示 AIops 不可用时发布照常进行（只记日志、事件进重试队列）；
 * 设为 false 则建静默失败会阻断发布，仅在「发布必须留痕」的严格环境使用。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
@Data
@Component
@ConfigurationProperties(prefix = "light-deploy.aiops")
public class AiopsProperties {

    /** 是否启用 AIops 联动。关闭时所有联动逻辑直接短路。 */
    private boolean enabled = false;

    /** AIops 服务地址，例如 http://127.0.0.1:2029。 */
    private String baseUrl = "http://127.0.0.1:2029";

    /** 服务间令牌，对应 AIops 侧 DEPLOY_OPENAPI_TOKEN。 */
    private String serviceToken = "";

    /** 调用方标识，对应 AIops 侧 X-Service-Id 白名单。 */
    private String serviceId = "light-deploy";

    /** 建连超时（毫秒）。AIops 在内网，建连失败要快速暴露而不是拖住发布线程。 */
    private int connectTimeoutMs = 3000;

    /** 读超时（毫秒）。 */
    private int readTimeoutMs = 5000;

    /** 事件推送最大重试次数，超过后标记为「已放弃」。 */
    private int retryMax = 5;

    /** 重试扫描间隔（秒）。 */
    private int retryIntervalSeconds = 30;

    /**
     * 静默窗补关对账扫描间隔（秒）。
     *
     * <p>发布收尾时关窗是一次性同步调用，若恰好撞上目标机（尤其是 AIops 自身）重启会失败，
     * 由对账任务按本间隔重试补关，避免窗口一直生效到 TTL。默认 60 秒。</p>
     */
    private int silenceReconcileIntervalSeconds = 60;

    /**
     * 补关对账只回看最近多少小时内的部署。
     *
     * <p>没必要反复扫描陈年历史：窗口 TTL 早已过期，补关也无意义。默认 24 小时。</p>
     */
    private int silenceReconcileWindowHours = 24;

    /** 事件有效期（小时）。超过该时长仍未送达的事件不再补发，避免发布结束后推送过期时序。 */
    private int expireHours = 2;

    /** 建静默失败时是否继续发布。true=继续（默认），false=阻断。 */
    private boolean failOpen = true;

    /**
     * 静默窗基准时长（分钟）。
     *
     * <p>平台按「基准 + 每台目标 3 分钟余量」估算，AIops 侧还有 120 分钟硬上限兜底。</p>
     *
     * <p><b>为什么是 15 而不是更长：</b>正常路径下窗口根本活不到 TTL——发布收尾会主动关窗，
     * 关窗失败还有 {@code AiopsSilenceReconciler} 每 60 秒补关。TTL 只兜「关窗与补关都够不着」的
     * 最坏情况（平台在发布中途整体挂掉），此时窗口每多开一分钟，目标机的真实告警就多被压制一分钟。
     * 因此宁可让 TTL 短、由对账保证正常关闭，也不要用长 TTL 去「保证不会漏」——
     * 漏关的代价是误报警，多静默的代价是真故障被静音。</p>
     */
    private int silenceTtlMinutes = 15;

    /** 需要静默的告警类型，取值沿用 AIops 的 alarm_type_name（如「服务异常」）。 */
    private List<String> alertCategories = new ArrayList<>(List.of("服务异常"));

    /** 调用方声明永不静默的告警类型，AIops 侧另有硬编码底线，此处只是显式声明。 */
    private List<String> neverSilence = new ArrayList<>(List.of("系统资源", "内核", "网络", "MySQL连接池", "MySQL慢查询"));

    /**
     * 重试扫描间隔（毫秒），由 {@link #retryIntervalSeconds} 换算。
     *
     * <p>单独暴露是因为 {@code @Scheduled} 只认毫秒常量表达式，直接让它读秒再乘法会让
     * 定时任务的配置与 yaml 里的秒值脱钩。非法值回落到 30 秒。</p>
     *
     * @return 扫描间隔（毫秒）
     */
    public long getRetryIntervalMs() {
        return retryIntervalSeconds > 0 ? retryIntervalSeconds * 1000L : 30000L;
    }

    /**
     * 补关对账扫描间隔（毫秒），由 {@link #silenceReconcileIntervalSeconds} 换算。
     *
     * <p>与 {@link #getRetryIntervalMs()} 同理，单独暴露是因为 {@code @Scheduled}
     * 只认毫秒常量表达式。非法值回落到 60 秒。</p>
     *
     * @return 扫描间隔（毫秒）
     */
    public long getSilenceReconcileIntervalMs() {
        return silenceReconcileIntervalSeconds > 0 ? silenceReconcileIntervalSeconds * 1000L : 60000L;
    }
}
