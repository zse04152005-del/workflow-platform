\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'application test must start without tenant context';
    END IF;
    IF EXISTS (SELECT FROM public.human_task)
       OR EXISTS (SELECT FROM public.audit_log)
       OR EXISTS (SELECT FROM public.quota_usage) THEN
        RAISE EXCEPTION 'G2-08 tables failed closed visibility without tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');

INSERT INTO public.human_task (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    node_instance_id, assignee_subject, form_schema, status, timeout_policy
) VALUES (
    '019fb1d7-d968-9501-9501-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    transaction_timestamp() - interval '10 minutes', 'system:runtime',
    transaction_timestamp() - interval '10 minutes', 'system:runtime',
    '019fb1d7-d968-8701-8701-000000000001', 'oidc:manager-a',
    '{"type":"object","required":["approved"]}'::jsonb,
    'WAITING', 'NONE'
);
INSERT INTO public.human_task (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    node_instance_id, candidate_role_code, form_schema, status,
    deadline_at, timeout_policy
) VALUES (
    '019fb1d7-d968-9502-9502-000000000002',
    '019fb1d7-d968-7a01-8a01-000000000001',
    transaction_timestamp() - interval '10 minutes', 'system:runtime',
    transaction_timestamp() - interval '10 minutes', 'system:runtime',
    '019fb1d7-d968-8702-8702-000000000002', 'risk_reviewer',
    '{"type":"object"}'::jsonb, 'WAITING',
    transaction_timestamp() - interval '5 minutes', 'FAIL'
);
INSERT INTO public.human_task (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    node_instance_id, assignee_subject, form_schema, status, timeout_policy
) VALUES (
    '019fb1d7-d968-9503-9503-000000000003',
    '019fb1d7-d968-7a01-8a01-000000000001',
    transaction_timestamp() - interval '10 minutes', 'system:runtime',
    transaction_timestamp() - interval '10 minutes', 'system:runtime',
    '019fb1d7-d968-8703-8703-000000000003', 'oidc:finance-a',
    '{"type":"object"}'::jsonb, 'WAITING', 'NONE'
);
INSERT INTO public.human_task (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    node_instance_id, candidate_role_code, form_schema, status,
    deadline_at, timeout_policy
) VALUES (
    '019fb1d7-d968-9504-9504-000000000004',
    '019fb1d7-d968-7a01-8a01-000000000001',
    transaction_timestamp() - interval '10 minutes', 'system:runtime',
    transaction_timestamp() - interval '10 minutes', 'system:runtime',
    '019fb1d7-d968-8704-8704-000000000004', 'future_reviewer',
    '{"type":"object"}'::jsonb, 'WAITING',
    transaction_timestamp() + interval '5 minutes', 'ESCALATE'
);

INSERT INTO public.audit_log (
    id, tenant_id, created_by, actor_type, actor_id, action,
    resource_type, resource_id, occurred_at, reason, trace_id,
    before_digest_or_redacted, after_digest_or_redacted
) VALUES (
    '019fb1d7-d968-9601-9601-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'service:human-task', 'USER', 'oidc:manager-a', 'human_task.created',
    'human_task', '019fb1d7-d968-9501-9501-000000000001',
    transaction_timestamp(), 'approval required', repeat('3', 32),
    NULL, '{"status":"WAITING","form":"redacted"}'::jsonb
);

INSERT INTO public.quota_usage (
    id, tenant_id, created_by, updated_by, resource_type,
    window_start, window_end, used_value, limit_value
) VALUES (
    '019fb1d7-d968-9701-9701-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'service:quota', 'service:quota', 'workflow_start',
    '2026-07-30T00:00:00Z', '2026-07-30T01:00:00Z', 1, 10
);

UPDATE public.human_task
   SET assignee_subject = 'oidc:delegate-a',
       transfer_count = transfer_count + 1,
       last_transferred_at = transaction_timestamp(),
       last_transferred_by = 'oidc:manager-a',
       updated_at = transaction_timestamp(),
       updated_by = 'oidc:manager-a',
       version = version + 1
 WHERE id = '019fb1d7-d968-9501-9501-000000000001';

