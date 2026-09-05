package com.lx.deploy.task.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务执行日志响应对象。
 *
 * <p>VO 只用于 Controller 出参，由 TaskLogService 从数据库实体转换而来。
 * 敏感字段不会生成到 VO，避免密码、密文、Token 或私钥被返回给调用方。</p>
 *
 * <p>前端按 sequenceNo 升序展示日志，与任务实际执行顺序一致；
 * 日志级别枚举与后端 TaskLogLevel 保持一致，变更时需两侧同步。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:01
 */
@Data
@Schema(description = "任务执行日志响应数据")
public class TaskLogVo {

    /** 数据主键。 */
    private String id;

    /** 日志写入时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;

    /**
     * 所属任务主键。
     *
     * <p>前端按任务 ID 查询日志列表的过滤条件。</p>
     */
    @Schema(description = "所属任务主键")
    private String taskId;

    /**
     * 同一任务内的日志顺序号。
     *
     * <p>从 1 开始递增，服务端保证任务内连续唯一，前端按此字段升序排列展示。</p>
     */
    @Schema(description = "任务内日志顺序号")
    private Long sequenceNo;

    /**
     * 日志所属任务阶段。
     *
     * <p>执行器标注的阶段描述（如 CONNECT/EXEC），可能为空。</p>
     */
    @Schema(description = "日志所属任务阶段")
    private String stage;

    /**
     * 日志级别。
     *
     * <p>取值 DEBUG / INFO / WARN / ERROR，与后端 TaskLogLevel 枚举一致，
     * 前端据此渲染不同颜色的级别标签。</p>
     */
    @Schema(description = "日志级别：DEBUG/INFO/WARN/ERROR")
    private String logLevel;

    /**
     * 日志内容。
     *
     * <p>该条日志的具体执行信息。</p>
     */
    @Schema(description = "日志内容")
    private String content;

}
