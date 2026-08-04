package com.workflowplatform.controlplane.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.LengthPrefixedSha256;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import com.workflowplatform.payload.PayloadDigests;
import com.workflowplatform.payload.TenantPayloadStore;
import com.workflowplatform.payload.UnavailableTenantPayloadStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Validates trigger boundaries and delegates to the transactional runtime store. */
@Service
public final class RuntimeTriggerService {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern BUSINESS_KEY = Pattern.compile("[\\p{L}\\p{N}._:/-]{1,200}");
    private static final String TRIGGER_TYPE_MANUAL = "manual";
    private static final String TRIGGER_TYPE_HTTP = "http";
    private static final int DEFAULT_INLINE_THRESHOLD_BYTES = 65_536;
    private static final int MAXIMUM_INPUT_BYTES = 16 * 1024 * 1024;
    private static final String RUNTIME_RETENTION_CLASS = "runtime-30d";

    private final RuntimeTriggerStore store;
    private final ObjectMapper objectMapper;
    private final PayloadPolicy payloadPolicy;
    private final TenantPayloadStore payloadStore;
    private final Clock clock;

    public RuntimeTriggerService(RuntimeTriggerStore triggerStore, ObjectMapper jsonMapper) {
        this(
                triggerStore,
                jsonMapper,
                new PayloadPolicy(DEFAULT_INLINE_THRESHOLD_BYTES),
                new UnavailableTenantPayloadStore(),
                Clock.systemUTC());
    }

    @Autowired
    RuntimeTriggerService(
            RuntimeTriggerStore triggerStore,
            ObjectMapper jsonMapper,
            TenantPayloadStore tenantPayloadStore,
            @Value("${workflow.object-storage.inline-threshold-bytes:65536}")
                    int inlineThresholdBytes) {
        this(
                triggerStore,
                jsonMapper,
                new PayloadPolicy(inlineThresholdBytes),
                tenantPayloadStore,
                Clock.systemUTC());
    }

    public RuntimeTriggerService(
            RuntimeTriggerStore triggerStore,
            ObjectMapper jsonMapper,
            PayloadPolicy configuredPayloadPolicy,
            TenantPayloadStore tenantPayloadStore,
            Clock configuredClock) {
        store = Objects.requireNonNull(triggerStore, "triggerStore");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        payloadPolicy = Objects.requireNonNull(configuredPayloadPolicy, "configuredPayloadPolicy");
        payloadStore = Objects.requireNonNull(tenantPayloadStore, "tenantPayloadStore");
        clock = Objects.requireNonNull(configuredClock, "configuredClock");
    }

    public RuntimeTriggerResult trigger(
            WorkflowRequestContext context,
            UUID workflowId,
            String triggerType,
            JsonNode input,
            String businessKey,
            String idempotencyKey,
            String traceId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(workflowId, "workflowId");
        requireTriggerType(triggerType);
        requireInput(input);
        requireBusinessKey(businessKey);
        String effectiveTraceId = traceId == null ? randomTraceId() : traceId;
        if (!TRACE_ID.matcher(effectiveTraceId).matches()
                || effectiveTraceId.chars().allMatch(character -> character == '0')) {
            throw new IllegalArgumentException("traceId must be a non-zero 32 character hex value");
        }
        WorkflowDraftIdempotency idempotency = idempotency(idempotencyKey);
        String canonicalInput = canonicalJson(input);
        byte[] inputBytes = canonicalInput.getBytes(StandardCharsets.UTF_8);
        String requestDigest =
                digest(
                        "runtime-trigger-v1",
                        workflowId.toString(),
                        triggerType,
                        nullable(businessKey),
                        canonicalInput,
                        effectiveTraceId);
        PayloadPlacement placement = placePayload(context, inputBytes);
        return store.trigger(
                new RuntimeTriggerStore.RuntimeTriggerCommand(
                        context,
                        workflowId,
                        triggerType,
                        input,
                        businessKey,
                        effectiveTraceId,
                        TimeOrderedUuidV7.generate(),
                        "runtime:trigger:" + workflowId,
                        idempotency.keyDigest(),
                        requestDigest,
                        placement.reference(),
                        placement.digest()));
    }

    public RuntimeTriggerResult manual(
            WorkflowRequestContext context,
            UUID workflowId,
            JsonNode input,
            String businessKey,
            String idempotencyKey,
            String traceId) {
        return trigger(
                context,
                workflowId,
                TRIGGER_TYPE_MANUAL,
                input,
                businessKey,
                idempotencyKey,
                traceId);
    }

    public RuntimeTriggerResult http(
            WorkflowRequestContext context,
            UUID workflowId,
            JsonNode input,
            String businessKey,
            String idempotencyKey,
            String traceId) {
        return trigger(
                context,
                workflowId,
                TRIGGER_TYPE_HTTP,
                input,
                businessKey,
                idempotencyKey,
                traceId);
    }

    private static void requireTriggerType(String triggerType) {
        if (!TRIGGER_TYPE_MANUAL.equals(triggerType) && !TRIGGER_TYPE_HTTP.equals(triggerType)) {
            throw new IllegalArgumentException("triggerType must be manual or http");
        }
    }

    private static void requireInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("trigger input must be a JSON object");
        }
        if (input.toString().getBytes(StandardCharsets.UTF_8).length > MAXIMUM_INPUT_BYTES) {
            throw new IllegalArgumentException("trigger input exceeds 16 MiB");
        }
    }

    private PayloadPlacement placePayload(WorkflowRequestContext context, byte[] inputBytes) {
        String digest = PayloadDigests.sha256(inputBytes);
        if (payloadPolicy.shouldInline(inputBytes)) {
            return new PayloadPlacement(null, digest);
        }
        TenantPayloadStore.StoredPayload stored =
                payloadStore.put(
                        context.tenantId(),
                        inputBytes,
                        "application/json",
                        RUNTIME_RETENTION_CLASS,
                        clock.instant());
        if (!digest.equals(stored.digest()) || stored.sizeBytes() != inputBytes.length) {
            throw new IllegalStateException("object payload store returned inconsistent metadata");
        }
        return new PayloadPlacement(stored.reference(), stored.digest());
    }

    private static void requireBusinessKey(String businessKey) {
        if (businessKey != null && !BUSINESS_KEY.matcher(businessKey).matches()) {
            throw new IllegalArgumentException(
                    "businessKey contains unsupported characters or length");
        }
    }

    private WorkflowDraftIdempotency idempotency(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._~-]{8,128}")) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 8 to 128 safe characters");
        }
        return new WorkflowDraftIdempotency(digest("idempotency-key-v1", key));
    }

    private String canonicalJson(JsonNode input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("trigger input cannot be serialized", exception);
        }
    }

    private static String randomTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String nullable(String value) {
        return value == null ? "<null>" : value;
    }

    static String digest(String... values) {
        return LengthPrefixedSha256.digest(values);
    }

    private record WorkflowDraftIdempotency(String keyDigest) {}

    private record PayloadPlacement(String reference, String digest) {}
}
