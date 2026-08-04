# OpenAPI、DSL 与事件公共契约

- 状态：Accepted
- 日期：2026-07-30
- 对应任务：G2-10
- 对应需求：REQ-CONTRACT-001、REQ-DATA-001、REQ-DSL-001
- Schema 方言：JSON Schema 2020-12

## 1. 权威源

| 契约 | 权威文件 | 当前版本 | 用途 |
|---|---|---:|---|
| HTTP API | `api/openapi/workflow-platform.v1.openapi.json` | OpenAPI 3.1 / API 1.0.0 | 公开控制面路径、请求、响应、安全和错误引用 |
| DSL | `schemas/dsl/workflow-definition-v1.schema.json` | `schemaVersion=1` | MVP 触发器、节点、边、重试、超时和受限表达式形状 |
| Event Envelope | `schemas/events/event-envelope-v1.schema.json` | `schemaVersion=1` | 跨模块/Topic 的公共事件信封 |
| 兼容基线 | `config/contracts/compatibility-baseline.json` | 1.0.0 | 冻结操作、字段、类型、枚举与演进策略 |
| 错误目录 | `config/api/problem-details.json` | 1.0.0 | RFC 9457 错误码、成员、trace 和脱敏策略 |

校验命令：

```powershell
pnpm contracts:public:verify
pnpm contracts:public:test
```

## 2. HTTP 边界

所有公开路径位于 `/api/v1`。当前文件是实现前的权威契约，operation 均显式标记
`x-workflow-implementation-status: planned`；只有对应业务任务交付并通过实现一致性测试后，才能按事实更新状态。

契约覆盖 15 个 MVP operation：工作流列表/创建/读取/草稿更新/校验/发布/启动实例，实例列表/读取/暂停/恢复/取消/重放，以及人工待办列表/决策。

安全与并发规则：

- 顶层使用 OIDC；租户从已验证的认证上下文获得，请求模型禁止客户端 `tenantId`。
- 所有非 GET operation 必须要求 `Idempotency-Key`，作用域由租户、operation 和目标资源共同确定。
- `PUT /api/v1/workflows/{workflowId}/draft` 额外要求强 ETag 的 `If-Match`，避免多人覆盖草稿。
- 列表使用不透明 cursor 和有上限的 limit，不定义大 offset 分页。
- 尚未实现的接口不能因 OpenAPI 已存在而被宣称可运行。

## 3. 统一错误引用

所有 operation 的 `default` 错误响应统一引用 `ProblemResponse`，媒体类型为
`application/problem+json`。`ProblemDetail` 与 G2-09 对齐：

- 必需 RFC 9457 成员：`type/title/status/detail/instance`。
- 必需扩展：`code/traceId/errors/retryable`；可选 `retryAfterSeconds`。
- 字段错误只含 `field/code/message`，最多 50 项，不返回 rejected value。
- `instance` 只允许 request path；额外成员被拒绝。
- 响应头记录 `Cache-Control: no-store` 和 `X-Trace-Id`，认证与可重试错误可使用 `WWW-Authenticate`、`Retry-After`。

## 4. DSL v1 边界

DSL v1 冻结六类节点：`start`、`end`、`http`、`condition`、`delay`、`human_task`；冻结三类触发器：`manual`、`http`、`cron`。每个节点必须声明 `executorVersion`，运行实例必须绑定发布时的 Schema、catalog 和 executor 版本。

安全和确定性限制：

- HTTP 节点只通过 `connectorRef` 选择受管 endpoint，可选 `secretRefs` 只保存受管密钥引用；Schema 不开放任意 endpoint/url 或密钥材料。
- 条件、映射和动态取值使用受限表达式；v1 显式阻断 `now()`、`random()`、`System`、`Runtime` 与 `class` 等非确定性或反射入口。
- Cron 必须显式 timezone 和 misfire policy；重试、延迟、deadline 与 timeout 使用 ISO-8601 duration。
- 不提供任意脚本节点，不把完整 BPMN、并行汇聚、子流程或补偿伪装成 MVP 能力。

JSON Schema 负责结构、基础类型和局部条件。唯一 Start、节点 ID 唯一、节点可达、边引用、非法环、连接器/密钥存在、参数类型与 CEL 资源限制由 G3 编译器完成；Schema 通过不等于流程可发布。

## 5. Event Envelope v1

必需成员是 `eventId/eventType/occurredAt/tenantId/traceId/schemaVersion/payload`：

- eventId 与 tenantId 使用 UUIDv7；occurredAt 使用 RFC3339 UTC `Z`，小数秒不超过 6 位。
- traceId 为 32 位小写十六进制且不能全零。
- eventType 使用稳定的点分域名；payload 必须是 JSON object。
- 根对象允许未知可选成员，消费者必须忽略不认识的可选字段，从而允许生产者向前添加元数据。

Envelope 只锁定公共信封，不提前定义每一种 payload。具体事件类型的 payload Schema、Topic/Key、生产者和消费者契约随 G3～G7 对应能力建立。

## 6. 兼容性规则

| 变化 | v1 内是否允许 | 正确动作 |
|---|---|---|
| 新增 API operation | 是 | 保持唯一 operationId，补充权限、错误和测试 |
| 新增可选请求/响应字段 | 是 | 记录默认/缺省语义并加兼容测试 |
| 删除或重命名 operation/响应字段 | 否 | 新 `/api/v2`、ADR、迁移指南 |
| 改字段类型或删除 enum 值 | 否 | 新 Major，保留迁移窗口 |
| 给已有请求新增必填字段 | 否 | 新 Major 或改为可选且定义默认行为 |
| 修改 DSL v1 的破坏性语义 | 否 | 新 Schema 文件、递增 schemaVersion、upcaster 和迁移测试 |
| 删除 Event 必需字段或关闭未知字段 | 否 | 新事件 Major/类型和生产/消费迁移 |
| Event 新增可选字段 | 是 | 消费者忽略未知字段；生产者契约测试 |

兼容门禁把当前 operationId、成功状态、必需头、代表性请求/响应字段、类型与 enum 固化在机器基线中。变更兼容基线不是绕过门禁的手段：破坏性变更必须先有 ADR 和迁移资产，再以新 Major 工件并存。

## 7. 验证与反向控制

正向门禁执行：JSON 可解析、全部 `$ref` 可达、OpenAPI/OIDC/幂等/ETag/租户边界、Problem Details 对齐、兼容基线、DSL/Event Schema 编译，以及全部正反 fixture。

反向测试主动破坏 operation、operationId、必需头、Problem Details 引用、租户边界、响应字段、请求必填字段、字段类型、enum、`$ref`、OIDC、DSL/Event 版本和必需字段、未知事件字段兼容性及 fixture 约束。任何一类破坏未被阻断都使总门禁失败。

## 8. 后续实现边界

- G3 实现 DSL 解析/规范化/编译、草稿、Validate 与 Publish，并验证实现与 OpenAPI/Schema 一致。
- G4/G5 实现实例、控制、重放和可靠运行协议；G7 实现 Human Task API。
- G6 接入真实 OIDC、RBAC、对象级授权、请求保护与租户传播。
- G9 完成 HTTP/Kafka Trace Context、结构化日志与低基数遥测。
- G11-02 基于本契约生成文档/SDK 示例；生成物不能反向成为权威源。
