package com.lx.deploy.task.model.dto;

import com.lx.deploy.common.model.TimeRangeQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 异步任务请求对象。
 *
 * <p>Controller 的查询、新增和修改请求统一使用该 DTO，不直接接收 Task 数据库实体。
 * DTO 只用于 Controller 入参，避免接口层直接暴露数据库实体。</p>
 *
 * <p><b>字段维护边界：</b>
 * 任务编号可选（未传入时服务端自动生成）；任务状态、开始时间、结束时间为状态流转字段，
 * 普通新增/修改请求中传入无效——新增强制 QUEUED，修改时由 Service 剔除后忽略。
 * 主键和审计字段由服务端维护，调用方不能通过请求体修改。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:00
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "异步任务请求参数")
public class TaskDto extends TimeRangeQuery {

    /**
     * 任务编号。
     *
     * <p>查询时等值过滤；新增时可选，未传入由服务端按
     * yyyyMMddHHmmss+6位随机 的纯数字规则生成，传入时做唯一性校验。</p>
     */
    @Schema(description = "任务编号，新增时可选，未传入自动生成")
    private String taskNo;

    /**
     * 任务类型。
     *
     * <p>新增必填；取值必须为 SERVER_TEST、PROJECT_TEST、BUILD 或 DEPLOY，
     * 与后端 TaskType 枚举一致，非法值返回参数校验错误。</p>
     */
    @Schema(description = "任务类型：SERVER_TEST/PROJECT_TEST/BUILD/DEPLOY")
    private String taskType;

    /**
     * 关联业务主键。
     *
     * <p>按任务类型指向对应业务记录（服务器 ID / 项目 ID / 构建记录 ID / 部署记录 ID），可选。</p>
     */
    @Schema(description = "关联业务主键，按任务类型指向不同业务记录")
    private String bizId;

    /**
     * 任务状态，数字编码，由服务端维护。
     *
     * <p>0 排队中 / 1 执行中 / 2 成功 / 3 失败 / 4 已取消。仅查询时可用作等值过滤条件；
     * 新增/修改请求中传入无效，状态只能通过流转方法（markRunning/markSuccess/
     * markFailed/cancel）变更。</p>
     */
    @Schema(description = "任务状态：0排队中，1执行中，2成功，3失败，4已取消，仅查询时有效")
    private Integer status;

    /**
     * 当前执行阶段。
     *
     * <p>由任务执行器通过进度更新接口维护，普通请求传入无效。</p>
     */
    @Schema(description = "当前执行阶段，由执行器维护")
    private String currentStage;

    /**
     * 任务进度。
     *
     * <p>传入时必须在 0 到 100 之间，否则返回参数校验错误。</p>
     */
    @Schema(description = "任务进度，0到100")
    private Integer progress;

    /**
     * 任务开始时间。
     *
     * <p>状态流转字段，普通请求传入无效，由 markRunning 流转写入。</p>
     */
    @Schema(description = "任务开始时间，由状态流转维护")
    private LocalDateTime startedAt;

    /**
     * 任务结束时间。
     *
     * <p>状态流转字段，普通请求传入无效，由 markSuccess/markFailed/cancel 流转写入。</p>
     */
    @Schema(description = "任务结束时间，由状态流转维护")
    private LocalDateTime finishedAt;

    /**
     * 任务失败原因。
     *
     * <p>查询条件不使用该字段；标记失败必须通过流转接口携带，且禁止包含敏感信息。</p>
     */
    @Schema(description = "任务失败原因，通过流转接口维护")
    private String errorMessage;

}
