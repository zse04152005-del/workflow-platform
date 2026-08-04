# 分支、提交、版本与兼容性管理规范

> 基线版本：1.0.0  
> 生效日期：2026-07-30  
> 机器策略：`config/governance/policy.json`  
> 校验命令：`pnpm governance:verify`、`pnpm governance:test`

## 1. 主干与分支规则

- `main` 是唯一长期分支，必须保持可构建；发布从经过完整门禁的 `main` 提交打不可变 Tag。
- 工作分支必须关联总控任务或缺陷 ID，格式为 `<type>/<task-or-issue>-<short-description>`。
- `type` 允许：`feat`、`fix`、`docs`、`test`、`refactor`、`perf`、`build`、`ci`、`chore`。
- `task-or-issue` 使用 `g0-01`～`g12-99` 或 `issue-<positive-number>`；描述只用小写字母、数字和短横线。
- 分支应短生命周期、聚焦一个可解释原因；不得在同一分支混入无关格式化、重命名或依赖升级。

示例：

```text
feat/g4-09-task-leasing
fix/g5-03-stale-worker-result
docs/g0-06-governance-baseline
test/issue-17-tenant-isolation
```

## 2. Commit Message

提交头遵循 Conventional Commits：

```text
<type>(<scope>)!: <imperative summary>
```

- 允许的 commit type 是分支类型加 `revert`；scope 可省略，推荐使用 `control-plane`、`definition`、`runtime`、`scheduler`、`human-task`、`security`、`worker`、`contracts`、`web`、`deploy`、`docs`。
- header 最长 100 个字符，摘要最长 72 个字符，不允许换行；英文摘要使用小写祈使语气，简洁中文摘要也可。
- 破坏性契约变更使用 `!`，正文必须包含 `BREAKING CHANGE:`、ADR、迁移窗口和回退/前滚方案。
- `perf` 必须附可复现的前后测量；`fix` 必须附回归测试；一个提交只表达一个原因。

## 3. 应用 SemVer 与发布 Tag

当前开发基线是 `0.1.0`：根与私有前端包使用基础版本 `0.1.0`，Maven 开发版本使用 `0.1.0-SNAPSHOT`。发布候选移除 `-SNAPSHOT`，通过门禁后打 Tag。

| 变化 | 0.x 阶段 | 1.0.0 以后 |
|---|---|---|
| 破坏性 API/DSL/Event/行为变更 | MINOR + ADR + 迁移窗口 | MAJOR + ADR + 迁移窗口 |
| 向后兼容的新能力 | MINOR | MINOR |
| 向后兼容修复/安全补丁 | PATCH | PATCH |

- 稳定 Tag：`vMAJOR.MINOR.PATCH`，例如 `v0.1.0`。
- 预发布 Tag：`vMAJOR.MINOR.PATCH-alpha.N`、`-beta.N` 或 `-rc.N`，序号从 1 开始。
- 禁止移动或复用已发布 Tag，禁止使用 `latest` 作为唯一可追溯版本。
- 发布版本、镜像标签、SBOM、签名和发布说明必须指向同一 Git SHA。

## 4. OpenAPI 版本与兼容窗口

- MVP API 主路径为 `/api/v1`，OpenAPI 3.1 是公开 HTTP 契约的权威源。
- 同一 Major 内允许新增可选字段、可选参数、新端点和新枚举处理策略；消费者必须忽略未知响应字段。
- 删除/重命名字段、改变类型或含义、收紧已有必填约束、改变状态码语义属于破坏性变更。
- 破坏性变更必须使用新 Major 路径（例如 `/api/v2`）并新增 ADR；至少保留前一个 Minor 的兼容窗口，具体下线日期写入发布计划。
- 统一错误响应保持 RFC 9457 风格，`code` 的语义不可在同一 Major 内复用。

## 5. DSL Schema 版本

- 每个 DSL 根对象必须携带整数 `schemaVersion`，MVP 起始值为 `1`。
- 同版本只能做向后兼容扩展；发布时规范化 JSON、计算 checksum，并锁定节点执行器版本。
- 删除字段、改变默认值/表达式语义/节点行为或让历史流程产生不同结果属于破坏性变更，必须增加 `schemaVersion`。
- 新版本必须提供确定性 upcaster 或明确拒绝策略，并用历史 fixture 做兼容测试；已发布 DSL 永远不原地改写。

## 6. Event Schema 版本

- 事件 Envelope 必须包含 `schemaVersion`，MVP 起始值为 `1`；`eventId`、`eventType`、`occurredAt`、`tenantId`、`traceId` 和 `payload` 是基础字段。
- 同一 Major 内只增加可选字段，消费者必须忽略未知字段；不得删除/重命名字段或改变既有语义。
- 破坏性事件变更使用新事件 Major/类型，并在迁移期双写或提供适配消费者；变更必须有生产者/消费者契约测试和 ADR。
- Topic 不是数据库；事件版本变化不得改变 PostgreSQL 作为事实源的边界。

## 7. 数据库迁移版本

- Flyway 文件格式固定为 `V<positive_version>__<lower_snake_case>.sql`，版本可用下划线分段，例如 `V1__initial_schema.sql`、`V1_1__add_index.sql`。
- 迁移版本独立于应用 SemVer，按数据库实际顺序单调递增；同一版本不得重复。
- 已登记迁移只追加、不改写、不重命名、不删除；SHA-256 目录 `config/migrations/checksums.json` 会阻止漂移。
- 采用 expand-migrate-contract：先增加兼容结构并双读/双写或回填，再迁移消费者，最后在后续版本收缩旧结构。
- 每次迁移必须验证空库、重复执行门禁、前一发布版本升级、失败停止与前滚方案；生产发布不依赖不可逆的临时手工 SQL。

## 8. 变更控制流程

1. 关联 Requirement 和总控任务，分类为兼容、破坏性或仅内部实现。
2. 影响一致性、安全、公开契约、数据模型或拓扑时先更新 ADR。
3. 同步修改实现、OpenAPI/Schema、迁移、测试、示例、Runbook 和追踪矩阵。
4. 执行[统一 Definition of Done](definition-of-done.md)中的适用门禁，并保存正反向证据。
5. 只有全部验收通过后更新版本、清单和发布说明；未经测试不得声称兼容、性能或可恢复性。

当前规范不授权自动提交或推送。Git commit、远程分支、PR、Tag 和发布动作只有在用户明确授权并具备远程仓库后才能执行。
