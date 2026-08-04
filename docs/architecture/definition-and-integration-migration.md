# 工作流定义、版本、触发器与集成配置迁移

- 状态：Accepted
- 日期：2026-07-30
- 对应任务：G2-05
- Requirement：`REQ-DATA-001`、`REQ-DSL-001`、`REQ-DEF-001`、`REQ-CRON-001`、`REQ-SECRETS-001`
- 权威迁移：`control-plane/src/main/resources/db/migration/V2__create_definition_and_integrations.sql`
- SHA-256：`1ee8a70c4e8a0a6a3d2e00f77bbdd11aa34276886d24af40582e35145478fe01`

## 1. 实现范围

V2 只创建 ER 模型分配给 G2-05 的五张表：`workflow_definition`、`workflow_version`、`trigger_config`、`connector`、`secret_reference`。展开后共 71 列、10 个 `ON DELETE RESTRICT` 外键、8 个显式索引、22 条 CHECK、5 个强制 RLS policy 和 2 个不可变历史触发器。

迁移依赖 V1 的 `tenant`、`project`、事务级 tenant context 和数据库角色。所有主键仍由应用生成 RFC 9562 UUIDv7；时间使用 `timestamptz(6)`/UTC，数据库默认时间使用事务时钟。V2 不改写 V1，也不创建 G2-06 及后续任务所属表。

## 2. 表与生命周期契约

| 表 | 可变性 | 关键完整性 | 删除策略 |
|---|---|---|---|
| `workflow_definition` | 草稿可更新；调用方显式递增 `draft_revision` 与 `version` | project 内 normalized name 唯一；draft DSL 必须为 object；checksum 为 64 位小写 SHA-256 | 生命周期状态，无物理 DELETE |
| `workflow_version` | 只追加；发布后 UPDATE/DELETE 均拒绝 | definition 内 `version_no` 与 checksum 分别唯一；版本号/Schema 版本为正；发布时间不早于创建时间 | 不可变历史 |
| `trigger_config` | 与发布版本绑定后只追加 | version 内 trigger key 唯一；key 为 lower_snake_case；config 必须为 object | 不可变历史 |
| `connector` | 可更新并带乐观锁 version | tenant/project 作用域内有效名称唯一；type 为 lower_snake_case；config 为 object；endpoint 非空白 | 三字段软删除，无物理 DELETE |
| `secret_reference` | 只保存引用或密文，不保存可打印明文 | tenant/project 作用域内有效名称唯一；provider 为 lower_snake_case；材料严格 XOR | 三字段软删除，无物理 DELETE |

`workflow_definition` 的草稿可变性不代表任意覆盖：Repository/API 必须在 G3 使用 `version`/`draft_revision` 实现 If-Match/ETag 冲突检测。数据库只保证 revision/version 非负和结构约束，不替代 G3 的发布事务、DSL 规范化或领域状态机。

## 3. 发布历史不可变

运行时角色对 `workflow_version`、`trigger_config` 只有 `SELECT, INSERT`，没有 `UPDATE, DELETE`。这能阻断普通应用和受控平台运维账号改写历史，但表 owner 天生具有表权限，因此 V2 额外安装 `reject_immutable_row_change()` 的 `BEFORE UPDATE OR DELETE` 触发器。

触发器函数是 `SECURITY INVOKER`，固定 `search_path = pg_catalog, pg_temp`，并从 `PUBLIC` 撤销 EXECUTE。数据库集成测试同时验证：

- 应用与平台管理员因缺少表权限不能更新/删除；
- `workflow_migrator` 即使作为 owner，在设置合法 tenant context 后也被 SQLSTATE `55000` 触发器拒绝；
- 发布新版本/触发配置必须 INSERT 新行，不能原地修改。

## 4. Trigger 条件语义

`manual`、`http`、`cron` 使用稳定小写类型值。Cron 必须同时给出非空白 IANA timezone 和 `skip`、`fire_once`、`catch_up_limited` 之一；manual/http 必须让 `timezone`、`misfire_policy` 保持 `NULL`，避免无效字段被误认为生效配置。G2-06/V3 已落库 occurrence 去重与 Cron 快照绑定；Cron 表达式、DST 计算、积压上限和禁止重叠仍由 G4-15 实现，本迁移不提前声称具备调度能力。

## 5. Connector 与 Secret 作用域唯一性

