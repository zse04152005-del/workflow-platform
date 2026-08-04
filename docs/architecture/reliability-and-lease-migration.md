# 可靠消息、幂等、死信与 Worker Lease V4 数据库迁移

- 状态：Accepted
- 日期：2026-07-30
- 对应任务：G2-07
- Requirements：`REQ-DATA-001`、`REQ-OUTBOX-001`、`REQ-LEASE-001`、`REQ-RETRY-001`
- 决策基线：`ADR-002`
- 迁移：`V4__create_reliability_and_leases.sql`
- 校验命令：`pnpm database:g2-07:verify`、`pnpm database:g2-07:test`、`pnpm database:g2-07:integration`

V4 在不修改 V1～V3 的前提下创建 `outbox_event`、`processed_event`、`idempotency_record`、`dead_letter`、`worker_lease`。它只完成可靠性记录、约束、索引、RLS、最小权限与生命周期的数据库边界；Kafka Publisher/Consumer、HTTP 幂等拦截器、Worker claim/result 协议、退避和重放执行仍由 G4/G5 实现，不能把“表已创建”描述为端到端可靠消息已经交付。

## 1. 实现前痛点复审

| 痛点 | V4 决策 |
|---|---|
| Outbox/Worker 的高竞争扫描索引未匹配 tenant RLS | Q-05、Publisher lease recovery、active lease expiry 均改为 tenant-leading partial index |
| Outbox 的 Publisher 字段可半填、发布后可被回拨 | CHECK 强制 lease 字段成对；trigger 只允许版本/attempt/时间单调推进，published 后不可更新 |
| 幂等记录只约束 COMPLETED 的部分响应字段 | PROCESSING 不得携带响应；COMPLETED/FAILED 必须保存合法 HTTP status 与 64 位响应摘要；只允许一次终态收口 |
| dead_letter 的 task_execution 可能属于另一 node | V4 为 task_execution 添加三元唯一目标，以 `(tenant_id,task_execution_id,node_instance_id)` 复合 FK 绑定 |
| worker_lease 被建模为 immutable-audit，却必须心跳 | 改用 mutable-audit；每次心跳必须更新 audit/version，终态后不可改写 |
| 仅有 generation 唯一性，缺少数据库 fencing | INSERT/heartbeat trigger 锁定并校验 node 当前 generation、worker_id、lease_expire_at；一个 node 仅允许一个 ACTIVE lease |

## 2. 表与不变量

| 表 | 列 | FK | CHECK | 显式索引 | 数据库生命周期 |
|---|---:|---:|---:|---:|---|
| outbox_event | 20 | 1 | 7 | 3 | version 0 未领取写入；领取/失败/确认单调推进；published 后不可更新 |
| processed_event | 10 | 1 | 4 | 2 | `(consumer_name,event_id)` 去重；运行角色只 SELECT/INSERT；UPDATE trigger 拒绝改写 |
| idempotency_record | 15 | 1 | 4 | 2 | tenant+operation+key_digest 唯一；每个有效窗口一次 PROCESSING→COMPLETED/FAILED；过期后由 V6 受限重置 |
| dead_letter | 17 | 3 | 5 | 3 | attempt 必须属于同 node；一次 OPEN→REQUEUED/RESOLVED/DISMISSED |
| worker_lease | 15 | 2 | 5 | 3（含一个 partial unique） | ACTIVE 心跳可单调延长；只接受 node 当前 fencing token；终态不可更新 |

合计 5 表、77 列、8 个 `ON DELETE RESTRICT` 外键、25 个 CHECK、13 个显式索引、5 个生命周期 guard。`task_execution` 新增一个只为 dead-letter 三元 FK 服务的前置唯一绑定。

## 3. Outbox 与消费者去重

- `id` 就是稳定 event id；事件身份、aggregate、schema、partition key、Payload/ref、摘要、发生时间和 trace snapshot 在写入后不可变。
- `payload` 与 `payload_ref` 严格 XOR，`payload_digest` 必须为 64 位小写十六进制。
- Publisher 按 `(tenant_id,next_publish_at,id) WHERE published_at IS NULL` 使用 `FOR UPDATE SKIP LOCKED`；数据库索引与 RLS 的 tenant 前缀一致。
- `publisher_id` 与 `publish_lease_until` 必须同时为空或同时有效；发布确认时同时清空 lease 并写 `published_at`。
- `publish_attempts` 每次更新最多增加 1，`next_publish_at` 不得回拨，`version` 每次恰好增加 1。
- `processed_event` 使用全局 event id 加 consumer name 去重，业务插入后不可 UPDATE；受控保留作业仍可在去重窗口后清理记录。

数据库无法单独证明业务状态变更与 Outbox 位于同一应用事务，也不能模拟“Broker 已确认、published_at 尚未提交”的崩溃窗口；这些由 G4-07/G5-07 的事务服务与故障注入测试完成。

