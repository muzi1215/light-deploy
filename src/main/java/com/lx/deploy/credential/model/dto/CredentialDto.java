package com.lx.deploy.credential.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 加密凭据请求对象。
 *
 * <p>DTO 只用于 Controller 入参，避免接口层直接暴露数据库实体。主键、审计字段和软删除字段由服务端维护。</p>
 *
 * <p><b>字段维护边界：</b>name/type 新增必填；credential 是**明文凭据**，只允许写入
 * （新增必填、修改选填且不传则保留旧密文），Service 用 AES-GCM 加密后落库；
 * metadataJson 选填，只放非敏感元数据。密文字段（encrypted_value）不出现在 DTO 里，
 * 调用方无法提交密文，也无法通过任何接口把密文读回去。</p>
 *
 * @author lx
 * @since 2026-09-23 02:13:26
 */
@Data
@Schema(description = "加密凭据请求参数")
public class CredentialDto {

    /**
     * 凭据名称。
     *
     * <p>面向页面的可读名称，新增必填，长度上限 100；与已存在名称重复时抛 DATA_CONFLICT(4005)。查询时等值过滤。</p>
     */
    @Schema(description = "凭据名称")
    private String name;

    /**
     * 凭据类型，取值见后端 {@code CredentialType} 枚举。
     *
     * <p>新增必填：GIT_TOKEN（Git 访问令牌）、SSH_KEY（Git 私钥）、REGISTRY（镜像仓库凭据）；
     * SSH_PASSWORD 为保留类型，当前没有消费方。查询时等值过滤。</p>
     */
    @Schema(description = "凭据类型：SSH_PASSWORD/SSH_KEY/GIT_TOKEN/REGISTRY")
    private String type;

    /**
     * 明文凭据内容（Git 令牌 / 私钥 / 仓库密码）。
     *
     * <p>只写字段：新增必填，修改选填（留空表示保留原凭据）。Service 加密后写入
     * credential.encrypted_value，数据库、日志、接口响应中都不出现明文（AGENTS.md §7）。</p>
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, description = "明文凭据内容，仅提交不回显")
    private String credential;

    /**
     * 非敏感凭据元数据（JSON 文本）。
     *
     * <p>选填，例如 registry 登录用户名 {"username":"light-deploy"}；禁止写入明文密码、令牌或私钥。</p>
     */
    @Schema(description = "非敏感凭据元数据")
    private String metadataJson;
}
