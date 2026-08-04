-- G2-06: workflow runtime, node/task execution, and Cron schedule occurrences.
-- Depends on V2 definitions, immutable versions/triggers, and tenant-context helpers.

ALTER TABLE public.workflow_version
    ADD CONSTRAINT uq_workflow_version_instance_binding
    UNIQUE (tenant_id, id, definition_id);

ALTER TABLE public.trigger_config
    ADD CONSTRAINT uq_trigger_config_schedule_binding
    UNIQUE (tenant_id, id, timezone, misfire_policy);

CREATE TABLE public.workflow_instance (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    definition_id uuid NOT NULL,
    workflow_version_id uuid NOT NULL,
    business_key varchar(200),
    status varchar(40) NOT NULL,
    input_ref varchar(1024),
    input_digest char(64) NOT NULL,
    started_at timestamptz(6),
    completed_at timestamptz(6),
    trace_id varchar(32) NOT NULL,
    CONSTRAINT pk_workflow_instance PRIMARY KEY (id),
    CONSTRAINT uq_workflow_instance_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_workflow_instance_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_instance_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES public.workflow_definition (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_instance_version
        FOREIGN KEY (tenant_id, workflow_version_id, definition_id)
        REFERENCES public.workflow_version (tenant_id, id, definition_id) ON DELETE RESTRICT,
    CONSTRAINT ck_workflow_instance_status CHECK (
        status IN (
            'CREATED', 'RUNNING', 'PAUSE_REQUESTED', 'PAUSED',
            'CANCEL_REQUESTED', 'CANCELLED', 'SUCCEEDED', 'FAILED'
        )
    ),
    CONSTRAINT ck_workflow_instance_state_times CHECK (
        (status = 'CREATED' AND started_at IS NULL AND completed_at IS NULL)
        OR (
            status IN ('RUNNING', 'PAUSE_REQUESTED', 'PAUSED', 'CANCEL_REQUESTED')
            AND started_at IS NOT NULL
            AND completed_at IS NULL
        )
        OR (
            status IN ('CANCELLED', 'SUCCEEDED', 'FAILED')
            AND started_at IS NOT NULL
            AND completed_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_workflow_instance_time_order CHECK (
        updated_at >= created_at
        AND (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= started_at)
    ),
    CONSTRAINT ck_workflow_instance_input_digest
        CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workflow_instance_trace_id
        CHECK (trace_id ~ '^[0-9a-f]{32}$' AND trace_id <> repeat('0', 32)),
    CONSTRAINT ck_workflow_instance_optional_text CHECK (
        (business_key IS NULL OR btrim(business_key) <> '')
        AND (input_ref IS NULL OR btrim(input_ref) <> '')
    ),
    CONSTRAINT ck_workflow_instance_version CHECK (version >= 0)
);

CREATE INDEX idx_workflow_instance_tenant_created
    ON public.workflow_instance (tenant_id, created_at DESC, id DESC);

CREATE INDEX idx_workflow_instance_business_key
    ON public.workflow_instance (tenant_id, business_key, created_at, id)
    WHERE business_key IS NOT NULL;

CREATE INDEX idx_workflow_instance_status
    ON public.workflow_instance (tenant_id, status, updated_at, id);

CREATE INDEX idx_workflow_instance_definition
    ON public.workflow_instance (tenant_id, definition_id, created_at);

CREATE INDEX idx_workflow_instance_version
    ON public.workflow_instance (tenant_id, workflow_version_id, definition_id, created_at);

CREATE TABLE public.node_instance (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    workflow_instance_id uuid NOT NULL,
    node_id varchar(120) NOT NULL,
    node_type varchar(40) NOT NULL,
    status varchar(40) NOT NULL,
    attempt_no integer NOT NULL DEFAULT 0,
    dispatch_count integer NOT NULL DEFAULT 0,
    lease_generation bigint NOT NULL DEFAULT 0,
    next_schedule_at timestamptz(6),
    current_worker_id varchar(160),
    lease_expire_at timestamptz(6),
    output_ref varchar(1024),
    output_digest char(64),
    error_code varchar(120),
    CONSTRAINT pk_node_instance PRIMARY KEY (id),
    CONSTRAINT uq_node_instance_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_node_instance_workflow_node
        UNIQUE (tenant_id, workflow_instance_id, node_id),
    CONSTRAINT fk_node_instance_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_node_instance_workflow
        FOREIGN KEY (tenant_id, workflow_instance_id)
        REFERENCES public.workflow_instance (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_node_instance_status CHECK (
        status IN (
            'PENDING', 'READY', 'DISPATCHED', 'RUNNING', 'RETRY_WAIT',
            'WAITING_TIMER', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_node_instance_identity
        CHECK (btrim(node_id) <> '' AND node_type ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT ck_node_instance_counters CHECK (
        attempt_no >= 0
        AND dispatch_count >= 0
        AND lease_generation >= 0
        AND version >= 0
    ),
    CONSTRAINT ck_node_instance_schedule CHECK (
        (
            status IN ('READY', 'RETRY_WAIT', 'WAITING_TIMER')
            AND next_schedule_at IS NOT NULL
            AND next_schedule_at >= updated_at
        )
        OR (
            status NOT IN ('READY', 'RETRY_WAIT', 'WAITING_TIMER')
            AND next_schedule_at IS NULL
        )
    ),
    CONSTRAINT ck_node_instance_lease CHECK (
        (
            status IN ('DISPATCHED', 'RUNNING')
            AND current_worker_id IS NOT NULL
            AND btrim(current_worker_id) <> ''
            AND lease_expire_at IS NOT NULL
            AND lease_expire_at > updated_at
        )
        OR (
            status NOT IN ('DISPATCHED', 'RUNNING')
            AND current_worker_id IS NULL
            AND lease_expire_at IS NULL
        )
    ),
    CONSTRAINT ck_node_instance_output CHECK (
        (output_ref IS NULL AND output_digest IS NULL)
        OR (
            output_ref IS NOT NULL
            AND btrim(output_ref) <> ''
            AND output_digest ~ '^[0-9a-f]{64}$'
        )
    ),
    CONSTRAINT ck_node_instance_error CHECK (
        (
            status IN ('FAILED', 'RETRY_WAIT')
            AND error_code IS NOT NULL
            AND btrim(error_code) <> ''
        )
        OR (status NOT IN ('FAILED', 'RETRY_WAIT') AND error_code IS NULL)
    ),
    CONSTRAINT ck_node_instance_audit_time CHECK (updated_at >= created_at)
);

CREATE INDEX idx_node_instance_workflow
    ON public.node_instance (tenant_id, workflow_instance_id, node_id);

CREATE INDEX idx_node_instance_schedule
    ON public.node_instance (tenant_id, status, next_schedule_at, id)
    WHERE status IN ('READY', 'RETRY_WAIT', 'WAITING_TIMER');

CREATE INDEX idx_node_instance_lease_expiry
    ON public.node_instance (tenant_id, lease_expire_at, id)
    WHERE status IN ('DISPATCHED', 'RUNNING');

CREATE TABLE public.task_execution (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    node_instance_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    execution_id uuid NOT NULL,
    status varchar(40) NOT NULL,
    started_at timestamptz(6) NOT NULL,
    finished_at timestamptz(6),
    input_ref varchar(1024),
    input_digest char(64) NOT NULL,
    output_ref varchar(1024),
    output_digest char(64),
    error_code varchar(120),
    error_summary_redacted varchar(2000),
    CONSTRAINT pk_task_execution PRIMARY KEY (id),
    CONSTRAINT uq_task_execution_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_task_execution_node_attempt
        UNIQUE (tenant_id, node_instance_id, attempt_no),
    CONSTRAINT uq_task_execution_execution_id UNIQUE (tenant_id, execution_id),
    CONSTRAINT fk_task_execution_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_execution_node
        FOREIGN KEY (tenant_id, node_instance_id)
        REFERENCES public.node_instance (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_execution_attempt CHECK (attempt_no > 0 AND version >= 0),
    CONSTRAINT ck_task_execution_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'ABANDONED', 'CANCELLED')
    ),
    CONSTRAINT ck_task_execution_state_fields CHECK (
        (
            status = 'RUNNING'
            AND finished_at IS NULL
            AND output_ref IS NULL
            AND output_digest IS NULL
            AND error_code IS NULL
            AND error_summary_redacted IS NULL
        )
        OR (
            status = 'SUCCEEDED'
            AND finished_at IS NOT NULL
            AND error_code IS NULL
            AND error_summary_redacted IS NULL
        )
        OR (
            status IN ('FAILED', 'TIMED_OUT', 'ABANDONED', 'CANCELLED')
            AND finished_at IS NOT NULL
            AND error_code IS NOT NULL
            AND btrim(error_code) <> ''
            AND error_summary_redacted IS NOT NULL
            AND btrim(error_summary_redacted) <> ''
        )
    ),
    CONSTRAINT ck_task_execution_time_order CHECK (
        updated_at >= created_at
        AND started_at >= created_at
        AND (finished_at IS NULL OR finished_at >= started_at)
    ),
    CONSTRAINT ck_task_execution_input CHECK (
        input_digest ~ '^[0-9a-f]{64}$'
        AND (input_ref IS NULL OR btrim(input_ref) <> '')
    ),
    CONSTRAINT ck_task_execution_output CHECK (
        (output_ref IS NULL AND output_digest IS NULL)
        OR (
            output_ref IS NOT NULL
            AND btrim(output_ref) <> ''
            AND output_digest ~ '^[0-9a-f]{64}$'
        )
    )
);

CREATE INDEX idx_task_execution_node
    ON public.task_execution (tenant_id, node_instance_id, attempt_no DESC);

CREATE INDEX idx_task_execution_status
    ON public.task_execution (tenant_id, status, started_at, id);

CREATE TABLE public.schedule_occurrence (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    trigger_config_id uuid NOT NULL,
    scheduled_at timestamptz(6) NOT NULL,
    timezone varchar(80) NOT NULL,
    misfire_policy varchar(40) NOT NULL,
    status varchar(40) NOT NULL,
    workflow_instance_id uuid,
    claimed_at timestamptz(6),
    CONSTRAINT pk_schedule_occurrence PRIMARY KEY (id),
    CONSTRAINT uq_schedule_occurrence_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_schedule_occurrence_trigger_time
        UNIQUE (tenant_id, trigger_config_id, scheduled_at),
    CONSTRAINT fk_schedule_occurrence_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedule_occurrence_trigger
        FOREIGN KEY (tenant_id, trigger_config_id, timezone, misfire_policy)
        REFERENCES public.trigger_config (tenant_id, id, timezone, misfire_policy)
        ON DELETE RESTRICT,
    CONSTRAINT fk_schedule_occurrence_instance
        FOREIGN KEY (tenant_id, workflow_instance_id)
        REFERENCES public.workflow_instance (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_schedule_occurrence_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'FIRED', 'SKIPPED', 'FAILED')),
    CONSTRAINT ck_schedule_occurrence_state_fields CHECK (
        (status = 'PENDING' AND claimed_at IS NULL AND workflow_instance_id IS NULL)
        OR (status = 'CLAIMED' AND claimed_at IS NOT NULL AND workflow_instance_id IS NULL)
        OR (status = 'FIRED' AND claimed_at IS NOT NULL AND workflow_instance_id IS NOT NULL)
        OR (
            status IN ('SKIPPED', 'FAILED')
            AND claimed_at IS NOT NULL
            AND workflow_instance_id IS NULL
        )
    ),
    CONSTRAINT ck_schedule_occurrence_claim_time
        CHECK (claimed_at IS NULL OR claimed_at >= scheduled_at),
    CONSTRAINT ck_schedule_occurrence_snapshot CHECK (
        btrim(timezone) <> ''
        AND misfire_policy IN ('skip', 'fire_once', 'catch_up_limited')
    ),
    CONSTRAINT ck_schedule_occurrence_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_schedule_occurrence_due
    ON public.schedule_occurrence (tenant_id, status, scheduled_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_schedule_occurrence_trigger
    ON public.schedule_occurrence (
        tenant_id, trigger_config_id, timezone, misfire_policy, scheduled_at DESC
    );

CREATE INDEX idx_schedule_occurrence_instance
    ON public.schedule_occurrence (tenant_id, workflow_instance_id)
    WHERE workflow_instance_id IS NOT NULL;

CREATE OR REPLACE FUNCTION public.guard_task_execution_finalization()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RUNNING' OR NEW.version <> 0 THEN
            RAISE EXCEPTION 'task_execution must be inserted as RUNNING with version 0'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'task_execution history cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'RUNNING'
       OR NEW.status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'ABANDONED', 'CANCELLED') THEN
        RAISE EXCEPTION 'task_execution only permits one RUNNING-to-terminal finalization'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'task_execution finalization requires version + 1 and monotonic updated_at'
            USING ERRCODE = '55000';
    END IF;

    IF ROW(
        NEW.id,
        NEW.tenant_id,
        NEW.node_instance_id,
        NEW.attempt_no,
        NEW.execution_id,
        NEW.started_at,
        NEW.input_ref,
        NEW.input_digest,
        NEW.created_at,
        NEW.created_by
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.tenant_id,
        OLD.node_instance_id,
        OLD.attempt_no,
        OLD.execution_id,
        OLD.started_at,
        OLD.input_ref,
        OLD.input_digest,
        OLD.created_at,
        OLD.created_by
    ) THEN
        RAISE EXCEPTION 'task_execution identity and input snapshot cannot change'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION public.guard_task_execution_finalization() FROM PUBLIC;

CREATE TRIGGER task_execution_finalization_guard
BEFORE INSERT OR UPDATE OR DELETE ON public.task_execution
FOR EACH ROW EXECUTE FUNCTION public.guard_task_execution_finalization();

ALTER TABLE public.workflow_instance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.workflow_instance FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_instance_tenant_isolation ON public.workflow_instance
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.node_instance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.node_instance FORCE ROW LEVEL SECURITY;
CREATE POLICY node_instance_tenant_isolation ON public.node_instance
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.task_execution ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.task_execution FORCE ROW LEVEL SECURITY;
CREATE POLICY task_execution_tenant_isolation ON public.task_execution
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.schedule_occurrence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.schedule_occurrence FORCE ROW LEVEL SECURITY;
CREATE POLICY schedule_occurrence_tenant_isolation ON public.schedule_occurrence
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

REVOKE ALL ON TABLE public.workflow_instance FROM PUBLIC;
REVOKE ALL ON TABLE public.node_instance FROM PUBLIC;
REVOKE ALL ON TABLE public.task_execution FROM PUBLIC;
REVOKE ALL ON TABLE public.schedule_occurrence FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON TABLE
    public.workflow_instance,
    public.node_instance,
    public.task_execution,
    public.schedule_occurrence
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";
