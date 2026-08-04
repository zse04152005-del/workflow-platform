\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'tenant context must fail closed before it is set';
    END IF;
    IF public.app_is_platform_admin() THEN
        RAISE EXCEPTION 'ordinary application role must not be a platform administrator';
    END IF;
    IF EXISTS (SELECT FROM public.tenant) THEN
        RAISE EXCEPTION 'tenant rows must not be visible without tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
INSERT INTO public.tenant (
    id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:test-admin-a',
    'oidc:test-admin-a',
    'Tenant A',
    'tenant-a',
    'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b01-8b01-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:test-admin-a',
    'oidc:test-admin-a',
    'Project A',
    'project-a',
    'ACTIVE'
);
INSERT INTO public.membership (
    id, tenant_id, created_by, updated_by, project_id,
    principal_subject, scope_type, status
) VALUES (
    '019fb1d7-d968-7c01-8c01-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:test-admin-a',
    'oidc:test-admin-a',
    NULL,
    'oidc:user-a',
    'TENANT',
    'ACTIVE'
);
INSERT INTO public.role (
    id, tenant_id, created_by, updated_by, code, name,
    permission_codes, system_role, status
) VALUES (
    '019fb1d7-d968-7d01-8d01-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:test-admin-a',
    'oidc:test-admin-a',
    'tenant_admin',
    'Tenant administrator',
    ARRAY['tenant:read', 'tenant:manage'],
    true,
    'ACTIVE'
);
INSERT INTO public.role_binding (
    id, tenant_id, created_by, updated_by, membership_id, role_id,
    scope_type, scope_id, status
) VALUES (
    '019fb1d7-d968-7e01-8e01-000000000001',
    '019fb1d7-d968-7a01-8a01-000000000001',
    'oidc:test-admin-a',
    'oidc:test-admin-a',
    '019fb1d7-d968-7c01-8c01-000000000001',
    '019fb1d7-d968-7d01-8d01-000000000001',
    'TENANT',
    NULL,
    'ACTIVE'
);
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
INSERT INTO public.tenant (
    id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:test-admin-b',
    'oidc:test-admin-b',
    'Tenant B',
    'tenant-b',
    'ACTIVE'
);
INSERT INTO public.project (
    id, tenant_id, created_by, updated_by, name, normalized_name, status
) VALUES (
    '019fb1d7-d968-7b02-8b02-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:test-admin-b',
    'oidc:test-admin-b',
    'Project B',
    'project-b',
    'ACTIVE'
);
INSERT INTO public.membership (
    id, tenant_id, created_by, updated_by, project_id,
    principal_subject, scope_type, status
) VALUES (
    '019fb1d7-d968-7c02-8c02-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:test-admin-b',
    'oidc:test-admin-b',
    NULL,
    'oidc:user-b',
    'TENANT',
    'ACTIVE'
);
INSERT INTO public.role (
    id, tenant_id, created_by, updated_by, code, name,
    permission_codes, system_role, status
) VALUES (
    '019fb1d7-d968-7d02-8d02-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:test-admin-b',
    'oidc:test-admin-b',
    'tenant_admin',
    'Tenant administrator',
    ARRAY['tenant:read', 'tenant:manage'],
    true,
    'ACTIVE'
);
INSERT INTO public.role_binding (
    id, tenant_id, created_by, updated_by, membership_id, role_id,
    scope_type, scope_id, status
) VALUES (
    '019fb1d7-d968-7e02-8e02-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:test-admin-b',
    'oidc:test-admin-b',
    '019fb1d7-d968-7c02-8c02-000000000002',
    '019fb1d7-d968-7d02-8d02-000000000002',
    'TENANT',
    NULL,
    'ACTIVE'
);
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL OR EXISTS (SELECT FROM public.tenant) THEN
        RAISE EXCEPTION 'transaction-local tenant context leaked through pool boundary';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
DECLARE
    affected_rows integer;
