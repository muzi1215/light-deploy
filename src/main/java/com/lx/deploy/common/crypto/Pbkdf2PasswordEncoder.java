package com.lx.deploy.common.crypto;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2 密码哈希工具。
 *
 * <p>过渡期本地用户表（user）的密码哈希实现。使用 JDK 内置的
 * {@code PBKDF2WithHmacSHA256}，不引入任何第三方安全依赖，规避离线构建（mvn -o）加包风险。
 * 哈希结果存储格式为 {@code PBKDF2$<迭代次数>$<saltBase64>$<hashBase64>}，
 * 自带盐与参数，校验时按存储值重算，无需额外的盐字段。</p>
 *
 * <p>后续接入统一权限中心（auth-cloud）后，本类仅用于迁移期兼容，新账号的凭据由权限中心托管。</p>
 */
public final class Pbkdf2PasswordEncoder {

    /** 算法名，PBKDF2 + HMAC-SHA256。 */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 默认迭代次数，6 万次在 CPU 成本与抗暴力破解之间取平衡。 */
    private static final int DEFAULT_ITERATIONS = 60_000;

    /** 派生密钥长度（比特），256 位与 SHA-256 输出长度一致。 */
    private static final int KEY_LENGTH_BITS = 256;

    /** 随机盐长度（字节）。 */
    private static final int SALT_LENGTH_BYTES = 16;

    private Pbkdf2PasswordEncoder() {
    }

    /**
     * 对明文密码生成带盐哈希。
     *
     * @param rawPassword 明文密码，允许为 null（返回 null，调用方自行判空）
     * @return 存储格式字符串；入参为 null 时返回 null
     */
    public static String encode(String rawPassword) {
        if (rawPassword == null) {
            return null;
        }
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, DEFAULT_ITERATIONS, KEY_LENGTH_BITS);
        return "PBKDF2$" + DEFAULT_ITERATIONS + "$" + b64(salt) + "$" + b64(hash);
    }

    /**
     * 校验明文密码是否匹配已存储的哈希。
     *
     * <p>使用恒定时间比较，避免计时侧信道；存储格式非法或任何异常都返回 false，
     * 不向上抛错（登录失败统一按凭据不匹配处理）。</p>
     *
     * @param rawPassword 待校验明文密码
     * @param stored 数据库中的哈希字符串（PBKDF2$iter$salt$hash）
     * @return 匹配返回 true，否则 false
     */
    public static boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"PBKDF2".equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = b64Decode(parts[2]);
            byte[] expected = b64Decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return slowEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行一次 PBKDF2 派生。
     *
     * @param password 密码字符数组
     * @param salt 盐
     * @param iterations 迭代次数
     * @param keyLengthBits 密钥长度（比特）
     * @return 派生字节
     */
    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            KeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 哈希失败：" + e.getMessage(), e);
        }
    }

    /** 字节数组转 Base64 字符串（无换行）。 */
    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Base64 字符串转字节数组。 */
    private static byte[] b64Decode(String s) {
        return Base64.getDecoder().decode(s);
    }

    /**
     * 恒定时间比较两个字节数组，避免计时攻击。
     *
     * @param a 期望字节
     * @param b 实际字节
     * @return 长度与内容完全一致返回 true
     */
    private static boolean slowEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
