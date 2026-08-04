# MVP demo script

1. Start `deploy/compose` with `scripts/demo/run-demo.ps1`.
2. Sign in to Keycloak as the local demo user and choose the `commerce` project.
3. Open the `订单超时关闭` definition, inspect the DAG, validate and publish.
4. Trigger it with the sample HTTP request in `docs/api/trigger-examples.md`.
5. Open the instance timeline, then approve the `大额退款审批` task.
6. Inject an HTTP timeout, inspect the redacted dead letter and replay as a new
   instance. End by opening the audit record and Trace link.
