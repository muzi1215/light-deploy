package com.lx.deploy.server.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 部署服务器请求对象。
 *
 * <p>Controller 的查询、新增和修改请求统一使用该 DTO，不直接接收 Server 数据库实体。
 * 主键、创建时间、更新时间和软删除字段由服务端维护，调用方不能通过请求体修改。</p>
 *
 * <p><b>字段维护边界：</b>serverName/host/username 新增必填；sshPort/authType/envTag/
 * status 选填，未传时由 Service 填充默认值；credential 是明文凭据，只允许写入
 * （新增必填、修改选填且不传则保留旧凭据），Service 用 AES-GCM 加密后落库，
 * 任何查询接口都不返回该字段。</p>
 *
 * @author lx
 * @since 2026-08-23 19:56:21
 */
@Data
@Schema(description = "部署服务器请求参数")
public class ServerDto {

    /**
     * 服务器名称。
     *
     * <p>面向页面的显示名称，如"阿里云轻量服务器"，新增必填；查询时等值过滤。</p>
     */
    @Schema(description = "服务器名称")
    private String serverName;

    /**
     * 服务器 IP 地址或域名。
     *
     * <p>SSH 连接目标地址，新增必填；与 sshPort 组成唯一键 uk_server_host_port，
     * 同一主机端口软删除后可重新登记。查询时等值过滤。</p>
     */
    @Schema(description = "服务器IP地址或域名")
    private String host;

    /**
     * SSH 连接端口。
     *
     * <p>选填，取值 1 到 65535，默认 22；查询时等值过滤。</p>
     */
    @Schema(description = "SSH端口，1到65535")
    private Integer sshPort;

    /**
     * SSH 登录用户名。
     *
     * <p>远程执行命令所用的系统账号，如 root，新增必填；查询时等值过滤。</p>
     */
    @Schema(description = "SSH登录用户")
    private String username;

    /**
     * SSH 认证类型，取值见后端 {@code ServerAuthType} 枚举。
     *
     * <p>选填，1 密码认证，2 私钥认证，默认 1；决定 credential 字段中提交的
     * 明文内容被加密后属于密码还是私钥。查询时等值过滤。</p>
     */
    @Schema(description = "SSH认证类型：1密码，2私钥")
    private Byte authType;

    /**
     * SSH 密码或私钥明文。
     *
     * <p>只写不读（WRITE_ONLY）：Service 使用 CredentialCrypto 的 AES-GCM 加密后
     * 写入 Server.ciphertext 字段，任何查询/列表/详情响应都不会返回该字段。
     * 新增时必填（根据 authType 提交密码或私钥），修改时选填、不传则保留旧凭据；
     * 该字段禁止参与普通条件查询和日志输出。</p>
     */
    @Schema(description = "SSH密码或私钥明文，仅写入", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String credential;

    /**
     * 环境标签，取值见后端 {@code EnvironmentTag} 枚举。
     *
     * <p>选填，1 开发，2 测试，3 生产，默认 1；仅用于服务器列表展示与筛选，
     * 正式项目发布目标以项目环境绑定配置为准。查询时等值过滤。</p>
     */
    @Schema(description = "环境标签：1开发，2测试，3生产")
    private Byte envTag;

    /**
     * 服务器状态，取值见后端 {@code ServerStatus} 枚举。
     *
     * <p>选填，0 停用，1 启用，默认 1；只表示服务器是否允许被平台连接和参与部署，
     * 不能用来表达开发、测试或生产环境。查询时等值过滤。</p>
     */
    @Schema(description = "服务器状态：0停用，1启用")
    private Byte status;

    /**
     * 服务器描述。
     *
     * <p>选填，面向页面的用途说明；查询条件不使用该字段。</p>
     */
    @Schema(description = "服务器描述")
    private String description;

}
