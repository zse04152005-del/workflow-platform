# G2-05 验收记录：Definition、Version、Trigger、Connector 与 Secret 迁移

- 完成日期：2026-07-30
- 任务：G2-05 创建 `workflow_definition`、`workflow_version`、`trigger_config`、`connector`、`secret_reference`
- Requirement：`REQ-DATA-001`、`REQ-DSL-001`、`REQ-DEF-001`、`REQ-CRON-001`、`REQ-SECRETS-001`
- 结论：通过
- 下一步：G2-06 创建 Runtime 表

## 1. 实现前复审与修正

落库前重新核对 ER 模型、V1 角色/RLS、密钥边界和升级策略，修正了以下容易在后续放大的问题：

1. draft/published checksum 从“非空字符串”收紧为 64 位小写 SHA-256，DSL、trigger config、connector config 根必须为 JSON object；
2. trigger key、connector type、secret provider 强制 lower_snake_case；Cron 必须有非空白 timezone 和受控 misfire，manual/http 不得携带 Cron 专属字段；
3. `workflow_version`、`trigger_config` 不只依赖 GRANT 不可变，还用 owner 也无法绕过的 `BEFORE UPDATE OR DELETE` 触发器阻断历史改写；
4. connector/secret 的 tenant 级 `project_id=NULL` 使用 partial `NULLS NOT DISTINCT` 唯一索引，软删除后才允许同名重建；删除 actor/reason 必须为非空白文本；
5. Secret local ciphertext 与 external provider key 严格 XOR，空 bytea、空外部 key、空 key version 都被拒绝；测试输出不读取材料列；
6. 集成脚本原有固定测试密码被 Gitleaks 拦截，现改为每次运行用系统加密随机数生成五组短生命周期值，不打印、不写文件，也未降低扫描规则或添加 allowlist。

## 2. 交付内容

| 类别 | 交付物 | 结果 |
|---|---|---|
| Flyway | `V2__create_definition_and_integrations.sql` | 五表、71 列、10 FK、8 索引、22 CHECK、5 强制 RLS、2 不可变触发器 |
| Checksum | `config/migrations/checksums.json` | SHA-256 `1ee8a70c4e8a0a6a3d2e00f77bbdd11aa34276886d24af40582e35145478fe01` |
| 静态门禁 | `verify-definition-migration.mjs` | ER 与 V2 列/约束/FK/index/RLS/trigger/GRANT 精确一致 |
| 门禁自测 | `test-definition-migration-verifier.mjs` | 十类合成破坏被阻断 |
| 升级 fixture | `g2-05-v1-upgrade-fixture.sql` | V1 先写 A/B tenant/project，再升级 V2 |
| 数据库测试 | `g2-05-app-role-test.sql`、`g2-05-catalog-test.sql`、`g2-05-platform-admin-test.sql` | 应用、owner、平台管理员三条权限路径 |
| 编排 | `test-tenant-rbac-migration.ps1 -Suite G2-05` | 固定镜像、随机临时凭据、V1→V2→重复 migrate、自动清理 |
| 文档 | `definition-and-integration-migration.md` | 生命周期、租户隔离、不可变历史、Secret 边界、升级/前滚规则 |
| 追踪 | `requirements.json`、`03-需求追踪矩阵.md` | DATA/DSL/DEF/CRON/SECRETS 更新为据实 partial |

## 3. Schema 实测结果

| 检查项 | 结果 |
|---|---:|
| G2-05 表 | 5/5 |
| 展开列 | 71/71 |
| `ON DELETE RESTRICT` 外键 | 10/10 |
| 复合 tenant FK | 5/5 |
| 普通业务索引 | 6/6 |
| partial + `NULLS NOT DISTINCT` 唯一索引 | 2/2 |
| CHECK | 22/22 |
| ENABLE + FORCE RLS | 5/5 |
| USING + WITH CHECK policy | 5/5 |
| 不可变 UPDATE/DELETE trigger | 2/2 |
| 应用/平台管理员 DELETE 权限 | 0 |
| immutable 表应用/平台管理员 UPDATE 权限 | 0 |
| 新表 owner | 5/5 `workflow_migrator` |
| Flyway 成功历史 | V1、V2 两条；第二次 migrate 无新增 |

至此已有 10/22 张核心表真实落库，剩余 12 张由 G2-06～G2-08 创建。ER 仍保持 22 表/322 列/39 FK/47 索引的完整设计基线，不把未迁移表提前标为 implemented。

## 4. 静态正反向门禁

```powershell
pnpm er:verify
pnpm er:test
pnpm database:g2-05:verify
pnpm database:g2-05:test
pnpm migrations:verify
pnpm migrations:test
```

