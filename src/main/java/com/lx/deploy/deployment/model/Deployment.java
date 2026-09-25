package com.lx.deploy.deployment.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部署主任务。
 *
 * <p>一次「把某个发布版本发到某个环境」的完整记录，是发布模块的聚合根：
 * 主任务维护整体状态、策略与操作者，具体发到哪台机器由 {@link DeploymentTarget} 展开。
 * 目标列表在创建时按 project_environment_target 的启用绑定快照生成，因此后续修改项目拓扑
 * 不会影响已经创建的部署记录——部署记录必须忠实反映「当时发了哪些机器」。</p>
 *
 * <p>状态机见 {@link DeploymentStatus}：排队 → 发布中 → 成功/失败/已取消，
 * 成功之后可回滚（回滚中 → 已回滚）。主任务状态由各目标状态汇总推导，
 * 不允许人工直接改写。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Data
@TableName("deployment")
@Schema(description = "部署主任务")
public class Deployment extends BaseEntity {

    /**
     * 所属项目 ID，关联 project.id。
     *
     * <p>创建时必填且由服务端校验项目存在并启用；运行时类型、运行配置与健康检查配置都来自该项目。</p>
     */
    @TableField("project_id")
    @Schema(description = "项目ID，关联project表")
    private String projectId;

    /**
     * 目标环境 ID，关联 environment.id。
     *
     * <p>创建时必填且由服务端校验环境存在；默认部署策略与批次大小取自该环境，
     * 请求中显式传入 strategy 时覆盖环境默认值。</p>
     */
    @TableField("environment_id")
    @Schema(description = "环境ID，关联environment表")
    private String environmentId;

    /**
     * 待发布的版本 ID，关联 release_record.id。
     *
     * <p>创建时必填且必须处于「可发布」（ReleaseStatus.RELEASEABLE）状态；
     * 版本携带镜像标签（DOCKER）或产物路径与构建机（JAR / STATIC），是本次分发的唯一输入。</p>
     */
    @TableField("release_id")
    @Schema(description = "发布版本ID，关联release_record表，须为可发布状态")
    private String releaseId;

    /**
     * 部署策略编码，取值见 {@code DeployStrategy} 枚举。
     *
     * <p>SINGLE_TARGET 单目标 / ALL_AT_ONCE 全量 / ROLLING 滚动；
     * 创建时从环境复制（请求可覆盖），执行器据此编排批次。
     * 该字段属于「分类」而非「状态」，沿用字符串编码（AGENTS.md §4.5）。</p>
     */
    @TableField("strategy")
    @Schema(description = "部署策略：SINGLE_TARGET/ALL_AT_ONCE/ROLLING")
    private String strategy;

    /**
     * 部署状态数字编码，取值见 {@link DeploymentStatus}。
     *
     * <p>0 排队 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 回滚中 / 6 已回滚。
     * 由服务端状态机维护，调用方不可通过创建或修改接口直接指定。</p>
     */
    @TableField("status")
    @Schema(description = "部署状态：0排队，1发布中，2成功，3失败，4已取消，5回滚中，6已回滚")
    private Integer status;

    /**
     * 操作者 ID。
     *
     * <p>过渡期记录发起部署的人，接入统一权限中心后由外部用户 ID 替代；服务端维护。</p>
     */
    @TableField("operator_id")
    @Schema(description = "操作者ID，服务端维护")
    private String operatorId;

    /**
     * 操作者名称。
     *
     * <p>页面展示用，服务端维护；与 operator_id 一起构成审计留痕。</p>
     */
    @TableField("operator_name")
    @Schema(description = "操作者名称，服务端维护")
    private String operatorName;

    /**
     * AIops 告警静默窗口 ID。
     *
     * <p>v1 只占位不发起真实调用：环境开启 AIops 联动后，后续版本在发布前创建静默、
     * 发布稳定后关闭，静默必须带自动过期时间（AGENTS.md §15）。可为空。</p>
     */
    @TableField("aiops_silence_id")
    @Schema(description = "AIops静默窗口ID，v1仅占位")
    private String aiopsSilenceId;

    /**
     * 发布开始时间。
     *
     * <p>执行器真正开始发布时写入，服务端维护。</p>
     */
    @TableField("started_at")
    @Schema(description = "开始时间，由状态流转维护")
    private LocalDateTime startedAt;

    /**
     * 发布结束时间。
     *
     * <p>成功、失败、取消或回滚完成时写入，服务端维护。</p>
     */
    @TableField("finished_at")
    @Schema(description = "结束时间，由状态流转维护")
    private LocalDateTime finishedAt;

    /**
     * 失败原因。
     *
     * <p>整体失败或回滚失败时填写，面向页面展示；禁止包含凭据、密钥等敏感信息。
     * 允许清空：目标全部恢复健康后重跑的场景需要把旧原因抹掉。</p>
     */
    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "失败原因，服务端维护，禁止含敏感信息")
    private String errorMessage;
}
