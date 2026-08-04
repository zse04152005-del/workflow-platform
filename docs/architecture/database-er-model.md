# 核心数据库 ER、约束、索引与分区基线

- 状态：Accepted
- 日期：2026-07-30
- 对应任务：G2-03
- Requirement：`REQ-DATA-001`
- 机器模型：`config/database/er-model.json`
- 模型版本：`1.4.0`（G2-08 Human Task、append-only 审计与非重叠配额窗口落库前约束复审）
- 校验命令：`pnpm er:verify`、`pnpm er:test`

本文锁定 G2-04～G2-08 创建的 22 张核心表能力级 Schema。22 张表已由 V1～V5 创建，并经过逐版本升级、重复迁移和真实 PostgreSQL 三角色验收；后续能力仍必须逐项与机器模型、领域约定和迁移 checksum 门禁比对。

## 1. 设计不变量

1. 内部主键使用应用生成的 RFC 9562 UUIDv7，PostgreSQL 类型为 `uuid`。
2. 除 `tenant` 自身和允许平台行的 `audit_log` 外，业务表 `tenant_id NOT NULL` 并启用 RLS。
3. 跨租户实体外键使用 `(tenant_id, referenced_id) -> (tenant_id, id)`；目标表具有 `(tenant_id,id)` 唯一约束，数据库层阻断混租户引用。
4. 所有外键默认 `ON DELETE RESTRICT`，不通过级联删除抹去定义、执行、审批或审计证据。
5. 可变记录使用 `created_at/by`、`updated_at/by`、`version`；时间为 `timestamptz(6)`；敏感字段只存密文、受权引用或脱敏摘要。
6. PostgreSQL 是唯一事实源；Outbox、processed event、幂等和租约分别有独立唯一约束，不依赖 Kafka offset 或 Redis 锁维持正确性。
7. 分区是经过测量后启用的演进方案。MVP 首先保留简单主键/外键；启用分区前必须验证 PostgreSQL 分区唯一约束和引用迁移。
8. Runtime instance 必须用三元复合 FK 证明 workflow_version 属于同一个 definition；schedule occurrence 必须用 timezone/misfire 复合 FK 证明其来自同一份 Cron trigger 快照。
9. task_execution 不是任意可变记录：只能以 RUNNING/version=0 插入，并由触发器放行一次 RUNNING→终态收口；终态 UPDATE/DELETE 对应用、平台管理员和表 owner 一律拒绝。

## 2. ER 图：租户与定义

```mermaid
erDiagram
    TENANT ||--o{ PROJECT : owns
    TENANT ||--o{ MEMBERSHIP : authorizes
    PROJECT o|--o{ MEMBERSHIP : scopes
    TENANT ||--o{ ROLE : defines
    MEMBERSHIP ||--o{ ROLE_BINDING : receives
    ROLE ||--o{ ROLE_BINDING : grants
    PROJECT ||--o{ WORKFLOW_DEFINITION : contains
    WORKFLOW_DEFINITION ||--o{ WORKFLOW_VERSION : publishes
    WORKFLOW_VERSION ||--o{ TRIGGER_CONFIG : binds
    TENANT ||--o{ CONNECTOR : manages
    PROJECT o|--o{ CONNECTOR : scopes
    TENANT ||--o{ SECRET_REFERENCE : manages
    PROJECT o|--o{ SECRET_REFERENCE : scopes

    TENANT {
        uuid id PK
        varchar normalized_name UK
        varchar status
        bigint version
    }
    PROJECT {
        uuid id PK
        uuid tenant_id FK
        varchar normalized_name
        varchar status
    }
    MEMBERSHIP {
        uuid id PK
        uuid tenant_id FK
        uuid project_id FK_NULLABLE
        varchar principal_subject
        varchar status
    }
    ROLE {
        uuid id PK
        uuid tenant_id FK
        varchar code
        text_array permission_codes
    }
    ROLE_BINDING {
        uuid id PK
        uuid membership_id FK
        uuid role_id FK
        varchar scope_type
        uuid scope_id
    }
    WORKFLOW_DEFINITION {
        uuid id PK
        uuid project_id FK
        jsonb draft_dsl
        bigint draft_revision
        char draft_checksum
    }
    WORKFLOW_VERSION {
        uuid id PK
        uuid definition_id FK
        integer version_no
        integer schema_version
        jsonb dsl
        char checksum
    }
    TRIGGER_CONFIG {
        uuid id PK
        uuid workflow_version_id FK
        varchar trigger_key
        varchar trigger_type
        jsonb config
    }
    CONNECTOR {
        uuid id PK
        uuid project_id FK_NULLABLE
        varchar connector_type
        varchar endpoint
        timestamptz deleted_at
    }
    SECRET_REFERENCE {
        uuid id PK
        uuid project_id FK_NULLABLE
        varchar provider
        bytea ciphertext
        varchar key_version
    }
```

