package com.lx.deploy.common.enums.build;

import lombok.Getter;

import java.util.Arrays;

/**
 * 构建产物类型枚举。
 *
 * <p>数据库以字符串保存稳定编码，对应 build_artifact.artifact_type 列。
 * 产物类型决定后续登记的内容：镜像类记录镜像标签与摘要，文件类记录产物路径与 SHA256。</p>
 *
 * <p>取值与 AGENTS.md §3 的运行时×构建模式矩阵一致：
 * DOCKER 构建产出 BASE_IMAGE（分层基础镜像）与 APP_IMAGE（业务镜像），
 * JAR / STATIC 脚本构建产出 JAR 或 STATIC 文件。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Getter
public enum ArtifactType {

    /** 分层构建的基础依赖镜像。 */
    BASE_IMAGE("BASE_IMAGE", "基础镜像"),

    /** 业务应用镜像。 */
    APP_IMAGE("APP_IMAGE", "应用镜像"),

    /** Java 构建产物 JAR 包。 */
    JAR("JAR", "JAR包"),

    /** 前端构建产物静态目录。 */
    STATIC("STATIC", "静态产物");

    private final String code;
    private final String message;

    /**
     * 初始化构建产物类型。
     *
     * @param code    数据库存储的产物类型编码
     * @param message 面向页面和日志的中文说明
     */
    ArtifactType(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找构建产物类型。
     *
     * @param code 数据库或请求传入的产物类型编码
     * @return 对应产物类型；编码为空或不受支持时返回 null
     */
    public static ArtifactType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
