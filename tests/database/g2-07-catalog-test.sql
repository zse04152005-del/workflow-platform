\set ON_ERROR_STOP on

DO $test$
DECLARE
    actual_names text[];
    reliability_tables constant text[] := ARRAY[
        'outbox_event', 'processed_event', 'idempotency_record',
        'dead_letter', 'worker_lease'
    ];
    mutable_tables constant text[] := ARRAY[
        'outbox_event', 'idempotency_record', 'dead_letter', 'worker_lease'
    ];
    guard_functions constant text[] := ARRAY[
        'guard_outbox_event_transition',
        'guard_processed_event_immutability',
        'guard_idempotency_record_transition',
        'guard_dead_letter_transition',
        'guard_worker_lease_transition'
    ];
BEGIN
    SELECT array_agg(table_name ORDER BY table_name)
      INTO actual_names
      FROM information_schema.tables
     WHERE table_schema = 'public'
       AND table_type = 'BASE TABLE'
       AND table_name <> 'flyway_schema_history';
    IF actual_names IS DISTINCT FROM ARRAY[
        'connector',
        'dead_letter',
        'idempotency_record',
        'membership',
        'node_instance',
        'outbox_event',
        'processed_event',
        'project',
        'role',
        'role_binding',
        'schedule_occurrence',
        'secret_reference',
        'task_execution',
        'tenant',
        'trigger_config',
        'worker_lease',
        'workflow_definition',
        'workflow_instance',
        'workflow_version'
    ]::text[] THEN
        RAISE EXCEPTION 'unexpected V1+V2+V3+V4 table set: %', actual_names;
    END IF;

    SELECT array_agg(version ORDER BY installed_rank)
      INTO actual_names
      FROM public.flyway_schema_history
     WHERE success;
    IF actual_names IS DISTINCT FROM ARRAY['1', '2', '3', '4']::text[] THEN
        RAISE EXCEPTION 'Flyway history must contain exactly successful V1-V4: %', actual_names;
    END IF;

    IF (
        SELECT count(*)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (reliability_tables)
    ) <> 77 THEN
        RAISE EXCEPTION 'G2-07 expanded column count must remain 77';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname = ANY (reliability_tables)
           AND c.relrowsecurity
           AND c.relforcerowsecurity
    ) <> 5 THEN
        RAISE EXCEPTION 'all five G2-07 tables must enable and force RLS';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = ANY (reliability_tables)
           AND cmd = 'ALL'
           AND roles = ARRAY['public']::name[]
           AND qual = '(tenant_id = app_tenant_id())'
           AND with_check = '(tenant_id = app_tenant_id())'
    ) <> 5 THEN
        RAISE EXCEPTION 'G2-07 ALL-command tenant policies drifted';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = ANY (ARRAY[
               'public.outbox_event'::regclass,
               'public.processed_event'::regclass,
               'public.idempotency_record'::regclass,
               'public.dead_letter'::regclass,
               'public.worker_lease'::regclass
           ])
           AND confdeltype = 'r'
    ) <> 8 THEN
        RAISE EXCEPTION 'all eight G2-07 foreign keys must use ON DELETE RESTRICT';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_constraint
         WHERE contype = 'c'
           AND conrelid = ANY (ARRAY[
               'public.outbox_event'::regclass,
               'public.processed_event'::regclass,
               'public.idempotency_record'::regclass,
               'public.dead_letter'::regclass,
               'public.worker_lease'::regclass
           ])
    ) <> 25 THEN
        RAISE EXCEPTION 'all 25 G2-07 CHECK constraints must be installed';
    END IF;

    IF NOT EXISTS (
        SELECT
          FROM pg_constraint
         WHERE conname = 'uq_task_execution_dead_letter_binding'
           AND conrelid = 'public.task_execution'::regclass
           AND contype = 'u'
           AND pg_get_constraintdef(oid) =
               'UNIQUE (tenant_id, id, node_instance_id)'
    ) THEN
        RAISE EXCEPTION 'task/attempt dead-letter binding UNIQUE drifted';
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_constraint
         WHERE conname = 'fk_dead_letter_execution'
           AND conrelid = 'public.dead_letter'::regclass
           AND pg_get_constraintdef(oid) =
               'FOREIGN KEY (tenant_id, task_execution_id, node_instance_id) REFERENCES task_execution(tenant_id, id, node_instance_id) ON DELETE RESTRICT'
    ) THEN
        RAISE EXCEPTION 'dead-letter attempt/node composite FK drifted';
    END IF;

    SELECT array_agg(indexname ORDER BY indexname)
      INTO actual_names
      FROM pg_indexes
     WHERE schemaname = 'public'
       AND tablename = ANY (reliability_tables)
       AND indexname LIKE 'idx_%';
    IF actual_names IS DISTINCT FROM ARRAY[
        'idx_dead_letter_execution',
        'idx_dead_letter_node',
        'idx_dead_letter_open',
        'idx_idempotency_record_expiry',
        'idx_idempotency_record_processing',
        'idx_outbox_event_publish_lease',
        'idx_outbox_event_tenant_created',
        'idx_outbox_event_unpublished',
        'idx_processed_event_retention',
        'idx_processed_event_tenant',
        'idx_worker_lease_expiry',
        'idx_worker_lease_node'
    ]::text[] THEN
        RAISE EXCEPTION 'G2-07 explicit idx_ index set drift: %', actual_names;
    END IF;

    IF position(
        '(tenant_id, next_publish_at, id)'
        IN pg_get_indexdef('public.idx_outbox_event_unpublished'::regclass)
    ) = 0 OR NOT EXISTS (
        SELECT
          FROM pg_index
         WHERE indexrelid = 'public.idx_outbox_event_unpublished'::regclass
           AND indpred IS NOT NULL
           AND pg_get_expr(indpred, indrelid) = '(published_at IS NULL)'
    ) THEN
        RAISE EXCEPTION 'Outbox due index lost tenant-leading unpublished contract';
    END IF;
    IF position(
        '(tenant_id, publish_lease_until, id)'
        IN pg_get_indexdef('public.idx_outbox_event_publish_lease'::regclass)
    ) = 0 OR NOT EXISTS (
        SELECT
          FROM pg_index
         WHERE indexrelid = 'public.idx_outbox_event_publish_lease'::regclass
           AND indpred IS NOT NULL
           AND pg_get_expr(indpred, indrelid) LIKE '%publisher_id IS NOT NULL%'
    ) THEN
        RAISE EXCEPTION 'Outbox lease-recovery index drifted';
    END IF;
    IF position(
        '(tenant_id, expires_at, id)'
        IN pg_get_indexdef('public.idx_worker_lease_expiry'::regclass)
    ) = 0 OR NOT EXISTS (
        SELECT
          FROM pg_index
         WHERE indexrelid = 'public.idx_worker_lease_expiry'::regclass
           AND indpred IS NOT NULL
           AND pg_get_expr(indpred, indrelid) LIKE '%ACTIVE%'
    ) THEN
        RAISE EXCEPTION 'Worker expiry index lost tenant-leading ACTIVE-only contract';
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_index
         WHERE indexrelid = 'public.uq_worker_lease_active_node'::regclass
           AND indisunique
           AND indpred IS NOT NULL
           AND pg_get_expr(indpred, indrelid) = '((status)::text = ''ACTIVE''::text)'
    ) THEN
        RAISE EXCEPTION 'one-active-lease-per-node partial uniqueness drifted';
    END IF;

    IF (
        SELECT count(DISTINCT table_name)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (reliability_tables)
           AND column_name = 'id'
           AND data_type = 'uuid'
           AND is_nullable = 'NO'
    ) <> 5 THEN
        RAISE EXCEPTION 'all five G2-07 identifiers must be non-null UUID';
    END IF;
    IF EXISTS (
        SELECT
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (reliability_tables)
           AND data_type LIKE 'timestamp%'
           AND (data_type <> 'timestamp with time zone' OR datetime_precision <> 6)
    ) THEN
        RAISE EXCEPTION 'G2-07 lifecycle timestamps must be timestamptz(6)';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles owner_role ON owner_role.oid = c.relowner
         WHERE n.nspname = 'public'
           AND c.relname = ANY (reliability_tables)
           AND owner_role.rolname = 'workflow_migrator'
    ) <> 5 THEN
        RAISE EXCEPTION 'workflow_migrator must own all five G2-07 tables';
    END IF;

    IF EXISTS (
        SELECT
          FROM unnest(mutable_tables) AS table_name
         WHERE NOT has_table_privilege(
                   'workflow_app', format('public.%I', table_name), 'SELECT,INSERT,UPDATE'
               )
            OR NOT has_table_privilege(
                   'workflow_platform_admin',
                   format('public.%I', table_name),
                   'SELECT,INSERT,UPDATE'
               )
            OR has_table_privilege('workflow_app', format('public.%I', table_name), 'DELETE')
            OR has_table_privilege(
                   'workflow_platform_admin', format('public.%I', table_name), 'DELETE'
               )
    ) THEN
        RAISE EXCEPTION 'G2-07 mutable-table grants drifted';
    END IF;
    IF NOT has_table_privilege(
        'workflow_app', 'public.processed_event', 'SELECT,INSERT'
    ) OR NOT has_table_privilege(
        'workflow_platform_admin', 'public.processed_event', 'SELECT,INSERT'
    ) OR has_table_privilege(
        'workflow_app', 'public.processed_event', 'UPDATE,DELETE'
    ) OR has_table_privilege(
        'workflow_platform_admin', 'public.processed_event', 'UPDATE,DELETE'
    ) THEN
        RAISE EXCEPTION 'processed_event append-only grants drifted';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
          JOIN pg_roles owner_role ON owner_role.oid = p.proowner
         WHERE n.nspname = 'public'
           AND p.proname = ANY (guard_functions)
           AND pg_get_function_identity_arguments(p.oid) = ''
           AND p.prorettype = 'trigger'::regtype
           AND NOT p.prosecdef
           AND p.provolatile = 'v'
           AND p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp']::text[]
           AND owner_role.rolname = 'workflow_migrator'
    ) <> 5 THEN
        RAISE EXCEPTION 'G2-07 trigger-function safety contracts drifted';
    END IF;
    IF EXISTS (
        SELECT
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
          CROSS JOIN LATERAL aclexplode(
              COALESCE(p.proacl, acldefault('f', p.proowner))
          ) AS acl
         WHERE n.nspname = 'public'
           AND p.proname = ANY (guard_functions)
           AND acl.grantee = 0
           AND acl.privilege_type = 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'a G2-07 guard function is executable by PUBLIC';
    END IF;
    IF EXISTS (
        SELECT
          FROM unnest(guard_functions) AS function_name
         WHERE has_function_privilege(
                   'workflow_app', format('public.%I()', function_name), 'EXECUTE'
               )
            OR has_function_privilege(
                   'workflow_platform_admin',
                   format('public.%I()', function_name),
                   'EXECUTE'
               )
    ) THEN
        RAISE EXCEPTION 'runtime roles can execute a G2-07 guard directly';
    END IF;

    SELECT array_agg(tgname ORDER BY tgname)
      INTO actual_names
      FROM pg_trigger
     WHERE tgrelid = ANY (ARRAY[
               'public.outbox_event'::regclass,
               'public.processed_event'::regclass,
               'public.idempotency_record'::regclass,
               'public.dead_letter'::regclass,
               'public.worker_lease'::regclass
           ])
       AND NOT tgisinternal;
    IF actual_names IS DISTINCT FROM ARRAY[
        'dead_letter_transition_guard',
        'idempotency_record_transition_guard',
        'outbox_event_transition_guard',
        'processed_event_immutability_guard',
        'worker_lease_transition_guard'
    ]::text[] THEN
        RAISE EXCEPTION 'G2-07 trigger set drift: %', actual_names;
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_trigger
         WHERE tgrelid = 'public.worker_lease'::regclass
           AND tgname = 'worker_lease_transition_guard'
           AND position('BEFORE INSERT OR UPDATE' IN pg_get_triggerdef(oid)) > 0
           AND position('guard_worker_lease_transition()' IN pg_get_triggerdef(oid)) > 0
    ) OR NOT EXISTS (
        SELECT
          FROM pg_trigger
         WHERE tgrelid = 'public.processed_event'::regclass
           AND tgname = 'processed_event_immutability_guard'
           AND position('BEFORE UPDATE' IN pg_get_triggerdef(oid)) > 0
           AND position('guard_processed_event_immutability()' IN pg_get_triggerdef(oid)) > 0
    ) THEN
        RAISE EXCEPTION 'G2-07 trigger event contracts drifted';
    END IF;

    PERFORM public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
    IF NOT EXISTS (
        SELECT FROM public.workflow_instance
         WHERE id = '019fb1d7-d968-8601-8601-000000000001'
    ) OR NOT EXISTS (
        SELECT FROM public.task_execution
         WHERE id = '019fb1d7-d968-8801-8801-000000000001'
           AND status = 'SUCCEEDED'
    ) THEN
        RAISE EXCEPTION 'tenant A V3 fixture was lost during V3-to-V4 upgrade';
    END IF;
    PERFORM public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
    IF NOT EXISTS (
        SELECT FROM public.workflow_instance
         WHERE id = '019fb1d7-d968-8602-8602-000000000002'
    ) OR NOT EXISTS (
        SELECT FROM public.node_instance
         WHERE id = '019fb1d7-d968-8702-8702-000000000002'
    ) THEN
        RAISE EXCEPTION 'tenant B V3 fixture was lost during V3-to-V4 upgrade';
    END IF;
