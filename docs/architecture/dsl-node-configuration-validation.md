# DSL 节点配置、集成引用与授权校验

状态：Accepted  
任务：G3-04  
机器契约：`config/dsl/node-configuration-validation.json`

## 1. 目标与边界

G3-04 接收 G3-03 已验证的 `ValidatedWorkflowGraph`，把六类节点和三类触发器的配置编译为不可变类型，并在发布前校验 connector/secret 引用的存在性、生命周期、租户/项目作用域和当前主体的使用授权。

本阶段只读取 connector 与 secret reference 的元数据快照，不读取 ciphertext、external key 或 Provider 返回的密钥材料。CEL/JSONPath 的语法、静态类型和资源沙箱仍由 G3-05 负责；Provider、轮换流程、SSRF 出站策略和使用审计仍由 G6 负责。

## 2. 向后兼容的 DSL v1 补强

审计发现原 DSL 只有 HTTP 节点的 `connectorRef`，无法表达 HMAC 触发器或 HTTP 调用所需密钥。因此在不改变既有必填字段的前提下增加两个可选能力：

- HTTP Trigger：`hmacSecretRef`。当 `authentication=hmac` 时必填；OIDC 时禁止。
- HTTP Node `config`：`secretRefs`，最多 20 个、不可重复，只保存受管引用。

这两个字段是 DSL v1 的可选扩展；旧 OIDC、manual、cron 以及不使用额外密钥的 HTTP 定义保持有效。

## 3. 专属 Schema 与类型

| 类型 | 关键约束 | 类型化结果 |
|---|---|---|
| Manual Trigger | 不允许专属字段 | `ManualTriggerConfiguration` |
| HTTP Trigger | OIDC/HMAC、强制幂等；HMAC 密钥 XOR | `HttpTriggerConfiguration` |
| Cron Trigger | cron 形状、真实 IANA `ZoneId`、misfire/catch-up 联动 | `CronTriggerConfiguration` |
| Start | 不允许专属字段 | `StartNodeConfiguration` |
| End | outcome 可选，默认 succeeded | `EndNodeConfiguration` |
| HTTP | connector、方法、模板、Header/Body/Result、最多 20 个 Secret | `HttpNodeConfiguration` |
| Condition | 非空、受长度限制的表达式字符串 | `ConditionNodeConfiguration` |
| Delay | 正 duration 与到期表达式严格二选一 | `DelayNodeConfiguration` |
| Human Task | role/assignee 二选一；form/timeout/deadline 联动 | `HumanTaskNodeConfiguration` |

表达式在这里仅校验 JSON 类型、非空和长度，不能据此宣称 CEL/JSONPath 已安全；G3-05 将继续解析并执行类型与资源边界校验。

## 4. 引用解析和授权

验证上下文固定 `tenantId`、`projectId`、不含密钥材料的资源目录快照及 `ResourceUseGrant(kind, resourceId)` 集合。解析规则固定为：

1. 仅考虑当前 tenant 的同类型、同引用资源；跨租户对象等同不可用。
2. 先查当前 project，再回退到 `project_id=NULL` 的租户级对象；其他 project 不可见。
3. 项目级同名对象存在时会遮蔽租户级对象，即使项目对象已禁用或删除，也不得绕过到租户对象。
4. 只有未软删除且状态为 `ACTIVE` 的对象可发布；`DISABLED` 与 Secret 的 `ROTATION_REQUIRED` 均拒绝。
5. 存在且有效后仍须有同 kind、同资源 ID 的显式 use grant。

校验结果只保留资源 ID、引用、kind 和 project/tenant 作用域，不复制 endpoint、config、provider key 或任何 secret material。

## 5. 错误与确定性

校验顺序固定为触发器定义顺序、节点定义顺序和字段校验阶段。异常只返回九个稳定失败码及有界 JSON Pointer，不包含引用值、节点 ID、Secret 名称或用户 Payload。`unavailable` 统一覆盖不存在、跨作用域、禁用、待轮换和软删除，避免从公开错误中枚举资源状态。

## 6. 验收

Java 正反测试覆盖全部九个配置变体、项目优先/租户回退、遮蔽规则、类型错误、重复引用、跨租户/跨项目、禁用/删除/待轮换及无授权。契约门禁同时编译 JSON Schema、验证配置 fixture、核对机器契约与 Java 源绑定，并通过反向变异证明关键约束不可移除。
