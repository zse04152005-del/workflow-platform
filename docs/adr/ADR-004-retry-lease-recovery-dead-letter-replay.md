# ADR-004：重试、租约恢复、迟到结果、死信与重放

Status: Accepted — 2026-08-04

## 1. 背景与目标

平台采用至少一次投递。Kafka 的 `task.ready` 只是“数据库中可能存在可执行任务”的提示，PostgreSQL 中的实例、节点、attempt、租约、Outbox、消费去重与死信记录才是事实源。本 ADR 收口 G5-01～G5-13 的运行语义，避免 Scheduler、Worker 与运维 API 分别解释重试、崩溃恢复和人工处置。

本 ADR 不承诺 exactly-once 外部副作用。对外部写操作，稳定 `execution_id`、下游幂等键与可选结果查询钩子共同降低重复副作用风险。

## 2. 三类计数必须独立

| 计数 | 唯一增长时点 | 不增长的场景 | 用途 |
|---|---|---|---|
| `dispatch_count` | Scheduler 成功把节点从 `READY` 派发为 `DISPATCHED` | 重复 Kafka 消息、心跳、结果上报、仅进入 `RETRY_WAIT` | 观察数据库任务被派发的次数 |
| `attempt_no` | Worker 以当前 fencing token 成功把节点从 `DISPATCHED` 领取为 `RUNNING`，并在同一事务创建新的 `task_execution` | 派发后、领取前崩溃；心跳；仅回收 `DISPATCHED` 租约 | 业务执行 attempt 序号与最大次数判断 |
| `lease_generation` | 每次创建新的派发租约时单调增加 | 心跳续租、结果上报 | 当前执行权的 fencing token |

三者均为非负单调计数。`task_execution(tenant_id,node_instance_id,attempt_no)` 和 `worker_lease(tenant_id,node_instance_id,lease_generation)` 唯一；一个节点最多存在一个 `ACTIVE` Worker lease。`dispatch_count` 与 `lease_generation` 通常在派发时同时增长，但禁止把它们与 `attempt_no` 合并为一个字段。

## 3. Claim、心跳与迟到结果

Worker 必须用一个短事务完成：锁定 `DISPATCHED` 节点、校验未过期租约和当前 worker/token、节点进入 `RUNNING`、增加 `attempt_no`、创建 RUNNING attempt、创建 ACTIVE lease、写审计与 Outbox。影响行数不是 1 时不得执行插件。

心跳和结果写入必须同时匹配：

- `tenant_id` 与 `node_instance_id`；
- 当前 `worker_id`；
- 当前 `lease_generation`；
- `lease_expire_at > transaction_timestamp()`；
- 允许的 expected status/version。

任一条件失配即为 stale fence。旧 Worker 的成功、失败和心跳都不得修改节点、attempt、租约、审计、Outbox 或消费去重记录。节点已成功后，重复或迟到的失败也不得逆转终态。

## 4. 错误分类与退避

错误先映射为稳定代码和可重试布尔值，再进入策略计算：

- 配置、Schema、权限、策略拒绝、非 408/429 的 HTTP 4xx：不可重试；
- 执行超时、中断、网络传输失败、HTTP 408/429/5xx：可重试；
- 未知插件异常只落稳定 `NODE_EXECUTION_UNEXPECTED` 与脱敏摘要，不持久化异常消息或堆栈。

策略使用发布时冻结到节点快照的 `fixed`、`linear` 或 `exponential`：

- fixed：`initialDelay`；
- linear：`initialDelay + increment × (completedAttempts - 1)`；
- exponential：`initialDelay × multiplier^(completedAttempts - 1)`；
- `maxDelay` 限制单次策略延迟；`jitterRatio` 在 `[-ratio,+ratio]` 内有界扰动；
- `Retry-After` 只能增加、不能缩短策略延迟，最终单次延迟不超过 24 小时。

只有 `attempt_no < maxAttempts` 且“首个 attempt 开始时间到下一次计划时间”不超过 `maxElapsedTime` 才进入 `RETRY_WAIT`；否则终结为 `FAILED` 并写死信。策略缺失时，可重试错误也安全终结，禁止无界重试。

