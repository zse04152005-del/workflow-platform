\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'G2-08 upgrade fixture must start without tenant context';
    END IF;
    IF to_regclass('public.human_task') IS NOT NULL
       OR to_regclass('public.audit_log') IS NOT NULL
       OR to_regclass('public.quota_usage') IS NOT NULL THEN
        RAISE EXCEPTION 'G2-08 tables unexpectedly exist before V5';
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
    'Human Upgrade Tenant A', 'human-upgrade-tenant-a', 'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b01-8b01-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
    'Human Upgrade Project A', 'human-upgrade-project-a', 'ACTIVE'
);
INSERT INTO public.workflow_definition (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, status, draft_dsl, draft_revision, draft_checksum
) VALUES (
    '019fb1d7-d968-8101-8101-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
    '019fb1d7-d968-7b01-8b01-000000000001',
    'Refund approval', 'refund-approval', 'ACTIVE',
    '{"nodes":[]}'::jsonb, 1, repeat('a', 64)
);
INSERT INTO public.workflow_version (
    id, tenant_id, created_by, definition_id, version_no, schema_version,
    dsl, checksum, executor_catalog_version, published_at, published_by
) VALUES (
    '019fb1d7-d968-8201-8201-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a', '019fb1d7-d968-8101-8101-000000000001',
    1, 1, '{"nodes":[]}'::jsonb, repeat('b', 64), 'catalog-v1',
    transaction_timestamp(), 'oidc:upgrade-admin-a'
);
INSERT INTO public.workflow_instance (
    id, tenant_id, created_by, updated_by, definition_id, workflow_version_id,
    business_key, status, input_digest, started_at, trace_id
) VALUES (
    '019fb1d7-d968-8601-8601-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'system:runtime', 'system:runtime',
    '019fb1d7-d968-8101-8101-000000000001',
    '019fb1d7-d968-8201-8201-000000000001',
    'refund-001', 'RUNNING', repeat('1', 64), transaction_timestamp(), repeat('1', 32)
);
INSERT INTO public.node_instance (
    id, tenant_id, created_by, updated_by, workflow_instance_id,
    node_id, node_type, status
) VALUES
    (
        '019fb1d7-d968-8701-8701-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'system:runtime', 'system:runtime',
        '019fb1d7-d968-8601-8601-000000000001',
        'manager_approval', 'human_task', 'WAITING_APPROVAL'
    ),
    (
        '019fb1d7-d968-8702-8702-000000000002',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'system:runtime', 'system:runtime',
        '019fb1d7-d968-8601-8601-000000000001',
        'risk_timeout', 'human_task', 'WAITING_APPROVAL'
    ),
    (
        '019fb1d7-d968-8703-8703-000000000003',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'system:runtime', 'system:runtime',
        '019fb1d7-d968-8601-8601-000000000001',
        'finance_approval', 'human_task', 'WAITING_APPROVAL'
    ),
    (
        '019fb1d7-d968-8704-8704-000000000004',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'system:runtime', 'system:runtime',
        '019fb1d7-d968-8601-8601-000000000001',
        'future_timeout', 'human_task', 'WAITING_APPROVAL'
    );
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
INSERT INTO public.tenant (
    id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    'Human Upgrade Tenant B', 'human-upgrade-tenant-b', 'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b02-8b02-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    'Human Upgrade Project B', 'human-upgrade-project-b', 'ACTIVE'
);
INSERT INTO public.workflow_definition (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, status, draft_dsl, draft_revision, draft_checksum
) VALUES (
    '019fb1d7-d968-8102-8102-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    '019fb1d7-d968-7b02-8b02-000000000002',
    'Tenant B approval', 'tenant-b-approval', 'ACTIVE',
    '{"nodes":[]}'::jsonb, 1, repeat('c', 64)
);
INSERT INTO public.workflow_version (
    id, tenant_id, created_by, definition_id, version_no, schema_version,
    dsl, checksum, executor_catalog_version, published_at, published_by
) VALUES (
    '019fb1d7-d968-8202-8202-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', '019fb1d7-d968-8102-8102-000000000002',
    1, 1, '{"nodes":[]}'::jsonb, repeat('d', 64), 'catalog-v1',
    transaction_timestamp(), 'oidc:upgrade-admin-b'
);
INSERT INTO public.workflow_instance (
    id, tenant_id, created_by, updated_by, definition_id, workflow_version_id,
    business_key, status, input_digest, started_at, trace_id
) VALUES (
    '019fb1d7-d968-8602-8602-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'system:runtime', 'system:runtime',
    '019fb1d7-d968-8102-8102-000000000002',
    '019fb1d7-d968-8202-8202-000000000002',
    'approval-b-001', 'RUNNING', repeat('2', 64), transaction_timestamp(), repeat('2', 32)
);
INSERT INTO public.node_instance (
    id, tenant_id, created_by, updated_by, workflow_instance_id,
    node_id, node_type, status
) VALUES (
    '019fb1d7-d968-8791-8791-000000000091',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'system:runtime', 'system:runtime',
    '019fb1d7-d968-8602-8602-000000000002',
    'tenant_b_approval', 'human_task', 'WAITING_APPROVAL'
);
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'transaction-local context leaked from V4 upgrade fixture';
    END IF;
    IF EXISTS (SELECT FROM public.tenant)
       OR EXISTS (SELECT FROM public.node_instance) THEN
        RAISE EXCEPTION 'V4 fixture became visible without tenant context';
    END IF;
END;
$test$;

SELECT 'G2-08 V4 upgrade fixture PASS' AS result;