BEGIN
    IF (SELECT count(*) FROM public.tenant) <> 1 THEN
        RAISE EXCEPTION 'tenant A must see exactly one tenant';
    END IF;
    IF (SELECT count(*) FROM public.project) <> 1 THEN
        RAISE EXCEPTION 'tenant A must see exactly one project';
    END IF;

    UPDATE public.project
       SET name = 'cross-tenant-write-must-not-happen'
     WHERE id = '019fb1d7-d968-7b02-8b02-000000000002';
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 0 THEN
        RAISE EXCEPTION 'tenant A updated a tenant B project';
    END IF;

    BEGIN
        INSERT INTO public.project (
            id, tenant_id, created_by, updated_by, name, normalized_name, status
        ) VALUES (
            '019fb1d7-d968-7b99-8b99-000000000099',
            '019fb1d7-d968-7a02-8a02-000000000002',
            'oidc:user-a', 'oidc:user-a', 'Bad project', 'bad-project', 'ACTIVE'
        );
        RAISE EXCEPTION 'expected tenant RLS write rejection';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        INSERT INTO public.membership (
            id, tenant_id, created_by, updated_by, project_id,
            principal_subject, scope_type, status
        ) VALUES (
            '019fb1d7-d968-7c91-8c91-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:user-a', 'oidc:user-a',
            '019fb1d7-d968-7b02-8b02-000000000002',
            'oidc:cross-tenant', 'PROJECT', 'ACTIVE'
        );
        RAISE EXCEPTION 'expected cross-tenant project FK rejection';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.role_binding (
            id, tenant_id, created_by, updated_by, membership_id, role_id,
            scope_type, scope_id, status
        ) VALUES (
            '019fb1d7-d968-7e91-8e91-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:user-a', 'oidc:user-a',
            '019fb1d7-d968-7c02-8c02-000000000002',
            '019fb1d7-d968-7d01-8d01-000000000001',
            'TENANT', NULL, 'ACTIVE'
        );
        RAISE EXCEPTION 'expected cross-tenant membership FK rejection';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.membership (
            id, tenant_id, created_by, updated_by, project_id,
            principal_subject, scope_type, status
        ) VALUES (
            '019fb1d7-d968-7c92-8c92-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:user-a', 'oidc:user-a', NULL, 'oidc:user-a', 'TENANT', 'ACTIVE'
        );
        RAISE EXCEPTION 'expected NULLS NOT DISTINCT membership uniqueness rejection';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.role_binding (
            id, tenant_id, created_by, updated_by, membership_id, role_id,
            scope_type, scope_id, status
        ) VALUES (
            '019fb1d7-d968-7e92-8e92-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:user-a', 'oidc:user-a',
            '019fb1d7-d968-7c01-8c01-000000000001',
            '019fb1d7-d968-7d01-8d01-000000000001',
            'TENANT', NULL, 'ACTIVE'
        );
        RAISE EXCEPTION 'expected NULLS NOT DISTINCT role binding uniqueness rejection';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.membership (
            id, tenant_id, created_by, updated_by, project_id,
            principal_subject, scope_type, status
        ) VALUES (
            '019fb1d7-d968-7c93-8c93-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:user-a', 'oidc:user-a',
            '019fb1d7-d968-7b01-8b01-000000000001',
            'oidc:bad-scope', 'TENANT', 'ACTIVE'
        );
        RAISE EXCEPTION 'expected membership scope CHECK rejection';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.role_binding (
            id, tenant_id, created_by, updated_by, membership_id, role_id,
            scope_type, scope_id, status
        ) VALUES (
            '019fb1d7-d968-7e93-8e93-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:user-a', 'oidc:user-a',
            '019fb1d7-d968-7c01-8c01-000000000001',
            '019fb1d7-d968-7d01-8d01-000000000001',
            'PROJECT', NULL, 'ACTIVE'
        );
        RAISE EXCEPTION 'expected role binding scope CHECK rejection';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.role (
            id, tenant_id, created_by, updated_by, code, name,
            permission_codes, status
        ) VALUES (
            '019fb1d7-d968-7d93-8d93-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:user-a', 'oidc:user-a', 'Bad-Code', 'Bad role', ARRAY[]::text[], 'ACTIVE'
        );
        RAISE EXCEPTION 'expected role code CHECK rejection';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.tenant SET status = 'UNKNOWN'
         WHERE id = '019fb1d7-d968-7a01-8a01-000000000001';
        RAISE EXCEPTION 'expected tenant status CHECK rejection';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.tenant SET version = -1
         WHERE id = '019fb1d7-d968-7a01-8a01-000000000001';
        RAISE EXCEPTION 'expected optimistic version CHECK rejection';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        EXECUTE $sql$
            INSERT INTO public.tenant (
                id, created_by, updated_by, name, normalized_name, status
            ) VALUES (
                'not-a-uuid', 'oidc:user-a', 'oidc:user-a', 'Bad', 'bad-uuid', 'ACTIVE'
            )
        $sql$;
        RAISE EXCEPTION 'expected invalid UUID rejection';
    EXCEPTION
        WHEN invalid_text_representation THEN NULL;
    END;

    BEGIN
        DELETE FROM public.role_binding
         WHERE id = '019fb1d7-d968-7e01-8e01-000000000001';
        RAISE EXCEPTION 'expected physical deletion privilege rejection';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    UPDATE public.membership
       SET status = 'REVOKED',
           revoked_at = transaction_timestamp(),
           revoked_by = 'oidc:test-admin-a',
           revocation_reason = 'integration-test',
           updated_at = transaction_timestamp(),
           updated_by = 'oidc:test-admin-a',
           version = version + 1
     WHERE id = '019fb1d7-d968-7c01-8c01-000000000001';
    IF NOT EXISTS (
        SELECT FROM public.membership
         WHERE id = '019fb1d7-d968-7c01-8c01-000000000001'
           AND status = 'REVOKED'
           AND revoked_at IS NOT NULL
           AND version = 1
    ) THEN
        RAISE EXCEPTION 'membership revocation did not preserve history';
    END IF;
