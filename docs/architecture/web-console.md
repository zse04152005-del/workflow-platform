# Web Console

The management console is an authenticated operational surface, not a static
landing page. Navigation is permission-aware and tenant/project selectors are
always visible. Workflow users can edit JSON/YAML with an unsaved-change guard,
inspect a read-only DAG and deterministic version diff, and confirm publish.

Runtime pages use business-key/status filters, keyset-backed tables and
instance/node/attempt timelines. Approval, pause/resume/cancel, dead-letter and
replay actions show the required reason and explicitly communicate cooperative
cancellation. Integrations show only opaque Secret references. Empty, loading,
error, keyboard and reduced-motion states are covered by the component test
suite.
