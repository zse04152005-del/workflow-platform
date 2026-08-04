package com.workflowplatform.controlplane.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Development Secret provider using a caller-supplied AES key and random nonce. */
public final class AesGcmSecretProvider implements SecretProvider {

    private static final int TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private final SecretKey key;
    private final SecureRandom random;

    public AesGcmSecretProvider(SecretKey encryptionKey, SecureRandom secureRandom) {
        key = Objects.requireNonNull(encryptionKey, "encryptionKey");
        random = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    @Override
    public EncryptedSecret encrypt(String reference, byte[] plaintext) {
        Objects.requireNonNull(reference, "reference");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(reference, nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("secret encryption failed", exception);
        }
    }

    @Override
    public byte[] resolve(EncryptedSecret secret) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, secret.nonce()));
            cipher.updateAAD(secret.reference().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(secret.ciphertext());
        } catch (GeneralSecurityException exception) {
            throw new SecurityException("secret resolution failed", exception);
        }
    }
}
