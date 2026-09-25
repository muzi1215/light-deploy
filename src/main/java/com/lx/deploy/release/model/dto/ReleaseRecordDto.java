package com.lx.deploy.release.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 发布版本查询条件。
 *
 * <p>发布版本由构建链路自动创建，本模块当前只提供只读查询，
 * 因此 DTO 只保留查询条件，不提供任何可写字段——版本的新增与废弃分别由构建成功与版本保留策略触发。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Data
@Schema(description = "发布版本查询条件")
public class ReleaseRecordDto {

    /** 所属项目 ID；发布页按项目筛选可发布版本。 */
    @Schema(description = "项目ID")
    private String projectId;

    /** 版本状态数字编码，见 {@code ReleaseStatus}；不传时不过滤状态。 */
    @Schema(description = "版本状态：0构建中，1可发布，2已废弃")
    private Integer status;
}
