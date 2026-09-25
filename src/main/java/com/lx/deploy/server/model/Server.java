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
     * 服务器状态，数字编码。
     *
     * <p>0 停用 / 1 启用，含义见 {@link com.lx.deploy.common.constants.EnableStatus}；
     * 只表示服务器是否允许被平台连接和参与部署，不能用来表达开发、测试或生产环境。
     * 停用服务器不允许绑定新部署目标。</p>
     */
    @TableField("status")
    @Schema(description = "服务器状态：0停用，1启用")
    private Integer status;

    /**
     * 服务器描述。
     *
     * <p>面向页面的用途说明，如"阿里云轻量服务器，1年"，选填。</p>
     */
    @TableField("description")
    @Schema(description = "服务器描述")
    private String description;

    /**
     * 构建工作区根目录，仅构建机使用。
     *
     * <p>每次构建在该目录下创建 {@code 项目编码_构建ID} 的独立子目录，
     * 构建结束（无论成功失败）后整体清理，属于临时目录。
     * 纯部署目标机不使用该字段，保留建表时的默认值即可。</p>
     */
    @TableField("build_workspace_dir")
    @Schema(description = "构建工作区根目录，仅构建机使用")
    private String buildWorkspaceDir;

    /**
     * 构建产物持久目录，仅构建机使用。
     *
     * <p>文件型产物（JAR）与目录型产物（前端 dist）在构建成功后转存到
     * {@code 该目录/项目编码/版本号} 下，供发布阶段通过 SFTP 下载。
     * 必须落在工作区之外：工作区会被清理，登记工作区路径会导致发布时下载到已删除的文件。
     * 路径在创建构建产物时已快照进 build_artifact 的元数据，之后修改本字段不影响历史版本。</p>
     */
    @TableField("build_artifact_dir")
    @Schema(description = "构建产物持久目录，仅构建机使用")
    private String buildArtifactDir;

    /**
     * 平台在该服务器上的部署根目录。
     *
     * <p>连通性测试检查该目录存在且可写；应用实际部署在其 {@code apps} 子目录下，
     * 该子目录名由平台约定、不单独配置。目录路径来自页面配置而非代码常量，
     * 不同机器可使用不同的挂载点（如数据盘）。</p>
     */
    @TableField("agent_base_dir")
    @Schema(description = "平台在该服务器上的部署根目录")
    private String agentBaseDir;

    /**
     * 构建环境初始化脚本，仅构建机使用。
     *
     * <p>平台通过 SSH 执行构建命令时用的是非交互式 shell，不加载 /etc/profile 等登录环境，
     * PATH 里往往没有 mvn 这类「已安装但靠 profile 导出路径」的工具。执行构建命令前，
     * 平台会先固定执行一次 {@code . /etc/profile} 兜底，随后执行本字段内容，
     * 用于补充或覆盖环境，例如 {@code export PATH=/opt/apache-maven-3.8.8/bin:$PATH}。</p>
     */
    @TableField("build_env_script")
    @Schema(description = "构建环境初始化脚本，仅构建机使用")
    private String buildEnvScript;

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
