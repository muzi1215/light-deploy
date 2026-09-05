package com.lx.deploy.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部署项目响应对象。
 *
 * <p>VO 只用于 Controller 出参，由 ProjectService 从数据库实体转换而来。
 * 敏感字段不会生成到 VO，避免密码、密文、Token 或私钥被返回给调用方。</p>
 *
 * <p>前端消费方式：runtimeType/sourceType/buildMode/status 渲染为标签，
 * 与后端枚举一一对应，变更时需两侧同步；gitCredentialId 允许返回
 * （只是 credential 表外键引用，不含凭据内容）。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@Schema(description = "部署项目响应数据")
public class ProjectVo {

    /**
     * 数据主键。
     *
     * <p>由服务端通过 IdGenerator 生成 32 位 UUID，调用方只读、不能通过请求体修改；
     * 前端用于行标识以及详情、编辑、删除接口的路径参数。</p>
     */
    @Schema(description = "主键ID")
    private String id;

    /**
     * 创建时间。
     *
     * <p>由 MyBatis-Plus 在插入时自动填充，服务端维护，调用方只读；列表默认按该字段倒序。</p>
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 更新时间。
     *
     * <p>由 MyBatis-Plus 在插入和更新时自动填充，服务端维护，调用方只读。</p>
     */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /**
     * 项目编码。
     *
     * <p>业务唯一标识，前端列表展示与搜索使用。</p>
     */
    @Schema(description = "项目编码")
    private String code;

    /**
     * 项目名称。
     *
     * <p>面向页面的显示名称。</p>
     */
    @Schema(description = "项目名称")
    private String name;

    /**
     * 运行时类型。
     *
     * <p>取值 DOCKER / JAR / STATIC，与后端 RuntimeType 枚举一致。</p>
     */
    @Schema(description = "运行时：DOCKER/JAR/STATIC")
    private String runtimeType;

    /**
     * 代码来源。
     *
     * <p>取值 GIT / UPLOAD，与后端 SourceType 枚举一致。</p>
     */
    @Schema(description = "代码来源：GIT/UPLOAD")
    private String sourceType;

    /**
     * Git 仓库地址。
     *
     * <p>仅 GIT 来源有值。</p>
     */
    @Schema(description = "Git仓库地址")
    private String gitUrl;

    /**
     * Git 分支。
     *
     * <p>构建使用的默认分支。</p>
     */
    @Schema(description = "Git分支")
    private String gitBranch;

    /**
     * Git 凭据 ID。
     *
     * <p>指向 credential 表的外键引用，不是凭据内容本身；
     * 页面用它展示项目与凭据的绑定关系，因此允许返回。</p>
     */
    @Schema(description = "Git凭据ID，指向credential表")
    private String gitCredentialId;

    /**
     * 构建模式。
     *
     * <p>取值与后端 BuildMode 枚举一致：
     * PLATFORM_TEMPLATE / PROJECT_DOCKERFILE / LAYERED_DOCKER / ARTIFACT_ONLY。</p>
     */
    @Schema(description = "构建模式")
    private String buildMode;

    /**
     * 构建配置 JSON。
     *
     * <p>字符串形式的 JSON 配置，内部结构由构建模块定义。</p>
     */
    @Schema(description = "构建配置JSON")
    private String buildConfigJson;

    /**
     * 运行配置 JSON。
     *
     * <p>字符串形式的 JSON 配置，发布模块消费。</p>
     */
    @Schema(description = "运行配置JSON")
    private String runtimeConfigJson;

    /**
     * 健康检查配置 JSON。
     *
     * <p>字符串形式的 JSON 配置，发布模块消费。</p>
     */
    @Schema(description = "健康检查配置JSON")
    private String healthConfigJson;

    /**
     * 保留发布版本数量。
     *
     * <p>大于等于 1，页面展示项目版本保留策略。</p>
     */
    @Schema(description = "保留发布版本数量")
    private Integer retainReleaseCount;

    /**
     * 项目状态。
     *
     * <p>取值 ENABLED / DISABLED，与后端 EnableStatus 枚举一致；
     * 前端渲染为启用/停用标签。</p>
     */
    @Schema(description = "状态：ENABLED/DISABLED")
    private String status;

    /**
     * 项目描述。
     *
     * <p>面向页面的用途说明。</p>
     */
    @Schema(description = "项目描述")
    private String description;

    /**
     * 创建人。
     *
     * <p>过渡期操作者标识，接入统一权限中心后为外部用户 ID。</p>
     */
    @Schema(description = "创建人")
    private String createdBy;

    /**
     * 更新人。
     *
     * <p>过渡期操作者标识，随修改更新。</p>
     */
    @Schema(description = "更新人")
    private String updatedBy;

}
