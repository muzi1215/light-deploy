package com.lx.deploy.build.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 构建产物响应对象。
 *
 * <p> Controller 返回构建产物列表时使用，补充产物类型中文文案。不暴露凭据等敏感信息。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Data
@Schema(description = "构建产物响应")
public class BuildArtifactVo {

    /** 产物主键。 */
    @Schema(description = "产物主键")
    private String id;

    /** 所属构建任务 ID。 */
    @Schema(description = "所属构建任务ID")
    private String buildId;

    /** 产物类型编码，见 ArtifactType 枚举。 */
    @Schema(description = "产物类型：BASE_IMAGE/APP_IMAGE/JAR/STATIC")
    private String artifactType;

    /** 产物类型中文文案。 */
    @Schema(description = "产物类型文案")
    private String artifactTypeText;

    /** 产物名称（镜像全名或文件名）。 */
    @Schema(description = "产物名称")
    private String artifactName;

    /** 镜像标签或产物版本标签。 */
    @Schema(description = "镜像标签或版本标签")
    private String artifactTag;

    /** 镜像摘要或产物 SHA256。 */
    @Schema(description = "镜像摘要(DOCKER)或SHA256(文件类)")
    private String artifactDigest;

    /** 使用的 Dockerfile 路径。 */
    @Schema(description = "Dockerfile路径")
    private String dockerfilePath;

    /** 产物元数据 JSON。 */
    @Schema(description = "产物元数据JSON")
    private String metadataJson;

    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
