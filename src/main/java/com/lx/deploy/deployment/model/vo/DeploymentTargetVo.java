package com.lx.deploy.deployment.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部署目标任务响应对象。
 *
 * <p>Controller 返回部署目标明细时使用，是页面「目标抽屉」与回滚确认的数据来源。
 * 在实体基础上补充服务器名称、状态文案与健康状态文案；
 * old_version 必须与 new_version 一起看：两者相同表示这台机器本来就是目标版本。</p>
 *
 * <p>不暴露服务器的 SSH 凭据、密文等任何敏感字段。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Data
@Schema(description = "部署目标任务响应")
public class DeploymentTargetVo {

    /** 目标主键。 */
    @Schema(description = "目标主键")
    private String id;

    /** 所属部署 ID。 */
    @Schema(description = "部署主任务ID")
    private String deploymentId;

    /** 目标服务器 ID。 */
    @Schema(description = "目标服务器ID")
    private String serverId;

    /** 目标服务器名称，便于页面展示。 */
    @Schema(description = "目标服务器名称")
    private String serverName;

    /** 目标服务器地址，便于运维直接定位机器。 */
    @Schema(description = "目标服务器地址")
    private String serverHost;

    /**
     * 发布顺序快照，创建部署时从部署目标配置固化。
     *
     * <p>页面按它展示「第几台发」，与执行器的实际执行顺序同源；
     * 历史部署该字段为空（当时未记录顺序）。</p>
     */
    @Schema(description = "发布顺序快照，创建部署时固化")
    private Integer deployOrder;

    /** 目标状态数字编码，见 DeploymentTargetStatus。 */
    @Schema(description = "目标状态：0排队，1发布中，2成功，3失败，4已取消，5已跳过")
    private Integer status;

    /** 目标状态中文文案，由服务端统一转换。 */
    @Schema(description = "目标状态文案")
    private String statusText;

    /** 发布前版本，回滚依据。 */
    @Schema(description = "发布前版本")
    private String oldVersion;

    /** 本次发布版本。 */
    @Schema(description = "本次发布版本")
    private String newVersion;

    /** 镜像标签，仅 DOCKER 运行时有值。 */
    @Schema(description = "镜像标签")
    private String imageTag;

    /** 健康检查状态数字编码，见 HealthStatus。 */
    @Schema(description = "健康检查状态：0未检查，1健康，2不健康")
    private Integer healthStatus;

    /** 健康检查状态中文文案，由服务端统一转换。 */
    @Schema(description = "健康检查状态文案")
    private String healthStatusText;

    /** 目标失败原因。 */
    @Schema(description = "目标失败原因")
    private String errorMessage;

    /** 目标开始时间。 */
    @Schema(description = "开始时间")
    private LocalDateTime startedAt;

    /** 目标结束时间。 */
    @Schema(description = "结束时间")
    private LocalDateTime finishedAt;

    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
