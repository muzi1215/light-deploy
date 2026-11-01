package com.lx.deploy.aiops.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * AIops 静默窗展示对象。
 *
 * <p>数据来源是 AIops 的 {@code GET /openapi/v1/silences/{id}}，不是本平台库——
 * 平台只存了 {@code deployment.aiops_silence_id} 这一个标识，窗口的起止、覆盖主机、
 * 关闭原因都在对端。因此本对象可能整体为 null（没建窗 / 对端不可用），
 * 页面必须能处理这种「只有 ID 没有详情」的状态。</p>
 *
 * @author lx
 * @since 2026-11-02 03:10:00
 */
@Data
@Schema(description = "AIops静默窗展示")
public class AiopsSilenceVo {

    /** 静默窗 ID。 */
    @Schema(description = "静默窗ID")
    private String silenceId;

    /** 状态数字编码：0 生效中 / 1 已关闭 / 2 已过期。 */
    @Schema(description = "状态：0生效中，1已关闭，2已过期")
    private Integer status;

    /** 状态中文文案。 */
    @Schema(description = "状态文案")
    private String statusText;

    /** 是否已过期，由对端按到期时间实时计算（过期不落库，不能只看 status）。 */
    @Schema(description = "是否已过期")
    private Boolean expired;

    /** 生效开始时间。 */
    @Schema(description = "生效开始时间")
    private String startAt;

    /** 到期时间。 */
    @Schema(description = "到期时间")
    private String expiresAt;

    /** 关闭时间，未关闭为空。 */
    @Schema(description = "关闭时间")
    private String closeTime;

    /** 关闭原因（平台关窗时写入，如「发布成功」）。 */
    @Schema(description = "关闭原因")
    private String closeReason;

    /** 项目编码。 */
    @Schema(description = "项目编码")
    private String projectCode;

    /** 环境编码。 */
    @Schema(description = "环境编码")
    private String environmentCode;

    /** 覆盖的目标主机地址，跨平台匹配告警靠的就是它。 */
    @Schema(description = "覆盖的主机地址")
    private List<String> hosts;

    /** 覆盖的目标服务器 ID（平台主键）。 */
    @Schema(description = "覆盖的服务器ID")
    private List<String> serverIds;

    /** 静默的告警类型。 */
    @Schema(description = "静默的告警类型")
    private List<String> alertCategories;

    /** 建窗调用方标识。 */
    @Schema(description = "调用方标识")
    private String source;
}
