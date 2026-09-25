package com.lx.deploy.project.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 部署项目。
 *
 * <p>发布链路的起点实体，回答"发布什么"：登记项目的运行时（DOCKER/JAR/STATIC）、
 * 代码来源（Git/上传）与构建模式，是构建和发布模块的配置来源。
 * 删除保护：仍被 project_environment_target 引用时拒绝删除。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。
 * 唯一键 uk_project_code 保证编码唯一；三段 JSON 配置只做格式校验不做语义解析，
 * 内部结构由构建/发布模块按运行时类型消费。修改字段时应同步更新数据库 DDL。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@TableName("project")
@Schema(description = "部署项目")
public class Project extends BaseEntity {

    /**
     * 项目编码，业务唯一标识。
     *
     * <p>必填，匹配 ^[A-Za-z0-9_-]{2,64}$；Service 层唯一性预检 +
     * 数据库唯一键 uk_project_code 兜底。修改接口同样强制校验编码必填，
     * 唯一性预检排除当前记录自身。</p>
     */
    @TableField("code")
    @Schema(description = "项目编码，字母数字下划线中划线，2到64位")
    private String code;

    /**
     * 项目名称。
     *
     * <p>面向页面的显示名称，必填。</p>
     */
    @TableField("name")
    @Schema(description = "项目名称")
    private String name;

    /**
     * 运行时类型，取值见 {@code RuntimeType} 枚举。
     *
     * <p>DOCKER 容器部署 / JAR systemd 部署 / STATIC 前端静态产物部署；
     * 必填，决定构建模式选项和发布动作分支。</p>
     */
    @TableField("runtime_type")
    @Schema(description = "运行时：DOCKER/JAR/STATIC")
    private String runtimeType;

    /**
     * 代码来源类型，取值见 {@code SourceType} 枚举。
     *
     * <p>GIT 仓库拉取 / UPLOAD 本地上传产物；未指定时 Service 填充默认值 GIT。
     * GIT 来源强制要求填写 gitUrl。</p>
     */
    @TableField("source_type")
    @Schema(description = "代码来源：GIT/UPLOAD，默认GIT")
    private String sourceType;

    /**
     * Git 仓库地址。
     *
     * <p>仅 GIT 来源必填，如 https://git.example.com/group/repo.git；UPLOAD 来源忽略。</p>
     *
     * <p>updateStrategy = ALWAYS：表单清空后必须真的落库为 NULL，否则切换来源或删掉地址时
     * 旧值会残留（详见 AGENTS.md §10.1「可清空字段」）。</p>
     */
    @TableField(value = "git_url", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "Git仓库地址，GIT来源必填")
    private String gitUrl;

    /**
     * Git 分支。
     *
     * <p>构建时使用的默认分支，如 main，选填。</p>
     *
     * <p>updateStrategy = ALWAYS：同 gitUrl，保证清空操作能落库为 NULL。</p>
     */
    @TableField(value = "git_branch", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "Git分支")
    private String gitBranch;

    /**
     * Git 凭据 ID，指向独立 credential 表。
     *
     * <p>私有仓库拉取时使用，选填（公开仓库留空）。Service 校验该凭据存在且类型为
     * GIT_TOKEN 或 SSH_KEY，凭据被项目引用期间不允许删除。VO 中允许返回
     * （只是外键引用，不含凭据内容）。</p>
     *
     * <p>updateStrategy = ALWAYS：页面把凭据下拉清空时必须真的解除绑定，
     * 否则凭据会一直被这个项目引用、无法删除。</p>
     */
    @TableField(value = "git_credential_id", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "Git凭据ID，指向credential表")
    private String gitCredentialId;

    /**
     * Docker 构建模式，取值见 {@code BuildMode} 枚举。
     *
     * <p>PLATFORM_TEMPLATE 平台模板 / PROJECT_DOCKERFILE 项目自带 Dockerfile /
     * LAYERED_DOCKER 分层构建（AIops 项目使用）/ SCRIPT_DEPLOY 脚本部署 /
     * ARTIFACT_ONLY 仅发布产物；
     * 未指定时 Service 填充默认值 PLATFORM_TEMPLATE。仅 DOCKER 运行时生效。</p>
     */
    @TableField("build_mode")
    @Schema(description = "构建模式，默认PLATFORM_TEMPLATE")
    private String buildMode;

