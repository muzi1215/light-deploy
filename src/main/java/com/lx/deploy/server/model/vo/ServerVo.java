package com.lx.deploy.server.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部署服务器响应对象。
 *
 * <p>Controller 只返回该 VO，不返回 Server 数据库实体。SSH 密码或私钥密文不在本类中声明，
 * 从类型结构上避免凭据字段出现在查询、新增和修改接口的响应中。</p>
 */
@Data
@Schema(description = "部署服务器响应数据")
public class ServerVo {

    /** 数据主键。 */
    private String id;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
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
    /** 环境标签：1 开发，2 测试，3 生产。 */
    private Byte envTag;
    /** 服务器状态：0 停用，1 启用。 */
    private Byte status;
    /** 服务器描述。 */
    private String description;
    /** 创建人用户 ID。 */
    private String createdBy;
    /** 更新人用户 ID。 */
    private String updatedBy;
}
