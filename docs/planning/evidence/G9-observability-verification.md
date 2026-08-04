# G9 observability and operations verification

## Status

`[~]` low-cardinality conventions, structured log sanitization, probes/shutdown
contract, dashboard and runbook are implemented; final container smoke checks
remain.

## Evidence

`ObservabilityConventionsTests` verifies stable span/metric names, sensitive log
redaction and shutdown lease handoff. Dashboard panels cover throughput,
backlog, scheduler, Outbox, lease and dead-letter signals.
