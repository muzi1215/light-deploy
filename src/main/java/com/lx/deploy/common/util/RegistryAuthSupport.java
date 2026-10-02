package com.lx.deploy.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

/**
 * 镜像仓库鉴权的公共实现。
 *
 * <p>构建链路（{@code BuildTaskHandler.dockerPush}）与项目连通性测试
 * （{@code ProjectConnectTestTaskHandler}）都要做同一件事：解析凭据的
 * 用户名与密码、登录仓库、用完登出。**必须共用这一份实现**，否则两处对「凭据怎么切分」
 * 「密码怎么传」的处理一旦分叉，就会出现「测试能登录、构建推不上去」这种最难查的问题。</p>
 *
 * <p>密码一律走 {@code --password-stdin}，<b>不用</b> {@code docker login -p &lt;密码&gt;}：
 * 后者会把明文密码留在构建机的进程列表（{@code ps -ef}）里，同机其他用户可见。
 * 调用方负责把密码写到构建机临时文件（权限 600）并在用完后删除。</p>
 *
 * <p>凭据存储存在两种历史形态（必须都兼容，不能只认一种）：</p>
 * <ol>
 *   <li><b>推荐形态</b>：密码存 {@code encrypted_value}，用户名存 {@code metadata_json} 的
 *       {@code username} 字段。凭据模块文档与 {@code CredentialDto} 注释采用此形态，平台里
 *       现存的阿里云个人镜像仓库凭据也按此存储。</li>
 *   <li><b>兼容形态</b>：用户名与密码以 {@code 用户名:密码} 整体存 {@code encrypted_value}。
 *       前端 REGISTRY 凭据占位符（「用户名:密码，如 light-deploy:Abc123456」）引导用户
 *       这样填，早期实现也按此解析。</li>
 * </ol>
 *
 * <p>解析规则见 {@link #resolveRegistryCredentials(String, String)}：metadata 含非空
 * {@code username} 时取其为用户名、{@code encrypted_value} 整体当密码；否则回退到按首个冒号
 * 切分 {@code encrypted_value}。两种形态互不冲突。</p>
 *
 * @author lx
 * @since 2026-09-26 02:10:00
 */
public final class RegistryAuthSupport {

    /** 登录前把密码文件喂给 docker 的命令片段。 */
    private static final String PASSWORD_STDIN = " --password-stdin";

    /** JSON 解析器，仅用于从 metadata_json 取 username，不做其它业务语义解析。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RegistryAuthSupport() {
    }

    /**
     * 解析 REGISTRY 凭据的用户名与密码，兼容两种存储形态（见类注释）。
     *
     * <p>判定优先级：{@code metadataJson} 含非空 {@code username} → 其为用户名、
     * {@code decryptedValue} 整体为密码；否则回退 {@link #parseUserPassword(String)}。
     * 这样无论是「密码在密文 + 用户名在 metadata」还是「用户名:密码 整体在密文」都能正确解析，
     * 且对两处消费方透明。</p>
     *
     * @param decryptedValue 解密后的凭据明文（REGISTRY 形态下通常为密码本身）
     * @param metadataJson   凭据的 metadata_json（可能含 username），允许为 null
     * @return 长度为 2 的数组，[0] 用户名，[1] 密码
     */
    public static String[] resolveRegistryCredentials(String decryptedValue, String metadataJson) {
        String usernameFromMeta = extractUsername(metadataJson);
        if (StringUtils.hasText(usernameFromMeta)) {
            return new String[]{usernameFromMeta, decryptedValue == null ? "" : decryptedValue};
        }
        return parseUserPassword(decryptedValue);
    }

    /**
     * 从 metadata_json 提取 username 字段。
     *
     * <p>解析失败（非法 JSON / 字段缺失 / 非字符串）一律返回空串，交由调用方回退到
     * {@link #parseUserPassword(String)}，绝不抛异常中断登录流程。</p>
     *
     * @param metadataJson 凭据元数据 JSON 文本
     * @return username，缺失或解析失败时为空串
     */
    private static String extractUsername(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return "";
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(metadataJson);
            JsonNode username = node.get("username");
            if (username != null && username.isTextual() && StringUtils.hasText(username.asText())) {
                return username.asText();
            }
        } catch (Exception ignored) {
            // 非法 JSON 或非预期结构：静默回退，不影响主流程
        }
        return "";
    }

    /**
     * 按<b>第一个</b>冒号切分 {@code 用户名:密码}。
     *
     * <p>只按第一个冒号切分：密码本身可能含冒号，按最后一个切会把用户名与密码都切错。
     * 无冒号时视为「只填了用户名」，密码为空——这种凭据注定登录失败，但解析不做语义判断，
     * 让 docker 报出真实错误比在这里猜更可靠。本方法作为
     * {@link #resolveRegistryCredentials(String, String)} 的回退分支保留。</p>
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
     * <p>密码不进入命令行，只以文件内容的形式通过 stdin 传给 docker，因此该命令<b>可以</b>
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
