# 贡献与提交规范

本规范适用于后端、前端、部署、测试和文档变更。项目以正确性、可恢复性、租户隔离和可复现证据为优先目标。

任务完成必须满足[统一 Definition of Done](docs/governance/definition-of-done.md)；分支、提交、SemVer、API/DSL/Event 和数据库迁移规则以[版本与变更管理规范](docs/governance/versioning-and-change-management.md)为准。规则的机器源是 `config/governance/policy.json`，提交前运行 `pnpm governance:verify` 与 `pnpm governance:test`。

## 1. 开发流程

1. 从总控清单选择一个具有唯一编号的任务，例如 `G4-09`。
2. 开始实现时将任务标为 `[~]`；若任务能在一次工作周期内完成，可在验收通过后直接标为 `[x]`。
3. 在独立分支完成实现、测试和文档。
4. 运行与风险相匹配的自动化检查，保存可复现命令和结果。
5. 只有验收标准全部满足后，更新总控清单的状态、完成日期和证据。
6. 合并前确认没有真实密钥、敏感 Payload、临时调试代码或未经证实的性能数字。

Java 变更至少运行 `./mvnw -B clean verify`（Windows 使用 `.\mvnw.cmd`）；前端或配置变更至少运行 `pnpm verify`。Java 格式问题先用 `./mvnw -B spotless:apply` 修复，再重新执行完整门禁。`pnpm verify` 包含配置契约和固定版本 Gitleaks 的密钥正反向检查；不能以 Docker 未启动为由跳过后提交。

## 2. 分支命名

主分支为 `main`。工作分支必须关联总控任务或缺陷 ID，格式为 `<type>/<task-or-issue>-<short-description>`，全程使用小写字母、数字和短横线：

```text
feat/g4-09-task-leasing
fix/g5-03-stale-worker-result
docs/g1-02-consistency-adr
chore/g1-10-ci-baseline
test/issue-17-tenant-isolation
```

允许的前缀：`feat`、`fix`、`docs`、`test`、`refactor`、`perf`、`build`、`ci`、`chore`。任务编号范围为 `g0-01`～`g12-99`，缺陷使用 `issue-<正整数>`；`feature/...`、大写任务号、下划线和无 ID 分支均不接受。

## 3. Commit Message

提交信息使用 Conventional Commits：

```text
<type>(<scope>): <imperative summary>
```

常用类型：

| 类型 | 用途 |
|---|---|
| `feat` | 新增用户可见或平台能力 |
| `fix` | 修复缺陷或不变量破坏 |
| `docs` | 仅修改文档 |
| `test` | 新增或修正测试 |
| `refactor` | 不改变行为的结构调整 |
| `perf` | 有测量证据的性能优化 |
| `build` | 构建系统或依赖变更 |
| `ci` | CI/CD 配置 |
| `chore` | 其他维护性变更 |
| `revert` | 撤销已有提交 |

推荐 scope：`control-plane`、`definition`、`runtime`、`scheduler`、`human-task`、`security`、`worker`、`contracts`、`web`、`deploy`、`docs`。

示例：

```text
feat(runtime): add optimistic node state transition
fix(worker): reject result from stale lease generation
test(scheduler): cover duplicate ready event dispatch
docs(adr): define outbox failure windows
```

规则：

- Header 最长 100 个字符，摘要最长 72 个字符且不得换行；英文摘要使用小写祈使语气，简洁中文摘要也可。
- 摘要使用祈使语气，说明本次提交带来的变化，不写“update files”一类无信息内容。
- 一个提交聚焦一个可解释的原因；格式化或批量重命名不要与行为变更混在一起。
- 不使用未经验证的 `perf` 声明。
- 破坏性契约变更使用 `!` 或 `BREAKING CHANGE:`，并附迁移方案和 ADR。

## 4. 版本与兼容性

- 应用版本遵循 SemVer；当前基础版本是 `0.1.0`，Maven 开发版本是 `0.1.0-SNAPSHOT`。
- 稳定 Tag 使用 `vMAJOR.MINOR.PATCH`，预发布使用 `-alpha.N`、`-beta.N` 或 `-rc.N`；Tag 不得移动或复用。
- MVP HTTP API 使用 `/api/v1`；DSL 与事件从整数 `schemaVersion: 1` 开始。破坏性变更必须升级 Major/Schema、提供 ADR、兼容窗口和迁移方案。
- Flyway 迁移使用 `V<positive_version>__<lower_snake_case>.sql`，只追加并纳入 SHA-256 目录；采用 expand-migrate-contract。
- 完整判断规则和示例见[版本与变更管理规范](docs/governance/versioning-and-change-management.md)。

## 5. 代码与架构边界

- `control-plane` 和 `worker` 不得互相形成编译依赖。
- 控制面模块不得直接调用其他模块的 Repository 或更新其拥有的表。
- `shared-contracts` 不得包含 Repository、Spring Service 或共享业务实体。
- 权威状态只能保存在 PostgreSQL；不能用 Redis 或内存状态替代。
- 影响一致性、安全、公开 API、数据模型或部署拓扑的变更必须新增或更新 ADR。

## 6. 测试要求

- 修复缺陷必须先增加能复现问题的测试，或说明无法自动化的原因。
- 状态机、事务、Kafka、租约、RLS 和对象存储等关键行为使用真实基础设施的 Testcontainers 测试，不能只用 Mock 证明。
- 所有外部调用都要覆盖成功、超时、重试、重复和不可重试错误。
- 性能结果必须附测试环境、脚本、数据规模、持续时间和 P50/P95/P99。

## 7. Pull Request 检查清单

- [ ] 变更关联总控任务或缺陷 ID。
- [ ] DOD-01～DOD-12 的适用项全部满足；所有 `N/A` 均记录理由。
- [ ] 实现没有突破 ADR 中的边界；若突破，已先更新 ADR。
- [ ] 自动化测试已运行并记录命令与结果。
- [ ] Java 变更通过 Spotless、Checkstyle、PMD/CPD、SpotBugs 和 JaCoCo；前端变更通过 `pnpm verify`。
- [ ] 新增质量规则抑制或覆盖率排除时，已记录问题 ID、责任人、原因和到期日期。
- [ ] `pnpm config:verify` 与 `pnpm secrets:verify` 通过；新增配置键已登记来源、默认值和敏感级别。
- [ ] `pnpm migrations:verify`、`pnpm migrations:test`、`pnpm compose:verify`、`pnpm ci:verify` 通过。
- [ ] 数据库迁移向前兼容，且测试了空库和升级路径。
- [ ] API、DSL 或事件变更已更新 Schema 和兼容性测试。
- [ ] 日志、错误、Trace 和审计不包含敏感数据。
- [ ] 文档、示例与真实实现一致。
- [ ] 总控清单只在验收完成后标记为 `[x]`。

## 8. 禁止提交的内容

- 真实凭据、私钥、证书、生产连接串和生产数据。
- 含个人信息或业务敏感信息的日志、Trace、测试夹具和截图。
- IDE 缓存、构建产物、依赖目录和本地运行数据。
- 未注明来源或许可证不兼容的复制代码与资产。
- 没有测试口径的吞吐量、延迟或高可用宣传。
