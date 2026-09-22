package com.lx.deploy.registry.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 镜像仓库请求对象。
 *
 * <p>DTO 只用于 Controller 入参，避免接口层直接暴露数据库实体。主键、审计字段和软删除字段由服务端维护。</p>
 *
 * <p><b>字段维护边界：</b>name/registryUrl 新增必填；namespace/credentialId/status/description
 * 选填，未传时由 Service 填充默认值（status 默认启用）。credentialId 填写时必须是类型为
 * REGISTRY 的凭据；本表只服务于 DOCKER 运行时（AGENTS.md §23.4）。</p>
 *
 * @author lx
 * @since 2026-09-23 02:13:27
 */
@Data
@Schema(description = "镜像仓库请求参数")
public class RegistryDto {

    /**
     * 仓库名称，新增必填，长度上限 100，与已存在名称重复时抛 DATA_CONFLICT(4005)。查询时等值过滤。
     */
    @Schema(description = "仓库名称")
    private String name;

    /**
     * 仓库地址，新增必填，如 registry.cn-hangzhou.aliyuncs.com（不带 https:// 协议前缀）。
     */
    @Schema(description = "仓库地址")
    private String registryUrl;

    /**
     * 仓库命名空间，选填，如 light-deploy；为空表示镜像直接推送到仓库根命名空间。
     */
    @Schema(description = "仓库命名空间")
    private String namespace;

    /**
     * 登录仓库使用的凭据 ID，选填（公共仓库留空）。
     *
     * <p>填写时必须存在且类型为 REGISTRY，否则抛 VALIDATION_ERROR(4001)。</p>
     */
    @Schema(description = "仓库凭据ID")
    private String credentialId;

    /**
     * 仓库状态，选填，取值见后端 {@code EnableStatus}：0 停用，1 启用，未传默认 1。
     */
    @Schema(description = "状态：0停用，1启用")
    private Integer status;

    /**
     * 仓库描述，选填，长度上限 500。
     */
    @Schema(description = "仓库描述")
    private String description;
}
