package com.lx.deploy.credential.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 加密凭据实体。
 *
 * <p>保存 Git 拉取与镜像仓库推送使用的外部系统凭据。明文只在「Service 写入前」和
 * 「SSH / Git / Registry 执行前」两个瞬间存在，数据库、日志、接口响应中都只有密文。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护，
 * 业务代码不直接操作这些字段。</p>
 *
 * @author lx
 * @since 2026-09-23 02:13:26
 */
@Data
@TableName("credential")
@Schema(description = "加密凭据")
public class Credential extends BaseEntity {

    /**
     * 凭据名称。
     *
     * <p>面向页面的可读名称，如「gitee 只读令牌」，新增必填；与 is_deleted 组成唯一键
     * uk_credential_name，同一名称软删除后可重新创建。查询时等值过滤。</p>
     */
    @TableField("name")
    @Schema(description = "凭据名称")
    private String name;

    /**
     * 凭据类型编码，取值见 {@code CredentialType} 枚举。
     *
     * <p>合法取值：GIT_TOKEN（Git 访问令牌）、SSH_KEY（Git 私钥）、REGISTRY（镜像仓库凭据）、
     * SSH_PASSWORD（保留，暂无消费方）。该字段决定密文里装的是什么，创建后不建议修改。</p>
     */
    @TableField("type")
    @Schema(description = "凭据类型：SSH_PASSWORD/SSH_KEY/GIT_TOKEN/REGISTRY")
    private String type;

    /**
     * AES-GCM 加密后的凭据内容（密文）。
     *
     * <p>由服务端维护：新增/修改时 Service 调用 {@code CredentialCrypto.encrypt()} 写入，
     * 调用方永远不能直接提交密文，查询接口也永远不返回该字段。解密只发生在建立 SSH/Git/Registry
     * 连接之前，用后不缓存（AGENTS.md §7）。</p>
     */
    @TableField("encrypted_value")
    @Schema(description = "AES-GCM加密后的凭据内容")
    private String encryptedValue;

    /**
     * 非敏感凭据元数据（JSON 文本）。
     *
     * <p>保存与凭据配套但不敏感的信息，例如 registry 的登录用户名、Git 服务的免密主机指纹。
     * 明文密码、令牌、私钥**禁止**写入该字段。调用方选填，为空时落库 NULL。</p>
     */
    @TableField("metadata_json")
    @Schema(description = "非敏感凭据元数据")
    private String metadataJson;
}
