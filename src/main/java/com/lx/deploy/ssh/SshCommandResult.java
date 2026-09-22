package com.lx.deploy.ssh;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.regex.Pattern;

/**
 * 单条远程命令的执行结果。
 *
 * <p>SSH 层只负责"执行并如实返回"，不对非零退出码抛异常：
 * 是否把失败升级为任务失败，由上层任务处理器按业务语义判断
 * （例如 {@code systemctl is-active} 返回非零本身就是有效结论）。</p>
 *
 * <p>输出内容在进入日志和响应前必须经过 {@link #maskSecrets(String)} 处理，
 * 避免远程命令意外回显凭据、Token 等敏感信息。</p>
 *
 * @param command   实际执行的命令文本
 * @param exitCode  远程命令退出码；通道未返回退出码时为 -1
 * @param stdout    标准输出内容
 * @param stderr    标准错误内容
 * @param truncated 输出是否因超过长度上限被截断
 * @author lx
 * @since 2026-09-06 01:20:00
 */
@Schema(description = "远程命令执行结果")
public record SshCommandResult(
        String command,
        int exitCode,
        String stdout,
        String stderr,
        boolean truncated) {

    /** 命令执行成功的退出码。 */
    private static final int EXIT_SUCCESS = 0;

    /**
     * 判断命令是否执行成功。
     *
     * @return 退出码为 0 返回 true
     */
    public boolean success() {
        return exitCode == EXIT_SUCCESS;
    }

    /**
     * 生成面向页面和日志的单行摘要。
     *
     * <p>只保留退出码与输出首行，避免整段命令输出污染任务日志；
     * 需要完整输出时由调用方自行读取 {@link #stdout()}。</p>
     *
     * @return 形如 {@code exit=0 stdout=Linux 5.10...} 的摘要文本
     */
    public String summary() {
        return "exit=" + exitCode
                + " stdout=" + firstLine(stdout)
                + (stderr == null || stderr.isBlank() ? "" : " stderr=" + firstLine(stderr));
    }

    /**
     * 对输出文本做敏感信息脱敏。
     *
     * <p>远程命令可能把环境变量、连接串或命令行参数中的凭据回显到 stdout/stderr，
     * 任务日志和接口响应都不允许出现明文凭据。这里按常见键名形态做正则遮盖，
     * 属于兜底策略，不能替代"命令本身不要输出凭据"的编写约束。</p>
     *
     * @param text 原始输出文本，允许为空
     * @return 脱敏后的文本；入参为空时原样返回
     */
    public static String maskSecrets(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // 形如 PASSWORD=xxx / "token": "xxx" / --password=xxx 的键值对，值统一替换为掩码。
        return SECRET_PATTERN.matcher(text).replaceAll("$1$2******");
    }

    /**
     * 提取文本首行并限制长度，用于日志摘要。
     *
     * @param text 原始文本，允许为空
     * @return 首行文本；为空时返回空串
     */
    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }

    /** 常见凭据键名的匹配模式，分组 1 为键名与分隔符、分组 2 为值的起始引号。 */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|access[_-]?key|access[_-]?secret|private[_-]?key)(\\s*[=:]\\s*)(\"?)([^\\s\"',;]+)");

}