## 4. HTTP 幂等

`idempotency_record` 只保存 SHA-256 key/request/response 摘要和受权 response ref，不保存明文 Idempotency-Key。唯一键为 `(tenant_id,operation_scope,key_digest)`：

1. 首次插入只能是 `PROCESSING/version=0`，且不含响应字段。
2. 相同 key 的并发插入由唯一约束收敛；服务层必须再比较 `request_digest`。
3. 每个有效窗口仅允许一次 `PROCESSING→COMPLETED|FAILED`，终态必须有 100～599 的响应码和响应摘要。
4. 未过期终态、请求身份、过期窗口和首次响应快照均不可二次改写。

G3-06 的 24 小时 API replay window 由 V6 追加 guard 落实：只有 `expires_at <= transaction_timestamp()` 的行才能在 `FOR UPDATE` 串行化后重置为 `PROCESSING`；tenant/scope/key 与创建证据保持不变，version 恰好 +1，旧响应必须清空并设置新的未来 expiry。

数据库表不等于 API 已实现；同键同摘要回放、同键异摘要 409 和 in-flight 响应仍由 G4-03/G4-04 完成。

## 5. Dead letter 与不可改写处置

- `dead_letter` 表示业务任务最终失败，不替代 Kafka 基础设施隔离队列。
- 可选 `task_execution_id` 一旦存在，必须通过三元 FK 证明 attempt 属于 `node_instance_id`，不能只做到“同 tenant”。
- error code 使用稳定三段式大写命名；错误摘要必须脱敏，Payload 只保存 ref+digest 成对信息。
- 初始只能是 `OPEN`；一次处置可变为 `REQUEUED`、`RESOLVED` 或 `DISMISSED`，并把 disposition/version 恰好增加 1；失败快照和终态不可覆盖。
- 重放是创建新的状态推进和审计记录，不把原死信改回 OPEN。

## 6. Worker lease 与 fencing

创建 ACTIVE lease 前，trigger 对对应 `node_instance` 取 `FOR KEY SHARE`，并要求以下字段完全相等：

```text
tenant_id + node_instance_id + lease_generation + worker_id + expires_at
```

心跳事务应先条件更新 node 的当前 lease expiry，再更新 `worker_lease`；第二步 trigger 会重新校验 node 当前 generation/worker/expiry。旧 generation、旧 worker 或未同步的 expiry 均以 SQLSTATE `55000` 拒绝。ACTIVE 心跳必须让 `last_heartbeat_at` 与 `expires_at` 单调增加，version 恰好 +1；RELEASED/EXPIRED/FENCED 只允许从 ACTIVE 进入一次，终态后不可更新。

这一 guard 是数据库最后防线，不替代服务层的 expected version/status 条件更新。G5 仍需验证多 Worker 竞争、进程 kill、心跳并发、租约回收和迟到结果拒绝。

## 7. 租户隔离与权限

- 五表全部 `ENABLE` + `FORCE ROW LEVEL SECURITY`，policy 是 `tenant_id = app_tenant_id()`；没有 tenant context 时 fail closed。
- application 与 platform-admin 角色只有在显式设置事务级 tenant context 后才能访问行；平台管理员身份不绕过这些业务表 RLS。
- outbox/idempotency/dead_letter/worker_lease 仅授予 SELECT/INSERT/UPDATE；processed_event 仅授予 SELECT/INSERT；五表均不授予 DELETE。
- 五个 trigger function 都是 `SECURITY INVOKER`、固定 `search_path=pg_catalog,pg_temp`，PUBLIC 和运行角色不能直接 EXECUTE。
- 表 owner 仍受 FORCE RLS；终态/追加记录由 trigger 防止 owner 误改。物理清理由专用保留作业和审计流程控制，不经普通业务角色。

## 8. 迁移、验证与保留

V4 采用只追加 Flyway：先把 V1～V3 迁到 V3、写入两租户 definition/runtime/attempt fixture，再升级到 V4并重复执行 migrate。真实 PostgreSQL 套件验证 catalog、升级数据、约束、索引、trigger、函数权限、应用角色、平台管理员与 owner 三类负向行为。

Outbox 的保留窗口不得短于最大发布重试与消费者 replay 窗口；processed_event 不得短于 Kafka replay 窗口；idempotency 不得短于客户端重试窗口；worker_lease 不得短于最大迟到结果与事故调查窗口。MVP 暂不物理分区，G2-11 在代表性数据上复核查询计划和写放大后再决定月分区。

已知告警：固定 Flyway 11.7.2 对 PostgreSQL 18.4 输出“最高已测试 PostgreSQL 17”的兼容告警；当前迁移已真实通过，但版本支持矩阵仍由 G2-11 复评。
