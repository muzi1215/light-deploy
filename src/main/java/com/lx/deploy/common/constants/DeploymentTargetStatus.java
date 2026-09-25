package com.lx.deploy.common.constants;

/**
 * 部署目标任务状态数字编码常量。
 *
 * <p>状态保存在 deployment_target.status，数据库使用 TINYINT 数字编码。
 * 目标状态与主任务状态一一对应，但多一个 5 已跳过：滚动发布某批失败后，
 * 尚未执行的后续目标被置为已跳过，表示「本次没有动它」而不是「它失败了」。
 * 这一区分很重要——回滚时不能回滚被跳过的目标，否则会把从未更新过的机器回退到旧版本。</p>
 *
 * <p>状态只能通过 DeploymentService 的流转方法修改，普通更新接口不允许直接写 status。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
public final class DeploymentTargetStatus {

    /** 排队：目标已创建，等待所在批次被调度。 */
    public static final int QUEUED = 0;

    /** 发布中：正在该目标服务器上执行发布动作。 */
    public static final int DEPLOYING = 1;

    /** 成功：发布与健康检查（若配置）均通过。 */
    public static final int SUCCESS = 2;

    /** 失败：发布动作或健康检查失败。 */
    public static final int FAILED = 3;

    /** 已取消：主任务在终态前被取消，尚未完成的目标随之为已取消。 */
    public static final int CANCELED = 4;

    /** 已跳过：策略中止导致该目标未执行，保持旧版本不变。 */
    public static final int SKIPPED = 5;

    /**
     * 工具类禁止实例化：所有成员都是编译期常量。
     */
    private DeploymentTargetStatus() {
    }

    /**
     * 校验状态编码是否合法。
     *
     * @param status 待校验的状态编码，允许为 null
     * @return true 表示属于上表六种取值之一；null 与其他数字返回 false
     */
    public static boolean isValid(Integer status) {
        if (status == null) {
            return false;
        }
        return status >= QUEUED && status <= SKIPPED;
    }

    /**
     * 判断目标是否还停留在初始状态、尚未动过。
     *
     * <p>排队与已跳过都属于「本次没有真正更新过这台机器」：前者还没轮到，后者因策略中止没有执行。
     * 回滚只针对 DEPLOYING / SUCCESS / FAILED 三类真正发生过变更的目标。</p>
     *
     * @param status 状态编码，允许为 null
     * @return true 表示未发生变更（QUEUED 或 SKIPPED）
     */
    public static boolean isUntouched(Integer status) {
        return status != null && (status == QUEUED || status == SKIPPED);
    }

    /**
     * 判断状态是否为终态。
     *
     * @param status 状态编码，允许为 null
     * @return true 表示已结束（成功/失败/已取消/已跳过）；null 与其他编码返回 false
     */
    public static boolean isFinal(Integer status) {
        return status != null
                && (status == SUCCESS || status == FAILED || status == CANCELED || status == SKIPPED);
    }

    /**
     * 将状态编码转换为中文描述。
     *
     * <p>用于异常提示、任务日志与页面文案，保证状态含义只有一处定义。</p>
     *
     * @param status 状态编码，允许为 null
     * @return 状态中文描述；未识别的编码返回「未知状态」
     */
    public static String describe(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case QUEUED -> "排队";
            case DEPLOYING -> "发布中";
            case SUCCESS -> "成功";
            case FAILED -> "失败";
            case CANCELED -> "已取消";
            case SKIPPED -> "已跳过";
            default -> "未知状态";
        };
    }
}
