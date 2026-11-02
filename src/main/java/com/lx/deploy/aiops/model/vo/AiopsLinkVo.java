package com.lx.deploy.aiops.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 一次发布的 AIops 联动全景（静默窗 + 事件流水）。
 *
 * <p>聚合三处数据：本平台 `deployment`（窗口标识）、本平台 `aiops_event`（事件流水）、
 * AIops 静默窗详情（远程只读）。三个部分各自可能缺失，因此用
 * {@code globalEnabled} / {@code environmentAiopsEnabled} / {@code silence} / {@code silenceMessage}
 * 把「为什么这里是空的」直接交代清楚，避免页面出现无解释的空白。</p>
 *
 * <p><b>远程查询失败不抛异常</b>：AIops 不可用时只把 {@code silence} 留空并把原因放进
 * {@code silenceMessage}，联动详情页仍能展示事件流水——排障恰恰最需要这个页面，
 * 不能因为被排障对象挂了就打不开。</p>
 *
 * @author lx
 * @since 2026-11-02 03:10:00
 */
@Data
@Schema(description = "发布AIops联动全景")
public class AiopsLinkVo {

    /** 部署主键。 */
    @Schema(description = "部署主键")
    private String deploymentId;

    /** 平台级总闸是否开启（light-deploy.aiops.enabled）。 */
    @Schema(description = "平台AIops联动总闸是否开启")
    private Boolean globalEnabled;

    /** 该环境是否开启建窗（environment.aiops_enabled）。 */
    @Schema(description = "环境级AIops开关是否开启")
    private Boolean environmentAiopsEnabled;

    /** 平台记录的静默窗 ID，未建窗为空。 */
    @Schema(description = "静默窗ID")
    private String silenceId;

    /** 静默窗详情，未建窗或对端不可用时为空。 */
    @Schema(description = "静默窗详情")
    private AiopsSilenceVo silence;

    /** 远程查询静默窗失败时的原因，成功或未建窗时为空。 */
    @Schema(description = "静默窗查询失败原因")
    private String silenceMessage;

    /** 补关对账状态：窗口还开着且已过收尾宽限时，页面据此提示「补关中」。 */
    @Schema(description = "静默窗补关对账状态")
    private AiopsReconcileVo silenceReconcile;

    /** 事件流水，按 sequence 升序。 */
    @Schema(description = "事件流水")
    private List<AiopsEventVo> events;
}
