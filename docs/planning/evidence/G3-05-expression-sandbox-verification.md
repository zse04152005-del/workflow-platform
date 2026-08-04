# G3-05 CEL/JSONPath 表达式编译与资源沙箱验收证据

## 1. 任务

- 任务：G3-05 实现 CEL 条件表达式语法校验与沙箱边界
- Requirement：`REQ-DSL-001`
- 完成日期：2026-07-31
- 状态：`[x]`

## 2. 开发前审计与优化

审计 G3-01～G3-04 后确认五个发布风险：

1. 原实现只检查非空、2048 字符和少量危险词，非法语法仍可能进入发布结果；
2. Condition 与数据选择共用无语义字符串，Boolean 条件和 String/JSON 映射无法静态区分；
3. `inputSchema`/`outputSchema` 尚未参与类型推断，闭合对象的未知字段不会被提前发现；
4. JSONPath 若开放递归、通配、过滤和脚本，求值开销与数据暴露范围无法预测；
5. 没有统一的步数、时间、结果字节、深度和条目守卫，后续执行器容易各自扩大边界。

本次增加独立 G3-05 编译阶段：Condition 节点和条件边固定使用 `workflow-cel-v1`；HTTP/Delay/Human Task/边映射固定使用 `workflow-named-root-jsonpath-v1`。编译输出只保存类型、预算指标和表达式 SHA-256，不复制原文；运行时守卫作为 G4-12/G4-13 必须复用的边界实现。

## 3. 实现结果

### 3.1 CEL 子集

- 支持 Boolean/Integer/Number/String/Null、括号、具名根、属性/确定性下标；
- 支持一元、算术、比较、相等和短路逻辑运算及确定性优先级；
- Condition 节点和条件边结果必须为 Boolean；
- 禁止任意函数/方法调用、集合字面量、comprehension、反射、类/对象创建、时间和随机源；
- `now/random/System/Runtime/class/new/import` 在词法阶段拒绝。

### 3.2 具名根 JSONPath 子集

- 根固定为 `input/output/response/node/trigger`；
- 只允许 `.property`、`['property']`/`["property"]` 和非负数组索引；
- 禁止 `$` 根、递归下降、通配符、过滤器、切片、union 和脚本；
- Header、Delay expires-at 和 Human assignee 必须静态可赋值为 String；Body/Result/边映射允许 JSON Dynamic。

### 3.3 静态类型与脱敏结果

- 从 `inputSchema`/`outputSchema` 提取 8 类类型；空或暂不支持的 Schema 组合退化为 Dynamic；
- `additionalProperties: false` 的 Object 拒绝未知字段；
- 五个稳定失败码只携带有界 JSON Pointer，不回显表达式、节点 ID 或输入值；
- `CompiledExpression` 仅保存语言、结果/期望类型、Token/AST/步数和小写 SHA-256；集合深层不可变。

### 3.4 双层资源沙箱

- 编译期：256 Token、256 AST 节点、32 层嵌套、32 个路径段、512 预估步；
- 求值期：512 步、25 ms 单调时间、256 KiB 结果、64 层、4096 条目；
- 结果只接受 JSON 兼容的 null、String、Boolean、有限数值、数组和字符串键对象；
- `ExpressionEvaluationGuard` 每次求值独立创建，后续执行器不能共享或扩大预算。

## 4. 关键文件

- `config/dsl/expression-sandbox.json`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/DeterministicWorkflowExpressionCompiler.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/CompiledWorkflowExpressions.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/ExpressionSandboxPolicy.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/ExpressionEvaluationGuard.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowExpressionCompilerTests.java`
- `tests/contracts/dsl/expressions`
- `scripts/dsl/verify-expression-sandbox.mjs`
- `scripts/dsl/test-expression-sandbox-verifier.mjs`
- `docs/architecture/dsl-expression-sandbox.md`

## 5. 自动化验收

### 5.1 G3-05 契约门禁

```text
DSL G3-05 expression verification PASS: 2 profiles, 5 failure codes, 3 valid and 7 invalid fixtures.
DSL G3-05 verifier self-test PASS: 19 negative mutations blocked.
```

Java G3-05 共 10 个测试方法，覆盖全部表达式位置、优先级/括号/下标、Schema 类型、未知根和闭合字段、任意函数/方法和反射拒绝、JSONPath `$`/递归/通配/过滤拒绝、嵌套/路径预算，以及运行时步数、单调超时、结果字节/深度/条目/JSON 类型。

### 5.2 Maven 五模块

Java 21 / Maven 3.9.16 `verify` 结果：

- shared-contracts：16 项；
- control-plane unit + PostgreSQL Testcontainers integration：95 项；
- worker：1 项；
- test-support：2 项；
- 合计：114 项，0 失败、0 错误、0 跳过。

PostgreSQL 18.4、Flyway V1～V5、PMD、CPD、SpotBugs、Checkstyle、Spotless 与 JaCoCo 全绿。control-plane 实测行覆盖率 86.09%，分支覆盖率 71.63%。

### 5.3 整仓 Node/前端/Secret 门禁

Node/pnpm `pnpm verify` 完整通过：

- G3-01～G3-05、公共契约、架构、领域、数据库、治理、追踪和配置正反门禁全绿；
- Gitleaks 固定镜像扫描 371 个提交候选无泄漏，合成硬编码 Secret 被正确阻断；
- Web Console lint、TypeScript typecheck、3 项 Vitest 和 Vite production build 通过。

最终证据写入后，开发源提交候选复扫为 372 个，仍无泄漏且合成 Secret 继续被阻断。

### 5.4 桌面交付独立复核

- Robocopy 非镜像增量同步共处理 372 个交付文件，本轮复制 29 个、跳过 343 个，0 失败、0 mismatch；
- 逐文件 SHA-256 复核：372 个共享文件，0 缺失、0 不一致；
- 权威总控清单与桌面根目录、`docs/planning` 两份清单哈希一致；
- 桌面 `dsl:g3-05:verify/test` 独立通过，19 类反向变异全部阻断；
- 桌面 376 个提交候选 Secret 扫描无泄漏，合成 Secret 被阻断；
- 桌面既有 `.git` 保留，开发源临时 `.git` 已精确清理。

## 6. 遗留边界

- G3-06～G3-09 继续负责草稿、Validate/Publish API、不可变版本、克隆/导入导出/差异；当前编译结果尚未接 API 生命周期。
- G4-12 接入 CEL 运行时求值和 Boolean 分支；G4-13 接入 HTTP JSONPath 映射/提取，并必须使用本阶段守卫。
- G3-10 继续扩充属性测试、兼容性和更大恶意表达式语料；本任务已有针对当前边界的确定性反例，但不提前完成 G3-10。
- 当前静态类型支持 JSON Schema 基础 `type/properties/items/additionalProperties`；组合关键字暂退化为 Dynamic，不影响语法、根、操作和预算安全。后续若需要 oneOf/anyOf 精确类型须版本化扩展。
- Flyway 对 PostgreSQL 18.4 的声明支持上限提示继续按既有治理跟踪。

## 7. 结论

G3-05 已满足总控清单：CEL 条件和具名根 JSONPath 在发布前完成语法、静态类型和确定性预算编译，危险能力被阻断，运行时资源边界已有可复用守卫和自动化证据。下一步执行 G3-06。