`connector.project_id`、`secret_reference.project_id` 可为 `NULL`，表示租户级对象。普通 UNIQUE 会把多个 NULL 当作不同值，因此两表使用：

```sql
CREATE UNIQUE INDEX ...
ON ... (tenant_id, project_id, normalized_name)
NULLS NOT DISTINCT
WHERE deleted_at IS NULL;
```

这样同一租户只能有一个有效的租户级同名对象；软删除后旧行继续留痕，新行可以复用名称。project 级同名与 tenant 级同名是不同作用域。`(tenant_id, project_id)` 复合外键同时防止把其他租户的 project ID 写入当前租户记录。

软删除必须让 `deleted_at`、`deleted_by`、`deletion_reason` 同时为空或同时有值，后两项必须为非空白文本；调用方还需递增 `version` 并更新审计字段。运行时角色没有物理 DELETE 权限。

## 6. Secret 材料安全

`secret_reference` 强制两种且仅一种材料形态：

- `provider = 'local'`：`ciphertext` 必须存在且至少 1 byte，`external_key` 必须为 NULL；
- 外部 provider：`external_key` 必须为非空白文本，`ciphertext` 必须为 NULL；
- 两种模式的 `key_version` 都不得为空白。

数据库只保存密文或外部 Provider key，不负责 KMS/Vault 鉴权、信封加密、轮换或 SSRF 防护。G6-07/G6-08 必须实现 Provider 端口、出站策略和字段级授权。日志、Trace、异常、审计和测试输出禁止打印 `ciphertext` 或 `external_key`；集成测试只断言约束结果，并用固定非敏感字节验证 local 分支。

## 7. 租户隔离与权限

- 五表全部 `ENABLE` 且 `FORCE ROW LEVEL SECURITY`，policy 同时使用 `USING`/`WITH CHECK` 比较 `tenant_id = app_tenant_id()`。
- 平台管理员在没有 tenant context 时也看不到五表；设置 context 后只能访问指定租户。V1 的 tenant 列表旁路不会扩散到业务表。
- 十个外键全部 `ON DELETE RESTRICT`；其中五个实体引用使用 `(tenant_id, referenced_id)` 复合键阻断跨租户引用。
- 可变三表授予运行时 `SELECT, INSERT, UPDATE`；不可变两表仅授予 `SELECT, INSERT`；五表都不授予 DELETE，PUBLIC 无表权限。
- 表 owner 保持 `workflow_migrator`，`workflow_app` 不获得 DDL、BYPASSRLS 或对象所有权。

## 8. 升级、验证与失败处理

可复现验收命令：

```powershell
pnpm er:verify
pnpm er:test
pnpm database:g2-05:verify
pnpm database:g2-05:test
pnpm database:g2-04:integration
pnpm database:g2-05:integration
pnpm migrations:verify
pnpm migrations:test
```

G2-05 集成套件使用固定 digest 的 PostgreSQL 18.4/Flyway 11.7.2 一次性容器，先只迁移 V1，写入 A/B tenant/project fixture，再升级 V2 并第二次 migrate。系统目录必须只出现成功的 V1、V2 两条历史，V1 fixture 必须在各自 tenant context 下保持完整。

V2 在 PostgreSQL 事务内执行，失败时 Flyway 停止且不记录成功版本。迁移登记 SHA-256 后禁止修改、改名或删除；已部署环境发现缺陷时只能添加更高版本前滚迁移。测试资源使用 `workflow-g2-05-*` 随机前缀，结束后清理，不挂载现有 Compose 数据卷。

## 9. 已知边界

- 本任务创建持久化结构与数据库不变量，不代表 G3 的定义 API、DSL 编译/校验、发布事件、克隆/导入/差异已经实现。
- trigger 配置、occurrence 去重与 Cron 快照绑定已持久化，但实际手动/HTTP/Cron 触发及 DST/misfire 执行仍由 G4 完成。
- connector/secret_reference 已具备隔离、软删除和材料约束，但 Provider 实现、轮换、字段授权、审计和 SSRF 防护仍由 G6 完成。
- Flyway 11.7.2 在 PostgreSQL 18.4 实测通过，但 CLI 声明的已测试上限仍为 PostgreSQL 17；G2-11 负责兼容矩阵复审和完整 Schema/查询计划验收。
