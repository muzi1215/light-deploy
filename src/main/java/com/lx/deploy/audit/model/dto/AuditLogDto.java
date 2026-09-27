package com.lx.deploy.audit.model.dto;

import com.lx.deploy.common.model.TimeRangeQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 操作日志查询条件。
 *
 * <p>本模块只提供查询与详情：审计记录不允许改写（改写了就不再是审计），因此没有新增、
 * 修改接口对应的写入字段，DTO 全部字段都只用于过滤。</p>
 *
 * <p><b>查询口径：</b>操作人与操作模块用模糊匹配，其余用等值。日志检索场景下用户通常只记得
 * 「谁」或「哪个模块」的片段，等值匹配会让搜索几乎搜不到东西；而类型、请求方式、结果
 * 都是固定取值，等值匹配才符合下拉筛选的预期。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "操作日志查询条件")
public class AuditLogDto extends TimeRangeQuery {

    /**
     * 操作者名称，模糊匹配。
     *
     * <p>未接入登录态时后端记录的是 {@code system} 或前端带来的名称，按名称片段搜索即可。</p>
     */
    @Schema(description = "操作者名称，模糊匹配")
    private String operName;

    /**
     * 操作模块，模糊匹配，如「服务器」「发布」。
     */
    @Schema(description = "操作模块，模糊匹配")
    private String title;

    /**
     * 操作类型，等值匹配，取值见 {@code BusinessType}：INSERT/UPDATE/DELETE/EXPORT/IMPORT/
     * EXECUTE/ROLLBACK/CANCEL/OTHER。
     */
    @Schema(description = "操作类型，等值匹配")
    private String businessType;

    /**
     * HTTP 请求方法，等值匹配：GET / POST / PUT / DELETE。
     */
    @Schema(description = "HTTP请求方法，等值匹配")
    private String requestMethod;

    /**
     * 操作结果，等值匹配，取值见 {@code AuditResult}：0 失败，1 成功。
     */
    /**
     * 操作结果，等值匹配，取值见 {@code AuditResult}：0 失败，1 成功。
     */
    @Schema(description = "操作结果：0失败，1成功")
    private Integer result;

    /**
     * 创建时间区间由基类 {@link TimeRangeQuery} 提供。
     *
     * <p>早先这两个字段写在本类里（操作日志是第一个支持时间筛选的列表），
     * 现统一收进基类：全平台所有列表共用同一份「创建时间区间」语义，
     * 避免每个 DTO 各起一套命名与格式注解。</p>
     */
}
