package com.lx.deploy.common.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256 密钥生成工具。
 *
 * <p>该类提供独立的 main 方法，可直接运行生成一把随机 AES-256 密钥。
 * 输出结果已经是 Base64 编码格式，可以直接配置到 {@code LIGHT_DEPLOY_AES_KEY} 环境变量。</p>
 */
public final class AesKeyGenerator {

    /** AES-256 密钥长度，单位为字节。 */
    private static final int KEY_LENGTH = 32;

    /** 安全随机数生成器，使用操作系统提供的安全熵源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesKeyGenerator() {
        // 工具类不允许实例化。
    }

    /**
     * 生成一把 Base64 编码的 AES-256 密钥。
     *
     * @return Base64 编码的 32 字节随机密钥
     */
    public static String generateKey() {
        // 使用安全随机数填充 256 位密钥，避免使用可预测的普通随机数。
        byte[] key = new byte[KEY_LENGTH];
        RANDOM.nextBytes(key);
        // 使用标准 Base64，确保结果可直接被 CredentialCrypto 解码。
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 命令行入口，直接打印一把新的 AES-256 密钥。
     *
     * @param args 不使用命令行参数
     */
    public static void main(String[] args) {
        System.out.println(generateKey());
    }
}
