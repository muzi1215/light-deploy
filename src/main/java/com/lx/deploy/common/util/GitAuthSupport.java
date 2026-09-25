package com.lx.deploy.common.util;

import org.springframework.util.StringUtils;

/**
 * Git 仓库鉴权与地址处理的公共实现。
 *
 * <p>构建链路（{@code BuildTaskHandler}）与项目连通性测试（{@code ProjectConnectTestTaskHandler}）
 * 需要对同一份仓库地址做完全相同的处理：把令牌注入 HTTPS 地址的密码位、剥掉地址里已有的
 * userinfo、按协议选择鉴权方式。**必须共用这一份实现**——两处各写一套，迟早会在
 * 「谁该补 oauth2」「谁该剔掉多余用户名」上出现行为分叉，表现为「测试通过但构建失败」。</p>
 *
 * <p>本类只做地址与命令片段拼装，**不接触凭据明文以外的任何状态**，也不写日志；
 * 调用方负责在写日志前用 {@link SecretMasker} 脱敏。</p>
 *
 * @author lx
 * @since 2026-09-26 02:10:00
 */
public final class GitAuthSupport {

    /** HTTPS 协议前缀。 */
    public static final String HTTPS_PREFIX = "https://";

    /**
     * 令牌形态凭据的默认用户名占位。
     *
     * <p>HTTPS 地址的用户名位不参与认证，但**不允许为空**：GitLab 官方口径「任意非空字符串，
     * GitLab 不校验该值」，GitHub 官方口径「用户名不用于认证，但不填会报凭据无效」，
     * Gitee 用 {@code oauth2}。三家统一补这个占位符即可通用。</p>
     */
    public static final String DEFAULT_GIT_TOKEN_USER = "oauth2";

    /**
     * 克隆与探测命令统一前置的非交互开关。
     *
     * <p>构建机上的 git 跑在非交互 SSH 会话里，一旦转去索要凭据，报错会是
     * {@code could not read Password ... No such device or address}，
     * 把真正的原因（URL 里缺密码段 / 令牌无效）掩盖成「认证失败」。加上它让 git 直接失败并给出真实原因。</p>
     */
    public static final String NO_PROMPT_PREFIX = "GIT_TERMINAL_PROMPT=0 ";

    /** SSH 命令前缀的固定片段，配合私钥文件使用。 */
    private static final String SSH_OPTIONS = " -o StrictHostKeyChecking=no -o BatchMode=yes";

    private GitAuthSupport() {
    }

    /**
     * 判断仓库地址是否为 HTTPS 协议。
     *
     * @param gitUrl 仓库地址，允许为 null
     * @return true 表示 https:// 开头
     */
    public static boolean isHttps(String gitUrl) {
        return gitUrl != null && gitUrl.startsWith(HTTPS_PREFIX);
    }

    /**
     * 判断仓库地址是否为 HTTP / HTTPS 协议（即非 SSH）。
     *
     * @param gitUrl 仓库地址，允许为 null
     * @return true 表示 http:// 或 https:// 开头
     */
    public static boolean isHttpProtocol(String gitUrl) {
        return isHttps(gitUrl) || (gitUrl != null && gitUrl.startsWith("http://"));
    }

    /**
     * 拼装「带令牌」的 HTTPS 地址。
     *
     * <p>明文支持两种写法，由用户在同一份凭据里表达：</p>
     * <ul>
     *   <li>{@code 用户名:令牌} —— 用户指定用户名，兼容必须用真实用户名的平台或自建 Git 服务；</li>
     *   <li>{@code 令牌} —— 只填令牌，平台补 {@link #DEFAULT_GIT_TOKEN_USER} 占位并把它落到密码位。</li>
     * </ul>
     *
     * <p>另做两处容错：用户可能把整条 {@code https://…@} 粘进凭据（剥掉前缀与结尾 {@code @}，
     * 否则会拼出 {@code https://https://}）；仓库地址本身也可能已经带了用户名（先剔除，
     * 避免与凭据里的用户名叠成两段）。</p>
     *
     * @param gitUrl    HTTPS 仓库地址
     * @param plaintext 凭据明文
     * @return 形如 {@code https://oauth2:令牌@主机/路径} 的地址
     */
    public static String buildTokenUrl(String gitUrl, String plaintext) {
        String hostPath = gitUrl.substring(HTTPS_PREFIX.length());
        int at = hostPath.indexOf('@');
        if (at >= 0) {
            hostPath = hostPath.substring(at + 1);
        }
        String userInfo = plaintext == null ? "" : plaintext.strip();
        if (userInfo.startsWith(HTTPS_PREFIX)) {
            userInfo = userInfo.substring(HTTPS_PREFIX.length());
        }
        if (userInfo.endsWith("@")) {
            userInfo = userInfo.substring(0, userInfo.length() - 1);
        }
        // 没有冒号说明只给了令牌：补用户名占位，让令牌落在密码位。
        if (userInfo.indexOf(':') < 0) {
            userInfo = DEFAULT_GIT_TOKEN_USER + ":" + userInfo;
        }
        return HTTPS_PREFIX + userInfo + "@" + hostPath;
    }

    /**
     * 去掉仓库地址里的 userinfo（{@code https://用户名:密码@主机/路径} → {@code https://主机/路径}）。
     *
     * <p>两个用途：拼装带令牌地址前剔除地址里原有的用户名，避免与凭据里的用户名叠加；
     * 以及给出克隆成功后要复位成的干净地址（git 会把含明文令牌的完整地址写进工作区的
     * {@code .git/config}）。</p>
     *
     * @param gitUrl 原始仓库地址
     * @return 剥掉 userinfo 的地址；本来就没有 userinfo 时原样返回
     */
    public static String stripUserInfo(String gitUrl) {
        if (gitUrl == null) {
            return null;
        }
        int scheme = gitUrl.indexOf("://");
        if (scheme < 0) {
            return gitUrl;
        }
        String rest = gitUrl.substring(scheme + 3);
        int at = rest.indexOf('@');
        int slash = rest.indexOf('/');
        // 只有 @ 出现在第一个 / 之前才算 userinfo；SSH 形式（git@host:path）不含 ://，不会走到这里。
        if (at < 0 || (slash >= 0 && at > slash)) {
            return gitUrl;
        }
        return gitUrl.substring(0, scheme + 3) + rest.substring(at + 1);
    }

    /**
     * 拼装克隆 / 探测命令的分支参数片段。
     *
     * @param branch 分支名，可为空
     * @return {@code --branch <分支> }；分支为空时返回空串（使用仓库默认分支）
     */
    public static String branchOption(String branch) {
        return StringUtils.hasText(branch) ? "--branch " + branch + " " : "";
    }

    /**
     * 拼装引用 SSH 私钥的命令前缀。
     *
     * <p>私钥由调用方写到构建机临时文件后传路径进来；前缀里同时带上
     * {@code StrictHostKeyChecking=no}（首次连接不因未知主机而中断）与
     * {@code BatchMode=yes}（不可交互，认证失败立即返回而不等待输入）。</p>
     *
     * @param keyFile 构建机上的私钥文件路径
     * @return 形如 {@code GIT_SSH_COMMAND='ssh -i /tmp/key' } 的前缀
     */
    public static String sshCommandPrefix(String keyFile) {
        return "GIT_SSH_COMMAND='ssh -i " + keyFile + SSH_OPTIONS + "' ";
    }
}
