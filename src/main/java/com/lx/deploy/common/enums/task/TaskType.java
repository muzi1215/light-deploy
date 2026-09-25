package com.lx.deploy.common.enums.task;

import lombok.Getter;

import java.util.Arrays;

/**
 * 异步任务类型枚举。
 *
 * <p>数据库以字符串保存稳定编码。SERVER_TEST 为服务器连通性测试，
 * PROJECT_TEST 为项目连通性测试（在构建机上验证 Git 仓库与镜像仓库可达、凭据可用），
 * BUILD 为构建任务，DEPLOY 为发布任务，通过 bizId 关联具体业务记录。</p>
 *
 * <p>对应数据库字段 lds_task.task_type。</p>
 *
 * @author lx
 * @since 2026-09-05 19:32:43
 */
@Getter
public enum TaskType {

    /** 服务器连通性测试任务。 */
    SERVER_TEST("SERVER_TEST", "服务器连通性测试"),
    /** 项目连通性测试任务：在项目的构建机上验证代码仓库与镜像仓库是否可用。 */
    PROJECT_TEST("PROJECT_TEST", "项目连通性测试"),
    /** 构建任务，产出构建产物。 */
    BUILD("BUILD", "构建任务"),
    /** 发布任务，将版本发布到目标服务器。 */
    DEPLOY("DEPLOY", "发布任务");

    private final String code;
    private final String message;

    /**
     * 初始化任务类型。
     *
     * @param code 数据库存储的任务类型编码
     * @param message 面向页面和日志的中文说明
     */
    TaskType(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找任务类型。
     *
     * @param code 数据库或请求传入的任务类型编码
     * @return 对应任务类型；编码为空或不受支持时返回 null
     */
    public static TaskType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
