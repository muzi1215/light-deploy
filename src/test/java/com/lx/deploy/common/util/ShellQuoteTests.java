package com.lx.deploy.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShellQuote} 的行为验证，重点是 {@link ShellQuote#heredocWrite}。
 *
 * <p>这里不 mock shell，而是真的用 {@code sh -c} 执行生成的命令、再把文件读回来比对。
 * 原因：只断言字符串长相测不出「转义漏了一层，变量被 shell 提前吃掉」这类问题，
 * 而平台下发的正是用户手写的 docker-compose.yml —— 里面有 {@code ${VAR}}、引号、反引号，
 * 是 heredoc 语义最容易出岔子的输入。</p>
 *
 * @author lx
 * @since 2026-09-29 01:00:00
 */
class ShellQuoteTests {

    @Test
    void quoteWrapsPathWithBlanks() {
        assertThat(ShellQuote.quote("/opt/my app/x.yml")).isEqualTo("'/opt/my app/x.yml'");
        assertThat(ShellQuote.quote(null)).isEqualTo("''");
    }

    @Test
    void heredocWritesComposeFileByteForByte(@TempDir Path dir) throws Exception {
        // 刻意混进 shell 里有特殊含义的字符：变量、命令替换、单双引号、反斜杠、空行。
        String content = String.join("\n",
                "services:",
                "  aiops:",
                "    image: registry.cn-hangzhou.aliyuncs.com/lx/aiops:${IMAGE_TAG}",
                "    container_name: aiops",
                "    environment:",
                "      - TZ=Asia/Shanghai",
                "      - APP_NAME=\"ai ops\"",
                "    command: sh -c 'echo $HOME `whoami` \\ done'",
                "",
                "  redis:",
                "    image: redis:7-alpine",
                "");
        Path target = dir.resolve("docker-compose.yml");

        execute(ShellQuote.heredocWrite(target.toString(), content));

        // 原样落盘：变量没被 shell 求值，引号与反引号保持字面。
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    void heredocPicksMarkerNotPresentInContent(@TempDir Path dir) throws Exception {
        // 内容里正好有一行 LD_EOF：分隔符若写死，文件会被静默截断，
        // 表现为「compose 少了一截、容器起不来」，排查方向完全跑偏。
        String content = "services:\n  a:\n    image: x:${IMAGE_TAG}\nLD_EOF\n    restart: always\n";
        Path target = dir.resolve("c.yml");

        execute(ShellQuote.heredocWrite(target.toString(), content));

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(content);
        assertThat(ShellQuote.heredocWrite(target.toString(), content)).contains("LD_EOF_1");
    }

    @Test
    void heredocAppendsTrailingNewline(@TempDir Path dir) throws Exception {
        String content = "a: ${IMAGE_TAG}";
        Path target = dir.resolve("d.yml");

        execute(ShellQuote.heredocWrite(target.toString(), content));

        // 不补换行的话结束分隔符会被拼到正文同一行，文件少最后一截。
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(content + "\n");
    }

    @Test
    void heredocHandlesEmptyContent(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("e.yml");
        execute(ShellQuote.heredocWrite(target.toString(), ""));
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("\n");
    }

    @Test
    void heredocCommandShape() {
        String command = ShellQuote.heredocWrite("/tmp/x.yml", "svc:\n");
        assertThat(command).startsWith("cat > '/tmp/x.yml' <<'LD_EOF'").endsWith("LD_EOF\n");
    }

    /**
     * 在真实 shell 里执行生成的命令：断言命令可执行性，而不只是字符串形状。
     */
    private void execute(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            assertThat(code).as("命令执行失败%n输出：%s%n命令：%s", output, command).isEqualTo(0);
        } catch (IOException e) {
            throw new IllegalStateException("无法执行 shell 命令", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("执行被中断", e);
        }
    }
}
