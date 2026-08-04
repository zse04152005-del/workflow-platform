# G2-02 ID、时间、枚举、错误码、审计字段与删除策略验收记录

- 状态：`[x]`
- 完成日期：2026-07-30
- Requirement：`REQ-DATA-001`（状态由 `planned` 更新为 `partial`；G2-03 ER/约束仍为 planned）
- 权威项目目录：`C:\Users\Administrator\Desktop\workflow-platform`

## 1. 实现内容与关键决策

在建表和 OpenAPI/Event Schema 之前建立统一机器约定，避免后续 20+ 张表与各模块自行定义基础语义：

| 领域 | 锁定规则 |
|---|---|
| 内部 ID | RFC 9562 UUIDv7，应用写库前生成，PostgreSQL `uuid`，JSON 小写 canonical；不能用 ID 代替业务时间排序 |
| 外部 ID/业务键 | Provider 值保持 `varchar`；business key 作用域 tenant + workflow；不无依据规范化 |
| 幂等键 | 不透明、最长 128、tenant + operation + resource 作用域，存 SHA-256 摘要及脱敏/加密元数据 |
| 时间 | Java `Instant`、PostgreSQL `timestamptz(6)`、RFC3339 UTC `Z`、最多微秒精度 |
| 时区/区间 | Cron 使用 IANA TZDB；区间 `[start,end)`；领域时钟注入 `Clock`，DSL 禁止隐式 now |
| 状态枚举 | `UPPER_SNAKE_CASE`；未知必需值拒绝；未知 Event Schema 隔离告警；禁止 ordinal/fallback |
| discriminator | `lower_snake_case`，同 Major 不重命名/删除 |
| 错误码 | `<DOMAIN>_<CATEGORY>_<DETAIL>`；9 个 Domain、8 个 Category；同 Major 稳定且永不复用 |
| 可变记录审计 | `created_at/by`、`updated_at/by`、`version`；租户记录含 `tenant_id`；特权操作含 `reason` |
| 删除 | 默认显式生命周期而非全表通用软删除；实体必须选择 6 种策略之一 |

删除策略包括：lifecycle-status、soft-delete、revocation、immutable-history、retention-purge、regulated-erasure。普通 API 禁止硬删除；不可变发布版本、attempt 和 audit_log 不使用软删除掩盖历史。

## 2. 关键文件

| 文件 | 用途 |
|---|---|
| `config/domain/conventions.json` | ID、时间、枚举、错误码、审计、删除与源码守卫的机器权威策略 |
| `docs/architecture/domain-data-conventions.md` | 完整语义、字段表、删除矩阵和后续任务接口 |
| `scripts/domain/verify-domain-conventions.mjs` | fixture、策略、源码、SQL、文档和 package scripts 校验 |
| `scripts/domain/test-domain-conventions.mjs` | 正向策略与八类反向控制 |
| `config/traceability/requirements.json` | REQ-DATA-001 更新为 partial，并引用 G2-02 真实证据 |
| `package.json` | 增加 `domain:verify/test` 并接入 `pnpm verify`/CI |

## 3. 正反向验证

正向门禁验证 UUIDv7、RFC3339 UTC、错误域/类别、审计字段、删除策略、必需文档和 package scripts；同时扫描 `control-plane/src`、`worker/src`、`shared-contracts/src` 与 Flyway 目录：

- Java 禁止 `java.time.LocalDateTime`、`java.sql.Timestamp`。
- SQL 禁止 `timestamp without time zone`、`serial`、`bigserial`。

以下八类反例全部被阻断：

1. UUID 正则过宽并接受 UUIDv4/大写/无短横线。
2. 时间正则过宽并接受本地时间、offset 或超过微秒精度。
3. 未知枚举错误回退到第一个值。
4. 错误 Domain 重复。
5. 缺少公共审计字段。
6. 一个实体被分配到两个删除策略。
7. 移除 Java 禁用类型守卫。
8. 规范文档路径不存在。

## 4. 验收命令与结果

在桌面权威目录执行：

```powershell
pnpm run domain:verify
pnpm run domain:test
pnpm run verify
node scripts/database/verify-migrations.mjs
node scripts/database/test-migration-verifier.mjs
pnpm run secrets:verify
```

结果：

- Domain conventions：PASS；2 个 UUIDv7 正向 fixture、2 个 UTC 时间正向 fixture、9 个错误域、8 个错误类别、5 个可变记录审计字段、6 个删除策略、2 个 Java/3 个 SQL 禁用项。
- Domain 反向测试：PASS；上述八类错误全部被阻断。
- `pnpm verify`：PASS；架构、领域约定、治理、追踪、配置、Secret、Web lint/类型、3 项前端测试和生产构建全部通过。
- Migration：0 迁移基线通过；未登记、登记、checksum 篡改三条自测路径通过。
- 最终 Gitleaks：150 个 Git 提交候选文件无泄漏，合成硬编码密码反例被阻断。

关键输出：

```text
Domain conventions verification PASS: UUIDv7=2 fixtures, time=2 fixtures, errorNamespaces=9, errorCategories=8, auditFields=5, deletionStrategies=6, forbiddenJavaTypes=2, forbiddenSqlTokens=3.
Domain conventions tests PASS: positive policy accepted; broad UUID/time rules, enum fallback, duplicate namespace, missing audit field, conflicting deletion strategy, removed source guard, and missing document blocked.
Migration verification PASS: 0 migration(s), unique ordered versions, immutable SHA-256 catalog.
Migration verifier self-test PASS: unregistered migration blocked, registered migration accepted, checksum tampering blocked.
Secret verification PASS: 150 commit candidates clean; synthetic hard-coded secret blocked.
```

## 5. 验收结论与遗留风险

G2-02 验收通过。基础 ID、时间、时区、枚举、错误码、审计字段和删除/保留规则已有机器源、可读规范和正反向门禁，后续数据与契约任务不能自行偏离。

本任务没有创建真实业务表，也没有声明 RFC 9457/OpenAPI 已实现：G2-03 负责把规则映射到实体、外键、索引、RLS 和分区，G2-09/G2-10 负责错误响应和公开 Schema。因此 `REQ-DATA-001` 保持 `partial`。

下一步：G2-03——设计并评审 ER 图及索引、外键、唯一约束和分区策略。