UPDATE public.human_task
   SET status = 'DECIDED',
       decision = 'APPROVED',
       decision_at = transaction_timestamp(),
       decided_by = 'oidc:delegate-a',
       updated_at = transaction_timestamp(),
       updated_by = 'oidc:delegate-a',
       version = version + 1
 WHERE id = '019fb1d7-d968-9501-9501-000000000001';

UPDATE public.human_task
   SET status = 'TIMED_OUT',
       updated_at = transaction_timestamp(),
       updated_by = 'system:timeout',
       version = version + 1
 WHERE id = '019fb1d7-d968-9502-9502-000000000002';

UPDATE public.quota_usage
   SET used_value = used_value + 2,
       updated_at = transaction_timestamp(),
       updated_by = 'service:quota',
       version = version + 1
 WHERE id = '019fb1d7-d968-9701-9701-000000000001';

INSERT INTO public.quota_usage (
    id, tenant_id, created_by, updated_by, resource_type,
    window_start, window_end, used_value, limit_value
) VALUES (
    '019fb1d7-d968-9702-9702-000000000002',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'service:quota', 'service:quota', 'workflow_start',
    '2026-07-30T01:00:00Z', '2026-07-30T02:00:00Z', 0, 10
);

DO $negative$
BEGIN
    BEGIN
        UPDATE public.human_task
           SET decision = 'REJECTED',
               decision_at = transaction_timestamp(),
               decided_by = 'oidc:another-user',
               updated_at = transaction_timestamp(),
               updated_by = 'oidc:another-user',
               version = version + 1
         WHERE id = '019fb1d7-d968-9501-9501-000000000001';
        RAISE EXCEPTION 'second human decision rewrote terminal evidence';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        UPDATE public.human_task
           SET updated_at = transaction_timestamp(),
               updated_by = 'oidc:finance-a',
               version = version + 1
         WHERE id = '019fb1d7-d968-9503-9503-000000000003';
        RAISE EXCEPTION 'WAITING human task allowed a non-transfer update';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        UPDATE public.human_task
           SET status = 'TIMED_OUT',
               updated_at = transaction_timestamp(),
               updated_by = 'system:timeout',
               version = version + 1
         WHERE id = '019fb1d7-d968-9504-9504-000000000004';
        RAISE EXCEPTION 'future human deadline timed out early';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        INSERT INTO public.human_task (
            id, tenant_id, created_by, updated_by, node_instance_id,
            assignee_subject, form_schema, status, timeout_policy
        ) VALUES (
            '019fb1d7-d968-9591-9591-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'system:runtime', 'system:runtime',
            '019fb1d7-d968-8791-8791-000000000091', 'oidc:cross-tenant',
            '{"type":"object"}'::jsonb, 'WAITING', 'NONE'
        );
        RAISE EXCEPTION 'human task accepted a node from another tenant';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        UPDATE public.audit_log
           SET reason = 'rewritten'
         WHERE id = '019fb1d7-d968-9601-9601-000000000001';
        RAISE EXCEPTION 'application role updated audit history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        DELETE FROM public.audit_log
         WHERE id = '019fb1d7-d968-9601-9601-000000000001';
        RAISE EXCEPTION 'application role deleted audit history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        INSERT INTO public.audit_log (
            id, tenant_id, created_by, actor_type, actor_id, action,
            resource_type, resource_id, occurred_at, trace_id
        ) VALUES (
            '019fb1d7-d968-9691-9691-000000000091', NULL,
            'oidc:tenant-user', 'PLATFORM_ADMIN', 'oidc:tenant-user',
            'platform.setting_changed', 'platform_setting', 'global',
            transaction_timestamp(), repeat('4', 32)
        );
        RAISE EXCEPTION 'tenant application appended a platform audit row';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        UPDATE public.quota_usage
           SET used_value = used_value - 1,
               updated_at = transaction_timestamp(),
               updated_by = 'service:quota',
               version = version + 1
         WHERE id = '019fb1d7-d968-9701-9701-000000000001';
        RAISE EXCEPTION 'quota usage moved backwards';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        INSERT INTO public.quota_usage (
            id, tenant_id, created_by, updated_by, resource_type,
            window_start, window_end, used_value, limit_value
        ) VALUES (
            '019fb1d7-d968-9791-9791-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'service:quota', 'service:quota', 'workflow_start',
            '2026-07-30T00:30:00Z', '2026-07-30T01:30:00Z', 0, 10
        );
        RAISE EXCEPTION 'overlapping quota window was accepted';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        DELETE FROM public.quota_usage
         WHERE id = '019fb1d7-d968-9701-9701-000000000001';
        RAISE EXCEPTION 'application role deleted quota history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
