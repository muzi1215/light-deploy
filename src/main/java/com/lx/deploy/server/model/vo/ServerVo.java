package com.lx.deploy.server.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部署服务器响应对象。
 *
 * <p>Controller 只返回该 VO，不返回 Server 数据库实体。SSH 密码或私钥密文不在本类中声明，
 * 从类型结构上避免凭据字段出现在查询、新增和修改接口的响应中。</p>
 *
 * <p>前端消费方式：authType/envTag 为字符串枚举，status 为数字编码（0 停用 / 1 启用），
 * 页面渲染为标签；credential/ciphertext 永不返回。</p>
 *
 * @author lx
 * @since 2026-08-23 19:56:21
 */
@Data
@Schema(description = "部署服务器响应数据")
public class ServerVo {

    /**
     * 数据主键。
     *
     * <p>由服务端通过 IdGenerator 生成 32 位 UUID，调用方只读、不能通过请求体修改；
     * 前端用于行标识以及详情、编辑、删除接口的路径参数。</p>
     */
    @Schema(description = "主键ID")
    private String id;

    /**
     * 创建时间。
     *
     * <p>由 MyBatis-Plus 在插入时自动填充，服务端维护，调用方只读；列表默认按该字段倒序。</p>
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 更新时间。
     *
     * <p>由 MyBatis-Plus 在插入和更新时自动填充，服务端维护，调用方只读。</p>
     */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /**
     * 服务器名称。
     *
     * <p>面向页面的显示名称，如"阿里云轻量服务器"。</p>
     */
    @Schema(description = "服务器名称")
    private String serverName;

    /**
     * 服务器 IP 地址或域名。
     *
     * <p>SSH 连接目标地址，与 sshPort 组成唯一键。</p>
     */
    @Schema(description = "服务器IP地址或域名")
    private String host;

    /**
     * SSH 连接端口。
     *
     * <p>取值 1 到 65535，默认 22。</p>
     */
    @Schema(description = "SSH端口，1到65535")
    private Integer sshPort;

    /**
     * SSH 登录用户名。
     *
     * <p>远程执行命令所用的系统账号，如 root。</p>
     */
    @Schema(description = "SSH登录用户")
    private String username;

    /**
     * SSH 认证类型。
     *
     * <p>取值 1 密码认证，2 私钥认证，与后端 ServerAuthType 枚举一致；
     * 前端渲染为认证方式标签。密文内容不在此返回。</p>
     */
    @Schema(description = "SSH认证类型：1密码，2私钥")
    private Byte authType;

    /**
     * 环境标签。
     *
     * <p>取值 1 开发，2 测试，3 生产，与后端 EnvironmentTag 枚举一致；
     * 前端渲染为环境标签，仅用于列表展示与筛选。</p>
     */
    @Schema(description = "环境标签：1开发，2测试，3生产")
    private Byte envTag;

    /**
     * 服务器状态。
     *
     * <p>取值 0 停用 / 1 启用，由服务端维护；
     * 前端渲染为状态标签，表示服务器是否允许被平台连接和参与部署。</p>
     */
    @Schema(description = "服务器状态：0停用，1启用")
    private Integer status;

    /**
     * 构建工作区根目录，仅构建机使用。
     *
     * <p>页面在服务器表单中展示与编辑；每次构建在该目录下创建临时子目录并在结束后清理。</p>
     */
    @Schema(description = "构建工作区根目录，仅构建机使用")
    private String buildWorkspaceDir;

    /**
     * 构建产物持久目录，仅构建机使用。
     *
     * <p>页面在服务器表单中展示与编辑；产物转存于此供发布阶段下载，不随工作区清理。</p>
     */
    @Schema(description = "构建产物持久目录，仅构建机使用")
    private String buildArtifactDir;

    /**
     * 平台在该服务器上的部署根目录。
     *
     * <p>页面在服务器表单中展示与编辑；连通性测试结果中会体现该目录是否就绪。</p>
     */
    @Schema(description = "平台在该服务器上的部署根目录")
    private String agentBaseDir;

    /**
     * 构建环境初始化脚本，仅构建机使用。
     *
     * <p>页面在服务器表单中展示与编辑；执行构建命令前由平台在目标机构建机上执行。</p>
     */
    @Schema(description = "构建环境初始化脚本，仅构建机使用")
    private String buildEnvScript;

    /**
     * 服务器描述。
     *
     * <p>面向页面的用途说明，如"阿里云轻量服务器，1年"。</p>
     */
    @Schema(description = "服务器描述")
    private String description;

    /**
     * 创建人用户 ID。
     *
     * <p>过渡期记录操作者，接入统一权限中心后由外部用户 ID 替代。</p>
     */
    @Schema(description = "创建人用户ID")
    private String createdBy;

    /**
     * 更新人用户 ID。
     *
     * <p>过渡期记录操作者，随修改更新。</p>
     */
    @Schema(description = "更新人用户ID")
    private String updatedBy;
}
