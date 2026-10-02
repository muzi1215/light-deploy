package com.lx.deploy.release.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发布版本实体。
 *
 * <p>构建成功后由构建执行链路自动创建一条「可发布」版本，作为部署任务的输入。
 * 一个项目可拥有多个发布版本，超出 {@code project.retain_release_count} 的历史版本在后续发布时被置为「已废弃」。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。
 * 注意：本表没有 build_id 列，构建与发布版本通过 project_id + commit_id + version 关联，
 * 保持「构建→发布版本→部署」松耦合链路（见 docs/构建模块.md）。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Data
@TableName("release_record")
@Schema(description = "发布版本")
public class ReleaseRecord extends BaseEntity {

    /**
     * 所属项目 ID，关联 project.id。
     *
     * <p>发布版本归属的项目，服务端维护，是回滚与保留版本数量统计的维度。</p>
     */
    @TableField("project_id")
    @Schema(description = "项目ID，关联project表")
    private String projectId;

    /**
     * 产物所在构建机 ID，关联 server.id。
     *
     * <p>由构建成功时从 build.build_server_id 冗余过来，服务端维护。
     * JAR / STATIC 发布时发布模块据此建立 SFTP 会话下载产物（本表与 build 无外键，
     * 发布链路只能依赖本字段定位构建机）；DOCKER 运行时留空，镜像从仓库拉取即可。</p>
     *
     * <p>历史版本可能为空（本列为后加能力），这类版本在文件类运行时无法完成分发，
     * 需要重新构建生成新版本。</p>
     */
    @TableField("build_server_id")
    @Schema(description = "产物所在构建机ID，JAR/STATIC部署时用于SFTP下载产物")
    private String buildServerId;

    /**
     * 发布版本号。
     *
     * <p>构建成功时生成，形如 项目编码-提交短码 或 项目编码-时间戳；与 is_deleted 组成唯一键，同一项目内不重复。</p>
     */
    @TableField("version")
    @Schema(description = "发布版本号")
    private String version;

    /**
     * 版本序号。
     *
     * <p>本版本在项目内的递增编号，服务端维护，是版本号字符串 {@code {项目编码}-v{序号}-{提交短码}}
     * 的权威来源，也是版本列表的排序依据。</p>
     *
     * <p>取号规则：构建成功时取该项目已有最大序号 +1（统计时不过滤软删除，避免序号被重用）；
     * 若发起构建时人工指定了序号，则采用指定值，并保证同一项目内不重复。</p>
     */
    @TableField("version_seq")
    @Schema(description = "版本序号，项目内递增")
    private Integer versionSeq;

    /**
     * Git 提交 ID。
     *
     * <p>本版本对应的提交，用于溯源；允许为空（如直接发布已有产物场景）。</p>
     */
    @TableField("commit_id")
    @Schema(description = "Git提交ID")
    private String commitId;

    /**
     * JAR / 静态产物在构建机上的绝对路径。
     *
     * <p>仅 JAR / STATIC 运行时填写，部署时从该路径 scp 到目标机；DOCKER 运行时留空。</p>
     */
    @TableField("artifact_path")
    @Schema(description = "JAR或静态产物路径，DOCKER运行时留空")
    private String artifactPath;

    /**
     * Docker 镜像标签。
     *
     * <p>仅 DOCKER 运行时填写，如 registry.cn-hangzhou.aliyuncs.com/lx/auth-tool:v1.2.3。</p>
     */
    @TableField("image_tag")
    @Schema(description = "Docker镜像标签，文件类运行时留空")
    private String imageTag;

    /**
     * Docker 镜像摘要。
     *
     * <p>仅 DOCKER 运行时填写，镜像推送后由 docker inspect 取 RepoDigests；用于部署时精确拉取。</p>
     */
    @TableField("image_digest")
    @Schema(description = "Docker镜像摘要，文件类运行时留空")
    private String imageDigest;

    /**
     * docker-compose.yml 原文快照。
     *
     * <p>仅 DOCKER 运行时且部署形态为 COMPOSE 时填写：构建阶段从仓库按
     * {@code build_config_json.compose_file_path} 读出来原文存盘，发布时原样下发到目标机工作目录。</p>
     *
     * <p>为什么不把它当产物走构建机持久目录：它是几 KB 的编排描述而不是可分发制品，
     * 落盘只会多一个需要回收的目录；留在版本记录里既能随版本精确回溯，
     * 回滚时也省掉一次文件传输。**平台不对这份内容做任何改写**——
     * 版本号通过独立的变量文件注入，见 {@code DockerDeployService}。</p>
     */
    @TableField("compose_content")
    @Schema(description = "docker-compose.yml 原文快照，DOCKER+COMPOSE 形态使用")
    private String composeContent;

    /**
     * DOCKER 运行时构建上下文 tarball 在构建机上的绝对路径。
     *
     * <p>仅 DOCKER 运行时且部署形态为 COMPOSE、且目标机指定了 work_dir 时填写：构建阶段把构建上下文
     * （仓库源码快照，排除 .git 与构建产物目录）打成 tarball 持久化到构建机产物目录，发布阶段随 compose
     * 一起下发到目标机工作目录并解包，方便运维上机 {@code docker build} 再次构建。</p>
     *
     * <p>存量版本（本列为后加能力）恒为空，发布阶段据此跳过上下文下发；镜像仍从仓库拉取，回滚不受影响。</p>
     */
    @TableField("context_artifact_path")
    @Schema(description = "DOCKER 构建上下文tarball路径，COMPOSE+work_dir时下发供上机重建")
    private String contextArtifactPath;

    /**
     * 产物 SHA256 摘要。
     *
     * <p>JAR / STATIC 运行时填写，由构建机 sha256sum 计算，部署前校验文件一致性。</p>
     */
    @TableField("sha256")
    @Schema(description = "产物SHA256摘要")
    private String sha256;

    /**
     * 运行时类型，取值见 {@code RuntimeType} 枚举。
     *
     * <p>DOCKER / JAR / STATIC；构建时从 project.runtime_type 复制，决定部署动作分支。</p>
     */
    @TableField("runtime_type")
    @Schema(description = "运行时类型：DOCKER/JAR/STATIC")
    private String runtimeType;

    /**
     * 版本状态数字编码，取值见 {@code ReleaseStatus} 常量。
     *
     * <p>0 构建中 / 1 可发布 / 2 已废弃；由构建与发布链路维护，调用方不可直接修改。</p>
     */
    @TableField("status")
    @Schema(description = "版本状态：0构建中，1可发布，2已废弃")
    private Integer status;

    /**
     * 创建人。
     *
     * <p>过渡期记录操作者，接入统一权限中心后由外部用户 ID 替代。</p>
     */
    @TableField("created_by")
    @Schema(description = "创建人")
    private String createdBy;
}
