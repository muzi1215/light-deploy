package com.lx.deploy.deployment.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 运行配置 JSON 解析载体。
 *
 * <p>对应 {@code project.runtime_config_json}，可被部署目标 {@code project_environment_target.runtime_config_json}
 * 覆盖（后者只需写差异字段）。承载「发布到机器上之后怎么把它跑起来」的参数，
 * 按运行时分工使用：</p>
 * <ul>
 *   <li>DOCKER：container_name、port_mappings、extra_docker_args；</li>
 *   <li>JAR：work_dir、service_name、jvm_options、java_bin、spring_profiles_active、
 *       config_dir、config_file——jar 与配置分离部署，发版为固定目录原地替换；</li>
 *   <li>STATIC：static_root、static_backup_dir——静态目录整体替换，备份留在同级目录。</li>
 * </ul>
 *
 * <p>各运行时的字段互不干扰：JAR 的字段只在 JAR 分支消费，STATIC 的字段只在 STATIC 分支消费，
 * 三个运行时各自对应独立的发布服务（{@code JarDeployService} / {@code StaticDeployService} /
 * {@code DockerDeployService}），不会出现「配了 A 影响 B」。</p>
 *
 * <p>字段缺失时由各发布服务给出按项目编码推导的默认值，
 * 保证「不填也能发布」——中小企业的第一诉求是把东西发上去，不是先配一堆参数。</p>
 *
 * <p>JSON 键为 snake_case，每个字段用 {@link JsonProperty} 显式绑定。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Data
public class DeployConfig {

    /**
     * 应用在目标机上的部署根目录，JAR 运行时使用。
     *
     * <p>留空时由发布服务推导为「目标机部署根目录 + /apps」：
     * 目标机部署根目录在服务器页面按机器配置，因此这里不再写任何默认绝对路径。</p>
     */
    @JsonProperty("deploy_base")
    private String deployBase;

    /** Docker 容器名，默认取项目编码。 */
    @JsonProperty("container_name")
    private String containerName;

    /** 容器端口映射，形如 8080:8080，多个用英文逗号分隔；留空表示不映射端口。 */
    @JsonProperty("port_mappings")
    private String portMappings;

    /** 附加 docker run 参数（如 -e KEY=VALUE、--network xxx），原样拼到命令行。 */
    @JsonProperty("extra_docker_args")
    private String extraDockerArgs;

    /** JVM 启动参数，形如 -Xms256m -Xmx512m；JAR 运行时使用。 */
    @JsonProperty("jvm_options")
    private String jvmOptions;

    /** systemd 服务名（不含 .service），默认取项目编码；JAR 运行时使用。 */
    @JsonProperty("service_name")
    private String serviceName;

    /**
     * JAR 运行时的目标机固定工作目录，如 /lx/jarapps/auth-tool-7888。
     *
     * <p>留空时回落到「部署根目录 / 项目编码」。与按版本建子目录的区别：本目录固定不变，
     * 发版是原地替换 jar，systemd 单元只在首次部署生成、之后不再重写，
     * 因此运维手工调整过的启动参数不会被平台覆盖，回滚也只是重新投放旧 jar。</p>
     */
    @JsonProperty("work_dir")
    private String workDir;

    /** 工作目录下的配置目录名，默认 config；jar 与配置分离部署时使用。 */
    @JsonProperty("config_dir")
    private String configDir;

    /** 配置文件名，默认 application.yml，用于 systemd 的 --spring.config.location。 */
    @JsonProperty("config_file")
    private String configFile;

    /** java 可执行文件绝对路径；留空时由平台在目标机探测（command -v java）。 */
    @JsonProperty("java_bin")
    private String javaBin;

    /** spring.profiles.active，默认 prod。 */
    @JsonProperty("spring_profiles_active")
    private String springProfilesActive;

    /**
     * 静态产物发布根目录（Nginx root），默认 /usr/share/nginx/html/项目编码。
     *
     * <p>这里填的就是运维在 nginx 里已经配好的那个目录，平台<b>不会</b>要求为了发布去改 nginx 配置。</p>
     */
    @JsonProperty("static_root")
    private String staticRoot;

    /**
     * 静态产物备份目录，默认 {@code static_root} 父目录下的 {@code .static-bak}。
     *
     * <p>发布时旧版本目录被整目录移到这里，命名 {@code {目录名}_bak_{时间戳}_{版本号}}，
     * 回滚优先从这里还原，不必回头找构建机。</p>
     *
     * <p>两个约束：其一，必须与 {@code static_root} 处在同一分区，否则目录级 mv 会退化成
     * 跨分区复制，切换期间网站处于半新半旧状态（发布时会检测并告警）；
     * 其二，建议配到 web 根之外——默认位置在 nginx 根的同级目录，
     * 旧版本里的 source map 等文件理论上可以被外部请求到。</p>
     */
    @JsonProperty("static_backup_dir")
    private String staticBackupDir;
}
