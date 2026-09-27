package com.lx.deploy.audit.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志响应对象。
 *
 * <p>字段命名与数据库列不完全一致（如 {@code operName} 对应 {@code operator_name}、
 * {@code businessType} 对应 {@code action}），由 Service 显式转换：操作日志的字段是
 * 「给人看的」，沿用运维熟悉的 operXxx 命名比强凑列名更好读，代价只是多一段映射。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
@Data
@Schema(description = "操作日志")
public class AuditLogVo {

    /**
     * 主键，由服务端维护。
     */
    @Schema(description = "主键")
    private String id;

    /**
     * 请求追踪 ID，与日志文件中的 {@code %X{requestId}} 一致，是串联请求日志的线索。
     */
    @Schema(description = "请求追踪ID")
    private String traceId;

    /** 应用系统名称，来自配置 light-deploy.application-name。 */
    @Schema(description = "应用系统")
    private String application;

    /** 操作者 ID，取不到时为 system。 */
    @Schema(description = "操作者ID")
    private String operId;

    /** 操作者名称，取不到时为 system。 */
    @Schema(description = "操作者名称")
    private String operName;

    /** 操作模块，来自注解 title。 */
    @Schema(description = "操作模块")
    private String title;

    /** 操作类型编码，取值见 {@code BusinessType}。 */
    @Schema(description = "操作类型")
    private String businessType;

    /** 操作类型的中文描述，供页面直接展示。 */
    @Schema(description = "操作类型描述")
    private String businessTypeText;

    /**
     * 资源类型，来自 {@code @OperLog} 的 resourceType，页面按它选择字段名的中文映射。
     *
     * <p>只有声明了资源类型（即需要采集变更明细）的操作才有值。</p>
     */
    @Schema(description = "资源类型")
    private String resourceType;

    /** 被操作记录的主键，取自方法入参，语义同 {@link #resourceType}。 */
    @Schema(description = "资源主键")
    private String resourceId;

    /** 被切的方法，格式「类全名#方法名」。 */
    @Schema(description = "操作方法")
    private String method;

    /** HTTP 请求方法。 */
    @Schema(description = "HTTP请求方法")
    private String requestMethod;

    /** 请求地址，不含查询串。 */
    @Schema(description = "请求地址")
    private String operUrl;

    /** 操作 IP。 */
    @Schema(description = "操作IP")
    private String operIp;

    /** 操作系统，粗粒度解析结果。 */
    @Schema(description = "操作系统")
    private String os;

    /** 浏览器，粗粒度解析结果。 */
    @Schema(description = "浏览器")
    private String browser;

    /** 请求参数 JSON，敏感键已替换为 ***，超长已截断。 */
    @Schema(description = "请求参数，已脱敏")
    private String operParam;

    /** 返回结果 JSON，超长已截断；未开启记录时为空。 */
    @Schema(description = "返回结果")
    private String jsonResult;

    /**
     * 操作结果数字编码：0 失败，1 成功。
     *
     * <p>与 {@link #resultText} 是同一事实的两种表达：页面筛选用数字，展示用文字。</p>
     */
    @Schema(description = "操作结果：0失败，1成功")
    private Integer result;

    /** 操作结果中文描述。 */
    @Schema(description = "操作结果描述")
    private String resultText;

    /** 失败原因，成功时为空。 */
    @Schema(description = "失败原因")
    private String errorMessage;

    /** 操作耗时（毫秒）。 */
    @Schema(description = "操作耗时，单位毫秒")
    private Integer costTime;

    /**
     * 字段级变更明细，按字段原始顺序排列。
     *
     * <p>没有变更、未声明资源类型或快照取不到时为空数组而不是 null：
     * 页面不必再判空，直接用 {@code length === 0} 决定是否显示「无变更」占位。</p>
     */
    @Schema(description = "字段级变更明细")
    private List<AuditFieldChangeVo> changedFields = List.of();

    /** 操作时间。 */
    @Schema(description = "操作时间")
    private LocalDateTime createTime;
}
