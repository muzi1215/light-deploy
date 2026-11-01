package com.lx.deploy.aiops.client;

import java.util.List;

/**
 * AIops 对外接口的报文模型。
 *
 * <p>集中放成嵌套 record，避免为十几个一次性结构各建一个文件。字段名与 AIops 侧
 * 输出的驼峰 JSON 严格一致（对方用 pydantic 的 alias_generator=to_camel 序列化），
 * 不额外加注解，靠名字对齐即可。</p>
 *
 * <p>时间统一用 ISO8601 字符串传递（如 {@code 2026-11-02T01:00:00+08:00}），
 * 避免两端时区/序列化配置不一致导致的时间漂移。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
public final class AiopsDtos {

    private AiopsDtos() {
    }

    /** AIops 统一响应外壳（与 StandardJSONResponse 一致）。 */
    public record Envelope<T>(Integer code, String message, Integer totalCount, T data) {
    }

    /** 目标服务器引用。 */
    public record ServerRef(String serverId, String host) {
    }

    /** 静默范围。 */
    public record Scope(String projectCode, String environmentCode, List<ServerRef> servers) {
    }

    /** 创建静默窗请求。 */
    public record SilenceCreateRequest(String idempotencyKey,
                                       String reason,
                                       Scope scope,
                                       List<String> alertCategories,
                                       List<String> neverSilence,
                                       String startAt,
                                       String expiresAt) {
    }

    /** 关闭静默窗请求。 */
    public record SilenceCloseRequest(String reason) {
    }

    /** 静默窗结果（建窗 / 关窗 / 延期回执）。 */
    public record SilenceData(String silenceId,
                              Integer status,
                              String startAt,
                              String expiresAt,
                              List<String> appliedCategories,
                              List<String> ignoredCategories,
                              Boolean duplicated) {
    }

    /**
     * 静默窗详情（只读查询结果）。
     *
     * <p>比 {@link SilenceData} 多出「谁建的、覆盖哪些主机、关没关、为什么关」，
     * 用于页面展示；写接口不回这些字段，所以单独一个 record，不给 {@link SilenceData} 加大半空字段。</p>
     */
    public record SilenceDetail(String silenceId,
                                String externalKey,
                                String projectCode,
                                String environmentCode,
                                List<String> serverIds,
                                List<String> hosts,
                                List<String> alertCategories,
                                String source,
                                Integer status,
                                Boolean expired,
                                String startAt,
                                String expiresAt,
                                String closeTime,
                                String closeReason) {
    }

    /** 项目引用。 */
    public record ProjectRef(String code, String name) {
    }

    /** 环境引用。 */
    public record EnvRef(String code, String name) {
    }

    /** 发布事件报文。 */
    public record EventRequest(String eventId,
                               String eventType,
                               String deploymentId,
                               String idempotencyKey,
                               Integer sequence,
                               ProjectRef project,
                               EnvRef environment,
                               String version,
                               String previousVersion,
                               String commit,
                               String strategy,
                               ServerRef server,
                               String status,
                               String occurredAt,
                               String operator,
                               String requestId) {
    }

    /** 事件接收结果。 */
    public record EventData(Boolean accepted, Boolean duplicated, String eventDbId, String idempotencyKey) {
    }

    /** 连通性探测结果。 */
    public record HealthData(String status, String service, String time, String caller) {
    }
}
