# ADR-002：可靠消息、幂等、任务租约与一致性边界

- 状态：Accepted
- 日期：2026-07-30
- 决策人：项目开发团队
- 关联任务：G1-02
- 前置决策：ADR-001

## 背景

工作流平台需要在数据库提交、消息发布、Worker 崩溃、消息重复、网络超时和外部接口响应不确定的情况下保持可恢复。PostgreSQL、Kafka 和外部业务系统之间不存在一个可依赖的全局事务，因此平台不能承诺任意外部副作用 exactly-once。

本 ADR 定义权威状态、事务边界、Outbox、消费者去重、Worker 领取、租约、fencing token、接口幂等以及异常恢复规则。后续表结构、状态机和故障测试必须满足这些约束。

## 决策摘要

1. PostgreSQL 是实例、节点、执行 attempt、调度和人工任务的唯一事实源。
2. 数据库状态变更与 Outbox 事件在同一事务提交；Kafka 采用至少一次投递。
3. Kafka 消息只是“数据库中可能有工作”的通知，不能单独证明任务可执行。
4. Worker 消费消息后，必须以条件更新成功领取数据库租约，才能执行任务。
5. 每次领取生成单调递增的 `lease_generation`，它同时作为 fencing token；只有当前 token 的心跳和结果有效。
6. 平台内部通过事务、乐观锁和消费者去重保持单次有效状态推进；外部副作用通过稳定 execution id、下游幂等、结果查询和补偿降低重复风险。
7. 数据库不可用时停止创建、领取和推进任务，不在内存中产生不可持久化的权威状态。

## 1. 权威数据与核心记录

| 记录 | 作用 | 关键约束 |
|---|---|---|
| `workflow_instance` | 工作流实例权威状态 | `status + version` 条件更新；终态不可逆 |
| `node_instance` | 节点当前状态与调度信息 | `(instance_id, node_id)` 唯一；保存 `dispatch_count`、`current_attempt_no`、`lease_generation` |
| `task_execution` | 每次真正开始执行的不可变历史 | `(node_instance_id, attempt_no)` 唯一；成功/失败结果只能完成一次 |
| `outbox_event` | 待发布领域事件 | `event_id` 全局唯一；与业务状态同事务写入 |
| `processed_event` | 消费者已处理事件 | `(consumer_name, event_id)` 唯一 |
| `idempotency_record` | 写接口首次请求及响应 | `(tenant_id, operation_scope, idempotency_key)` 唯一；保存请求摘要 |
| `dead_letter` | 业务任务最终失败记录 | 一个失败节点可有多次处置记录，但历史不可覆盖 |

MQ 中的数据不是权威记录；Redis 也不得保存无法从 PostgreSQL 重建的状态。

## 2. 数据库事务边界

### 2.1 命令事务

一次会推进业务状态的命令必须在单个 PostgreSQL 事务中完成：

1. 读取并校验当前状态、版本、租户和权限。
2. 使用 expected status/version 更新实例或节点。
3. 写入 attempt、幂等记录或审计记录等同事务数据。
4. 写入一个或多个 Outbox 事件。
5. 提交后才向调用方返回成功。

事务回滚时，业务变更和 Outbox 必须同时消失。禁止先发 Kafka 再提交数据库，也禁止提交数据库后由请求线程“尽力发送”而不落 Outbox。

### 2.2 外部调用边界

HTTP 等外部调用绝不包在长数据库事务中：

1. Worker 先用短事务领取租约。
2. 再用短事务将节点从 `DISPATCHED` 变为 `RUNNING` 并创建 `task_execution`。
3. 事务提交后执行外部调用。
4. 最后用短事务携带 fencing token 上报结果并推进状态。

这样避免长时间持锁，但无法消除“外部调用成功、结果落库前 Worker 崩溃”的不确定窗口。该窗口由第 7 节的外部副作用策略处理。

## 3. Outbox 发布

### 3.1 写入规则

`outbox_event` 至少包含：

- `event_id`、`event_type`、`schema_version`、`aggregate_type`、`aggregate_id`
- `tenant_id`、`partition_key`、`occurred_at`、`trace_context`
- `payload` 或对象存储引用、`created_at`
- `publish_attempts`、`next_publish_at`、`published_at`
- Publisher 领取字段 `publisher_id`、`publish_lease_until`

事件 Payload 不放明文密钥，默认只包含消费者所需的最小数据。

### 3.2 Publisher 算法

