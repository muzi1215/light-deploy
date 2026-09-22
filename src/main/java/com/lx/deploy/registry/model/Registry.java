package com.lx.deploy.registry.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 镜像仓库实体。
 *
 * <p>登记 Docker 镜像的推送目标（私有 Harbor、阿里云 ACR、Docker Hub 等）。
 * **只服务于 DOCKER 运行时**：脚本部署（SCRIPT_DEPLOY）与仅发布产物（ARTIFACT_ONLY）
 * 不产出镜像，也就不需要绑定仓库（AGENTS.md §23.4）。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护，
 * 业务代码不直接操作这些字段。</p>
 *
 * @author lx
 * @since 2026-09-23 02:13:27
 */
@Data
@TableName("registry")
@Schema(description = "镜像仓库")
public class Registry extends BaseEntity {

    /**
     * 仓库名称。
     *
     * <p>面向页面的可读名称，如「阿里云 ACR」，新增必填；与 is_deleted 组成唯一键
     * uk_registry_name。查询时等值过滤。</p>
     */
    @TableField("name")
    @Schema(description = "仓库名称")
    private String name;

    /**
     * 仓库地址。
     *
     * <p>镜像推送/拉取的主机名，可带端口，如 registry.cn-hangzhou.aliyuncs.com；
     * 不带协议前缀（https://），构建模块拼接镜像全名时自行补全。新增必填。</p>
     */
    @TableField("registry_url")
    @Schema(description = "仓库地址")
    private String registryUrl;

    /**
     * 仓库命名空间（项目组/租户）。
     *
     * <p>如 AIops 项目在阿里云上的命名空间；镜像全名 = 仓库地址/命名空间/镜像名:标签。选填。</p>
     */
    @TableField("namespace")
    @Schema(description = "仓库命名空间")
    private String namespace;

    /**
     * 登录仓库使用的凭据 ID（引用 credential.id）。
     *
     * <p>选填（公共仓库不需要）：填写时必须是类型为 REGISTRY 的凭据，Service 会校验存在性与类型；
     * 凭据被引用期间不允许删除（见 CredentialService.delete）。</p>
     */
    @TableField("credential_id")
    @Schema(description = "仓库凭据ID")
    private String credentialId;

    /**
     * 仓库状态数字编码，取值见 {@code EnableStatus} 常量。
     *
     * <p>0 停用，1 启用，类型为 Integer（与 project.status、server.status 保持一致，
     * 不使用枚举表达状态，AGENTS.md §22.4）。停用的仓库不允许被新项目绑定。</p>
     */
    @TableField("status")
    @Schema(description = "状态：0停用，1启用")
    private Integer status;

    /**
     * 仓库描述。
     *
     * <p>选填，长度上限 500，用于说明仓库用途或归属团队。</p>
     */
    @TableField("description")
    @Schema(description = "仓库描述")
    private String description;
}
