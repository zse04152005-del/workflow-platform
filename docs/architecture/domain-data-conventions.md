# ID、时间、枚举、错误码、审计与删除约定

- 状态：Accepted
- 日期：2026-07-30
- 对应任务：G2-02
- Requirement：`REQ-DATA-001`
- 机器策略：`config/domain/conventions.json`
- 策略版本：`1.4.0`（补齐 G3-06 HTTP 412/428 前置条件错误类别）
- 校验命令：`pnpm domain:verify`、`pnpm domain:test`

本规范在建表和公开契约前统一最容易漂移的基础语义。G2-03 的 ER、G2-09 的错误响应和 G2-10 的 OpenAPI/DSL/Event Schema 必须引用本规范，不能各自发明 ID、时间或删除规则。

## 1. ID 规则

### 1.1 内部主键与事件 ID

- tenant、definition、version、instance、node、attempt、task、event 等内部标识统一使用 RFC 9562 UUIDv7。
- ID 在应用写库前生成，PostgreSQL 存储类型为 `uuid`，API/Event 使用小写、带短横线的 canonical 字符串。
- UUIDv7 的时间前缀只用于 B-tree 局部性和排障，不作为业务排序或发生时间；稳定排序始终使用 `(created_at, id)` 等显式游标。
- 不使用自增 `serial/bigserial` 暴露资源规模，不把 Kafka offset、业务键或外部 Provider ID 当内部主键。

合法示例：`0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493`。UUIDv4、大写 UUID、无短横线字符串和任意数字 ID 均不符合内部 ID 契约。

### 1.2 外部引用、业务键与幂等键

- OIDC subject、订单号、工单号、traceparent 等外部值按受限 `varchar` 原样保存，不能强制转 UUID。
- `business_key` 默认大小写敏感，作用域为 tenant + workflow；只有业务契约明确时才做规范化。
- `Idempotency-Key` 是最长 128 字符的不透明值，作用域为 tenant + operation + resource；持久化 SHA-256 摘要和脱敏/加密元数据，不在日志中输出原值。

## 2. 时间、时区与区间

- Java 权威时间类型使用 `java.time.Instant`；数据库使用 `timestamp(6) with time zone`（`timestamptz(6)`）。
- API/Event 使用 RFC 3339 UTC `Z`，最多 6 位小数，例如 `2026-07-30T11:00:00.123456Z`；不接受无时区本地时间或 `+08:00` 作为持久化规范格式。
- 写库时间默认使用同一事务的 `transaction_timestamp()`；领域计算通过注入的 `java.time.Clock`，禁止直接散落系统时钟。
- Cron 和业务日历必须保存 IANA TZDB zone id，例如 `Asia/Shanghai`；固定 offset 不能替代地区时区和 DST 规则。
- 时间区间统一为 `[start, end)`，Duration 使用 ISO-8601；DSL 不允许隐式 `now()`、随机时间或进程内定时器成为可恢复状态。
- PostgreSQL 微秒精度是比较和 checksum 的上限；Java 纳秒值写库/出库必须规范化，避免乐观比较漂移。

## 3. 枚举与未知值

| 类型 | 规范 | 示例 |
|---|---|---|
| 生命周期/状态 | `UPPER_SNAKE_CASE` | `CREATED`、`WAITING_TIMER`、`CANCEL_REQUESTED` |
| 节点/触发器/策略 discriminator | `lower_snake_case` | `http`、`human_task`、`fire_once` |

- 数据库存 `varchar + CHECK` 或受控引用表；禁止按 Java ordinal 存储。
- API/Event 收到未知的必需枚举值必须返回稳定契约错误；不能映射为第一个值、`UNKNOWN` 后继续推进，或静默选择默认分支。
- 消费者忽略未知可选字段；不支持的 Event Schema 进入隔离队列并告警。
- 同一 Major 内只能追加有兼容处理的新值；重命名、删除或改变语义属于破坏性变更。
- 状态是否终态及允许迁移由 G4-01 状态机 ADR 锁定，不能仅凭枚举顺序判断。

## 4. 稳定错误码

格式为 `<DOMAIN>_<CATEGORY>_<DETAIL>`，全大写下划线，例如：

```text
COMMON_VALIDATION_INVALID_FIELD
AUTH_AUTHENTICATION_TOKEN_EXPIRED
TENANT_AUTHORIZATION_CROSS_TENANT
DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH
DEFINITION_PRECONDITION_REQUIRED_IF_MATCH
RUNTIME_CONFLICT_STALE_VERSION
HUMAN_TASK_CONFLICT_ALREADY_DECIDED
CONNECTOR_DEPENDENCY_TIMEOUT
```

允许的 DOMAIN：`COMMON`、`AUTH`、`TENANT`、`DEFINITION`、`RUNTIME`、`SCHEDULER`、`HUMAN_TASK`、`WORKER`、`CONNECTOR`。

