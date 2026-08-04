# ADR-003：Runtime 实例、节点与 attempt 状态机

- 状态：Accepted
- 日期：2026-07-31
- 任务：G4-01
- Requirement：`REQ-STATE-001`、`REQ-RETRY-001`、`REQ-CRON-001`
- 机器矩阵：`config/runtime/state-machine.json`

## 1. 背景与审计结论

G2-06 已把 `workflow_instance`、`node_instance`、`task_execution` 和 `schedule_occurrence` 的状态字段、时间字段、租约字段和终态数据库约束落库，但 CHECK 只能描述“某一行状态与字段是否自洽”，不能阻止应用把任意旧状态直接 UPDATE 成任意新状态。若没有一份唯一迁移图，G4-02、Scheduler、Worker 和 API 很容易各自定义不同的暂停、取消、重试和恢复语义。

本 ADR 锁定状态机语义；它不提前实现 G4-02 的 Repository/事务组件、G4-05 的 DAG 推进器、G4-06 的调度扫描器或 G4-09 的 Worker claim。后续实现必须消费机器矩阵，不能通过枚举 ordinal 推断合法性。

## 2. 共同迁移协议

每次状态迁移都必须携带：`entityId`、`expectedStatus`、`expectedVersion`、命令、可信 actor/tenant context 和 trace context。Repository 使用条件更新：

```sql
UPDATE <table>
   SET status = :to_status,
       version = version + 1,
       updated_at = :now,
       updated_by = :actor
 WHERE tenant_id = :tenant
   AND id = :id
   AND status = :expected_status
   AND version = :expected_version;
```

影响行数为 0 时，组件必须先区分资源不存在、`VERSION_CONFLICT` 和 `INVALID_STATE_TRANSITION`，不能静默覆盖。影响行数为 1 时，状态行、追加审计和对应 Outbox event 在同一数据库事务提交；任一部分失败则全部回滚。版本严格增加 1，terminal 状态无 outgoing transition；重试只能插入新的 attempt，不能改写历史 attempt。

共同规则：

1. PostgreSQL 是唯一运行时权威状态，内存、Kafka message 或 Redis lock 不能形成第二事实源；
2. transition event 使用稳定 `eventType`、实体 ID、from/to、version、reason 和 traceId，payload 脱敏；
3. Worker 更新节点或 attempt 必须同时验证当前 `worker_id`、未过期 lease 和 fencing token；迟到 token 只能得到稳定冲突；
4. 终态只能由定义好的完成、失败、超时、放弃或取消命令进入，终态二次更新一律拒绝；
5. 状态字符串是稳定契约，禁止以 enum ordinal、字典序或“非终态即可运行”推断迁移。

## 3. Workflow instance 状态机

状态：`CREATED`、`RUNNING`、`PAUSE_REQUESTED`、`PAUSED`、`CANCEL_REQUESTED`、`CANCELLED`、`SUCCEEDED`、`FAILED`。初始状态为 `CREATED`；终态为 `CANCELLED`、`SUCCEEDED`、`FAILED`。

| From | To | 命令 | 核心 guard |
|---|---|---|---|
| CREATED | RUNNING | `start` | expected status/version、definition version 已绑定 |
| RUNNING | PAUSE_REQUESTED | `request_pause` | actor 已授权 |
| RUNNING | CANCEL_REQUESTED | `request_cancel` | actor 已授权 |
| RUNNING | SUCCEEDED | `complete_success` | 所有节点成功终态 |
| RUNNING | FAILED | `complete_failure` | 已记录失败原因 |
| PAUSE_REQUESTED | PAUSED | `pause` | 没有 active execution |
| PAUSE_REQUESTED | CANCEL_REQUESTED | `request_cancel` | actor 已授权 |
| PAUSE_REQUESTED | FAILED | `pause_failure` | 已记录失败原因 |
| PAUSED | RUNNING | `resume` | actor 已授权 |
| PAUSED | CANCEL_REQUESTED | `request_cancel` | actor 已授权 |
| CANCEL_REQUESTED | CANCELLED | `cancel` | 没有 active execution |

`start` 设置 `started_at`；三类终态迁移设置 `completed_at`。不允许从 `CREATED` 直接取消、从 `PAUSED` 直接成功、从任何终态恢复或从请求态跳过请求阶段。

## 4. Node instance 状态机

状态：`PENDING`、`READY`、`DISPATCHED`、`RUNNING`、`RETRY_WAIT`、`WAITING_TIMER`、`WAITING_APPROVAL`、`SUCCEEDED`、`FAILED`、`CANCELLED`。初始状态为 `PENDING`；终态为 `SUCCEEDED`、`FAILED`、`CANCELLED`。

合法迁移按执行阶段分组：

