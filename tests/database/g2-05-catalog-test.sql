\set ON_ERROR_STOP on

DO $test$
DECLARE
    actual_names text[];
    new_tables constant text[] := ARRAY[
        'workflow_definition',
        'workflow_version',
        'trigger_config',
        'connector',
        'secret_reference'
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
        'project',
        'role',
        'role_binding',
        'secret_reference',
        'tenant',
        'trigger_config',
        'workflow_definition',
        'workflow_version'
    ]::text[] THEN
        RAISE EXCEPTION 'unexpected V1+V2 table set: %', actual_names;
    END IF;

    SELECT array_agg(version ORDER BY installed_rank)
      INTO actual_names
      FROM public.flyway_schema_history
     WHERE success;
    IF actual_names IS DISTINCT FROM ARRAY['1', '2']::text[] THEN
        RAISE EXCEPTION 'Flyway history must contain exactly successful V1 and V2 rows: %', actual_names;
    END IF;

    PERFORM public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
    IF (SELECT count(*) FROM public.tenant) <> 1
       OR (SELECT count(*) FROM public.project) <> 1 THEN
        RAISE EXCEPTION 'tenant A V1 fixture was lost during V1-to-V2 upgrade';
    END IF;
    PERFORM public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
    IF (SELECT count(*) FROM public.tenant) <> 1
       OR (SELECT count(*) FROM public.project) <> 1 THEN
        RAISE EXCEPTION 'tenant B V1 fixture was lost during V1-to-V2 upgrade';
    END IF;

    IF (
        SELECT count(*)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (new_tables)
    ) <> 71 THEN
        RAISE EXCEPTION 'G2-05 expanded column count must remain 71';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname = ANY (new_tables)
           AND c.relrowsecurity
           AND c.relforcerowsecurity
    ) <> 5 THEN
        RAISE EXCEPTION 'all five G2-05 tables must enable and force RLS';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = ANY (new_tables)
           AND cmd = 'ALL'
           AND roles = ARRAY['public']::name[]
           AND qual = '(tenant_id = app_tenant_id())'
           AND with_check = '(tenant_id = app_tenant_id())'
    ) <> 5 THEN
        RAISE EXCEPTION 'G2-05 ALL-command tenant policies drifted';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = ANY (ARRAY[
               'public.workflow_definition'::regclass,
               'public.workflow_version'::regclass,
               'public.trigger_config'::regclass,
               'public.connector'::regclass,
               'public.secret_reference'::regclass
           ])
           AND confdeltype = 'r'
    ) <> 10 THEN
        RAISE EXCEPTION 'all ten G2-05 foreign keys must use ON DELETE RESTRICT';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_constraint
         WHERE contype = 'c'
           AND conrelid = ANY (ARRAY[
               'public.workflow_definition'::regclass,
               'public.workflow_version'::regclass,
               'public.trigger_config'::regclass,
               'public.connector'::regclass,
               'public.secret_reference'::regclass
           ])
    ) <> 22 THEN
        RAISE EXCEPTION 'all 22 G2-05 CHECK constraints must be installed';
    END IF;

    SELECT array_agg(indexname ORDER BY indexname)
      INTO actual_names
      FROM pg_indexes
     WHERE schemaname = 'public'
       AND tablename = ANY (new_tables)
       AND (
           indexname LIKE 'idx_%'
           OR indexname IN ('uq_connector_active_name', 'uq_secret_reference_active_name')
       );
    IF actual_names IS DISTINCT FROM ARRAY[
        'idx_connector_project',
        'idx_secret_reference_project',
        'idx_trigger_config_version',
        'idx_workflow_definition_project',
        'idx_workflow_definition_updated',
        'idx_workflow_version_definition',
        'uq_connector_active_name',
        'uq_secret_reference_active_name'
    ]::text[] THEN
        RAISE EXCEPTION 'G2-05 explicit index set drift: %', actual_names;
    END IF;

    IF (
        SELECT count(*)
          FROM pg_index i
          JOIN pg_class index_class ON index_class.oid = i.indexrelid
         WHERE index_class.relname IN (
             'uq_connector_active_name',
             'uq_secret_reference_active_name'
         )
           AND i.indisunique
           AND i.indnullsnotdistinct
           AND i.indpred IS NOT NULL
           AND pg_get_expr(i.indpred, i.indrelid) LIKE '%deleted_at IS NULL%'
    ) <> 2 THEN
        RAISE EXCEPTION 'both active-name indexes must be partial and NULLS NOT DISTINCT';
    END IF;

    IF position(
        'updated_at DESC, id DESC'
        IN pg_get_indexdef('public.idx_workflow_definition_updated'::regclass)
    ) = 0 THEN
        RAISE EXCEPTION 'definition recent cursor DESC ordering drifted';
    END IF;
    IF position(
        'version_no DESC'
        IN pg_get_indexdef('public.idx_workflow_version_definition'::regclass)
    ) = 0 THEN
        RAISE EXCEPTION 'workflow version DESC ordering drifted';
    END IF;

    IF (
        SELECT count(DISTINCT table_name)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (new_tables)
           AND column_name = 'id'
           AND data_type = 'uuid'
           AND is_nullable = 'NO'
    ) <> 5 THEN
        RAISE EXCEPTION 'all five G2-05 identifiers must be non-null UUID';
    END IF;

    IF EXISTS (
        SELECT
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (new_tables)
           AND column_name IN ('created_at', 'updated_at', 'published_at', 'deleted_at')
           AND (data_type <> 'timestamp with time zone' OR datetime_precision <> 6)
    ) THEN
        RAISE EXCEPTION 'G2-05 audit/lifecycle timestamps must be timestamptz(6)';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles owner_role ON owner_role.oid = c.relowner
         WHERE n.nspname = 'public'
           AND c.relname = ANY (new_tables)
           AND owner_role.rolname = 'workflow_migrator'
    ) <> 5 THEN
        RAISE EXCEPTION 'workflow_migrator must own all five G2-05 tables';
    END IF;

    IF EXISTS (
        SELECT
          FROM unnest(ARRAY[
              'workflow_definition', 'connector', 'secret_reference'
          ]) AS table_name
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
        RAISE EXCEPTION 'mutable G2-05 table grants drifted';
    END IF;

    IF EXISTS (
        SELECT
          FROM unnest(ARRAY['workflow_version', 'trigger_config']) AS table_name
         WHERE NOT has_table_privilege(
                   'workflow_app', format('public.%I', table_name), 'SELECT,INSERT'
               )
            OR NOT has_table_privilege(
                   'workflow_platform_admin', format('public.%I', table_name), 'SELECT,INSERT'
               )
            OR has_table_privilege(
                   'workflow_app', format('public.%I', table_name), 'UPDATE,DELETE'
               )
            OR has_table_privilege(
                   'workflow_platform_admin', format('public.%I', table_name), 'UPDATE,DELETE'
               )
    ) THEN
        RAISE EXCEPTION 'immutable G2-05 table grants drifted';
    END IF;

    SELECT array_agg(tgname ORDER BY tgname)
      INTO actual_names
      FROM pg_trigger
     WHERE tgrelid = ANY (ARRAY[
         'public.workflow_version'::regclass,
         'public.trigger_config'::regclass
     ])
       AND NOT tgisinternal;
    IF actual_names IS DISTINCT FROM ARRAY[
        'trigger_config_immutable',
        'workflow_version_immutable'
    ]::text[] THEN
        RAISE EXCEPTION 'immutable trigger set drift: %', actual_names;
    END IF;
    IF EXISTS (
        SELECT
          FROM pg_trigger
         WHERE tgrelid = ANY (ARRAY[
             'public.workflow_version'::regclass,
             'public.trigger_config'::regclass
         ])
           AND NOT tgisinternal
           AND (
               tgtype <> 27
               OR position('reject_immutable_row_change()' IN pg_get_triggerdef(oid)) = 0
           )
    ) THEN
        RAISE EXCEPTION 'immutable trigger event/function contract drifted';
    END IF;

    IF NOT EXISTS (
        SELECT
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
          JOIN pg_roles owner_role ON owner_role.oid = p.proowner
         WHERE n.nspname = 'public'
           AND p.proname = 'reject_immutable_row_change'
           AND pg_get_function_identity_arguments(p.oid) = ''
           AND p.prorettype = 'trigger'::regtype
           AND NOT p.prosecdef
           AND p.provolatile = 'v'
           AND p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp']::text[]
           AND owner_role.rolname = 'workflow_migrator'
    ) THEN
        RAISE EXCEPTION 'immutable trigger function safety contract drifted';
    END IF;

    IF EXISTS (
        SELECT
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
          CROSS JOIN LATERAL aclexplode(
              COALESCE(p.proacl, acldefault('f', p.proowner))
          ) AS acl
         WHERE n.nspname = 'public'
           AND p.proname = 'reject_immutable_row_change'
           AND acl.grantee = 0
           AND acl.privilege_type = 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'immutable trigger function is executable by PUBLIC';
    END IF;
    IF has_function_privilege(
        'workflow_app', 'public.reject_immutable_row_change()', 'EXECUTE'
    ) OR has_function_privilege(
        'workflow_platform_admin', 'public.reject_immutable_row_change()', 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'runtime roles can execute the internal trigger function directly';
    END IF;
END;
$test$;

-- Table ownership must not bypass history immutability; FORCE RLS requires an explicit context.
BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
BEGIN
    BEGIN
        UPDATE public.workflow_version
           SET published_by = 'owner-mutation'
         WHERE id = '019fb1d7-d968-8201-8201-000000000001';
        RAISE EXCEPTION 'table owner updated immutable workflow version';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        DELETE FROM public.workflow_version
         WHERE id = '019fb1d7-d968-8201-8201-000000000001';
        RAISE EXCEPTION 'table owner deleted immutable workflow version';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        UPDATE public.trigger_config
           SET trigger_key = 'owner_mutation'
         WHERE id = '019fb1d7-d968-8301-8301-000000000001';
        RAISE EXCEPTION 'table owner updated immutable trigger config';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
    BEGIN
        DELETE FROM public.trigger_config
         WHERE id = '019fb1d7-d968-8301-8301-000000000001';
        RAISE EXCEPTION 'table owner deleted immutable trigger config';
    EXCEPTION
        WHEN SQLSTATE '55000' THEN NULL;
    END;
END;
$test$;
COMMIT;

SELECT 'G2-05 catalog, upgrade, ownership, grants, and immutability tests PASS' AS result;
