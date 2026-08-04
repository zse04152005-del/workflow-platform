-- G2-05: workflow definitions, immutable versions/triggers, connectors, and secret references.
-- Depends on V1 tenant/project tables and tenant-context helpers.

CREATE TABLE public.workflow_definition (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    project_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    normalized_name varchar(160) NOT NULL,
    status varchar(32) NOT NULL,
    draft_dsl jsonb NOT NULL,
    draft_revision bigint NOT NULL DEFAULT 0,
    draft_checksum char(64) NOT NULL,
    CONSTRAINT pk_workflow_definition PRIMARY KEY (id),
    CONSTRAINT uq_workflow_definition_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_workflow_definition_project_name
        UNIQUE (tenant_id, project_id, normalized_name),
    CONSTRAINT fk_workflow_definition_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_definition_project
        FOREIGN KEY (tenant_id, project_id)
        REFERENCES public.project (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_workflow_definition_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'DEACTIVATED')),
    CONSTRAINT ck_workflow_definition_revision
        CHECK (draft_revision >= 0 AND version >= 0),
    CONSTRAINT ck_workflow_definition_dsl_object
        CHECK (jsonb_typeof(draft_dsl) = 'object'),
    CONSTRAINT ck_workflow_definition_checksum
        CHECK (draft_checksum ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_workflow_definition_project
    ON public.workflow_definition (tenant_id, project_id, status, id);

CREATE INDEX idx_workflow_definition_updated
    ON public.workflow_definition (tenant_id, updated_at DESC, id DESC);

CREATE TABLE public.workflow_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    definition_id uuid NOT NULL,
    version_no integer NOT NULL,
    schema_version integer NOT NULL,
    dsl jsonb NOT NULL,
    checksum char(64) NOT NULL,
    executor_catalog_version varchar(80) NOT NULL,
    published_at timestamptz(6) NOT NULL,
    published_by varchar(255) NOT NULL,
    CONSTRAINT pk_workflow_version PRIMARY KEY (id),
    CONSTRAINT uq_workflow_version_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_workflow_version_number
        UNIQUE (tenant_id, definition_id, version_no),
    CONSTRAINT uq_workflow_version_checksum
        UNIQUE (tenant_id, definition_id, checksum),
    CONSTRAINT fk_workflow_version_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_version_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES public.workflow_definition (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_workflow_version_numbers
        CHECK (version_no > 0 AND schema_version > 0),
    CONSTRAINT ck_workflow_version_dsl_object
        CHECK (jsonb_typeof(dsl) = 'object'),
    CONSTRAINT ck_workflow_version_checksum_format
        CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workflow_version_publish_time
        CHECK (published_at >= created_at)
);

CREATE INDEX idx_workflow_version_definition
    ON public.workflow_version (tenant_id, definition_id, version_no DESC);

CREATE TABLE public.trigger_config (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    workflow_version_id uuid NOT NULL,
    trigger_key varchar(80) NOT NULL,
    trigger_type varchar(40) NOT NULL,
    config jsonb NOT NULL,
    timezone varchar(80),
    misfire_policy varchar(40),
    CONSTRAINT pk_trigger_config PRIMARY KEY (id),
    CONSTRAINT uq_trigger_config_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_trigger_config_version_key
        UNIQUE (tenant_id, workflow_version_id, trigger_key),
    CONSTRAINT fk_trigger_config_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trigger_config_version
        FOREIGN KEY (tenant_id, workflow_version_id)
        REFERENCES public.workflow_version (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_trigger_config_key
        CHECK (trigger_key ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT ck_trigger_config_type
        CHECK (trigger_type IN ('manual', 'http', 'cron')),
    CONSTRAINT ck_trigger_config_cron CHECK (
        (
            trigger_type = 'cron'
            AND timezone IS NOT NULL
            AND btrim(timezone) <> ''
            AND misfire_policy IN ('skip', 'fire_once', 'catch_up_limited')
        )
        OR (
            trigger_type <> 'cron'
            AND timezone IS NULL
            AND misfire_policy IS NULL
        )
    ),
    CONSTRAINT ck_trigger_config_object
        CHECK (jsonb_typeof(config) = 'object')
);

CREATE INDEX idx_trigger_config_version
    ON public.trigger_config (tenant_id, workflow_version_id, trigger_type);

CREATE TABLE public.connector (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz(6),
    deleted_by varchar(255),
    deletion_reason varchar(500),
    project_id uuid,
    name varchar(120) NOT NULL,
    normalized_name varchar(120) NOT NULL,
    connector_type varchar(40) NOT NULL,
    endpoint varchar(2048) NOT NULL,
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(32) NOT NULL,
    CONSTRAINT pk_connector PRIMARY KEY (id),
    CONSTRAINT uq_connector_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_connector_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_connector_project
        FOREIGN KEY (tenant_id, project_id)
        REFERENCES public.project (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_connector_type
        CHECK (connector_type ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT ck_connector_config_object
        CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT ck_connector_endpoint CHECK (btrim(endpoint) <> ''),
    CONSTRAINT ck_connector_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_connector_delete_fields CHECK (
        (
            deleted_at IS NULL
            AND deleted_by IS NULL
            AND deletion_reason IS NULL
        )
        OR (
            deleted_at IS NOT NULL
            AND deleted_by IS NOT NULL
            AND btrim(deleted_by) <> ''
            AND deletion_reason IS NOT NULL
            AND btrim(deletion_reason) <> ''
        )
    )
);

CREATE UNIQUE INDEX uq_connector_active_name
    ON public.connector (tenant_id, project_id, normalized_name)
    NULLS NOT DISTINCT
    WHERE deleted_at IS NULL;

CREATE INDEX idx_connector_project
    ON public.connector (tenant_id, project_id, status)
    WHERE deleted_at IS NULL;

CREATE TABLE public.secret_reference (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz(6),
    deleted_by varchar(255),
    deletion_reason varchar(500),
    project_id uuid,
    name varchar(120) NOT NULL,
    normalized_name varchar(120) NOT NULL,
    provider varchar(40) NOT NULL,
    external_key varchar(512),
    ciphertext bytea,
    key_version varchar(80) NOT NULL,
    status varchar(32) NOT NULL,
    CONSTRAINT pk_secret_reference PRIMARY KEY (id),
    CONSTRAINT uq_secret_reference_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_secret_reference_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_secret_reference_project
        FOREIGN KEY (tenant_id, project_id)
        REFERENCES public.project (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_secret_reference_provider
        CHECK (provider ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT ck_secret_reference_material CHECK (
        (
            provider = 'local'
            AND ciphertext IS NOT NULL
            AND octet_length(ciphertext) > 0
            AND external_key IS NULL
        )
        OR (
            provider <> 'local'
            AND external_key IS NOT NULL
            AND btrim(external_key) <> ''
            AND ciphertext IS NULL
        )
    ),
    CONSTRAINT ck_secret_reference_key_version CHECK (btrim(key_version) <> ''),
    CONSTRAINT ck_secret_reference_status
        CHECK (status IN ('ACTIVE', 'DISABLED', 'ROTATION_REQUIRED')),
    CONSTRAINT ck_secret_reference_delete_fields CHECK (
        (
            deleted_at IS NULL
            AND deleted_by IS NULL
            AND deletion_reason IS NULL
        )
        OR (
            deleted_at IS NOT NULL
            AND deleted_by IS NOT NULL
            AND btrim(deleted_by) <> ''
            AND deletion_reason IS NOT NULL
            AND btrim(deletion_reason) <> ''
        )
    )
);

CREATE UNIQUE INDEX uq_secret_reference_active_name
    ON public.secret_reference (tenant_id, project_id, normalized_name)
    NULLS NOT DISTINCT
    WHERE deleted_at IS NULL;

CREATE INDEX idx_secret_reference_project
    ON public.secret_reference (tenant_id, project_id, status)
    WHERE deleted_at IS NULL;

CREATE OR REPLACE FUNCTION public.reject_immutable_row_change()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION '% is immutable; append a new version/configuration instead', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION public.reject_immutable_row_change() FROM PUBLIC;

CREATE TRIGGER workflow_version_immutable
BEFORE UPDATE OR DELETE ON public.workflow_version
FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_row_change();

CREATE TRIGGER trigger_config_immutable
BEFORE UPDATE OR DELETE ON public.trigger_config
FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_row_change();

ALTER TABLE public.workflow_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.workflow_definition FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_definition_tenant_isolation ON public.workflow_definition
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.workflow_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.workflow_version FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_version_tenant_isolation ON public.workflow_version
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.trigger_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.trigger_config FORCE ROW LEVEL SECURITY;
CREATE POLICY trigger_config_tenant_isolation ON public.trigger_config
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.connector ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.connector FORCE ROW LEVEL SECURITY;
CREATE POLICY connector_tenant_isolation ON public.connector
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.secret_reference ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.secret_reference FORCE ROW LEVEL SECURITY;
CREATE POLICY secret_reference_tenant_isolation ON public.secret_reference
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

REVOKE ALL ON TABLE public.workflow_definition FROM PUBLIC;
REVOKE ALL ON TABLE public.workflow_version FROM PUBLIC;
REVOKE ALL ON TABLE public.trigger_config FROM PUBLIC;
REVOKE ALL ON TABLE public.connector FROM PUBLIC;
REVOKE ALL ON TABLE public.secret_reference FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON TABLE
    public.workflow_definition,
    public.connector,
    public.secret_reference
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";

GRANT SELECT, INSERT ON TABLE
    public.workflow_version,
    public.trigger_config
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";
