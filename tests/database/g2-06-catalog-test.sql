\set ON_ERROR_STOP on

DO $test$
DECLARE
    actual_names text[];
    runtime_tables constant text[] := ARRAY[
        'workflow_instance', 'node_instance', 'task_execution', 'schedule_occurrence'
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
        'membership',
        'node_instance',
        'project',
        'role',
        'role_binding',
        'schedule_occurrence',
        'secret_reference',
        'task_execution',
        'tenant',
        'trigger_config',
        'workflow_definition',
        'workflow_instance',
        'workflow_version'
    ]::text[] THEN
        RAISE EXCEPTION 'unexpected V1+V2+V3 table set: %', actual_names;
    END IF;

    SELECT array_agg(version ORDER BY installed_rank)
      INTO actual_names
      FROM public.flyway_schema_history
     WHERE success;
    IF actual_names IS DISTINCT FROM ARRAY['1', '2', '3']::text[] THEN
        RAISE EXCEPTION 'Flyway history must contain exactly successful V1, V2, V3: %', actual_names;
    END IF;

    IF (
        SELECT count(*)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (runtime_tables)
    ) <> 69 THEN
        RAISE EXCEPTION 'G2-06 expanded column count must remain 69';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname = ANY (runtime_tables)
           AND c.relrowsecurity
           AND c.relforcerowsecurity
    ) <> 4 THEN
        RAISE EXCEPTION 'all four G2-06 tables must enable and force RLS';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = ANY (runtime_tables)
           AND cmd = 'ALL'
           AND roles = ARRAY['public']::name[]
           AND qual = '(tenant_id = app_tenant_id())'
           AND with_check = '(tenant_id = app_tenant_id())'
    ) <> 4 THEN
        RAISE EXCEPTION 'G2-06 ALL-command tenant policies drifted';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = ANY (ARRAY[
               'public.workflow_instance'::regclass,
               'public.node_instance'::regclass,
               'public.task_execution'::regclass,
               'public.schedule_occurrence'::regclass
           ])
           AND confdeltype = 'r'
    ) <> 10 THEN
        RAISE EXCEPTION 'all ten G2-06 foreign keys must use ON DELETE RESTRICT';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_constraint
         WHERE contype = 'c'
           AND conrelid = ANY (ARRAY[
               'public.workflow_instance'::regclass,
               'public.node_instance'::regclass,
               'public.task_execution'::regclass,
               'public.schedule_occurrence'::regclass
           ])
    ) <> 26 THEN
        RAISE EXCEPTION 'all 26 G2-06 CHECK constraints must be installed';
    END IF;

    IF NOT EXISTS (
        SELECT
          FROM pg_constraint
         WHERE conname = 'uq_workflow_version_instance_binding'
           AND conrelid = 'public.workflow_version'::regclass
           AND contype = 'u'
           AND pg_get_constraintdef(oid) = 'UNIQUE (tenant_id, id, definition_id)'
    ) THEN
        RAISE EXCEPTION 'definition/version instance binding UNIQUE drifted';
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_constraint
         WHERE conname = 'uq_trigger_config_schedule_binding'
           AND conrelid = 'public.trigger_config'::regclass
           AND contype = 'u'
           AND pg_get_constraintdef(oid) =
               'UNIQUE (tenant_id, id, timezone, misfire_policy)'
    ) THEN
        RAISE EXCEPTION 'Cron trigger snapshot binding UNIQUE drifted';
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_constraint
         WHERE conname = 'fk_workflow_instance_version'
           AND conrelid = 'public.workflow_instance'::regclass
           AND pg_get_constraintdef(oid) =
               'FOREIGN KEY (tenant_id, workflow_version_id, definition_id) REFERENCES workflow_version(tenant_id, id, definition_id) ON DELETE RESTRICT'
    ) THEN
        RAISE EXCEPTION 'workflow instance definition/version FK binding drifted';
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_constraint
         WHERE conname = 'fk_schedule_occurrence_trigger'
           AND conrelid = 'public.schedule_occurrence'::regclass
           AND pg_get_constraintdef(oid) =
               'FOREIGN KEY (tenant_id, trigger_config_id, timezone, misfire_policy) REFERENCES trigger_config(tenant_id, id, timezone, misfire_policy) ON DELETE RESTRICT'
    ) THEN
        RAISE EXCEPTION 'schedule occurrence Cron snapshot FK drifted';
    END IF;

    SELECT array_agg(indexname ORDER BY indexname)
      INTO actual_names
      FROM pg_indexes
     WHERE schemaname = 'public'
       AND tablename = ANY (runtime_tables)
       AND indexname LIKE 'idx_%';
    IF actual_names IS DISTINCT FROM ARRAY[
        'idx_node_instance_lease_expiry',
        'idx_node_instance_schedule',
        'idx_node_instance_workflow',
        'idx_schedule_occurrence_due',
        'idx_schedule_occurrence_instance',
        'idx_schedule_occurrence_trigger',
        'idx_task_execution_node',
        'idx_task_execution_status',
        'idx_workflow_instance_business_key',
        'idx_workflow_instance_definition',
        'idx_workflow_instance_status',
        'idx_workflow_instance_tenant_created',
        'idx_workflow_instance_version'
    ]::text[] THEN
        RAISE EXCEPTION 'G2-06 explicit index set drift: %', actual_names;
    END IF;

    IF position(
        '(tenant_id, status, next_schedule_at, id)'
        IN pg_get_indexdef('public.idx_node_instance_schedule'::regclass)
    ) = 0 OR NOT EXISTS (
        SELECT
          FROM pg_index
         WHERE indexrelid = 'public.idx_node_instance_schedule'::regclass
           AND indpred IS NOT NULL
           AND pg_get_expr(indpred, indrelid) LIKE '%READY%'
           AND pg_get_expr(indpred, indrelid) LIKE '%RETRY_WAIT%'
           AND pg_get_expr(indpred, indrelid) LIKE '%WAITING_TIMER%'
    ) THEN
        RAISE EXCEPTION 'node due index lost tenant-leading partial contract';
    END IF;
    IF position(
        '(tenant_id, lease_expire_at, id)'
        IN pg_get_indexdef('public.idx_node_instance_lease_expiry'::regclass)
    ) = 0 OR NOT EXISTS (
        SELECT
          FROM pg_index
         WHERE indexrelid = 'public.idx_node_instance_lease_expiry'::regclass
           AND indpred IS NOT NULL
           AND pg_get_expr(indpred, indrelid) LIKE '%DISPATCHED%'
           AND pg_get_expr(indpred, indrelid) LIKE '%RUNNING%'
    ) THEN
        RAISE EXCEPTION 'node lease index lost tenant-leading partial contract';
    END IF;
    IF position(
        '(tenant_id, status, scheduled_at, id)'
        IN pg_get_indexdef('public.idx_schedule_occurrence_due'::regclass)
    ) = 0 OR NOT EXISTS (
        SELECT
          FROM pg_index
         WHERE indexrelid = 'public.idx_schedule_occurrence_due'::regclass
           AND indpred IS NOT NULL
           AND pg_get_expr(indpred, indrelid) LIKE '%PENDING%'
           AND pg_get_expr(indpred, indrelid) NOT LIKE '%CLAIMED%'
    ) THEN
        RAISE EXCEPTION 'Cron due index lost tenant-leading PENDING-only contract';
    END IF;
    IF position(
        'timezone, misfire_policy, scheduled_at DESC'
        IN pg_get_indexdef('public.idx_schedule_occurrence_trigger'::regclass)
    ) = 0 THEN
        RAISE EXCEPTION 'Cron snapshot history index ordering drifted';
    END IF;

    IF (
        SELECT count(DISTINCT table_name)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (runtime_tables)
           AND column_name = 'id'
           AND data_type = 'uuid'
           AND is_nullable = 'NO'
    ) <> 4 THEN
        RAISE EXCEPTION 'all four G2-06 identifiers must be non-null UUID';
    END IF;
    IF EXISTS (
        SELECT
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (runtime_tables)
           AND column_name IN (
               'created_at', 'updated_at', 'started_at', 'completed_at',
               'finished_at', 'next_schedule_at', 'lease_expire_at',
               'scheduled_at', 'claimed_at'
           )
           AND (data_type <> 'timestamp with time zone' OR datetime_precision <> 6)
    ) THEN
        RAISE EXCEPTION 'G2-06 lifecycle timestamps must be timestamptz(6)';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles owner_role ON owner_role.oid = c.relowner
         WHERE n.nspname = 'public'
           AND c.relname = ANY (runtime_tables)
           AND owner_role.rolname = 'workflow_migrator'
    ) <> 4 THEN
        RAISE EXCEPTION 'workflow_migrator must own all four G2-06 tables';
    END IF;

    IF EXISTS (
        SELECT
          FROM unnest(runtime_tables) AS table_name
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
        RAISE EXCEPTION 'G2-06 runtime table grants drifted';
    END IF;

    IF NOT EXISTS (
        SELECT
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
          JOIN pg_roles owner_role ON owner_role.oid = p.proowner
         WHERE n.nspname = 'public'
           AND p.proname = 'guard_task_execution_finalization'
           AND pg_get_function_identity_arguments(p.oid) = ''
           AND p.prorettype = 'trigger'::regtype
           AND NOT p.prosecdef
           AND p.provolatile = 'v'
           AND p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp']::text[]
           AND owner_role.rolname = 'workflow_migrator'
    ) THEN
        RAISE EXCEPTION 'task finalization trigger function safety contract drifted';
    END IF;
    IF EXISTS (
        SELECT
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
          CROSS JOIN LATERAL aclexplode(
              COALESCE(p.proacl, acldefault('f', p.proowner))
          ) AS acl
         WHERE n.nspname = 'public'
           AND p.proname = 'guard_task_execution_finalization'
           AND acl.grantee = 0
           AND acl.privilege_type = 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'task finalization trigger function is executable by PUBLIC';
    END IF;
    IF has_function_privilege(
        'workflow_app', 'public.guard_task_execution_finalization()', 'EXECUTE'
    ) OR has_function_privilege(
        'workflow_platform_admin',
        'public.guard_task_execution_finalization()',
        'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'runtime roles can execute the task guard directly';
    END IF;

    SELECT array_agg(tgname ORDER BY tgname)
      INTO actual_names
      FROM pg_trigger
     WHERE tgrelid = 'public.task_execution'::regclass
       AND NOT tgisinternal;
    IF actual_names IS DISTINCT FROM ARRAY['task_execution_finalization_guard']::text[] THEN
        RAISE EXCEPTION 'task finalization trigger set drift: %', actual_names;
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_trigger
         WHERE tgrelid = 'public.task_execution'::regclass
           AND tgname = 'task_execution_finalization_guard'
           AND NOT tgisinternal
           AND tgtype = 31
           AND position(
               'guard_task_execution_finalization()' IN pg_get_triggerdef(oid)
           ) > 0
    ) THEN
        RAISE EXCEPTION 'task INSERT/UPDATE/DELETE guard event contract drifted';
    END IF;

    PERFORM public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
    IF (SELECT count(*) FROM public.tenant) <> 1
       OR (SELECT count(*) FROM public.project) <> 1
       OR (SELECT count(*) FROM public.workflow_definition) <> 2
       OR (SELECT count(*) FROM public.workflow_version) <> 2
       OR (SELECT count(*) FROM public.trigger_config) <> 2
       OR (SELECT count(*) FROM public.connector) <> 1
       OR (SELECT count(*) FROM public.secret_reference) <> 1 THEN
        RAISE EXCEPTION 'tenant A V2 fixture was lost during V2-to-V3 upgrade';
    END IF;
    PERFORM public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
    IF (SELECT count(*) FROM public.tenant) <> 1
       OR (SELECT count(*) FROM public.project) <> 1
       OR (SELECT count(*) FROM public.workflow_definition) <> 1
       OR (SELECT count(*) FROM public.workflow_version) <> 1
       OR (SELECT count(*) FROM public.trigger_config) <> 1
       OR (SELECT count(*) FROM public.connector) <> 1
       OR (SELECT count(*) FROM public.secret_reference) <> 1 THEN
        RAISE EXCEPTION 'tenant B V2 fixture was lost during V2-to-V3 upgrade';
    END IF;
END;
$test$;

-- FORCE RLS still applies to the table owner; the guard protects terminal history and identity.
BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
BEGIN
    BEGIN
        UPDATE public.task_execution
           SET status = 'FAILED',
               error_code = 'OWNER_REWRITE',
               error_summary_redacted = 'must fail',
               version = version + 1
         WHERE id = '019fb1d7-d968-8801-8801-000000000001';
        RAISE EXCEPTION 'table owner rewrote terminal task history';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        DELETE FROM public.task_execution
         WHERE id = '019fb1d7-d968-8801-8801-000000000001';
        RAISE EXCEPTION 'table owner deleted terminal task history';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        UPDATE public.task_execution
           SET status = 'FAILED',
               finished_at = transaction_timestamp(),
               error_code = 'OWNER_IDENTITY',
               error_summary_redacted = 'must fail',
               node_instance_id = '019fb1d7-d968-8702-8702-000000000002',
               updated_at = transaction_timestamp(),
               version = version + 1
         WHERE id = '019fb1d7-d968-8802-8802-000000000002';
        RAISE EXCEPTION 'table owner changed task identity during finalization';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        INSERT INTO public.task_execution (
            id, tenant_id, created_by, updated_by, node_instance_id, attempt_no,
            execution_id, status, started_at, finished_at, input_digest
        ) VALUES (
            '019fb1d7-d968-8896-8896-000000000096',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'owner', 'owner', '019fb1d7-d968-8701-8701-000000000001', 96,
            '019fb1d7-d968-8897-8897-000000000097', 'SUCCEEDED',
            transaction_timestamp(), transaction_timestamp(), repeat('8', 64)
        );
        RAISE EXCEPTION 'table owner inserted terminal task without RUNNING phase';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
END;
$test$;
ROLLBACK;

SELECT 'G2-06 catalog, upgrade, ownership, grants, indexes, RLS, and guard tests PASS' AS result;
