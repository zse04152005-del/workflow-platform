\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'tenant context must fail closed before G2-07 tests';
    END IF;
    IF public.app_is_platform_admin() THEN
        RAISE EXCEPTION 'ordinary application role became platform administrator';
    END IF;
    IF EXISTS (SELECT FROM public.outbox_event)
       OR EXISTS (SELECT FROM public.processed_event)
       OR EXISTS (SELECT FROM public.idempotency_record)
       OR EXISTS (SELECT FROM public.dead_letter)
       OR EXISTS (SELECT FROM public.worker_lease) THEN
        RAISE EXCEPTION 'G2-07 rows are visible without tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');

INSERT INTO public.node_instance (
    id, tenant_id, created_by, updated_by, workflow_instance_id,
    node_id, node_type, status
) VALUES (
    '019fb1d7-d968-8790-8790-000000000090',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:runtime-a', 'oidc:runtime-a',
    '019fb1d7-d968-8601-8601-000000000001',
    'dead_letter_mismatch_probe', 'start', 'PENDING'
);

INSERT INTO public.outbox_event (
    id, tenant_id, created_by, aggregate_type, aggregate_id, event_type,
    schema_version, partition_key, payload, payload_digest, occurred_at,
    trace_context, next_publish_at
) VALUES (
    '019fb1d7-d968-9001-9001-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'system:runtime', 'workflow_instance',
    '019fb1d7-d968-8601-8601-000000000001',
    'workflow.instance.started', 1,
    '019fb1d7-d968-8601-8601-000000000001',
    '{"instanceId":"019fb1d7-d968-8601-8601-000000000001"}'::jsonb,
    repeat('a', 64), transaction_timestamp(),
    '00-11111111111111111111111111111111-2222222222222222-01',
    transaction_timestamp()
);
UPDATE public.outbox_event
   SET publisher_id = 'publisher-a-01',
       publish_lease_until = transaction_timestamp() + interval '1 minute',
       publish_attempts = publish_attempts + 1,
       version = version + 1
 WHERE id = '019fb1d7-d968-9001-9001-000000000001';
UPDATE public.outbox_event
   SET published_at = transaction_timestamp(),
       publisher_id = NULL,
       publish_lease_until = NULL,
       version = version + 1
 WHERE id = '019fb1d7-d968-9001-9001-000000000001';

INSERT INTO public.processed_event (
    id, tenant_id, created_by, consumer_name, event_id, event_type,
    schema_version, processed_at, result_digest
) VALUES (
    '019fb1d7-d968-9101-9101-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'system:runtime-consumer', 'runtime-state-projector',
    '019fb1d7-d968-9001-9001-000000000001',
    'workflow.instance.started', 1, transaction_timestamp(), repeat('b', 64)
);

INSERT INTO public.idempotency_record (
    id, tenant_id, created_by, updated_by, operation_scope,
    key_digest, request_digest, status, expires_at
) VALUES (
    '019fb1d7-d968-9201-9201-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:caller-a', 'oidc:caller-a',
    'POST:/api/v1/workflows/order/instances',
    repeat('c', 64), repeat('d', 64), 'PROCESSING',
    transaction_timestamp() + interval '24 hours'
);
UPDATE public.idempotency_record
   SET status = 'COMPLETED',
       response_status = 201,
       response_ref = 'object://tenant-a/idempotency/response-1',
       response_digest = repeat('e', 64),
       updated_at = transaction_timestamp(),
       updated_by = 'oidc:caller-a',
       version = version + 1
 WHERE id = '019fb1d7-d968-9201-9201-000000000001';

INSERT INTO public.dead_letter (
    id, tenant_id, created_by, updated_by, node_instance_id,
    task_execution_id, category, error_code, error_summary_redacted,
    payload_ref, payload_digest, status
) VALUES (
    '019fb1d7-d968-9301-9301-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'system:runtime', 'system:runtime',
    '019fb1d7-d968-8701-8701-000000000001',
    '019fb1d7-d968-8802-8802-000000000002',
    'DEPENDENCY', 'WORKER_DEPENDENCY_TIMEOUT',
    'Dependency timed out after the final attempt',
    'object://tenant-a/dead-letter/payload-1', repeat('f', 64), 'OPEN'
);
UPDATE public.dead_letter
   SET status = 'RESOLVED',
       disposition_count = disposition_count + 1,
       last_disposition_at = transaction_timestamp(),
       updated_at = transaction_timestamp(),
       updated_by = 'oidc:operator-a',
       version = version + 1
 WHERE id = '019fb1d7-d968-9301-9301-000000000001';

