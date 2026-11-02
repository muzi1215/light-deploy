package com.lx.deploy.aiops.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 静默窗补关对账状态。
 *
 * <p>回答联动详情页的一个具体疑问：「窗口还显示生效中，是没人管了，还是马上会关？」</p>
 *
 * <p>判定条件与后端对账任务 {@code AiopsSilenceReconciler} 的候选条件**完全一致**
 * （终态 + 有窗口 + 没有「静默已关」事件 + 终态已稳定超过宽限），
 * 页面上说「补关中」就等于「下一轮扫描一定会处理它」，不会出现页面乐观、任务其实没管的错位。</p>
 *
 * @author lx
 * @since 2026-11-03
 */
@Data
@Schema(description = "静默窗补关对账状态")
public class AiopsReconcileVo {

    /** 是否正处于「等待补关」：窗口没记「已关」且已过收尾宽限，下一轮扫描会重试关窗。 */
    @Schema(description = "是否正在等待补关")
    private Boolean pending;

    /** 对账扫描间隔（秒）：决定页面提示「多久内会重试」。 */
    @Schema(description = "对账扫描间隔（秒）")
    private Integer retryIntervalSeconds;

    /** 终态稳定宽限（秒）：终态后多久才开始补关，用于解释「为什么刚结束还没动静」。 */
    @Schema(description = "终态稳定宽限（秒）")
    private Integer graceSeconds;
}
