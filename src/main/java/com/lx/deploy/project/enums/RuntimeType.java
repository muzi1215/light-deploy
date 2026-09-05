package com.lx.deploy.project.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 项目运行时类型枚举。
 *
 * <p>数据库以字符串保存稳定编码，业务代码使用枚举区分三种部署运行时。
 * DOCKER 支持 Dockerfile 与 Docker Compose，JAR 使用 systemd 托管，
 * STATIC 为前端静态产物配合 Nginx 部署。</p>
 */
@Getter
public enum RuntimeType {

    /** Docker 容器运行时，Dockerfile 或 Docker Compose 部署。 */
    DOCKER("DOCKER", "Docker容器"),
    /** Java JAR 运行时，配合 systemd 服务部署。 */
    JAR("JAR", "Java JAR"),
    /** 前端静态产物运行时，配合 Nginx 部署。 */
    STATIC("STATIC", "静态产物");

    private final String code;
    private final String message;

    /**
     * 初始化运行时类型。
     *
     * @param code 数据库存储的运行时编码
     * @param message 面向页面和日志的中文说明
     */
    RuntimeType(String code, String message) {
        this.code = code;
        this.message = message;
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
}
