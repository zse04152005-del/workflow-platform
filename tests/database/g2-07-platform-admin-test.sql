\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF NOT public.app_is_platform_admin() THEN
        RAISE EXCEPTION 'provisioned platform administrator was not recognized';
    END IF;
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'platform administrator unexpectedly inherited tenant context';
    END IF;
    IF (SELECT count(*) FROM public.tenant) <> 2 THEN
        RAISE EXCEPTION 'platform administrator must see both tenant fixtures';
    END IF;
    IF EXISTS (SELECT FROM public.outbox_event)
       OR EXISTS (SELECT FROM public.processed_event)
       OR EXISTS (SELECT FROM public.idempotency_record)
       OR EXISTS (SELECT FROM public.dead_letter)
       OR EXISTS (SELECT FROM public.worker_lease) THEN
        RAISE EXCEPTION 'platform administrator bypassed G2-07 tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
BEGIN
    IF (SELECT count(*) FROM public.outbox_event) <> 1
       OR (SELECT count(*) FROM public.processed_event) <> 1
       OR (SELECT count(*) FROM public.idempotency_record) <> 1
       OR (SELECT count(*) FROM public.dead_letter) <> 1
       OR (SELECT count(*) FROM public.worker_lease) <> 1 THEN
        RAISE EXCEPTION 'platform administrator tenant A isolation drifted';
    END IF;
    IF EXISTS (
        SELECT FROM public.outbox_event
         WHERE id = '019fb1d7-d968-9002-9002-000000000002'
    ) THEN
        RAISE EXCEPTION 'tenant A context exposed tenant B Outbox rows';
    END IF;

    BEGIN
        UPDATE public.outbox_event
           SET next_publish_at = next_publish_at + interval '1 minute',
               version = version + 1
         WHERE id = '019fb1d7-d968-9001-9001-000000000001';
        RAISE EXCEPTION 'platform administrator rewrote published Outbox event';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        UPDATE public.processed_event
           SET result_digest = repeat('0', 64)
         WHERE id = '019fb1d7-d968-9101-9101-000000000001';
        RAISE EXCEPTION 'platform administrator rewrote processed-event history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
        UPDATE public.idempotency_record
           SET response_status = 202,
               version = version + 1
         WHERE id = '019fb1d7-d968-9201-9201-000000000001';
        RAISE EXCEPTION 'platform administrator rewrote terminal idempotency history';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        UPDATE public.dead_letter
           SET error_summary_redacted = 'platform rewrite',
               version = version + 1
         WHERE id = '019fb1d7-d968-9301-9301-000000000001';
        RAISE EXCEPTION 'platform administrator rewrote disposed dead letter';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        UPDATE public.worker_lease
           SET released_at = released_at + interval '1 second',
               version = version + 1
         WHERE id = '019fb1d7-d968-9401-9401-000000000001';
        RAISE EXCEPTION 'platform administrator rewrote terminal Worker lease';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        DELETE FROM public.dead_letter
         WHERE id = '019fb1d7-d968-9301-9301-000000000001';
        RAISE EXCEPTION 'platform administrator physically deleted dead-letter history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
        INSERT INTO public.outbox_event (
            id, tenant_id, created_by, aggregate_type, aggregate_id,
            event_type, schema_version, partition_key, payload,
            payload_digest, occurred_at, next_publish_at
        ) VALUES (
            '019fb1d7-d968-9098-9098-000000000098',
            '019fb1d7-d968-7a02-8a02-000000000002',
            'ops:runtime', 'workflow_instance',
            '019fb1d7-d968-8602-8602-000000000002',
            'workflow.cross_tenant', 1, 'cross-tenant', '{}'::jsonb,
            repeat('9', 64), transaction_timestamp(), transaction_timestamp()
        );
        RAISE EXCEPTION 'platform administrator wrote outside active tenant context';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
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
        RAISE EXCEPTION 'platform administrator tenant B isolation drifted';
    END IF;
    IF EXISTS (
        SELECT FROM public.outbox_event
         WHERE id = '019fb1d7-d968-9001-9001-000000000001'
    ) THEN
        RAISE EXCEPTION 'tenant B context exposed tenant A Outbox rows';
    END IF;
    BEGIN
        UPDATE public.worker_lease
           SET last_heartbeat_at = transaction_timestamp(),
               expires_at = expires_at + interval '1 minute',
               updated_at = transaction_timestamp(),
               updated_by = 'ops:runtime',
               version = version + 1
         WHERE id = '019fb1d7-d968-9402-9402-000000000002';
        RAISE EXCEPTION 'Worker heartbeat bypassed current node expiry fencing';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
END;
$test$;
COMMIT;

SELECT 'G2-07 platform-admin RLS, least-privilege, lifecycle, and fencing tests PASS' AS result;