    /**
     * 镜像仓库 ID，指向 registry 表。
     *
     * <p>只对 DOCKER 运行时有意义：构建出的镜像推开前需要知道推到哪个仓库
     * （AGENTS.md §23.4）。JAR / STATIC 运行时必须为空，Service 会校验这一点。
     * VO 中允许返回（只是外键引用，不含仓库密码）。</p>
     *
     * <p>updateStrategy = ALWAYS：运行时从 DOCKER 改成 JAR / STATIC 时，
     * 前端不再提交该字段，必须显式落库为 NULL，否则会残留一个与运行时矛盾的仓库绑定。</p>
     */
    @TableField(value = "registry_id", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "镜像仓库ID，仅DOCKER运行时使用，指向registry表")
    private String registryId;

    /**
     * 构建机服务器 ID，指向 server 表。
     *
     * <p>选填，回答「这个项目在哪台机器上构建」：既是「连通性测试」的执行目标，
     * 也是新建构建时构建机的默认来源（请求显式指定 &gt; 项目档案 &gt; 系统默认配置）。
     * 不填时回落到系统默认构建机。Service 校验该服务器存在且启用。</p>
     *
     * <p>updateStrategy = ALWAYS：页面清空构建机后必须真的解除绑定，
     * 否则项目会一直固定在一台已经不打算使用的机器上。</p>
     */
    @TableField(value = "build_server_id", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "构建机服务器ID，选填，指向server表")
    private String buildServerId;

    /**
     * 构建配置 JSON。
     *
     * <p>选填，Service 校验合法 JSON 格式但不解析语义，
     * 内部结构（如构建参数、镜像前缀）由构建模块按运行时类型定义和消费。</p>
     *
     * <p>updateStrategy = ALWAYS：表单清空该字段时 DTO 传空串、Service 归一为 null，
     * 必须显式写入 NULL 才能真正清除，否则 MyBatis-Plus 默认忽略 null 字段，
     * 会出现「能设置、不能清空」的问题。</p>
     */
    @TableField(value = "build_config_json", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "构建配置JSON，格式校验不解析语义")
    private String buildConfigJson;

    /**
     * 运行配置 JSON。
     *
     * <p>选填，与构建配置同等校验规则；发布模块消费（如 JVM 参数、
     * 容器端口映射、Nginx 路由等）。</p>
     *
     * <p>updateStrategy = ALWAYS：同构建配置，保证清空操作能落库为 NULL。</p>
     */
    @TableField(value = "runtime_config_json", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "运行配置JSON，格式校验不解析语义")
    private String runtimeConfigJson;

    /**
     * 健康检查配置 JSON。
     *
     * <p>选填，与构建配置同等校验规则；发布模块消费
     * （如检查路径、间隔、超时、成功阈值）。</p>
     *
     * <p>updateStrategy = ALWAYS：同构建配置，保证清空操作能落库为 NULL。</p>
     */
    @TableField(value = "health_config_json", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "健康检查配置JSON，格式校验不解析语义")
    private String healthConfigJson;

    /**
     * 保留发布版本数量。
     *
     * <p>取值大于等于 1，未指定时 Service 填充默认值 5；
     * 发布模块按此数量回滚并清理历史版本。</p>
     */
    @TableField("retain_release_count")
    @Schema(description = "保留发布版本数量，最小1，默认5")
    private Integer retainReleaseCount;

    /**
     * 项目状态，数字编码。
     *
     * <p>0 停用 / 1 启用，由服务端维护；未指定时 Service 填充默认值 1（启用）。
     * 只表示是否允许参与构建和发布，不表达开发、测试或生产环境。</p>
     */
    @TableField("status")
    @Schema(description = "状态：0停用，1启用，默认1")
    private Integer status;

    /**
     * 项目描述。
     *
     * <p>面向页面的用途说明，选填。</p>
     *
     * <p>updateStrategy = ALWAYS：文本域清空后要真的落库为 NULL，否则描述删不掉。</p>
     */
    @TableField(value = "description", updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "项目描述")
    private String description;

    /**
     * 创建人。
     *
     * <p>过渡期记录操作者，接入统一权限中心后由外部用户 ID 替代。</p>
     */
    @TableField("created_by")
    @Schema(description = "创建人")
    private String createdBy;

    /**
     * 更新人。
     *
     * <p>过渡期记录操作者，随修改更新。</p>
     */
    @TableField("updated_by")
    @Schema(description = "更新人")
    private String updatedBy;

}
