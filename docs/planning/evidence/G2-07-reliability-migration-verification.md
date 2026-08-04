# G2-07 可靠性表与 Worker Lease V4 迁移验收证据

- 任务：G2-07 创建 `outbox_event` / `processed_event` / `idempotency_record` / `dead_letter` / `worker_lease`
- 日期：2026-07-30
- 结论：通过
- 迁移：`control-plane/src/main/resources/db/migration/V4__create_reliability_and_leases.sql`
- 架构说明：`docs/architecture/reliability-and-lease-migration.md`

## 1. 实现结果

| 项目 | 验收值 |
|---|---:|
| 新表 | 5 |
| 展开列 | 77 |
| `ON DELETE RESTRICT` 外键 | 8 |
| CHECK | 25 |
| 显式索引 | 13（含 1 个 partial unique） |
| 前置 task attempt/node 复合绑定 | 1 |
| `ENABLE` + `FORCE RLS` | 5/5 |
| 生命周期 guard function/trigger | 5/5 |
| 反向变异类别 | 15 |

关键不变量：

- Outbox Payload/ref XOR、64 位摘要、Publisher lease 成对、attempt/next time/version 单调、published 后不可更新。
- processed event 使用 `(consumer_name,event_id)` 去重，只向运行角色授予 SELECT/INSERT，UPDATE trigger 阻断 owner 误改。
- idempotency 使用 tenant+operation+key digest 唯一，PROCESSING 不含响应，COMPLETED/FAILED 必须有合法响应码和摘要，只能收口一次。
- dead letter 的可选 attempt 通过 `(tenant_id,task_execution_id,node_instance_id)` 绑定到同一 node；OPEN 只能处置一次，失败快照不可改。
- worker lease 使用 node 当前 generation/worker/expiry 做 `FOR KEY SHARE` fencing；ACTIVE 心跳/expiry/version 单调；一个 node 仅有一个 ACTIVE lease；终态不可改写。
- Outbox 与 Worker 高竞争 partial index 均以 tenant 开头，与强制 RLS 的查询方式一致。

## 2. 设计复审修正

实现前将 ER 模型从 `1.2.0` 升到 `1.3.0`，领域约定从 `1.1.0` 升到 `1.2.0`，修正：非 tenant-leading due index、Outbox 半租约、幂等响应弱配对、死信 attempt/node 错配、Worker lease 审计类型矛盾和终态可覆盖六类风险。V1～V3 未修改；G2-06 验证器只忽略明确标记 `introducedIn=G2-07` 的后续前置约束，冻结 V3 仍以原 69 列/10 FK/13 索引/26 CHECK 通过。

## 3. 不可变迁移校验和

| 文件 | SHA-256 |
|---|---|
| `V1__create_tenant_and_rbac.sql` | `e25262cbb5910192511f0817a1fd5f0929788bcb02f37ffc62bb182248c0288e` |
| `V2__create_definition_and_integrations.sql` | `1ee8a70c4e8a0a6a3d2e00f77bbdd11aa34276886d24af40582e35145478fe01` |
| `V3__create_runtime_and_schedule.sql` | `6e1519197f6e8b17c0bc1b2e17701ea0878741e6af5da1561090d845207acb66` |
| `V4__create_reliability_and_leases.sql` | `394e585b0e2b22ce505851bc198c42ed40738540861f948640bc4ff6122c2961` |

`pnpm migrations:verify` 通过 4 个有序、唯一且已登记的 Flyway 迁移；`pnpm migrations:test` 继续阻断未登记文件和 checksum 篡改。

## 4. 静态与反向门禁

通过：

```text
pnpm database:g2-07:verify
pnpm database:g2-07:test
```

反向测试实际阻断：缺失 FORCE RLS、弱化 attempt-node FK、前置唯一绑定漂移、Outbox/Worker tenant 前缀丢失、ACTIVE partial unique 漂移、弱化发布租约/幂等响应/死信处置、Worker audit 列缺失、processed guard 缺失、fencing generation 校验缺失、processed UPDATE 授权、DELETE 授权和未追踪表。

## 5. 真实 PostgreSQL 验收

命令：

```text
pnpm database:g2-07:integration
```

固定镜像：PostgreSQL `18.4-bookworm` digest、Flyway `11.7.2-alpine` digest。测试先执行 V1～V3，写入两租户 V3 definition/runtime/attempt fixture，再执行 V4 和第二次 V4 migrate。结果：

- V3→V4 升级成功，V3 数据完整保留，重复 migrate 无操作。
- application role：Outbox claim/publish、processed dedup、idempotency 收口、dead-letter 处置、Worker heartbeat/release 全部成功。
- 负向：双 Payload、发布后改写、重复消费、processed UPDATE、跳过 PROCESSING、幂等/死信/lease 终态改写、attempt-node 错配、旧 fencing token、DELETE 全部按预期拒绝。
- RLS：无 tenant context 零可见；tenant A/B 双向隔离；跨租户更新零行；平台管理员也必须设置事务 tenant context。
- catalog/owner：77 列、8 FK、25 CHECK、13 索引、5 policy、5 guard、安全 search_path、函数 ACL、表 owner 与 grant 均符合模型；owner 改写同样被 guard 拒绝。

同时复跑 `database:g2-04:integration`、`database:g2-05:integration`、`database:g2-06:integration`，V1 空库、V1→V2、V2→V3 冻结回归全部通过。

## 6. 整仓门禁

- `pnpm verify`：架构/领域/ER/G2-04～G2-07/治理/追踪/配置/Secret/ESLint/TypeScript/Vitest/Vite 全通过；合成硬编码 Secret 被固定 digest Gitleaks 阻断。
- Maven 在线 `clean verify`：5/5 Reactor 模块成功；Checkstyle、Spotless、PMD/CPD、SpotBugs、ArchUnit、Spring context 和测试全部通过。
- Maven 离线 `-o clean verify`：5/5 Reactor 模块成功，证明依赖缓存可复现。
- `pnpm compose:verify`：7 个固定 digest 服务、5 个 healthcheck、2 个 one-shot initializer、仅 loopback 发布端口。
- `pnpm ci:verify`：4 个 Job、9 个 SHA 固定 Action、最小权限与固定 actionlint 镜像通过。
- 临时 `workflow-g2-*` Docker 容器/网络由套件 `finally` 自动清理。

## 7. 明确未完成边界

本任务只声明 V4 数据库能力 implemented。以下仍是后续任务：

- G4：业务状态推进与 Outbox 同事务、Publisher/Consumer、HTTP/手动触发幂等、Worker claim/result 协议。
- G5：Kafka 崩溃窗口、并发 heartbeat/kill/lease recovery、迟到结果、重试退避、死信 API 与重放。
- G2-11：代表性数据上的 `EXPLAIN`、锁竞争、选择性、写放大和分区阈值。

Flyway 11.7.2 对 PostgreSQL 18.4 仍输出“最高已测试 PostgreSQL 17”告警；真实回归已通过，但版本支持矩阵由 G2-11 复评。

## 8. 下一步

G2-08：创建 `human_task`、`audit_log`、`quota_usage`，继续使用只追加 V5、复合 tenant FK、强制 RLS、追加审计/人工决策不可改写和 tenant-leading inbox/quota 索引。
