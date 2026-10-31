package com.lx.deploy.build.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 构建任务实体。
 *
 * <p>一条 build 记录对应一次「针对某个项目在某台构建机上执行构建」的动作，由
 * {@code BuildService.createAndTrigger} 创建并同步生成一条 {@code lds_task}（BUILD 类型）驱动执行。
 * 构建成功后由执行器写入 git_commit、产物与发布版本（release_record）。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。
 * status / started_at / finished_at / git_commit / error_message 由构建执行链路维护，调用方不可直接修改。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Data
@TableName("build")
@Schema(description = "构建任务")
public class Build extends BaseEntity {

    /**
     * 项目 ID，关联 project.id。
     *
     * <p>构建的目标项目，由服务端在创建构建时写入，调用方提供；决定运行时、构建模式与构建配置来源。</p>
     */
    @TableField("project_id")
    @Schema(description = "项目ID，关联project表")
    private String projectId;

    /**
     * 关联的部署任务 ID，关联 deployment.id。
     *
     * <p>预留字段：当前「构建→发布版本→部署」是松耦合链路，构建不直接绑定某次部署，
     * 因此 v1 恒为空；后续若支持「为某次部署定向构建」再回填。</p>
     */
    @TableField("deployment_id")
    @Schema(description = "关联的部署任务ID，预留，当前为空")
    private String deploymentId;

    /**
     * 构建模式编码，取值见 {@code BuildMode} 枚举。
     *
     * <p>创建时从 project.build_mode 复制，服务端维护；执行器据此选择构建方式
     * （Dockerfile 构建 / 分层构建 / 脚本构建）。</p>
     */
    @TableField("build_mode")
    @Schema(description = "构建模式，见BuildMode枚举")
    private String buildMode;

    /**
     * 构建机服务器 ID，关联 server.id。
     *
     * <p>专用构建机方案下使用的构建执行机器，由配置默认构建机或请求显式指定；构建全程通过 SSH 连它执行。</p>
     */
    @TableField("build_server_id")
    @Schema(description = "构建机服务器ID，关联server表")
    private String buildServerId;

    /**
     * 实际构建所用的 Git 提交 ID。
     *
     * <p>由构建机克隆后执行 {@code git rev-parse HEAD} 回填，服务端维护；用于发布版本溯源。</p>
     */
    @TableField("git_commit")
    @Schema(description = "实际构建的Git提交ID，克隆后回填")
    private String gitCommit;

    /**
     * 手动指定的版本序号，可选。
     *
     * <p>发起构建时填写的序号，为空表示由构建成功时自动递增取号（取项目内最大序号 +1）。
     * 由服务端在创建构建时写入，创建时即校验项目内未被占用，避免构建跑完才在落库阶段失败。</p>
     *
     * <p>构建链路据此定稿发布版本号：序号一旦用于生成镜像 tag 与产物目录就不能再变，
     * 否则版本号与镜像、目录会彼此对不上。</p>
     */
    @TableField("version_seq")
    @Schema(description = "手动指定的版本序号，为空则自动递增取号")
    private Integer versionSeq;

    /**
     * 依赖文件摘要。
     *
     * <p>预留字段：用于依赖缓存命中判定（如 pom.xml/package-lock.json 的哈希），v1 不计算，恒为空。</p>
     */
    @TableField("dependency_hash")
    @Schema(description = "依赖文件摘要，预留")
    private String dependencyHash;

    /**
     * 分层构建是否重新构建基础镜像。
     *
     * <p>仅 {@code LAYERED_DOCKER} 模式生效：基础层通常是 OS + 运行时 + 依赖，体积大且极少变化，
     * 绝大多数构建只需重建 app 层，默认跳过可大幅节省构建机与仓库带宽。</p>
     * <p>false（默认）：跳过基础镜像的构建与推送，直接复用仓库中已存在的稳定基础镜像
     * （{镜像仓库}:base）；若仓库中尚无该稳定 base，执行器会 fail-fast 提示先重建一次。</p>
     * <p>true：重新构建并推送稳定基础镜像（覆盖仓库中同名 tag），base 内容变更时必须勾选。</p>
     * <p>开关在创建构建时固化进本记录、由异步执行任务读取，避免任务排队期间语义漂移；与部署的
     * {@code force_overwrite_config} 思路一致（默认跳过/保护，需要时显式勾选覆盖）。</p>
     */
    @TableField("rebuild_base_image")
    @Schema(description = "分层构建是否重新构建基础镜像，默认false（复用已有稳定base）")
    private Boolean rebuildBaseImage;

    /**
     * 构建状态数字编码，取值见 {@code BuildStatus} 常量。
     *
     * <p>0 排队中 / 1 构建中 / 2 成功 / 3 失败；由构建执行链路通过状态方法维护，调用方不可直接修改。</p>
     */
    @TableField("status")
    @Schema(description = "构建状态：0排队中，1构建中，2成功，3失败")
    private Integer status;

    /**
     * 构建开始时间，服务端维护。
     */
    @TableField("started_at")
    @Schema(description = "构建开始时间，服务端维护")
    private LocalDateTime startedAt;

    /**
     * 构建结束时间，服务端维护。
     */
    @TableField("finished_at")
    @Schema(description = "构建结束时间，服务端维护")
    private LocalDateTime finishedAt;

    /**
     * 构建失败原因。
     *
     * <p>失败时由执行器写入，面向页面展示；禁止包含凭据、密钥或 Token 等敏感信息。</p>
     */
    @TableField("error_message")
    @Schema(description = "构建失败原因，禁止含敏感信息")
    private String errorMessage;
}
