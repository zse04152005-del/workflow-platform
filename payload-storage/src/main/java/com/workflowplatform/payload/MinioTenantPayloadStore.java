package com.workflowplatform.payload;

import io.minio.GetObjectArgs;
import io.minio.GetObjectTagsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetObjectTagsArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** MinIO/S3 implementation with tenant-prefixed references and verified immutable metadata. */
public final class MinioTenantPayloadStore implements TenantPayloadStore {

    private static final String PREFIX = "payload/v1/";
    private static final String TENANT_METADATA = "tenant-id";
    private static final String DIGEST_METADATA = "sha256";
    private static final String SIZE_METADATA = "size-bytes";
    private static final String RETENTION_METADATA = "retention-class";
    private static final String ARCHIVE_AT_METADATA = "archive-at";
    private static final String RETAIN_UNTIL_METADATA = "retain-until";
    private static final String LIFECYCLE_TAG = "lifecycle";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAXIMUM_PAYLOAD_BYTES = 64 * 1024 * 1024;

    private final MinioClient client;
    private final String bucket;

    public MinioTenantPayloadStore(
            String endpoint, String accessKey, String secretKey, String configuredBucket) {
        this(
                MinioClient.builder()
                        .endpoint(requireText(endpoint, "object storage endpoint"))
                        .credentials(
                                requireText(accessKey, "object storage access key"),
                                requireText(secretKey, "object storage secret key"))
                        .build(),
                configuredBucket);
    }

