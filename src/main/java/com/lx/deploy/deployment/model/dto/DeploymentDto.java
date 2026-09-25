package com.lx.deploy.deployment.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 部署任务请求对象。
 *
 * <p>创建部署的请求体，同时作为列表/分页查询的条件对象（查询时字段可部分填写）。
 * 只携带「发什么、发到哪、用哪个版本」三要素，不暴露任何服务端维护字段：
 * 状态、操作者、起止时间、失败原因、AIops 静默窗口都由服务端写入。</p>
 *
 * <p>部署策略默认取自环境（environment.deploy_strategy），请求中显式传入 strategy 时覆盖，
 * 用于临时改用滚动或全量发布而不改动环境配置本身。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Data
@Schema(description = "部署任务请求")
public class DeploymentDto {

    /**
     * 关联项目 ID。
     *
     * <p>创建时必填，服务端校验项目存在且启用；运行时类型、运行配置与健康检查配置都取自该项目。</p>
     */
    @NotBlank(message = "项目ID不能为空")
    @Schema(description = "项目ID，必填")
    private String projectId;

    /**
     * 目标环境 ID。
     *
     * <p>创建时必填，服务端校验环境存在；默认部署策略与批次大小取自该环境。</p>
     */
    @NotBlank(message = "环境ID不能为空")
    @Schema(description = "环境ID，必填")
    private String environmentId;

    /**
     * 待发布的版本 ID。
     *
     * <p>创建时必填，服务端校验版本存在且处于「可发布」状态；
     * 已废弃或构建中的版本不允许发布。</p>
     */
    @NotBlank(message = "发布版本ID不能为空")
    @Schema(description = "发布版本ID，必填，须为可发布状态")
    private String releaseId;

    /**
     * 部署策略（可选覆盖）。
     *
     * <p>取值见 {@code DeployStrategy} 枚举；为空时使用环境配置的默认策略。
     * 传入非法编码时服务端直接拒绝，不会静默退化。</p>
     */
    @Schema(description = "部署策略，可选，覆盖环境默认值")
    private String strategy;

    /**
     * 部署状态，仅用于查询条件。
     *
     * <p>数字编码，取值见 {@code DeploymentStatus}；创建请求传入本字段会被服务端忽略，
     * 新部署一律从排队开始。</p>
     */
    @Schema(description = "部署状态，仅作为查询条件，创建时忽略")
    private Integer status;
}
