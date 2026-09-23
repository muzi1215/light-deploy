package com.lx.deploy.task.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 任务日志增量拉取响应。
 *
 * <p>供任务详情页实时查看执行过程：前端首次请求不传 lastSeq，取得最新一批日志后记住
 * {@link #lastSeq}，之后每次带着它请求，只拿增量。相比按页轮询，增量拉取不会出现
 * 日志重复、乱序或漏读，也不需要在页面侧做去重。</p>
 *
 * <p>响应同时带上任务状态与是否终态：前端据此在同一请求里判断「是否还需要继续拉」，
 * 不必额外查询任务状态。当前阶段的实时推送方式是 1.5 秒轮询，后续可平滑替换为 SSE，
 * 替换时只需保持本 VO 的字段语义不变。</p>
 *
 * @author lx
 * @since 2026-09-23 02:20:00
 */
@Data
@Schema(description = "任务日志增量拉取结果")
public class TaskLogPullVo {

    /** 任务主键。 */
    @Schema(description = "任务主键")
    private String taskId;

    /** 任务状态数字编码，含义见 com.lx.deploy.common.constants.TaskStatus。 */
    @Schema(description = "任务状态：0排队中，1执行中，2成功，3失败，4已取消")
    private Integer status;

    /** 任务状态中文描述，由服务端统一转换，避免前端各自维护状态文案。 */
    @Schema(description = "任务状态中文描述")
    private String statusText;

    /** 是否已进入终态（成功/失败/已取消）；为 true 时前端停止轮询。 */
    @Schema(description = "是否已结束：true 表示成功/失败/已取消，前端应停止轮询")
    private Boolean finished;

    /** 当前执行阶段，由任务处理器通过 updateProgress 汇报。 */
    @Schema(description = "当前执行阶段")
    private String currentStage;

    /** 任务进度百分比，0 到 100。 */
    @Schema(description = "任务进度，0到100")
    private Integer progress;

    /** 任务失败原因；任务未失败时为空。 */
    @Schema(description = "任务失败原因，未失败时为空")
    private String errorMessage;

    /** 本次拉取到的日志，按顺序号升序排列。 */
    @Schema(description = "本次增量日志，按顺序号升序")
    private List<TaskLogVo> logs;

    /** 本次返回的最大顺序号，下次请求原样传入 lastSeq 即可继续取增量。 */
    @Schema(description = "本次返回的最大日志顺序号，下次请求作为 lastSeq 传入")
    private Long lastSeq;
}
