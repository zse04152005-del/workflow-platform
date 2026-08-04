# G4-03 Runtime manual/HTTP trigger verification

## Status

`[x]` implementation, PostgreSQL transaction integration tests and the complete
five-module Maven quality gate passed on 2026-08-03.

## Implemented

- manual and authenticated HTTP trigger service/controller;
- mandatory Idempotency-Key, length-prefixed request digest, 24-hour replay;
- same digest replay and different digest conflict semantics;
- JSON-object/1 MiB input bound, business-key bound, non-zero trace validation;
- atomic idempotency claim, instance/node snapshot, audit and Outbox boundary.

## Verification

`RuntimeTriggerServiceTests` covers digest determinism, pre-persistence input
and key rejection, and stable accepted response metadata.
`JdbcRuntimeTriggerStoreIT` passed 5/5 PostgreSQL 18.4 Testcontainers cases,
including same-key replay, digest conflict, atomic snapshots and tenant RLS.
The final `mvnw.cmd -B clean verify` run passed all five modules with 196 unit
tests and 44 integration tests, plus PMD, CPD, SpotBugs and JaCoCo.
