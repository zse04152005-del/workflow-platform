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
    IF EXISTS (SELECT FROM public.human_task)
       OR EXISTS (SELECT FROM public.quota_usage)
       OR EXISTS (SELECT FROM public.audit_log) THEN
        RAISE EXCEPTION 'platform administrator bypassed G2-08 tenant context';
    END IF;
END;
$test$;

INSERT INTO public.audit_log (
    id, tenant_id, created_by, actor_type, actor_id, action,
    resource_type, resource_id, occurred_at, reason, trace_id,
    after_digest_or_redacted
) VALUES (
    '019fb1d7-d968-9699-9699-000000000099', NULL,
    'oidc:platform-admin', 'PLATFORM_ADMIN', 'oidc:platform-admin',
    'platform.setting_changed', 'platform_setting', 'global-retention',
    transaction_timestamp(), 'approved platform retention change', repeat('9', 32),
    '{"retention":"redacted-policy-id"}'::jsonb
);

DO $negative$
BEGIN
    IF (SELECT count(*) FROM public.audit_log) <> 1 THEN
        RAISE EXCEPTION 'platform administrator cannot see the platform audit row';
    END IF;

    BEGIN
        INSERT INTO public.audit_log (
            id, tenant_id, created_by, actor_type, actor_id, action,
            resource_type, resource_id, occurred_at, trace_id
        ) VALUES (
            '019fb1d7-d968-9698-9698-000000000098', NULL,
            'system:platform', 'SYSTEM', 'system:platform',
            'platform.setting_changed', 'platform_setting', 'invalid-actor',
            transaction_timestamp(), repeat('8', 32)
        );
        RAISE EXCEPTION 'platform audit row accepted a non-platform actor';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.audit_log (
            id, tenant_id, created_by, actor_type, actor_id, action,
            resource_type, resource_id, occurred_at, trace_id
        ) VALUES (
            '019fb1d7-d968-9697-9697-000000000097',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:platform-admin', 'PLATFORM_ADMIN', 'oidc:platform-admin',
            'tenant.setting_changed', 'tenant',
            '019fb1d7-d968-7a01-8a01-000000000001',
            transaction_timestamp(), repeat('7', 32)
        );
        RAISE EXCEPTION 'platform administrator wrote tenant audit without tenant context';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        UPDATE public.audit_log
           SET reason = 'platform rewrite'
         WHERE id = '019fb1d7-d968-9699-9699-000000000099';
        RAISE EXCEPTION 'platform administrator updated audit history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        DELETE FROM public.audit_log
         WHERE id = '019fb1d7-d968-9699-9699-000000000099';
        RAISE EXCEPTION 'platform administrator deleted audit history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
END;
$negative$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
BEGIN
    IF (SELECT count(*) FROM public.human_task) <> 4
       OR (SELECT count(*) FROM public.quota_usage) <> 2 THEN
        RAISE EXCEPTION 'platform administrator tenant A isolation drifted';
    END IF;
    IF (SELECT count(*) FROM public.audit_log) <> 2 THEN
        RAISE EXCEPTION 'tenant A context must expose tenant A plus platform audit rows';
    END IF;
    IF EXISTS (
        SELECT FROM public.human_task
         WHERE id = '019fb1d7-d968-9581-9581-000000000081'
    ) THEN
        RAISE EXCEPTION 'tenant A context exposed tenant B human task';
    END IF;

    BEGIN
        INSERT INTO public.human_task (
            id, tenant_id, created_by, updated_by, node_instance_id,
            assignee_subject, form_schema, status, timeout_policy
        ) VALUES (
            '019fb1d7-d968-9598-9598-000000000098',
            '019fb1d7-d968-7a02-8a02-000000000002',
            'oidc:platform-admin', 'oidc:platform-admin',
            '019fb1d7-d968-8791-8791-000000000091', 'oidc:cross-tenant',
            '{"type":"object"}'::jsonb, 'WAITING', 'NONE'
        );
        RAISE EXCEPTION 'platform administrator wrote human task outside active tenant';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        INSERT INTO public.quota_usage (
            id, tenant_id, created_by, updated_by, resource_type,
            window_start, window_end, used_value, limit_value
        ) VALUES (
            '019fb1d7-d968-9798-9798-000000000098',
            '019fb1d7-d968-7a02-8a02-000000000002',
            'oidc:platform-admin', 'oidc:platform-admin', 'workflow_start',
            '2026-07-30T02:00:00Z', '2026-07-30T03:00:00Z', 0, 5
        );
        RAISE EXCEPTION 'platform administrator wrote quota outside active tenant';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        UPDATE public.human_task
           SET decision = 'REJECTED',
               decision_at = transaction_timestamp(),
               decided_by = 'oidc:platform-admin',
               updated_at = transaction_timestamp(),
               updated_by = 'oidc:platform-admin',
               version = version + 1
         WHERE id = '019fb1d7-d968-9501-9501-000000000001';
        RAISE EXCEPTION 'platform administrator rewrote terminal human decision';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;

    BEGIN
        UPDATE public.quota_usage
           SET limit_value = 100,
               used_value = used_value + 1,
               updated_at = transaction_timestamp(),
               updated_by = 'oidc:platform-admin',
               version = version + 1
         WHERE id = '019fb1d7-d968-9701-9701-000000000001';
        RAISE EXCEPTION 'platform administrator rewrote quota limit snapshot';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
END;
$test$;
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
DO $test$
BEGIN
    IF (SELECT count(*) FROM public.human_task) <> 1
       OR (SELECT count(*) FROM public.quota_usage) <> 1 THEN
        RAISE EXCEPTION 'platform administrator tenant B isolation drifted';
    END IF;
    IF (SELECT count(*) FROM public.audit_log) <> 2 THEN
        RAISE EXCEPTION 'tenant B context must expose tenant B plus platform audit rows';
    END IF;
    IF EXISTS (
        SELECT FROM public.quota_usage
         WHERE id = '019fb1d7-d968-9701-9701-000000000001'
    ) THEN
        RAISE EXCEPTION 'tenant B context exposed tenant A quota usage';
    END IF;
END;
$test$;
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'platform tenant context leaked after commit';
    END IF;
    IF (SELECT count(*) FROM public.audit_log) <> 1
       OR EXISTS (SELECT FROM public.human_task)
       OR EXISTS (SELECT FROM public.quota_usage) THEN
        RAISE EXCEPTION 'platform-level visibility changed after tenant-context transactions';
    END IF;
END;
$test$;

SELECT 'G2-08 platform-admin RLS, platform audit, lifecycle, and quota tests PASS' AS result;
