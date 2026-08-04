# G3-08 工作流 Publish API、不可变版本与事务发布验收证据

## 1. 任务

- 任务：G3-08 实现 Publish API、不可变版本、版本号、checksum 与发布审计
- Requirement：`REQ-DSL-001`、`REQ-DEF-001`、`REQ-REL-001`、`REQ-AUD-001`
- 完成日期：2026-07-31
- 状态：`[x]`

## 2. 开发前审计与优化

在把草稿发布为运行时可引用版本前，审计并补齐十个关键边界：

1. 发布不能信任历史 Validate 响应；事务内锁定草稿后必须重跑 parse、graph、resource/configuration、expression 四阶段校验；
2. 请求必须携带 `expectedDraftRevision`，锁后不一致立即冲突，阻止校验与持久化之间的并发覆盖；
3. `Idempotency-Key` 为必需请求头，键与 actor、workflow、revision、release note 的请求指纹绑定，终态在 24 小时内安全重放；
4. 同一 workflow 的版本号必须从 1 开始严格单调，分配过程与草稿锁处于同一事务；
5. checksum 只允许来自 G3-02 canonical DSL UTF-8 的 SHA-256，不能对原始 JSON 文本或数据库序列化偶然形状求摘要；
6. `workflow_version` 与所属 `trigger_config` 一同生成不可变快照，复用 V2 数据库 immutable trigger，不新增重复迁移；
7. 同一 checksum 不允许借不同幂等键重复发布，避免生成语义相同的冗余版本；
8. 版本、触发器、草稿 ACTIVE 状态、审计、Outbox 与幂等终态必须同事务提交，任一步失败整笔回滚；
9. 发布审计使用追加式 `workflow.version.publish`，Outbox 使用 `workflow.version.published`，payload 只含公开标识、版本和 checksum，不包含 DSL、Secret 或连接配置；
10. transaction-local tenant context 是事务第一条业务 SQL，并继续依赖 FORCE RLS；跨租户目标对调用方表现为 404，不能泄露存在性。

## 3. 实现结果

### 3.1 Publish API 与并发前置条件

- 实现 `POST /api/v1/workflows/{workflowId}/publish`，OpenAPI 与兼容基线从 planned 切换为 implemented；
- 必需 `Idempotency-Key` 和正文 `expectedDraftRevision`；可选 `releaseNote` 若提供则必须非空且不超过 1000 字符；
- 服务在锁定草稿后核对 revision，陈旧 revision 返回统一冲突 Problem Details；
- 校验失败返回稳定的 `WORKFLOW_PUBLICATION_VALIDATION_FAILED`，不产生版本、触发器、审计、事件或幂等成功结果。

### 3.2 锁内复验与不可变快照

- 同一事务执行 `tenant context -> idempotency claim -> SELECT ... FOR UPDATE -> revision check -> four-stage validation`；
- 复用 G3-02～G3-05 的 canonical parser、图校验、Secret-free 资源授权快照和表达式编译，不读取或接受历史 Validate 票据；
- 从 canonical DSL 生成 checksum，写入 `workflow_version.definition_snapshot` 和单调 `version_number`；
- 将当前草稿触发器写入关联 `trigger_config` 快照，发布完成后更新 definition 为 `ACTIVE`；
- V2 已有的 update/delete trigger 同时保护 `workflow_version` 与版本触发器，数据库层阻断发布后篡改。

### 3.3 幂等、审计与 Outbox 原子性

- 共用 `JdbcIdempotencyRecords` 统一 G3-06/G3-08 的 24 小时 claim、终态完成和过期键复用语义；
- 同键同请求返回原 `versionId/versionNumber/checksum/publishedAt`，同键不同请求返回幂等冲突；
- 不同幂等键对同一 checksum 的重复发布返回发布冲突；并发发布同一 revision 只有一个事务成功；
- 追加 `workflow.version.publish` 审计，记录 tenant/project/workflow/version/checksum/actor 与 release note；
- 同事务写入 `workflow.version.published` Outbox，聚合键绑定 workflow version；任何 JDBC、校验或并发异常均回滚全部发布副作用。

## 4. 关键文件

