package com.lx.deploy.server.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;


/**
 * 部署服务器。
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。
 * 业务字段说明来源于数据库字段注释，修改字段时应同步更新数据库 DDL。</p>
 *
 * @author lx
 * @since 2026-08-23 19:56:21
 */
@Data
@TableName("server")
@Schema(description = "部署服务器")
public class Server extends BaseEntity {
    /**
     * 服务器名称
     */
    @TableField("server_name")
    @Schema(description = "服务器名称")
    private String serverName;

    /**
     * 服务器IP地址或域名
     */
    @TableField("host")
    @Schema(description = "服务器IP地址或域名")
    private String host;

    /**
     * SSH端口
     */
    @TableField("ssh_port")
    @Schema(description = "SSH端口")
    private Integer sshPort;

    /**
     * SSH登录用户
     */
    @TableField("username")
    @Schema(description = "SSH登录用户")
    private String username;

    /**
     * SSH认证类型：1密码，2私钥
     */
    @TableField("auth_type")
    @Schema(description = "SSH认证类型：1密码，2私钥")
    private Byte authType;

    /**
     * AES-GCM加密后的SSH密码或私钥内容
     */
    @TableField("ciphertext")
    @Schema(description = "AES-GCM加密后的SSH密码或私钥内容")
    private String ciphertext;

    /**
     * 环境标签：1开发，2测试，3生产
     */
    @TableField("env_tag")
    @Schema(description = "环境标签：1开发，2测试，3生产")
    private Byte envTag;

    /**
     * 服务器状态：0停用，1启用
     */
    @TableField("status")
    @Schema(description = "服务器状态：0停用，1启用")
    private Byte status;

    /**
     * 服务器描述
     */
    @TableField("description")
    @Schema(description = "服务器描述")
    private String description;

    /**
     * 创建人用户ID
     */
    @TableField("created_by")
    @Schema(description = "创建人用户ID")
    private String createdBy;

    /**
     * 更新人用户ID
     */
    @TableField("updated_by")
    @Schema(description = "更新人用户ID")
    private String updatedBy;

}