INSERT INTO public.worker_lease (
    id, tenant_id, created_by, updated_by, node_instance_id,
    lease_generation, worker_id, status, acquired_at, expires_at
)
SELECT
    '019fb1d7-d968-9401-9401-000000000001',
    tenant_id, 'system:dispatcher', 'system:dispatcher', id,
    lease_generation, current_worker_id, 'ACTIVE', transaction_timestamp(), lease_expire_at
  FROM public.node_instance
 WHERE id = '019fb1d7-d968-8701-8701-000000000001';

DO $negative$
BEGIN
    BEGIN
        INSERT INTO public.outbox_event (
            id, tenant_id, created_by, aggregate_type, aggregate_id, event_type,
            schema_version, partition_key, payload, payload_ref, payload_digest,
            occurred_at, next_publish_at
        ) VALUES (
            '019fb1d7-d968-9091-9091-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'system:runtime', 'workflow_instance',
            '019fb1d7-d968-8601-8601-000000000001',
            'workflow.invalid', 1, 'invalid', '{}'::jsonb,
            'object://invalid/double-payload', repeat('1', 64),
            transaction_timestamp(), transaction_timestamp()
        );
        RAISE EXCEPTION 'Outbox accepted inline payload and payload_ref together';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.outbox_event
           SET next_publish_at = next_publish_at + interval '1 minute',
               version = version + 1
         WHERE id = '019fb1d7-d968-9001-9001-000000000001';
        RAISE EXCEPTION 'published Outbox event was rewritten';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        INSERT INTO public.processed_event (
            id, tenant_id, created_by, consumer_name, event_id, event_type,
            schema_version, processed_at
        ) VALUES (
            '019fb1d7-d968-9191-9191-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'system:runtime-consumer', 'runtime-state-projector',
            '019fb1d7-d968-9001-9001-000000000001',
            'workflow.instance.started', 1, transaction_timestamp()
        );
        RAISE EXCEPTION 'duplicate consumer/event pair was accepted';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.processed_event
           SET result_digest = repeat('1', 64)
         WHERE id = '019fb1d7-d968-9101-9101-000000000001';
        RAISE EXCEPTION 'application role updated processed-event history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        INSERT INTO public.idempotency_record (
            id, tenant_id, created_by, updated_by, operation_scope,
            key_digest, request_digest, status, response_status,
            response_digest, expires_at
        ) VALUES (
            '019fb1d7-d968-9291-9291-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:caller-a', 'oidc:caller-a', 'terminal-direct',
            repeat('1', 64), repeat('2', 64), 'COMPLETED', 200,
            repeat('3', 64), transaction_timestamp() + interval '1 hour'
        );
        RAISE EXCEPTION 'idempotency record skipped PROCESSING state';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        UPDATE public.idempotency_record
           SET response_status = 202,
               version = version + 1
         WHERE id = '019fb1d7-d968-9201-9201-000000000001';
        RAISE EXCEPTION 'terminal idempotency response was rewritten';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        INSERT INTO public.dead_letter (
            id, tenant_id, created_by, updated_by, node_instance_id,
            task_execution_id, category, error_code,
            error_summary_redacted, status
        ) VALUES (
            '019fb1d7-d968-9391-9391-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'system:runtime', 'system:runtime',
            '019fb1d7-d968-8790-8790-000000000090',
            '019fb1d7-d968-8802-8802-000000000002',
            'DEPENDENCY', 'WORKER_DEPENDENCY_TIMEOUT',
            'Attempt belongs to another node', 'OPEN'
        );
        RAISE EXCEPTION 'dead letter accepted an attempt from a different node';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.dead_letter
           SET error_summary_redacted = 'rewritten terminal failure',
               version = version + 1
         WHERE id = '019fb1d7-d968-9301-9301-000000000001';
        RAISE EXCEPTION 'disposed dead letter was rewritten';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        INSERT INTO public.worker_lease (
            id, tenant_id, created_by, updated_by, node_instance_id,
            lease_generation, worker_id, status, acquired_at, expires_at
        ) VALUES (
            '019fb1d7-d968-9491-9491-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'system:dispatcher', 'system:dispatcher',
            '019fb1d7-d968-8701-8701-000000000001',
            1, 'stale-worker', 'ACTIVE', transaction_timestamp(),
            transaction_timestamp() + interval '5 minutes'
        );
        RAISE EXCEPTION 'stale Worker fencing token was accepted';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        DELETE FROM public.outbox_event
         WHERE id = '019fb1d7-d968-9001-9001-000000000001';
        RAISE EXCEPTION 'application role physically deleted Outbox history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
