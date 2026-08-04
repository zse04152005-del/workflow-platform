# 工作流克隆、导入导出与版本差异 API

## 1. 范围与接口

G3-09 提供四个已实现接口：

- `POST /api/v1/workflows/{workflowId}/clone`：从当前草稿或指定已发布版本创建新草稿；
- `GET /api/v1/workflows/{workflowId}/export`：省略 `version` 导出当前草稿，指定正整数 `version` 导出不可变版本；
- `POST /api/v1/workflows/import`：校验并导入 `workflow-platform-definition` v1 JSON bundle；
- `GET /api/v1/workflows/{workflowId}/versions/diff`：按 `fromVersion`、`toVersion` 比较两个不可变版本。

Clone 与 Import 必须携带 `Idempotency-Key`，成功返回 HTTP 201、Location、强 ETag 和标准 `WorkflowDetail`。Export 与 Diff 返回 `Cache-Control: no-store`。所有读取先设置 transaction-local tenant context，并继续依赖 22 表 FORCE RLS；不存在与跨租户资源统一返回 404。

## 2. Bundle 完整性与数据最小化

导出包固定 `format=workflow-platform-definition`、`formatVersion=1`，包含来源 workflowId、来源类型、草稿 revision 或发布 version、持久化时间、canonical DSL SHA-256，以及 workflow name/description/definition。导入会重新执行 G3-02 有界解析与 canonical 化，并要求：

1. bundle source checksum 与 definition 的 canonical SHA-256 完全一致；
2. workflow.name 与 DSL 根 name 一致；
3. resources 清单与 DSL v1 的 `hmacSecretRef`、`connectorRef`、`secretRefs` 精确一致，无缺失、额外或重复项；
4. format、版本、来源选择器和字段上限合法。

resources 只输出 `kind/reference`，明确禁止 tenantId、projectId、connector endpoint/config、Secret ciphertext/external key/key version 或任何 Secret material。Bundle 不输出数据库内部 resource UUID，也不自动复制连接器或 Secret。

原始 DSL 仍受 1 MiB parser 上限；带清单和映射的 JSON 请求受 2,200,000 字符、64 层、200,000 Token 等全局 Jackson 边界保护。最多允许 10,000 个不同资源引用/映射。

## 3. 资源重绑策略

同项目 Clone 默认保持引用名称，可提供显式 override。跨项目 Clone 必须对全部资源引用提供 `kind/sourceReference/targetReference` 映射；Import 无论目标环境是否看似同名，都要求完整显式映射。未知、重复、类型不匹配、缺失或额外映射均返回 400，避免同名资源在另一项目或租户中被静默重绑。

Clone/Import 创建的是 revision 1、状态 DRAFT 的可编辑草稿，因此允许复制尚未通过完整图/资源/表达式校验的草稿；这不是发布绕过。G3-08 Publish 仍会在锁定事务内重新执行四阶段校验，资源不存在、不可用或无 use grant 时无法发布。

## 4. 幂等、审计与事务

Clone 的 24 小时幂等 scope 为 `workflow:clone:{sourceWorkflowId}`，Import 为 `workflow:import`。请求摘要绑定目标项目、名称、描述、来源类型/revision/version/checksum 和重写后的 canonical DSL。同键同请求重放返回原草稿；同键异请求、名称冲突或回放摘要不一致返回 409。

单事务顺序为 `tenant context -> idempotency claim -> source read/bundle validation -> active target project -> draft insert -> append-only audit -> idempotency completion`。审计 action 分别为 `workflow.definition.clone` 与 `workflow.definition.import`，只记录来源/目标 workflow 标识、来源 revision/version 和两端 checksum，不记录 DSL、资源名称、连接配置或 Secret。任一步失败时草稿、审计和幂等领取整体回滚。本任务复用 V2/V4/V5/V6，不新增 Flyway 迁移。

## 5. 确定性版本差异

Diff 只读取 immutable `workflow_version`。对象字段按字典序、数组按索引递归比较，路径使用 RFC 6901 JSON Pointer 转义，操作只有 add/remove/replace。`changeCount` 保留完整数量，公开 `changes` 最多 1000 条，超限时 `truncated=true`。

单个 before/after JSON 预览最多 4096 UTF-8 bytes；超限后只返回 `type/sha256/truncated` 摘要，避免一个大子树突破响应预算。相同版本允许比较并稳定返回零差异。Diff 不修改历史版本，也不读取 trigger、connector 或 Secret 材料。

## 6. 后续边界

G3-10 继续扩展跨版本兼容性、恶意组合输入和并发更新语料；G8-03～G8-05 消费这些后端接口实现列表、克隆、编辑器和可视化差异。跨 Major bundle upcaster、图形化语义 diff 与插件市场资源自动迁移不属于本任务。
