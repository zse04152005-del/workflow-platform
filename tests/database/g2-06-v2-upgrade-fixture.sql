\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'G2-06 upgrade fixture must start without tenant context';
    END IF;
    IF EXISTS (SELECT FROM public.tenant)
       OR EXISTS (SELECT FROM public.workflow_definition) THEN
        RAISE EXCEPTION 'V2 data must fail closed without tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
INSERT INTO public.tenant (
    id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
    'Runtime Upgrade Tenant A', 'runtime-upgrade-tenant-a', 'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b01-8b01-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
    'Runtime Upgrade Project A', 'runtime-upgrade-project-a', 'ACTIVE'
);
INSERT INTO public.workflow_definition (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, status, draft_dsl, draft_revision, draft_checksum
) VALUES
    (
        '019fb1d7-d968-8101-8101-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
        '019fb1d7-d968-7b01-8b01-000000000001',
        'Order orchestration', 'order-orchestration', 'ACTIVE',
        '{"nodes":[]}'::jsonb, 1, repeat('a', 64)
    ),
    (
        '019fb1d7-d968-8103-8103-000000000003',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
        '019fb1d7-d968-7b01-8b01-000000000001',
        'Refund orchestration', 'refund-orchestration', 'ACTIVE',
        '{"nodes":[]}'::jsonb, 1, repeat('b', 64)
    );
INSERT INTO public.workflow_version (
    id, tenant_id, created_by, definition_id, version_no, schema_version,
    dsl, checksum, executor_catalog_version, published_at, published_by
) VALUES
    (
        '019fb1d7-d968-8201-8201-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:upgrade-admin-a', '019fb1d7-d968-8101-8101-000000000001',
        1, 1, '{"nodes":[]}'::jsonb, repeat('c', 64), 'catalog-v1',
        transaction_timestamp(), 'oidc:upgrade-admin-a'
    ),
    (
        '019fb1d7-d968-8203-8203-000000000003',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:upgrade-admin-a', '019fb1d7-d968-8103-8103-000000000003',
        1, 1, '{"nodes":[]}'::jsonb, repeat('d', 64), 'catalog-v1',
        transaction_timestamp(), 'oidc:upgrade-admin-a'
    );
INSERT INTO public.trigger_config (
    id, tenant_id, created_by, workflow_version_id, trigger_key,
    trigger_type, config, timezone, misfire_policy
) VALUES
    (
        '019fb1d7-d968-8301-8301-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:upgrade-admin-a', '019fb1d7-d968-8201-8201-000000000001',
        'hourly', 'cron', '{"expression":"0 * * * *"}'::jsonb,
        'Asia/Shanghai', 'fire_once'
    ),
    (
        '019fb1d7-d968-8303-8303-000000000003',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:upgrade-admin-a', '019fb1d7-d968-8201-8201-000000000001',
        'manual_start', 'manual', '{}'::jsonb, NULL, NULL
    );
INSERT INTO public.connector (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, connector_type, endpoint, config, status
) VALUES (
    '019fb1d7-d968-8401-8401-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
    '019fb1d7-d968-7b01-8b01-000000000001',
    'Order API', 'order-api', 'http', 'https://api.example.test/orders',
    '{}'::jsonb, 'ACTIVE'
);
INSERT INTO public.secret_reference (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, provider, external_key, ciphertext, key_version, status
) VALUES (
    '019fb1d7-d968-8501-8501-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
    '019fb1d7-d968-7b01-8b01-000000000001',
    'Order API credential', 'order-api-credential', 'vault',
    'kv/workflow/order-api', NULL, 'vault-v1', 'ACTIVE'
);
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
INSERT INTO public.tenant (
    id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    'Runtime Upgrade Tenant B', 'runtime-upgrade-tenant-b', 'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b02-8b02-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    'Runtime Upgrade Project B', 'runtime-upgrade-project-b', 'ACTIVE'
);
INSERT INTO public.workflow_definition (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, status, draft_dsl, draft_revision, draft_checksum
) VALUES (
    '019fb1d7-d968-8102-8102-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    '019fb1d7-d968-7b02-8b02-000000000002',
    'Tenant B orchestration', 'tenant-b-orchestration', 'ACTIVE',
    '{"nodes":[]}'::jsonb, 1, repeat('e', 64)
);
INSERT INTO public.workflow_version (
    id, tenant_id, created_by, definition_id, version_no, schema_version,
    dsl, checksum, executor_catalog_version, published_at, published_by
) VALUES (
    '019fb1d7-d968-8202-8202-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', '019fb1d7-d968-8102-8102-000000000002',
    1, 1, '{"nodes":[]}'::jsonb, repeat('f', 64), 'catalog-v1',
    transaction_timestamp(), 'oidc:upgrade-admin-b'
);
INSERT INTO public.trigger_config (
    id, tenant_id, created_by, workflow_version_id, trigger_key,
    trigger_type, config, timezone, misfire_policy
) VALUES (
    '019fb1d7-d968-8302-8302-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', '019fb1d7-d968-8202-8202-000000000002',
    'daily', 'cron', '{"expression":"0 0 * * *"}'::jsonb,
    'UTC', 'skip'
);
INSERT INTO public.connector (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, connector_type, endpoint, config, status
) VALUES (
    '019fb1d7-d968-8402-8402-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    '019fb1d7-d968-7b02-8b02-000000000002',
    'Tenant B API', 'tenant-b-api', 'http', 'https://api.example.test/tenant-b',
    '{}'::jsonb, 'ACTIVE'
);
INSERT INTO public.secret_reference (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, provider, external_key, ciphertext, key_version, status
) VALUES (
    '019fb1d7-d968-8502-8502-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    '019fb1d7-d968-7b02-8b02-000000000002',
    'Tenant B credential', 'tenant-b-credential', 'vault',
    'kv/workflow/tenant-b', NULL, 'vault-v1', 'ACTIVE'
);
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'transaction-local context leaked from V2 upgrade fixture';
    END IF;
    IF EXISTS (SELECT FROM public.tenant)
       OR EXISTS (SELECT FROM public.workflow_definition) THEN
        RAISE EXCEPTION 'V2 fixture became visible without tenant context';
    END IF;
END;
$test$;

SELECT 'G2-06 V2 upgrade fixture PASS' AS result;
