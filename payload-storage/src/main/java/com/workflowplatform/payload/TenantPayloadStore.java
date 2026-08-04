package com.workflowplatform.payload;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Tenant-authorized object payload port with integrity and lifecycle enforcement. */
public interface TenantPayloadStore {

    StoredPayload put(
            UUID tenantId,
            byte[] payload,
            String contentType,
            String retentionClass,
            Instant storedAt);

    byte[] read(UUID tenantId, String reference, String expectedDigest);

    void archive(UUID tenantId, String reference, String expectedDigest, Instant archivedAt);

    void deleteExpired(UUID tenantId, String reference, String expectedDigest, Instant deletedAt);

    Lifecycle lifecycle(UUID tenantId, String reference, String expectedDigest);

    record StoredPayload(
            String reference,
            String digest,
            long sizeBytes,
            String retentionClass,
            Instant archiveAt,
            Instant retainUntil) {

        public StoredPayload {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(retentionClass, "retentionClass");
            Objects.requireNonNull(archiveAt, "archiveAt");
            Objects.requireNonNull(retainUntil, "retainUntil");
        }
    }

    enum Lifecycle {
        HOT,
        ARCHIVED
    }
}
