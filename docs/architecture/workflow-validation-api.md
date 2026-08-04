# 工作流 Validate API、精确定位与资源授权快照

## 1. 已实现范围与审计优化

G3-07 实现 `POST /api/v1/workflows/{workflowId}/validate`。请求 `{}` 校验持久化草稿，请求携带 `definition` 时校验临时定义；两种模式分别返回 `source=current_draft` 与 `source=supplied_definition`。临时定义名称必须与目标工作流名称一致，避免把一份工作流内容误当成另一份工作流的校验结果。

审计补强了原契约的三个歧义：结果绑定 canonical SHA-256 `checksum`，当前草稿额外绑定 `draftRevision`；返回总 `issueCount` 和 `truncated`，最多公开 500 条；每项错误使用稳定 code、有界 JSON Pointer `path`，路径可安全解析时附带 `nodeId`。因此调用方能明确知道“校验了什么”和“错误落在哪个节点/字段”。

Validate 只要求并校验 `Idempotency-Key` 的传输格式，不写 `idempotency_record`，因为它是无副作用的只读 POST。Validate 结果也不是发布凭证；G3-08 Publish 必须在发布事务中重新读取目标修订并重新校验，不能信任历史 Validate 响应。

## 2. 确定性短路流水线

服务按固定顺序执行：

```text
parse -> graph -> resource-and-configuration -> expression
```

任一阶段失败即短路，不执行依赖后续阶段。解析成功后才返回 checksum；临时定义解析失败返回 HTTP 200、`valid=false`，持久化草稿若无法重新解析则视为数据完整性错误，而不是把损坏数据伪装成普通用户输入错误。名称漂移在图校验前失败。

公开目录包含 40 个稳定错误码：9 个 `DSL_PARSE_*`、1 个 `DSL_DEFINITION_NAME_MISMATCH`、16 个 `DSL_GRAPH_*`、9 个 `DSL_CONFIGURATION_*` 和 5 个 `DSL_EXPRESSION_*`。公开 message 使用固定脱敏文本，严重级别在本阶段仅允许 `error`；内部异常、SQL、租户 ID、Secret 和表达式内容不会进入响应。

## 3. 节点与字段定位

图、配置和表达式校验器产生 JSON Pointer。映射器保留原路径，并在以下情况补充节点 ID：

- `/nodes/{index}/...` 映射到对应节点 ID；
- `/edges/{index}/from` 和 `/edges/{index}/to` 映射到明确的源或目标节点；
- 边整体、未知索引或不明确路径不猜测 `nodeId`。

API 返回全部错误总数，但最多输出前 500 条确定性顺序结果。`truncated=true` 表示存在未公开项，客户端应修复当前批次后重新校验，而不是依赖无限错误列表。

## 4. Secret-free 资源与 RBAC 快照

配置阶段在独立只读事务的第一步调用 `public.app_set_tenant_context`，依赖 connector、secret_reference、membership、role 和 role_binding 的 FORCE RLS。仓储只选择 `id`、`tenant_id`、`project_id`、`normalized_name`、`status`、`deleted_at`；禁止选择 endpoint、config、ciphertext、external_key 或 Provider Secret 材料。

身份只来自可信 `WorkflowRequestContext.actorId` 与 `membership.principal_subject` 的匹配。只有 ACTIVE membership、ACTIVE 且未撤销的 role_binding、ACTIVE role，以及 TENANT 或当前 PROJECT scope 生效。稳定权限为 `connector:use` 和 `secret:use`，加载后扩展成 `ResourceUseGrant(kind, resourceId)`；资源可见但无 grant 时产生 `*_USE_FORBIDDEN`，生命周期不可用时产生 `*_UNAVAILABLE`，跨租户资源则由 RLS 完全隐藏。

## 5. HTTP 与并发语义

- 合法或业务校验失败均返回 HTTP 200；用 `valid` 区分。
- 工作流不存在或被 RLS 隐藏时返回统一 404 Problem Details。
- 缺失可信请求上下文返回 401；缺失或非法 `Idempotency-Key` 返回标准字段校验 Problem Details。
- `{}` 校验从仓储读取到的草稿快照，并把 revision/checksum 回显；并发更新不会改变该响应指向的快照，但调用方不能把它用于跳过发布时复验。

## 6. 验证与已知边界

Java 单元和 MVC 测试覆盖两种来源、四阶段错误、名称漂移、节点/字段映射、500 条截断、header/可信上下文和阶段短路。PostgreSQL 18.4 Testcontainers 覆盖元数据装配、TENANT/PROJECT scope、ACTIVE/REVOKED binding、显式 grant、Secret 材料隔离与跨租户 RLS。

机器与反向门禁：

```powershell
pnpm definition:g3-07:verify
pnpm definition:g3-07:test
```

G3-08 仍需实现发布事务、不可变版本、发布事件与发布时复验；G6 仍需实现 OIDC 上下文产生器和管理类 RBAC API。本步骤不声明这些能力已完成。
