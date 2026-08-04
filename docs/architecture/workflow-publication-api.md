# 工作流发布 API 与不可变版本事务

## 1. 范围

G3-08 实现 `POST /api/v1/workflows/{workflowId}/publish`。请求必须携带 `Idempotency-Key`，并用 `expectedDraftRevision` 指明调用方准备发布的草稿修订；可选 `releaseNote` 最长 1000 字符。成功返回 HTTP 201，以及 `workflowId`、从 1 开始的 `version`、规范 DSL 的 SHA-256 `checksum` 和 `publishedAt`。

Validate API 只提供诊断，不产生发布凭证。Publish 必须在自己的数据库事务中锁定目标 `workflow_definition` 行，并对锁定快照重新执行 `parse → graph → resource-and-configuration → expression` 四阶段校验。草稿 revision 不匹配、校验失败、相同 checksum 被不同幂等键重复发布均返回稳定的 409 状态冲突。

## 2. 单事务顺序

1. 首条数据库语句设置 transaction-local tenant context；
2. 领取 24 小时幂等记录，键空间按 workflow 隔离，请求摘要绑定 workflowId、expectedDraftRevision 和 releaseNote；
3. 以 `FOR UPDATE` 锁定草稿，并核对 revision；
4. 在同一事务中重新加载 Secret-free 资源/授权上下文并运行四阶段校验；
5. 计算当前最大版本号加一，写入不可变 `workflow_version` 和不可变 `trigger_config` 快照；
6. 将定义状态设为 ACTIVE；
7. 追加 `workflow.version.publish` 审计，releaseNote 进入审计 reason；
8. 追加 `workflow.version.published` transactional Outbox 事件；
9. 最后完成幂等记录并提交。

任一步失败都会回滚版本、触发器、状态、审计、Outbox 和幂等领取，不留下半发布状态。并发发布由定义行锁串行化；数据库唯一约束保证 `(tenant_id, definition_id, version_no)` 与 checksum 不重复。

## 3. 不可变性与数据最小化

`workflow_version` 和 `trigger_config` 已由 V2 数据库触发器禁止 UPDATE/DELETE，历史实例始终绑定原始版本快照。发布审计和 Outbox 只记录 workflow/version 标识、revision、checksum 和版本号，不复制连接器配置、Secret 密文或外部密钥路径。资源校验仍只读取 G3-07 的六个元数据字段和显式 `connector:use`、`secret:use` grant。

事件信封使用 schemaVersion 1、producer `control_plane`，payload 固定包含 `workflowId`、`workflowVersionId`、`version`、`checksum`、`draftRevision`，并持久化 payload digest 和 trace context。审计表继续由追加式触发器与 FORCE RLS 保护。

## 4. 失败与重放语义

- 缺少/非法幂等键、revision 或 releaseNote：400；
- 工作流不存在或被 FORCE RLS 隐藏：404；
- revision 过期、当前草稿校验失败、同一 key 对应不同请求、不同 key 重复发布同一 checksum：409；
- 相同幂等键和相同请求在 24 小时内重放：返回第一次提交的同一不可变版本；若版本或响应摘要无法核验则安全返回冲突。

发布成功后草稿仍可继续编辑；下一次发布必须带新的 revision，并产生下一个单调版本号。克隆、导入导出和版本差异继续由 G3-09 实现。
