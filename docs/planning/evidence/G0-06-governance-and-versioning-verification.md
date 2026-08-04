# G0-06 Definition of Done、分支/提交与版本规则验收记录

- 状态：`[x]`
- 完成日期：2026-07-30
- 任务：定义统一 Definition of Done、分支/提交规范和版本规则
- 权威项目目录：`C:\Users\Administrator\Desktop\workflow-platform`

## 1. 审核与完善结论

已有 `CONTRIBUTING.md` 已覆盖基础分支、Conventional Commits 和 PR 清单，但仍存在四个可执行性缺口：

1. “代码、测试、文档、证据齐全”未拆成统一、可逐项判定的 DoD。
2. 分支规则只写“尽量包含任务编号”，不能阻止无任务 ID、大写或下划线命名。
3. 未定义应用 SemVer、预发布 Tag、Maven `-SNAPSHOT` 与 JavaScript 基础版本的关系。
4. OpenAPI、DSL、Event 和 Flyway 各自的版本/兼容窗口没有统一变更控制和机器校验。

本任务将这些原则固化为人类规范、JSON 机器策略、正向 fixture 和反向控制；没有创建 Git commit、Tag 或远程分支，因为仓库当前没有提交和 remote，且用户尚未授权以其身份提交或推送。

## 2. 交付内容

| 文件 | 用途 |
|---|---|
| `config/governance/policy.json` | 分支、Commit、SemVer、契约、迁移、DoD 和必需文档的机器权威策略 |
| `docs/governance/definition-of-done.md` | DOD-01～DOD-12、风险匹配门禁和完成证据模板 |
| `docs/governance/versioning-and-change-management.md` | 主干/分支、Conventional Commits、SemVer、API/DSL/Event/Flyway 兼容与变更流程 |
| `scripts/governance/verify-governance.mjs` | 策略、fixture、版本对齐、迁移不变量、文档和 package scripts 校验 |
| `scripts/governance/test-governance-verifier.mjs` | 正向策略与七类反向控制 |
| `CONTRIBUTING.md`、`README.md` | 接入统一规范、文档入口和本地命令 |
| `package.json` | 增加 `governance:verify`、`governance:test` 并接入 `pnpm verify`/CI |

## 3. 锁定规则

- DoD：12/12 类别；任何 `N/A` 必须说明理由，任务只能在实现、测试、文档、证据和状态同步后标记 `[x]`。
- 分支：`<type>/<g0-01..g12-99|issue-N>-<lower-kebab-description>`；`main` 为唯一长期分支。
- Commit：Conventional Commits，Header 最长 100 字符、摘要最长 72 字符；破坏性变更使用 `!`/`BREAKING CHANGE:`。
- 应用版本：当前基础 `0.1.0`，Maven 开发版本 `0.1.0-SNAPSHOT`；稳定 Tag `vX.Y.Z`，预发布 `-alpha.N/-beta.N/-rc.N`。
- OpenAPI：MVP `/api/v1`；同 Major 只做兼容扩展，破坏性变更新 Major + ADR + 迁移窗口。
- DSL/Event：整数 `schemaVersion=1` 起步；破坏性变更新 Schema/Major，并提供 upcaster/消费者迁移与契约测试。
- Flyway：`V<positive_version>__<lower_snake_case>.sql`，只追加、SHA-256 防漂移，采用 expand-migrate-contract。

## 4. 验收命令与结果

在桌面权威目录执行：

```powershell
pnpm run governance:verify
pnpm run governance:test
pnpm run verify
node scripts/database/verify-migrations.mjs
node scripts/database/test-migration-verifier.mjs
node scripts/ci/verify-workflow.mjs
pnpm run secrets:verify
```

结果：

- `governance:verify`：PASS；策略 v1.0.0、分支 4 正/6 反、Commit 4 正/5 反、版本对齐、DoD 12/12、必需文档 4/4。
- `governance:test`：PASS；重复 type、过宽分支/Commit/迁移正则、非法 SemVer、缺失 DoD、缺失文档共七类反向控制全部被阻断。
- `pnpm verify`：PASS；治理、追踪、配置、Secret、Web lint、TypeScript、3 项前端测试和生产构建全部通过。
- Migration：0 迁移基线通过；未登记迁移、checksum 篡改被阻断，登记后的合法迁移被接受。
- CI 契约：4 个最小权限 Job、9 个 SHA 固定 Action 和 actionlint 全部通过。
- 最终 Gitleaks：139 个 Git 提交候选文件无泄漏，合成硬编码密码反例被阻断。

关键输出：

```text
Governance verification PASS: policy=1.0.0, branches=4 positive/6 negative, commits=4 positive/5 negative, baseVersion=0.1.0, DoD=12/12, documents=4.
Governance verifier tests PASS: positive policy accepted; duplicate type, broad branch/commit/migration rules, invalid SemVer, missing DoD, and missing document blocked.
Migration verification PASS: 0 migration(s), unique ordered versions, immutable SHA-256 catalog.
Migration verifier self-test PASS: unregistered migration blocked, registered migration accepted, checksum tampering blocked.
CI workflow verification PASS: 4 jobs, 9 SHA-pinned Action uses, minimal permissions, actionlint PASS.
Secret verification PASS: 139 commit candidates clean; synthetic hard-coded secret blocked.
```

## 5. 验收结论与遗留风险

G0-06 验收通过，Gate G0 关闭。后续任务有统一、可机器执行的完成定义和版本/兼容性边界，现有 CI 通过 `pnpm verify` 自动执行治理门禁。

遗留外部条件：仓库尚无首个 commit 和 remote，因此未执行提交、PR、Tag 或 Hosted CI；这不影响本地规则验收，但在用户明确授权并配置远程仓库后，仍需执行首次远程 CI 与分支保护验收。

下一步：G2-01——绘制上下文图、容器图和模块依赖图。
