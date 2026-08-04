package com.workflowplatform.controlplane.security;

/** Opaque secret reference provider; callers never persist resolved values. */
public interface SecretProvider {

    EncryptedSecret encrypt(String reference, byte[] plaintext);

    byte[] resolve(EncryptedSecret secret);

    record EncryptedSecret(String reference, byte[] nonce, byte[] ciphertext) {
        public EncryptedSecret {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