## 3. ER 图：运行时与可靠性

```mermaid
erDiagram
    WORKFLOW_DEFINITION ||--o{ WORKFLOW_INSTANCE : starts
    WORKFLOW_VERSION ||--o{ WORKFLOW_INSTANCE : snapshots
    WORKFLOW_INSTANCE ||--o{ NODE_INSTANCE : contains
    NODE_INSTANCE ||--o{ TASK_EXECUTION : attempts
    TRIGGER_CONFIG ||--o{ SCHEDULE_OCCURRENCE : schedules
    WORKFLOW_INSTANCE o|--o{ SCHEDULE_OCCURRENCE : created_by
    NODE_INSTANCE ||--o{ WORKER_LEASE : leases
    NODE_INSTANCE ||--o{ DEAD_LETTER : fails_to
    TASK_EXECUTION o|--o{ DEAD_LETTER : explains
    TENANT ||--o{ OUTBOX_EVENT : emits
    TENANT ||--o{ PROCESSED_EVENT : deduplicates
    TENANT ||--o{ IDEMPOTENCY_RECORD : protects

    WORKFLOW_INSTANCE {
        uuid id PK
        uuid workflow_version_id FK
        varchar business_key
        varchar status
        char input_digest
        bigint version
    }
    NODE_INSTANCE {
        uuid id PK
        uuid workflow_instance_id FK
        varchar node_id
        varchar status
        integer attempt_no
        integer dispatch_count
        bigint lease_generation
        timestamptz next_schedule_at
    }
    TASK_EXECUTION {
        uuid id PK
        uuid node_instance_id FK
        integer attempt_no
        uuid execution_id UK
        varchar status
        char input_digest
        char output_digest
    }
    SCHEDULE_OCCURRENCE {
        uuid id PK
        uuid trigger_config_id FK
        timestamptz scheduled_at
        varchar timezone
        varchar misfire_policy
        varchar status
    }
    OUTBOX_EVENT {
        uuid id PK_EVENT_ID
        uuid aggregate_id
        varchar event_type
        integer schema_version
        timestamptz next_publish_at
        timestamptz published_at
    }
    PROCESSED_EVENT {
        uuid id PK
        varchar consumer_name
        uuid event_id
        timestamptz processed_at
    }
    IDEMPOTENCY_RECORD {
        uuid id PK
        varchar operation_scope
        char key_digest
        char request_digest
        varchar status
        timestamptz expires_at
    }
    DEAD_LETTER {
        uuid id PK
        uuid node_instance_id FK
        uuid task_execution_id FK_NULLABLE
        varchar error_code
        varchar status
    }
    WORKER_LEASE {
        uuid id PK
        uuid node_instance_id FK
        bigint lease_generation
        varchar worker_id
        varchar status
        timestamptz expires_at
    }
```

## 4. ER 图：人工任务、审计与配额

```mermaid
erDiagram
    NODE_INSTANCE ||--|| HUMAN_TASK : waits_for
    TENANT ||--o{ AUDIT_LOG : records
    TENANT ||--o{ QUOTA_USAGE : meters

    HUMAN_TASK {
        uuid id PK
        uuid node_instance_id FK_UK
        varchar candidate_role_code
        varchar assignee_subject
        varchar status
        timestamptz deadline_at
        varchar decision
        bigint version
    }
    AUDIT_LOG {
        uuid id PK
        uuid tenant_id FK_NULLABLE
        varchar actor_type
        varchar actor_id
        varchar action
        varchar resource_type
        varchar resource_id
        timestamptz occurred_at
        varchar trace_id
    }
    QUOTA_USAGE {
        uuid id PK
        uuid tenant_id FK
        varchar resource_type
        timestamptz window_start
        timestamptz window_end
        bigint used_value
        bigint limit_value
    }
```

## 5. 数据字典与关键约束

