\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'tenant context must fail closed before G2-06 tests';
    END IF;
    IF public.app_is_platform_admin() THEN
        RAISE EXCEPTION 'ordinary application role became platform administrator';
    END IF;
    IF EXISTS (SELECT FROM public.workflow_instance)
       OR EXISTS (SELECT FROM public.node_instance)
       OR EXISTS (SELECT FROM public.task_execution)
       OR EXISTS (SELECT FROM public.schedule_occurrence) THEN
        RAISE EXCEPTION 'G2-06 rows are visible without tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
INSERT INTO public.workflow_instance (
    id, tenant_id, created_by, updated_by, definition_id, workflow_version_id,
    business_key, status, input_ref, input_digest, started_at, trace_id
) VALUES (
    '019fb1d7-d968-8601-8601-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:runtime-a', 'oidc:runtime-a',
    '019fb1d7-d968-8101-8101-000000000001',
    '019fb1d7-d968-8201-8201-000000000001',
    'order-001', 'RUNNING', 'object://tenant-a/input/order-001',
    repeat('1', 64), transaction_timestamp(), repeat('1', 32)
);
INSERT INTO public.node_instance (
    id, tenant_id, created_by, updated_by, workflow_instance_id,
    node_id, node_type, status, attempt_no, dispatch_count, lease_generation,
    current_worker_id, lease_expire_at
) VALUES (
    '019fb1d7-d968-8701-8701-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:runtime-a', 'oidc:runtime-a',
    '019fb1d7-d968-8601-8601-000000000001',
    'charge_payment', 'http_task', 'RUNNING', 2, 2, 2,
    'worker-a-01', transaction_timestamp() + interval '5 minutes'
);
INSERT INTO public.task_execution (
    id, tenant_id, created_by, updated_by, node_instance_id, attempt_no,
    execution_id, status, started_at, input_ref, input_digest
) VALUES
    (
        '019fb1d7-d968-8801-8801-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'worker:a', 'worker:a', '019fb1d7-d968-8701-8701-000000000001', 1,
        '019fb1d7-d968-8811-8811-000000000011', 'RUNNING',
        transaction_timestamp(), 'object://tenant-a/task/input-1', repeat('2', 64)
    ),
    (
        '019fb1d7-d968-8802-8802-000000000002',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'worker:a', 'worker:a', '019fb1d7-d968-8701-8701-000000000001', 2,
        '019fb1d7-d968-8812-8812-000000000012', 'RUNNING',
        transaction_timestamp(), 'object://tenant-a/task/input-2', repeat('3', 64)
    );
UPDATE public.task_execution
   SET status = 'SUCCEEDED',
       finished_at = transaction_timestamp(),
       output_ref = 'object://tenant-a/task/output-1',
       output_digest = repeat('4', 64),
       updated_at = transaction_timestamp(),
       updated_by = 'worker:a',
       version = version + 1
 WHERE id = '019fb1d7-d968-8801-8801-000000000001';

INSERT INTO public.schedule_occurrence (
    id, tenant_id, created_by, updated_by, trigger_config_id, scheduled_at,
    timezone, misfire_policy, status
) VALUES
    (
        '019fb1d7-d968-8901-8901-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'scheduler:a', 'scheduler:a', '019fb1d7-d968-8301-8301-000000000001',
        transaction_timestamp() - interval '1 minute',
        'Asia/Shanghai', 'fire_once', 'PENDING'
    ),
    (
        '019fb1d7-d968-8902-8902-000000000002',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'scheduler:a', 'scheduler:a', '019fb1d7-d968-8301-8301-000000000001',
        transaction_timestamp(), 'Asia/Shanghai', 'fire_once', 'PENDING'
    );
UPDATE public.schedule_occurrence
   SET status = 'CLAIMED',
       claimed_at = transaction_timestamp(),
       updated_at = transaction_timestamp(),
       updated_by = 'scheduler:a',
       version = version + 1
 WHERE id = '019fb1d7-d968-8901-8901-000000000001';
UPDATE public.schedule_occurrence
   SET status = 'FIRED',
       workflow_instance_id = '019fb1d7-d968-8601-8601-000000000001',
       updated_at = transaction_timestamp(),
       updated_by = 'scheduler:a',
       version = version + 1
 WHERE id = '019fb1d7-d968-8901-8901-000000000001';

