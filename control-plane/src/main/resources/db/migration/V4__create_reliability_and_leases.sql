-- G2-07: transactional Outbox, consumer deduplication, API idempotency,
-- business dead letters, and durable Worker lease history.
-- Depends on V3 runtime records and tenant-context helpers.

ALTER TABLE public.task_execution
    ADD CONSTRAINT uq_task_execution_dead_letter_binding
    UNIQUE (tenant_id, id, node_instance_id);

CREATE TABLE public.outbox_event (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    schema_version integer NOT NULL,
    partition_key varchar(200) NOT NULL,
    payload jsonb,
    payload_ref varchar(1024),
    payload_digest char(64) NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    trace_context varchar(512),
    publish_attempts integer NOT NULL DEFAULT 0,
    next_publish_at timestamptz(6) NOT NULL,
    published_at timestamptz(6),
    publisher_id varchar(160),
    publish_lease_until timestamptz(6),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT uq_outbox_event_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_outbox_event_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbox_event_payload
        CHECK ((payload IS NULL) <> (payload_ref IS NULL)),
    CONSTRAINT ck_outbox_event_identity CHECK (
        btrim(aggregate_type) <> ''
        AND btrim(event_type) <> ''
        AND btrim(partition_key) <> ''
    ),
    CONSTRAINT ck_outbox_event_digest
        CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_outbox_event_publish
        CHECK (publish_attempts >= 0 AND schema_version > 0 AND version >= 0),
    CONSTRAINT ck_outbox_event_lease CHECK (
        (
            published_at IS NULL
            AND (
                (publisher_id IS NULL AND publish_lease_until IS NULL)
                OR (
                    publisher_id IS NOT NULL
                    AND btrim(publisher_id) <> ''
                    AND publish_lease_until IS NOT NULL
                    AND publish_lease_until > created_at
                )
            )
        )
        OR (
            published_at IS NOT NULL
            AND publisher_id IS NULL
            AND publish_lease_until IS NULL
        )
    ),
    CONSTRAINT ck_outbox_event_times CHECK (
        next_publish_at >= occurred_at
        AND (published_at IS NULL OR published_at >= occurred_at)
    ),
    CONSTRAINT ck_outbox_event_trace
        CHECK (trace_context IS NULL OR btrim(trace_context) <> '')
);

CREATE INDEX idx_outbox_event_unpublished
    ON public.outbox_event (tenant_id, next_publish_at, id)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_event_publish_lease
    ON public.outbox_event (tenant_id, publish_lease_until, id)
    WHERE published_at IS NULL AND publisher_id IS NOT NULL;

CREATE INDEX idx_outbox_event_tenant_created
    ON public.outbox_event (tenant_id, created_at, id);

