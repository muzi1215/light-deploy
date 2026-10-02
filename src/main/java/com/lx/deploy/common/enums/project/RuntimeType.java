package com.lx.deploy.common.enums.project;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目运行时类型枚举。
 *
 * <p>数据库以字符串保存稳定编码，业务代码使用枚举区分三种部署运行时。
 * DOCKER 支持 Dockerfile 与 Docker Compose，JAR 使用 systemd 托管，
 * STATIC 为前端静态产物配合 Nginx 部署。</p>
 *
 * <p><b>与构建模式的约束</b>：每个运行时只接受一部分构建模式，见 {@link #getSupportedBuildModes()}。
 * 划分依据是「产物是不是镜像」：Docker 镜像类构建模式产出镜像并推送 registry，脚本 / 产物类
 * 构建模式直接产出文件，不经过镜像仓库（AGENTS.md §23.4）。</p>
 *
 * <p>对应数据库字段 project.runtime_type，决定构建模式与发布动作分支。</p>
 *
 * @author lx
 * @since 2026-09-05 19:32:43
 */
@Getter
public enum RuntimeType {

    /**
     * Docker 容器运行时，Dockerfile 或 Docker Compose 部署。
     *
     * <p>产物是镜像，必须经过镜像仓库；因此只接受两种 Docker 构建模式，
     * 不接受脚本 / 仅发布产物（它们不产出镜像）。Dockerfile 与 Compose 文件
     * 一律由项目仓库提供，平台不再提供语言模板。</p>
     */
    DOCKER("DOCKER", "Docker容器", Set.of(
            BuildMode.PROJECT_DOCKERFILE,
            BuildMode.LAYERED_DOCKER)),

    /**
     * Java JAR 运行时，配合 systemd 服务部署。
     *
     * <p>产物是 JAR 文件：可以由脚本构建产出，也可以直接发布已有产物，都不涉及镜像仓库。</p>
     */
    JAR("JAR", "Java JAR", Set.of(
            BuildMode.SCRIPT_DEPLOY,
            BuildMode.ARTIFACT_ONLY)),

    /**
     * 前端静态产物运行时，配合 Nginx 部署。
     *
     * <p>与 JAR 同理，产物是目录/文件（dist），不涉及镜像仓库。</p>
     */
    STATIC("STATIC", "静态产物", Set.of(
            BuildMode.SCRIPT_DEPLOY,
            BuildMode.ARTIFACT_ONLY));

    private final String code;
    private final String message;

    /**
     * 该运行时允许的构建模式集合。
     *
     * <p>由枚举常量在声明处直接给出，是这条组合规则在代码里的**唯一**来源：
     * ProjectService 的校验与前端下拉联动都以此为准（AGENTS.md §23.4）。</p>
     */
    private final Set<BuildMode> supportedBuildModes;

    /**
     * 初始化运行时类型。
     *
     * @param code 数据库存储的运行时编码
     * @param message 面向页面和日志的中文说明
     * @param supportedBuildModes 该运行时允许的构建模式集合
     */
    RuntimeType(String code, String message, Set<BuildMode> supportedBuildModes) {
        this.code = code;
        this.message = message;
        this.supportedBuildModes = supportedBuildModes;
    }

    /**
     * 根据数据库编码查找运行时类型。
     *
     * @param code 数据库或请求传入的运行时编码
     * @return 对应运行时类型；编码为空或不受支持时返回 null
     */
    public static RuntimeType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断该运行时是否支持指定构建模式。
     *
     * <p>ProjectService 新增/修改项目时调用该校验组合合法性；构建模块落地后，
     * 分支逻辑也应以本方法为准，避免各处散落组合判断。</p>
     *
     * @param buildMode 待校验的构建模式，允许为 null
     * @return true 表示组合合法；buildMode 为 null 时返回 false
     */
    public boolean supports(BuildMode buildMode) {
        return buildMode != null && supportedBuildModes.contains(buildMode);
    }

    /**
     * 返回该运行时支持的构建模式编码，用英文逗号分隔。
     *
     * <p>用于拼装异常提示，让调用方直接看到合法取值，不用去翻代码或文档。
     * 按 {@link BuildMode} 的声明顺序输出（而不是 Set 的遍历顺序），
     * 保证同一份规则每次拼出的提示文案稳定、便于比对。</p>
     *
     * @return 形如 "PLATFORM_TEMPLATE,PROJECT_DOCKERFILE,LAYERED_DOCKER" 的编码列表
     */
    public String supportedBuildModeCodes() {
        return Arrays.stream(BuildMode.values())
                .filter(supportedBuildModes::contains)
                .map(BuildMode::getCode)
                .collect(Collectors.joining("、"));
    }
}
