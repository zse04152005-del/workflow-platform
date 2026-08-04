\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'G2-07 upgrade fixture must start without tenant context';
    END IF;
    IF EXISTS (SELECT FROM public.tenant)
       OR EXISTS (SELECT FROM public.workflow_instance) THEN
        RAISE EXCEPTION 'V3 data must fail closed without tenant context';
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
    'Reliability Upgrade Tenant A', 'reliability-upgrade-tenant-a', 'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b01-8b01-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
    'Reliability Upgrade Project A', 'reliability-upgrade-project-a', 'ACTIVE'
);
INSERT INTO public.workflow_definition (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, status, draft_dsl, draft_revision, draft_checksum
) VALUES (
    '019fb1d7-d968-8101-8101-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:upgrade-admin-a', 'oidc:upgrade-admin-a',
    '019fb1d7-d968-7b01-8b01-000000000001',
    'Order orchestration', 'order-orchestration', 'ACTIVE',
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
    'worker-a-01', transaction_timestamp() + interval '10 minutes'
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
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
INSERT INTO public.tenant (
    id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    'Reliability Upgrade Tenant B', 'reliability-upgrade-tenant-b', 'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b02-8b02-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:upgrade-admin-b', 'oidc:upgrade-admin-b',
    'Reliability Upgrade Project B', 'reliability-upgrade-project-b', 'ACTIVE'
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
    status, input_digest, trace_id
) VALUES (
    '019fb1d7-d968-8602-8602-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:runtime-b', 'oidc:runtime-b',
    '019fb1d7-d968-8102-8102-000000000002',
    '019fb1d7-d968-8202-8202-000000000002',
    'CREATED', repeat('5', 64), repeat('5', 32)
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
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'transaction-local context leaked from V3 upgrade fixture';
    END IF;
    IF EXISTS (SELECT FROM public.tenant)
       OR EXISTS (SELECT FROM public.workflow_instance) THEN
        RAISE EXCEPTION 'V3 fixture became visible without tenant context';
    END IF;
END;
$test$;

SELECT 'G2-07 V3 upgrade fixture PASS' AS result;
