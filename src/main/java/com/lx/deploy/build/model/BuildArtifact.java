package com.lx.deploy.build.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 构建产物实体。
 *
 * <p>一次构建可能产生多个产物：Docker 分层构建会产出基础镜像 + 业务镜像，
 * JAR / STATIC 脚本构建产出单个文件产物。每个产物一条记录，挂在 build.id 下。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Data
@TableName("build_artifact")
@Schema(description = "构建产物")
public class BuildArtifact extends BaseEntity {

    /**
     * 所属构建任务 ID，关联 build.id。
     *
     * <p>服务端维护，构建过程中每产出一个镜像/文件即登记一条记录。</p>
     */
    @TableField("build_id")
    @Schema(description = "所属构建任务ID，关联build表")
    private String buildId;

    /**
     * 产物类型编码，取值见 {@code ArtifactType} 枚举。
     *
     * <p>BASE_IMAGE / APP_IMAGE 为镜像类，JAR / STATIC 为文件类，决定 artifact_digest 与 artifact_tag 的语义。</p>
     */
    @TableField("artifact_type")
    @Schema(description = "产物类型：BASE_IMAGE/APP_IMAGE/JAR/STATIC")
    private String artifactType;

    /**
     * 产物名称。
     *
     * <p>镜像类为镜像全名（含仓库地址与命名空间），文件类为文件名（如 auth-tool.jar）。</p>
     */
    @TableField("artifact_name")
    @Schema(description = "产物名称，镜像全名或文件名")
    private String artifactName;

    /**
     * 镜像标签或产物版本标签。
     *
     * <p>镜像类为 tag（如 v1.2.3 或 latest）；文件类为版本标签（如项目编码-提交短码）。</p>
     */
    @TableField("artifact_tag")
    @Schema(description = "镜像标签或产物版本标签")
    private String artifactTag;

    /**
     * 产物摘要。
     *
     * <p>镜像类存放镜像摘要（RepoDigests，如 sha256:...）；JAR / STATIC 文件类复用本列存放产物 SHA256，
     * 便于后续部署校验一致性（见 docs/构建模块.md）。</p>
     */
    @TableField("artifact_digest")
    @Schema(description = "镜像摘要(DOCKER)或产物SHA256(文件类)")
    private String artifactDigest;

    /**
     * 依赖摘要，预留与 build.dependency_hash 对齐。
     */
    @TableField("dependency_hash")
    @Schema(description = "依赖摘要，预留")
    private String dependencyHash;

    /**
     * 实际使用的 Dockerfile 路径。
     *
     * <p>仅镜像类产物有意义，记录本次构建基于哪个构建机上的 Dockerfile；文件类留空。</p>
     */
    @TableField("dockerfile_path")
    @Schema(description = "使用的Dockerfile路径，文件类留空")
    private String dockerfilePath;

    /**
     * 产物元数据（JSON 文本）。
     *
     * <p>预留字段，用于存放构建机上的产物绝对路径、体积等附加信息；v1 主要用于存放文件类产物的构建机绝对路径。</p>
     */
    @TableField("metadata_json")
    @Schema(description = "产物元数据JSON，含构建机绝对路径等")
    private String metadataJson;
}
