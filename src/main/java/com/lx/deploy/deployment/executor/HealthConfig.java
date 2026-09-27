package com.lx.deploy.deployment.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 健康检查配置 JSON 解析载体。
 *
 * <p>对应 {@code project.health_config_json}，可被部署目标的 {@code runtime_config_json}
 * 中的同名键覆盖（合并规则见 DeployTaskHandler）。本类只做结构承载，不参与业务校验：
 * 字段缺失时由 {@link HealthChecker} 按默认值处理，不配置健康检查的 project 直接跳过检查。</p>
 *
 * <p>v1 支持两种探测方式：HTTP 探测（请求指定路径，2xx 视为健康）与 TCP 探测（端口可连通即健康）。
 * 远程命令探测留作后续扩展，当前不实现——它需要在目标机上执行任意 shell，
 * 配置与输出脱敏的复杂度远超收益。</p>
 *
 * <p>JSON 键为 snake_case，每个字段用 {@link JsonProperty} 显式绑定。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Data
public class HealthConfig {

    /** 探测方式：HTTP 或 TCP，默认 HTTP。 */
    @JsonProperty("type")
    private String type = "HTTP";

    /** HTTP 探测路径，默认 /；TCP 探测忽略本字段。 */
    @JsonProperty("path")
    private String path = "/";

    /** 探测端口：TCP 必填；HTTP 必填（平台直连目标端口）。 */
    @JsonProperty("port")
    private Integer port;

    /** 单次探测超时秒数，默认 3。 */
    @JsonProperty("timeout_seconds")
    private Integer timeoutSeconds = 3;

    /** 最大探测次数（含首次），默认 3；超过仍失败判为不健康。 */
    @JsonProperty("retry_count")
    private Integer retryCount = 3;

    /** 两次探测之间的等待秒数，默认 3；给应用留出启动时间。 */
    @JsonProperty("interval_seconds")
    private Integer intervalSeconds = 3;

    /**
     * 探测位置：TARGET 在目标机本地探测（默认），PLATFORM 从平台侧探测。
     *
     * <p>运维视角：填 TARGET 时平台通过 SSH 登录目标机检查端口是否监听，
     * 不受云防火墙/安全组影响，也不需要业务端口对平台放行；
     * 只有想验证「外部客户端能不能访问到」时才填 PLATFORM，
     * 此时平台必须能直达 {@code server.host:port}，否则会被防火墙拦成假失败。
     * 不填按 TARGET 处理。</p>
     */
    @JsonProperty("probe_from")
    private String probeFrom = "TARGET";
}
