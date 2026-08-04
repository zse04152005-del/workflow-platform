package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.payload.PayloadDigests;
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
        String digest = PayloadDigests.sha256(payload);
        String reference =
                payload.length <= inlineThresholdBytes
                        ? "inline:sha256:" + digest
                        : "object:sha256:" + digest;
        return new PayloadReference(reference, digest, payload.length, retentionClass);
    }

    public void verify(byte[] payload, PayloadReference reference) {
        if (!PayloadDigests.sha256(payload).equals(reference.digest())) {
            throw new IllegalArgumentException("payload digest mismatch");
        }
    }

    public boolean shouldInline(byte[] payload) {
        return Objects.requireNonNull(payload, "payload").length <= inlineThresholdBytes;
    }

    public record PayloadReference(
            String reference, String digest, long sizeBytes, String retentionClass) {}
}
