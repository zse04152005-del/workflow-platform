# G2-08 Human Task / Audit / Quota 迁移验收证据

- 验收日期：2026-07-30
- 状态：PASS
- 迁移：`V5__create_human_audit_and_quota.sql`
- SHA-256：`803776f6bb525e4726f90d20b76f708fc6baae82aaa5f1450529b85b55e7cd50`
- 前置升级：V4→V5

## 1. 交付清单

- 迁移：`control-plane/src/main/resources/db/migration/V5__create_human_audit_and_quota.sql`
- 静态验证：`scripts/database/verify-human-audit-quota-migration.mjs`
- 反向控制：`scripts/database/test-human-audit-quota-migration-verifier.mjs`
- 升级 fixture：`tests/database/g2-08-v4-upgrade-fixture.sql`
- 应用角色：`tests/database/g2-08-app-role-test.sql`
- Catalog/owner：`tests/database/g2-08-catalog-test.sql`
- 平台管理员：`tests/database/g2-08-platform-admin-test.sql`
- 架构说明：`docs/architecture/human-audit-quota-migration.md`

## 2. 量化验收

| 项目 | 实测 |
|---|---:|
| 新表 | 3 |
| 新列 | 49 |
| `ON DELETE RESTRICT` FK | 4 |
| CHECK | 17 |
| 显式索引 | 9 |
| ENABLE + FORCE RLS | 3/3 |
| lifecycle guard | 3 |
| 静态反向变异 | 15/15 拒绝 |
| PostgreSQL 角色路径 | application / migrator owner / platform admin |

## 3. 命令与结果

```powershell
pnpm database:g2-08:verify
pnpm database:g2-08:test
pnpm database:g2-08:integration
pnpm database:g2-04:integration
pnpm database:g2-05:integration
pnpm database:g2-06:integration
pnpm database:g2-07:integration
pnpm migrations:verify
pnpm migrations:test
pnpm verify
pnpm compose:verify
pnpm ci:verify
mvn clean verify
mvn -o clean verify
```

结果：

- 静态模型验证 PASS：3 表、49 列、4 FK、9 索引、17 CHECK、3 guard、强制 RLS、最小权限。
- 反向控制 PASS：缺表、tenant-leading 索引、平台审计 policy、转交/终态、append-only trigger、错误授权、quota advisory lock/相交检测/limit 快照、FK、FORCE RLS 和禁用类型等 15 类破坏均被拒绝。
- PostgreSQL PASS：V4 fixture→V5、重复 migrate、两租户隔离、首次转交/决策、超时/提前超时、append-only 审计、平台可空行、配额单调/相邻/重叠窗口与三角色最小权限全部通过。
- 冻结回归 PASS：G2-04 空库 V1、G2-05 V1→V2、G2-06 V2→V3、G2-07 V3→V4 全部在 V5 文件存在时通过。
- 整仓 PASS：197 个提交候选无 Secret、合成密钥被阻断；前端 2 个测试文件/3 项测试、lint、typecheck 与 Vite build 通过；Compose/CI/迁移正反向门禁通过。
- Java PASS：Maven 3.9.16/Java 21 五模块联网与离线 `clean verify` 均成功，测试与静态分析无失败。

## 4. 冻结校验和

| 迁移 | SHA-256 |
|---|---|
| V1 | `e25262cbb5910192511f0817a1fd5f0929788bcb02f37ffc62bb182248c0288e` |
| V2 | `1ee8a70c4e8a0a6a3d2e00f77bbdd11aa34276886d24af40582e35145478fe01` |
| V3 | `6e1519197f6e8b17c0bc1b2e17701ea0878741e6af5da1561090d845207acb66` |
| V4 | `394e585b0e2b22ce505851bc198c42ed40738540861f948640bc4ff6122c2961` |
| V5 | `803776f6bb525e4726f90d20b76f708fc6baae82aaa5f1450529b85b55e7cd50` |

V1～V4 内容和登记值未变化。

## 5. 审核结论与边界

- Human Task 首次有效决策、单次转交证据、deadline 超时与终态不可改写已由数据库强制。
- audit_log 的 tenant 行、受控平台行、append-only 和最小权限已由 RLS + trigger + grants 三层控制。
- quota_usage 的 tenant/resource/window_start 唯一、窗口不相交、用量单调与 limit 快照不可改已落库。
- G7 的 inbox/API/鉴权/node 联动/事件/E2E，G6 的审计内容脱敏、审计查询、配额 policy/限流/背压仍未实现，不在本任务中虚报完成。
- Flyway 11.7.2/PostgreSQL 18.4 支持上限告警交由 G2-11 复审。
