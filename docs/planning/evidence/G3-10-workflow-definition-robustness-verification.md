# G3-10 工作流定义鲁棒性与兼容性验收证据

## 1. 任务

- 任务：G3-10 编写解析、图算法、兼容性、恶意表达式和并发更新测试
- Requirement：`REQ-DSL-001`、`REQ-DEF-001`
- 完成日期：2026-07-31
- 状态：`[x]`

## 2. 开发前审计与优化

审计 G3-01～G3-09 的实现和证据后确认四个缺口：

1. canonical/parser 和图算法已有确定性示例，但缺少固定种子、跨规模、可按索引复现的生成式属性测试；
2. 公共兼容门禁冻结 JSON Schema，却没有用生产编译链冻结 DSL v1 历史文档的 canonical checksum；
3. CEL/JSONPath 已有单点反例，但缺少跨函数、方法、环境、非确定性、递归下降、filter/slice/union/script 和预算攻击族的组合语料；
4. PostgreSQL 只有双写者同 ETag 场景，尚未证明高竞争下失败写者的幂等 claim 随事务回滚。

实现机器门禁时又发现两个追踪债：`REQ-DEF-001` 的测试/文档虽已有 G3-10 证据，`controlTasks` 未映射 G3-10；G3-10 收口后 `REQ-DSL-001` 已无 planned artifact，状态和历史 G3-03 的阶段性断言仍停留在 `partial`。现已同步升级为 `implemented`，同时保留 G3-03 当时的责任边界记录。

## 3. 实现结果

### 3.1 可重复属性与恶意输入

- 使用 `java.util.Random(30310)`，不新增网络依赖或 property-testing 框架；
- 64 个 JSON 键序/等价数字排列证明 canonical/checksum 稳定且 canonical 再解析幂等；
- 48 个 2～500 节点线性 DAG 证明确定性拓扑序与最大深度 stack-safe；
- 16 个生成环连续两次产生相同 `CYCLE_NOT_ALLOWED` 违规集合；
- 9 类组合解析攻击均拒绝且异常不回显 payload；
- 13 个 CEL、10 个 JSONPath 和 3 个预算炸弹覆盖 7 个攻击族，均在静态编译阶段失败。

### 3.2 DSL v1 冻结兼容语料

新增三个 append-only fixture，并由生产 parser/graph/configuration/expression 编译链验证：

- minimal：`cb3b9f4cf6e2d7ba19da1a94421a4e1176c161bd1175bfd00d7ecbcf1655607a`；
- root extension：`577ef1eef8e139a20316fbd574beeaa3f404fc04d6e6b735d7c556165fc7bd34`；
- condition：`bef2fc4af7a2e714f4a0e2e7dec30d03bb7d76255a4cde05695810b242c9cf90`。

未知可选根字段保留在 canonical 文档并由 runtime 忽略；无 upcaster 的未来 `schemaVersion` 被拒绝。公共兼容基线仍为 schemaVersion 1 / contractVersion 1.0.0。

### 3.3 八写者 PostgreSQL 竞争

`JdbcWorkflowDraftStoreIT` 新增第 11 项场景：8 个独立 Idempotency-Key writer 使用同一强 ETag，在 `CountDownLatch` start barrier 后并发更新。真实 PostgreSQL 18.4 结果为 1 项成功、7 项 `PRECONDITION_FAILED`，revision 只增加 1；只留下 1 条 `COMPLETED`，失败事务的 `PROCESSING` claim 为 0。

### 3.4 机器契约与追踪

- `config/definition/robustness-suite.json` 锁定种子、规模、攻击族、兼容策略与并发不变量；
- 正向校验器检查 fixture/source/doc/package/Requirement 绑定；
- 21 类反向变异覆盖种子、解析/图规模、兼容 checksum、CEL/JSONPath/预算、writer 成败/回滚、兼容基线、根脚本与两项 Requirement；
- `REQ-DSL-001` 已升级为 `implemented`；`REQ-DEF-001` 因 G8 UI 和 Deactivated 后续能力继续保持 `partial`。

## 4. 关键文件

