# G8 management console verification

## Status

`[~]` the console information architecture, pages and interaction tests are
implemented. Final browser/Playwright execution is part of the consolidated
front-end gate.

## Evidence

`web-console/src/App.tsx` now provides tenant/project context, workflow editor
and publish confirmation, DAG/version diff, runtime timeline, approval inbox,
failure/replay controls, integration Secret references and audit query. `App.test.tsx`
covers overview, unsaved protection, DAG accessibility and approval action.
