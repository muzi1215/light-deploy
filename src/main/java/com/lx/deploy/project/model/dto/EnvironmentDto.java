package com.lx.deploy.project.model.dto;

import com.lx.deploy.common.model.TimeRangeQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部署环境请求对象。
 *
 * <p>Controller 的查询、新增和修改请求统一使用该 DTO，不直接接收 Environment 数据库实体。
 * 主键、审计字段和软删除字段由服务端维护，调用方不能通过请求体修改。</p>
 *
 * <p><b>字段维护边界：</b>code/name 新增必填；deployStrategy/batchSize/aiopsEnabled
 * 选填，未传时由 Service 填充默认值。修改请求同样要求 code 必填（唯一性预检排除自身）。</p>
 *
 * <p><b>本类不接收任何服务器字段，这是刻意的：</b>环境是全局共享的，一个环境被多个项目引用，
 * 而各项目在该环境下绑定哪些机器由 project_environment_target 决定，环境层面给不出有意义的
 * 「默认服务器」（详见 AGENTS.md §24.11）。要表达「主要发这一台」请用部署目标的 is_primary。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "部署环境请求参数")
public class EnvironmentDto extends TimeRangeQuery {

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
