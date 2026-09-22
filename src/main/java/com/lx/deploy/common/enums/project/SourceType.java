package com.lx.deploy.common.enums.project;

import lombok.Getter;

import java.util.Arrays;

/**
 * 项目代码来源类型枚举。
 *
 * <p>区别代码从 Git 仓库拉取和由用户直接上传产物两种来源。
 * GIT 来源在发布时按分支拉取代码构建，UPLOAD 来源直接使用上传的产物。</p>
 *
 * <p>对应数据库字段 project.source_type。GIT 来源必须提供 gitUrl。</p>
 *
 * @author lx
 * @since 2026-09-05 19:32:43
 */
@Getter
public enum SourceType {

    /** 从 Git 仓库拉取代码构建。 */
    GIT("GIT", "Git仓库"),
    /** 用户直接上传构建产物。 */
    UPLOAD("UPLOAD", "本地上传");

    private final String code;
    private final String message;

    /**
     * 初始化代码来源类型。
     *
     * @param code 数据库存储的来源编码
     * @param message 面向页面和日志的中文说明
     */
    SourceType(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找代码来源类型。
     *
     * @param code 数据库或请求传入的来源编码
     * @return 对应来源类型；编码为空或不受支持时返回 null
     */
    public static SourceType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