1. 使用 `FOR UPDATE SKIP LOCKED` 分批领取 `published_at IS NULL AND next_publish_at <= now()` 的事件。
2. 写入 Publisher 短租约后立即提交领取事务。
3. 以 `instance_id` 作为工作流运行事件的默认 Kafka key，保证同实例在同 Topic/Partition 内的相对顺序。
4. Kafka Producer 使用 `acks=all` 和幂等 Producer，但不能因此假设跨数据库 exactly-once。
5. 收到 Broker 确认后更新 `published_at`；失败则增加 `publish_attempts` 并指数退避。
6. Publisher 在“Kafka 已接收、数据库尚未标记”时崩溃，会再次发布同一 `event_id`，消费者必须去重。
7. 过期的 Publisher 租约可由其他实例接管。

MVP 使用数据库轮询 Outbox。只有压测证明轮询成为瓶颈时，才评估 CDC/Debezium，并另写 ADR。

## 4. Kafka 投递与消费者去重

- 交付语义为 at-least-once，不启用自动提交后即视为业务完成。
- 消费者识别 `event_id`、`event_type` 和 `schema_version`；未知必需版本进入基础设施隔离队列并告警。
- 对纯数据库消费者，业务变更与插入 `processed_event` 必须在同一事务中完成。
- 重复事件若已存在 `processed_event`，消费者直接确认，不重复推进状态。
- Kafka offset 只代表消费进度，不代表工作流权威状态；数据库提交后、offset 提交前崩溃只会导致安全的重复消费。
- 基础设施消息解析失败与业务节点最终失败分开记录：前者进入 Kafka 隔离/DLQ，后者进入平台 `dead_letter` 表。

Worker 产生外部副作用时，`processed_event` 本身无法实现 exactly-once；任务领取条件和稳定 execution id 才是主要保护。

## 5. Worker 领取、租约与心跳

### 5.1 领取条件

Worker 收到任务事件后，执行类似以下语义的条件更新：

```sql
UPDATE node_instance
SET status = 'DISPATCHED',
    worker_id = :worker_id,
    lease_generation = lease_generation + 1,
    lease_expire_at = :lease_expire_at,
    dispatch_count = dispatch_count + 1,
    version = version + 1
WHERE id = :node_instance_id
  AND status = 'READY'
  AND next_schedule_at <= now()
  AND version = :expected_version;
```

只有更新一行的 Worker 获得执行权。更新零行表示消息重复、状态已变化或任务已被其他 Worker 领取，消费者应重新读取并安全结束，不能强行覆盖。

### 5.2 三个计数必须分离

- `dispatch_count`：每次成功领取都增加，用于观察消息/租约层面的重复派发。
- `attempt_no`：只有节点真正进入 `RUNNING` 并创建 `task_execution` 时才增加，受业务最大重试次数限制。
- `lease_generation`：每次新租约都增加，作为 fencing token；不因心跳增加。

若 Worker 在 `DISPATCHED` 后、`RUNNING` 前崩溃，重新派发不消耗业务 attempt。若已经创建 attempt 后崩溃，该 attempt 标记为 `ABANDONED` 或 `TIMED_OUT`，再按重试策略决定是否创建下一 attempt。

### 5.3 心跳与结果

- 心跳更新条件必须同时匹配 `node_id + worker_id + lease_generation + 非终态`。
- 结果上报必须匹配当前 `lease_generation` 和允许的当前状态。
- 新 Worker 获得下一代租约后，旧 Worker 的心跳、成功或失败结果全部返回 stale lease，不能覆盖当前状态。
- 节点一旦 `SUCCEEDED`，迟到失败和重复成功均不得改变结果。
- Worker 优雅停机时先停止领取；已开始任务在宽限期内完成，否则停止心跳并交由租约恢复。

## 6. 租约恢复与重试

Scheduler 扫描已过期的 `DISPATCHED/RUNNING` 节点：

1. 以状态、版本和过期时间条件更新，避免多个 Scheduler 重复回收。
2. `DISPATCHED` 且未创建 attempt：回到 `READY`，写新的 TaskReady Outbox，不增加 attempt。
3. `RUNNING`：当前 attempt 标记为 `ABANDONED/TIMED_OUT`；根据错误类别、最大次数和最大重试窗口进入 `RETRY_WAIT` 或 `FAILED`。
4. 到达 `next_schedule_at` 后由 Scheduler 迁移为 `READY` 并写 Outbox。
5. fixed/linear/exponential 退避都加入 jitter；尊重受信任下游的 `Retry-After` 上限。

重试只创建新的 `task_execution`，历史 attempt 永不改写或删除。

## 7. 外部副作用与不确定结果

每个 attempt 生成稳定且唯一的 `execution_id`。同一 attempt 内的网络重试使用相同 execution id，新业务 attempt 使用新的 execution id，并同时携带稳定的 `node_execution_key` 供下游按业务需求去重。

HTTP Worker 的处理优先级：

1. 向支持幂等的下游透传 `Idempotency-Key`/execution id。
2. 连接器可配置结果查询接口；写请求超时后先查询，再决定是否重试。
3. 无幂等也无查询能力的高风险操作必须人工确认，并明确标记为可能重复。
4. 二期可配置补偿节点，但补偿也只是新的外部操作，不能被描述为绝对回滚。

