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
        RAISE EXCEPTION 'platform administrator must see both V1 tenant fixtures';
    END IF;
    IF EXISTS (SELECT FROM public.workflow_definition)
       OR EXISTS (SELECT FROM public.workflow_version)
       OR EXISTS (SELECT FROM public.trigger_config)
       OR EXISTS (SELECT FROM public.connector)
       OR EXISTS (SELECT FROM public.secret_reference) THEN
        RAISE EXCEPTION 'platform administrator bypassed G2-05 tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
BEGIN
    IF (SELECT count(*) FROM public.workflow_definition) <> 1
       OR (SELECT count(*) FROM public.workflow_version) <> 1
       OR (SELECT count(*) FROM public.trigger_config) <> 2
       OR (SELECT count(*) FROM public.connector) <> 3
       OR (SELECT count(*) FROM public.secret_reference) <> 3 THEN
        RAISE EXCEPTION 'platform administrator tenant A isolation drifted';
    END IF;
    IF EXISTS (
        SELECT FROM public.workflow_definition
         WHERE id = '019fb1d7-d968-8102-8102-000000000002'
    ) THEN
        RAISE EXCEPTION 'platform administrator tenant A context exposed tenant B definition';
    END IF;

    BEGIN
        UPDATE public.workflow_version
           SET published_by = 'ops-mutation'
         WHERE id = '019fb1d7-d968-8201-8201-000000000001';
        RAISE EXCEPTION 'platform administrator updated immutable workflow version';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
        DELETE FROM public.connector
         WHERE id = '019fb1d7-d968-8403-8403-000000000003';
        RAISE EXCEPTION 'platform administrator physically deleted connector';
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
    IF (SELECT count(*) FROM public.workflow_definition) <> 1
       OR (SELECT count(*) FROM public.workflow_version) <> 1
       OR (SELECT count(*) FROM public.trigger_config) <> 1
       OR (SELECT count(*) FROM public.connector) <> 1
       OR (SELECT count(*) FROM public.secret_reference) <> 1 THEN
        RAISE EXCEPTION 'platform administrator tenant B isolation drifted';
    END IF;
    IF EXISTS (
        SELECT FROM public.workflow_definition
         WHERE id = '019fb1d7-d968-8101-8101-000000000001'
    ) THEN
        RAISE EXCEPTION 'platform administrator tenant B context exposed tenant A definition';
    END IF;
END;
$test$;
COMMIT;

SELECT 'G2-05 platform-admin RLS and least-privilege tests PASS' AS result;
