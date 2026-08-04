package com.workflowplatform.controlplane.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Inline/object-storage payload policy with digest verification and retention class. */
public final class PayloadPolicy {

    private static final int MINIMUM_THRESHOLD_BYTES = 1;
    private final int inlineThresholdBytes;

    public PayloadPolicy(int thresholdBytes) {
        if (thresholdBytes < MINIMUM_THRESHOLD_BYTES) {
            throw new IllegalArgumentException("payload threshold must be positive");
        }
        inlineThresholdBytes = thresholdBytes;
    }

    public PayloadReference reference(byte[] payload, String retentionClass) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(retentionClass, "retentionClass");
        String digest = digest(payload);
        String reference =
                payload.length <= inlineThresholdBytes
                        ? "inline:sha256:" + digest
                        : "object:sha256:" + digest;
        return new PayloadReference(reference, digest, payload.length, retentionClass);
    }

    public void verify(byte[] payload, PayloadReference reference) {
        if (!digest(payload).equals(reference.digest())) {
            throw new IllegalArgumentException("payload digest mismatch");
        }
    }

    private static String digest(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record PayloadReference(
            String reference, String digest, long sizeBytes, String retentionClass) {}
}
