INSERT INTO public.tenant (
    id, created_at, created_by, updated_at, updated_by, version,
    name, normalized_name, status, quota_config
) VALUES
    (
        '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493',
        '2026-01-01T00:00:00Z', 'g2-11', '2026-01-01T00:00:00Z', 'g2-11', 0,
        'Query Plan Tenant', 'query-plan-tenant', 'ACTIVE', '{}'::jsonb
    ),
    (
        '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494',
        '2026-01-01T00:00:00Z', 'g2-11', '2026-01-01T00:00:00Z', 'g2-11', 0,
        'Other Query Tenant', 'other-query-tenant', 'ACTIVE', '{}'::jsonb
    );

INSERT INTO public.project (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    version, name, normalized_name, status
) VALUES (
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70001',
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493',
    '2026-01-01T00:00:00Z', 'g2-11', '2026-01-01T00:00:00Z', 'g2-11',
    0, 'Plan Project', 'plan-project', 'ACTIVE'
);

INSERT INTO public.workflow_definition (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    version, project_id, name, normalized_name, status,
    draft_dsl, draft_revision, draft_checksum
) VALUES (
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70002',
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493',
    '2026-01-01T00:00:00Z', 'g2-11', '2026-01-01T00:00:00Z', 'g2-11',
    0, '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70001',
    'plan-workflow', 'plan-workflow', 'ACTIVE', '{}'::jsonb, 1, repeat('a', 64)
);

INSERT INTO public.workflow_version (
    id, tenant_id, created_at, created_by, definition_id,
    version_no, schema_version, dsl, checksum, executor_catalog_version,
    published_at, published_by
) VALUES (
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70003',
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493',
    '2026-01-01T00:00:00Z', 'g2-11',
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70002',
    1, 1, '{}'::jsonb, repeat('b', 64), 'catalog-v1',
    '2026-01-01T00:00:00Z', 'g2-11'
);

INSERT INTO public.trigger_config (
    id, tenant_id, created_at, created_by, workflow_version_id,
    trigger_key, trigger_type, config, timezone, misfire_policy
) VALUES (
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70004',
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493',
    '2026-01-01T00:00:00Z', 'g2-11',
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70003',
    'plan_cron', 'cron', '{"expression":"0 0 * * *"}'::jsonb, 'UTC', 'skip'
);

INSERT INTO public.workflow_instance (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    version, definition_id, workflow_version_id, business_key, status,
    input_digest, started_at, trace_id
)
SELECT
    ('10000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11',
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11', 0,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70002'::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70003'::uuid,
    'business-' || i, 'RUNNING', repeat('c', 64),
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    lpad(to_hex(i), 32, '0')
FROM generate_series(1, 6000) AS generated(i);

INSERT INTO public.node_instance (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    version, workflow_instance_id, node_id, node_type, status,
    attempt_no, dispatch_count, lease_generation, next_schedule_at
)
SELECT
    ('20000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11',
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11', 0,
    ('10000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    'ready_node', 'http', 'READY', 0, 0, 0,
    '2026-01-01T01:00:00Z'::timestamptz + make_interval(secs => i)
FROM generate_series(1, 6000) AS generated(i);

INSERT INTO public.node_instance (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    version, workflow_instance_id, node_id, node_type, status,
    attempt_no, dispatch_count, lease_generation,
    current_worker_id, lease_expire_at
)
SELECT
    ('30000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11',
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11', 0,
    ('10000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    'running_node', 'http', 'RUNNING', 1, 1, 1,
    'worker-' || mod(i, 100),
    '2026-01-01T01:00:00Z'::timestamptz + make_interval(secs => i)
FROM generate_series(1, 6000) AS generated(i);

INSERT INTO public.node_instance (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    version, workflow_instance_id, node_id, node_type, status,
    attempt_no, dispatch_count, lease_generation
)
SELECT
    ('40000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11',
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11', 0,
    ('10000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    'human_node', 'human_task', 'WAITING_APPROVAL', 0, 0, 0
FROM generate_series(1, 6000) AS generated(i);

INSERT INTO public.schedule_occurrence (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    version, trigger_config_id, scheduled_at, timezone, misfire_policy,
    status
)
SELECT
    ('50000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11',
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11', 0,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb70004'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'UTC', 'skip', 'PENDING'
FROM generate_series(1, 6000) AS generated(i);

INSERT INTO public.outbox_event (
    id, tenant_id, created_at, created_by, aggregate_type, aggregate_id,
    event_type, schema_version, partition_key, payload, payload_digest,
    occurred_at, publish_attempts, next_publish_at, version
)
SELECT
    ('60000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11', 'workflow_instance',
    ('10000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    'workflow.instance.started', 1, 'partition-' || mod(i, 100),
    '{}'::jsonb, repeat('d', 64),
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    0,
    '2026-01-01T01:00:00Z'::timestamptz + make_interval(secs => i),
    0
FROM generate_series(1, 6000) AS generated(i);

INSERT INTO public.human_task (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    version, node_instance_id, candidate_role_code, assignee_subject,
    form_schema, status, timeout_policy, transfer_count
)
SELECT
    ('70000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11',
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11', 0,
    ('40000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    CASE
        WHEN mod(i, 2) = 0 AND mod(i, 100) = 42 THEN 'finance_reviewer'
        WHEN mod(i, 2) = 0 THEN 'role_' || mod(i, 100)
        ELSE NULL
    END,
    CASE WHEN mod(i, 2) = 1 THEN 'user-' || mod(i, 100) ELSE NULL END,
    '{"type":"object"}'::jsonb, 'WAITING', 'NONE', 0
FROM generate_series(1, 6000) AS generated(i);

INSERT INTO public.dead_letter (
    id, tenant_id, created_at, created_by, updated_at, updated_by,
    version, node_instance_id, category, error_code,
    error_summary_redacted, status, disposition_count
)
SELECT
    ('80000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11',
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11', 0,
    ('40000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    'execution', 'WORKFLOW_NODE_FAILED', 'Redacted test failure',
    'OPEN', 0
FROM generate_series(1, 6000) AS generated(i);

INSERT INTO public.audit_log (
    id, tenant_id, created_at, created_by, actor_type, actor_id,
    action, resource_type, resource_id, occurred_at, trace_id
)
SELECT
    ('90000000-0000-7000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493'::uuid,
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    'g2-11', 'SYSTEM', 'g2-11-fixture',
    'workflow.instance_started', 'workflow_instance',
    ('10000000-0000-7000-8000-' || lpad(i::text, 12, '0')),
    '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => i),
    lpad(to_hex(i), 32, '0')
FROM generate_series(1, 6000) AS generated(i);

ANALYZE public.workflow_instance;
ANALYZE public.node_instance;
ANALYZE public.schedule_occurrence;
ANALYZE public.outbox_event;
ANALYZE public.human_task;
ANALYZE public.dead_letter;
ANALYZE public.audit_log;