平台对外声明“至少一次执行 + 业务幂等辅助”，不声明任意外部接口 exactly-once。

## 8. HTTP 写接口幂等

需要创建实例、审批、重放、取消或产生其他不可安全重复副作用的接口接收 `Idempotency-Key`。草稿更新仍以 ETag/If-Match 解决并发覆盖，不能用幂等键代替版本检查。

`idempotency_record` 的作用域为：

`tenant_id + operation_scope + idempotency_key`

其中 `operation_scope` 至少包含 API 操作和关键资源标识。记录请求方法、规范化路径、请求摘要、状态、首次响应码、响应摘要/引用和过期时间。

处理规则：

- 首次请求：插入记录，并与业务变更在同一事务完成。
- 同键同摘要且已完成：返回首次响应，不重复执行业务。
- 同键不同摘要：返回 `409 Conflict`。
- 同键同摘要且仍在处理：返回 `409/202` 和可重试提示，不并发执行第二次。
- 记录保留时间不得短于对应操作的最大客户端重试窗口；高风险运维操作使用更长保留期。

## 9. 故障场景的确定行为

| 故障点 | 预期行为 |
|---|---|
| 业务事务提交前崩溃 | 状态和 Outbox 一起回滚 |
| 业务事务提交后、Publisher 发送前崩溃 | 其他 Publisher 取得过期租约并发送 |
| Kafka 已接收、`published_at` 更新前崩溃 | 同一 event id 重复发布，消费者去重 |
| 消费后、领取任务前崩溃 | 消息重投，尚无执行权和副作用 |
| 领取后、进入 RUNNING 前崩溃 | 租约过期后重新派发，不增加 attempt |
| 外部调用前崩溃 | attempt 超时后按策略重试 |
| 外部调用成功、结果落库前崩溃 | 进入不确定窗口；使用下游幂等/结果查询/人工介入 |
| 旧 Worker 晚到上报 | fencing token 不匹配，拒绝更新 |
| 数据库不可用 | 停止创建、领取、心跳和状态推进；不产生内存权威状态 |
| Kafka 不可用 | 状态与 Outbox 保留；Publisher 退避并告警，恢复后继续发布 |

## 10. 背压、保留与观测

- Outbox、Ready、Retry Wait、过期租约和死信均设置队列深度与最老年龄指标。
- 连接器和租户具有独立并发上限；积压时优先保护控制面和高优先级任务。
- 已发布 Outbox 和 processed event 按恢复/重复窗口保留后分区归档，不立即删除。
- 指标不使用 tenant id、workflow id、event id 等高基数标签；明细进入日志、Trace 或查询库。
- 事件 Payload、错误和审计字段执行统一脱敏，禁止记录 token、密钥和完整敏感响应。

## 11. 不采用的方案

### 分布式两阶段提交

不采用。Kafka、PostgreSQL 和任意外部服务之间无法形成实用的统一事务，并会显著降低可用性和可运维性。

### 仅依靠 Kafka exactly-once

不采用。Kafka 事务不能覆盖 PostgreSQL 与外部 HTTP 副作用，仍需要 Outbox、数据库条件更新和业务幂等。

### Redis 分布式锁作为执行权来源

不采用。核心状态已在 PostgreSQL；再引入独立锁真相会增加分叉窗口。执行权由数据库条件更新、租约和 fencing token 决定。

### MQ 消息即任务真相

不采用。重复、丢失感知和消息保留都不能替代可查询、可审计的持久化节点状态。

## 12. 必须持续成立的不变量

1. 数据库中不存在已提交的“需要发布事件”的状态变更却没有对应 Outbox。
2. 一个节点在任意时刻最多有一个当前有效 lease generation。
3. 非当前 fencing token 不能延长租约或完成节点。
4. 成功终态不能被重复或迟到消息逆转。
5. 重复 event id 对同一消费者最多产生一次有效数据库状态推进。
6. 同一幂等作用域和 key 的不同请求摘要绝不共用结果。
7. 数据库不可用时不生成仅存在于内存或 Kafka 的权威实例状态。
8. 历史 attempt 不被重试、重放或租约恢复覆盖。

## 验收标准

ADR-002 在满足以下条件时视为完成：

- 明确数据库、Kafka、Redis 和外部系统的一致性边界。
- 明确 Outbox 的写入、领取、发送、确认、重复与恢复语义。
- 明确消费者去重、Kafka offset 与数据库事务的关系。
- 明确 Worker 领取、三类计数、心跳、租约恢复和 fencing 规则。
- 明确 HTTP 幂等作用域、请求摘要冲突和首次响应回放规则。
- 为主要故障窗口定义确定行为，并列出可转化为自动化测试的不变量。
