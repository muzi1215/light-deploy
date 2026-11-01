package com.lx.deploy.aiops.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * AIops 联动事件。
 *
 * <p>每一次对外推送（静默窗创建/关闭、发布生命周期事件）都先落本表再异步派发，
 * 因此「推送失败」只是状态字段变化，不会反噬发布流程。表结构在
 * {@code schema.sql} 中随建库脚本建好，升级已存在的库需执行对应 upgrade 脚本。</p>
 *
 * <p>双键设计：{@code eventId} 是本次投递的唯一标识（作为 HTTP 的 Idempotency-Key 传给 AIops），
 * {@code idempotencyKey} 是业务幂等键（如 {@code deploymentId:HOST_SUCCESS:serverId}），
 * 用于本地识别「同一业务事件重复投递」。两者不可混用——混用后重试时会用旧 eventId 发新事件。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aiops_event")
@Schema(description = "AIops联动事件")
public class AiopsEvent extends BaseEntity {

    /** 所属部署主任务 ID。 */
    @TableField("deployment_id")
    @Schema(description = "部署任务ID")
    private String deploymentId;

    /** 本次投递唯一标识，作为 HTTP Idempotency-Key。 */
    @TableField("event_id")
    @Schema(description = "事件ID")
    private String eventId;

    /** 事件类型编码，见 {@link AiopsEventType}。 */
    @TableField("event_type")
    @Schema(description = "事件类型")
    private String eventType;

    /** 业务幂等键，本地去重用。 */
    @TableField("idempotency_key")
    @Schema(description = "幂等键")
    private String idempotencyKey;

    /** 请求报文快照（已脱敏）。 */
    @TableField("request_body")
    @Schema(description = "请求内容")
    private String requestBody;

    /** 响应报文快照。 */
    @TableField("response_body")
    @Schema(description = "响应内容")
    private String responseBody;

    /** 调用状态：0 待发送 / 1 成功 / 2 失败 / 3 已放弃。 */
    @TableField("status")
    @Schema(description = "调用状态：0待发送，1成功，2失败，3已放弃")
    private Integer status;

    /** 已重试次数。 */
    @TableField("retry_count")
    @Schema(description = "重试次数")
    private Integer retryCount;

    /**
     * 下次重试时间；为空表示不需要重试。
     *
     * <p>{@code updateStrategy = ALWAYS}：投递成功后必须把该列清成 NULL，
     * 默认的 NOT_NULL 策略会跳过 null 字段，导致「已成功」的事件仍挂着一个过去的重试时间，
     * 排查时极易误判成「还在排队补发」。</p>
     */
    @TableField(value = "next_retry_time", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "下次重试时间")
    private LocalDateTime nextRetryTime;

    /**
     * 最近一次失败原因。
     *
     * <p>同样用 ALWAYS：重试成功后必须清空失败原因，否则成功记录会一直带着上一条错误信息。</p>
     */
    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "错误信息")
    private String errorMessage;
}
