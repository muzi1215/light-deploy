package com.lx.deploy.task.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 任务执行日志请求对象。
 *
 * <p>Controller 的查询、新增和修改请求统一使用该 DTO，不直接接收 TaskLog 数据库实体。
 * DTO 只用于 Controller 入参，避免接口层直接暴露数据库实体。</p>
 *
 * <p><b>字段维护边界：</b>
 * sequenceNo 由服务端按任务内递增分配，调用方传入一律忽略；
 * 写入前服务端校验 taskId 对应任务真实存在；主键和审计字段由服务端维护。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:01
 */
@Data
@Schema(description = "任务执行日志请求参数")
public class TaskLogDto {

    /**
     * 所属任务主键。
     *
     * <p>查询时等值过滤（页面按任务查看日志的主要入口）；
     * 新增时必填且任务必须真实存在，修改请求中传入无效。</p>
     */
    @Schema(description = "所属任务主键，新增时必填")
    private String taskId;

    /**
     * 同一任务内的日志顺序号。
     *
     * <p>由服务端自动分配，任何请求中传入的值都会被忽略。</p>
     */
    @Schema(description = "任务内日志顺序号，服务端自动分配，传入无效")
    private Long sequenceNo;

    /**
     * 日志所属任务阶段。
     *
     * <p>查询时等值过滤；新增时可选，由执行器标注（如 CONNECT/EXEC）。</p>
     */
    @Schema(description = "日志所属任务阶段")
    private String stage;

    /**
     * 日志级别。
     *
     * <p>查询时等值过滤；新增时可选，取值必须为 INFO 或 ERROR，
     * 未传入默认 INFO；修改请求中传入无效。</p>
     */
    @Schema(description = "日志级别：INFO/ERROR")
    private String logLevel;

    /**
     * 日志内容。
     *
     * <p>新增时必填；修改时仅允许修正内容，禁止包含凭据等敏感信息。</p>
     */
    @Schema(description = "日志内容，新增时必填")
    private String content;

}
