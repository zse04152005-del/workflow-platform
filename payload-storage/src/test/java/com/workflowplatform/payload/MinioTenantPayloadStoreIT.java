package com.workflowplatform.payload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class MinioTenantPayloadStoreIT {

    private static final String MINIO_IMAGE =
            "minio/minio:RELEASE.2025-09-07T16-13-09Z@sha256:"
                    + "14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e";
    private static final String ACCESS_KEY = "workflow_g5_test";
    private static final String SECRET_KEY = UUID.randomUUID().toString();
    private static final String BUCKET = "workflow-g5-payloads";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final UUID TENANT_ONE = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c901");
    private static final UUID TENANT_TWO = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c902");
    private static final Instant STORED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .withLabel("com.workflowplatform.test-suite", "G5-12")
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static MinioClient client;
    private static MinioTenantPayloadStore store;

    @BeforeAll
    static void createPrivateBucket() throws Exception {
        client =
                MinioClient.builder()
                        .endpoint(endpoint())
                        .credentials(ACCESS_KEY, SECRET_KEY)
                        .build();
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        store = new MinioTenantPayloadStore(client, BUCKET);
    }

    @Test
    void writesReadsAndRejectsCrossTenantDigestTamperingAndMissingObjects() throws Exception {
        byte[] payload =
                "{\"orderId\":\"order-1001\",\"amount\":100}".getBytes(StandardCharsets.UTF_8);
        TenantPayloadStore.StoredPayload stored =
                store.put(TENANT_ONE, payload, JSON_CONTENT_TYPE, "runtime-30d", STORED_AT);

        assertTrue(stored.reference().startsWith("s3://" + BUCKET + "/payload/v1/" + TENANT_ONE));
        assertEquals(PayloadDigests.sha256(payload), stored.digest());
        assertArrayEquals(payload, store.read(TENANT_ONE, stored.reference(), stored.digest()));
        assertReason(
                PayloadStorageException.Reason.ACCESS_DENIED,
                () -> store.read(TENANT_TWO, stored.reference(), stored.digest()));
        assertReason(
                PayloadStorageException.Reason.INTEGRITY,
                () -> store.read(TENANT_ONE, stored.reference(), "0".repeat(64)));

        byte[] tampered = payload.clone();
        tampered[tampered.length - 2] ^= 1;
        client.putObject(
                PutObjectArgs.builder()
                        .bucket(BUCKET)
                        .object(objectName(stored.reference()))
                        .contentType(JSON_CONTENT_TYPE)
                        .userMetadata(
                                Map.of(
                                        "tenant-id",
                                        TENANT_ONE.toString(),
                                        "sha256",
                                        stored.digest(),
                                        "size-bytes",
                                        Long.toString(stored.sizeBytes()),
                                        "retention-class",
                                        stored.retentionClass(),
                                        "archive-at",
                                        stored.archiveAt().toString(),
                                        "retain-until",
                                        stored.retainUntil().toString()))
                        .stream(new ByteArrayInputStream(tampered), tampered.length, -1)
                        .build());
        assertReason(
                PayloadStorageException.Reason.INTEGRITY,
                () -> store.read(TENANT_ONE, stored.reference(), stored.digest()));
    }

    @Test
    void enforcesArchiveAndDeleteBoundariesAgainstRealObjectTagsAndRemoval() {
        byte[] payload = "{\"payload\":\"lifecycle-boundary\"}".getBytes(StandardCharsets.UTF_8);
        TenantPayloadStore.StoredPayload stored =
                store.put(TENANT_ONE, payload, JSON_CONTENT_TYPE, "runtime-30d", STORED_AT);

        assertEquals(
                TenantPayloadStore.Lifecycle.HOT,
                store.lifecycle(TENANT_ONE, stored.reference(), stored.digest()));
        assertReason(
                PayloadStorageException.Reason.RETENTION,
                () ->
                        store.archive(
                                TENANT_ONE,
                                stored.reference(),
                                stored.digest(),
                                stored.archiveAt().minusSeconds(1)));
        store.archive(TENANT_ONE, stored.reference(), stored.digest(), stored.archiveAt());
        assertEquals(
                TenantPayloadStore.Lifecycle.ARCHIVED,
                store.lifecycle(TENANT_ONE, stored.reference(), stored.digest()));
        assertArrayEquals(payload, store.read(TENANT_ONE, stored.reference(), stored.digest()));

        assertReason(
                PayloadStorageException.Reason.RETENTION,
                () ->
                        store.deleteExpired(
                                TENANT_ONE,
                                stored.reference(),
                                stored.digest(),
                                stored.retainUntil().minusSeconds(1)));
        store.deleteExpired(TENANT_ONE, stored.reference(), stored.digest(), stored.retainUntil());
        assertReason(
                PayloadStorageException.Reason.NOT_FOUND,
                () -> store.read(TENANT_ONE, stored.reference(), stored.digest()));
    }

    @Test
    void rejectsInvalidConstructionSizeRetentionDigestAndReferenceBoundaries() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MinioTenantPayloadStore(client, "INVALID_BUCKET"));
        MinioTenantPayloadStore configured =
                new MinioTenantPayloadStore(endpoint(), ACCESS_KEY, SECRET_KEY, BUCKET);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        configured.put(
                                TENANT_ONE,
                                new byte[0],
                                JSON_CONTENT_TYPE,
                                "runtime-30d",
                                STORED_AT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        configured.put(
                                TENANT_ONE,
                                new byte[] {1},
                                JSON_CONTENT_TYPE,
                                "unbounded",
                                STORED_AT));
        assertReason(
                PayloadStorageException.Reason.INVALID_REFERENCE,
                () -> configured.read(TENANT_ONE, "s3:opaque", null));
        assertReason(
                PayloadStorageException.Reason.INVALID_REFERENCE,
                () -> configured.read(TENANT_ONE, "s3:opaque", "bad"));
        assertReason(
                PayloadStorageException.Reason.INVALID_REFERENCE,
                () -> configured.read(TENANT_ONE, "not a valid uri", "0".repeat(64)));
        assertReason(
                PayloadStorageException.Reason.INVALID_REFERENCE,
                () ->
                        configured.read(
                                TENANT_ONE,
                                "https://"
                                        + BUCKET
                                        + "/payload/v1/"
                                        + TENANT_ONE
                                        + "/"
                                        + "0".repeat(64),
                                "0".repeat(64)));
    }

    private static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static String objectName(String reference) {
        return URI.create(reference).getPath().substring(1);
    }

    private static void assertReason(
            PayloadStorageException.Reason expected,
            org.junit.jupiter.api.function.Executable action) {
        PayloadStorageException exception = assertThrows(PayloadStorageException.class, action);
        assertEquals(expected, exception.reason());
    }
}
