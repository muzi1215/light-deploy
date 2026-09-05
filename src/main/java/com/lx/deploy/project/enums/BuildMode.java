package com.lx.deploy.project.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * Docker 构建模式枚举。
 *
 * <p>仅 DOCKER 运行时使用这些构建模式。AIops 项目使用分层构建：
 * 基础依赖镜像推送到阿里云镜像仓库，业务镜像基于基础镜像构建。</p>
 */
@Getter
public enum BuildMode {

    /** 平台内置 Dockerfile 模板构建。 */
    PLATFORM_TEMPLATE("PLATFORM_TEMPLATE", "平台模板"),
    /** 使用项目仓库内自带的 Dockerfile 构建。 */
    PROJECT_DOCKERFILE("PROJECT_DOCKERFILE", "项目Dockerfile"),
    /** Dockerfile.base 与 Dockerfile.app 分层构建。 */
    LAYERED_DOCKER("LAYERED_DOCKER", "分层构建"),
    /** 直接发布已有 JAR 或静态产物，不做构建。 */
    ARTIFACT_ONLY("ARTIFACT_ONLY", "仅发布产物");

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
