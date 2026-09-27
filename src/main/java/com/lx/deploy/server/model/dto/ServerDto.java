package com.lx.deploy.server.model.dto;

import com.lx.deploy.common.model.TimeRangeQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

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
@EqualsAndHashCode(callSuper = true)
@Schema(description = "部署服务器请求参数")
public class ServerDto extends TimeRangeQuery {

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
     * 服务器状态，数字编码。
     *
     * <p>选填，0 停用 / 1 启用，默认 1；只表示服务器是否允许被平台连接和参与部署，
     * 不能用来表达开发、测试或生产环境。查询时等值过滤。</p>
     */
    @Schema(description = "服务器状态：0停用，1启用")
    private Integer status;

    /**
     * 构建工作区根目录，仅构建机使用。
     *
     * <p>选填，不传或空白时使用数据库默认值；页面表单预填默认值，按需修改。
     * 每次构建在该目录下创建独立子目录并在结束后清理，属于临时目录，
     * 需要构建机登录用户有读写权限。查询条件不使用该字段。</p>
     */
    @Schema(description = "构建工作区根目录，仅构建机使用")
    private String buildWorkspaceDir;

    /**
     * 构建产物持久目录，仅构建机使用。
     *
     * <p>选填，不传或空白时使用数据库默认值；页面表单预填默认值，按需修改。
     * 必须落在工作区目录之外，否则会被构建收尾的清理删除，导致发布阶段下载到不存在的路径。
     * 修改该值不影响历史版本：产物路径在创建时已快照进构建产物元数据。查询条件不使用该字段。</p>
     */
    @Schema(description = "构建产物持久目录，仅构建机使用")
    private String buildArtifactDir;

    /**
     * 平台在该服务器上的部署根目录。
     *
     * <p>选填，不传或空白时使用数据库默认值；页面表单预填默认值，按需修改。
     * 连通性测试会检查该目录存在且可写，应用实际部署在其 apps 子目录下。
     * 该目录由运维在目标机上预先创建，平台不会自动创建（连通性测试只报告、不产生副作用）。
     * 查询条件不使用该字段。</p>
     */
    @Schema(description = "平台在该服务器上的部署根目录")
    private String agentBaseDir;

    /**
     * 构建环境初始化脚本，仅构建机使用。
     *
     * <p>选填，空白时归一为 null（不执行额外环境脚本，沿用平台的 {@code . /etc/profile} 兜底）。
     * 平台执行构建命令前会在当前 shell 执行本字段内容，用于补 PATH 等环境变量，
     * 例如 {@code export PATH=/opt/apache-maven-3.8.8/bin:$PATH}。单行命令，不要带换行。</p>
     */
    @Schema(description = "构建环境初始化脚本，仅构建机使用")
    private String buildEnvScript;

    /**
     * 服务器描述。
     *
     * <p>选填，面向页面的用途说明；查询条件不使用该字段。</p>
     */
    @Schema(description = "服务器描述")
    private String description;

}