END;
$negative$;

DO $test$
BEGIN
    IF (SELECT count(*) FROM public.human_task) <> 4 THEN
        RAISE EXCEPTION 'tenant A human-task fixture count drifted';
    END IF;
    IF (SELECT status FROM public.human_task WHERE id = '019fb1d7-d968-9501-9501-000000000001') <> 'DECIDED'
       OR (SELECT transfer_count FROM public.human_task WHERE id = '019fb1d7-d968-9501-9501-000000000001') <> 1
       OR (SELECT status FROM public.human_task WHERE id = '019fb1d7-d968-9502-9502-000000000002') <> 'TIMED_OUT' THEN
        RAISE EXCEPTION 'human transfer/decision/timeout state drifted';
    END IF;
    IF (SELECT count(*) FROM public.audit_log) <> 1 THEN
        RAISE EXCEPTION 'tenant A audit fixture count drifted';
    END IF;
    IF (SELECT count(*) FROM public.quota_usage) <> 2
       OR (SELECT used_value FROM public.quota_usage WHERE id = '019fb1d7-d968-9701-9701-000000000001') <> 3 THEN
        RAISE EXCEPTION 'tenant A quota fixtures or monotonic increment drifted';
    END IF;
END;
$test$;
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
DO $test$
BEGIN
    IF EXISTS (SELECT FROM public.human_task)
       OR EXISTS (SELECT FROM public.audit_log)
       OR EXISTS (SELECT FROM public.quota_usage) THEN
        RAISE EXCEPTION 'tenant B observed tenant A G2-08 rows';
    END IF;
END;
$test$;

INSERT INTO public.human_task (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    node_instance_id, candidate_role_code, form_schema, status, timeout_policy
) VALUES (
    '019fb1d7-d968-9581-9581-000000000081',
    '019fb1d7-d968-7a02-8a02-000000000002',
    transaction_timestamp() - interval '1 minute', 'system:runtime',
    transaction_timestamp() - interval '1 minute', 'system:runtime',
    '019fb1d7-d968-8791-8791-000000000091', 'tenant_b_reviewer',
    '{"type":"object"}'::jsonb, 'WAITING', 'NONE'
);
INSERT INTO public.audit_log (
    id, tenant_id, created_by, actor_type, actor_id, action,
    resource_type, resource_id, occurred_at, trace_id
) VALUES (
    '019fb1d7-d968-9681-9681-000000000081',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'service:human-task', 'SERVICE', 'service:human-task', 'human_task.created',
    'human_task', '019fb1d7-d968-9581-9581-000000000081',
    transaction_timestamp(), repeat('8', 32)
);
INSERT INTO public.quota_usage (
    id, tenant_id, created_by, updated_by, resource_type,
    window_start, window_end, used_value, limit_value
) VALUES (
    '019fb1d7-d968-9781-9781-000000000081',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'service:quota', 'service:quota', 'workflow_start',
    '2026-07-30T00:00:00Z', '2026-07-30T01:00:00Z', 1, 5
);
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'application tenant context leaked after commit';
    END IF;
    IF EXISTS (SELECT FROM public.human_task)
       OR EXISTS (SELECT FROM public.audit_log)
       OR EXISTS (SELECT FROM public.quota_usage) THEN
        RAISE EXCEPTION 'G2-08 rows became visible without tenant context';
    END IF;
END;
$test$;

SELECT 'G2-08 application RLS, human lifecycle, audit, quota, and least-privilege tests PASS' AS result;
