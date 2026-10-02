package com.lx.deploy.common.util;

import com.lx.deploy.common.enums.project.DeployForm;
import com.lx.deploy.common.exception.DeployException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * compose 形态的准入校验与版本注入。
 *
 * <p>{@link ComposeInjection#requireUsable} 是构建期的唯一闸门：放过去一个没有版本占位符的
 * compose，容器会永远拉同一个 tag —— 发布报成功、线上没变化，属于最难发现的一类故障。
 * 这里把「放什么、拦什么」逐条钉死。</p>
 *
 * @author lx
 * @since 2026-09-29 01:00:00
 */
class ComposeInjectionTests {

    @Test
    void rejectsBlankContent() {
        assertThatThrownBy(() -> ComposeInjection.requireUsable("   "))
                .isInstanceOf(DeployException.class)
                .hasMessageContaining("docker-compose 文件为空");
    }

    @Test
    void rejectsComposeWithoutVersionPlaceholder() {
        // 写死 tag 的 compose：发布 v12 实际还是拉 v11，但流程全程报成功。
        String content = "services:\n  aiops:\n    image: registry/lx/aiops:v11\n";
        assertThatThrownBy(() -> ComposeInjection.requireUsable(content))
                .isInstanceOf(DeployException.class)
                .hasMessageContaining(ComposeInjection.PLACEHOLDER)
                .hasMessageContaining("无法指定本次要发布的镜像版本");
    }

    @Test
    void acceptsComposeWherePlaceholderIsOnOneServiceOnly() {
        // 多服务编排里只有业务镜像随版本变，中间件保持固定 tag —— 这是最典型的写法，必须放行。
        String content = String.join("\n",
                "services:",
                "  aiops:",
                "    image: registry/lx/aiops:${IMAGE_TAG}",
                "  redis:",
                "    image: redis:7-alpine",
                "");
        assertThat(ComposeInjection.requireUsable(content)).isEqualTo(content.strip());
    }

    @Test
    void trimsSurroundingBlanks() {
        assertThat(ComposeInjection.requireUsable("  a: ${IMAGE_TAG} \n"))
                .isEqualTo("a: ${IMAGE_TAG}");
    }

    @Test
    void envContentIsSingleVariableLine() {
        assertThat(ComposeInjection.envContent("registry/lx/aiops:aiops-v12-a1b2c3"))
                .isEqualTo("IMAGE_TAG=registry/lx/aiops:aiops-v12-a1b2c3\n");
    }

    @Test
    void deployFormFallsBackToRunForUnknownValues() {
        // 未知值不能让发布链路崩掉，也不能悄悄切到 compose（那会因缺少 compose 快照而失败）。
        assertThat(DeployForm.fromCode(null)).isEqualTo(DeployForm.RUN);
        assertThat(DeployForm.fromCode("")).isEqualTo(DeployForm.RUN);
        assertThat(DeployForm.fromCode("kubernetes")).isEqualTo(DeployForm.RUN);
        assertThat(DeployForm.fromCode("COMPOSE")).isEqualTo(DeployForm.COMPOSE);
        assertThat(DeployForm.fromCode(" compose ")).isEqualTo(DeployForm.COMPOSE);
    }
}
