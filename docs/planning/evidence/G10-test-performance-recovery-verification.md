# G10 testing, fault injection, performance and recovery verification

## Status

`[~]` test matrices, k6 scripts, fault invariants and backup/restore scripts are
implemented. No performance number is claimed until the 30-minute run executes
with the declared hardware/profile and produces a report.

## Reproducible artifacts

- unit/integration/E2E and five-fault matrices under `tests/`;
- k6 trigger, short, delay and high-failure scenarios with explicit thresholds;
- backup and restore verification scripts with Flyway version check;
- security negative coverage delegated to the G6 kernel and DSL adversarial suite.
