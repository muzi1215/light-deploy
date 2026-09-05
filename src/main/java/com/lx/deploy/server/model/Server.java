package com.lx.deploy.server.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 部署服务器。
 *
 * <p>平台纳管的目标 Linux 服务器档案，SSH/SFTP 远程执行的连接依据。
 * 凭据安全是本实体的核心约束：ciphertext 只保存 AES-GCM 密文，
 * 明文凭据仅在 DTO 中短暂存在，Service 写库前加密、执行器连接前短暂解密。</p>
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。
 * 唯一键 uk_server_host_port 保证同一主机端口只能登记一台服务器；
 * 修改字段时应同步更新数据库 DDL 和本类注释。</p>
 *
 * @author lx
 * @since 2026-08-23 19:56:21
 */
@Data
@TableName("server")
@Schema(description = "部署服务器")
public class Server extends BaseEntity {

    /**
     * 服务器名称。
     *
     * <p>面向页面的显示名称，如"阿里云轻量服务器"，新增时必填。</p>
     */
    @TableField("server_name")
    @Schema(description = "服务器名称")
    private String serverName;

    /**
     * 服务器 IP 地址或域名。
     *
     * <p>SSH 连接目标地址，与 sshPort 组成唯一键，
     * 同一主机端口软删除后可重新登记。</p>
     */
    @TableField("host")
    @Schema(description = "服务器IP地址或域名")
    private String host;

    /**
     * SSH 连接端口。
     *
     * <p>取值 1 到 65535，默认 22。</p>
     */
    @TableField("ssh_port")
    @Schema(description = "SSH端口，1到65535")
    private Integer sshPort;

    /**
     * SSH 登录用户名。
     *
     * <p>远程执行命令所用的系统账号，如 root。</p>
     */
    @TableField("username")
    @Schema(description = "SSH登录用户")
    private String username;

    /**
     * SSH 认证类型，取值见 {@code ServerAuthType} 枚举。
     *
     * <p>1 密码认证，2 私钥认证；决定 ciphertext 字段中加密的内容类型。</p>
     */
    @TableField("auth_type")
    @Schema(description = "SSH认证类型：1密码，2私钥")
    private Byte authType;

    /**
     * AES-GCM 加密后的 SSH 密码或私钥内容。
     *
     * <p>只写不读：VO 不声明该字段，任何查询接口都不会返回密文；
     * 由 CredentialCrypto 加解密，密钥通过 LIGHT_DEPLOY_AES_KEY 注入。
     * 该字段禁止参与普通条件查询和日志输出。</p>
     */
    @TableField("ciphertext")
    @Schema(description = "AES-GCM加密后的SSH密码或私钥内容", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String ciphertext;

    /**
     * 服务器环境标签，取值见 {@code EnvironmentTag} 枚举。
     *
     * <p>1 开发，2 测试，3 生产；仅用于服务器列表展示与筛选，
     * 正式项目发布目标以项目环境绑定配置为准，不以标签为准。</p>
     */
    @TableField("env_tag")
    @Schema(description = "环境标签：1开发，2测试，3生产")
    private Byte envTag;

    /**
     * 服务器状态，取值见 {@code ServerStatus} 枚举。
     *
     * <p>0 停用，1 启用；只表示服务器是否允许被平台连接和参与部署，
     * 不能用来表达开发、测试或生产环境。停用服务器不允许绑定新部署目标。</p>
     */
    @TableField("status")
    @Schema(description = "服务器状态：0停用，1启用")
    private Byte status;

    /**
     * 服务器描述。
     *
     * <p>面向页面的用途说明，如"阿里云轻量服务器，1年"，选填。</p>
     */
    @TableField("description")
    @Schema(description = "服务器描述")
    private String description;

    /**
     * 创建人用户 ID。
     *
     * <p>过渡期记录操作者，接入统一权限中心后由外部用户 ID 替代。</p>
     */
    @TableField("created_by")
    @Schema(description = "创建人用户ID")
    private String createdBy;

    /**
     * 更新人用户 ID。
     *
     * <p>过渡期记录操作者，随修改更新。</p>
     */
    @TableField("updated_by")
    @Schema(description = "更新人用户ID")
    private String updatedBy;

}
