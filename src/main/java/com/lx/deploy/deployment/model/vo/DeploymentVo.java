package com.lx.deploy.deployment.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部署任务响应对象。
 *
 * <p>Controller 对外返回部署记录时使用。在实体基础上补充了可读性字段（项目名、环境名、
 * 版本号、状态与策略文案）、目标汇总计数（总数/成功/失败）、关联的异步任务标识，
 * 以及回滚可行性判定（rollbackable / rollbackBlockReason），
 * 让页面一次请求就能渲染列表行、日志入口与回滚按钮的可用状态，不必再为每行补查接口。</p>
 *
 * <p>{@code version} 与 {@code currentVersion} 必须一起看：前者是本次要发布的版本（快照，不可变），
 * 后者是机器实际在跑的版本。回滚后两者不同，只看 version 会以为机器还跑着新版本。</p>
 *
 * <p>不暴露任何凭据、密文或 Token。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Data
@Schema(description = "部署任务响应")
public class DeploymentVo {

    /** 部署主键。 */
    @Schema(description = "部署主键")
    private String id;

    /** 关联项目 ID。 */
    @Schema(description = "项目ID")
    private String projectId;

    /** 项目名称，便于页面展示。 */
    @Schema(description = "项目名称")
    private String projectName;

    /** 目标环境 ID。 */
    @Schema(description = "环境ID")
    private String environmentId;

    /** 环境名称，便于页面展示。 */
    @Schema(description = "环境名称")
    private String environmentName;

    /** 发布版本 ID。 */
    @Schema(description = "发布版本ID")
    private String releaseId;

    /** 发布版本号，页面主要展示字段。 */
    @Schema(description = "发布版本号")
    private String version;

    /**
     * 各目标机器当前实际运行的版本，列表在已回滚时用它补一行「当前 xxx」。
     *
     * <p>与 {@link #version} 的区别：{@code version} 是「这次动作针对的发布版本」（取自
     * release_id，是记录创建时的快照，回滚后也不改写），而本字段回答「机器上现在跑的是什么」。
     * 两者在正常发布后相同，在回滚后不同——回滚把机器退回了历史版本，但这次动作原本发的版本没变。
     * 多目标版本不一致（部分回滚、部分首次发布）时为空，具体逐台值看目标明细。</p>
     */
    @Schema(description = "目标机器当前实际运行的版本")
    private String currentVersion;

    /** 部署策略编码，见 DeployStrategy 枚举。 */
    @Schema(description = "部署策略：SINGLE_TARGET/ALL_AT_ONCE/ROLLING")
    private String strategy;

    /** 部署策略中文文案，由服务端统一转换。 */
    @Schema(description = "部署策略文案")
    private String strategyText;

    /** 部署状态数字编码，见 DeploymentStatus。 */
    @Schema(description = "部署状态：0排队，1发布中，2成功，3失败，4已取消，5回滚中，6已回滚")
    private Integer status;

    /** 部署状态中文文案，由服务端统一转换。 */
    @Schema(description = "部署状态文案")
    private String statusText;

    /** 操作者名称。 */
    @Schema(description = "操作者名称")
    private String operatorName;

    /** AIops 静默窗口 ID，v1 仅占位。 */
    @Schema(description = "AIops静默窗口ID")
    private String aiopsSilenceId;

    /** 目标总数。 */
    @Schema(description = "目标总数")
    private Integer targetTotal;

    /** 已成功目标数。 */
    @Schema(description = "已成功目标数")
    private Integer targetSuccess;

    /** 已失败目标数。 */
    @Schema(description = "已失败目标数")
    private Integer targetFailed;

    /** 发布开始时间。 */
    @Schema(description = "开始时间")
    private LocalDateTime startedAt;

    /** 发布结束时间。 */
    @Schema(description = "结束时间")
    private LocalDateTime finishedAt;

    /** 失败原因。 */
    @Schema(description = "失败原因")
    private String errorMessage;

    /** 是否允许发起回滚，由服务端按状态与历史版本可用性判定。 */
    @Schema(description = "是否允许回滚")
    private Boolean rollbackable;

    /** 不可回滚的原因，可回滚时为空。 */
    @Schema(description = "不可回滚原因")
    private String rollbackBlockReason;

    /** 真正可回滚的目标数；不可回滚时为 0。 */
    @Schema(description = "可回滚目标数")
    private Integer rollbackTargetCount;

    /** 变更过但退不回去、将保持原版本的目标数。 */
    @Schema(description = "将跳过的目标数")
    private Integer rollbackSkippedCount;

    /** 关联的异步任务 ID，前端据此拉取发布日志。 */
    @Schema(description = "关联任务ID，用于拉取日志")
    private String taskId;

    /** 关联的异步任务编号。 */
    @Schema(description = "关联任务编号")
    private String taskNo;

    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /** 软删除标记。 */
    @Schema(description = "软删除标记")
    private Integer isDeleted;
}
