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

    /**
     * 拼一条把多行文本<b>原样</b>写入远程文件的命令。
     *
     * <p>用 quoted heredoc（{@code <<'MARKER'}）：shell 对其中内容不做任何替换，
     * 因此 {@code ${VAR}}、反引号、双引号都不必额外转义。这一点对下发项目自带的
     * docker-compose.yml 是硬要求——平台必须把文件交给 docker 时保持逐字节一致，
     * 中途做一次替换就可能悄悄改掉用户的意图。</p>
     *
     * <p>分隔符要<b>保证</b>不出现在内容里，否则文件被提前截断且不报错，
     * 表现为「compose 文件少了一截、容器起不来」，排查方向完全跑偏。
     * 这里循环加后缀直到唯一，而不是假定某个名字够特别：内容来自用户仓库，任何假定都可能被现实打脸。</p>
     *
     * @param path    目标机上的文件绝对路径
     * @param content 文件正文；内部遗留 CR 由调用方负责，这里只保证结尾有换行
     * @return 可直接执行的 shell 命令
     */
    public static String heredocWrite(String path, String content) {
        String body = content == null ? "" : content;
        String marker = "LD_EOF";
        for (int i = 1; containsMarkerLine(body, marker); i++) {
            marker = "LD_EOF_" + i;
        }
        String ensured = body.endsWith("\n") ? body : body + "\n";
        return "cat > " + quote(path) + " <<'" + marker + "'\n" + ensured + marker + "\n";
    }

    /**
     * 判断文本中是否存在与分隔符相同的整行（允许前后空白——heredoc 只在行首完全匹配时才结束，
     * 缩进写法 {@code <<-} 更是只看行首，因此这里的宽松判定是故意保守的）。
     */
    private static boolean containsMarkerLine(String content, String marker) {
        return content.lines().anyMatch(line -> line.strip().equals(marker));
    }
}
