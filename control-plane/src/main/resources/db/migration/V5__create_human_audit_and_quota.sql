-- G2-08: human task, append-only audit, and quota window accounting.
-- V1-V4 are frozen; this migration only appends the remaining three core tables.

CREATE TABLE public.human_task (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    node_instance_id uuid NOT NULL,
    candidate_role_code varchar(80),
    assignee_subject varchar(255),
    form_schema jsonb NOT NULL,
    form_data_ref varchar(1024),
    form_data_digest char(64),
    status varchar(40) NOT NULL,
    deadline_at timestamptz(6),
    timeout_policy varchar(40) NOT NULL,
    decision varchar(40),
    decision_at timestamptz(6),
    decided_by varchar(255),
    transfer_count integer NOT NULL DEFAULT 0,
    last_transferred_at timestamptz(6),
    last_transferred_by varchar(255),
    CONSTRAINT pk_human_task PRIMARY KEY (id),
    CONSTRAINT uq_human_task_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_human_task_node UNIQUE (tenant_id, node_instance_id),
    CONSTRAINT fk_human_task_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_human_task_node
        FOREIGN KEY (tenant_id, node_instance_id)
        REFERENCES public.node_instance (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_human_task_status
        CHECK (status IN ('WAITING','DECIDED','TIMED_OUT','CANCELLED')),
    CONSTRAINT ck_human_task_candidate CHECK (
        (candidate_role_code IS NULL OR candidate_role_code ~ '^[a-z][a-z0-9_]*$')
        AND (assignee_subject IS NULL OR btrim(assignee_subject) <> '')
        AND (candidate_role_code IS NOT NULL OR assignee_subject IS NOT NULL)
    ),
    CONSTRAINT ck_human_task_form CHECK (
        jsonb_typeof(form_schema) = 'object'
        AND form_schema <> '{}'::jsonb
        AND (
            (form_data_ref IS NULL AND form_data_digest IS NULL)
            OR (
                form_data_ref IS NOT NULL
                AND btrim(form_data_ref) <> ''
                AND form_data_digest ~ '^[0-9a-f]{64}$'
            )
        )
    ),
    CONSTRAINT ck_human_task_decision CHECK (
        (
            status = 'DECIDED'
            AND decision IN ('APPROVED','REJECTED')
            AND decision_at IS NOT NULL
            AND decided_by IS NOT NULL
            AND btrim(decided_by) <> ''
        )
        OR (
            status IN ('WAITING','TIMED_OUT','CANCELLED')
            AND decision IS NULL
            AND decision_at IS NULL
            AND decided_by IS NULL
        )
    ),
    CONSTRAINT ck_human_task_timeout CHECK (
        (deadline_at IS NULL AND timeout_policy = 'NONE' AND status <> 'TIMED_OUT')
        OR (
            deadline_at IS NOT NULL
            AND deadline_at >= created_at
            AND timeout_policy IN ('FAIL','AUTO_APPROVE','AUTO_REJECT','ESCALATE')
        )
    ),
    CONSTRAINT ck_human_task_transfer CHECK (
        (
            transfer_count = 0
            AND last_transferred_at IS NULL
            AND last_transferred_by IS NULL
        )
        OR (
            transfer_count > 0
            AND last_transferred_at IS NOT NULL
            AND last_transferred_at >= created_at
            AND last_transferred_by IS NOT NULL
            AND btrim(last_transferred_by) <> ''
        )
    ),
    CONSTRAINT ck_human_task_audit CHECK (
        updated_at >= created_at
        AND version >= transfer_count
        AND (
            decision_at IS NULL
            OR (decision_at >= created_at AND decision_at <= updated_at)
        )
        AND (last_transferred_at IS NULL OR last_transferred_at <= updated_at)
    )
);

CREATE INDEX idx_human_task_assignee
    ON public.human_task (tenant_id, assignee_subject, created_at, id)
    WHERE status = 'WAITING' AND assignee_subject IS NOT NULL;

CREATE INDEX idx_human_task_candidate
    ON public.human_task (tenant_id, candidate_role_code, created_at, id)
    WHERE status = 'WAITING' AND candidate_role_code IS NOT NULL;

CREATE INDEX idx_human_task_deadline
    ON public.human_task (tenant_id, deadline_at, id)
    WHERE status = 'WAITING' AND deadline_at IS NOT NULL;

CREATE INDEX idx_human_task_node
    ON public.human_task (tenant_id, node_instance_id);

CREATE TABLE public.audit_log (
    id uuid NOT NULL,
    tenant_id uuid,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    actor_type varchar(40) NOT NULL,
    actor_id varchar(255) NOT NULL,
    action varchar(120) NOT NULL,
    resource_type varchar(80) NOT NULL,
    resource_id varchar(255) NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    reason varchar(1000),
    trace_id varchar(32) NOT NULL,
    source_ip inet,
    before_digest_or_redacted jsonb,
    after_digest_or_redacted jsonb,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_log_actor CHECK (
        actor_type IN ('USER','SERVICE','WORKER','SYSTEM','PLATFORM_ADMIN')
        AND btrim(actor_id) <> ''
        AND (tenant_id IS NOT NULL OR actor_type = 'PLATFORM_ADMIN')
    ),
    CONSTRAINT ck_audit_log_action
        CHECK (action ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    CONSTRAINT ck_audit_log_resource CHECK (
        resource_type ~ '^[a-z][a-z0-9_]*$'
        AND btrim(resource_id) <> ''
    ),
    CONSTRAINT ck_audit_log_time CHECK (created_at >= occurred_at),
    CONSTRAINT ck_audit_log_trace CHECK (
        trace_id ~ '^[0-9a-f]{32}$'
        AND trace_id <> repeat('0', 32)
    ),
    CONSTRAINT ck_audit_log_payload CHECK (
        (reason IS NULL OR btrim(reason) <> '')
        AND (
            before_digest_or_redacted IS NULL
            OR jsonb_typeof(before_digest_or_redacted) = 'object'
        )
        AND (
            after_digest_or_redacted IS NULL
            OR jsonb_typeof(after_digest_or_redacted) = 'object'
        )
    )
);

CREATE INDEX idx_audit_log_tenant_time
    ON public.audit_log (tenant_id, occurred_at DESC, id DESC);

CREATE INDEX idx_audit_log_resource
    ON public.audit_log (tenant_id, resource_type, resource_id, occurred_at DESC);

CREATE INDEX idx_audit_log_trace
    ON public.audit_log (tenant_id, trace_id, occurred_at DESC, id DESC);

CREATE TABLE public.quota_usage (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    resource_type varchar(80) NOT NULL,
    window_start timestamptz(6) NOT NULL,
    window_end timestamptz(6) NOT NULL,
    used_value bigint NOT NULL DEFAULT 0,
    limit_value bigint NOT NULL,
    CONSTRAINT pk_quota_usage PRIMARY KEY (id),
    CONSTRAINT uq_quota_usage_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_quota_usage_window
        UNIQUE (tenant_id, resource_type, window_start),
    CONSTRAINT fk_quota_usage_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_quota_usage_resource
        CHECK (resource_type ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT ck_quota_usage_window CHECK (window_end > window_start),
    CONSTRAINT ck_quota_usage_values
        CHECK (used_value >= 0 AND limit_value >= 0 AND version >= 0),
    CONSTRAINT ck_quota_usage_audit CHECK (updated_at >= created_at)
);

CREATE INDEX idx_quota_usage_active
    ON public.quota_usage (tenant_id, resource_type, window_end, id);

CREATE INDEX idx_quota_usage_retention
    ON public.quota_usage (tenant_id, window_end, id);

CREATE OR REPLACE FUNCTION public.guard_human_task_transition()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'WAITING'
           OR NEW.version <> 0
           OR NEW.transfer_count <> 0
           OR NEW.updated_at <> NEW.created_at THEN
            RAISE EXCEPTION 'human task must start WAITING at version zero'
                USING ERRCODE = '55000';
        END IF;

        PERFORM 1
          FROM public.node_instance AS node
         WHERE node.tenant_id = NEW.tenant_id
           AND node.id = NEW.node_instance_id
           AND node.status = 'WAITING_APPROVAL'
         FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'human task requires a WAITING_APPROVAL node in the same tenant'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'WAITING' THEN
        RAISE EXCEPTION 'terminal human task is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'human task update requires one version step and monotonic time'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(
        NEW.id,
        NEW.tenant_id,
        NEW.created_at,
        NEW.created_by,
        NEW.node_instance_id,
        NEW.form_schema,
        NEW.form_data_ref,
        NEW.form_data_digest,
        NEW.deadline_at,
        NEW.timeout_policy
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.tenant_id,
        OLD.created_at,
        OLD.created_by,
        OLD.node_instance_id,
        OLD.form_schema,
        OLD.form_data_ref,
        OLD.form_data_digest,
        OLD.deadline_at,
        OLD.timeout_policy
    ) THEN
        RAISE EXCEPTION 'human task identity, form, and timeout snapshot cannot change'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'WAITING' THEN
        IF NEW.transfer_count <> OLD.transfer_count + 1
           OR NEW.last_transferred_at IS NULL
           OR NEW.last_transferred_at <= COALESCE(OLD.last_transferred_at, OLD.created_at)
           OR NEW.last_transferred_by IS NULL
           OR btrim(NEW.last_transferred_by) = ''
           OR NEW.updated_by <> NEW.last_transferred_by
           OR ROW(NEW.candidate_role_code, NEW.assignee_subject)
              IS NOT DISTINCT FROM ROW(OLD.candidate_role_code, OLD.assignee_subject)
           OR ROW(NEW.decision, NEW.decision_at, NEW.decided_by)
              IS DISTINCT FROM ROW(OLD.decision, OLD.decision_at, OLD.decided_by) THEN
            RAISE EXCEPTION 'WAITING human task update must be one auditable transfer'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(
        NEW.candidate_role_code,
        NEW.assignee_subject,
        NEW.transfer_count,
        NEW.last_transferred_at,
        NEW.last_transferred_by
    ) IS DISTINCT FROM ROW(
        OLD.candidate_role_code,
        OLD.assignee_subject,
        OLD.transfer_count,
        OLD.last_transferred_at,
        OLD.last_transferred_by
    ) THEN
        RAISE EXCEPTION 'terminal resolution cannot also change human task routing'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'DECIDED' THEN
        IF NEW.decision_at IS NULL
           OR NEW.decision_at < OLD.updated_at
           OR NEW.decided_by <> NEW.updated_by THEN
            RAISE EXCEPTION 'human task decision requires matching actor and monotonic decision time'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status = 'TIMED_OUT' THEN
        IF NEW.deadline_at IS NULL
           OR NEW.deadline_at > transaction_timestamp()
           OR NEW.updated_at < NEW.deadline_at THEN
            RAISE EXCEPTION 'human task cannot time out before its deadline'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status = 'CANCELLED' THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'unsupported human task transition'
        USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION public.guard_audit_log_append_only()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.tenant_id IS NULL AND NOT public.app_is_platform_admin() THEN
            RAISE EXCEPTION 'only a platform administrator may append a platform audit row'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'audit log is append-only'
        USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION public.guard_quota_usage_transition()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 0 OR NEW.updated_at <> NEW.created_at THEN
            RAISE EXCEPTION 'quota usage window must start at version zero'
                USING ERRCODE = '55000';
        END IF;

        PERFORM pg_catalog.pg_advisory_xact_lock(
            pg_catalog.hashtextextended(
                NEW.tenant_id::text || ':' || NEW.resource_type,
                0
            )
        );
        IF EXISTS (
            SELECT 1
              FROM public.quota_usage AS existing
             WHERE existing.tenant_id = NEW.tenant_id
               AND existing.resource_type = NEW.resource_type
               AND existing.id <> NEW.id
               AND existing.window_start < NEW.window_end
               AND existing.window_end > NEW.window_start
        ) THEN
            RAISE EXCEPTION 'quota usage windows for one tenant and resource must not overlap'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.version <> OLD.version + 1
       OR NEW.updated_at < OLD.updated_at
       OR NEW.used_value <= OLD.used_value THEN
        RAISE EXCEPTION 'quota usage update requires one version step and a monotonic increment'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(
        NEW.id,
        NEW.tenant_id,
        NEW.created_at,
        NEW.created_by,
        NEW.resource_type,
        NEW.window_start,
        NEW.window_end,
        NEW.limit_value
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.tenant_id,
        OLD.created_at,
        OLD.created_by,
        OLD.resource_type,
        OLD.window_start,
        OLD.window_end,
        OLD.limit_value
    ) THEN
        RAISE EXCEPTION 'quota usage identity, window, and limit snapshot cannot change'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION public.guard_human_task_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_audit_log_append_only() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_quota_usage_transition() FROM PUBLIC;

CREATE TRIGGER human_task_transition_guard
BEFORE INSERT OR UPDATE ON public.human_task
FOR EACH ROW EXECUTE FUNCTION public.guard_human_task_transition();

CREATE TRIGGER audit_log_append_only_guard
BEFORE INSERT OR UPDATE OR DELETE ON public.audit_log
FOR EACH ROW EXECUTE FUNCTION public.guard_audit_log_append_only();

CREATE TRIGGER quota_usage_transition_guard
BEFORE INSERT OR UPDATE ON public.quota_usage
FOR EACH ROW EXECUTE FUNCTION public.guard_quota_usage_transition();

ALTER TABLE public.human_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.human_task FORCE ROW LEVEL SECURITY;
CREATE POLICY human_task_tenant_isolation ON public.human_task
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_log_tenant_isolation ON public.audit_log
    USING (
        tenant_id = public.app_tenant_id()
        OR (tenant_id IS NULL AND public.app_is_platform_admin())
    )
    WITH CHECK (
        tenant_id = public.app_tenant_id()
        OR (tenant_id IS NULL AND public.app_is_platform_admin())
    );

ALTER TABLE public.quota_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.quota_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY quota_usage_tenant_isolation ON public.quota_usage
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

REVOKE ALL ON TABLE public.human_task FROM PUBLIC;
REVOKE ALL ON TABLE public.audit_log FROM PUBLIC;
REVOKE ALL ON TABLE public.quota_usage FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON TABLE public.human_task, public.quota_usage
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";

GRANT SELECT, INSERT ON TABLE public.audit_log
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";
