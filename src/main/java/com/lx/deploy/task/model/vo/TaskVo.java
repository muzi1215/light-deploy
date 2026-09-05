package com.lx.deploy.task.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步任务响应对象。
 *
 * <p>VO 只用于 Controller 出参，由 TaskService 从数据库实体转换而来。
 * 敏感字段不会生成到 VO，避免密码、密文、Token 或私钥被返回给调用方。</p>
 *
 * <p>前端依据 status 渲染状态标签、依据 progress 渲染进度条，
 * 枚举取值与后端 TaskStatus/TaskType 保持一致，变更时需两侧同步。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:00
 */
@Data
@Schema(description = "异步任务响应数据")
public class TaskVo {

    /** 数据主键。 */
    private String id;

    /** 创建时间，即任务提交时间。 */
    private LocalDateTime createTime;

    /** 更新时间，随状态流转和进度更新变化。 */
    private LocalDateTime updateTime;

    /**
     * 任务编号，业务唯一标识。
     *
     * <p>格式 TASK-yyyyMMddHHmmss-8位随机段，页面展示与人工检索使用。</p>
     */
    @Schema(description = "任务编号")
    private String taskNo;

    /**
     * 任务类型。
     *
     * <p>取值 SERVER_TEST / BUILD / DEPLOY，与后端 TaskType 枚举一致。</p>
     */
    @Schema(description = "任务类型：SERVER_TEST/BUILD/DEPLOY")
    private String taskType;

    /**
     * 关联业务主键。
     *
     * <p>按任务类型指向对应业务记录，前端可据此跳转关联资源详情。</p>
     */
    @Schema(description = "关联业务主键")
    private String bizId;

    /**
     * 任务状态。
     *
     * <p>取值 QUEUED / RUNNING / SUCCESS / FAILED / CANCELED，与后端 TaskStatus 枚举一致；
     * 终态任务不允许取消，非终态任务可人工取消。</p>
     */
    @Schema(description = "任务状态：QUEUED/RUNNING/SUCCESS/FAILED/CANCELED")
    private String status;

    /**
     * 当前执行阶段。
     *
     * <p>执行器汇报的阶段描述，排队中任务该字段为空。</p>
     */
    @Schema(description = "当前执行阶段")
    private String currentStage;

    /**
     * 任务进度。
     *
     * <p>0 到 100，页面据此渲染进度条。</p>
     */
    @Schema(description = "任务进度，0到100")
    private Integer progress;

    /**
     * 任务开始时间。
     *
     * <p>任务进入 RUNNING 时写入，排队中任务为空。</p>
     */
    @Schema(description = "任务开始时间")
    private LocalDateTime startedAt;

    /**
     * 任务结束时间。
     *
     * <p>任务进入终态时写入，未结束任务为空。</p>
     */
    @Schema(description = "任务结束时间")
    private LocalDateTime finishedAt;

    /**
     * 任务失败原因。
     *
     * <p>仅 FAILED 状态有值，服务端保证不含敏感信息。</p>
     */
    @Schema(description = "任务失败原因，仅失败状态有值")
    private String errorMessage;

}