对可能产生不确定外部结果的写操作，Worker 在盲目重试前调用匹配节点类型的 `ExternalResultQueryHook`。查询确认成功或确定失败时使用查询结果；查询异常、无结果或仍不确定时安全回退到原可重试失败。

## 5. 租约到期与 Worker 崩溃恢复

Scheduler 使用租户前缀索引和 `FOR UPDATE SKIP LOCKED` 扫描到期的 `DISPATCHED/RUNNING` 节点：

- `DISPATCHED` 到期：回到 `READY`，不增加 `attempt_no`；下一次真实派发再增加 `dispatch_count/lease_generation`；
- `RUNNING` 到期：当前 RUNNING attempt 在同一恢复事务标记为 `ABANDONED`，ACTIVE lease 标记为 `EXPIRED`，节点回到 `READY`；下一次派发/领取创建更高 attempt；
- 暂停中的实例允许回收到安全的 `READY`，但暂停状态阻止新派发；取消中的实例由协作式取消规则收敛。

恢复事务任一写入失败必须整体回滚。强制关闭 Worker 后不需要进程内清理才能恢复；旧 Worker 即使恢复运行，也因 fencing token 失效而无法提交结果。

## 6. 消费去重与事务边界

消费者以 `(consumer_name,event_id)` 为唯一去重键。节点结果、attempt 终结、lease 释放、`processed_event`、审计与 Outbox 必须在同一数据库事务提交。重复事件在看到已提交的 `processed_event` 后直接结束；业务事务回滚时不得留下去重记录，否则会丢失重试机会。

`processed_event` 是追加式记录，运行角色只有 SELECT/INSERT 权限，数据库 guard 拒绝 UPDATE。它不替代 Worker claim/fencing，也不能将外部副作用变成 exactly once。

## 7. 死信、人工备注与再次派发

最大次数/窗口耗尽或不可重试失败写入 `dead_letter`。记录只保存稳定错误代码、经过统一敏感文本脱敏和长度限制的摘要，以及成对的 payload reference/SHA-256；禁止异常堆栈、认证头、令牌或密钥材料。

人工备注是追加式审计事实：原备注先统一脱敏，再写入 append-only `audit_log.reason`，Outbox 只携带备注摘要。备注操作必须有 `Idempotency-Key`、租户 RLS、操作者与 trace。

“再次派发”不会把源终态节点或历史 attempt 改回可变状态。它在一个事务中把 OPEN 死信处置为 `REQUEUED`，并复用“从失败节点重放”创建新的目标实例/节点；目标失败节点从 `READY` 开始，后续 claim 创建新的 attempt。源实例、源节点和源 attempt 保持不可改写。重复同键回放首次响应，不创建第二个目标实例；已处置死信不能再次处置。

## 8. 实例控制与 Payload 边界

失败节点重放和新实例重放都创建新的状态事实，不覆盖历史 attempt。暂停阻止新派发但保留持久化计时器；取消是协作式的，不能声称撤销已经发生的外部副作用。

大 Payload 使用租户隔离的 MinIO/S3 对象引用、SHA-256 和保留类别；G5-12 已以真实 MinIO 验证阈值外置、授权读取、摘要/元数据校验、归档标签、保留期拒绝删除和到期删除。G5-13 已注入 Outbox 发布后未标记、Kafka 重复、真实 TLS 网络停滞超时和 PostgreSQL 短时不可用，并验证无重复推进或半提交；Kafka 积压等更广泛的容量故障场景继续由 G10 扩展。

## 9. 拒绝的方案

- 用 Kafka offset 作为业务完成事实：无法覆盖数据库提交与 offset 提交之间的崩溃窗口。
- 用 Redis 锁作为执行权事实：会引入与 PostgreSQL 状态分叉的第二真相。
- 让旧 Worker 的“最终成功”覆盖新租约：破坏 fencing，可能逆转已重试后的正确结果。
- 直接把 FAILED 源节点改回 READY：破坏终态与历史不可改写；人工再次派发必须创建新的目标状态事实。
- 在内存中保存死信备注或处置计数：重启后丢失且无法审计，不能作为 G5-08 完成证据。
