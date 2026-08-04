# G3-07 工作流 Validate API、精确错误定位与授权快照验收证据

## 1. 任务

- 任务：G3-07 实现 Validate API，错误精确定位到节点和字段
- Requirement：`REQ-DSL-001`、`REQ-DEF-001`
- 完成日期：2026-07-31
- 状态：`[x]`

## 2. 开发前审计与优化

在把 G3-03～G3-05 接入 HTTP API 前，审计并修正八个关键缺口：

1. 原 OpenAPI 只有 `valid/issues`，无法说明校验来源和草稿并发版本；响应增加 `source`、当前草稿 `draftRevision`、解析成功后的 canonical `checksum`；
2. 图、配置和表达式失败已有内部 JSON Pointer，但缺少稳定公开映射；建立 40 个大写错误码、固定脱敏 message、字段 `path` 与安全可解析的 `nodeId`；
3. 大型非法定义可能产生无界响应；返回 `issueCount`、`truncated` 并把公开列表限制为 500 条；
4. 固定执行 `parse -> graph -> resource-and-configuration -> expression`，每阶段失败后确定性短路，避免用不完整前置结果继续校验；
5. `{}` 校验当前草稿，携带 `definition` 校验临时定义；临时定义名称必须与目标 workflow 一致；
6. 当前草稿解析失败属于持久化完整性异常，临时定义解析失败则是 HTTP 200、`valid=false` 的正常校验结果；
7. G3-04 只有内存资源模型，缺少数据库装配；新增 transaction-local tenant context、FORCE RLS、ACTIVE membership/role/binding 与 TENANT/PROJECT scope 的显式 grant 装配；
8. 资源快照只读取 `id/tenant_id/project_id/normalized_name/status/deleted_at`，禁止选择 endpoint/config/ciphertext/external_key；Validate 是只读 POST，只校验 `Idempotency-Key`，不写幂等表，也不作为 G3-08 发布凭证。

## 3. 实现结果

### 3.1 API 与结果绑定

- 实现 `POST /api/v1/workflows/{workflowId}/validate`，OpenAPI 与兼容基线均标记 `implemented`；
- `{}` 返回 `source=current_draft` 和读取快照的 revision/checksum；
- `definition` 返回 `source=supplied_definition` 和该临时定义的 canonical checksum；
- 业务校验失败保持 HTTP 200，工作流不存在或被 RLS 隐藏时返回统一 404 Problem Details；
- 缺失可信请求上下文返回 401，缺失/非法 `Idempotency-Key` 使用统一字段校验 Problem Details。

### 3.2 四阶段确定性短路

- Parse：复用 G3-02 有界 JSON 解析、canonical JSON 和 SHA-256；
- Graph：复用 G3-03 的 16 类图不变量；
- Resource/Configuration：复用 G3-04 的 9 类节点/触发器/资源授权校验；
- Expression：复用 G3-05 的 5 类受限 CEL/JSONPath 编译；
- 名称漂移在图校验前返回 `/name`；任一阶段失败后不调用依赖阶段。

### 3.3 精确定位与有界公开错误

- 公开目录为 9 个 `DSL_PARSE_*`、1 个 `DSL_DEFINITION_NAME_MISMATCH`、16 个 `DSL_GRAPH_*`、9 个 `DSL_CONFIGURATION_*`、5 个 `DSL_EXPRESSION_*`；
- `/nodes/{index}/...` 映射节点 ID，`/edges/{index}/from|to` 只在端点明确时映射；不明确的边路径不猜测；
- response message 固定且脱敏，severity 仅为 `error`；
- 总错误数通过 `issueCount` 保留，最多返回前 500 条，超限时 `truncated=true`。

### 3.4 Secret-free RBAC/RLS 上下文

- JDBC 事务第一步调用 `SELECT public.app_set_tenant_context(?)`；
- connector/secret_reference 查询只投影六个允许元数据字段；
- `actorId` 只与 `membership.principal_subject` 匹配；仅 ACTIVE、未撤销的 membership/role_binding 和 ACTIVE role 生效；
- 仅 TENANT 或当前 PROJECT scope 生效，稳定权限 `connector:use`、`secret:use` 扩展为 `ResourceUseGrant(kind, resourceId)`；
- 无权限的可见资源产生 `*_USE_FORBIDDEN`，跨租户资源由 FORCE RLS 隐藏，Secret 材料从未进入 Java 快照。

