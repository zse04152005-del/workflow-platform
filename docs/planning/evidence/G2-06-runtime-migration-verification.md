# G2-06 Runtime 与 Schedule Flyway 迁移验收证据

- 状态：PASS
- 完成日期：2026-07-30
- 总控任务：G2-06 创建 Runtime 表：workflow_instance/node_instance/task_execution/schedule_occurrence
- 权威迁移：`control-plane/src/main/resources/db/migration/V3__create_runtime_and_schedule.sql`
- V3 SHA-256：`6e1519197f6e8b17c0bc1b2e17701ea0878741e6af5da1561090d845207acb66`
- ER 模型版本：`1.2.0`

## 1. 实现结果

V3 是只追加迁移，V1/V2 未修改。迁移创建：

- 4 张 G2-06 表、69 个展开列；
- 10 个 `ON DELETE RESTRICT` 外键、13 个显式索引、26 个 CHECK；
- 4 个 `ENABLE + FORCE RLS` policy；
- 2 个前滚唯一约束，分别支撑 Definition-Version 三元绑定和 Cron timezone/misfire 快照绑定；
- 1 个 `task_execution` INSERT/UPDATE/DELETE guard，只允许一次 RUNNING→终态收口；
- 应用与平台管理员四表 SELECT/INSERT/UPDATE，0 DELETE；PUBLIC 0 表权限。

核心问题修正：

1. `workflow_instance` 不能把一个 definition 与不属于它的 workflow_version 拼接为非法快照。
2. `schedule_occurrence` 只能引用 Cron trigger，且 timezone/misfire 必须与 trigger 快照完全一致。
3. workflow/node/task/schedule 的状态与 started/completed、next_schedule、worker/lease、output/error、claimed/instance 字段形成数据库不变量。
4. Task 只能以 RUNNING/version=0 插入；终态收口 version 必须恰好 +1，身份/输入快照不可改变，终态后任何角色不能 UPDATE/DELETE。
5. Q-03/Q-04/Q-06 高竞争 partial index 改为 tenant-leading，符合强制 RLS 的每租户事务查询边界。
6. 删除约定明确 task_execution 的“不可变历史”允许一次受 trigger 保护的 open→terminal finalization；workflow/node/schedule 纳入受控 retention-purge。

## 2. 主要产物

- `config/database/er-model.json`
- `config/domain/conventions.json`
- `control-plane/src/main/resources/db/migration/V3__create_runtime_and_schedule.sql`
- `scripts/database/verify-runtime-migration.mjs`
- `scripts/database/test-runtime-migration-verifier.mjs`
- `scripts/database/test-tenant-rbac-migration.ps1`
- `tests/database/g2-06-v2-upgrade-fixture.sql`
- `tests/database/g2-06-app-role-test.sql`
- `tests/database/g2-06-catalog-test.sql`
- `tests/database/g2-06-platform-admin-test.sql`
- `docs/architecture/runtime-and-schedule-migration.md`
- `config/traceability/requirements.json`
- `docs/planning/03-需求追踪矩阵.md`

## 3. 静态与反向门禁

执行：

```powershell
pnpm er:verify
pnpm er:test
pnpm domain:verify
pnpm domain:test
pnpm database:g2-05:verify
pnpm database:g2-05:test
pnpm database:g2-06:verify
pnpm database:g2-06:test
```

结果：PASS。

- G2-06 静态模型：4 表、69 列、10 FK、13 索引、26 CHECK、2 predecessor binding、4 强制 RLS、1 Task guard。
- 反向变异阻断：缺 FORCE RLS、Definition-Version FK 缺列/乱序、Cron 快照 FK 缺失、节点状态/租约/调度约束弱化、Task trigger 移除、误授 DELETE、摘要/trace 约束弱化、tenant-leading/partial index 漂移、私增未追踪表。
- G2-05 基线继续按 V2 的 5 表/71 列/10 FK/8 索引/22 CHECK/2 immutable trigger 验证；`introducedIn: G2-06` 不会反向改写 V2。
- ER/领域约定/需求追踪及其反向门禁全部 PASS。

## 4. 真实 PostgreSQL/Flyway 验收

执行：

```powershell
pnpm database:g2-04:integration
pnpm database:g2-05:integration
pnpm database:g2-06:integration
```

结果：三套均 PASS。

G2-06 套件使用固定 digest 的 PostgreSQL 18.4 与 Flyway 11.7.2 临时容器：

