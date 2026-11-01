package com.lx.deploy.aiops.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AIops 联动事件展示对象。
 *
 * <p>一次发布会有多条（部署级 4 条 + 每台目标 1 条，见 {@code docs/AIops联动.md} §4），
 * 因此这是「流水」而不是「一条记录」。{@code sequence} 是同一次发布内的事件序号，
 * 页面按它排序才能还原真实时间线（`create_time` 在并发下可能同秒）。</p>
 *
 * @author lx
 * @since 2026-11-02 03:10:00
 */
@Data
@Schema(description = "AIops联动事件展示")
public class AiopsEventVo {

    /** 事件主键。 */
    @Schema(description = "事件主键")
    private String id;

    /** 事件类型编码，见 AiopsEventType。 */
    @Schema(description = "事件类型编码")
    private String eventType;

    /** 事件类型中文短名，由服务端按枚举翻译。 */
    @Schema(description = "事件类型文案")
    private String eventTypeLabel;

    /** 同一次发布内的事件序号，页面的排序依据。 */
    @Schema(description = "事件序号")
    private Integer sequence;

    /** 业务幂等键，重试时不变，用于与对端对账。 */
    @Schema(description = "幂等键")
    private String idempotencyKey;

    /** 投递状态：0 待发送 / 1 成功 / 2 失败待重试 / 3 已放弃。 */
    @Schema(description = "投递状态：0待发送，1成功，2失败待重试，3已放弃")
    private Integer status;

    /** 投递状态中文文案。 */
    @Schema(description = "投递状态文案")
    private String statusText;

    /** 已重试次数。 */
    @Schema(description = "重试次数")
    private Integer retryCount;

    /** 下次重试时间，无待重试时为空。 */
    @Schema(description = "下次重试时间")
    private LocalDateTime nextRetryTime;

    /** 最近一次投递失败原因，成功时为空。 */
    @Schema(description = "失败原因")
    private String errorMessage;

    /** 主机级事件的目标服务器 ID；部署级事件为空。 */
    @Schema(description = "目标服务器ID")
    private String serverId;

    /** 主机级事件的目标地址；部署级事件为空。 */
    @Schema(description = "目标服务器地址")
    private String host;

    /** 事件发生（平台落库）时间。 */
    @Schema(description = "事件时间")
    private LocalDateTime occurredAt;

    /** 对端回执时间（即 update_time），未送达为空。 */
    @Schema(description = "对端回执时间")
    private LocalDateTime deliveredAt;
}
