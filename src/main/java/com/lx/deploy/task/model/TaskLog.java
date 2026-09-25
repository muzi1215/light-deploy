package com.lx.deploy.task.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 任务执行日志。
 *
 * <p>任务执行过程的留痕记录，由任务执行器在执行过程中逐条写入，
 * 页面按 sequenceNo 升序展示，与实际执行顺序一致。
 * 注意与服务器本地 logs 文件互相独立：本地日志面向运维排障（含 requestId），
 * 任务日志面向页面展示执行过程。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:00
 */
@Data
@TableName("lds_task_log")
@Schema(description = "任务执行日志")
public class TaskLog extends BaseEntity {

    /**
     * 所属任务主键。
     *
     * <p>写入前由 TaskLogService 校验任务真实存在，避免日志悬空；
     * 写入后不允许通过普通更新接口变更。</p>
     */
    @TableField("task_id")
    @Schema(description = "所属任务主键")
    private String taskId;

    /**
     * 同一任务内的日志顺序号，从 1 开始递增。
     *
     * <p>由 TaskLogService 按任务内最大顺序号加一自动分配，调用方传入值一律忽略；
     * 数据库唯一键 uk_lds_task_log_sequence 保证任务内连续唯一。</p>
     */
    @TableField("sequence_no")
    @Schema(description = "任务内日志顺序号，服务端自动分配")
    private Long sequenceNo;

    /**
     * 日志所属任务阶段。
     *
     * <p>由执行器写入时标注，如 CONNECT（建立连接）、EXEC（执行命令），
     * 允许为空。</p>
     */
    @TableField("stage")
    @Schema(description = "日志所属任务阶段，由执行器标注")
    private String stage;

    /**
     * 日志级别，取值见 {@code TaskLogLevel} 枚举。
     *
     * <p>只分 INFO 与 ERROR，未传入时默认 INFO；写入后不允许修改。</p>
     */
    @TableField("log_level")
    @Schema(description = "日志级别：INFO/ERROR")
    private String logLevel;

    /**
     * 日志内容。
     *
     * <p>必填，空日志没有记录价值；禁止包含凭据、密钥等敏感信息。</p>
     */
    @TableField("content")
    @Schema(description = "日志内容，必填")
    private String content;

}
