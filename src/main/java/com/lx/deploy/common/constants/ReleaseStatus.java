package com.lx.deploy.common.constants;

/**
 * 发布版本状态数字编码常量。
 *
 * <p>发布版本状态保存在 release_record.status，数据库使用 TINYINT 数字编码。
 * 构建成功会自动创建一条状态为「可发布」的发布版本；发布成功后历史版本可被置为「已废弃」，
 * 不物理删除（AGENTS.md §23.4 / 项目模块 §2.3.4）。</p>
 *
 * <p>状态字段统一使用本类的 int 常量比较，不使用枚举表达状态（AGENTS.md §4.5）。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
public final class ReleaseStatus {

    /** 构建中：版本刚由构建任务创建，尚未就绪。 */
    public static final int BUILDING = 0;

    /** 可发布：构建成功并已登记产物/镜像，可被部署任务引用。 */
    public static final int RELEASABLE = 1;

    /** 已废弃：被更新的版本取代，保留记录但不再参与回滚。 */
    public static final int DEPRECATED = 2;

    /**
     * 工具类禁止实例化：所有成员都是编译期常量。
     */
    private ReleaseStatus() {
    }

    /**
     * 判断状态编码是否为合法取值。
     *
     * @param status 状态编码，允许为 null
     * @return true 表示是 0/1/2 之一；null 与其它数字返回 false
     */
    public static boolean isValid(Integer status) {
        return status != null && (status == BUILDING || status == RELEASABLE || status == DEPRECATED);
    }

    /**
     * 将状态编码转换为中文描述。
     *
     * @param status 状态编码，允许为 null
     * @return 状态中文描述；未识别的编码返回「未知状态」
     */
    public static String describe(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case BUILDING -> "构建中";
            case RELEASABLE -> "可发布";
            case DEPRECATED -> "已废弃";
            default -> "未知状态";
        };
    }
}