允许的 CATEGORY：`VALIDATION`、`AUTHENTICATION`、`AUTHORIZATION`、`NOT_FOUND`、`CONFLICT`、`PRECONDITION_FAILED`、`PRECONDITION_REQUIRED`、`RATE_LIMIT`、`DEPENDENCY`、`INTERNAL`。

- 错误码在同一 Major 内稳定、永久不复用；新增码是兼容扩展，删除/改义是破坏性变更。
- G2-09 负责把错误码映射到 RFC 9457 风格 HTTP status/type/title/detail/traceId；本任务不提前伪造 OpenAPI。
- `detail`、字段错误和日志不得包含 token、密钥、完整敏感 Payload、SQL 或内部堆栈。

## 5. 公共审计字段

可变业务记录至少包含：

| 字段 | 规则 |
|---|---|
| `created_at` / `updated_at` | `timestamptz(6)`，由数据库事务时钟写入 |
| `created_by` / `updated_by` | `<actor_type>:<stable_subject_id>`；系统身份以 `system:` 开头 |
| `version` | `bigint`，初值 0，每次有效更新 +1，客户端不可直接写 |
| `tenant_id` | 所有租户拥有记录必填；租户本身和平台级记录按模型例外 |

软删除对象额外使用 `deleted_at`、`deleted_by`、`deletion_reason`。暂停、取消、重放、发布、审批、密钥和跨租户运维等特权操作必须提交 `reason`。

追加 `audit_log` 至少记录 tenant、actor type/id、action、resource type/id、occurred_at、reason、trace_id、source_ip，以及脱敏前后摘要。禁止存储明文 Secret 或完整敏感 Payload；显示名只能作为非权威快照，权限判断使用稳定 subject ID。

本项目不建立跨模块共享的 JPA `BaseEntity`。字段约定由迁移、Repository 和 Schema 校验执行，避免共享实体破坏模块所有权。

## 6. 删除与保留策略

默认策略是“显式生命周期，而不是所有表统一软删除”（机器策略名：`explicit-lifecycle-not-generic-soft-delete`）。每个实体在 G2-03 ER 评审时必须选择且只能选择以下一种策略：

| 策略 | 适用示例 | 行为 |
|---|---|---|
| lifecycle-status | tenant、project、workflow_definition | disable/deactivate + 审计，保留引用完整性 |
| soft-delete | connector、secret_reference | 写 deleted 字段；默认查询排除；唯一约束使用 partial index |
| revocation | membership、role_binding | 写 revoked_at/by/reason，保留授权历史 |
| immutable-history | workflow_version、task_execution、audit_log、human_task_decision | 完全不可变记录拒绝更新；task_execution 只允许一次 RUNNING→终态，human_task 只允许受保护的转交或首次终态；终态不更新/不软删；按受控保留策略归档清理 |
| retention-purge | workflow_instance、node_instance、schedule_occurrence、outbox_event、processed_event、idempotency_record、dead_letter、worker_lease、human_task、quota_usage | 超过恢复/重放/去重/迟到结果/决策证据/配额计量窗口后批量归档/清理并记录指标；追加历史与各类终态不可改写 |
| regulated-erasure | personal_data | 经批准的擦除流程匿名化/删除，保留非敏感审计 tombstone |

普通业务 API 禁止物理删除。硬删除只允许数据保留/合规擦除作业，在校验 tenant、引用、法律保留和备份影响后执行。不可变发布版本、已收口 attempt 和审计历史不能通过软删除伪装为不存在；Task、幂等、死信、Worker lease 与 Human Task 的 open→terminal 更新不是历史改写，必须由数据库 trigger 保证只能发生一次。

## 7. 源码与迁移守卫

- 核心源码禁止 `java.time.LocalDateTime` 和 `java.sql.Timestamp`；使用 `Instant` + 显式 IANA zone。
- Flyway SQL 禁止 `timestamp without time zone`、`serial` 和 `bigserial`；内部主键使用 UUIDv7。
- 守卫扫描 `control-plane/src`、`worker/src`、`shared-contracts/src` 和迁移目录，并以正反向 fixture 防止规则空跑。

## 8. 后续任务接口

- G2-03：把每个实体映射到 ID、审计和删除策略，锁定 ER/索引/约束/RLS。
- G2-04～G2-08：迁移脚本必须满足 UUID、UTC、审计、version 和删除策略。
- G2-09：实现 RFC 9457 错误响应与字段错误。
- G2-10：在 OpenAPI/DSL/Event Schema 中复用 ID、时间、枚举和错误码约定。
- G4-01：锁定实例/节点/attempt 的终态和允许迁移，不按枚举 ordinal 推断。

验收命令：

```powershell
pnpm domain:verify
pnpm domain:test
```
