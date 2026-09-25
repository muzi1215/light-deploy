package com.lx.deploy.common.util;

/**
 * 镜像仓库鉴权的公共实现。
 *
 * <p>构建链路（{@code BuildTaskHandler.dockerPush}）与项目连通性测试
 * （{@code ProjectConnectTestTaskHandler}）都要做同一件事：按 {@code 用户名:密码} 解析凭据、
 * 登录仓库、用完登出。**必须共用这一份实现**，否则两处对「凭据怎么切分」「密码怎么传」
 * 的处理一旦分叉，就会出现「测试能登录、构建推不上去」这种最难查的问题。</p>
 *
 * <p>密码一律走 {@code --password-stdin}，**不用** {@code docker login -p <密码>}：
 * 后者会把明文密码留在构建机的进程列表（{@code ps -ef}）里，同机其他用户可见。
 * 调用方负责把密码写到构建机临时文件（权限 600）并在用完后删除。</p>
 *
 * @author lx
 * @since 2026-09-26 02:10:00
 */
public final class RegistryAuthSupport {

    /** 登录前把密码文件喂给 docker 的命令片段。 */
    private static final String PASSWORD_STDIN = " --password-stdin";

    private RegistryAuthSupport() {
    }

    /**
     * 按**第一个**冒号切分 {@code 用户名:密码}。
     *
     * <p>只按第一个冒号切分：密码本身可能含冒号，按最后一个切会把用户名与密码都切错。
     * 无冒号时视为「只填了用户名」，密码为空——这种凭据注定登录失败，但解析不做语义判断，
     * 让 docker 报出真实错误比在这里猜更可靠。</p>
     *
     * @param plaintext 凭据明文，允许为 null
     * @return 长度为 2 的数组，[0] 用户名，[1] 密码
     */
    public static String[] parseUserPassword(String plaintext) {
        String text = plaintext == null ? "" : plaintext;
        int idx = text.indexOf(':');
        if (idx < 0) {
            return new String[]{text, ""};
        }
        return new String[]{text.substring(0, idx), text.substring(idx + 1)};
    }

    /**
     * 拼装从密码文件登录仓库的命令。
     *
     * <p>密码不进入命令行，只以文件内容的形式通过 stdin 传给 docker，因此该命令**可以**
     * 原样写进任务日志（仍建议用 {@link SecretMasker} 兜底）。</p>
     *
     * @param host         仓库地址（带端口的主机名，不含协议前缀）
     * @param username     用户名
     * @param passwordFile 构建机上存放密码的临时文件路径
     * @return 形如 {@code cat /tmp/x | docker login host -u user --password-stdin} 的命令
     */
    public static String dockerLoginCommand(String host, String username, String passwordFile) {
        return "cat " + passwordFile + " | docker login " + host + " -u " + username + PASSWORD_STDIN;
    }

    /**
     * 拼装登出命令。
     *
     * <p>登出只是清理 {@code ~/.docker/config.json} 里本次登录留下的认证信息，
     * 失败不影响结论，调用方可只记日志。</p>
     *
     * @param host 仓库地址
     * @return 形如 {@code docker logout host} 的命令
     */
    public static String dockerLogoutCommand(String host) {
        return "docker logout " + host;
    }
}
