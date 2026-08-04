package com.workflowplatform.payload;

import java.time.Instant;
import java.util.UUID;

/** Test/backward-compatible fallback that permits inline flows but rejects object references. */
public final class UnavailableTenantPayloadStore implements TenantPayloadStore {

    @Override
    public StoredPayload put(
            UUID tenantId,
            byte[] payload,
            String contentType,
            String retentionClass,
            Instant storedAt) {
        throw unavailable();
    }

    @Override
    public byte[] read(UUID tenantId, String reference, String expectedDigest) {
        throw unavailable();
    }

    @Override
    public void archive(
            UUID tenantId, String reference, String expectedDigest, Instant archivedAt) {
        throw unavailable();
    }

    @Override
    public void deleteExpired(
            UUID tenantId, String reference, String expectedDigest, Instant deletedAt) {
        throw unavailable();
    }

    @Override
    public Lifecycle lifecycle(UUID tenantId, String reference, String expectedDigest) {
        throw unavailable();
    }

    private static PayloadStorageException unavailable() {
        return new PayloadStorageException(
                PayloadStorageException.Reason.BACKEND, "object payload storage is not configured");
    }
}