DO $test$
BEGIN
    BEGIN
        INSERT INTO public.workflow_instance (
            id, tenant_id, created_by, updated_by, definition_id,
            workflow_version_id, status, input_digest, trace_id
        ) VALUES (
            '019fb1d7-d968-8691-8691-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:runtime-a', 'oidc:runtime-a',
            '019fb1d7-d968-8101-8101-000000000001',
            '019fb1d7-d968-8203-8203-000000000003',
            'CREATED', repeat('5', 64), repeat('5', 32)
        );
        RAISE EXCEPTION 'definition/version mismatch was accepted';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_instance (
            id, tenant_id, created_by, updated_by, definition_id,
            workflow_version_id, status, input_digest, trace_id
        ) VALUES (
            '019fb1d7-d968-8692-8692-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:runtime-a', 'oidc:runtime-a',
            '019fb1d7-d968-8101-8101-000000000001',
            '019fb1d7-d968-8201-8201-000000000001',
            'CREATED', repeat('5', 64), repeat('0', 32)
        );
        RAISE EXCEPTION 'all-zero trace id was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_instance (
            id, tenant_id, created_by, updated_by, definition_id,
            workflow_version_id, status, input_digest, started_at, trace_id
        ) VALUES (
            '019fb1d7-d968-8693-8693-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:runtime-a', 'oidc:runtime-a',
            '019fb1d7-d968-8101-8101-000000000001',
            '019fb1d7-d968-8201-8201-000000000001',
            'CREATED', repeat('5', 64), transaction_timestamp(), repeat('6', 32)
        );
        RAISE EXCEPTION 'CREATED workflow with started_at was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.node_instance (
            id, tenant_id, created_by, updated_by, workflow_instance_id,
            node_id, node_type, status
        ) VALUES (
            '019fb1d7-d968-8791-8791-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:runtime-a', 'oidc:runtime-a',
            '019fb1d7-d968-8601-8601-000000000001',
            'missing_due_time', 'timer', 'READY'
        );
        RAISE EXCEPTION 'READY node without next_schedule_at was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.node_instance (
            id, tenant_id, created_by, updated_by, workflow_instance_id,
            node_id, node_type, status, current_worker_id, lease_expire_at
        ) VALUES (
            '019fb1d7-d968-8792-8792-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:runtime-a', 'oidc:runtime-a',
            '019fb1d7-d968-8601-8601-000000000001',
            'expired_lease', 'http_task', 'RUNNING', 'worker-bad',
            transaction_timestamp() - interval '1 second'
        );
        RAISE EXCEPTION 'expired RUNNING lease was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.task_execution (
            id, tenant_id, created_by, updated_by, node_instance_id, attempt_no,
            execution_id, status, started_at, finished_at, input_digest
        ) VALUES (
            '019fb1d7-d968-8891-8891-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'worker:a', 'worker:a', '019fb1d7-d968-8701-8701-000000000001', 91,
            '019fb1d7-d968-8892-8892-000000000092', 'SUCCEEDED',
            transaction_timestamp(), transaction_timestamp(), repeat('6', 64)
        );
        RAISE EXCEPTION 'terminal task was inserted without RUNNING phase';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        INSERT INTO public.task_execution (
            id, tenant_id, created_by, updated_by, node_instance_id, attempt_no,
            execution_id, status, started_at, input_digest, error_code
        ) VALUES (
            '019fb1d7-d968-8893-8893-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'worker:a', 'worker:a', '019fb1d7-d968-8701-8701-000000000001', 93,
            '019fb1d7-d968-8894-8894-000000000094', 'RUNNING',
            transaction_timestamp(), repeat('6', 64), 'STALE_ERROR'
        );
        RAISE EXCEPTION 'RUNNING task with terminal error fields was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.task_execution
           SET status = 'FAILED',
               error_code = 'LATE_REWRITE',
               error_summary_redacted = 'must fail',
               version = version + 1
         WHERE id = '019fb1d7-d968-8801-8801-000000000001';
        RAISE EXCEPTION 'terminal task was rewritten';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        UPDATE public.task_execution
           SET status = 'FAILED',
               finished_at = transaction_timestamp(),
               error_code = 'IDENTITY_MUTATION',
               error_summary_redacted = 'must fail',
               execution_id = '019fb1d7-d968-8895-8895-000000000095',
               updated_at = transaction_timestamp(),
               version = version + 1
         WHERE id = '019fb1d7-d968-8802-8802-000000000002';
        RAISE EXCEPTION 'task identity mutation during finalization was accepted';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        INSERT INTO public.schedule_occurrence (
            id, tenant_id, created_by, updated_by, trigger_config_id,
            scheduled_at, timezone, misfire_policy, status
        ) VALUES (
            '019fb1d7-d968-8991-8991-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'scheduler:a', 'scheduler:a', '019fb1d7-d968-8303-8303-000000000003',
            transaction_timestamp() + interval '1 minute', 'UTC', 'skip', 'PENDING'
        );
        RAISE EXCEPTION 'manual trigger produced a schedule occurrence';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.schedule_occurrence (
            id, tenant_id, created_by, updated_by, trigger_config_id,
            scheduled_at, timezone, misfire_policy, status
        ) VALUES (
            '019fb1d7-d968-8992-8992-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'scheduler:a', 'scheduler:a', '019fb1d7-d968-8301-8301-000000000001',
            transaction_timestamp() + interval '1 minute', 'UTC', 'skip', 'PENDING'
        );
        RAISE EXCEPTION 'Cron occurrence snapshot drift was accepted';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.schedule_occurrence (
            id, tenant_id, created_by, updated_by, trigger_config_id,
            scheduled_at, timezone, misfire_policy, status, claimed_at
        ) VALUES (
            '019fb1d7-d968-8993-8993-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'scheduler:a', 'scheduler:a', '019fb1d7-d968-8301-8301-000000000001',
            transaction_timestamp() - interval '2 minutes', 'Asia/Shanghai', 'fire_once',
            'FIRED', transaction_timestamp()
        );
        RAISE EXCEPTION 'FIRED occurrence without workflow instance was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        DELETE FROM public.task_execution
         WHERE id = '019fb1d7-d968-8801-8801-000000000001';
        RAISE EXCEPTION 'application role physically deleted task history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
