# DSL CEL/JSONPath 表达式编译与资源沙箱

状态：Accepted  
任务：G3-05  
机器契约：`config/dsl/expression-sandbox.json`

## 1. 审计结论与边界

G3-01～G3-04 只证明表达式是非空、有界字符串，并拒绝少量显式危险词；字符串形状检查不能证明语法正确、返回类型正确或运行时资源受限。G3-05 因此在图校验和节点配置/资源授权之后增加独立编译阶段，输入为 `ValidatedWorkflowConfiguration`，输出为不可变 `CompiledWorkflowExpressions`。

本阶段完成发布前编译和可复用的运行时资源守卫，不执行工作流节点。Condition 节点的真实求值和分支选择由 G4-12 接入；HTTP 映射与响应提取由 G4-13 接入。后续执行器必须使用本阶段输出的预算与 `ExpressionEvaluationGuard`，不能自行扩大限制。

## 2. 两种受限语言

### 2.1 `workflow-cel-v1`

Condition 节点 `config.expression` 和条件边 `when` 使用 CEL 子集，结果必须为 Boolean。允许 Boolean、整数、数值、字符串和 null 字面量，具名根访问、确定性下标，以及一元、算术、比较、相等和短路逻辑运算。

子集不开放函数或方法调用、集合字面量、comprehension、宏、反射、类访问、对象创建、时间和随机源。`now`、`random`、`System`、`Runtime`、`class`、`new`、`import` 均在词法阶段拒绝。由于不存在动态调用或插件函数，表达式不能访问网络、文件、环境变量或创建线程。

### 2.2 `workflow-named-root-jsonpath-v1`

HTTP Header/Body/Result、Delay 到期时间、Human Task 指派和边参数映射使用具名根 JSONPath 子集。根固定为 `input`、`output`、`response`、`node`、`trigger`，允许：

- `.property`；
- `['property']` 或 `["property"]`；
- 非负数组索引 `[0]`。

为保证一次选择的开销可预测，禁止 `$` 根、递归下降 `..`、通配符、过滤器、切片、union 和脚本表达式。该 profile 是平台契约，不声称兼容任意第三方 JSONPath 扩展。

## 3. 静态类型

编译器从 `inputSchema` 和 `outputSchema` 提取 Boolean、Integer、Number、String、Object、Array 和 Null；缺失或当前不支持的 JSON Schema 组合退化为 Dynamic，但不会绕过语言或预算限制。显式 `additionalProperties: false` 的 Object 会拒绝未知字段。

类型期望固定如下：

| 位置 | 语言 | 期望结果 |
|---|---|---|
| Condition 节点 / 条件边 | CEL | Boolean |
| HTTP Header | JSONPath | String |
| Delay expires-at / Human assignee | JSONPath | String |
| HTTP Body/Result、边映射 | JSONPath | Dynamic/任意 JSON 值 |

编译元数据只保存语言、结果/期望类型、Token/AST/预估步数和原文 SHA-256，不复制表达式原文。失败仅返回五个稳定码和有界 JSON Pointer，不回显用户 Payload。

## 4. 双层资源边界

发布前编译限制为：最多 256 Token、256 AST 节点、32 层嵌套、32 个路径段，静态预估不超过 512 步。超限定义不能发布。

每次真实求值还必须创建独立 `ExpressionEvaluationGuard`：最多 512 步、25 ms 单调时钟预算，结果最多 256 KiB、64 层、4096 个容器条目，并只接受 JSON 兼容的 null、字符串、Boolean、有限数值、数组和字符串键对象。守卫使用单调时间，不把系统时间暴露给表达式。

## 5. 失败稳定性

发布失败码固定为：

- `expression_syntax_invalid`；
- `expression_type_mismatch`；
- `expression_identifier_unknown`；
- `expression_operation_forbidden`；
- `expression_budget_exceeded`。

运行时沙箱原因固定为 step、time、result bytes、result depth、result entries 和 result type。两类错误均不包含表达式原文、输入值、节点 ID 或资源内容。

## 6. 验收策略

Java 测试覆盖全部表达式位置、运算优先级、括号/下标、Schema 类型、未知根和闭合字段、函数/反射拒绝、JSONPath 递归/过滤拒绝、嵌套/路径预算，以及运行时步数、单调超时、结果字节、深度、条目和 JSON 类型边界。机器门禁同时锁定契约、Java 源绑定、fixture、包脚本、文档和反向变异。
