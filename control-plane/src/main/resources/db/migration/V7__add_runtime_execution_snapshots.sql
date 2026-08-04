-- G4-04/G4-10: retain immutable execution input and node configuration snapshots.
-- Existing rows remain readable; every new runtime trigger supplies both snapshots.

ALTER TABLE public.workflow_instance
    ADD COLUMN input_payload jsonb;

ALTER TABLE public.workflow_instance
    ADD CONSTRAINT ck_workflow_instance_input_payload
    CHECK (input_payload IS NULL OR jsonb_typeof(input_payload) = 'object');

ALTER TABLE public.node_instance
    ADD COLUMN config_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE public.node_instance
    ADD CONSTRAINT ck_node_instance_config_snapshot
    CHECK (jsonb_typeof(config_snapshot) = 'object');

COMMENT ON COLUMN public.workflow_instance.input_payload IS
    'Immutable inline JSON input snapshot; large payloads use input_ref plus input_digest.';

COMMENT ON COLUMN public.node_instance.config_snapshot IS
    'Immutable node configuration copied from the published workflow version at trigger time.';
