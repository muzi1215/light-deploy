package com.lx.deploy.common.util;

import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * compose 形态下「版本号怎么进到项目自带的 docker-compose.yml 里」的唯一定义。
 *
 * <p>平台只约定一件事：项目在 compose 里把镜像 tag 写成占位符 {@code ${IMAGE_TAG}}，
 * 平台在发布时把实际版本号放进同名变量文件，由 docker compose 自己做插值。
 * 文件的其余部分（端口、卷、网络、环境变量、依赖顺序）平台一概不碰。</p>
 *
 * <p><b>为什么不直接把占位符替换成实际 tag 再下发</b>：那样运维上机看到的
 * {@code docker-compose.yml} 与仓库里的不是同一份，版本伪装成文件内容的一部分，
 * 排障时「文件里写的是啥」不再可信；反过来用变量文件注入， compose 永远是原样，
 * 版本只有一个地方看——滚动更新时也不用重写整个文件。</p>
 *
 * <p>构建与发布两侧共用本类，避免「构建期校验的占位符」和「发布期注入的变量名」各自漂移。</p>
 *
 * @author lx
 * @since 2026-09-29 00:42:00
 */
public final class ComposeInjection {

    /** 镜像版本占位符：项目在 compose 的 image 字段里写它（无默认值形态）。 */
    public static final String PLACEHOLDER = "${IMAGE_TAG}";

    /** 平台生成的环境变量名，与占位符同名去掉花括号。 */
    public static final String VARIABLE = "IMAGE_TAG";

    /** 下发到目标机的 compose 文件名。 */
    public static final String COMPOSE_FILE = "docker-compose.yml";

    /** 平台注入版本号用的变量文件；刻意不与 compose 的默认 .env 同名，避免与项目自己的 .env 互相覆盖。 */
    public static final String ENV_FILE = ".env.light-deploy";

    /**
     * 版本占位符匹配：兼容「无默认值」{@code ${IMAGE_TAG}} 与「带默认值」
     * {@code ${IMAGE_TAG:-auth-tool:dev}} / {@code ${IMAGE_TAG-default}} 两种合法写法。
     *
     * <p>早期实现用 {@code content.contains("${IMAGE_TAG}")} 做精确子串匹配，会被默认值语法
     * （TAG 后紧跟 {@code :} 或 {@code -} 而非 {@code }}）漏判，导致明明引用了 IMAGE_TAG 的
     * compose 被误报「找不到占位符」。改用正则匹配变量引用整体，两种写法都能命中。</p>
     */
    private static final Pattern IMAGE_TAG_REF =
            Pattern.compile("\\$\\{IMAGE_TAG(?:[:-][^}]*)?\\}");

    private ComposeInjection() {
    }

    /**
     * 校验项目提供的 compose 内容是否可用。
     *
     * <p>核心校验只有一条：<b>必须出现版本占位符</b>。缺少它意味着无论平台注入什么，
     * 容器用的都是写死的那一个 tag——发布 v12 实际还是拉 v11，甚至回到 latest，
     * 而发布流程全程报成功。这类错误必须在构建期就拦下来。</p>
     *
     * @param content compose 文件原文
     * @return 去掉首尾空白后的原文
     * @throws DeployException 内容为空或未出现占位符时抛出，文案给出可直接照做的修法
     */
    public static String requireUsable(String content) {
        if (!StringUtils.hasText(content)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "读取到的 docker-compose 文件为空，请确认仓库中存在该文件且路径配置正确");
        }
        String trimmed = content.strip();
        if (!IMAGE_TAG_REF.matcher(trimmed).find()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "项目的 compose 文件中找不到版本占位符 " + PLACEHOLDER
                            + "，平台无法指定本次要发布的镜像版本。"
                            + "请把需要随版本更新的服务的 image 写成『image: " + PLACEHOLDER
                            + "』（平台把 " + VARIABLE + " 注入为「完整镜像引用」含仓库地址与 tag；"
                            + "切勿在 compose 里再写仓库前缀，否则会变成 前缀:完整引用 的双重叠加）；"
                            + "不随版本变化的中间件（redis、mysql 等）保持原样即可");
        }
        return trimmed;
    }

    /**
     * 生成版本变量文件内容。
     *
     * @param imageTag 本次要发布的完整镜像标签（含仓库地址与 tag）
     * @return 变量文件正文，单行 {@code IMAGE_TAG=...}
     */
    public static String envContent(String imageTag) {
        return VARIABLE + "=" + imageTag + "\n";
    }
}
