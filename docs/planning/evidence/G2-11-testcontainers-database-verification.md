# G2-11 Testcontainers 全量数据库验收证据

- 验收日期：2026-07-31
- 状态：PASS
- 目标：空库/重复迁移、完整 Catalog、约束、RLS 与关键查询计划

## 1. 交付清单

- 机器契约：`config/database/g2-11-testcontainers.json`
- 集成测试：`control-plane/src/test/java/com/workflowplatform/controlplane/database/DatabaseMigrationIT.java`
- 查询数据：`control-plane/src/test/resources/database/g2-11-query-plan-fixture.sql`
- 运行入口：`scripts/database/test-g2-11-testcontainers.ps1`
- 静态门禁：`scripts/database/verify-testcontainers-database.mjs`
- 反向控制：`scripts/database/test-testcontainers-database-verifier.mjs`
- 架构说明：`docs/architecture/testcontainers-database-verification.md`

## 2. 已完成的真实验收

```powershell
pnpm database:g2-11:verify
pnpm database:g2-11:test
mvn -pl control-plane -am -Dit.test=DatabaseMigrationIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

结果：

- 静态正向门禁 PASS；PostgreSQL digest、Flyway 版本、Catalog、ER Q-01～Q-10、Java/POM/fixture/runner/package/doc 绑定一致。
- 静态反向门禁 PASS：12/12 变异被拒绝。
- Testcontainers PASS：4/4 集成测试通过；PostgreSQL 18.4 和 Testcontainers 1.21.4 真实启动。
- Flyway PASS：V1～V5 空库迁移 5 项、validate 成功、第二次 migrate 为 0 项、当前版本 5。
- Catalog PASS：22 表、327 列、22 PK、39 FK、41 UNIQUE 约束、3 独立条件唯一索引、100 CHECK、47 个 `idx_` 查询索引、22 表强制 RLS。
- 行为 PASS：CHECK/FK/UNIQUE/RLS 读写/append-only trigger 六类探针通过。
- 计划 PASS：代表性 fixture + `ANALYZE` 下，Q-01～Q-10 均命中指定索引且无 `Seq Scan`；未使用 `enable_seqscan` 等 planner hint。
- Java 质量 PASS：相关 Reactor 的 Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 与 29 项单元/集成测试无失败。

## 3. 目录计数口径修正

首次综合验收发现 ER 的“44 个唯一性保障”不能全部按 `pg_constraint.contype='u'` 统计。真实 Catalog 为 41 个 UNIQUE 约束，加 3 个不依附约束的条件唯一索引：

- `uq_connector_active_name`
- `uq_secret_reference_active_name`
- `uq_worker_lease_active_node`

测试和机器契约已拆分统计，同时保持唯一性保障合计 44。Flyway 自有表 `flyway_schema_history` 也从业务表、列和约束统计中显式排除。

## 4. 风险与范围

- Flyway 11.7.2 虽在本项目 PostgreSQL 18.4 套件中通过，但运行时仍警告最高已测试版本为 PostgreSQL 17。生产升级前必须升级 Flyway 或把 PostgreSQL 固定在官方支持矩阵内。
- G2-11 锁定访问路径，不声明生产 P95/P99、吞吐或容量数字。
- 并发锁竞争、fencing、长事务和 30 分钟容量测试仍属于 G10，不在本任务中虚报完成。

## 5. 收尾门禁

最终结果：

- 工作镜像 `pnpm verify`、迁移正反向、Compose、CI、Maven 联网与离线五模块 `clean verify` 全部 PASS；36 项 Java 测试 0 失败/0 错误/0 跳过。
- 固定 digest Gitleaks 扫描工作镜像 245 个、桌面仓库 248 个提交候选均无泄漏，合成硬编码密钥被阻断。
- 非破坏 Robocopy 同步成功，未使用 `/MIR`；桌面 `.git`、`.env` 边界、`node_modules`、`target`、`dist` 和三份规划基线均被保留。
- 桌面独立 `pnpm verify` PASS；正式 `pnpm database:g2-11:integration` 入口 PASS，4/4 Testcontainers 测试再次通过。
- 排除 `.git/.env/node_modules/target/dist` 后，工作镜像与桌面项目 245 个文件逐一 SHA-256 一致。

G2-11 满足 Definition of Done，可以在总控清单标记 `[x]`。下一步进入 G3-01；G10 并发/属性/容量测试仍保持 planned。
