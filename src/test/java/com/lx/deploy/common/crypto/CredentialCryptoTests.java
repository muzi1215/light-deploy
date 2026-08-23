package com.lx.deploy.common.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java AES-GCM 公共凭据加解密组件测试。 */
class CredentialCryptoTests {

    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** 验证凭据经过 AES-GCM 加密后可以使用同一把密钥完整解密。 */
    @Test
    void shouldEncryptAndDecryptCredential() {
        CredentialCrypto crypto = new CredentialCrypto(TEST_KEY);
        String plaintext = "light-deploy凭据";
        String ciphertext = crypto.encrypt(plaintext);
        assertTrue(ciphertext.length() > 0);
        assertEquals(plaintext, crypto.decrypt(ciphertext));
    }

    /** 验证不同密钥不能解密已有密文。 */
    @Test
    void shouldRejectCiphertextWhenKeyDoesNotMatch() {
        CredentialCrypto source = new CredentialCrypto(TEST_KEY);
        CredentialCrypto target = new CredentialCrypto("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo0NTY3ODk=");
        String ciphertext = source.encrypt("secret");
        assertThrows(IllegalArgumentException.class, () -> target.decrypt(ciphertext));
    }
}