END;
$test$;
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
INSERT INTO public.workflow_instance (
    id, tenant_id, created_by, updated_by, definition_id, workflow_version_id,
    status, input_digest, trace_id
) VALUES (
    '019fb1d7-d968-8602-8602-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:runtime-b', 'oidc:runtime-b',
    '019fb1d7-d968-8102-8102-000000000002',
    '019fb1d7-d968-8202-8202-000000000002',
    'CREATED', repeat('7', 64), repeat('7', 32)
);
INSERT INTO public.node_instance (
    id, tenant_id, created_by, updated_by, workflow_instance_id,
    node_id, node_type, status
) VALUES (
    '019fb1d7-d968-8702-8702-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:runtime-b', 'oidc:runtime-b',
    '019fb1d7-d968-8602-8602-000000000002',
    'tenant_b_start', 'start', 'PENDING'
);
INSERT INTO public.schedule_occurrence (
    id, tenant_id, created_by, updated_by, trigger_config_id, scheduled_at,
    timezone, misfire_policy, status
) VALUES (
    '019fb1d7-d968-8903-8903-000000000003',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'scheduler:b', 'scheduler:b', '019fb1d7-d968-8302-8302-000000000002',
    transaction_timestamp(), 'UTC', 'skip', 'PENDING'
);
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'tenant context leaked through G2-06 transaction boundary';
    END IF;
    IF EXISTS (SELECT FROM public.workflow_instance)
       OR EXISTS (SELECT FROM public.node_instance)
       OR EXISTS (SELECT FROM public.task_execution)
       OR EXISTS (SELECT FROM public.schedule_occurrence) THEN
        RAISE EXCEPTION 'G2-06 rows leaked without tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
DECLARE
    affected_rows integer;
BEGIN
    IF (SELECT count(*) FROM public.workflow_instance) <> 1
       OR (SELECT count(*) FROM public.node_instance) <> 1
       OR (SELECT count(*) FROM public.task_execution) <> 2
       OR (SELECT count(*) FROM public.schedule_occurrence) <> 2 THEN
        RAISE EXCEPTION 'tenant A G2-06 row counts drifted';
    END IF;
    IF NOT EXISTS (
        SELECT FROM public.task_execution
         WHERE id = '019fb1d7-d968-8801-8801-000000000001'
           AND status = 'SUCCEEDED'
           AND version = 1
    ) THEN
        RAISE EXCEPTION 'task finalization was not preserved';
    END IF;
    IF NOT EXISTS (
        SELECT FROM public.schedule_occurrence
         WHERE id = '019fb1d7-d968-8901-8901-000000000001'
           AND status = 'FIRED'
           AND version = 2
    ) THEN
        RAISE EXCEPTION 'schedule claim/fire lifecycle was not preserved';
    END IF;
    UPDATE public.workflow_instance
       SET business_key = 'cross-tenant-write-must-not-happen'
     WHERE id = '019fb1d7-d968-8602-8602-000000000002';
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 0 THEN
        RAISE EXCEPTION 'tenant A updated tenant B workflow instance';
    END IF;
END;
$test$;
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
DO $test$
BEGIN
    IF (SELECT count(*) FROM public.workflow_instance) <> 1
       OR (SELECT count(*) FROM public.node_instance) <> 1
       OR (SELECT count(*) FROM public.task_execution) <> 0
       OR (SELECT count(*) FROM public.schedule_occurrence) <> 1 THEN
        RAISE EXCEPTION 'tenant B G2-06 row counts drifted';
    END IF;
    IF EXISTS (
        SELECT FROM public.workflow_instance
         WHERE id = '019fb1d7-d968-8601-8601-000000000001'
    ) THEN
        RAISE EXCEPTION 'tenant B can see tenant A workflow instance';
    END IF;
END;
$test$;
COMMIT;

SELECT 'G2-06 application-role constraints, RLS, and task-finalization tests PASS' AS result;
