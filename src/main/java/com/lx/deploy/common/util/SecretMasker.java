package com.lx.deploy.common.util;

import java.util.regex.Pattern;

/**
 * 命令与文本脱敏工具。
 *
 * <p>凡是可能含凭据明文的字符串（远程命令、异常消息）在进入任务日志前都必须过一遍这里。
 * 构建链路与项目连通性测试共用同一份规则，避免出现「构建的日志脱敏了、测试的没脱敏」这类漏点。</p>
 *
 * <p>注意：本工具只是**兜底**。更可靠的做法是从一开始就不让凭据出现在命令里
 * （如镜像仓库密码走 {@code --password-stdin}、Git 私钥走临时文件），脱敏只用于
 * 无法避免的情形（令牌必须落在 URL 密码位）。</p>
 *
 * @author lx
 * @since 2026-09-26 02:10:00
 */
public final class SecretMasker {

    /** {@code -p 密码} / {@code --password 密码} 形式的命令行凭据。 */
    private static final Pattern PASSWORD_ARG = Pattern.compile("(?i)(-p|--password)\\s+\\S+");

    /** {@code https://用户名:密码@主机} 形式的 URL userinfo。 */
    private static final Pattern URL_USER_INFO = Pattern.compile("(https?)://[^@/\\s]+@");

    /** 脱敏后使用的占位文本。 */
    private static final String MASK = "***";

    private SecretMasker() {
    }

    /**
     * 把命令中的凭据片段替换为 {@code ***}。
     *
     * <p>覆盖两类：命令行上的密码参数，以及 URL 里的 userinfo（含令牌注入后的地址）。
     * 识别不到任何凭据时原样返回。</p>
     *
     * @param command 待脱敏的命令，允许为 null
     * @return 脱敏后的文本；入参为 null 时返回 null
     */
    public static String maskSecret(String command) {
        if (command == null) {
            return null;
        }
        String masked = PASSWORD_ARG.matcher(command).replaceAll("$1 " + MASK);
        return URL_USER_INFO.matcher(masked).replaceAll("$1://" + MASK + "@");
    }
}
