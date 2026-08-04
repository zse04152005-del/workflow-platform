-- G3-06: persist the optional public workflow description without rewriting frozen migrations.

ALTER TABLE public.workflow_definition
    ADD COLUMN description varchar(1000);

-- V4 permits one PROCESSING-to-terminal transition. G3-06 adds a 24-hour replay
-- window, so the repository may reclaim a row only after that window expires.
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

    IF OLD.expires_at <= transaction_timestamp()
       AND NEW.status = 'PROCESSING' THEN
        IF NEW.version <> OLD.version + 1
           OR NEW.updated_at < OLD.updated_at
           OR NEW.expires_at <= transaction_timestamp()
           OR NEW.response_status IS NOT NULL
           OR NEW.response_ref IS NOT NULL
           OR NEW.response_digest IS NOT NULL
           OR ROW(
               NEW.id,
               NEW.tenant_id,
               NEW.operation_scope,
               NEW.key_digest,
               NEW.created_at,
               NEW.created_by
           ) IS DISTINCT FROM ROW(
               OLD.id,
               OLD.tenant_id,
               OLD.operation_scope,
               OLD.key_digest,
               OLD.created_at,
               OLD.created_by
           ) THEN
            RAISE EXCEPTION 'expired idempotency reset must preserve identity and start a new window'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'PROCESSING'
       OR NEW.status NOT IN ('COMPLETED', 'FAILED')
       OR NEW.version <> OLD.version + 1
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'idempotency record permits one PROCESSING-to-terminal transition per window'
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
        RAISE EXCEPTION 'idempotency request identity cannot change inside a replay window'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;
