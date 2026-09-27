package com.lx.deploy.deployment.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.constants.DeploymentTargetStatus;
import com.lx.deploy.common.constants.HealthStatus;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部署目标任务。
 *
 * <p>一次部署在单台服务器上的执行单元，是「健康检查结果」与「回滚依据」的实际落点：
 * old_version 记录发布前的版本，回滚时按它重新发布；new_version 是本次要发到的版本。
 * 两个版本相同的目标说明这台机器已经是目标版本，回滚时会被跳过。</p>
 *
 * <p>状态机见 {@link DeploymentTargetStatus}：排队 → 发布中 → 成功/失败/已取消，
 * 另有 5 已跳过表示滚动策略中止后本目标未被更新（回滚时不处理已跳过的目标）。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Data
@TableName("deployment_target")
@Schema(description = "部署目标任务")
public class DeploymentTarget extends BaseEntity {

    /**
     * 所属部署主任务 ID，关联 deployment.id。
     *
     * <p>创建时由服务端写入，目标必须挂在一次部署下才有意义。</p>
     */
    @TableField("deployment_id")
    @Schema(description = "部署主任务ID，关联deployment表")
    private String deploymentId;

    /**
     * 目标服务器 ID，关联 server.id。
     *
     * <p>由 project_environment_target 快照而来；发布时校验服务器仍存在且启用，
     * 停用服务器上的目标直接判失败，避免把异常隐藏成跳过。</p>
     */
    @TableField("server_id")
    @Schema(description = "目标服务器ID，关联server表")
    private String serverId;

    /**
     * 发布顺序快照，创建部署时从部署目标配置固化。
     *
     * <p><b>必须是快照而不是现查</b>：执行器按本字段升序决定「谁先发」，
     * 若改用 {@code create_time} 排序，同一次创建的多条目标在同一秒插入
     * （{@code DATETIME} 只到秒）会完全并列，MySQL 对并列行不保证稳定顺序，
     * 实际由随机 UUID 主键决定，表现为「配了顺序 1 却先发顺序 2」。
     * 若改为实时关联 project_environment_target 取顺序，则绑定被改序或删除会
     * 倒过来污染已经发生过的历史部署，与「deployment 是一次执行的不可变快照」冲突。</p>
     *
     * <p>由服务端在创建部署时写入，调用方不可指定；历史数据为 NULL。</p>
     */
    @TableField("deploy_order")
    @Schema(description = "发布顺序快照，创建部署时从部署目标配置固化")
    private Integer deployOrder;

    /**
     * 目标状态数字编码，取值见 {@link DeploymentTargetStatus}。
     *
     * <p>0 排队 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 已跳过；
     * 由服务端状态机维护，调用方不可直接指定。</p>
     */
    @TableField("status")
    @Schema(description = "目标任务状态：0排队，1发布中，2成功，3失败，4已取消，5已跳过")
    private Integer status;

    /**
     * 发布前版本。
     *
     * <p>回滚依据：记录这台机器在本次发布前的版本号，取自上一个成功部署的 new_version；
     * 首次发布时为空，表示该机器此前没有平台纳管的版本，回滚时只能判「无可回滚版本」。</p>
     */
    @TableField("old_version")
    @Schema(description = "发布前版本，回滚依据，首次发布时为空")
    private String oldVersion;

    /**
     * 本次发布版本。
     *
     * <p>等于 release_record.version，发布成功后成为该机器的当前版本。</p>
     */
    @TableField("new_version")
    @Schema(description = "本次发布版本")
    private String newVersion;

    /**
     * 本次实际部署的镜像标签。
     *
     * <p>DOCKER 运行时填写，取自 release_record.image_tag；执行器按它 docker pull。
     * JAR / STATIC 运行时为空（分发的是文件不是镜像）。</p>
     */
    @TableField("image_tag")
    @Schema(description = "镜像标签，仅DOCKER运行时填写")
    private String imageTag;

    /**
     * 健康检查状态数字编码，取值见 {@link HealthStatus}。
     *
     * <p>0 未检查 / 1 健康 / 2 不健康；允许为 NULL（未配置健康检查的项目）。
     * 不健康不改变「发布动作已成功」的事实，但会把目标整体判为失败——
     * 服务起不来的发布对用户而言就是失败。</p>
     */
    @TableField("health_status")
    @Schema(description = "健康检查状态：0未检查，1健康，2不健康")
    private Integer healthStatus;

    /**
     * 目标失败原因。
     *
     * <p>发布动作失败或健康检查不健康时填写，面向页面展示；禁止包含凭据。允许清空。</p>
     */
    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "目标失败原因，禁止含敏感信息")
    private String errorMessage;

    /**
     * 目标开始时间。
     *
     * <p>该目标开始发布时写入，服务端维护。</p>
     */
    @TableField("started_at")
    @Schema(description = "开始时间，由状态流转维护")
    private LocalDateTime startedAt;

    /**
     * 目标结束时间。
     *
     * <p>该目标到达终态时写入，服务端维护。</p>
     */
    @TableField("finished_at")
    @Schema(description = "结束时间，由状态流转维护")
    private LocalDateTime finishedAt;
}
