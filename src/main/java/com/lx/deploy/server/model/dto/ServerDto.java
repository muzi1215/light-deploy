package com.lx.deploy.server.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 部署服务器请求对象。
 *
 * <p>Controller 的查询、新增和修改请求统一使用该 DTO，不直接接收 Server 数据库实体。
 * 主键、创建时间、更新时间和软删除字段由服务端维护，调用方不能通过请求体修改。</p>
 */
@Data
@Schema(description = "部署服务器请求参数")
public class ServerDto {

    /** 服务器名称。 */
    private String serverName;
    /** 服务器 IP 地址或域名。 */
    private String host;
    /** SSH 连接端口。 */
    private Integer sshPort;
    /** SSH 登录用户名。 */
    private String username;
    /** SSH 认证类型：1 密码，2 私钥。 */
    private Byte authType;
    /** SSH 密码或私钥明文，只允许写入；Service 会使用 AES-GCM 加密后再保存。 */
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
    private String credential;
    /** 环境标签：1 开发，2 测试，3 生产。 */
    private Byte envTag;
    /** 服务器状态：0 停用，1 启用。 */
    private Byte status;
    /** 服务器描述。 */
    private String description;
}
