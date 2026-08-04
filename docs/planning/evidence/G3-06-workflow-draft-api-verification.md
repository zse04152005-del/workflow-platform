# G3-06 工作流草稿 API、强 ETag 与并发控制验收证据

## 1. 任务

- 任务：G3-06 实现草稿创建、更新、读取，使用 ETag/If-Match 防并发覆盖
- Requirement：`REQ-DEF-001`
- 完成日期：2026-07-31
- 状态：`[x]`

## 2. 开发前审计与优化

在 G3-01～G3-05 的 DSL 编译能力上接入 API 前，审计确认并补齐九个关键缺口：

1. 草稿更新不能只依赖应用内 revision 比较，必须把 `workflowId + revision + checksum` 绑定为强 ETag，并在 SQL 原子更新条件中复核；
2. `If-Match` 必须拒绝缺失、弱标签、通配符、多标签、畸形、跨工作流和陈旧标签，分别稳定映射 428/412 RFC 9457 响应；
3. 创建和更新都是可重试写操作，需用事务内幂等记录避免网络重试重复提交，同时只持久化 key/request SHA-256；
4. 24 小时 TTL 不能只是装饰字段；过期终态要在持有 `FOR UPDATE` 锁时安全复用同一行，并由数据库 guard 阻断身份漂移、无锁重置和旧响应残留；
5. 租户 ID 不能来自客户端 header，必须使用可信 request attribute，并在每个事务的第一条业务查询前建立 transaction-local tenant context；
6. 跨租户资源访问统一表现为 404，不能泄漏资源存在性；
7. HTTP JSON 在 MVC 物化前也必须受重复键、深度、Token、数字、字符串、字段名和总字节限制，不能绕过 G3-02 解析边界；
8. 工作流名称唯一冲突必须按 PostgreSQL SQLSTATE `23505` 映射 409，不能依赖本地化错误文本；
9. Testcontainers 集成类需要串行 fork，避免多个 Failsafe JVM 同时初始化本机 Docker 导致偶发探测失败。

本次同时统一 `Idempotency-Key` 的领域、OpenAPI 和服务端最大长度为 128；引入 UUIDv7；将 V6 迁移摘要固定为 `e8f3e77cbf05969113ce19a15e39cc107987956c61e0563b742321934d4d95dd`。

## 3. 实现结果

### 3.1 API 与边界

- `POST /api/v1/workflows`：创建 revision 1 草稿，返回 201、`Location` 和强 `ETag`；
- `GET /api/v1/workflows/{workflowId}`：租户隔离读取草稿，返回 200 和当前强 `ETag`；
- `PUT /api/v1/workflows/{workflowId}/draft`：要求 `Idempotency-Key` 与单个强 `If-Match`，成功后 revision 原子递增；
- Validate、Publish、克隆、导入导出和版本差异仍保持 planned，分别由 G3-07～G3-09 完成。

### 3.2 强 ETag 与并发

- 实体标签绑定 `workflowId + draftRevision + draftChecksum`，使用带引号的强标签；
- 缺少 `If-Match` 返回 428 `DEFINITION_PRECONDITION_REQUIRED_IF_MATCH`；
- 弱/通配/列表/畸形/跨工作流/陈旧标签返回 412 `DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH`；
- SQL 同时比较 `id`、`draft_revision`、`draft_checksum`，并原子替换 DSL/checksum、递增 revision/version、更新审计字段；
- 真实 PostgreSQL 双连接使用同一 ETag 并发更新时恰好一个成功，另一个稳定失败。

### 3.3 幂等、RLS 与数据库不变量

- 幂等键长度 8～128，仅允许 URI unreserved 字符；数据库只保存 key/request SHA-256；
- 同 scope/key 同请求在 24 小时窗口内重放，同键异请求返回 409；
- 过期窗口只有在行锁、身份字段不变、version 恰好加一、旧响应清空且新 expiry 在未来时才可重置；
- 创建/更新、幂等声明与完成状态处于同一事务；
- 控制器只接受可信 `WorkflowRequestContext`，仓储在事务内调用 `public.app_set_tenant_context` 并依赖 FORCE RLS；
- V6 增加 `workflow_definition.description`，V1～V5 未改写，V1→V6 与重复迁移均通过。

## 4. 关键文件