- `api/openapi/workflow-platform.v1.openapi.json`
- `config/definition/publish-api.json`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowPublicationController.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowPublicationService.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowPublicationStore.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/internal/JdbcWorkflowPublicationStore.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/internal/JdbcIdempotencyRecords.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowPublicationControllerTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowPublicationServiceTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/internal/JdbcWorkflowDraftStoreIT.java`
- `scripts/definition/verify-workflow-publication-api.mjs`
- `scripts/definition/test-workflow-publication-api-verifier.mjs`
- `docs/architecture/workflow-publication-api.md`

## 5. 自动化验收

### 5.1 G3-08 机器契约

```text
G3-08 Publish API verification PASS: locked in-transaction revalidation, monotonic immutable versions, trigger snapshots, 24h idempotency, append-only audit and transactional Outbox.
G3-08 verifier negative tests PASS: 15/15 mutations rejected.
```

反向变异覆盖缺失锁内复验、错误事务顺序、revision/header/release note 契约、非 canonical checksum、可变版本、触发器快照、版本号、幂等重放、重复 checksum、审计、Outbox、OpenAPI/兼容基线和根脚本漂移；公开 OpenAPI 兼容性与需求追踪矩阵正反门禁同时通过。

### 5.2 Maven 五模块与 PostgreSQL 18.4

Java 21.0.12 / Maven 3.9.16 离线 `clean verify` 最终结果：

- shared-contracts：16 项单元测试；
- control-plane：122 项单元测试 + 12 项 Testcontainers 集成测试；
- worker：1 项单元测试；
- test-support：2 项单元测试；
- 合计 153 项，0 失败、0 错误、0 跳过。

G3-08 Controller/Service 指定测试 13 项通过；PostgreSQL 发布场景 8/8 通过，证明并发发布仅一方成功、版本号严格 `1 -> 2`、审计/Outbox 原子写入、非法草稿全回滚和 FORCE RLS 跨租户隐藏。Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 全绿；control-plane 指令/分支/行覆盖率分别为 87.91%/70.91%/86.85%。

### 5.3 pnpm、前端与 Secret 门禁

- `pnpm definition:g3-08:verify/test` 正向与 15/15 反向门禁通过；
- 桌面 Git 工作树完整 `pnpm verify` 通过，覆盖全部历史架构、领域、公共契约、DSL、定义、数据库、治理、追踪、配置、安全与前端门禁；
- Web Console ESLint、TypeScript typecheck、3 项 Vitest 与 Vite production build 通过；
- Gitleaks 固定 digest 镜像扫描 433 个 Git commit candidates 无泄漏，合成硬编码 Secret 被项目规则正确阻断；
- 初次扫描命中的 4 个测试 `Idempotency-Key` 为高熵误报，已改为低熵语义占位符；未增加 allowlist、未放宽规则，修改后重新通过完整 Maven 门禁。

### 5.4 桌面交付

- 使用非镜像 Robocopy 增量同步，排除 `.git/.env/node_modules/target/dist`，不删除桌面独有文件；
- 桌面既有 `.git`、依赖目录和 4 个独有规划文件保留；
- 最终同步后对开发源和桌面所有应交付共享文件逐项 SHA-256，结果为 0 缺失、0 不一致；
- 权威总控清单、桌面根目录副本与桌面 `docs/planning` 副本 SHA-256 完全一致。

## 6. 遗留边界

- G3-09 继续负责克隆、导入导出与版本差异；G3-10 扩展兼容性、恶意表达式和并发更新组合测试；
- 发布 Outbox 已事务落库，Kafka relay、消费幂等、重试/死信和端到端执行属于 G4/G5；
- OIDC 上下文产生器、管理类 RBAC、Provider/KMS/轮换/SSRF/Secret 使用审计仍由 G6 完成；
- Flyway 11.7.2 在 PostgreSQL 18.4 实测通过，但高于官方最高测试版本 17 的兼容性提示继续治理；
- 当前发布版本可被后续运行时读取，但这不等同于工作流执行引擎、调度器或 Worker 已完成。

## 7. 结论

G3-08 已满足总控清单：合法草稿可在锁定事务内复验并发布为带单调版本号、canonical checksum 和触发器快照的不可变版本；并发覆盖、重复发布、非法或越权引用均被稳定拒绝；版本、状态、追加审计、Outbox 与幂等终态具备事务原子性，并由单元、MVC、真实 PostgreSQL、正反机器契约、完整 Maven/pnpm、Secret 扫描和桌面哈希验收共同证明。下一步执行 G3-09。
