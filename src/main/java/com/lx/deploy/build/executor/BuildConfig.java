package com.lx.deploy.build.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 项目构建配置 JSON 解析载体。
 *
 * <p>对应 {@code project.build_config_json}（见 docs/项目模块.md §2.3.1）。本类只做结构承载，
 * 不参与业务校验；字段缺失时由 {@link BuildTaskHandler} 按运行时/构建模式决定默认值或抛出明确异常。</p>
 *
 * <p>JSON 键为 snake_case，因此每个字段用 {@link JsonProperty} 显式绑定，避免依赖 Jackson 命名策略。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Data
public class BuildConfig {

    /** 子模块名：多模块仓库必填，默认 -pl 参数、默认模块目录名与默认镜像名来源。 */
    @JsonProperty("module_name")
    private String moduleName;

    /** 子模块相对仓库根目录，默认等于 moduleName；单模块仓库可省略。 */
    @JsonProperty("module_dir")
    private String moduleDir;

    /** Maven 构建入口 pom 路径，默认 {module_dir}/pom.xml。 */
    @JsonProperty("pom_path")
    private String pomPath;

    /** 完整构建命令；填写后覆盖平台推导结果。 */
    @JsonProperty("build_command")
    private String buildCommand;

    /** Dockerfile 相对仓库根路径，PROJECT_DOCKERFILE / LAYERED_DOCKER 必填。 */
    @JsonProperty("dockerfile_path")
    private String dockerfilePath;

    /** 分层构建基础层 Dockerfile 路径，LAYERED_DOCKER 必填。 */
    @JsonProperty("base_dockerfile_path")
    private String baseDockerfilePath;

    /**
     * docker-compose.yml 相对仓库根目录的路径，DOCKER 运行时选填，默认 {@code docker-compose.yml}。
     *
     * <p>配置了就会在构建成功后把文件<b>原文</b>快照进发布版本（见 {@code release_record.compose_content}），
     * 发布时直接下发到目标机，不必回仓库重新取，因此版本可精确回溯。
     * 代价是改了这份文件<b>必须重新构建</b>才会生效——它描述的是「这个版本怎么跑」，
     * 不属于运行时可以随便覆盖的参数。</p>
     */
    @JsonProperty("compose_file_path")
    private String composeFilePath;

    /** 构建产物相对路径，支持 * 通配，JAR / STATIC 运行时必填。 */
    @JsonProperty("artifact_path")
    private String artifactPath;

    /**
     * 随产物一起发布的配置文件相对路径，多个用英文逗号分隔，支持 * 通配。
     *
     * <p>JAR 运行时选填：Spring Boot 的 application.yml 与 jar 分离部署时填写，
     * 构建阶段会转存到产物同级的 config 目录，发布时由平台投放到目标机工作目录的 config 子目录。
     * 路径口径与 artifact_path 一致——相对仓库根优先，找不到再回落模块目录。
     * DOCKER 运行时不消费该字段。</p>
     */
    @JsonProperty("config_paths")
    private String configPaths;

    /** 业务镜像仓库地址（不含 tag），DOCKER 运行时建议填写。 */
    @JsonProperty("image_repository")
    private String imageRepository;

    /**
     * 构建命令的超时秒数，选填；不填用全局的 light-deploy.ssh.long-command-timeout-seconds。
     *
     * <p>首次构建要从仓库下载全部依赖（分钟级），增量构建通常几十秒，两者差一个数量级。
     * 项目级覆盖比全局值更贴合实际，避免"为了某个大项目把全局超时调到很大"。</p>
     */
    @JsonProperty("command_timeout_seconds")
    private Integer commandTimeoutSeconds;

    /**
     * 构建机启动级验证开关，DOCKER 运行时选填，默认 true。
     *
     * <p>方案 C 的核心闸门：镜像构建完成后、推送仓库前，先在构建机本地把它跑起来，
     * 确认"构建成功但一启动就崩"的缺陷镜像不会上推。只验证<b>启动级</b>（窗口内持续存活），
     * 需要连数据库/外部接口的<b>业务级</b>验证仍交给目标机。</p>
     *
     * <p>若应用启动就必须连外部依赖、而构建机网络不通，验证会误判失败；
     * 这类项目显式设 false 跳过，完全依赖目标机业务级验证。</p>
     */
    @JsonProperty("docker_verify_on_build")
    private Boolean dockerVerifyOnBuild;

    /**
     * 启动级验证窗口秒数，DOCKER 运行时选填，默认 30。
     *
     * <p>镜像以<b>前台</b>方式运行，由 {@code timeout} 在该秒数后终止；窗口内容器持续存活
     * 即视为通过（退出码 124）。过短会误杀启动慢的应用，过长则拖慢构建；按应用冷启动耗时调整。</p>
     */
    @JsonProperty("docker_verify_timeout_seconds")
    private Integer dockerVerifyTimeoutSeconds;

    /**
     * Maven settings.xml 相对仓库根路径，JAR_PREBUILD 模式选填。
     *
     * <p>填了则在 Maven 构建命令加 {@code -s} 指向它（典型用途：切到阿里云镜像加速依赖下载）。
     * 不填则走 Maven 默认配置（公网 Maven Central，构建机在内网时极慢）。</p>
     */
    @JsonProperty("maven_settings_path")
    private String mavenSettingsPath;
}
