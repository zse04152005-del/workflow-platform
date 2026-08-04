# G2-04 验收记录：Tenant 与 RBAC 初始 Flyway 迁移

- 完成日期：2026-07-30
- 任务：G2-04 创建 Flyway 初始迁移：tenant/project/membership/role/role_binding
- Requirement：`REQ-IAM-001`、`REQ-DATA-001`
- 结论：通过
- 下一步：G2-05 创建 Definition 表

## 1. 审核发现与设计修正

落 SQL 前复核了 ER 机器模型、领域约定、Compose 初始化、Spring 配置和迁移 checksum 门禁，发现若继续让 `workflow_app` 同时拥有数据库/Schema/表并执行 Flyway，会削弱 RLS 和最小权限的防御价值。本任务因此同时修正了角色边界：

1. `workflow_migrator` 成为数据库、Schema 和迁移对象 owner，仅用于 Flyway；
2. `workflow_app` 仅有 Schema USAGE、上下文函数 EXECUTE 和五表 SELECT/INSERT/UPDATE，无 CREATE/DELETE/BYPASSRLS；
3. `workflow_platform_admin` 是 NOLOGIN、无 BYPASSRLS 的标记角色，普通应用不是成员；
4. 五张表全部 `FORCE ROW LEVEL SECURITY`，避免 owner 误跳过 policy；
5. 平台管理员旁路由 `session_user` 的受控角色成员关系决定，不读取可伪造的布尔 GUC；
6. tenant context 通过 `set_config(..., true)` 限定到事务，未设置为空时关闭读取并拒绝写入。

## 2. 交付内容

| 类别 | 交付物 | 结果 |
|---|---|---|
| Flyway | `control-plane/src/main/resources/db/migration/V1__create_tenant_and_rbac.sql` | 五张表、61 列、约束、索引、强制 RLS、函数与最小 GRANT |
| Checksum | `config/migrations/checksums.json` | SHA-256 `e25262cbb5910192511f0817a1fd5f0929788bcb02f37ffc62bb182248c0288e` |
| Spring | `control-plane/pom.xml` 与三个 Profile | JDBC、Flyway core/PostgreSQL、JDBC driver；运行时与迁移凭据分离 |
| Provisioning | `deploy/compose/postgres/init/01-create-application-databases.sh` | migration/app/platform-admin/Keycloak 角色分离与最小 Schema 权限 |
| 静态门禁 | `verify-tenant-rbac-migration.mjs` | ER 与 SQL 表/列/FK/index/RLS/权限一致性 |
| 门禁自测 | `test-tenant-rbac-migration-verifier.mjs` | 六类合成破坏被阻断 |
| 真实数据库 | `test-tenant-rbac-migration.ps1` + `tests/database/g2-04-*.sql` | 固定 digest 一次性 PostgreSQL/Flyway 正反向验收 |
| 文档 | `docs/architecture/tenant-context-and-rbac-migration.md` | 角色、事务 context、RLS、旁路、信任边界与运维命令 |
| 追踪 | `config/traceability/requirements.json`、`docs/planning/03-需求追踪矩阵.md` | IAM 从 planned 更新为 partial，保留 G6 未完成范围 |

## 3. Schema 验收结果

| 检查项 | 实测结果 |
|---|---:|
| G2-04 表 | 5/5 |
| 展开列 | 61/61 |
| `ON DELETE RESTRICT` 外键 | 7/7 |
| 复合租户 FK | 3/3 |
| 显式业务索引 | 7/7 |
| ENABLE + FORCE RLS | 5/5 |
| USING + WITH CHECK policy | 5/5 |
| `NULLS NOT DISTINCT` 自然唯一约束 | 2/2 |
| 应用表 owner | 0/5（全部为 migrator） |
| 应用 DELETE/Schema CREATE/BYPASSRLS | 0 项权限 |
| Flyway 成功历史 | 1 条；第二次 migrate 无新执行 |

所有测试数据 ID 都是 canonical UUIDv7。数据库拒绝无效 UUID、非法状态、负 version、非法 role code、scope/project 不一致、NULL 绕过唯一性和跨租户复合引用。`membership` 通过 REVOKED 状态与撤销字段更新留痕，物理 DELETE 被权限拒绝。

## 4. 正反向测试

静态模型门禁：

```powershell
pnpm database:g2-04:verify
pnpm database:g2-04:test
pnpm migrations:verify
pnpm migrations:test
```

结果：PASS。门禁自测分别移除 FORCE RLS、调换复合 FK 目标列、取消 `NULLS NOT DISTINCT`、私增未追踪表、误授 DELETE、把 `timestamptz(6)` 改成 timestamp；六类反例全部被拒绝。checksum 自测继续证明未登记迁移和已登记 SQL 篡改会失败。

