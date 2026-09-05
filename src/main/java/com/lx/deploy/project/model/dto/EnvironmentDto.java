package com.lx.deploy.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 部署环境请求对象。
 *
 * <p>Controller 的查询、新增和修改请求统一使用该 DTO，不直接接收 Environment 数据库实体。
 * 主键、审计字段和软删除字段由服务端维护，调用方不能通过请求体修改。</p>
 *
 * <p><b>字段维护边界：</b>code/name 新增必填；deployStrategy/batchSize/aiopsEnabled
 * 选填，未传时由 Service 填充默认值；defaultServerId 选填但必须指向真实存在的服务器。
 * 修改请求同样要求 code 必填（唯一性预检排除自身）。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@Schema(description = "部署环境请求参数")
public class EnvironmentDto {

    /**
     * 环境编码。
     *
     * <p>新增/修改必填，匹配 ^[a-z][a-z0-9-]{1,31}$（如 dev/test/prod）；
     * Service 唯一性预检（修改时排除自身）。查询时等值过滤。</p>
     */
    @Schema(description = "环境编码，小写字母开头，如dev/test/prod")
    private String code;

    /**
     * 环境名称。
     *
     * <p>新增/修改必填；查询时等值过滤。</p>
     */
    @Schema(description = "环境名称")
    private String name;

    /**
     * 部署策略，取值见后端 DeployStrategy 枚举。
     *
     * <p>选填，默认 SINGLE_TARGET；取值 SINGLE_TARGET/ALL_AT_ONCE/ROLLING；
     * 查询时等值过滤。</p>
     */
    @Schema(description = "部署策略：SINGLE_TARGET/ALL_AT_ONCE/ROLLING，默认SINGLE_TARGET")
    private String deployStrategy;

    /**
     * 滚动发布批次大小。
     *
     * <p>选填，最小 1，默认 1；仅 ROLLING 策略生效。</p>
     */
    @Schema(description = "滚动发布批次大小，最小1，默认1")
    private Integer batchSize;

    /**
     * 默认目标服务器 ID。
     *
     * <p>选填，传入时 Service 校验服务器真实存在，不存在返回 DATA_NOT_FOUND；
     * 查询时等值过滤。</p>
     */
    @Schema(description = "默认目标服务器ID，选填，存在性校验")
    private String defaultServerId;

    /**
     * 是否启用 AIops 联动。
     *
     * <p>选填，0 关闭 1 开启，默认 0；查询时等值过滤。</p>
     */
    @Schema(description = "是否启用AIops联动：0关闭，1开启")
    private Byte aiopsEnabled;

    /**
     * 环境描述。
     *
     * <p>选填；查询条件不使用该字段。</p>
     */
    @Schema(description = "环境描述")
    private String description;

}
