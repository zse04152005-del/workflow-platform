# Runtime reliability kernel

The reliability layer implements ADR-004 as composable deterministic services.
Retry calculation covers fixed, linear and exponential delay, bounded jitter,
maximum attempts/window and `Retry-After`. Lease recovery increments dispatch
and generation without changing attempt number. Current generation validation
rejects a killed or late worker.

Processed events and Outbox IDs are the deduplication keys. Dead letters retain
only a bounded redacted summary and append notes/redispatch history. Replays
create a new attempt or instance and expose immutable historical IDs. Pause
stops new dispatch but retains persistent timers; cancellation is cooperative.
Large payloads become object references with SHA-256 verification and a
retention class.
