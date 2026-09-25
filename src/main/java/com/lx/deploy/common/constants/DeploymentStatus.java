package com.lx.deploy.common.constants;

/**
 * 部署主任务状态数字编码常量。
 *
 * <p>状态保存在 deployment.status，数据库使用 TINYINT 数字编码。发布主流程单向推进：
 * 0 排队 → 1 发布中 → 2 成功 / 3 失败 / 4 已取消；成功之后允许人工回滚进入
 * 5 回滚中 → 6 已回滚。2/3/6 为终态，4 已取消不可再流转。</p>
 *
 * <p>主任务状态由各目标状态汇总推导：任一目标失败即整体失败，全部目标成功才是成功。
 * 状态只能通过 DeploymentService 的流转方法修改，普通更新接口不允许直接写 status。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
public final class DeploymentStatus {

    /** 排队：记录已创建，等待执行器调度。 */
    public static final int QUEUED = 0;

    /** 发布中：执行器已开始按策略编排目标并逐个发布。 */
    public static final int DEPLOYING = 1;

    /** 成功：全部目标发布成功。 */
    public static final int SUCCESS = 2;

    /** 失败：至少一个目标发布失败，且未进入回滚。 */
    public static final int FAILED = 3;

    /** 已取消：人工在终态前取消，已开始的目标不会被强制中断。 */
    public static final int CANCELED = 4;

    /** 回滚中：正在按各目标 old_version 重新发布。 */
    public static final int ROLLING_BACK = 5;

    /** 已回滚：回滚完成。 */
    public static final int ROLLED_BACK = 6;

    /**
     * 工具类禁止实例化：所有成员都是编译期常量。
     */
    private DeploymentStatus() {
    }

    /**
     * 校验状态编码是否合法。
     *
     * @param status 待校验的状态编码，允许为 null
     * @return true 表示属于上表七种取值之一；null 与其他数字返回 false
     */
    public static boolean isValid(Integer status) {
        if (status == null) {
            return false;
        }
        return status >= QUEUED && status <= ROLLED_BACK;
    }

    /**
     * 判断状态是否为终态。
     *
     * <p>终态不再接受取消之外的状态流转，也不允许被回滚接口之外的操作修改；
     * 删除接口以本方法作为守卫条件，避免删掉正在执行的部署。</p>
     *
     * @param status 状态编码，允许为 null
     * @return true 表示已结束（成功/失败/已取消/已回滚）；null 与其他编码返回 false
     */
    public static boolean isFinal(Integer status) {
        return status != null
                && (status == SUCCESS || status == FAILED || status == CANCELED || status == ROLLED_BACK);
    }

    /**
     * 判断状态是否允许发起回滚。
     *
     * <p>只有发布成功的部署才有可回滚的「新版本」；失败、已取消、已回滚与进行中的部署不重复回滚，
     * 避免把同一批目标来回覆盖成不同版本。</p>
     *
     * @param status 状态编码，允许为 null
     * @return true 表示可回滚（仅 SUCCESS）
     */
    public static boolean canRollback(Integer status) {
        return status != null && status == SUCCESS;
    }

    /**
     * 判断状态是否允许取消。
     *
     * <p>排队中与发布中可以取消；终态取消无意义，回滚中不允许取消（回滚必须走完，
     * 中途取消会让目标停留在新旧混合状态）。</p>
     *
     * @param status 状态编码，允许为 null
     * @return true 表示可取消（QUEUED 或 DEPLOYING）
     */
    public static boolean canCancel(Integer status) {
        return status != null && (status == QUEUED || status == DEPLOYING);
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
            case ROLLING_BACK -> "回滚中";
            case ROLLED_BACK -> "已回滚";
            default -> "未知状态";
        };
    }
}
