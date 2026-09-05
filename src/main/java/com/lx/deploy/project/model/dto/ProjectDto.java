package com.lx.deploy.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 部署项目请求对象。
 *
 * <p>Controller 的查询、新增和修改请求统一使用该 DTO，不直接接收 Project 数据库实体。
 * 主键、审计字段和软删除字段由服务端维护，调用方不能通过请求体修改。</p>
 *
 * <p><b>字段维护边界：</b>code/name/runtimeType 新增必填；sourceType/buildMode/
 * status/retainReleaseCount 选填，未传时由 Service 填充默认值；三段 JSON 配置
 * 选填但必须为合法 JSON（Service 校验格式，不校验内部结构）。
 * 修改请求同样要求 code 必填（唯一性预检排除自身）。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@Schema(description = "部署项目请求参数")
public class ProjectDto {

    /**
     * 项目编码。
     *
     * <p>新增/修改必填，匹配 ^[A-Za-z0-9_-]{2,64}$；Service 唯一性预检
     * （修改时排除自身），冲突返回 DATA_CONFLICT。查询时等值过滤。</p>
     */
    @Schema(description = "项目编码，2到64位字母数字下划线中划线")
    private String code;

    /**
     * 项目名称。
     *
     * <p>新增/修改必填；查询时等值过滤。</p>
     */
    @Schema(description = "项目名称")
    private String name;

    /**
     * 运行时类型，取值见后端 RuntimeType 枚举。
     *
     * <p>新增必填：DOCKER / JAR / STATIC，非法值返回参数校验错误；查询时等值过滤。</p>
     */
    @Schema(description = "运行时：DOCKER/JAR/STATIC")
    private String runtimeType;

    /**
     * 代码来源，取值见后端 SourceType 枚举。
     *
     * <p>选填，默认 GIT；传入 GIT 时 gitUrl 必填；查询时等值过滤。</p>
     */
    @Schema(description = "代码来源：GIT/UPLOAD，默认GIT")
    private String sourceType;

    /**
     * Git 仓库地址。
     *
     * <p>仅 sourceType=GIT 时必填；查询条件不使用该字段。</p>
     */
    @Schema(description = "Git仓库地址，GIT来源必填")
    private String gitUrl;

    /**
     * Git 分支。
     *
     * <p>选填；查询时等值过滤。</p>
     */
    @Schema(description = "Git分支")
    private String gitBranch;

    /**
     * Git 凭据 ID。
     *
     * <p>选填，指向 credential 表；凭据模块未实现前不做存在性校验。</p>
     */
    @Schema(description = "Git凭据ID，指向credential表")
    private String gitCredentialId;

    /**
     * 构建模式，取值见后端 BuildMode 枚举。
     *
     * <p>选填，默认 PLATFORM_TEMPLATE；仅 DOCKER 运行时生效；查询时等值过滤。</p>
     */
    @Schema(description = "构建模式，默认PLATFORM_TEMPLATE")
    private String buildMode;

    /**
     * 构建配置 JSON。
     *
     * <p>选填，必须为合法 JSON；不参与查询条件。</p>
     */
    @Schema(description = "构建配置JSON，必须为合法JSON")
    private String buildConfigJson;

    /**
     * 运行配置 JSON。
     *
     * <p>选填，必须为合法 JSON；不参与查询条件。</p>
     */
    @Schema(description = "运行配置JSON，必须为合法JSON")
    private String runtimeConfigJson;

    /**
     * 健康检查配置 JSON。
     *
     * <p>选填，必须为合法 JSON；不参与查询条件。</p>
     */
    @Schema(description = "健康检查配置JSON，必须为合法JSON")
    private String healthConfigJson;

    /**
     * 保留发布版本数量。
     *
     * <p>选填，最小 1，默认 5；发布模块按此数量保留可回滚历史版本。</p>
     */
    @Schema(description = "保留发布版本数量，最小1，默认5")
    private Integer retainReleaseCount;

    /**
     * 项目状态，取值见后端 EnableStatus 枚举。
     *
     * <p>选填，默认 ENABLED；查询时等值过滤。</p>
     */
    @Schema(description = "状态：ENABLED/DISABLED，默认ENABLED")
    private String status;

    /**
     * 项目描述。
     *
     * <p>选填；查询条件不使用该字段。</p>
     */
    @Schema(description = "项目描述")
    private String description;

}
