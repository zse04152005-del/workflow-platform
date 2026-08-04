# Runtime execution kernel

G4-04 through G4-16 are implemented as small deterministic components around
the PostgreSQL-authoritative state machine. Trigger creation persists a version
bound `RuntimeInstanceSnapshot`; `DagProgressor` returns decisions but writes no
state; the scheduler scans only due READY/RETRY_WAIT/WAITING_TIMER rows and uses
claim-once semantics. Outbox and task-event components expose explicit
at-least-once, retry and duplicate outcomes.

Worker claims produce a monotonically increasing generation. Every heartbeat
and result must present the current `(taskId, workerId, generation)` tuple and
an unexpired lease. Node execution is isolated behind `NodePlugin` with a
timeout, bounded maps and a redacted error classification. Start/End,
Condition, managed HTTPS and Delay are the baseline plugins; delay state is
represented by `next_schedule_at`, not an in-process timer.

Cron calculation requires a timezone and explicit misfire policy. Occurrence
uniqueness and overlap constraints remain in PostgreSQL. Runtime queries use
`created_at DESC,id DESC` keyset cursors with a 200-row cap; OFFSET is excluded
from the contract.
