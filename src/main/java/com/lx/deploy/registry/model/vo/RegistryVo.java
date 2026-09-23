package com.lx.deploy.registry.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 镜像仓库响应对象。
 *
 * <p>VO 只用于 Controller 出参。本类只返回 credentialId（凭据 ID），不返回凭据内容：
 * 仓库密码保存在 credential 表里且只以密文形式存在，任何接口都不回显（AGENTS.md §7）。</p>
 *
 * @author lx
 * @since 2026-09-23 02:13:27
 */
@Data
@Schema(description = "镜像仓库响应数据")
public class RegistryVo {

    /** 数据主键，32 位 UUID 字符串；项目通过 project.registry_id 引用该值。 */
    private String id;

    /** 创建时间，服务端维护。 */
    private LocalDateTime createTime;

    /** 更新时间，服务端维护。 */
    private LocalDateTime updateTime;

    /** 仓库名称。 */
    @Schema(description = "仓库名称")
    private String name;

    /** 仓库地址（不含协议前缀）。 */
    @Schema(description = "仓库地址")
    private String registryUrl;

    /** 仓库命名空间，未填写时为 null。 */
    @Schema(description = "仓库命名空间")
    private String namespace;

    /**
     * 登录仓库使用的凭据 ID。
     *
     * <p>**只返回凭据 ID，不返回凭据内容**：仓库密码保存在 credential 表且只以密文形式存在。
     * 该字段由人工补回——生成器的敏感字段规则会把名称含 `credential` 的列排除在 VO 之外，
     * 但外键引用本身不是敏感信息，页面需要它展示「仓库绑定了哪份凭据」
     * （与 ProjectVo.gitCredentialId 的处理一致）。</p>
     */
    @Schema(description = "登录凭据ID，指向credential表")
    private String credentialId;

    /** 仓库状态数字编码，0 停用 / 1 启用，页面按该值映射标签颜色。 */
    @Schema(description = "状态：0停用，1启用")
    private Integer status;

    /** 仓库描述，未填写时为 null。 */
    @Schema(description = "仓库描述")
    private String description;
}
