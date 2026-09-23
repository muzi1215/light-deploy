package com.lx.deploy.common.constants;

/**
 * 启用状态数字编码常量。
 *
 * <p>项目、项目环境部署目标等资源共用的启用状态，数据库统一使用 TINYINT 保存数字编码，
 * Java 侧只用本类的 int 常量比较，不使用枚举表达状态（AGENTS.md §22）。状态只表示资源
 * 是否允许参与部署，不能用来表达开发、测试或生产环境。</p>
 *
 * <p>对应数据库字段 project.status、project_environment_target.status。</p>
 *
 * @author lx
 * @since 2026-09-23 01:10:00
 */
public final class EnableStatus {

    /** 停用：资源不允许参与部署。 */
    public static final int DISABLED = 0;

    /** 启用：资源允许参与部署。 */
    public static final int ENABLED = 1;

    /**
     * 工具类禁止实例化：所有成员都是编译期常量。
     */
    private EnableStatus() {
    }

    /**
     * 判断状态编码是否为合法取值。
     *
     * <p>用于 Service 校验调用方传入的状态：只接受 0 和 1，
     * 其他值（含 null 之外的越界数字）一律视为非法，由调用方抛出参数校验异常。</p>
     *
     * @param status 待校验的状态编码，允许为 null
     * @return true 表示是 0（停用）或 1（启用）；null 与其它数字返回 false
     */
    public static boolean isValid(Integer status) {
        return status != null && (status == DISABLED || status == ENABLED);
    }

    /**
     * 将状态编码转换为中文描述。
     *
     * <p>用于异常提示与日志，避免在业务代码里散落 0/1 的字面量含义。</p>
     *
     * @param status 状态编码，允许为 null
     * @return 状态中文描述；未识别的编码返回「未知状态」
     */
    public static String describe(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        if (status == ENABLED) {
            return "启用";
        }
        if (status == DISABLED) {
            return "停用";
        }
        return "未知状态";
    }
}
