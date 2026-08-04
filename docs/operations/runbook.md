# Runtime operations runbook

## Queue backlog or scheduler lag

Check `queue_depth`, scheduler lag and database due-index health. Pause new
dispatch for the affected tenant if capacity is exhausted, scale workers, then
resume and verify the lag alert clears. Never edit runtime rows directly.

## Outbox stalled or Kafka failure

Inspect unpublished age and publisher retry counters. Restore broker/network,
allow the publisher to replay by event ID, and verify processed-event
deduplication. Do not mark events sent manually without a broker receipt.

## Worker lease storm

Stop new dispatch, inspect lease expiry/generation and worker health, then let
the recovery scanner redispatch expired work. A late worker result must remain
rejected by fencing.

## Dead letters and keys

Review the redacted summary and audit reason, query the external result before
redispatch, and rotate connector references through KMS/Vault. Never paste a
Secret value into a ticket or command line.