- `config/definition/robustness-suite.json`
- `tests/contracts/dsl/robustness/compatibility/v1-minimal.json`
- `tests/contracts/dsl/robustness/compatibility/v1-root-extension.json`
- `tests/contracts/dsl/robustness/compatibility/v1-condition.json`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowDefinitionRobustnessTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/internal/JdbcWorkflowDraftStoreIT.java`
- `scripts/definition/verify-workflow-robustness-suite.mjs`
- `scripts/definition/test-workflow-robustness-suite-verifier.mjs`
- `scripts/dsl/verify-dsl-graph-validation.mjs`
- `docs/architecture/workflow-definition-robustness-suite.md`

## 5. 自动化验收

### 5.1 G3-10、追踪与公共契约

```text
G3-10 robustness verification PASS: seed=30310, parser=64, DAG=48/500 nodes, cycles=16, DSL v1 fixtures=3, CEL/JSONPath/budget attacks=13/10/3 and PostgreSQL writers=8.
G3-10 verifier negative tests PASS: 21/21 mutations rejected.
DSL G3-03 verifier tests PASS: 37 negative mutation classes rejected.
Traceability verification PASS: requirements=36, controlTasks=123/123, mvpGates=10/10.
Public contract verification PASS: operations=19, OpenAPI schemas=37, compatibilitySchemas=12.
Public contract verifier tests PASS: positive contract plus 23 reverse mutations.
```

### 5.2 Maven 五模块与 PostgreSQL 18.4

Java 21.0.12 / Maven 3.9.16 离线 `clean verify`：

- shared-contracts：16 项单元测试；
- control-plane：139 项单元测试 + 15 项 Testcontainers 集成测试；
- worker：1 项单元测试；
- test-support：2 项单元测试；
- 合计 173 项，0 失败、0 错误、0 跳过。

G3-10 固定种子专项 8/8；`JdbcWorkflowDraftStoreIT` 11/11。Spotless、Checkstyle、PMD/CPD、SpotBugs 与 JaCoCo 全绿。control-plane 指令/分支/行覆盖率分别为 88.60% / 70.42% / 87.58%。

### 5.3 根门禁与桌面交付

开发源完整 `pnpm verify` 已通过架构、领域、Problem Details、公共契约、DSL G3-01～G3-05、Definition G3-06～G3-10、数据库 G2-04～G2-11、治理、追踪和配置门禁；随后按预期在 Secret 扫描停止，因为开发源交付目录没有 `.git`，无法枚举 commit candidates。

非删除式同步未使用 `/MIR`，排除 `.git/.env/node_modules/target/dist`，0 失败并保留桌面独有目录/文件。在 `C:\Users\Administrator\Desktop\workflow-platform` 执行完整 `pnpm verify` 全绿：

- Gitleaks 固定 digest 镜像扫描 457 个 Git commit candidates 无泄漏，合成硬编码 Secret 被正确阻断；
- ESLint 0 warning、TypeScript 严格类型检查通过；
- 2 个 Vitest 文件、3 项测试通过；Vite production build 转换 1616 个模块并成功输出；
- 全部历史架构、领域、公共契约、DSL、Definition、数据库、治理、追踪与配置正反门禁通过。

最终共享交付文件逐项 SHA-256 为 0 缺失、0 不一致；权威、桌面根目录和桌面 `docs/planning` 三份总控清单哈希一致。桌面 `.git`、依赖/构建缓存和 4 个独有规划文件均保留。

## 6. 遗留边界

- 固定种子套件验证正确性和可重复性，不构成吞吐、SLO、长稳或容量证明；这些属于 G10；
- DSL v2/upcaster、跨 major bundle migration 必须建立独立语料和兼容策略，不能修改现有 v1 checksum 伪装兼容；
- Kafka relay、消费幂等、重试、死信和 Runtime/Task 状态机属于 G4/G5；
- Flyway 11.7.2 在 PostgreSQL 18.4 实测通过，但高于官方最高测试版本 17 的兼容性提示继续治理。

## 7. 结论

G3-10 已满足总控清单：解析/canonical、图算法、DSL v1 兼容性、恶意 CEL/JSONPath 与同强 ETag 高竞争更新均具备确定、可重复且防回归的证据。实现由 21 类机器反向变异、173 项 Java/PostgreSQL 测试、五模块质量门禁、桌面完整 `pnpm verify`、Secret 正反门禁和逐文件哈希共同证明。下一步进入 G4-01。
