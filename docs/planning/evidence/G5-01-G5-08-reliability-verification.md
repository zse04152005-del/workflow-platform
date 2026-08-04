# G5-01～G5-08 可靠性内核验收证据

## 1. 结论

截至 2026-08-04，G5-01～G5-08 已完成实现、真实 PostgreSQL 集成验证、真实 PostgreSQL/Kafka 崩溃恢复 E2E 和机器防漏门禁。系统在至少一次投递下以 PostgreSQL 状态、当前租约 fencing token 和事务 `processed_event` 为权威；重复事件、过期 Worker 结果和已处置死信不会改写既有事实。

本证据仅闭环 G5-01～G5-08。G5-09～G5-11 已有独立证据；G5-12、G5-13 仍明确延期，未被提前标记。

## 2. 任务与可执行证据

| 任务 | 已完成语义 | 生产实现 | 自动化证据 |
| --- | --- | --- | --- |
| G5-01 | ADR-004 固化计数、重试、fencing、租约恢复、去重、死信与不可改写重放 | `docs/adr/ADR-004-retry-lease-recovery-dead-letter-replay.md` | `runtime:g5:verify` 绑定 ADR 关键决策 |
| G5-02 | `dispatch_count` 在成功派发增长；`attempt_no` 在 claim 进入 RUNNING 增长；`lease_generation` 在成功派发增长 | V3/V4 迁移、`JdbcRuntimeTransitionStore`、`JdbcWorkerTaskStore` | `JdbcRuntimeTransitionStoreIT.workerClaimIncrementsOnlyTheAttemptCounter`；E2E 最终 `2:2:2` |
| G5-03 | 结果和心跳同时校验 `worker_id + lease_generation + lease_expire_at` | `JdbcWorkerTaskStore`、`JdbcRuntimeTransitionStore` | `staleGenerationCannotWriteALateResult`；崩溃恢复 E2E 中旧 Worker 上报抛出 fencing 冲突 |
| G5-04 | retryable/non-retryable 映射；fixed/linear/exponential 与稳定有界 jitter | `WorkerRetryPlanner`、`ManagedHttpWorkerNodeExecutor` | `WorkerRetryPlannerTests`、`ManagedHttpWorkerNodeExecutorTests` |
| G5-05 | 最大次数、最大窗口、Retry-After 和外部结果查询钩子 | `WorkerRetryPlanner`、`ExternalResultQueryHook`、`WorkerExecutionService` | `appliesRetryAfterAndStopsAtAttemptOrElapsedWindow`、外部查询成功/失败回退测试、Worker PostgreSQL 重试事务测试 |
| G5-06 | 到期扫描；旧 attempt→ABANDONED、旧 lease→EXPIRED、节点→READY，再派发新 generation | `LeaseRecoveryService`、`JdbcRuntimeSchedulingStore` | `recoversExpiredDispatchAndRunningLeaseWithoutAcceptingTheLateFence`；真实关闭 Worker 的 PostgreSQL/Kafka E2E |
| G5-07 | `(consumer_name,event_id)` 唯一；业务结果、attempt、lease、审计、Outbox 与 processed_event 同事务 | V4 迁移、`JdbcWorkerTaskStore` | `currentFenceCommitsResultAttemptLeaseAuditOutboxAndDedupTogether`；真实 Kafka 重复/重启消费证据 |
| G5-08 | 死信错误/人工备注统一脱敏；备注追加式审计；24 小时幂等；摘要 Outbox；OPEN→REQUEUED；新实例再次派发且源历史不可改写 | `SensitiveTextRedactor`、`DeadLetterDispositionController/Service`、`JdbcDeadLetterDispositionStore` | 脱敏单测、MVC、服务测试、`JdbcRuntimeControlStoreIT` 两个死信处置场景、OpenAPI 公共契约门禁 |

## 3. 崩溃恢复不变量

`RuntimeHappyPathE2EIT.killedWorkerLeaseIsRecoveredAndItsLateResultIsFenced` 使用真实 PostgreSQL 与 Kafka：

