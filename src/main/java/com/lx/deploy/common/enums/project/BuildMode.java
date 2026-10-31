package com.lx.deploy.common.enums.project;

import lombok.Getter;

import java.util.Arrays;

/**
 * 构建模式枚举。
 *
 * <p>仅 DOCKER 运行时使用这些构建模式。AIops 项目使用分层构建：
 * 基础依赖镜像推送到阿里云镜像仓库，业务镜像基于基础镜像构建。</p>
 *
 * <p><b>这个枚举回答的是「平台做不做预先构建」</b>，不是「产物从哪来」、也不是「语言是什么」。
 * Docker 类模式（{@code PROJECT_DOCKERFILE} / {@code LAYERED_DOCKER}）平台<b>一步构建都不做</b>，
 * 直接把项目 Dockerfile 交给 docker，产物由 Dockerfile 自己<b>自包含</b>产出；
 * {@code JAR_PREBUILD} 则是「平台先跑构建产出<b>单一预构建产物</b>（Java 即 Maven 打出的 jar），
 * 再用只 {@code COPY} 该产物的极简 Dockerfile 包镜像」。因此同一份「只 COPY 预构建产物、
 * 自己不会构建」的 Dockerfile 只能配 {@code JAR_PREBUILD}——配成 {@code PROJECT_DOCKERFILE}
 * 会在 {@code docker build} 阶段报找不到构建产物（Java 下即 jar not found），
 * 这是本项目真实踩过的坑（见 docs/构建模式与执行链路.md）。</p>
 *
 * <p>与运行时的合法组合由 {@code RuntimeType.supportedBuildModes()} 唯一决定，
 * 前后端都不得另写一套判断。对应数据库字段 project.build_mode。</p>
 *
 * @author lx
 * @since 2026-09-05 19:32:43
 */
@Getter
public enum BuildMode {

    /**
     * 项目自带 Dockerfile 构建。
     *
     * <p><b>平台不做任何预先构建</b>：直接在工作区执行
     * {@code docker build -f <dockerfile_path> -t <image>:<version> .}，
     * 产物完全由这份 Dockerfile 自己产出（典型是带 builder 阶段的多阶段构建）。
     * 走 {@code BuildTaskHandler.buildDocker}。</p>
     *
     * <p>反例：若 dockerfile_path 指向「只 COPY 预构建产物（如 jar）」的极简 Dockerfile，
     * 本模式下必定失败（预构建产物是构建结果、不在 Git 里），那种 Dockerfile 应配
     * {@link #JAR_PREBUILD}（Java 即 jar，其他语言对应其各自的预构建产物）。</p>
     */
    PROJECT_DOCKERFILE("PROJECT_DOCKERFILE", "项目Dockerfile"),

    /**
     * Dockerfile.base 与 Dockerfile.app 分层构建。
     *
     * <p>与 {@link #PROJECT_DOCKERFILE} 同为「Dockerfile 自行构建」，差别只在构建动作拆成两跳：
     * 先构建基础层并推 {@code base-<version>}，再基于它构建业务层。平台同样不跑 Maven。
     * 走 {@code BuildTaskHandler.buildDocker} 的分层分支。</p>
     */
    LAYERED_DOCKER("LAYERED_DOCKER", "分层构建"),

    /**
     * 脚本部署。
     *
     * <p>不产出镜像：在构建机执行项目自带的构建命令（{@code build_config_json.build_command}，
     * 缺省时按运行时推导），把生成的 jar / 静态目录登记为产物，目标机按「文件型」发布
     * （JAR 走 systemd 原地替换，STATIC 走目录内容替换）。走 {@code BuildTaskHandler.buildFileArtifact}。</p>
     */
    SCRIPT_DEPLOY("SCRIPT_DEPLOY", "脚本部署"),

    /**
     * 仅发布已有产物，不做任何构建。
     *
     * <p>配套来源 {@code UPLOAD}：运维在页面上传 jar / 压缩包，平台只登记版本，不连构建机。
     * 目标机发布动作与 {@link #SCRIPT_DEPLOY} 相同。构建任务在本模式下不会真正执行构建。</p>
     */
    ARTIFACT_ONLY("ARTIFACT_ONLY", "仅发布产物"),

    /**
     * B1：平台先 Maven 打出 jar，再用极简 Dockerfile 包成镜像（目标机只纯 run）。
     *
     * <p>分三步：① 用 Maven 容器执行 {@code mvn package}（遵循 pom 的 finalName 与 outputDirectory）；
     * ② 定位 jar 后交给项目提供的极简 Dockerfile（只 {@code COPY} jar）构建镜像；
     * ③ 构建机上启动级验证通过后推送仓库。额外产出「平铺热修包」下发目标机，
     * 供平台不可用时就地重建。走 {@code BuildTaskHandler.buildJarPrebuild}。</p>
     *
     * <p><b>与 {@link #PROJECT_DOCKERFILE} 的分界线（语言中性表述）</b>：Dockerfile 是否<b>自包含构建</b>、
     * 自己能从源码产出可运行镜像。能（多阶段构建，如 Java 的 builder 阶段 {@code mvn package}、
     * Python 的 {@code pip install}）就选前者；不能（Dockerfile 只 {@code COPY} 平台预先构建出的
     * 单一产物）就必须选本模式。本模式当前仅实现了 Java（Maven）这一支；其他语言不需要本模式——
     * 其 Dockerfile 多为自包含构建，直接选 {@link #PROJECT_DOCKERFILE} 即可，平台代码零改动。</p>
     */
    JAR_PREBUILD("JAR_PREBUILD", "JAR预构建镜像");

    private final String code;
    private final String message;

    /**
     * 初始化构建模式。
     *
     * @param code 数据库存储的构建模式编码
     * @param message 面向页面和日志的中文说明
     */
    BuildMode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找构建模式。
     *
     * @param code 数据库或请求传入的构建模式编码
     * @return 对应构建模式；编码为空或不受支持时返回 null
     */
    public static BuildMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(mode -> mode.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
