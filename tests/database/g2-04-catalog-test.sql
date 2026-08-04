\set ON_ERROR_STOP on

DO $test$
DECLARE
    actual_names text[];
BEGIN
    SELECT array_agg(table_name ORDER BY table_name)
      INTO actual_names
      FROM information_schema.tables
     WHERE table_schema = 'public'
       AND table_type = 'BASE TABLE'
       AND table_name <> 'flyway_schema_history';
    IF actual_names IS DISTINCT FROM ARRAY[
        'membership', 'project', 'role', 'role_binding', 'tenant'
    ]::text[] THEN
        RAISE EXCEPTION 'unexpected G2-04 table set: %', actual_names;
    END IF;

    IF (SELECT count(*) FROM public.flyway_schema_history WHERE success) <> 1 THEN
        RAISE EXCEPTION 'Flyway history must contain exactly one successful migration';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname = ANY (ARRAY['tenant', 'project', 'membership', 'role', 'role_binding'])
           AND c.relrowsecurity
           AND c.relforcerowsecurity
    ) <> 5 THEN
        RAISE EXCEPTION 'all five tables must enable and force RLS';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = ANY (ARRAY['tenant', 'project', 'membership', 'role', 'role_binding'])
           AND cmd = 'ALL'
           AND roles = ARRAY['public']::name[]
    ) <> 5 THEN
        RAISE EXCEPTION 'all five tables must have an ALL-command RLS policy';
    END IF;

    SELECT array_agg(indexname ORDER BY indexname)
      INTO actual_names
      FROM pg_indexes
     WHERE schemaname = 'public'
       AND indexname LIKE 'idx_%';
    IF actual_names IS DISTINCT FROM ARRAY[
        'idx_membership_project',
        'idx_membership_subject',
        'idx_project_tenant_status_id',
        'idx_role_binding_membership',
        'idx_role_binding_role',
        'idx_role_tenant_status',
        'idx_tenant_status_id'
    ]::text[] THEN
        RAISE EXCEPTION 'critical index set drift: %', actual_names;
    END IF;

    IF (
        SELECT count(*)
          FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = ANY (ARRAY[
               'public.project'::regclass,
               'public.membership'::regclass,
               'public.role'::regclass,
               'public.role_binding'::regclass
           ])
           AND confdeltype = 'r'
    ) <> 7 THEN
        RAISE EXCEPTION 'all seven foreign keys must use ON DELETE RESTRICT';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_index i
          JOIN pg_constraint c ON c.conindid = i.indexrelid
         WHERE c.conname IN (
             'uq_membership_scope_subject',
             'uq_role_binding_assignment'
         )
           AND i.indnullsnotdistinct
    ) <> 2 THEN
        RAISE EXCEPTION 'both nullable natural keys must use NULLS NOT DISTINCT';
    END IF;

    IF EXISTS (
        SELECT
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (ARRAY['tenant', 'project', 'membership', 'role', 'role_binding'])
           AND column_name IN ('created_at', 'updated_at', 'revoked_at')
           AND (data_type <> 'timestamp with time zone' OR datetime_precision <> 6)
    ) THEN
        RAISE EXCEPTION 'audit and revocation timestamps must be timestamptz(6)';
    END IF;

    IF (
        SELECT count(DISTINCT table_name)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = ANY (ARRAY['tenant', 'project', 'membership', 'role', 'role_binding'])
           AND column_name = 'id'
           AND data_type = 'uuid'
           AND is_nullable = 'NO'
    ) <> 5 THEN
        RAISE EXCEPTION 'all five primary identifiers must be non-null uuid';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles owner_role ON owner_role.oid = c.relowner
         WHERE n.nspname = 'public'
           AND c.relname = ANY (ARRAY['tenant', 'project', 'membership', 'role', 'role_binding'])
           AND owner_role.rolname = 'workflow_migrator'
    ) <> 5 THEN
        RAISE EXCEPTION 'runtime application role must not own tables';
    END IF;

    IF has_schema_privilege('workflow_app', 'public', 'CREATE') THEN
        RAISE EXCEPTION 'runtime application role must not create schema objects';
    END IF;
    IF NOT has_schema_privilege('workflow_app', 'public', 'USAGE') THEN
        RAISE EXCEPTION 'runtime application role needs schema usage';
    END IF;

    IF EXISTS (
        SELECT
          FROM unnest(ARRAY['tenant', 'project', 'membership', 'role', 'role_binding']) AS table_name
         WHERE NOT has_table_privilege(
                   'workflow_app', format('public.%I', table_name), 'SELECT,INSERT,UPDATE'
               )
            OR has_table_privilege('workflow_app', format('public.%I', table_name), 'DELETE')
            OR has_table_privilege(
                   'workflow_platform_admin', format('public.%I', table_name), 'DELETE'
               )
    ) THEN
        RAISE EXCEPTION 'table least-privilege contract drift';
    END IF;

    IF NOT has_function_privilege(
        'workflow_app', 'public.app_set_tenant_context(uuid)', 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'runtime role cannot set transaction-local tenant context';
    END IF;

    IF EXISTS (
        SELECT
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
          CROSS JOIN LATERAL aclexplode(
              COALESCE(p.proacl, acldefault('f', p.proowner))
          ) AS acl
         WHERE n.nspname = 'public'
           AND p.proname IN (
               'app_tenant_id', 'app_is_platform_admin', 'app_set_tenant_context'
           )
           AND acl.grantee = 0
           AND acl.privilege_type = 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'tenant context helper is executable by PUBLIC';
    END IF;

    IF EXISTS (
        SELECT
          FROM pg_roles
         WHERE rolname IN ('workflow_app', 'workflow_migrator')
           AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
    ) THEN
        RAISE EXCEPTION 'application or migration role has an elevated PostgreSQL attribute';
    END IF;
    IF NOT EXISTS (
        SELECT
          FROM pg_roles
         WHERE rolname = 'workflow_platform_admin'
           AND NOT rolcanlogin
           AND NOT rolsuper
           AND NOT rolbypassrls
    ) THEN
        RAISE EXCEPTION 'platform-admin marker must be a NOLOGIN, non-bypass role';
    END IF;
END;
$test$;

SELECT 'G2-04 catalog and least-privilege tests PASS' AS result;
