package com.lx.deploy.build.model.dto;

import com.lx.deploy.common.model.TimeRangeQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 构建任务请求对象。
 *
 * <p> Controller 创建构建时使用的请求体。构建的运行时、构建模式、代码来源与构建配置均来自
 * 关联项目（project 表与 build_config_json），因此本对象只携带构建目标与可选的构建机覆盖，
 * 不直接暴露服务端维护的字段（状态、Git 提交、时间、失败原因）。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "构建任务请求")
public class BuildDto extends TimeRangeQuery {

    /**
     * 关联项目 ID。
     *
     * <p>构建的目标项目，必填；决定运行时、构建模式与构建配置来源。创建时由服务端校验项目存在且启用。</p>
     */
    @NotBlank(message = "项目ID不能为空")
    @Schema(description = "关联项目ID，必填")
    private String projectId;

    /**
     * 构建机服务器 ID（可选覆盖）。
     *
     * <p>专用构建机方案下默认使用系统配置 {@code light-deploy.build.server-id}；
     * 填写时覆盖默认值，并校验该服务器存在且启用。</p>
     */
    @Schema(description = "构建机服务器ID，可选，覆盖系统默认构建机")
    private String buildServerId;

    /**
     * 版本序号（可选）。
     *
     * <p>留空时由平台在构建成功时自动取号（项目内最大序号 +1），这是常规用法的推荐值。
     * 填写时按指定序号生成版本号 {项目编码}-v{序号}-{提交短码}，并在创建阶段即校验该项目内
     * 未被占用——不拖到构建跑完才在版本落库时失败。</p>
     *
     * <p>仅在与外部发布流程对齐编号时使用，例如「这次对外就叫 v10」。</p>
     */
    @Min(value = 1, message = "版本序号必须大于 0")
    @Schema(description = "版本序号，可选；留空由平台自动递增取号")
    private Integer versionSeq;

    /**
     * 构建模式筛选条件（可选）。
     *
     * <p>取值对应 {@code BuildMode} 枚举 code（如 PROJECT_DOCKERFILE / LAYERED_DOCKER /
     * SCRIPT_DEPLOY / ARTIFACT_ONLY / JAR_PREBUILD）。列表页「构建模式」下拉筛选时由前端传入，
     * 为空表示不过滤；后端 {@code buildQueryWrapper} 仅在非空时拼接等值条件。</p>
     */
    @Schema(description = "构建模式筛选（BuildMode 枚举 code），可选")
    private String buildMode;

    /**
     * 分层构建是否重新构建基础镜像（可选覆盖）。
     *
     * <p>仅 {@code LAYERED_DOCKER} 模式生效：true 重新构建并推送稳定基础镜像，false（或不传）跳过、
     * 复用仓库中已有稳定基础镜像（{镜像仓库}:base）。为空时由后端按默认值处理（默认 false）。</p>
     * <p>基础层极少变化，绝大多数构建保持默认跳过即可；base 内容变更时显式传 true。</p>
     */
    @Schema(description = "分层构建是否重新构建基础镜像，可选；为空取默认值false")
    private Boolean rebuildBaseImage;

    /**
     * 构建状态筛选条件（可选）。
     *
     * <p>取值见 {@code BuildStatus} 常量类：0 排队中 / 1 构建中 / 2 成功 / 3 失败。
     * 列表页「状态」下拉筛选时由前端传入，为 null 表示不过滤。</p>
     */
    @Schema(description = "构建状态筛选（BuildStatus 编码），可选")
    private Integer status;
}