1. 空库迁移到 V2。
2. 应用角色在 A/B tenant context 写入 tenant/project、definition/version、Cron trigger、connector、secret_reference；A 额外含第二组 definition/version 和 Manual trigger。
3. V2→V3 升级并再次 migrate，确认只存在成功 V1/V2/V3 历史且 V2 数据无损。
4. 应用角色验证合法 instance/node/task/schedule 生命周期和跨租户不可见/不可写。
5. 负向验证同租户 Definition-Version 错配、全零 trace、状态/时间冲突、READY 无到期时间、过期/残缺租约、Task 直接终态插入/身份篡改/终态重写、Manual 冒充 Cron、Cron 快照漂移、FIRED 无 instance。
6. 目录验证对象集合/列/FK/CHECK/index/RLS/policy/owner/ACL/function/trigger 与模型一致。
7. `workflow_app`、继承平台管理员角色的 operations user、`workflow_migrator` owner 三类身份均不能绕过 Task 终态保护；前两类还因无 DELETE privilege 不能物理删除运行历史。
8. 随机加密数据库凭据和随机 `workflow-g2-06-*` Docker 资源名在 finally 中清理，无测试容器/网络残留。

G2-04 目标 V1、G2-05 V1→V2 在 V3 文件存在及集成脚本扩展后重新回归，均保持 PASS。

## 5. 构建、前端、安全和交付门禁

```powershell
mvnw clean verify
mvnw -o clean verify
pnpm verify
pnpm migrations:verify
pnpm migrations:test
pnpm compose:verify
pnpm ci:verify
```

最终结果：全部 PASS。

- Maven 3.9.16 + Temurin 21.0.12：5/5 Reactor 模块联网与离线 `clean verify` 成功；测试、Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 门禁通过。
- `pnpm verify`：架构/领域/ER/G2-04/G2-05/G2-06/治理/追踪/配置、安全、前端 lint/typecheck/test/build 全通过；Vitest 2 个文件/3 项测试通过，Vite 生产构建成功。
- Secret：178 个工作镜像可提交候选经固定 digest Gitleaks 镜像扫描为 0 泄漏；运行时合成硬编码密钥被项目规则阻断。工作镜像无持久 `.git`，扫描只临时创建无 commit 的 Git 元数据并在 finally 中清理，未配置/冒用用户身份。
- 迁移目录：V1/V2/V3 唯一、有序、三份 SHA-256 与 `config/migrations/checksums.json` 一致；篡改和未登记迁移反例被阻断。
- Compose：7 个固定 digest 服务、5 个 healthcheck、2 个一次性初始化器、端口仅 loopback。
- CI：4 jobs、9 个 SHA-pinned Action、最小权限与 actionlint 通过。

## 6. 需求追踪与边界

以下 Requirement 据实从 planned 更新为 partial 或扩充已有 partial 证据：`REQ-DATA-001`、`REQ-CRON-001`、`REQ-STATE-001`、`REQ-NODE-START-001`、`REQ-NODE-HTTP-001`、`REQ-NODE-DELAY-001`、`REQ-LEASE-001`、`REQ-RETRY-001`、`REQ-CONTROL-001`、`REQ-PAYLOAD-001`、`REQ-HUMAN-001`。

未提前声称完成：实例 API/DAG/状态迁移事务、Cron 计算/DST/misfire 执行、Worker 心跳/fencing、Outbox/去重/死信/重放、对象存储、暂停/恢复/取消 API、human_task/决策/审计。它们仍由 G2-07/G2-08/G4/G5/G7 等后续任务实现。

## 7. 遗留风险

- Flyway 11.7.2 在 PostgreSQL 18.4 实测通过，但其 CLI 声明的已测试上限是 PostgreSQL 17；G2-11 继续复审兼容矩阵。
- G2-11 仍需使用代表性数据验证 Q-01～Q-10 `EXPLAIN`、`SKIP LOCKED` 行为、选择性和写放大；本任务没有伪造性能数字。
- G2-07 继续创建 Outbox/processed/idempotency/dead_letter/worker_lease，并把当前节点租约基础接入可靠消息与 fencing 协议。

## 8. 完成结论

G2-06 满足“实现、自动化测试、真实 PostgreSQL 升级/权限验收、文档、需求追踪、checksum、全量构建和安全门禁”完成定义，可在总控清单标记 `[x]`。下一任务为 G2-07。
