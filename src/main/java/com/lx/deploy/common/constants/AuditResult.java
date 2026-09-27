package com.lx.deploy.common.constants;

/**
 * 操作审计结果数字编码常量。
 *
 * <p>结果保存在 audit_log.result，数据库使用 TINYINT 且非空，默认 1（成功）。
 * 默认取成功而非失败：写入日志时「成功」是常态，切面只在捕获到异常时才显式改成失败。</p>
 *
 * <p>判定口径：只要请求最终抛出了异常（业务异常、参数校验失败、未预期异常）就记为失败，
 * 不区分异常类型——操作者需要的是「这次操作没生效」，异常分类属于日志的职责。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
public final class AuditResult {

    /** 失败：执行过程中抛出了异常。 */
    public static final int FAILURE = 0;

    /** 成功：方法正常返回。 */
    public static final int SUCCESS = 1;

    /**
     * 工具类禁止实例化：所有成员都是编译期常量。
     */
    private AuditResult() {
    }

    /**
     * 校验结果编码是否合法。
     *
     * @param result 待校验的结果编码，允许为 null
     * @return true 表示属于上表两种取值之一；null 与其他数字返回 false
     */
    public static boolean isValid(Integer result) {
        return result != null && (result == FAILURE || result == SUCCESS);
    }

    /**
     * 将结果编码转换为中文描述。
     *
     * @param result 结果编码，允许为 null
     * @return 结果中文描述；未识别的编码返回「未知结果」
     */
    public static String describe(Integer result) {
        if (result == null) {
            return "未知结果";
        }
        return switch (result) {
            case FAILURE -> "失败";
            case SUCCESS -> "成功";
            default -> "未知结果";
        };
    }
}
