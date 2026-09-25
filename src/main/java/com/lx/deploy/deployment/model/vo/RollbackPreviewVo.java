package com.lx.deploy.deployment.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 回滚预览响应对象。
 *
 * <p>回答「点下回滚之后到底会发生什么」：本次能退回几台、哪些机器会跳过、每台从哪个版本
 * 退到哪个版本。判定结果与 {@code DeploymentService.evaluateRollback} 完全同源，
 * 因此预览里看到的，就是提交后真正会执行的。</p>
 *
 * <p>只读接口，不产生任何状态变更；不可回滚时 rollbackable 为 false，
 * blockReason 给出与接口拒绝时完全相同的原因文案。</p>
 *
 * @author lx
 * @since 2026-09-25 20:00:00
 */
@Data
@Schema(description = "回滚预览响应")
public class RollbackPreviewVo {

    /** 本次部署是否允许发起回滚。 */
    @Schema(description = "是否允许回滚")
    private Boolean rollbackable;

    /** 不可回滚的原因；可回滚时为空。 */
    @Schema(description = "不可回滚的原因")
    private String blockReason;

    /** 真正可回滚的目标数。 */
    @Schema(description = "可回滚目标数")
    private Integer rollbackCount;

    /** 本次变更过但退不回去、将保持原版本的目标数。 */
    @Schema(description = "将跳过的目标数")
    private Integer skippedCount;

    /** 本次部署的目标总数，用于表达「N / M 台」。 */
    @Schema(description = "目标总数")
    private Integer targetTotal;

    /** 逐目标明细，仅包含本次真正变更过的目标。 */
    @Schema(description = "逐目标回滚明细")
    private List<RollbackTargetPreviewVo> targets;
}
