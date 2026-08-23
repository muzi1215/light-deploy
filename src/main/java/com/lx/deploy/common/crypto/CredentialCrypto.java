package com.lx.deploy.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 平台凭据公共加解密组件。
 *
 * <p>本组件使用 Java 标准 AES-GCM 算法，不兼容其他语言或历史版本的私有密文格式。
 * 密文由版本字节、随机 IV 和 AES-GCM 输出组成，再使用 URL-safe Base64 编码，便于保存到数据库。</p>
 *
 * <p>密钥只允许通过外部配置注入，不能写入源码、数据库、日志或接口响应。配置值必须是
 * Base64 编码后解码为 32 字节的 AES-256 密钥。</p>
 */
@Component
public class CredentialCrypto {

    private static final byte VERSION = 1;
    private static final int KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 外部注入的 Base64 AES-256 密钥。 */
    private final String configuredKey;

    /**
     * 创建凭据加解密组件。
     *
     * @param configuredKey 外部注入的 Base64 AES-256 密钥
     */
    public CredentialCrypto(@Value("${light-deploy.crypto.aes-key:}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    /**
     * 加密凭据明文。
     *
     * @param plaintext 密码、私钥、Token 等敏感明文
     * @return Base64 编码的 AES-GCM 密文
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("凭据明文不能为 null");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(decodeKey(), "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(1 + IV_LENGTH + encrypted.length);
            payload.put(VERSION).put(iv).put(encrypted);
            return Base64.getUrlEncoder().encodeToString(payload.array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("凭据加密失败", ex);
        }
    }

    /**
     * 解密数据库中保存的凭据密文。
     *
     * @param ciphertext 本组件生成的 Base64 AES-GCM 密文
     * @return 凭据明文，仅应在建立连接等必要操作中短暂使用
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("凭据密文不能为空");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(ciphertext);
            if (payload.length < 1 + IV_LENGTH + 16 || payload[0] != VERSION) {
                throw new IllegalArgumentException("不是有效的凭据密文");
            }
            byte[] iv = Arrays.copyOfRange(payload, 1, 1 + IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(payload, 1 + IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(decodeKey(), "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (AEADBadTagException ex) {
            throw new IllegalArgumentException("凭据密文校验失败，密钥不匹配或密文已被篡改", ex);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("凭据密文解密失败", ex);
        }
    }

    /** 读取并校验外部 AES-256 密钥。 */
    private byte[] decodeKey() {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException("缺少 AES 密钥，请配置 LIGHT_DEPLOY_AES_KEY");
        }
        try {
            byte[] key = Base64.getDecoder().decode(configuredKey.trim());
            if (key.length != KEY_LENGTH) {
                throw new IllegalStateException("AES 密钥必须解码为 32 字节");
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("AES 密钥不是有效的 Base64", ex);
        }
    }
}