    public MinioTenantPayloadStore(MinioClient minioClient, String configuredBucket) {
        client = Objects.requireNonNull(minioClient, "minioClient");
        bucket = requireText(configuredBucket, "object storage bucket");
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("object storage bucket is invalid");
        }
    }

    @Override
    public StoredPayload put(
            UUID tenantId,
            byte[] payload,
            String contentType,
            String retentionClass,
            Instant storedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(storedAt, "storedAt");
        if (payload.length < 1 || payload.length > MAXIMUM_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "object payload size is outside the supported range");
        }
        PayloadRetentionPolicy policy = PayloadRetentionPolicy.parse(retentionClass);
        String digest = PayloadDigests.sha256(payload);
        String object = objectName(tenantId, digest);
        Instant archiveAt = storedAt.plus(policy.archiveAfter());
        Instant retainUntil = storedAt.plus(policy.retainFor());
        Map<String, String> metadata =
                Map.of(
                        TENANT_METADATA,
                        tenantId.toString(),
                        DIGEST_METADATA,
                        digest,
                        SIZE_METADATA,
                        Integer.toString(payload.length),
                        RETENTION_METADATA,
                        policy.externalName(),
                        ARCHIVE_AT_METADATA,
                        archiveAt.toString(),
                        RETAIN_UNTIL_METADATA,
                        retainUntil.toString());
        try {
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(object)
                            .contentType(requireText(contentType, "payload content type"))
                            .userMetadata(metadata)
                            .stream(new ByteArrayInputStream(payload), payload.length, -1)
                            .build());
            return new StoredPayload(
                    reference(object),
                    digest,
                    payload.length,
                    policy.externalName(),
                    archiveAt,
                    retainUntil);
        } catch (MinioException
                | IOException
                | InvalidKeyException
                | NoSuchAlgorithmException exception) {
            throw providerFailure("object payload write failed", exception);
        }
    }

    @Override
    public byte[] read(UUID tenantId, String reference, String expectedDigest) {
        ObjectIdentity identity = authorize(tenantId, reference, expectedDigest);
        try {
            StatObjectResponse stat =
                    client.statObject(
                            StatObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(identity.objectName())
                                    .build());
            ObjectMetadata metadata = verifiedMetadata(identity, stat);
            byte[] payload;
            try (var input =
                    client.getObject(
                            GetObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(identity.objectName())
                                    .build())) {
                payload = input.readAllBytes();
            }
            if (payload.length != metadata.sizeBytes()
                    || !identity.digest().equals(PayloadDigests.sha256(payload))) {
                throw new PayloadStorageException(
                        PayloadStorageException.Reason.INTEGRITY,
                        "object payload content digest mismatch");
            }
            return payload;
        } catch (PayloadStorageException exception) {
            throw exception;
        } catch (MinioException
                | IOException
                | InvalidKeyException
                | NoSuchAlgorithmException exception) {
            throw providerFailure("object payload read failed", exception);
        }
    }

    @Override
    public void archive(
            UUID tenantId, String reference, String expectedDigest, Instant archivedAt) {
        Objects.requireNonNull(archivedAt, "archivedAt");
        ObjectIdentity identity = authorize(tenantId, reference, expectedDigest);
        try {
            ObjectMetadata metadata =
                    verifiedMetadata(
                            identity,
                            client.statObject(
                                    StatObjectArgs.builder()
                                            .bucket(bucket)
                                            .object(identity.objectName())
                                            .build()));
            if (archivedAt.isBefore(metadata.archiveAt())) {
                throw new PayloadStorageException(
                        PayloadStorageException.Reason.RETENTION,
                        "object payload cannot be archived before archive-at");
            }
            client.setObjectTags(
                    SetObjectTagsArgs.builder()
                            .bucket(bucket)
                            .object(identity.objectName())
                            .tags(Map.of(LIFECYCLE_TAG, "archived"))
                            .build());
        } catch (PayloadStorageException exception) {
            throw exception;
        } catch (MinioException
                | IOException
                | InvalidKeyException
                | NoSuchAlgorithmException exception) {
            throw providerFailure("object payload archive failed", exception);
        }
    }

    @Override
    public void deleteExpired(
            UUID tenantId, String reference, String expectedDigest, Instant deletedAt) {
        Objects.requireNonNull(deletedAt, "deletedAt");
        ObjectIdentity identity = authorize(tenantId, reference, expectedDigest);
        try {
            ObjectMetadata metadata =
                    verifiedMetadata(
                            identity,
                            client.statObject(
                                    StatObjectArgs.builder()
                                            .bucket(bucket)
                                            .object(identity.objectName())
                                            .build()));
            if (deletedAt.isBefore(metadata.retainUntil())) {
                throw new PayloadStorageException(
                        PayloadStorageException.Reason.RETENTION,
                        "object payload cannot be deleted before retain-until");
            }
            client.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(identity.objectName())
                            .build());
        } catch (PayloadStorageException exception) {
            throw exception;
        } catch (MinioException
                | IOException
                | InvalidKeyException
                | NoSuchAlgorithmException exception) {
            throw providerFailure("object payload deletion failed", exception);
        }
    }

    @Override
    public Lifecycle lifecycle(UUID tenantId, String reference, String expectedDigest) {
        ObjectIdentity identity = authorize(tenantId, reference, expectedDigest);
        try {
            verifiedMetadata(
                    identity,
                    client.statObject(
                            StatObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(identity.objectName())
                                    .build()));
            Map<String, String> tags =
                    client.getObjectTags(
                                    GetObjectTagsArgs.builder()
                                            .bucket(bucket)
                                            .object(identity.objectName())
                                            .build())
                            .get();
            return "archived".equals(tags.get(LIFECYCLE_TAG)) ? Lifecycle.ARCHIVED : Lifecycle.HOT;
        } catch (PayloadStorageException exception) {
            throw exception;
        } catch (MinioException
                | IOException
                | InvalidKeyException
                | NoSuchAlgorithmException exception) {
            throw providerFailure("object payload lifecycle read failed", exception);
        }
    }

    private ObjectIdentity authorize(UUID tenantId, String reference, String expectedDigest) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (expectedDigest == null || !SHA_256.matcher(expectedDigest).matches()) {
            throw invalidReference("expected payload digest is invalid");
        }
        final URI uri;
        try {
            uri = URI.create(requireText(reference, "payload reference"));
        } catch (IllegalArgumentException exception) {
            throw new PayloadStorageException(
                    PayloadStorageException.Reason.INVALID_REFERENCE,
                    "payload reference is invalid",
                    exception);
        }
        String object = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        String[] segments = object.split("/", -1);
        if (!"s3".equals(uri.getScheme())
                || !bucket.equals(uri.getHost())
                || uri.getQuery() != null
                || uri.getFragment() != null
                || segments.length != 4
                || !"payload".equals(segments[0])
                || !"v1".equals(segments[1])
                || !SHA_256.matcher(segments[3]).matches()) {
            throw invalidReference("payload reference is outside the governed namespace");
        }
        if (!tenantId.toString().equals(segments[2])) {
            throw new PayloadStorageException(
                    PayloadStorageException.Reason.ACCESS_DENIED,
                    "payload reference does not belong to the requesting tenant");
        }
        if (!expectedDigest.equals(segments[3])) {
            throw new PayloadStorageException(
                    PayloadStorageException.Reason.INTEGRITY,
                    "payload reference digest does not match the expected digest");
        }
        return new ObjectIdentity(object, tenantId, expectedDigest);
    }

    private ObjectMetadata verifiedMetadata(ObjectIdentity identity, StatObjectResponse stat) {
        Map<String, String> metadata = normalizedMetadata(stat.userMetadata());
        if (!identity.tenantId().toString().equals(metadata.get(TENANT_METADATA))) {
            throw new PayloadStorageException(
                    PayloadStorageException.Reason.ACCESS_DENIED,
                    "object payload tenant metadata mismatch");
        }
        if (!identity.digest().equals(metadata.get(DIGEST_METADATA))) {
            throw new PayloadStorageException(
                    PayloadStorageException.Reason.INTEGRITY,
                    "object payload digest metadata mismatch");
        }
        try {
            long size = Long.parseLong(metadata.getOrDefault(SIZE_METADATA, "-1"));
            Instant archiveAt = Instant.parse(metadata.getOrDefault(ARCHIVE_AT_METADATA, ""));
            Instant retainUntil = Instant.parse(metadata.getOrDefault(RETAIN_UNTIL_METADATA, ""));
            PayloadRetentionPolicy.parse(metadata.get(RETENTION_METADATA));
            if (size != stat.size() || size < 1 || !archiveAt.isBefore(retainUntil)) {
                throw new IllegalArgumentException("object payload metadata boundary is invalid");
            }
            return new ObjectMetadata(size, archiveAt, retainUntil);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new PayloadStorageException(
                    PayloadStorageException.Reason.INTEGRITY,
                    "object payload lifecycle metadata is invalid",
                    exception);
        }
    }

    private static Map<String, String> normalizedMetadata(Map<String, String> source) {
        Map<String, String> result = new HashMap<>();
        source.forEach(
                (key, value) -> {
                    String normalized = key.toLowerCase(Locale.ROOT);
                    int prefix = normalized.lastIndexOf("x-amz-meta-");
                    result.put(prefix < 0 ? normalized : normalized.substring(prefix + 11), value);
                });
        return Map.copyOf(result);
    }

    private String reference(String object) {
        return "s3://" + bucket + "/" + object;
    }

    private static String objectName(UUID tenantId, String digest) {
        return PREFIX + tenantId + "/" + digest;
    }

    private static PayloadStorageException invalidReference(String message) {
        return new PayloadStorageException(
                PayloadStorageException.Reason.INVALID_REFERENCE, message);
    }

    private static PayloadStorageException providerFailure(String message, Exception exception) {
        if (exception instanceof ErrorResponseException provider) {
            String code = provider.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return new PayloadStorageException(
                        PayloadStorageException.Reason.NOT_FOUND, message, exception);
            }
            if ("AccessDenied".equals(code) || "InvalidAccessKeyId".equals(code)) {
                return new PayloadStorageException(
                        PayloadStorageException.Reason.ACCESS_DENIED, message, exception);
            }
        }
        return new PayloadStorageException(
                PayloadStorageException.Reason.BACKEND, message, exception);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record ObjectIdentity(String objectName, UUID tenantId, String digest) {}

    private record ObjectMetadata(long sizeBytes, Instant archiveAt, Instant retainUntil) {}
}
