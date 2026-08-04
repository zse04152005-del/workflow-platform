# G3-03 DSL 工作流图校验验收证据

任务：G3-03 实现图校验：唯一 Start、节点 ID 唯一、路径可达、边合法、禁止非法环  
Requirement：`REQ-DSL-001`  
完成日期：2026-07-31  
状态：Accepted

## 1. 审核结论与补强项

原始大纲要求发布前校验“起点唯一、节点 ID 唯一、路径可达、无非法循环”，但没有固定 End 终止性、边 transition 歧义、错误顺序和循环白名单。G3-03 在不越过后续节点权限与表达式编译边界的前提下补齐以下企业级不变量：

- Start 恰好一个、End 至少一个；允许多个业务结果 End；
- 节点 ID 和边 ID 分别全局唯一，边两端引用必须存在；
- 禁止自环、指向 Start 的入边、从 End 发出的出边和重复 `from/to/on` 路由；
- 固定六类节点的 transition 矩阵、出边基数和 `always` 歧义规则；
- 所有节点从 Start 正向可达，所有非 End 节点反向可达至少一个 End；
- DSL v1 没有显式迭代上限或退出预算，因此所有有向环均非法；
- 违规按校验阶段和定义顺序聚合，位置使用最长 128 字符 JSON Pointer，异常消息不拼接 Payload；
- 验证结果提供按节点定义索引打破并列的确定性拓扑序，算法复杂度 `O(V+E)`。

这解决了仅检查“从 Start 能看到节点”仍可能留下不终止分支、相同 transition 多候选、`always` 与结果边重叠，以及 HashMap 顺序导致编译产物漂移的问题。

## 2. 交付物

| 类别 | 文件 | 作用 |
|---|---|---|
| 机器契约 | `config/dsl/graph-validation.json` | 固定节点/边/transition/可达性/环/确定性/失败码和延期边界 |
| 校验入口 | `WorkflowGraphValidator.java` | 接收 G3-01 `WorkflowDefinition` 的稳定接口 |
| 实现 | `DeterministicWorkflowGraphValidator.java` | 身份索引、局部边规则、正反可达、三色 DFS 和确定性 Kahn 排序 |
| 失败契约 | `WorkflowGraphValidationFailure.java`、`WorkflowGraphViolation.java`、`WorkflowGraphValidationException.java` | 16 个稳定失败码、JSON Pointer 和脱敏聚合异常 |
| 不可变结果 | `ValidatedWorkflowGraph.java` | 原定义、唯一 Start、End 列表和确定性拓扑顺序 |
| Java 测试 | `WorkflowGraphValidatorTests.java` | 27 项场景，包括 500 节点最大深度 |
| 契约 fixture | `tests/contracts/dsl/graph` | 3 个合法图、18 类非法图 |
| 静态门禁 | `verify-dsl-graph-validation.mjs` | 交叉校验机器契约、Java、fixture、根脚本、文档和追踪 |
| 反向控制 | `test-dsl-graph-validation-verifier.mjs` | 37 类独立变异必须被正向门禁拒绝 |
| 架构说明 | `docs/architecture/dsl-graph-validation.md` | 阶段流程、矩阵、环策略、复杂度、错误和延期边界 |
| 追踪 | `config/traceability/requirements.json`、`docs/planning/03-需求追踪矩阵.md` | 将 G3-03 标为已实现，继续保留 G3-04/G3-05 为 planned |

## 3. 固定图规则

### 3.1 transition 矩阵

| 源节点 | 允许 transition | 附加规则 |
|---|---|---|
| Start | `success`、`always` | 恰好一条出边 |
| End | 无 | 禁止所有出边 |
| HTTP | `success`、`failure`、`always` | 同 transition 最多一条；`always` 不与结果边并存 |
| Condition | `condition`、`always` | 至少两条出边、至少一条条件分支、最多一个默认分支 |
| Delay | `success`、`failure`、`always` | 同 HTTP 的确定性规则 |
| Human Task | `success`、`failure`、`always` | 同 HTTP 的确定性规则 |

### 3.2 16 个稳定失败码

