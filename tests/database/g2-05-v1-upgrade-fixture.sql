\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'upgrade fixture must start without tenant context';
    END IF;
    IF EXISTS (SELECT FROM public.tenant) OR EXISTS (SELECT FROM public.project) THEN
        RAISE EXCEPTION 'V1 tenant data must fail closed without tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
INSERT INTO public.tenant (
    id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a',
    'oidc:upgrade-admin-a',
    'Upgrade Tenant A',
    'upgrade-tenant-a',
    'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b01-8b01-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a',
    'oidc:upgrade-admin-a',
    'Upgrade Project A',
    'upgrade-project-a',
    'ACTIVE'
);
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
INSERT INTO public.tenant (
    id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b',
    'oidc:upgrade-admin-b',
    'Upgrade Tenant B',
    'upgrade-tenant-b',
    'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b02-8b02-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b',
    'oidc:upgrade-admin-b',
    'Upgrade Project B',
    'upgrade-project-b',
    'ACTIVE'
);
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'transaction-local context leaked from V1 upgrade fixture';
    END IF;
    IF EXISTS (SELECT FROM public.tenant) OR EXISTS (SELECT FROM public.project) THEN
        RAISE EXCEPTION 'V1 upgrade fixture became visible without tenant context';
    END IF;
END;
$test$;

SELECT 'G2-05 V1 upgrade fixture PASS' AS result;
