# Runtime trigger component

G4-03 exposes one authenticated manual/HTTP start boundary at
`POST /api/v1/workflows/{workflowId}/instances`. The request must carry an
`Idempotency-Key` (8–128 safe characters); the optional `Traceparent` value is
validated as a non-zero 32-character trace identifier. Input is a JSON object
bounded to 1 MiB and the business key is bounded to 200 characters.

The service computes a length-prefixed SHA-256 request digest. The JDBC store
claims `idempotency_record` under the tenant RLS context, locks the published
workflow version, creates the instance and initial node snapshots, appends the
audit and `workflow.instance.created` Outbox event, and completes the claim in
one transaction. A same-key/same-digest request replays the original instance
reference; a different digest is a conflict. Payload content is never copied to
audit or Outbox; only the input digest and an inline content reference are
persisted.