`start_node_count_invalid`、`end_node_required`、`duplicate_node_id`、`duplicate_edge_id`、`unknown_edge_source`、`unknown_edge_target`、`self_loop_not_allowed`、`incoming_edge_to_start`、`outgoing_edge_from_end`、`transition_not_allowed`、`duplicate_edge_route`、`outgoing_transition_ambiguous`、`outgoing_edge_count_invalid`、`node_unreachable`、`node_cannot_reach_end`、`cycle_not_allowed`。

## 4. 自动化验收结果

| 门禁 | 结果 |
|---|---|
| `pnpm dsl:g3-03:verify` | PASS：唯一 Start/ID/引用、合法 transition、双向可达、禁止有向环、确定性拓扑、16 个失败码、27 项 Java 场景和 21 份 fixture 一致 |
| `pnpm dsl:g3-03:test` | PASS：37 类机器契约、算法标记、Java、fixture、根脚本、文档与追踪变异全部被拒绝 |
| G3-01/G3-02 回归 | PASS：18/28 类反向门禁及历史延期边界继续成立；矩阵明确记录 G3-03 已实现 |
| Maven 五模块离线 `clean verify` | PASS：Java 95 项，0 failures / 0 errors / 0 skipped；其中 G3-03 27 项、PostgreSQL 18.4 集成 4 项 |
| 500 节点上限 | PASS：完整 500 节点链通过三色 DFS 和拓扑排序，顺序从 Start 到 End，无栈溢出 |
| Java 质量 | PASS：Spotless、Checkstyle、PMD、CPD、SpotBugs、JaCoCo 全绿，未降低或屏蔽全局规则 |
| Node 24.14.0 / pnpm 11.9.0 `pnpm verify` | PASS：架构、领域、错误、公共契约、G3-01～G3-03、ER/迁移、治理、追踪、配置、Secret、前端 lint/typecheck/test/build 全绿 |
| migrations / Compose / CI | PASS：5 个迁移和 SHA-256 catalog、7 个固定 digest Compose 服务、4 个 CI jobs / 9 个 SHA-pinned Actions / actionlint |
| Secret | PASS：最终 321 个提交候选无 Secret，合成硬编码 Secret 被拒绝；Gitleaks 镜像固定 digest |
| 桌面交付审计 | PASS：工作镜像与桌面 321 个共享交付文件逐文件 SHA-256 为 0 missing / 0 mismatch；桌面只额外保留 3 份规划基线 |

## 5. 审核中发现并修复的问题

1. 首轮 PMD 报告 13 项：字面量、循环内构造、字段/方法同名和测试断言消息等均逐项修正，没有降低规则集。
2. SpotBugs 发现聚合异常包含未声明可序列化的违规值对象；已让 `WorkflowGraphViolation` 显式实现 `Serializable`，保留完整诊断数据而非使用 transient 丢弃。
3. G3-02 历史门禁要求追踪矩阵保留字面任务号 `G3-03`；矩阵已从泛化“图校验”改为明确“G3-03 图校验”，避免完成状态破坏历史可追踪性。
4. 初始测试只覆盖一般拓扑和环形状，未直接证明 500 节点最大递归深度；最终新增完整上限链测试并纳入静态门禁。

## 6. 明确遗留边界

- G3-04 继续负责六类节点和三类触发器的专属字段、connector/secret 存在性及租户授权；G3-03 不虚报这些能力。
- G3-05 继续负责 CEL/JSONPath 解析、类型、确定性函数白名单、资源预算和表达式沙箱。
- G3-10 继续负责跨版本属性、兼容组合和更大恶意输入全集。
- 当前 `ValidatedWorkflowGraph` 是编译前图证明，不等于版本可发布，也不提前实现 G3-06～G3-09 API/生命周期。
- PostgreSQL 18.4 集成仍显示 Flyway 对最新支持版本为 17 的既有提示；迁移和 Catalog 验收全部通过，该依赖支持矩阵继续按既有治理跟踪。

## 7. 结论与下一步

G3-03 满足总控清单的唯一 Start、节点/边 ID、引用存在、路径可达、边合法与禁止非法环要求，并补齐终止性、确定性和错误契约。下一步执行 G3-04：实现 Start/End/HTTP/Condition/Delay/Human Task 及 Manual/HTTP/Cron 的节点/触发器专属 Schema、connector/secret 引用和授权校验。