## 4. 关键文件

- `api/openapi/workflow-platform.v1.openapi.json`
- `config/definition/validate-api.json`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowValidationController.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowValidationService.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowValidationIssueMapper.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/internal/JdbcWorkflowValidationContextStore.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowValidationServiceTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowValidationControllerTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/internal/JdbcWorkflowDraftStoreIT.java`
- `scripts/definition/verify-workflow-validation-api.mjs`
- `scripts/definition/test-workflow-validation-api-verifier.mjs`
- `docs/architecture/workflow-validation-api.md`

## 5. 自动化验收

### 5.1 G3-07 机器契约

```text
G3-07 Validate API verification PASS: 4-stage short circuit, 40 stable issue codes, 500 issue cap, revision/checksum binding, secret-free RBAC/RLS context, Publish remains planned.
G3-07 verifier negative tests PASS: 15/15 mutations rejected.
```

反向变异覆盖流水线顺序、错误上限、发布凭证误用、Secret 字段选择、权限码、源码绑定、OpenAPI 状态/header/404/错误码/severity/required fields、Publish 提前实现、兼容基线和根脚本漂移。

### 5.2 Maven 五模块与 PostgreSQL 18.4

Java 21 / Maven 3.9.16 离线 `clean verify` 最终结果：

- shared-contracts：16 项单元测试；
- control-plane：114 项单元测试 + 10 项 Testcontainers 集成测试；
- worker：1 项单元测试；
- test-support：2 项单元测试；
- 合计 143 项，0 失败、0 错误、0 跳过。

G3-07 新增 5 项服务测试、3 项 MVC 测试和 2 项真实 PostgreSQL 资源上下文测试；与 G3-06 PostgreSQL 并发/RLS 套件共同运行。Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 全绿；control-plane 指令覆盖率 87.45%，分支覆盖率 71.25%。

### 5.3 pnpm、前端与 Secret 门禁

- `pnpm definition:g3-07:verify/test` 正向和 15/15 反向门禁通过；
- 桌面 Git 工作树完整 `pnpm verify` 通过，覆盖全部历史架构/领域/契约/DSL/定义/数据库/治理/追踪/配置/安全和前端门禁；
- Web Console ESLint、TypeScript typecheck、3 项 Vitest 与 Vite production build 通过；
- Gitleaks 固定 digest 镜像扫描 417 个 Git commit candidates 无泄漏，合成硬编码 Secret 被项目规则正确阻断；
- 初次 Secret 扫描发现的是测试标识符误报，通过语义等价重命名消除，未增加 allowlist 或放宽规则。

### 5.4 桌面交付

- 使用非镜像 Robocopy 增量同步，排除 `.git/.env/node_modules/target/dist`，不删除桌面独有文件；
- 桌面既有 `.git` 与依赖目录保留；
- 最终同步后对开发源和桌面所有应交付共享文件逐项 SHA-256，要求 0 缺失、0 不一致；
- 桌面完整根门禁在真实 Git 候选集上独立通过。

## 6. 遗留边界

- G3-08 Publish 必须在事务中重新读取 revision/checksum 并重跑校验；Validate 响应不能跳过发布复验；
- G3-08/G3-09 继续负责不可变版本、版本号、发布审计/事件、克隆、导入导出和差异；
- OIDC `WorkflowRequestContext` 产生器、管理类 RBAC、Provider/KMS/轮换/SSRF/Secret 使用审计仍由 G6 完成；
- Flyway 11.7.2 在 PostgreSQL 18.4 实测通过，但高于官方最高测试版本 17 的兼容性提示继续治理。

## 7. 结论

G3-07 已满足总控清单：Validate API 对当前或临时定义执行确定性四阶段校验，结果绑定来源/revision/checksum，错误稳定定位到节点和字段，资源授权上下文具备 Secret-free 元数据、显式 grant 与数据库强制租户隔离，并由单元、MVC、真实 PostgreSQL、机器反向、完整 Maven/pnpm 和桌面安全门禁共同证明。下一步执行 G3-08。