- `PENDING -> READY | CANCELLED`：DAG 前驱满足或实例正在取消；
- `READY -> DISPATCHED | WAITING_TIMER | WAITING_APPROVAL | CANCELLED`：调度领取、Delay、Human Task 或取消；
- `DISPATCHED -> RUNNING | READY | CANCELLED`：当前 lease/fencing claim、租约过期重新排队或取消；
- `RUNNING -> READY | SUCCEEDED | FAILED | RETRY_WAIT | WAITING_TIMER | WAITING_APPROVAL | CANCELLED`：租约到期恢复、Worker 结果、重试、Delay、审批或取消；
- `RETRY_WAIT -> READY | CANCELLED`：到期重排或取消；
- `WAITING_TIMER -> READY | CANCELLED`：到期唤醒或取消；
- `WAITING_APPROVAL -> READY | SUCCEEDED | FAILED | CANCELLED`：审批继续、成功、拒绝或取消。

进入 `DISPATCHED/RUNNING` 必须写入成对 worker/lease 字段；离开执行态必须清除 lease。进入 `READY/RETRY_WAIT/WAITING_TIMER` 必须设置 `next_schedule_at`，进入 `DISPATCHED/WAITING_APPROVAL` 或终态必须清除它，避免违反 V3 的状态字段 CHECK。输出只允许在成功迁移写入，错误摘要只允许在失败/重试迁移写入。

## 5. Attempt（task_execution）状态机

attempt 只能以 `RUNNING`、`version = 0` 插入。合法终态迁移为：

| From | To | 触发者 | 规则 |
|---|---|---|---|
| RUNNING | SUCCEEDED | Worker | 当前 lease/fencing token，输出摘要合法 |
| RUNNING | FAILED | Worker | 当前 lease/fencing token，脱敏错误已记录 |
| RUNNING | TIMED_OUT | Scheduler/Worker | execution deadline 已到 |
| RUNNING | ABANDONED | Scheduler | lease 过期且恢复策略允许放弃 |
| RUNNING | CANCELLED | Runtime/Scheduler | 实例取消且 execution 已停止 |

所有 attempt 终态必须设置 `finished_at`，version 只允许从 0（或当前实现允许的单次版本）增加 1；任何终态 UPDATE/DELETE 都拒绝。重试由 Node `RETRY_WAIT` 创建更高 `attempt_no` 的新 RUNNING 行，旧 attempt 保留不可改写。

## 6. Schedule occurrence 辅助状态机

为防止 G4-06 重新发明调度语义，本 ADR 同时锁定：`PENDING -> CLAIMED | SKIPPED`、`CLAIMED -> FIRED | FAILED | SKIPPED`。`PENDING` 领取使用 `scheduled_at`、tenant-leading index 和 `SKIP LOCKED`；`FIRED/SKIPPED/FAILED` 是终态。Cron timezone/misfire 快照不能漂移，occurrence 的 claim/version 也遵循共同迁移协议。DST、misfire 计算、catch-up 上限和禁止重叠的算法仍属于 G4-15。

## 7. 并发、故障与观测不变量

| 场景 | 确定结果 |
|---|---|
| 两个 writer 使用同一 version | 一个成功，另一个 `VERSION_CONFLICT`，不得产生第二个 audit/outbox |
| 非法 from/to | `INVALID_STATE_TRANSITION`，状态/version 不变 |
| Worker 迟到 fencing token | 拒绝更新，不延长 lease，不完成 node/attempt |
| 事务提交前故障 | 状态、审计、Outbox 一起回滚 |
| Outbox 发送前故障 | 状态已提交，Publisher 按 Outbox 重试 |
| 已成功终态收到迟到失败 | 终态 guard 拒绝，保留原成功事实 |
| 数据库不可用 | 不生成内存权威状态，不领取、不心跳、不推进 |

指标使用状态、原因、模块和结果等低基数标签；tenant/workflow/node/event ID 进入日志或 trace，不直接作为指标标签。任何错误、事件和审计字段都不得包含 Secret、token 或完整外部响应。

## 8. 后续任务边界

- G4-02：实现统一迁移组件、expected status/version 乐观锁、同事务审计/Outbox；
- G4-05：实现 DAG 前驱满足和 `PENDING -> READY` 推进；
- G4-06/G4-15：实现安全调度扫描、Cron/DST/misfire 和 occurrence 算法；
- G4-09/G5：实现 claim、heartbeat、lease generation/fencing 和 Worker 结果协议；
- G4-16：实现实例/节点/attempt 游标分页查询。

## 9. G4-02 实施状态

G4-02 已由 `RuntimeStateTransitionService` 和 `JdbcRuntimeTransitionStore` 实施：Java allow-list 精确覆盖本 ADR 的 45 条边（含 G5-06 增补的 `RUNNING -> READY:lease_expired`），所有调用使用 expected status/version 条件更新；状态、追加审计和 Outbox 同一 PostgreSQL 事务提交。0 行结果、RLS tenant context、typed side effect、Worker fencing 与脱敏事件规则见 `docs/architecture/runtime-state-transition-component.md`。后续 Runtime/Scheduler/Worker 代码必须复用此入口。