END;
$test$;
COMMIT;

BEGIN;
SET LOCAL workflow.platform_admin = 'true';
DO $test$
BEGIN
    IF public.app_is_platform_admin() THEN
        RAISE EXCEPTION 'custom GUC forged the platform-admin bypass';
    END IF;
    IF EXISTS (SELECT FROM public.tenant) THEN
        RAISE EXCEPTION 'forged platform-admin GUC exposed tenant rows';
    END IF;

    BEGIN
        EXECUTE 'SET LOCAL ROLE workflow_platform_admin';
        RAISE EXCEPTION 'ordinary application role switched to platform-admin role';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    BEGIN
        PERFORM public.app_set_tenant_context(NULL);
        RAISE EXCEPTION 'null tenant context was accepted';
    EXCEPTION
        WHEN null_value_not_allowed THEN NULL;
    END;

    BEGIN
        PERFORM set_config('workflow.tenant_id', 'not-a-uuid', true);
        PERFORM public.app_tenant_id();
        RAISE EXCEPTION 'invalid raw tenant context was accepted';
    EXCEPTION
        WHEN invalid_parameter_value THEN NULL;
    END;

    BEGIN
        INSERT INTO public.tenant (
            id, created_by, updated_by, name, normalized_name, status
        ) VALUES (
            '019fb1d7-d968-7a99-8a99-000000000099',
            'oidc:user-a', 'oidc:user-a', 'No context', 'no-context', 'ACTIVE'
        );
        RAISE EXCEPTION 'insert without tenant context was accepted';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
END;
$test$;
COMMIT;

SELECT 'G2-04 application-role tests PASS' AS result;
