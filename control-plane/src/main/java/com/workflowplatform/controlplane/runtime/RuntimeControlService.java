package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.LengthPrefixedSha256;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.runtime.RuntimeControlStore.RuntimeControlAction;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Validates instance control boundaries before the transactional store is invoked. */
@Service
public final class RuntimeControlService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{8,128}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final Pattern NODE_ID = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");

    private final RuntimeControlStore store;

    public RuntimeControlService(RuntimeControlStore controlStore) {
        store = Objects.requireNonNull(controlStore, "controlStore");
    }

    public RuntimeControlResult control(
            WorkflowRequestContext context,
            UUID instanceId,
            RuntimeControlAction action,
            String reason,
            String fromNodeId,
            String idempotencyKey,
            String traceparent) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(action, "action");
        requireReason(reason);
        if (action != RuntimeControlAction.REPLAY && fromNodeId != null) {
            throw new IllegalArgumentException("fromNodeId is only valid for replay");
        }
        if (fromNodeId != null
                && (fromNodeId.length() > 80 || !NODE_ID.matcher(fromNodeId).matches())) {
            throw new IllegalArgumentException("fromNodeId has an invalid stable format");
        }
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 8 to 128 safe characters");
        }
        String traceId = traceId(traceparent);
        String operationScope = "runtime:control:" + action.wireName() + ":" + instanceId;
        String keyDigest = LengthPrefixedSha256.digest("idempotency-key-v1", idempotencyKey);
        String requestDigest =
                LengthPrefixedSha256.digest(
                        "runtime-control-v1",
                        instanceId.toString(),
                        action.wireName(),
                        reason.trim(),
                        fromNodeId == null ? "<null>" : fromNodeId);
        return store.execute(
                new RuntimeControlStore.RuntimeControlCommand(
                        context,
                        instanceId,
                        action,
                        reason.trim(),
                        fromNodeId,
                        traceId,
                        TimeOrderedUuidV7.generate(),
                        operationScope,
                        keyDigest,
                        requestDigest));
    }

    private static void requireReason(String reason) {
        if (reason == null
                || reason.isBlank()
                || reason.length() > 500
                || reason.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("reason must contain 1 to 500 safe characters");
        }
    }

    private static String traceId(String traceparent) {
        if (traceparent == null) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        if (TRACE_ID.matcher(traceparent).matches()
                && !traceparent.chars().allMatch(character -> character == '0')) {
            return traceparent;
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        if (matcher.matches()
                && !matcher.group(1).chars().allMatch(character -> character == '0')) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Traceparent must contain a non-zero W3C trace id");
    }
}
