# DSL v1 核心模型与确定性边界

状态：Accepted（G3-01）  
权威机器契约：`config/dsl/core-model.json`  
公开结构契约：`schemas/dsl/workflow-definition-v1.schema.json`  
Java 值模型：`shared-contracts/src/main/java/com/workflowplatform/contracts/dsl`

## 1. 目标

G3-01 固定工作流 DSL 的结构和值对象边界，使定义在进入解析器、编译器和运行时之前具备稳定的版本、节点公共字段、边、参数、重试与超时语义。当前版本永久使用 `schemaVersion = 1`；破坏性变更必须新建 Schema 文件、提升版本并提供 upcaster 与迁移测试，不能原地改变 v1。

本任务只声明已经由 JSON Schema、Java 构造器、单元测试和静态门禁共同实现的能力，不把后续解析、图校验或表达式编译能力记为完成。

## 2. 根模型与版本绑定

根对象必须包含 `schemaVersion`、`name`、`catalogVersion`、`triggers`、`nodes` 和 `edges`，可选 `description`、`inputSchema`、`outputSchema` 与 `metadata`。资源边界固定为：

| 对象 | 最小值 | 最大值 |
|---|---:|---:|
| triggers | 1 | 20 |
| nodes | 2 | 500 |
| edges | 1 | 2000 |
| metadata 属性 | 0 | 32 |

确定性执行依赖三层不可变版本绑定：根定义绑定 `schemaVersion` 和 `catalogVersion`，每个节点绑定 `executorVersion`。仅记录类型而不记录执行器版本会导致重新执行时语义漂移，因此 Java 模型拒绝小于 1 的 catalog/executor 版本。

`WorkflowParameters` 保存输入和输出 JSON Schema。G3-02 的解析适配器负责在 DSL 根字段 `inputSchema`/`outputSchema` 与该值对象之间映射；G3-01 不提前实现序列化或 checksum。

## 3. 节点与触发器公共字段

DSL v1 固定六类节点：`start`、`end`、`http`、`condition`、`delay`、`human_task`；固定三类触发器：`manual`、`http`、`cron`。任意脚本节点不在允许列表中。

所有节点必须提供 `id`、`type`、`executorVersion`，可选 `displayName`。Java 的 `WorkflowNode` 明确保存这些公共字段，同时以深层不可变 JSON 对象暂存节点专属 configuration。只有 HTTP 节点允许顶层 `timeout` 和 `retry`；其他节点携带这两个字段会在构造时被拒绝。

`WorkflowTrigger` 固定 `id` 和 `type`，类型专属配置暂存为深层不可变 JSON 对象。HTTP/Cron 参数的完整类型和权限规则由 G3-04 校验，不在公共模型构造器中重复实现半套规则。

## 4. 边与参数映射

边必须包含 `id`、`from`、`to`、`on`，转换类型固定为 `success`、`failure`、`always`、`condition`。`condition` 边必须提供 `when`；其他转换禁止 `when`。单条边最多包含 100 个参数映射目标，目标使用点分路径，表达式长度不超过 2048 字符。

JSON Schema 用对象表达 `mapping`，因此天然保证目标键唯一。Java 使用 `List<ParameterMapping>` 保留显式映射值对象，并额外拒绝重复 target。G3-02 解析时必须采用确定性顺序把对象转换为列表；该排序和规范化规则不属于 G3-01。

## 5. 重试与时间模型

`RetryPolicy` 的 `maxAttempts` 范围为 1～10，所有策略都需要正值 `initialDelay`，并可设置正值 `maxDelay`、`maxElapsedTime` 与 0～1 的 `jitterRatio`。`maxDelay` 和 `maxElapsedTime` 均不得小于 `initialDelay`。

| 策略 | 必须字段 | 禁止字段 | 计算语义 |
|---|---|---|---|
| `fixed` | 无附加字段 | `increment`、`multiplier` | 每次使用固定初始间隔 |
| `linear` | 正值 `increment` | `multiplier` | 每次按固定增量增加间隔 |
| `exponential` | 1～10 的 `multiplier` | `increment` | 每次按倍率增加间隔 |

`maxDelay` 限制单次退避，`maxElapsedTime` 限制整个重试窗口，`timeout` 限制 HTTP 单次执行。Delay 的 `duration`、Human Task 的 `deadline` 与 HTTP 的 `timeout` 都使用 ISO-8601 duration。JSON Schema 负责格式校验；Java 核心模型进一步拒绝零值和负值，避免 `PT0S` 等值绕过运行时边界。

## 6. 深层不可变与确定性

所有进入 Java 值模型的 List、Map 和嵌套 JSON List/Map 都会递归复制并包装为不可修改集合，调用方后续修改原集合不会改变已构造的定义。仅允许 JSON 标量、对象和数组；拒绝非字符串对象键、非 JSON Java 对象以及 NaN/Infinity。

表达式形状门禁当前拒绝 `now()`、`random()`、`System.`、`Runtime.` 和 `class` 等确定性漂移入口，并限制空表达式与长度。这里是结构级防线，不等于 CEL/JSONPath 解析、类型检查或资源沙箱；完整表达式安全由 G3-05 负责。

## 7. 明确不负责的后续边界

G3-01 不负责以下能力，追踪状态必须继续保持 planned：

- G3-02：JSON/YAML 解析、字段规范化、canonical JSON、checksum 和字节大小限制。
- G3-03：唯一 Start、节点/边 ID 唯一、边引用存在、可达性与环规则。
- G3-04：节点专属参数类型、connector/secret 引用及授权。
- G3-05：CEL/JSONPath 语法、类型检查、资源预算和表达式沙箱。
- G3-10：编译器属性测试、兼容性组合与恶意输入全集。

因此 Java 测试会明确证明重复 Start、悬空边引用和仅满足形状的未完整表达式暂不在 G3-01 构造器中被误判；这些不是允许进入发布态，而是后续编译阶段必须收口的职责分离。

## 8. 自动化验收

```powershell
pnpm dsl:g3-01:verify
pnpm dsl:g3-01:test
mvn -pl shared-contracts -am test
```

静态门禁交叉校验机器契约、DSL Schema、兼容性基线、Java record/enum、不变量测试、正反 fixtures、文档和根 `pnpm verify` 绑定。反向门禁通过修改版本、节点/策略集合、边规则、资源上限、Java 不变量和文档边界，证明漂移会被拒绝。