- `api/openapi/workflow-platform.v1.openapi.json`
- `config/definition/draft-api.json`
- `control-plane/src/main/resources/db/migration/V6__add_workflow_draft_api_fields.sql`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowDraftController.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowDraftService.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/StrongDraftEtag.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/internal/JdbcWorkflowDraftStore.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/security/WorkflowRequestContext.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowDraftControllerTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowDraftServiceTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/internal/JdbcWorkflowDraftStoreIT.java`
- `scripts/definition/verify-workflow-draft-api.mjs`
- `scripts/definition/test-workflow-draft-api-verifier.mjs`
- `docs/architecture/workflow-draft-api.md`

## 5. 自动化验收

### 5.1 G3-06 机器契约

```text
G3-06 workflow draft API verification PASS: operations=3, strong ETag=412/428, idempotent writes=24h, tenant context=trusted attribute + forced RLS, PostgreSQL concurrency test bound.
G3-06 verifier negative tests PASS: 12/12 mutations rejected.
```

12 类反向变异覆盖 operation 状态、428/412、弱 ETag 拒绝、幂等 TTL/长度、可信租户上下文、FORCE RLS、V6 迁移、并发测试绑定和根门禁漂移。

### 5.2 Maven 五模块与 PostgreSQL 18.4

Java 21 / Maven 3.9.16 离线 `clean verify` 结果：

- shared-contracts：16 项单元测试；
- control-plane：106 项单元测试 + 8 项 PostgreSQL Testcontainers 集成测试；
- worker：1 项单元测试；
- test-support：2 项单元测试；
- 合计：133 项，0 失败、0 错误、0 跳过。

G3-06 直接相关单元测试 22 项；`JdbcWorkflowDraftStoreIT` 4 项覆盖创建/读取/更新、同键重放、过期键复用、跨租户 404 与双连接 ETag 竞争；`DatabaseMigrationIT` 4 项覆盖 V1→V6、重复迁移、Catalog/RLS 和关键查询计划。Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 全绿；control-plane 实测行覆盖率 86.18%，分支覆盖率 70.99%。

### 5.3 pnpm、前端与 Secret 门禁

- `pnpm definition:g3-06:verify` 与 `pnpm definition:g3-06:test` 通过；
- 完整 `pnpm verify` 覆盖架构、领域、RFC 9457、公共契约、G3-01～G3-06、ER/Flyway、治理、追踪、配置、安全和前端门禁；
- Web Console ESLint、TypeScript typecheck、3 项 Vitest 与 Vite production build 通过；
- Gitleaks 固定镜像在真实 Git 候选集上扫描，合成硬编码 Secret 负向控制继续被项目规则阻断；
- Flyway 清单的公开 SHA-256 仅通过路径与完整行格式同时匹配的窄豁免，责任人、原因和到期日已记录，没有放宽其他高熵值。

### 5.4 桌面交付复核

- 使用非镜像 Robocopy 增量同步，明确排除 `.git/.env/node_modules/target/dist`，未删除桌面独有文件；
- 开发源与桌面交付 399 个共享文件逐项 SHA-256：0 缺失、0 不一致；
- 权威总控清单、桌面根目录清单和 `docs/planning` 清单 SHA-256 均为 `B150F136FAA7010CC0670809CC07702B48131CF83F4558894C351F252DDFDBC7`；
- 桌面既有 `.git` 保留；桌面 `definition:g3-06:verify/test` 再次通过，12/12 反向变异全部拒绝；
- 桌面最终 403 个 Git 提交候选文件无 Secret，合成硬编码 Secret 被正确拦截。

## 6. 遗留边界

- G3-07 负责把 G3-03～G3-05 的图、资源授权和表达式编译接入 Validate API，并返回节点/字段精确错误；
- G3-08 负责 Publish API、不可变版本、版本号、checksum 和发布审计；G3-09 负责克隆、导入导出与差异；
- 旧幂等响应未保存完整历史 payload；资源被后续修改后，旧键会安全返回冲突，不虚报精确历史响应回放；
- 可信 `WorkflowRequestContext` 的 OIDC 产生器与细粒度 RBAC 仍由 G6 完成；当前只实现可接入边界和数据库强制租户隔离；
- Flyway 11.7.2 在 PostgreSQL 18.4 套件中实测通过，但仍高于其官方最高测试版本 17，生产升级前需继续执行兼容性治理。

## 7. 结论

G3-06 已满足总控清单：草稿创建、读取和更新具备强 ETag/If-Match 并发控制、事务幂等、可信租户上下文、FORCE RLS 与真实 PostgreSQL 并发证据；所有公开行为、错误码、迁移和测试均有机器绑定。下一步执行 G3-07。
