# 工作流草稿 API、强 ETag 与租户并发边界

## 1. 已实现范围

G3-06 实现 `POST /api/v1/workflows`、`GET /api/v1/workflows/{workflowId}` 和 `PUT /api/v1/workflows/{workflowId}/draft`。创建显式写入 `draft_revision=1`，读取和写入返回 `WorkflowDetail` 与强 `ETag`；Validate、Publish、克隆、导入导出和版本差异仍由 G3-07～G3-09 完成。

公开请求中的 `definition` 复用 G3-02 的有界 JSON 解析、canonical JSON 与 SHA-256。MVC 在物化 `JsonNode` 前同样启用重复键检测、64 层深度、Token/数字/字符串/字段名和整份 HTTP JSON 上限，避免框架先吞掉重复键或先无界分配。草稿保存不提前执行 G3-07 的完整图/资源/表达式发布校验，但拒绝非法核心模型、超限内容和资源名与 DSL 名称漂移。

## 2. ETag 与 If-Match

ETag 是带双引号的强实体标签，绑定 `workflowId + draftRevision + draftChecksum`。更新只接受服务端最近一次读取或写入返回的单个强标签；弱标签、`*`、列表、畸形值、其他工作流标签和旧修订全部拒绝。

- 缺少 `If-Match`：HTTP 428，`DEFINITION_PRECONDITION_REQUIRED_IF_MATCH`。
- 标签非法、跨工作流或已过期：HTTP 412，`DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH`。
- SQL 更新同时比较 `id`、`draft_revision` 和 `draft_checksum`，并在一个原子语句中递增 revision/version、替换 DSL/checksum 和更新审计字段。

## 3. 幂等与事务

创建和更新都要求 8～128 个 URI unreserved 字符组成的 `Idempotency-Key`，与领域传输上限和 OpenAPI 保持一致。服务只保存 key 和请求的 SHA-256，不保存原始 header 或 Payload。`idempotency_record` 的声明、业务写入和完成状态在同一数据库事务中；默认 TTL 为 24 小时。同 scope/key 但请求摘要不同返回 409。

同一 replay window 内只允许一次 `PROCESSING→COMPLETED/FAILED`。过期后，仓储先 `FOR UPDATE` 锁定原记录，V6 guard 才允许在保持 tenant/scope/key/创建证据不变、version 恰好递增、清空旧响应且设置未来 expiry 的条件下开启下一窗口；未过期终态、无锁覆盖或身份漂移仍由数据库拒绝。这样 TTL 是可执行契约，而不是永久占用键的装饰字段。

完成结果使用资源引用与 revision/checksum 响应摘要验证后重放。如果资源随后又被修改，旧幂等键不会伪造旧成功响应，而是返回冲突；这避免在没有对象存储响应快照时错误声称“精确历史响应回放”。

## 4. 租户与身份边界

控制器不读取 `X-Tenant-Id` 或其他用户可控租户 header。它只接受未来 G6 OIDC 过滤器写入的可信 `WorkflowRequestContext` request attribute；缺失上下文返回 401。

每个仓储事务在任何业务查询前调用 `public.app_set_tenant_context`，然后依赖数据库 FORCE RLS。跨租户读取和更新统一表现为 404，避免资源存在性泄漏。`created_by`/`updated_by` 只来自同一可信上下文。

## 5. 数据迁移与验证

V6 向 `workflow_definition` 追加可空 `description varchar(1000)`，并收紧 `idempotency_record` 的过期窗口重置 guard；V1～V5 未改写。真实 PostgreSQL 18.4 Testcontainers 使用迁移、应用和平台角色，验证 V1→V6、RLS、描述持久化、创建/更新幂等重放、过期键安全复用、跨租户不可见，以及两个连接持有同一 ETag 时恰好一个成功。

静态与反向门禁：

```powershell
pnpm definition:g3-06:verify
pnpm definition:g3-06:test
```

完整 Java 验收仍使用 Maven `verify`，并保留 Flyway 对 PostgreSQL 18.4 高于其官方最高测试版本 17 的兼容风险提示。