结果：全部 PASS。G2-05 verifier 自测依次移除 FORCE RLS、调换复合 tenant FK 列序、移除 `NULLS NOT DISTINCT`、漂移 partial predicate、删除 immutable trigger、误授 immutable UPDATE、弱化 Secret XOR、弱化 checksum、放宽 JSON 根类型、改变 DESC 顺序、私增未追踪表；这些反例均被拒绝。迁移 checksum 自测继续证明未登记迁移和已登记 SQL 篡改会失败。

## 5. 真实 PostgreSQL 升级与行为测试

```powershell
pnpm database:g2-04:integration
pnpm database:g2-05:integration
```

结果：最终回归均 PASS。测试镜像：

- PostgreSQL `18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296`；
- Flyway `11.7.2-alpine@sha256:8c25848de56085076096f8600c2babf6254cfe4dfc84d868bdef28c729067720`。

G2-05 先迁移到 V1，用应用角色在各自 context 写入 tenant/project A、B，再迁移 V2 并重复 migrate。升级后两租户的 V1 fixture 均完整，history 恰好 V1/V2 两条成功记录。应用测试覆盖草稿 revision/version 更新、definition/version/trigger 唯一性、checksum/JSON/发布时间、有效与非法 Cron、跨租户 project/version FK、tenant A/B 读写隔离、connector/secret tenant/project 作用域、partial uniqueness、软删除重建、Secret XOR 与空值、所有状态/格式约束和无物理 DELETE。

应用与平台管理员因最小 GRANT 不能更新/删除 version/trigger；`workflow_migrator` 作为 owner 也被 SQLSTATE `55000` 触发器拒绝。平台管理员无 context 时仍看不到五张业务表，设置 A/B context 后只见指定租户。测试未输出 `ciphertext` 或 `external_key`。所有 `workflow-g2-04-*`、`workflow-g2-05-*` 一次性资源在 finally 中清理。

## 6. 整仓质量门禁

Java 联网与离线构建：

```powershell
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd -B -ntp -o clean verify
```

结果：两次均 BUILD SUCCESS；5 个 Reactor 模块、14 项 Java 测试通过，Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 无违规。离线构建证明 Maven Wrapper 与依赖缓存可完整复现。

Node/部署门禁：

```powershell
pnpm verify
pnpm compose:verify
pnpm ci:verify
```

桌面权威项目全部 PASS：架构、领域、ER、G2-04/G2-05、治理、追踪、配置、lint、typecheck、2 个前端测试文件/3 项测试、Vite 生产构建、7 服务 Compose 和 4 Job/9 个固定 SHA Action。Gitleaks 最终验证 173 个 Git 提交候选无泄露，并成功阻断合成硬编码密钥。

最终非破坏性同步后，用 SHA-256 对工作交付镜像与桌面逐文件复核：170 个交付源文件全部存在，0 缺失、0 哈希差异、0 非预期额外文件；桌面额外保留 `00-原始项目开发大纲.md`、`01-项目大纲审计与优化基线.md`、`02-项目开发总控清单.md` 三份权威规划文档。桌面清单为 139 项、已完成 21、未完成 118；Maven 离线、G2-04/G2-05 PostgreSQL 套件在桌面再次 PASS，结束后临时容器/网络均为 0。

## 7. 兼容、失败停止与前滚

V2 只追加新对象，不修改 V1。PostgreSQL 事务内任一 DDL 失败会使 Flyway 停止且不记录 V2 成功；迁移一旦登记 SHA-256 就不得修改、改名或删除。已部署环境发现缺陷时必须新增 V3 或更高版本前滚，不能改写 V2，也不能通过删除共享/生产数据卷修复。

Flyway 11.7.2 实测可迁移 PostgreSQL 18.4，但 CLI 仍提示其官方已测试上限为 PostgreSQL 17。该兼容风险继续归 G2-11；本任务不通过忽略 warning 或虚报版本消除风险。

## 8. 遗留范围

1. G3 仍需实现 DSL 规范化/编译/校验、ETag、发布事务/事件、克隆/导入/差异 API；数据库表存在不等于这些能力完成。
2. G2-06/G4 仍需实现 runtime/schedule 表、手动/HTTP/Cron 调度、DST/misfire 执行、occurrence 去重与实例版本快照。
3. G6 仍需实现 Connector Provider、KMS/Vault、轮换、字段授权、审计脱敏和逐跳 SSRF 防护。
4. G2-11 仍需完整 Schema、前版升级、并发锁、十个关键查询计划和 Flyway/PostgreSQL 兼容矩阵验收。

## 9. 完成判定

实现、静态反向门禁、真实 V1→V2 PostgreSQL、owner/app/admin 不可变与 RLS 测试、迁移 checksum、Java 联网/离线构建、前端/Compose/CI、Secret 正反向控制、文档、追踪、最终桌面同步和 SHA-256 对账均已有可复现证据。G2-05 满足 Definition of Done，状态已更新为 `[x]`；下一步仅推进 G2-06，不提前实现其 Runtime 表。
