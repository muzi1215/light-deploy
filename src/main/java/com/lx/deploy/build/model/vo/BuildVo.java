package com.lx.deploy.build.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 构建任务响应对象。
 *
 * <p> Controller 对外返回构建记录时使用，在实体基础上补充了可读性字段（项目名、构建机名、
 * 状态文案）与关联的异步任务标识（taskId / taskNo），方便前端触发构建后直接拉取任务日志。
 * 不暴露任何凭据、密文或 Token。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Data
@Schema(description = "构建任务响应")
public class BuildVo {

    /** 构建主键。 */
    @Schema(description = "构建主键")
    private String id;

    /** 关联项目 ID。 */
    @Schema(description = "关联项目ID")
    private String projectId;

    /** 项目名称，便于页面展示。 */
    @Schema(description = "项目名称")
    private String projectName;

    /** 构建模式编码，见 BuildMode 枚举。 */
    @Schema(description = "构建模式")
    private String buildMode;

    /** 构建机服务器 ID。 */
    @Schema(description = "构建机服务器ID")
    private String buildServerId;

    /** 构建机名称，便于页面展示。 */
    @Schema(description = "构建机名称")
    private String buildServerName;

    /** 实际构建的 Git 提交 ID。 */
    @Schema(description = "Git提交ID")
    private String gitCommit;

    /** 依赖摘要，预留。 */
    @Schema(description = "依赖摘要")
    private String dependencyHash;

    /** 构建状态数字编码，见 BuildStatus。 */
    @Schema(description = "构建状态：0排队中，1构建中，2成功，3失败")
    private Integer status;

    /** 构建状态中文文案，由服务端统一转换。 */
    @Schema(description = "构建状态文案")
    private String statusText;

    /** 构建开始时间。 */
    @Schema(description = "构建开始时间")
    private LocalDateTime startedAt;

    /** 构建结束时间。 */
    @Schema(description = "构建结束时间")
    private LocalDateTime finishedAt;

    /** 构建失败原因。 */
    @Schema(description = "构建失败原因")
    private String errorMessage;

    /** 分层构建是否重新构建基础镜像（仅 LAYERED_DOCKER 生效）：true 重建，false 复用已有稳定 base。 */
    @Schema(description = "分层构建是否重新构建基础镜像")
    private Boolean rebuildBaseImage;

    /** 关联的异步任务 ID，前端据此拉取构建日志。 */
    @Schema(description = "关联任务ID，用于拉取日志")
    private String taskId;

    /** 关联的异步任务编号。 */
    @Schema(description = "关联任务编号")
    private String taskNo;

    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /** 软删除标记。 */
    @Schema(description = "软删除标记")
    private Integer isDeleted;
}
