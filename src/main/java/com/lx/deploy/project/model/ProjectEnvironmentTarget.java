package com.lx.deploy.project.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 项目环境部署目标。
 *
 * <p>发布链路的第三层实体，回答"具体发到哪台机器"：把「项目 × 环境」组合
 * 绑定到具体服务器，形成完整发布拓扑，发布模块按本表定义的目标顺序执行。
 * 三方 ID（项目/环境/服务器）写入前均由 Service 校验存在性，目标服务器必须启用。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。
 * 唯一键 uk_project_env_server 保证同一项目环境同一服务器只绑定一次；
 * 主目标标记同项目环境内互斥（最多一个），由 Service 在事务内自动降级旧主目标。
 * 修改字段时应同步更新数据库 DDL。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@TableName("project_environment_target")
@Schema(description = "项目环境部署目标")
public class ProjectEnvironmentTarget extends BaseEntity {

    /**
     * 项目 ID，指向 project 表。
     *
     * <p>必填，Service 通过 ProjectMapper 校验项目真实存在。</p>
     */
    @TableField("project_id")
    @Schema(description = "项目ID，指向project表")
    private String projectId;

    /**
     * 环境 ID，指向 environment 表。
     *
     * <p>必填，Service 通过 EnvironmentMapper 校验环境真实存在。</p>
     */
    @TableField("environment_id")
    @Schema(description = "环境ID，指向environment表")
    private String environmentId;

    /**
     * 目标服务器 ID，指向 server 表。
     *
     * <p>必填，Service 校验服务器存在且处于启用状态（status=1），
     * 停用服务器不允许绑定新目标。</p>
     */
    @TableField("server_id")
    @Schema(description = "目标服务器ID，指向server表，须为启用状态")
    private String serverId;

    /**
     * 是否为主目标。
     *
     * <p>0 普通目标，1 主目标；未指定时 Service 填充默认值 0。
     * 同一项目环境内最多一个主目标：设为新主目标时，Service 在同一事务内
     * 将旧主目标自动降级为普通目标。</p>
     */
    @TableField("is_primary")
    @Schema(description = "是否为主目标：0普通，1主目标，同项目环境内互斥")
    private Byte isPrimary;

    /**
     * 多目标发布顺序。
     *
     * <p>取值大于等于 1，未指定时 Service 填充默认值 1；
     * 滚动/全量发布按此顺序执行，查询结果默认按该字段升序排列。</p>
     */
    @TableField("deploy_order")
    @Schema(description = "多目标发布顺序，最小1，默认1")
    private Integer deployOrder;

    /**
     * 目标差异化运行配置 JSON。
     *
     * <p>选填，Service 校验合法 JSON 格式；覆盖项目默认运行配置，
     * 用于同一项目在不同服务器上的差异（如端口、内存参数）。</p>
     */
    @TableField("runtime_config_json")
    @Schema(description = "目标差异化运行配置JSON，格式校验不解析语义")
    private String runtimeConfigJson;

    /**
     * 目标状态，数字编码。
     *
     * <p>0 停用 / 1 启用，由服务端维护；未指定时 Service 填充默认值 1（启用）。
     * 停用的目标保留绑定关系，但不参与发布执行与批次编排。</p>
     */
    @TableField("status")
    @Schema(description = "状态：0停用，1启用，默认1")
    private Integer status;

}
