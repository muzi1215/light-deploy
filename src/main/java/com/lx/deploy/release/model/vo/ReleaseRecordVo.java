package com.lx.deploy.release.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发布版本响应对象。
 *
 * <p>Controller 对外返回发布版本时使用，主要用于发布模块选择「要发哪个版本」的下拉数据。
 * 在实体基础上补充状态文案，页面不需要自己翻译数字编码。</p>
 *
 * <p>产物路径（artifact_path）是构建机上的绝对路径，属于服务器内部信息但不含凭据，
 * 允许返回以便运维核对版本来源；镜像摘要与 SHA256 用于发布前的一致性核对。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Data
@Schema(description = "发布版本响应")
public class ReleaseRecordVo {

    /** 发布版本主键。 */
    @Schema(description = "发布版本主键")
    private String id;

    /** 所属项目 ID。 */
    @Schema(description = "项目ID")
    private String projectId;

    /** 发布版本号。 */
    @Schema(description = "发布版本号")
    private String version;

    /** 版本序号，项目内递增。 */
    @Schema(description = "版本序号，项目内递增，版本号据此排序")
    private Integer versionSeq;

    /** Git 提交 ID。 */
    @Schema(description = "Git提交ID")
    private String commitId;

    /** JAR / 静态产物在构建机上的绝对路径。 */
    @Schema(description = "产物路径，仅JAR/STATIC运行时有值")
    private String artifactPath;

    /** Docker 镜像标签。 */
    @Schema(description = "镜像标签，仅DOCKER运行时有值")
    private String imageTag;

    /** Docker 镜像摘要。 */
    @Schema(description = "镜像摘要")
    private String imageDigest;

    /** 产物 SHA256 摘要。 */
    @Schema(description = "产物SHA256摘要")
    private String sha256;

    /** 运行时类型，见 RuntimeType 枚举。 */
    @Schema(description = "运行时类型：DOCKER/JAR/STATIC")
    private String runtimeType;

    /** 版本状态数字编码，见 ReleaseStatus。 */
    @Schema(description = "版本状态：0构建中，1可发布，2已废弃")
    private Integer status;

    /** 版本状态中文文案，由服务端统一转换。 */
    @Schema(description = "版本状态文案")
    private String statusText;

    /** 产物所在构建机 ID，JAR / STATIC 分发时用于 SFTP 下载。 */
    @Schema(description = "产物所在构建机ID")
    private String buildServerId;

    /** 创建人。 */
    @Schema(description = "创建人")
    private String createdBy;

    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