END;
$negative$;
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
UPDATE public.node_instance
   SET lease_expire_at = transaction_timestamp() + interval '20 minutes',
       updated_at = transaction_timestamp(),
       updated_by = 'system:dispatcher',
       version = version + 1
 WHERE id = '019fb1d7-d968-8701-8701-000000000001';
UPDATE public.worker_lease
   SET last_heartbeat_at = transaction_timestamp(),
       expires_at = (
           SELECT lease_expire_at
             FROM public.node_instance
            WHERE id = '019fb1d7-d968-8701-8701-000000000001'
       ),
       updated_at = transaction_timestamp(),
       updated_by = 'worker:a',
       version = version + 1
 WHERE id = '019fb1d7-d968-9401-9401-000000000001';
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
UPDATE public.worker_lease
   SET status = 'RELEASED',
       released_at = transaction_timestamp(),
       updated_at = transaction_timestamp(),
       updated_by = 'system:dispatcher',
       version = version + 1
 WHERE id = '019fb1d7-d968-9401-9401-000000000001';
DO $negative$
BEGIN
    BEGIN
        UPDATE public.worker_lease
           SET released_at = released_at + interval '1 second',
               version = version + 1
         WHERE id = '019fb1d7-d968-9401-9401-000000000001';
        RAISE EXCEPTION 'terminal Worker lease was rewritten';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
END;
$negative$;
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
UPDATE public.node_instance
   SET status = 'DISPATCHED',
       dispatch_count = 1,
       lease_generation = 1,
       current_worker_id = 'worker-b-01',
       lease_expire_at = transaction_timestamp() + interval '10 minutes',
       updated_at = transaction_timestamp(),
       updated_by = 'system:dispatcher',
       version = version + 1
 WHERE id = '019fb1d7-d968-8702-8702-000000000002';
INSERT INTO public.outbox_event (
    id, tenant_id, created_by, aggregate_type, aggregate_id, event_type,
    schema_version, partition_key, payload_ref, payload_digest,
    occurred_at, next_publish_at
) VALUES (
    '019fb1d7-d968-9002-9002-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'system:runtime', 'workflow_instance',
    '019fb1d7-d968-8602-8602-000000000002',
    'workflow.instance.created', 1,
    '019fb1d7-d968-8602-8602-000000000002',
    'object://tenant-b/outbox/payload-1', repeat('6', 64),
    transaction_timestamp(), transaction_timestamp()
);
INSERT INTO public.processed_event (
    id, tenant_id, created_by, consumer_name, event_id, event_type,
    schema_version, processed_at
) VALUES (
    '019fb1d7-d968-9102-9102-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'system:runtime-consumer', 'runtime-state-projector',
    '019fb1d7-d968-9002-9002-000000000002',
    'workflow.instance.created', 1, transaction_timestamp()
);
INSERT INTO public.idempotency_record (
    id, tenant_id, created_by, updated_by, operation_scope,
    key_digest, request_digest, status, expires_at
) VALUES (
    '019fb1d7-d968-9202-9202-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:caller-b', 'oidc:caller-b', 'POST:/api/v1/tenant-b/instances',
    repeat('7', 64), repeat('8', 64), 'PROCESSING',
    transaction_timestamp() + interval '24 hours'
);
INSERT INTO public.dead_letter (
    id, tenant_id, created_by, updated_by, node_instance_id,
    category, error_code, error_summary_redacted, status
) VALUES (
    '019fb1d7-d968-9302-9302-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'system:runtime', 'system:runtime',
    '019fb1d7-d968-8702-8702-000000000002',
    'INTERNAL', 'WORKER_INTERNAL_DISPATCH',
    'Dispatch failed before attempt creation', 'OPEN'
);
INSERT INTO public.worker_lease (
    id, tenant_id, created_by, updated_by, node_instance_id,
    lease_generation, worker_id, status, acquired_at, expires_at
)
SELECT
    '019fb1d7-d968-9402-9402-000000000002',
    tenant_id, 'system:dispatcher', 'system:dispatcher', id,
    lease_generation, current_worker_id, 'ACTIVE', transaction_timestamp(), lease_expire_at
  FROM public.node_instance
 WHERE id = '019fb1d7-d968-8702-8702-000000000002';
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'tenant context leaked through G2-07 transaction boundary';
    END IF;
    IF EXISTS (SELECT FROM public.outbox_event)
       OR EXISTS (SELECT FROM public.processed_event)
       OR EXISTS (SELECT FROM public.idempotency_record)
       OR EXISTS (SELECT FROM public.dead_letter)
       OR EXISTS (SELECT FROM public.worker_lease) THEN
        RAISE EXCEPTION 'G2-07 rows leaked without tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