1. Worker 1 claim 后关闭 Spring 应用上下文，模拟进程被强制终止；
2. 租约到期扫描把 attempt 1 终结为 `ABANDONED`，lease generation 1 终结为 `EXPIRED`；
3. 控制面重新派发，Worker 2 claim 得到 generation 2、attempt 2；
4. Worker 1 的迟到结果被拒绝；Worker 2 成功提交；
5. attempt 2 为 `SUCCEEDED`，lease generation 2 为 `RELEASED`，节点三个计数最终为 `2:2:2`。

这证明恢复不会复用旧 fencing token，也不会用迟到结果覆盖新 Worker 的事实。

## 4. 死信处置与敏感数据边界

- `POST /api/v1/dead-letters/{deadLetterId}/notes` 和 `/redispatch` 均要求 `Idempotency-Key`，使用可信 `WorkflowRequestContext` 和 RLS，不接受客户端租户 ID。
- 错误摘要和操作员备注经共享 `SensitiveTextRedactor` 处理，覆盖凭据键、Authorization/Bearer、JWT、控制字符和长度上限。
- 原脱敏备注只追加到 `audit_log.reason`；Outbox 仅包含 `noteDigest`，不复制正文。
- redispatch 在同一事务锁定 OPEN 死信，调用失败节点 replay 创建新实例/节点，再将死信单向处置为 `REQUEUED`；源实例、节点和 task_execution 快照保持不变。
- 相同请求在 24 小时幂等窗口回放首次响应；跨租户查找返回通用不存在语义并回滚幂等 claim。

## 5. 已执行验证

专项机器门禁：

```text
pnpm run runtime:g5:verify
PASS：G5-01～G5-08 源码、数据库、API、测试与 ADR 绑定；G5-12/G5-13 显式延期。

pnpm run runtime:g5:test
PASS：正向契约 + 10/10 负向变异。
```

真实 PostgreSQL 集成测试：

```text
JdbcRuntimeControlStoreIT        8/8
JdbcRuntimeSchedulingStoreIT   11/11
JdbcRuntimeTransitionStoreIT    8/8
控制面合计                     27/27
JdbcWorkerTaskStoreIT            7/7
```

真实 PostgreSQL/Kafka E2E：

```text
RuntimeHappyPathE2EIT            2/2
```

专项组合命令：

```powershell
.\mvnw.cmd -B -pl worker,test-support -am verify `
  '-Dit.test=JdbcWorkerTaskStoreIT,RuntimeHappyPathE2EIT' `
  '-Dfailsafe.failIfNoSpecifiedTests=false'
```

结果为五模块 Reactor `BUILD SUCCESS`。随后执行 Java 21.0.12 / Maven 3.9.16 全仓 `./mvnw.cmd -B clean verify`，五模块全部 `SUCCESS`，完整单元测试、PostgreSQL/Kafka 集成测试、JaCoCo、Checkstyle、PMD/CPD 和 SpotBugs 全部通过。

桌面正式 Git 工作树最终执行 `pnpm verify`，连续运行约 131 秒并完整通过：公共契约为 23 个 operation、46 个 OpenAPI Schema 和 23 类反向变异；追踪矩阵为 36 条需求、123/123 个总控任务、10/10 个 MVP Gate；配置契约为 3 份 env、53 个键、6 个 profile、2 个无回退生产 profile；Secret 门禁确认 665 个提交候选文件无泄漏，且合成硬编码密钥被阻断；Web Console lint/typecheck、2 个测试文件/5 个测试和生产构建全部通过。

## 6. 机器防漏门禁

`scripts/runtime/verify-reliability-kernel.mjs` 不再只检查类是否存在，而是绑定：

- G5-01～G5-08 精确任务范围及 G5-12/G5-13 延期状态；
- 迁移约束、三个计数增长点与 worker/lease/expiry fencing 条件；
- 三种退避、稳定 jitter、次数/窗口、Retry-After、外部结果查询；
- 到期扫描、ABANDONED/EXPIRED、事务 processed_event；
- 死信 API、OpenAPI 状态、RLS/idempotency/audit/Outbox/replay 生产路径；
- PostgreSQL IT 与 PostgreSQL/Kafka 强制终止恢复 E2E 的关键断言。

`test-reliability-kernel-verifier.mjs` 会对任务范围、attempt 增长时点、过期 token、指数退避、processed_event、租约终结、Authorization 脱敏、死信状态/API 和崩溃恢复 E2E 进行 10 类反向变异，任何一项缺失都会使门禁失败。
