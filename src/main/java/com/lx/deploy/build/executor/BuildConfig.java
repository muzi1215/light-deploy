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
}
