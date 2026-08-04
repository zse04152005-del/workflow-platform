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
    IF EXISTS (SELECT FROM public.workflow_instance)
       OR EXISTS (SELECT FROM public.node_instance)
       OR EXISTS (SELECT FROM public.task_execution)
       OR EXISTS (SELECT FROM public.schedule_occurrence) THEN
        RAISE EXCEPTION 'platform administrator bypassed G2-06 tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
BEGIN
    IF (SELECT count(*) FROM public.workflow_instance) <> 1
       OR (SELECT count(*) FROM public.node_instance) <> 1
       OR (SELECT count(*) FROM public.task_execution) <> 2
       OR (SELECT count(*) FROM public.schedule_occurrence) <> 2 THEN
        RAISE EXCEPTION 'platform administrator tenant A isolation drifted';
    END IF;
    IF EXISTS (
        SELECT FROM public.workflow_instance
         WHERE id = '019fb1d7-d968-8602-8602-000000000002'
    ) THEN
        RAISE EXCEPTION 'tenant A context exposed tenant B runtime rows';
    END IF;

    UPDATE public.task_execution
       SET status = 'FAILED',
           finished_at = transaction_timestamp(),
           error_code = 'DEPENDENCY_TIMEOUT',
           error_summary_redacted = 'dependency timed out',
           updated_at = transaction_timestamp(),
           updated_by = 'ops:runtime',
           version = version + 1
     WHERE id = '019fb1d7-d968-8802-8802-000000000002';
    IF NOT EXISTS (
        SELECT FROM public.task_execution
         WHERE id = '019fb1d7-d968-8802-8802-000000000002'
           AND status = 'FAILED'
           AND version = 1
    ) THEN
        RAISE EXCEPTION 'platform administrator could not finalize RUNNING task once';
    END IF;

    BEGIN
        UPDATE public.task_execution
           SET error_summary_redacted = 'second rewrite',
               version = version + 1
         WHERE id = '019fb1d7-d968-8802-8802-000000000002';
        RAISE EXCEPTION 'platform administrator rewrote terminal task';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        DELETE FROM public.schedule_occurrence
         WHERE id = '019fb1d7-d968-8902-8902-000000000002';
        RAISE EXCEPTION 'platform administrator physically deleted occurrence history';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
        INSERT INTO public.workflow_instance (
            id, tenant_id, created_by, updated_by, definition_id,
            workflow_version_id, status, input_digest, trace_id
        ) VALUES (
            '019fb1d7-d968-8698-8698-000000000098',
            '019fb1d7-d968-7a02-8a02-000000000002',
            'ops:runtime', 'ops:runtime',
            '019fb1d7-d968-8102-8102-000000000002',
            '019fb1d7-d968-8202-8202-000000000002',
            'CREATED', repeat('9', 64), repeat('9', 32)
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
    IF (SELECT count(*) FROM public.workflow_instance) <> 1
       OR (SELECT count(*) FROM public.node_instance) <> 1
       OR (SELECT count(*) FROM public.task_execution) <> 0
       OR (SELECT count(*) FROM public.schedule_occurrence) <> 1 THEN
        RAISE EXCEPTION 'platform administrator tenant B isolation drifted';
    END IF;
    IF EXISTS (
        SELECT FROM public.workflow_instance
         WHERE id = '019fb1d7-d968-8601-8601-000000000001'
    ) THEN
        RAISE EXCEPTION 'tenant B context exposed tenant A runtime rows';
    END IF;
END;
$test$;
COMMIT;

SELECT 'G2-06 platform-admin RLS, least-privilege, and guarded finalization tests PASS' AS result;
