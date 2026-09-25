package com.lx.deploy.common.constants;

/**
 * 构建状态数字编码常量。
 *
 * <p>构建状态保存在 build.status，数据库使用 TINYINT 数字编码，状态单向推进：
 * 0 排队中 → 1 构建中 → 2 成功 / 3 失败。与任务状态不同，构建没有「已取消」编码，
 * 取消由底层 lds_task 承载；build 表只记录构建自身的终态成功或失败。</p>
 *
 * <p>状态字段统一使用本类的 int 常量比较，不使用枚举表达状态（AGENTS.md §4.5）。
 * 对应的 Java 字段类型为 Integer，禁止在业务代码里散落 0/1/2/3 这类无含义字面量。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
public final class BuildStatus {

    /** 排队中：构建记录已创建，对应任务等待执行器调度。 */
    public static final int QUEUED = 0;

    /** 构建中：执行器已开始执行，对应任务处于执行中状态。 */
    public static final int BUILDING = 1;

    /** 成功：构建完成且产物/镜像已登记，可创建发布版本。 */
    public static final int SUCCESS = 2;

    /** 失败：构建过程报错，失败原因写入 error_message。 */
    public static final int FAILED = 3;

    /**
     * 工具类禁止实例化：所有成员都是编译期常量。
     */
    private BuildStatus() {
    }

    /**
     * 判断状态是否为终态。
     *
     * <p>终态（成功/失败）的构建不允许再次触发或删除之外的状态流转；
     * 只有终态构建才允许删除（AGENTS.md §4.5 状态编码总表）。</p>
     *
     * @param status 构建状态编码，允许为 null
     * @return true 表示已结束（成功/失败）；null 与其它编码返回 false
     */
    public static boolean isFinal(Integer status) {
        return status != null && (status == SUCCESS || status == FAILED);
    }

    /**
     * 将状态编码转换为中文描述。
     *
     * <p>用于异常提示、构建日志与页面文案，保证状态含义只有一处定义。</p>
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
            case BUILDING -> "构建中";
            case SUCCESS -> "成功";
            case FAILED -> "失败";
            default -> "未知状态";
        };
    }
}
