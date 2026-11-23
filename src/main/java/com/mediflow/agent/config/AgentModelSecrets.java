package com.mediflow.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@Profile("mysql")
final class AgentModelSecrets {
    private static final int NONCE_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    AgentModelSecrets(@Value("${mediflow.llm.settings-key:}") String configuredKey) {
        if (configuredKey.isBlank()) {
            key = null;
        } else {
            byte[] bytes = Base64.getDecoder().decode(configuredKey.strip());
            if (bytes.length != 32) throw new IllegalStateException("Invalid Agent settings encryption key length");
            key = new SecretKeySpec(bytes, "AES");
        }
    }

    boolean available() { return key != null; }

    byte[] encrypt(String secret) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = apply(Cipher.ENCRYPT_MODE, nonce, secret.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    String decrypt(byte[] encrypted) {
        byte[] nonce = Arrays.copyOf(encrypted, NONCE_BYTES);
        byte[] content = Arrays.copyOfRange(encrypted, NONCE_BYTES, encrypted.length);
        return new String(apply(Cipher.DECRYPT_MODE, nonce, content), StandardCharsets.UTF_8);
    }

    private byte[] apply(int mode, byte[] nonce, byte[] content) {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务端尚未配置模型密钥加密。");
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            return cipher.doFinal(content);
        } catch (GeneralSecurityException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务端无法读取模型密钥配置。");
        }
    }
}
