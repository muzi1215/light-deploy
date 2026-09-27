package com.lx.deploy.common.enums.audit;

import lombok.Getter;

import java.util.Arrays;

/**
 * 操作日志的业务类型枚举。
 *
 * <p>对应数据库字段 audit_log.action。取值偏粗粒度：运维平台要回答的是「这次是新增、修改、
 * 删除，还是一次执行/回滚」，不需要细分到每张表，过细的取值只会让页面标签与筛选条件膨胀。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
@Getter
public enum BusinessType {

    /** 新增。 */
    INSERT("INSERT", "新增"),

    /** 修改。 */
    UPDATE("UPDATE", "修改"),

    /** 删除（平台统一为逻辑删除）。 */
    DELETE("DELETE", "删除"),

    /** 导出。 */
    EXPORT("EXPORT", "导出"),

    /** 导入。 */
    IMPORT("IMPORT", "导入"),

    /** 执行：触发一次异步动作，如新建部署、提交构建、连通性测试。 */
    EXECUTE("EXECUTE", "执行"),

    /** 回滚：把已发布版本退回上一个版本。 */
    ROLLBACK("ROLLBACK", "回滚"),

    /** 取消：中断进行中的部署或任务。 */
    CANCEL("CANCEL", "取消"),

    /** 其他：不属于以上分类的操作。 */
    OTHER("OTHER", "其他");

    private final String code;
    private final String message;

    /**
     * 初始化业务类型。
     *
     * @param code 数据库存储的类型编码
     * @param message 面向页面的中文说明
     */
    BusinessType(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找业务类型。
     *
     * @param code 数据库或请求传入的类型编码
     * @return 对应业务类型；编码为空或不受支持时返回 null
     */
    public static BusinessType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 将编码转换为中文描述。
     *
     * @param code 类型编码，允许为 null 或不受支持的取值
     * @return 中文描述；无法识别时返回「其他」
     */
    public static String describe(String code) {
        BusinessType type = fromCode(code);
        return type == null ? OTHER.message : type.message;
    }
}