| 表 | Owner / 迁移 | 租户与 RLS | 关键唯一约束 | 竞争/查询索引 | 删除与分区 |
|---|---|---|---|---|---|
| tenant | tenant / G2-04 | `id = tenant context` 或平台管理员 | normalized_name | status,id | lifecycle；不分区 |
| project | tenant / G2-04 | tenant_id 必填 | tenant+name；tenant+id | tenant+status | lifecycle；不分区 |
| membership | tenant / G2-04 | tenant_id；复合 project FK | tenant+project+subject，NULLS NOT DISTINCT | subject/status；project/status | revocation；不分区 |
| role | tenant / G2-04 | tenant_id | tenant+code；tenant+id | tenant+status | lifecycle；不分区 |
| role_binding | tenant / G2-04 | tenant_id；复合 membership/role FK | assignment，NULLS NOT DISTINCT | membership/status；role/status | revocation；不分区 |
| workflow_definition | definition / G2-05 | tenant_id；复合 project FK | project+normalized_name；tenant+id | project/status；updated cursor | lifecycle；不分区 |
| workflow_version | definition / G2-05 | tenant_id；复合 definition FK | definition+version；definition+checksum；G2-06 instance 三元绑定 | version_no DESC | immutable；不分区 |
| trigger_config | definition / G2-05 | tenant_id；复合 version FK | version+trigger_key；G2-06 Cron 快照绑定 | version+type | immutable；不分区 |
| connector | definition / G2-05 | tenant_id；可选 project | active name partial unique | active project/status | soft-delete；不分区 |
| secret_reference | security / G2-05 | tenant_id；可选 project | active name partial unique | active project/status | soft-delete；不分区 |
| workflow_instance | runtime / G2-06 | tenant_id；definition/version 三元绑定 | tenant+id | tenant cursor；business key；status | retention；测量后按 created_at 月分区 |
| node_instance | runtime / G2-06 | tenant_id；复合 instance FK；状态与 schedule/lease/error/output 字段一致 | instance+node_id | tenant-leading due scan；lease expiry；instance detail | retention；MVP 不分区 |
| task_execution | runtime / G2-06 | tenant_id；复合 node FK；单次 RUNNING→终态 trigger；G2-07 attempt-node 三元绑定 | node+attempt；execution_id | attempt history；running/failed | 终态不可变；候选 created_at 月分区 |
| schedule_occurrence | scheduler / G2-06 | tenant_id；Cron timezone/misfire 快照 FK；复合 instance FK | trigger+scheduled_at | tenant-leading due scan；快照历史 | retention；候选 scheduled_at 月分区 |
| outbox_event | platform adapter / G2-07 | tenant_id；Publisher 最小权限；事件快照/发布单向 guard | event id / tenant+id | tenant-leading unpublished SKIP LOCKED；publisher lease | retention；候选 created_at 月分区 |
| processed_event | runtime / G2-07 | tenant_id；Consumer 仅 SELECT/INSERT；UPDATE guard | consumer+event_id | processed_at retention | 追加式去重；去重窗口后受控清理 |
| idempotency_record | runtime / G2-07 | tenant_id；请求身份不可改；PROCESSING→单次终态 | tenant+operation+key_digest | expiry；stuck PROCESSING | 首次响应回放窗口后清理 |
| dead_letter | runtime / G2-07 | tenant_id；task_execution+node 三元 FK；OPEN→单次处置 | tenant+id | open inbox；node/attempt history | 元数据保留；敏感 Payload 更短 |
| worker_lease | runtime / G2-07 | tenant_id；当前 node generation/worker/expiry fencing guard | node+generation；active node partial unique | tenant-leading active expiry；lease history | 心跳可变、终态不可变；迟到结果窗口后清理 |
| human_task | human-task / G2-08 | tenant_id；复合 node FK；WAITING node 检查；单次转交/首次终态 guard | 一个 node 一个 task | tenant-leading assignee/role/deadline | 终态不可改；随实例保留；不分区 |
| audit_log | audit / G2-08 | tenant 可空；平台行仅平台管理员；append-only guard | 追加写，无业务唯一键 | tenant-leading time/resource/trace | immutable；候选 occurred_at 月分区 |
| quota_usage | tenant / G2-08 | tenant_id；同 tenant/resource 窗口不相交；用量单调 | tenant+resource+window_start | tenant-leading current/retention | limit/window 快照不可改；候选月分区 |

所有 tenant 表的 `(tenant_id,id)` 唯一约束是复合外键的目标，不是多余装饰。所有自然唯一键都包含 tenant；`membership`、`role_binding` 和可选 project 的配置使用 PostgreSQL `NULLS NOT DISTINCT`，避免 NULL 绕过唯一性。

## 6. 十个关键查询契约

| ID | 查询 | 索引 |
|---|---|---|
| Q-01 | 租户实例稳定游标 | idx_workflow_instance_tenant_created |
| Q-02 | business_key 关联搜索 | idx_workflow_instance_business_key |
| Q-03 | READY/RETRY_WAIT/WAITING_TIMER 到期扫描 | idx_node_instance_schedule |
| Q-04 | DISPATCHED/RUNNING 过期租约回收 | idx_node_instance_lease_expiry |
| Q-05 | 未发布 Outbox `SKIP LOCKED` 扫描 | idx_outbox_event_unpublished |
| Q-06 | Cron occurrence 到期扫描 | idx_schedule_occurrence_due |
| Q-07 | 用户人工待办 | idx_human_task_assignee |
| Q-08 | 角色人工待办 | idx_human_task_candidate |
| Q-09 | Open dead-letter 运维队列 | idx_dead_letter_open |
| Q-10 | 租户审计稳定游标 | idx_audit_log_tenant_time |

