# G7 human approval verification

## Status

`[~]` the lifecycle, first-decision guard, transfer, timeout, audit redaction and
large-refund test are implemented; consolidated Maven execution remains.

## Evidence

`HumanTaskServiceTests` verifies WAITING_APPROVAL creation, candidate-role
inbox, form redaction, first decision wins, late decision conflict, transfer
reason/version and escalation timeout behavior.
