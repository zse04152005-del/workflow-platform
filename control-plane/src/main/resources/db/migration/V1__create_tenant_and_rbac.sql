-- G2-04: tenant, project, membership, role, and role_binding.
-- Flyway placeholders are identifiers provisioned outside the application runtime:
--   workflowApplicationRole, workflowPlatformAdminRole.

CREATE OR REPLACE FUNCTION public.app_tenant_id()
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    tenant_setting text;
BEGIN
    tenant_setting := current_setting('workflow.tenant_id', true);
    IF tenant_setting IS NULL OR btrim(tenant_setting) = '' THEN
        RETURN NULL;
    END IF;

    BEGIN
        RETURN tenant_setting::uuid;
    EXCEPTION
        WHEN invalid_text_representation THEN
            RAISE EXCEPTION 'workflow.tenant_id must be a canonical UUID'
                USING ERRCODE = '22023';
    END;
END;
$function$;

CREATE OR REPLACE FUNCTION public.app_is_platform_admin()
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
    SELECT COALESCE(
        pg_has_role(
            session_user,
            to_regrole('${workflowPlatformAdminRole}'),
            'MEMBER'
        ),
        false
    );
$function$;

CREATE OR REPLACE FUNCTION public.app_set_tenant_context(p_tenant_id uuid)
RETURNS void
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'tenant context must not be null'
            USING ERRCODE = '22004';
    END IF;

    PERFORM set_config('workflow.tenant_id', p_tenant_id::text, true);
END;
$function$;

REVOKE ALL ON FUNCTION public.app_tenant_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.app_is_platform_admin() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.app_set_tenant_context(uuid) FROM PUBLIC;

CREATE TABLE public.tenant (
    id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    name varchar(120) NOT NULL,
    normalized_name varchar(120) NOT NULL,
    status varchar(32) NOT NULL,
    quota_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT pk_tenant PRIMARY KEY (id),
    CONSTRAINT uq_tenant_normalized_name UNIQUE (normalized_name),
    CONSTRAINT ck_tenant_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT ck_tenant_version CHECK (version >= 0)
);

CREATE INDEX idx_tenant_status_id
    ON public.tenant (status, id);

CREATE TABLE public.project (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    name varchar(120) NOT NULL,
    normalized_name varchar(120) NOT NULL,
    status varchar(32) NOT NULL,
    CONSTRAINT pk_project PRIMARY KEY (id),
    CONSTRAINT uq_project_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_project_tenant_name UNIQUE (tenant_id, normalized_name),
    CONSTRAINT fk_project_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_project_version CHECK (version >= 0)
);

CREATE INDEX idx_project_tenant_status_id
    ON public.project (tenant_id, status, id);

CREATE TABLE public.membership (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    revoked_at timestamptz(6),
    revoked_by varchar(255),
    revocation_reason varchar(500),
    project_id uuid,
    principal_subject varchar(255) NOT NULL,
    scope_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    CONSTRAINT pk_membership PRIMARY KEY (id),
    CONSTRAINT uq_membership_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_membership_scope_subject
        UNIQUE NULLS NOT DISTINCT (tenant_id, project_id, principal_subject),
    CONSTRAINT fk_membership_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_membership_project
        FOREIGN KEY (tenant_id, project_id)
        REFERENCES public.project (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_membership_scope CHECK (
        (scope_type = 'TENANT' AND project_id IS NULL)
        OR (scope_type = 'PROJECT' AND project_id IS NOT NULL)
    ),
    CONSTRAINT ck_membership_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX idx_membership_project
    ON public.membership (tenant_id, project_id, status);

CREATE INDEX idx_membership_subject
    ON public.membership (tenant_id, principal_subject, status);

CREATE TABLE public.role (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    code varchar(80) NOT NULL,
    name varchar(120) NOT NULL,
    permission_codes text[] NOT NULL,
    system_role boolean NOT NULL DEFAULT false,
    status varchar(32) NOT NULL,
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uq_role_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_role_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_role_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_role_code CHECK (code ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT ck_role_status CHECK (status IN ('ACTIVE', 'DEACTIVATED'))
);

CREATE INDEX idx_role_tenant_status
    ON public.role (tenant_id, status, code);

CREATE TABLE public.role_binding (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    revoked_at timestamptz(6),
    revoked_by varchar(255),
    revocation_reason varchar(500),
    membership_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type varchar(32) NOT NULL,
    scope_id uuid,
    status varchar(32) NOT NULL,
    CONSTRAINT pk_role_binding PRIMARY KEY (id),
    CONSTRAINT uq_role_binding_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_role_binding_assignment
        UNIQUE NULLS NOT DISTINCT (
            tenant_id,
            membership_id,
            role_id,
            scope_type,
            scope_id
        ),
    CONSTRAINT fk_role_binding_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_binding_membership
        FOREIGN KEY (tenant_id, membership_id)
        REFERENCES public.membership (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_binding_role
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES public.role (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_role_binding_scope CHECK (
        (scope_type = 'TENANT' AND scope_id IS NULL)
        OR (scope_type = 'PROJECT' AND scope_id IS NOT NULL)
    ),
    CONSTRAINT ck_role_binding_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX idx_role_binding_membership
    ON public.role_binding (tenant_id, membership_id, status);

CREATE INDEX idx_role_binding_role
    ON public.role_binding (tenant_id, role_id, status);

ALTER TABLE public.tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.tenant
    USING (id = public.app_tenant_id() OR public.app_is_platform_admin())
    WITH CHECK (id = public.app_tenant_id() OR public.app_is_platform_admin());

ALTER TABLE public.project ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.project FORCE ROW LEVEL SECURITY;
CREATE POLICY project_tenant_isolation ON public.project
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.membership FORCE ROW LEVEL SECURITY;
CREATE POLICY membership_tenant_isolation ON public.membership
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.role ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.role FORCE ROW LEVEL SECURITY;
CREATE POLICY role_tenant_isolation ON public.role
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.role_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.role_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY role_binding_tenant_isolation ON public.role_binding
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON TABLE public.tenant FROM PUBLIC;
REVOKE ALL ON TABLE public.project FROM PUBLIC;
REVOKE ALL ON TABLE public.membership FROM PUBLIC;
REVOKE ALL ON TABLE public.role FROM PUBLIC;
REVOKE ALL ON TABLE public.role_binding FROM PUBLIC;

GRANT USAGE ON SCHEMA public
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";
GRANT EXECUTE ON FUNCTION public.app_tenant_id()
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";
GRANT EXECUTE ON FUNCTION public.app_is_platform_admin()
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";
GRANT EXECUTE ON FUNCTION public.app_set_tenant_context(uuid)
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";
GRANT SELECT, INSERT, UPDATE ON TABLE
    public.tenant,
    public.project,
    public.membership,
    public.role,
    public.role_binding
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";

COMMENT ON FUNCTION public.app_set_tenant_context(uuid) IS
    'Sets transaction-local tenant context; call after authentication and inside every business transaction.';
COMMENT ON FUNCTION public.app_is_platform_admin() IS
    'Returns true only when session_user is a member of the provisioned NOLOGIN platform-admin role.';