G2-11 必须在真实 PostgreSQL/Testcontainers 中使用代表性数据验证这些索引和 `EXPLAIN` 计划；本任务只锁定查询契约，不伪造查询耗时。

## 7. 分区与保留评审

候选表：workflow_instance、task_execution、schedule_occurrence、outbox_event、processed_event、idempotency_record、worker_lease、audit_log、quota_usage。

- MVP 初始迁移可先不创建物理分区，避免 UUID 单列主键、复合外键和分区唯一约束提前复杂化。
- 只有真实容量与查询计划显示收益时启用按月 RANGE 分区；启用前新增 ADR/迁移计划，验证唯一约束、外键、RLS、append-only trigger、备份恢复和旧分区归档。
- Outbox/processed/idempotency 的清理窗口不得短于发布重试、Kafka 重放、客户端重试或故障恢复窗口。
- task_execution/audit_log 的“immutable”指业务历史不可改写；合规保留期后的分区归档/清理是受控运维动作，不是普通 API 删除。

## 8. 风险审查结论

- 用复合租户外键和 RLS 双重防护跨租户引用；应用 Repository 过滤仍然必须存在。
- `outbox_event.aggregate_id` 是多聚合引用，故不建多态 FK；其完整性由同事务 OutboxPort 和 aggregate_type 校验负责。
- `scope_id` 属于 TENANT/PROJECT 多态 scope，使用 CHECK + application validation；G6 RBAC 实现时评估拆表是否更清晰。
- role.permission_codes 以 text[] 保存稳定权限代码，避免在权限目录尚未锁定前扩张 role_permission 表；G6-03 可通过 ADR 演进。
- G2-05 实现前复审把 draft/version checksum 收紧为 64 位小写 SHA-256，把 DSL/trigger/connector JSON 根收紧为 object，并为 trigger key、connector type、secret provider 应用 lower_snake_case 数据库约束；Cron 专属字段不得出现在 manual/http 触发器中。
- G2-06 实现前复审新增 Definition-Version 三元绑定与 Cron timezone/misfire 快照绑定；补齐 workflow/node/task/schedule 的状态-字段、时间、摘要、租约约束，并把 Q-03/Q-04/Q-06 高竞争索引改为 tenant-leading partial index。
- G2-07 实现前复审把 Outbox/Worker due 索引改为 tenant-leading；补齐发布租约字段成对、Payload/摘要/时间约束；把 idempotency 首次响应与终态绑定；用 `(tenant_id,task_execution_id,node_instance_id)` 阻断死信错配 attempt；把 Worker lease 改为可审计心跳记录，并以 node 当前 generation/worker/expiry 做数据库 fencing。
- G2-08 实现前复审为 Human Task 补齐最近转交主体/时间、WAITING node 锁定、单次转交与首次终态 guard；把 deadline/audit trace/quota retention 索引改为 tenant-leading；以 grants + trigger 双重保护 append-only audit，并用 advisory lock + 区间检测阻断同 tenant/resource 的重叠 quota 窗口。
- task_execution 允许的唯一更新是触发器保护的 RUNNING→终态收口；函数采用 SECURITY INVOKER、固定安全 search_path 且不向 PUBLIC/运行角色授予直接 EXECUTE，终态后任何角色均不能再次 UPDATE/DELETE。
- secret_reference 强制 local provider 只能保存非空 ciphertext、外部 provider 只能保存非空 external_key，两种材料严格互斥，key_version 不能为空；其软删除三字段必须同时为空或同时有值。ciphertext/external_key、Payload ref 和错误摘要均标记敏感，日志/Trace/审计不得复制明文。
- 所有高竞争扫描都有部分索引，但实际锁行为、选择性和写放大必须由 G2-11/G10 实测。

## 9. 维护规则

- `config/database/er-model.json` 是 ER 机器权威源；表、owner、列、约束、索引、RLS、删除和分区变化必须先改模型与本文。设计模型不表示 Flyway 表已创建，只有迁移集成验收通过后才可声称已落库。
- G2-04～G2-08 的每个迁移组必须与模型表集合完全一致，禁止遗漏或私增未追踪表。
- 迁移文件只追加并登记 SHA-256；G2-11 验证空库、重复迁移、约束、RLS 和查询计划。
- ER 模型明确为设计证据，只有真实 Flyway/Testcontainers 验收后，对应表产物才能标记 implemented。

验收命令：

```powershell
pnpm er:verify
pnpm er:test
```