DECLARE
    affected_rows integer;
BEGIN
    IF (SELECT count(*) FROM public.outbox_event) <> 1
       OR (SELECT count(*) FROM public.processed_event) <> 1
       OR (SELECT count(*) FROM public.idempotency_record) <> 1
       OR (SELECT count(*) FROM public.dead_letter) <> 1
       OR (SELECT count(*) FROM public.worker_lease) <> 1 THEN
        RAISE EXCEPTION 'tenant A G2-07 row counts drifted';
    END IF;
    IF NOT EXISTS (
        SELECT FROM public.outbox_event
         WHERE id = '019fb1d7-d968-9001-9001-000000000001'
           AND published_at IS NOT NULL
           AND publish_attempts = 1
           AND version = 2
    ) THEN
        RAISE EXCEPTION 'Outbox publish lifecycle was not preserved';
    END IF;
    IF NOT EXISTS (
        SELECT FROM public.idempotency_record
         WHERE id = '019fb1d7-d968-9201-9201-000000000001'
           AND status = 'COMPLETED'
           AND version = 1
    ) THEN
        RAISE EXCEPTION 'idempotency completion was not preserved';
    END IF;
    IF NOT EXISTS (
        SELECT FROM public.dead_letter
         WHERE id = '019fb1d7-d968-9301-9301-000000000001'
           AND status = 'RESOLVED'
           AND disposition_count = 1
           AND version = 1
    ) THEN
        RAISE EXCEPTION 'dead-letter disposition was not preserved';
    END IF;
    IF NOT EXISTS (
        SELECT FROM public.worker_lease
         WHERE id = '019fb1d7-d968-9401-9401-000000000001'
           AND status = 'RELEASED'
           AND last_heartbeat_at IS NOT NULL
           AND version = 2
    ) THEN
        RAISE EXCEPTION 'Worker heartbeat/release lifecycle was not preserved';
    END IF;
    UPDATE public.idempotency_record
       SET updated_by = 'cross-tenant-write-must-not-happen'
     WHERE id = '019fb1d7-d968-9202-9202-000000000002';
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 0 THEN
        RAISE EXCEPTION 'tenant A updated tenant B idempotency record';
    END IF;
END;
$test$;
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
DO $test$
BEGIN
    IF (SELECT count(*) FROM public.outbox_event) <> 1
       OR (SELECT count(*) FROM public.processed_event) <> 1
       OR (SELECT count(*) FROM public.idempotency_record) <> 1
       OR (SELECT count(*) FROM public.dead_letter) <> 1
       OR (SELECT count(*) FROM public.worker_lease) <> 1 THEN
        RAISE EXCEPTION 'tenant B G2-07 row counts drifted';
    END IF;
    IF EXISTS (
        SELECT FROM public.outbox_event
         WHERE id = '019fb1d7-d968-9001-9001-000000000001'
    ) THEN
        RAISE EXCEPTION 'tenant B can see tenant A Outbox event';
    END IF;
END;
$test$;
COMMIT;

SELECT 'G2-07 application-role reliability, lifecycle, fencing, and RLS tests PASS' AS result;
