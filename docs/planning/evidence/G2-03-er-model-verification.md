# G2-03 ER 图、约束、索引与分区策略验收记录

- 状态：`[x]`
- 完成日期：2026-07-30
- Requirement：`REQ-DATA-001`（保持 `partial`；真实 Flyway 表与 PostgreSQL 验证仍为 planned）
- 权威项目目录：`C:\Users\Administrator\Desktop\workflow-platform`

## 1. 实现与评审范围

建立 G2-04～G2-08 的机器 ER 基线和三幅 Mermaid ER 图：

| 维度 | 结果 |
|---|---:|
| 核心表 | 22 |
| 展开后列 | 322 |
| 外键 | 39 |
| 显式索引 | 47 |
| 关键查询契约 | 10 |
| 延后按月分区候选 | 9 |
| 启用 RLS 的表 | 22/22 |
| 外键删除动作 | 39/39 `RESTRICT` |

迁移分组严格锁定：

- G2-04：tenant、project、membership、role、role_binding。
- G2-05：workflow_definition、workflow_version、trigger_config、connector、secret_reference。
- G2-06：workflow_instance、node_instance、task_execution、schedule_occurrence。
- G2-07：outbox_event、processed_event、idempotency_record、dead_letter、worker_lease。
- G2-08：human_task、audit_log、quota_usage。

## 2. 关键设计结论

- 所有内部表使用 UUIDv7 `uuid` 主键和 `timestamptz(6)`；可变记录使用审计字段与 bigint version。
- tenant 表按 `id = tenant context` 保护，audit_log 允许受控平台行，其余业务表 tenant_id 必填。
- 跨业务表使用 `(tenant_id, referenced_id)` 复合外键和目标 `(tenant_id,id)` 唯一约束，数据库层阻止跨租户引用。
- membership/role_binding/可选 project 配置使用 `NULLS NOT DISTINCT` 或 partial unique，防止 NULL 绕过唯一约束。
- Scheduler、租约、Outbox、Cron、人工待办、死信和审计均有面向真实竞争查询的部分/复合索引。
- PostgreSQL 分区唯一约束会影响单列 UUID PK 与外键，因此 MVP 不盲目预分区；9 张高增长表先记录按月 RANGE 候选和触发条件，只有实测后迁移。
- `outbox_event.aggregate_id` 是多态聚合引用，不伪造无法成立的 FK；完整性由同事务 OutboxPort 和 aggregate_type 规则保证。
- 设计显式标注 secret/Payload/error/source_ip 等敏感列，禁止复制到日志、Trace 或审计明文。

## 3. 关键文件

| 文件 | 用途 |
|---|---|
| `config/database/er-model.json` | 22 表、322 列、owner、迁移组、FK、unique/check、索引、RLS、删除、保留与分区机器源 |
| `docs/architecture/database-er-model.md` | 三幅 Mermaid ER、22 表数据字典、10 个查询契约、分区与风险评审 |
| `scripts/database/verify-er-model.mjs` | 跨架构 owner/领域约定/表/约束/索引/文档一致性校验 |
| `scripts/database/test-er-model.mjs` | 正向模型与九类反向控制 |
| `config/traceability/requirements.json` | REQ-DATA-001 增加 ER 真实证据并保留 Flyway/Testcontainers planned 状态 |
| `package.json` | 增加 `er:verify/test` 并接入 `pnpm verify`/CI |

## 4. 正反向验证

以下九类反例全部被阻断：

1. 重复表名。
2. tenant 表缺失 tenant_id。
3. secret_reference 关闭 RLS。
4. node -> workflow 外键移除 tenant_id，形成跨租户引用窗口。
5. task_execution 使用 `ON DELETE CASCADE`。
6. outbox_event owner 从 platform adapter 漂移为 runtime。
7. 删除 Q-03 Scheduler 到期扫描索引。
8. audit_log 使用非时间列作为月分区键。
9. ER 文档路径不存在。

机器校验还逐项确认外键目标是 PK/unique、源列有 leading index、自然唯一键包含 tenant 或解释全局作用域、数据库对象名不重复且不超过 PostgreSQL 限制、分区键为 `timestamptz(6)`、文档包含所有表和查询契约。

## 5. 验收命令与结果

在桌面权威目录执行：

```powershell
pnpm run er:verify
pnpm run er:test
pnpm run verify
node scripts/database/verify-migrations.mjs
node scripts/database/test-migration-verifier.mjs
pnpm run secrets:verify
```

结果：

- ER：PASS；22 表、322 列、39 FK、47 索引、10 查询契约、9 分区候选、22/22 RLS、全部 FK RESTRICT。
- ER 反向测试：PASS；上述九类错误全部被阻断。
- `pnpm verify`：PASS；架构、领域约定、ER、治理、追踪、配置、Secret、Web lint/类型、3 项前端测试和生产构建全部通过。
- Migration：真实迁移数仍为 0；空基线、未登记、登记和 checksum 篡改门禁通过。
- 最终 Gitleaks：155 个 Git 提交候选文件无泄漏，合成硬编码密码反例被阻断。

关键输出：

```text
ER model verification PASS: tables=22, columns=322, foreignKeys=39, indexes=47, queryContracts=10, partitionCandidates=9, RLS=22/22, onDelete=RESTRICT.
ER model tests PASS: positive model accepted; duplicate table, missing tenant_id, disabled RLS, cross-tenant FK, cascade delete, owner drift, missing query index, invalid partition key, and missing document blocked.
Migration verification PASS: 0 migration(s), unique ordered versions, immutable SHA-256 catalog.
Migration verifier self-test PASS: unregistered migration blocked, registered migration accepted, checksum tampering blocked.
Secret verification PASS: 155 commit candidates clean; synthetic hard-coded secret blocked.
```

## 6. 验收结论与遗留风险

G2-03 验收通过。ER、owner、tenant/RLS、FK、唯一约束、索引、删除/保留和分区策略已有机器源、可读图与反向门禁。

本任务严格保持“设计证据”状态：Flyway 目录仍为 0 迁移，没有声称任何业务表已存在。G2-04～G2-08 负责按组创建表，G2-11 负责用真实 PostgreSQL/Testcontainers 验证空库/升级、约束、RLS 和 Q-01～Q-10 查询计划；在此之前 REQ-DATA-001 继续为 `partial`。

下一步：G2-04——创建 tenant/project/membership/role/role_binding 初始 Flyway 迁移。
