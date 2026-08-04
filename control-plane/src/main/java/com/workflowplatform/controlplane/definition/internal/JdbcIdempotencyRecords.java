package com.workflowplatform.controlplane.definition.internal;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared row-locking implementation of the 24-hour API idempotency protocol. */
final class JdbcIdempotencyRecords {

    private static final int EXPECTED_ROW_COUNT = 1;

    private final JdbcTemplate jdbc;

    JdbcIdempotencyRecords(JdbcTemplate jdbcTemplate) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    Claim claim(
            WorkflowRequestContext context,
            UUID recordId,
            String operationScope,
            String keyDigest,
            String requestDigest) {
        int inserted =
                jdbc.update(
                        "INSERT INTO public.idempotency_record "
                                + "(id, tenant_id, created_by, updated_by, operation_scope, "
                                + "key_digest, request_digest, status, expires_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PROCESSING', "
                                + "transaction_timestamp() + interval '24 hours') "
                                + "ON CONFLICT (tenant_id, operation_scope, key_digest) DO NOTHING",
                        recordId,
                        context.tenantId(),
                        context.actorId(),
                        context.actorId(),
                        operationScope,
                        keyDigest,
                        requestDigest);
        Row row = lock(operationScope, keyDigest);
        if (inserted == EXPECTED_ROW_COUNT) {
            return new Claim(true, row);
        }
        if (row.expired()) {
            resetExpired(context, operationScope, keyDigest, requestDigest);
            return new Claim(true, row);
        }
        if (!row.requestDigest().equals(requestDigest) || !"COMPLETED".equals(row.status())) {
            throw new ConflictException();
        }
        return new Claim(false, row);
    }

    void complete(
            String operationScope,
            String keyDigest,
            int responseStatus,
            String responseReference,
            String responseDigest) {
        int changed =
                jdbc.update(
                        "UPDATE public.idempotency_record SET status = 'COMPLETED', "
                                + "response_status = ?, response_ref = ?, response_digest = ?, "
                                + "updated_at = transaction_timestamp(), version = version + 1 "
                                + "WHERE operation_scope = ? AND key_digest = ? "
                                + "AND status = 'PROCESSING'",
                        responseStatus,
                        responseReference,
                        responseDigest,
                        operationScope,
                        keyDigest);
        if (changed != EXPECTED_ROW_COUNT) {
            throw new IllegalStateException(
                    "Idempotency completion did not update exactly one row");
        }
    }

    private Row lock(String operationScope, String keyDigest) {
        List<Row> rows =
                jdbc.query(
                        "SELECT request_digest, status, response_status, response_ref, "
                                + "response_digest, expires_at <= transaction_timestamp() AS expired "
                                + "FROM public.idempotency_record WHERE operation_scope = ? "
                                + "AND key_digest = ? FOR UPDATE",
                        (result, rowNumber) ->
                                new Row(
                                        result.getString("request_digest"),
                                        result.getString("status"),
                                        (Integer) result.getObject("response_status"),
                                        result.getString("response_ref"),
                                        result.getString("response_digest"),
                                        result.getBoolean("expired")),
                        operationScope,
                        keyDigest);
        if (rows.size() != EXPECTED_ROW_COUNT) {
            throw new IllegalStateException("Idempotency claim did not resolve exactly one row");
        }
        return rows.getFirst();
    }

    private void resetExpired(
            WorkflowRequestContext context,
            String operationScope,
            String keyDigest,
            String requestDigest) {
        int reset =
                jdbc.update(
                        "UPDATE public.idempotency_record SET request_digest = ?, "
                                + "status = 'PROCESSING', response_status = NULL, "
                                + "response_ref = NULL, response_digest = NULL, "
                                + "expires_at = transaction_timestamp() + interval '24 hours', "
                                + "updated_at = transaction_timestamp(), updated_by = ?, "
                                + "version = version + 1 WHERE operation_scope = ? "
                                + "AND key_digest = ?",
                        requestDigest,
                        context.actorId(),
                        operationScope,
                        keyDigest);
        if (reset != EXPECTED_ROW_COUNT) {
            throw new IllegalStateException("Expired idempotency record was not reset");
        }
    }

    record Claim(boolean owner, Row row) {}

    record Row(
            String requestDigest,
            String status,
            Integer responseStatus,
            String responseReference,
            String responseDigest,
            boolean expired) {}

    static final class ConflictException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
