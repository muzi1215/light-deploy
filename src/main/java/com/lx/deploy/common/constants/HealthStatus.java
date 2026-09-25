package com.lx.deploy.common.constants;

/**
 * 部署目标健康检查状态数字编码常量。
 *
 * <p>状态保存在 deployment_target.health_status，数据库使用 TINYINT 且允许为 NULL。
 * 允许为空是刻意的：发布动作本身失败时不会走到健康检查，此时「未检查（0）」比 NULL 更容易表达，
 * 但数据库层保留 NULL 以兼容未配置健康检查的项目——页面展示时两者都渲染为「未检查」。</p>
 *
 * <p>健康检查只做观测，不改变目标的成功与否：发布动作成功但健康检查不健康时，
 * 目标仍记为失败并给出明确原因，避免把「发布成功但服务起不来」误报成成功。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
public final class HealthStatus {

    /** 未检查：未配置健康检查，或发布动作尚未执行到检查阶段。 */
    public static final int NOT_CHECKED = 0;

    /** 健康：探测在重试次数内通过。 */
    public static final int HEALTHY = 1;

    /** 不健康：超过重试次数仍未通过。 */
    public static final int UNHEALTHY = 2;

    /**
     * 工具类禁止实例化：所有成员都是编译期常量。
     */
    private HealthStatus() {
    }

    /**
     * 校验状态编码是否合法。
     *
     * @param status 待校验的状态编码，允许为 null
     * @return true 表示属于上表三种取值之一；null 与其他数字返回 false
     */
    public static boolean isValid(Integer status) {
        if (status == null) {
            return false;
        }
        return status >= NOT_CHECKED && status <= UNHEALTHY;
    }

    /**
     * 将状态编码转换为中文描述。
     *
     * <p>用于异常提示、任务日志与页面文案；未配置健康检查（null）同样渲染为「未检查」。</p>
     *
     * @param status 状态编码，允许为 null
     * @return 状态中文描述；未识别的编码返回「未知状态」
     */
    public static String describe(Integer status) {
        if (status == null) {
            return "未检查";
        }
        return switch (status) {
            case NOT_CHECKED -> "未检查";
            case HEALTHY -> "健康";
            case UNHEALTHY -> "不健康";
            default -> "未知状态";
        };
    }
}
