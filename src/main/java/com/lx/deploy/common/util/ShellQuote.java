package com.lx.deploy.common.util;

/**
 * shell 命令字面量转义。
 *
 * <p>平台拼出的远程命令里混着三类内容：平台自己的固定命令、目标机上的路径与文件名、
 * 以及来自数据库配置项的自由文本（工作目录、JVM 参数、服务名）。后两类可能含空格或引号，
 * 不转义时空格会把一条命令拆成多条，单引号会提前闭合字符串——两者都会让命令
 * 做不成它看起来在做的事，而报错信息往往指向完全无关的地方。</p>
 *
 * <p>转义规则是把值包进单引号，并把值内部的单引号替换成 {@code '\''}：
 * 先闭合当前引号、转义出一个字面单引号、再重新打开引号。</p>
 *
 * <p><b>为什么必须有唯一实现</b>：这条规则写错一处很难看出来，而此前项目里已经散落了
 * 四份各自手写的版本（deploy、build、release 各一份）。统一到这里之后，ssh 这类底层包
 * 也能安全拼命令，不必为了转义去依赖上层组件。</p>
 *
 * @author lx
 * @since 2026-09-27 23:20:00
 */
public final class ShellQuote {

    private ShellQuote() {
    }

    /**
     * 把任意值转成可直接拼进 shell 命令的单引号字面量。
     *
     * @param value 原始值，为 null 或空字符串时返回一对空引号 {@code ''}
     * @return 带引号的字面量，内部单引号已转义
     */
    public static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