真实 PostgreSQL：

```powershell
pnpm database:g2-04:integration
```

结果：PASS。测试使用：

- PostgreSQL `18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296`；
- Flyway `11.7.2-alpine@sha256:8c25848de56085076096f8600c2babf6254cfe4dfc84d868bdef28c729067720`；
- 独立 `workflow-g2-04-*` 容器/网络，不挂载现有 Compose 数据卷，结束后清理。

真实数据库覆盖：空库首次迁移、第二次迁移、Flyway history、系统目录、列类型/精度、约束和索引、无 tenant context 关闭、tenant A/B 读写隔离、跨租户 project/membership/role FK 拒绝、普通应用伪造管理员 GUC/SET ROLE 拒绝、受控平台管理员 tenant 列表旁路、其他四表仍需 tenant context、应用 DDL/DELETE 拒绝及撤销历史。

## 5. 整仓验收

Java 联网与离线构建：

```powershell
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd -B -ntp -o clean verify
```

结果：两次均 BUILD SUCCESS；5 个 Reactor 模块成功，14 项 Java 测试通过，Checkstyle/Spotless/PMD/CPD/SpotBugs/JaCoCo 门禁无违规。离线复验确认 Flyway、PostgreSQL JDBC 和 JDBC Starter 依赖已完整锁定/缓存。

桌面权威仓库：

```powershell
pnpm verify
pnpm compose:verify
pnpm ci:verify
```

结果：PASS。架构、领域约定、ER、G2-04、治理、追踪、配置、Secret、lint、类型、3 项前端测试和生产构建全部通过；164 个 Git 可提交候选文件 Secret 扫描干净，合成硬编码密钥被阻断；Compose 7 服务契约与 CI 4 Job/9 个 SHA 固定 Action/actionlint 通过。

最终使用 SHA-256 对工作交付镜像与桌面权威目录逐文件复核：161 个交付源文件全部存在且 0 哈希差异；桌面仅额外保留 `00-原始项目开发大纲.md`、`01-项目大纲审计与优化基线.md`、`02-项目开发总控清单.md` 三份权威规划文档。总控清单工作副本与桌面文件哈希一致，统计为 139 项、已完成 20、未完成 119、进行中/阻塞均为 0；测试结束后无 `workflow-g2-04-*` 一次性容器残留。

## 6. 兼容、失败停止与前滚

这是首个业务 Schema 迁移，不存在前一业务表版本需要升级；空库和第二次 migrate 已验证。迁移只创建新对象并在 PostgreSQL 事务内执行，失败时 Flyway 停止且不会把失败版本记为成功。迁移 checksum 登记后不允许改写或回滚 SQL；已应用环境发现缺陷时新增更高版本前滚迁移，不能修改 V1。

本地旧 Compose 数据卷的 owner/角色布局创建于 G2-04 之前时，只有确认无保留数据才能重建卷；共享/生产环境必须用受审权限变更和前滚迁移，禁止通过删除数据卷“修复”。

## 7. 遗留风险与后续责任

1. Spring Boot 3.5.16 BOM 锁定 Flyway 11.7.2；实测可迁移 PostgreSQL 18.4，但 CLI 提示其官方已测试上限为 PostgreSQL 17。G2-11 必须重新评审兼容矩阵并用同套测试验证升级。
2. PostgreSQL 自定义 GUC 不是身份凭据；RLS 防御遗漏过滤和错误引用，但持有数据库账号者仍能选择 tenant context。G6-02～G6-05 必须只允许认证/授权后的服务端代码建立上下文，并覆盖异步任务传播。
3. `role_binding.scope_id` 是 TENANT/PROJECT 多态引用，ER 当前按 CHECK + 应用校验设计；G6-03 实现权限矩阵时评估是否拆表以获得显式 FK。
4. G2-04 只验收五张表；其余 17 张表、全 Schema 前版升级、锁竞争和十个查询计划仍由 G2-05～G2-11 完成。

## 8. 完成判定

实现、真实数据库测试、静态反向门禁、文档、追踪和整仓验收均具备可复现证据，且未把 G6/G2-11 的后续能力提前标记为完成。G2-04 满足 Definition of Done，可以更新为 `[x]`。

下一步：G2-05——创建 `workflow_definition`、`workflow_version`、`trigger_config`、`connector`、`secret_reference`，复用本任务已建立的角色、事务 tenant context、强制 RLS、复合租户 FK 和真实 PostgreSQL测试框架。
