\set ON_ERROR_STOP on

DO $test$
DECLARE
    actual_names text[];
    g2_tables constant text[] := ARRAY['human_task', 'audit_log', 'quota_usage'];
    mutable_tables constant text[] := ARRAY['human_task', 'quota_usage'];
    guard_functions constant text[] := ARRAY[
        'guard_human_task_transition',
        'guard_audit_log_append_only',
        'guard_quota_usage_transition'
    ];
BEGIN
    SELECT array_agg(table_name ORDER BY table_name)
      INTO actual_names
      FROM information_schema.tables
     WHERE table_schema = 'public'
       AND table_type = 'BASE TABLE'
       AND table_name <> 'flyway_schema_history';
    IF actual_names IS DISTINCT FROM ARRAY[
        'audit_log',
        'connector',
        'dead_letter',
        'human_task',
        'idempotency_record',
        'membership',
        'node_instance',
        'outbox_event',
        'processed_event',
        'project',
        'quota_usage',
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
        RAISE EXCEPTION 'unexpected V1+V2+V3+V4+V5 table set: %', actual_names;
    END IF;

    SELECT array_agg(version ORDER BY installed_rank)
      INTO actual_names
      FROM public.flyway_schema_history
     WHERE success;
    IF actual_names IS DISTINCT FROM ARRAY['1', '2', '3', '4', '5']::text[] THEN
        RAISE EXCEPTION 'Flyway history must contain exactly successful V1-V5: %', actual_names;
    END IF;

    IF (
        SELECT count(*)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (g2_tables)
    ) <> 49 THEN
        RAISE EXCEPTION 'G2-08 expanded column count must remain 49';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname = ANY (g2_tables)
           AND c.relrowsecurity
           AND c.relforcerowsecurity
    ) <> 3 THEN
        RAISE EXCEPTION 'all three G2-08 tables must enable and force RLS';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename IN ('human_task', 'quota_usage')
           AND cmd = 'ALL'
           AND roles = ARRAY['public']::name[]
           AND qual = '(tenant_id = app_tenant_id())'
           AND with_check = '(tenant_id = app_tenant_id())'
    ) <> 2 THEN
        RAISE EXCEPTION 'human_task/quota_usage strict tenant policies drifted';
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'audit_log'
           AND cmd = 'ALL'
           AND roles = ARRAY['public']::name[]
           AND qual LIKE '%tenant_id = app_tenant_id()%'
           AND qual LIKE '%tenant_id IS NULL%'
           AND qual LIKE '%app_is_platform_admin()%'
           AND with_check LIKE '%tenant_id = app_tenant_id()%'
           AND with_check LIKE '%tenant_id IS NULL%'
           AND with_check LIKE '%app_is_platform_admin()%'
    ) THEN
        RAISE EXCEPTION 'audit_log tenant/platform-row policy drifted';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = ANY (ARRAY[
               'public.human_task'::regclass,
               'public.audit_log'::regclass,
               'public.quota_usage'::regclass
           ])
           AND confdeltype = 'r'
    ) <> 4 THEN
        RAISE EXCEPTION 'all four G2-08 foreign keys must use ON DELETE RESTRICT';
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_constraint
         WHERE conname = 'fk_human_task_node'
           AND conrelid = 'public.human_task'::regclass
           AND pg_get_constraintdef(oid) =
               'FOREIGN KEY (tenant_id, node_instance_id) REFERENCES node_instance(tenant_id, id) ON DELETE RESTRICT'
    ) THEN
        RAISE EXCEPTION 'human_task composite tenant/node FK drifted';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_constraint
         WHERE contype = 'c'
           AND conrelid = ANY (ARRAY[
               'public.human_task'::regclass,
               'public.audit_log'::regclass,
               'public.quota_usage'::regclass
           ])
    ) <> 17 THEN
        RAISE EXCEPTION 'all 17 G2-08 CHECK constraints must be installed';
    END IF;

    SELECT array_agg(indexname ORDER BY indexname)
      INTO actual_names
      FROM pg_indexes
     WHERE schemaname = 'public'
       AND tablename = ANY (g2_tables)
       AND indexname LIKE 'idx_%';
    IF actual_names IS DISTINCT FROM ARRAY[
        'idx_audit_log_resource',
        'idx_audit_log_tenant_time',
        'idx_audit_log_trace',
        'idx_human_task_assignee',
        'idx_human_task_candidate',
        'idx_human_task_deadline',
        'idx_human_task_node',
        'idx_quota_usage_active',
        'idx_quota_usage_retention'
    ]::text[] THEN
        RAISE EXCEPTION 'G2-08 explicit idx_ index set drift: %', actual_names;
    END IF;

    IF position(
        '(tenant_id, deadline_at, id)'
        IN pg_get_indexdef('public.idx_human_task_deadline'::regclass)
    ) = 0 OR NOT EXISTS (
        SELECT
          FROM pg_index
         WHERE indexrelid = 'public.idx_human_task_deadline'::regclass
           AND indpred IS NOT NULL
           AND pg_get_expr(indpred, indrelid) LIKE '%WAITING%'
           AND pg_get_expr(indpred, indrelid) LIKE '%deadline_at IS NOT NULL%'
    ) THEN
        RAISE EXCEPTION 'human timeout index lost tenant-leading WAITING contract';
    END IF;
    IF position(
        '(tenant_id, trace_id, occurred_at DESC, id DESC)'
        IN pg_get_indexdef('public.idx_audit_log_trace'::regclass)
    ) = 0 THEN
        RAISE EXCEPTION 'audit trace index lost tenant-leading stable cursor';
    END IF;
    IF position(
        '(tenant_id, resource_type, window_end, id)'
        IN pg_get_indexdef('public.idx_quota_usage_active'::regclass)
    ) = 0 OR position(
        '(tenant_id, window_end, id)'
        IN pg_get_indexdef('public.idx_quota_usage_retention'::regclass)
    ) = 0 THEN
        RAISE EXCEPTION 'quota lookup/retention indexes lost tenant-leading contract';
    END IF;

    IF (
        SELECT count(DISTINCT table_name)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (g2_tables)
           AND column_name = 'id'
           AND data_type = 'uuid'
           AND is_nullable = 'NO'
    ) <> 3 THEN
        RAISE EXCEPTION 'all three G2-08 identifiers must be non-null UUID';
    END IF;
    IF EXISTS (
        SELECT
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (g2_tables)
           AND data_type LIKE 'timestamp%'
           AND (data_type <> 'timestamp with time zone' OR datetime_precision <> 6)
    ) THEN
        RAISE EXCEPTION 'G2-08 lifecycle timestamps must be timestamptz(6)';
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'audit_log'
           AND column_name = 'tenant_id'
           AND data_type = 'uuid'
           AND is_nullable = 'YES'
    ) THEN
        RAISE EXCEPTION 'audit_log must retain its reviewed nullable platform tenant scope';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles owner_role ON owner_role.oid = c.relowner
         WHERE n.nspname = 'public'
           AND c.relname = ANY (g2_tables)
           AND owner_role.rolname = 'workflow_migrator'
    ) <> 3 THEN
        RAISE EXCEPTION 'workflow_migrator must own all three G2-08 tables';
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
        RAISE EXCEPTION 'G2-08 mutable-table grants drifted';
    END IF;
    IF NOT has_table_privilege('workflow_app', 'public.audit_log', 'SELECT,INSERT')
       OR NOT has_table_privilege(
           'workflow_platform_admin', 'public.audit_log', 'SELECT,INSERT'
       )
       OR has_table_privilege('workflow_app', 'public.audit_log', 'UPDATE,DELETE')
       OR has_table_privilege(
           'workflow_platform_admin', 'public.audit_log', 'UPDATE,DELETE'
       ) THEN
        RAISE EXCEPTION 'audit_log append-only grants drifted';
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
    ) <> 3 THEN
        RAISE EXCEPTION 'G2-08 trigger-function safety contracts drifted';
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
        RAISE EXCEPTION 'a G2-08 guard function is executable by PUBLIC';
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
        RAISE EXCEPTION 'a G2-08 runtime role can execute an internal guard directly';
    END IF;

    SELECT array_agg(tgname ORDER BY tgname)
      INTO actual_names
      FROM pg_trigger
     WHERE tgrelid = ANY (ARRAY[
               'public.human_task'::regclass,
               'public.audit_log'::regclass,
               'public.quota_usage'::regclass
           ])
       AND NOT tgisinternal;
    IF actual_names IS DISTINCT FROM ARRAY[
        'audit_log_append_only_guard',
        'human_task_transition_guard',
        'quota_usage_transition_guard'
    ]::text[] THEN
        RAISE EXCEPTION 'G2-08 trigger set drift: %', actual_names;
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_trigger
         WHERE tgrelid = 'public.audit_log'::regclass
           AND tgname = 'audit_log_append_only_guard'
           AND position('BEFORE INSERT OR DELETE OR UPDATE' IN pg_get_triggerdef(oid)) > 0
           AND position('guard_audit_log_append_only()' IN pg_get_triggerdef(oid)) > 0
    ) OR NOT EXISTS (
        SELECT
          FROM pg_trigger
         WHERE tgrelid = 'public.quota_usage'::regclass
           AND tgname = 'quota_usage_transition_guard'
           AND position('BEFORE INSERT OR UPDATE' IN pg_get_triggerdef(oid)) > 0
           AND position('guard_quota_usage_transition()' IN pg_get_triggerdef(oid)) > 0
    ) THEN
        RAISE EXCEPTION 'G2-08 trigger event contracts drifted';
    END IF;

    IF (SELECT count(*) FROM public.tenant) <> 0 THEN
        RAISE EXCEPTION 'migrator unexpectedly bypassed forced RLS without tenant context';
    END IF;
    PERFORM public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
    IF (SELECT count(*) FROM public.human_task) <> 4
       OR (SELECT count(*) FROM public.audit_log) <> 1
       OR (SELECT count(*) FROM public.quota_usage) <> 2 THEN
        RAISE EXCEPTION 'V4 fixture or G2-08 application evidence was not preserved';
    END IF;

    BEGIN
        UPDATE public.audit_log
           SET reason = 'owner rewrite'
         WHERE id = '019fb1d7-d968-9601-9601-000000000001';
        RAISE EXCEPTION 'table owner bypassed audit append-only trigger';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        DELETE FROM public.audit_log
         WHERE id = '019fb1d7-d968-9601-9601-000000000001';
        RAISE EXCEPTION 'table owner bypassed audit delete guard';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
END;
$test$;

SELECT 'G2-08 catalog, ownership, RLS, index, guard, and least-privilege tests PASS' AS result;