END;
$test$;

-- FORCE RLS applies to the owner; lifecycle guards also protect against owner rewrites.
BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
BEGIN
    BEGIN
        UPDATE public.outbox_event
           SET next_publish_at = next_publish_at + interval '1 minute',
               version = version + 1
         WHERE id = '019fb1d7-d968-9001-9001-000000000001';
        RAISE EXCEPTION 'table owner rewrote published Outbox history';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        UPDATE public.processed_event
           SET result_digest = repeat('0', 64)
         WHERE id = '019fb1d7-d968-9101-9101-000000000001';
        RAISE EXCEPTION 'table owner rewrote processed-event history';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        UPDATE public.idempotency_record
           SET response_status = 202,
               version = version + 1
         WHERE id = '019fb1d7-d968-9201-9201-000000000001';
        RAISE EXCEPTION 'table owner rewrote terminal idempotency history';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        UPDATE public.dead_letter
           SET error_summary_redacted = 'owner rewrite',
               version = version + 1
         WHERE id = '019fb1d7-d968-9301-9301-000000000001';
        RAISE EXCEPTION 'table owner rewrote disposed dead-letter history';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        UPDATE public.worker_lease
           SET released_at = released_at + interval '1 second',
               version = version + 1
         WHERE id = '019fb1d7-d968-9401-9401-000000000001';
        RAISE EXCEPTION 'table owner rewrote terminal Worker lease history';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
END;
$test$;
ROLLBACK;

SELECT 'G2-07 catalog, upgrade, ownership, grants, indexes, RLS, and guard tests PASS' AS result;
