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
        RAISE EXCEPTION 'platform administrator must see both tenant rows';
    END IF;
    IF EXISTS (SELECT FROM public.project) THEN
        RAISE EXCEPTION 'platform administrator must still set tenant context for tenant-owned tables';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
BEGIN
    IF (SELECT count(*) FROM public.project) <> 1 THEN
        RAISE EXCEPTION 'platform administrator tenant context did not isolate project rows';
    END IF;
END;
$test$;
COMMIT;

SELECT 'G2-04 platform-admin tests PASS' AS result;
