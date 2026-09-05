package com.lx.deploy.project.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 部署环境。
 *
 * <p>发布链路的第二层实体，回答"发到哪"：定义 dev/test/prod 等环境及其部署策略
 * （单目标/全量/滚动），项目环境目标（project_environment_target）按环境维度绑定服务器。
 * 删除保护：仍被部署目标引用时拒绝删除。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。
 * 唯一键 uk_environment_code 保证编码唯一；修改字段时应同步更新数据库 DDL。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@TableName("environment")
@Schema(description = "部署环境")
public class Environment extends BaseEntity {

    /**
     * 环境编码，业务唯一标识。
     *
     * <p>必填，匹配 ^[a-z][a-z0-9-]{1,31}$（如 dev/test/prod）；
     * Service 层唯一性预检 + 数据库唯一键兜底。
     * 设计上约定常用环境但不做白名单，允许自定义（如 staging）。</p>
     */
    @TableField("code")
    @Schema(description = "环境编码，小写字母开头，如dev/test/prod")
    private String code;

    /**
     * 环境名称。
     *
     * <p>面向页面的显示名称，如"开发环境"，必填。</p>
     */
    @TableField("name")
    @Schema(description = "环境名称")
    private String name;

    /**
     * 部署策略，取值见 {@code DeployStrategy} 枚举。
     *
     * <p>SINGLE_TARGET 单目标 / ALL_AT_ONCE 全量发布 / ROLLING 滚动发布；
     * 未指定时 Service 填充默认值 SINGLE_TARGET。任一批次失败后停止后续批次。</p>
     */
    @TableField("deploy_strategy")
    @Schema(description = "部署策略：SINGLE_TARGET/ALL_AT_ONCE/ROLLING，默认SINGLE_TARGET")
    private String deployStrategy;

    /**
     * 滚动发布批次大小。
     *
     * <p>取值大于等于 1，未指定时 Service 填充默认值 1；
     * 仅 ROLLING 策略生效，表示每批同时更新的主机数量。</p>
     */
    @TableField("batch_size")
    @Schema(description = "滚动发布批次大小，最小1，默认1")
    private Integer batchSize;

    /**
     * 默认目标服务器 ID，指向 server 表。
     *
     * <p>选填，Service 校验其真实存在；用于项目首次绑定环境时的默认建议目标，
     * 不替代项目环境目标的显式绑定。</p>
     */
    @TableField("default_server_id")
    @Schema(description = "默认目标服务器ID，选填，存在性校验")
    private String defaultServerId;

    /**
     * 是否启用 AIops 联动。
     *
     * <p>0 关闭，1 开启，未指定时 Service 填充默认值 0；
     * 开启后发布期间自动创建 AIops 告警静默窗口，发布稳定后关闭（静默必须带自动过期）。</p>
     */
    @TableField("aiops_enabled")
    @Schema(description = "是否启用AIops联动：0关闭，1开启")
    private Byte aiopsEnabled;

    /**
     * 环境描述。
     *
     * <p>面向页面的用途说明，选填。</p>
     */
    @TableField("description")
    @Schema(description = "环境描述")
    private String description;

}
