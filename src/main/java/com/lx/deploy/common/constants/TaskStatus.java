package com.lx.deploy.common.constants;

/**
 * 异步任务状态数字编码常量。
 *
 * <p>任务状态保存在 lds_task.status，数据库使用 TINYINT 数字编码，状态流转为单向推进：
 * 0 排队中 → 1 执行中 → 2 成功 / 3 失败，执行中的任务可被取消进入 4 已取消。
 * 2/3/4 为终态，终态任务不允许再次流转，也不允许被执行器重新调度。</p>
 *
 * <p>状态只能通过 TaskService 的流转方法修改，普通更新接口不允许直接写 status 字段。</p>
 *
 * @author lx
 * @since 2026-09-23 01:10:00
 */
public final class TaskStatus {

    /** 排队中：任务已创建，等待执行器调度。 */
    public static final int QUEUED = 0;

    /** 执行中：执行器已开始执行，progress 与 current_stage 在这期间更新。 */
    public static final int RUNNING = 1;

    /** 成功：执行完成且结果校验通过，填写 finished_at 并清空 error_message。 */
    public static final int SUCCESS = 2;

    /** 失败：执行过程中报错，finished_at 与 error_message 必填。 */
    public static final int FAILED = 3;

    /** 已取消：人工或系统在终态前取消，远程操作不会因此被强制中断。 */
    public static final int CANCELED = 4;

    /**
     * 工具类禁止实例化：所有成员都是编译期常量。
     */
    private TaskStatus() {
    }

    /**
     * 判断状态是否为终态。
     *
     * <p>终态任务不允许再次流转，也不允许被删除之外的操作修改；
     * 任务的取消、更新、删除接口都以本方法作为守卫条件。</p>
     *
     * @param status 任务状态编码，允许为 null
     * @return true 表示已结束（成功/失败/已取消）；null 与其他编码返回 false
     */
    public static boolean isFinal(Integer status) {
        return status != null
                && (status == SUCCESS || status == FAILED || status == CANCELED);
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
            case QUEUED -> "排队中";
            case RUNNING -> "执行中";
            case SUCCESS -> "成功";
            case FAILED -> "失败";
            case CANCELED -> "已取消";
            default -> "未知状态";
        };
    }
}
