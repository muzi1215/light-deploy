package com.lx.deploy.task.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步任务。
 *
 * <p>平台所有长耗时操作（连通性测试、构建、发布）的生命周期载体。
 * 状态流转由 {@code TaskService} 的流转方法驱动，单向推进：
 * QUEUED → RUNNING → SUCCESS/FAILED，QUEUED/RUNNING 可被取消为 CANCELED；
 * 状态、起止时间不接受普通更新接口修改，防止绕过状态机。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。
 * 修改字段时应同步更新数据库 DDL 和本类注释。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:00
 */
@Data
@TableName("lds_task")
@Schema(description = "异步任务")
public class Task extends BaseEntity {

    /**
     * 任务编号，业务唯一标识。
     *
     * <p>未传入时由 TaskService 按 TASK-yyyyMMddHHmmss-8位随机段 生成，
     * 数据库唯一键 uk_lds_task_no 兜底防重。</p>
     */
    @TableField("task_no")
    @Schema(description = "任务编号，未传入时由服务端自动生成")
    private String taskNo;

    /**
     * 任务类型，取值见 {@code TaskType} 枚举。
     *
     * <p>SERVER_TEST 服务器连通性测试 / PROJECT_TEST 项目连通性测试 / BUILD 构建 / DEPLOY 发布，
     * 通过 bizId 关联各类型对应的具体业务记录。</p>
     */
    @TableField("task_type")
    @Schema(description = "任务类型：SERVER_TEST/PROJECT_TEST/BUILD/DEPLOY")
    private String taskType;

    /**
     * 关联业务主键。
     *
     * <p>按任务类型指向不同业务表：SERVER_TEST 指向服务器 ID，PROJECT_TEST 指向项目 ID，
     * BUILD 指向构建记录 ID，DEPLOY 指向部署记录 ID；允许为空。</p>
     */
    @TableField("biz_id")
    @Schema(description = "关联业务主键，按任务类型指向不同业务记录")
    private String bizId;

    /**
     * 任务状态，数字编码，由服务端状态机维护。
     *
     * <p>0 排队中 / 1 执行中 / 2 成功 / 3 失败 / 4 已取消，含义见
     * {@link com.lx.deploy.common.constants.TaskStatus}。只能通过 TaskService 的
     * markRunning/markSuccess/markFailed/cancel 流转方法变更，新建任务强制从 0 开始，
     * 终态（2/3/4）不允许再流转。普通更新接口不允许修改本字段。</p>
     */
    @TableField("status")
    @Schema(description = "任务状态：0排队中，1执行中，2成功，3失败，4已取消")
    private Integer status;

    /**
     * 当前执行阶段。
     *
     * <p>由任务执行器通过 updateProgress 汇报，用于页面展示当前执行到哪一步。</p>
     */
    @TableField("current_stage")
    @Schema(description = "当前执行阶段，由执行器汇报")
    private String currentStage;

    /**
     * 任务进度，0 到 100。
     *
     * <p>仅 RUNNING 状态允许更新；新建任务强制从 0 开始。</p>
     */
    @TableField("progress")
    @Schema(description = "任务进度，0到100")
    private Integer progress;

    /**
     * 任务开始时间。
     *
     * <p>由 markRunning 流转方法写入，普通更新接口不可修改。</p>
     */
    @TableField("started_at")
    @Schema(description = "任务开始时间，由状态流转维护")
    private LocalDateTime startedAt;

    /**
     * 任务结束时间。
     *
     * <p>由 markSuccess/markFailed/cancel 流转方法写入，普通更新接口不可修改。</p>
     */
    @TableField("finished_at")
    @Schema(description = "任务结束时间，由状态流转维护")
    private LocalDateTime finishedAt;

    /**
     * 任务失败原因。
     *
     * <p>markFailed 时必填，面向页面展示；禁止包含凭据、密钥等敏感信息。</p>
     */
    @TableField("error_message")
    @Schema(description = "任务失败原因，markFailed 时必填")
    private String errorMessage;

}