CREATE TABLE public.processed_event (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    consumer_name varchar(120) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    schema_version integer NOT NULL,
    processed_at timestamptz(6) NOT NULL,
    result_digest char(64),
    CONSTRAINT pk_processed_event PRIMARY KEY (id),
    CONSTRAINT uq_processed_event_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_processed_event_consumer_event UNIQUE (consumer_name, event_id),
    CONSTRAINT fk_processed_event_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_processed_event_schema CHECK (schema_version > 0),
    CONSTRAINT ck_processed_event_identity
        CHECK (btrim(consumer_name) <> '' AND btrim(event_type) <> ''),
    CONSTRAINT ck_processed_event_digest
        CHECK (result_digest IS NULL OR result_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_processed_event_time CHECK (processed_at >= created_at)
);

CREATE INDEX idx_processed_event_retention
    ON public.processed_event (processed_at, id);

CREATE INDEX idx_processed_event_tenant
    ON public.processed_event (tenant_id, processed_at, id);

CREATE TABLE public.idempotency_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    operation_scope varchar(240) NOT NULL,
    key_digest char(64) NOT NULL,
    request_digest char(64) NOT NULL,
    status varchar(32) NOT NULL,
    response_status integer,
    response_ref varchar(1024),
    response_digest char(64),
    expires_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_record_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_idempotency_record_scope_key
        UNIQUE (tenant_id, operation_scope, key_digest),
    CONSTRAINT fk_idempotency_record_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_idempotency_record_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_idempotency_record_identity CHECK (
        btrim(operation_scope) <> ''
        AND key_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_idempotency_record_response CHECK (
        (
            status = 'PROCESSING'
            AND response_status IS NULL
            AND response_ref IS NULL
            AND response_digest IS NULL
        )
        OR (
            status IN ('COMPLETED', 'FAILED')
            AND response_status BETWEEN 100 AND 599
            AND response_digest ~ '^[0-9a-f]{64}$'
            AND (response_ref IS NULL OR btrim(response_ref) <> '')
        )
    ),
    CONSTRAINT ck_idempotency_record_time CHECK (
        updated_at >= created_at
        AND expires_at > created_at
        AND version >= 0
    )
);

CREATE INDEX idx_idempotency_record_expiry
    ON public.idempotency_record (expires_at, id);

CREATE INDEX idx_idempotency_record_processing
    ON public.idempotency_record (tenant_id, status, updated_at, id)
    WHERE status = 'PROCESSING';

CREATE TABLE public.dead_letter (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    node_instance_id uuid NOT NULL,
    task_execution_id uuid,
    category varchar(40) NOT NULL,
    error_code varchar(120) NOT NULL,
    error_summary_redacted varchar(2000) NOT NULL,
    payload_ref varchar(1024),
    payload_digest char(64),
    status varchar(40) NOT NULL,
    disposition_count integer NOT NULL DEFAULT 0,
    last_disposition_at timestamptz(6),
    CONSTRAINT pk_dead_letter PRIMARY KEY (id),
    CONSTRAINT uq_dead_letter_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_dead_letter_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dead_letter_node
        FOREIGN KEY (tenant_id, node_instance_id)
        REFERENCES public.node_instance (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_dead_letter_execution
        FOREIGN KEY (tenant_id, task_execution_id, node_instance_id)
        REFERENCES public.task_execution (tenant_id, id, node_instance_id) ON DELETE RESTRICT,
    CONSTRAINT ck_dead_letter_status
        CHECK (status IN ('OPEN', 'REQUEUED', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT ck_dead_letter_identity CHECK (
        btrim(category) <> ''
        AND error_code ~ '^[A-Z][A-Z0-9]*(_[A-Z0-9]+){2,}$'
        AND btrim(error_summary_redacted) <> ''
    ),
    CONSTRAINT ck_dead_letter_payload CHECK (
        (payload_ref IS NULL AND payload_digest IS NULL)
        OR (
            payload_ref IS NOT NULL
            AND btrim(payload_ref) <> ''
            AND payload_digest ~ '^[0-9a-f]{64}$'
        )
    ),
    CONSTRAINT ck_dead_letter_disposition CHECK (
        (status = 'OPEN' AND disposition_count = 0 AND last_disposition_at IS NULL)
        OR (
            status IN ('REQUEUED', 'RESOLVED', 'DISMISSED')
            AND disposition_count > 0
            AND last_disposition_at IS NOT NULL
            AND last_disposition_at >= created_at
        )
    ),
    CONSTRAINT ck_dead_letter_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_dead_letter_open
    ON public.dead_letter (tenant_id, status, created_at, id)
    WHERE status = 'OPEN';

CREATE INDEX idx_dead_letter_node
    ON public.dead_letter (tenant_id, node_instance_id, created_at);

CREATE INDEX idx_dead_letter_execution
    ON public.dead_letter (tenant_id, task_execution_id, node_instance_id)
    WHERE task_execution_id IS NOT NULL;

CREATE TABLE public.worker_lease (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    created_by varchar(255) NOT NULL,
    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    node_instance_id uuid NOT NULL,
    lease_generation bigint NOT NULL,
    worker_id varchar(160) NOT NULL,
    status varchar(40) NOT NULL,
    acquired_at timestamptz(6) NOT NULL,
    expires_at timestamptz(6) NOT NULL,
    last_heartbeat_at timestamptz(6),
    released_at timestamptz(6),
    CONSTRAINT pk_worker_lease PRIMARY KEY (id),
    CONSTRAINT uq_worker_lease_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_worker_lease_generation
        UNIQUE (tenant_id, node_instance_id, lease_generation),
    CONSTRAINT fk_worker_lease_tenant
        FOREIGN KEY (tenant_id) REFERENCES public.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_worker_lease_node
        FOREIGN KEY (tenant_id, node_instance_id)
        REFERENCES public.node_instance (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_worker_lease_generation
        CHECK (lease_generation > 0 AND version >= 0),
    CONSTRAINT ck_worker_lease_worker CHECK (btrim(worker_id) <> ''),
    CONSTRAINT ck_worker_lease_times CHECK (
        updated_at >= created_at
        AND acquired_at >= created_at
        AND expires_at > acquired_at
        AND (status <> 'ACTIVE' OR expires_at > updated_at)
        AND (
            last_heartbeat_at IS NULL
            OR (last_heartbeat_at >= acquired_at AND last_heartbeat_at <= expires_at)
        )
        AND (released_at IS NULL OR released_at >= acquired_at)
    ),
    CONSTRAINT ck_worker_lease_status
        CHECK (status IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'FENCED')),
    CONSTRAINT ck_worker_lease_lifecycle CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (
            status IN ('RELEASED', 'EXPIRED', 'FENCED')
            AND released_at IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uq_worker_lease_active_node
    ON public.worker_lease (tenant_id, node_instance_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_worker_lease_expiry
    ON public.worker_lease (tenant_id, expires_at, id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_worker_lease_node
    ON public.worker_lease (tenant_id, node_instance_id, lease_generation DESC);

CREATE OR REPLACE FUNCTION public.guard_outbox_event_transition()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 0
           OR NEW.publish_attempts <> 0
           OR NEW.published_at IS NOT NULL
           OR NEW.publisher_id IS NOT NULL
           OR NEW.publish_lease_until IS NOT NULL THEN
            RAISE EXCEPTION 'outbox event must be inserted as an unclaimed version-zero event'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.published_at IS NOT NULL THEN
        RAISE EXCEPTION 'published outbox event is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.version <> OLD.version + 1
       OR NEW.publish_attempts < OLD.publish_attempts
       OR NEW.publish_attempts > OLD.publish_attempts + 1
       OR NEW.next_publish_at < OLD.next_publish_at THEN
        RAISE EXCEPTION 'outbox publish progress must be monotonic with one version step'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(
        NEW.id,
        NEW.tenant_id,
        NEW.aggregate_type,
        NEW.aggregate_id,
        NEW.event_type,
        NEW.schema_version,
        NEW.partition_key,
        NEW.payload,
        NEW.payload_ref,
        NEW.payload_digest,
        NEW.occurred_at,
        NEW.trace_context,
        NEW.created_at,
        NEW.created_by
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.tenant_id,
        OLD.aggregate_type,
        OLD.aggregate_id,
        OLD.event_type,
        OLD.schema_version,
        OLD.partition_key,
        OLD.payload,
        OLD.payload_ref,
        OLD.payload_digest,
        OLD.occurred_at,
        OLD.trace_context,
        OLD.created_at,
        OLD.created_by
    ) THEN
        RAISE EXCEPTION 'outbox event identity and payload snapshot cannot change'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION public.guard_processed_event_immutability()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION 'processed event is immutable after insert'
        USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION public.guard_idempotency_record_transition()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROCESSING' OR NEW.version <> 0 THEN
            RAISE EXCEPTION 'idempotency record must begin in PROCESSING at version zero'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'PROCESSING'
       OR NEW.status NOT IN ('COMPLETED', 'FAILED')
       OR NEW.version <> OLD.version + 1
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'idempotency record permits one PROCESSING-to-terminal transition'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(
        NEW.id,
        NEW.tenant_id,
        NEW.operation_scope,
        NEW.key_digest,
        NEW.request_digest,
        NEW.expires_at,
        NEW.created_at,
        NEW.created_by
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.tenant_id,
        OLD.operation_scope,
        OLD.key_digest,
        OLD.request_digest,
        OLD.expires_at,
        OLD.created_at,
        OLD.created_by
    ) THEN
        RAISE EXCEPTION 'idempotency request identity cannot change'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION public.guard_dead_letter_transition()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN'
           OR NEW.disposition_count <> 0
           OR NEW.last_disposition_at IS NOT NULL
           OR NEW.version <> 0 THEN
            RAISE EXCEPTION 'dead letter must be inserted OPEN without a disposition'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'OPEN'
       OR NEW.status NOT IN ('REQUEUED', 'RESOLVED', 'DISMISSED')
       OR NEW.disposition_count <> OLD.disposition_count + 1
       OR NEW.version <> OLD.version + 1
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'dead letter permits one OPEN-to-disposed transition'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(
        NEW.id,
        NEW.tenant_id,
        NEW.node_instance_id,
        NEW.task_execution_id,
        NEW.category,
        NEW.error_code,
        NEW.error_summary_redacted,
        NEW.payload_ref,
        NEW.payload_digest,
        NEW.created_at,
        NEW.created_by
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.tenant_id,
        OLD.node_instance_id,
        OLD.task_execution_id,
        OLD.category,
        OLD.error_code,
        OLD.error_summary_redacted,
        OLD.payload_ref,
        OLD.payload_digest,
        OLD.created_at,
        OLD.created_by
    ) THEN
        RAISE EXCEPTION 'dead-letter failure snapshot cannot change'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION public.guard_worker_lease_transition()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE'
           OR NEW.version <> 0
           OR NEW.last_heartbeat_at IS NOT NULL
           OR NEW.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'worker lease must be inserted ACTIVE at version zero'
                USING ERRCODE = '55000';
        END IF;
        PERFORM 1
          FROM public.node_instance
         WHERE tenant_id = NEW.tenant_id
           AND id = NEW.node_instance_id
           AND lease_generation = NEW.lease_generation
           AND current_worker_id = NEW.worker_id
           AND lease_expire_at = NEW.expires_at
           AND status IN ('DISPATCHED', 'RUNNING')
         FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'worker lease does not match the current node fencing token'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'ACTIVE'
       OR NEW.version <> OLD.version + 1
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'terminal worker lease is immutable and updates require one version step'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(
        NEW.id,
        NEW.tenant_id,
        NEW.node_instance_id,
        NEW.lease_generation,
        NEW.worker_id,
        NEW.acquired_at,
        NEW.created_at,
        NEW.created_by
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.tenant_id,
        OLD.node_instance_id,
        OLD.lease_generation,
        OLD.worker_id,
        OLD.acquired_at,
        OLD.created_at,
        OLD.created_by
    ) THEN
        RAISE EXCEPTION 'worker lease identity and fencing token cannot change'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'ACTIVE' THEN
        IF NEW.last_heartbeat_at IS NULL
           OR NEW.last_heartbeat_at <= COALESCE(OLD.last_heartbeat_at, OLD.acquired_at)
           OR NEW.expires_at <= OLD.expires_at
           OR NEW.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'active lease heartbeat and expiry must advance monotonically'
                USING ERRCODE = '55000';
        END IF;
        PERFORM 1
          FROM public.node_instance
         WHERE tenant_id = NEW.tenant_id
           AND id = NEW.node_instance_id
           AND lease_generation = NEW.lease_generation
           AND current_worker_id = NEW.worker_id
           AND lease_expire_at = NEW.expires_at
           AND status IN ('DISPATCHED', 'RUNNING')
         FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'heartbeat fencing token is no longer current on the node'
                USING ERRCODE = '55000';
        END IF;
    ELSIF NEW.status IN ('RELEASED', 'EXPIRED', 'FENCED') THEN
        IF NEW.expires_at <> OLD.expires_at
           OR NEW.last_heartbeat_at IS DISTINCT FROM OLD.last_heartbeat_at THEN
            RAISE EXCEPTION 'terminal lease transition cannot rewrite heartbeat history'
                USING ERRCODE = '55000';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid worker lease transition'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION public.guard_outbox_event_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_processed_event_immutability() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_idempotency_record_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_dead_letter_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_worker_lease_transition() FROM PUBLIC;

CREATE TRIGGER outbox_event_transition_guard
BEFORE INSERT OR UPDATE ON public.outbox_event
FOR EACH ROW EXECUTE FUNCTION public.guard_outbox_event_transition();

CREATE TRIGGER processed_event_immutability_guard
BEFORE UPDATE ON public.processed_event
FOR EACH ROW EXECUTE FUNCTION public.guard_processed_event_immutability();

CREATE TRIGGER idempotency_record_transition_guard
BEFORE INSERT OR UPDATE ON public.idempotency_record
FOR EACH ROW EXECUTE FUNCTION public.guard_idempotency_record_transition();

CREATE TRIGGER dead_letter_transition_guard
BEFORE INSERT OR UPDATE ON public.dead_letter
FOR EACH ROW EXECUTE FUNCTION public.guard_dead_letter_transition();

CREATE TRIGGER worker_lease_transition_guard
BEFORE INSERT OR UPDATE ON public.worker_lease
FOR EACH ROW EXECUTE FUNCTION public.guard_worker_lease_transition();

ALTER TABLE public.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.outbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_event_tenant_isolation ON public.outbox_event
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.processed_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.processed_event FORCE ROW LEVEL SECURITY;
CREATE POLICY processed_event_tenant_isolation ON public.processed_event
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.idempotency_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.idempotency_record FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_record_tenant_isolation ON public.idempotency_record
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.dead_letter FORCE ROW LEVEL SECURITY;
CREATE POLICY dead_letter_tenant_isolation ON public.dead_letter
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

ALTER TABLE public.worker_lease ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.worker_lease FORCE ROW LEVEL SECURITY;
CREATE POLICY worker_lease_tenant_isolation ON public.worker_lease
    USING (tenant_id = public.app_tenant_id())
    WITH CHECK (tenant_id = public.app_tenant_id());

REVOKE ALL ON TABLE public.outbox_event FROM PUBLIC;
REVOKE ALL ON TABLE public.processed_event FROM PUBLIC;
REVOKE ALL ON TABLE public.idempotency_record FROM PUBLIC;
REVOKE ALL ON TABLE public.dead_letter FROM PUBLIC;
REVOKE ALL ON TABLE public.worker_lease FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON TABLE
    public.outbox_event,
    public.idempotency_record,
    public.dead_letter,
    public.worker_lease
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";

GRANT SELECT, INSERT ON TABLE public.processed_event
    TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";
