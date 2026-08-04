# G3-09 工作流克隆、导入导出与版本差异验收证据

## 1. 任务

- 任务：G3-09 实现克隆、导入导出和版本差异的后端能力
- Requirement：`REQ-DEF-001`
- 完成日期：2026-07-31
- 状态：`[x]`

## 2. 开发前审计与优化

在实现定义可移植能力前，审计并补齐十个边界：

1. Clone/Export 必须显式区分当前草稿与不可变发布版本，不能把草稿 revision 和发布 version 混为同一选择器；
2. 导出格式必须版本化并带 canonical DSL SHA-256，Import 必须在任何写入前复算完整性；
3. bundle 只允许公开的 `kind/reference` 资源清单，禁止 tenant/project、endpoint、连接配置、ciphertext、external key、key version 或 Secret material；
4. Import 必须完整显式映射全部 connector/secret 引用，跨项目 Clone 同样不得隐式保留源项目引用；
5. 同项目 Clone 可以默认保留引用，但若提供映射必须校验 kind、来源、重复项和目标引用；
6. Clone/Import 只创建 revision 1 的 DRAFT，可复制尚未完全合法的草稿，但后续 Publish 仍必须重新执行四阶段校验；
7. 草稿创建、24 小时幂等终态和来源审计必须同事务提交，名称冲突或任何异常都要整笔回滚；
8. 差异只比较不可变发布版本，路径使用 RFC 6901 JSON Pointer，对象键确定性排序、数组按索引比较；
9. 差异响应必须限制为 1000 项和单值 4096-byte preview，超限只返回 type/checksum/truncated 摘要，避免大对象放大；
10. portable bundle 需要独立 HTTP 文档上限；全局 Jackson 上限提升至 2,200,000 字符，但 DSL parser 自身仍保持 1 MiB 原始输入边界。

质量门禁还发现并修复了三类设计债：机器校验器按真实责任边界检查审计动作；追踪矩阵按逐文件证据解析；导入来源由伪造 `WorkflowSnapshot` 改为独立来源元数据并显式校验 selector，同时为 bundle 和请求集合增加防御性复制。

## 3. 实现结果

### 3.1 四个公开 API

- `POST /api/v1/workflows/{workflowId}/clone`：支持草稿或指定发布版本来源，跨项目强制完整资源映射；
- `GET /api/v1/workflows/{workflowId}/export`：导出当前草稿或指定发布版本，并返回 `Cache-Control: no-store`；
- `POST /api/v1/workflows/import`：验证格式版本、来源 selector、canonical checksum、资源清单和完整显式映射后创建草稿；
- `GET /api/v1/workflows/{workflowId}/versions/diff`：比较两个不可变发布版本并返回有界确定性差异。

OpenAPI 现有 19 个 operation、37 个 component schema，兼容基线冻结 12 个 implemented schema；未新增 Flyway 迁移，复用 V2/V4/V5/V6 表、RLS、幂等和审计能力。

### 3.2 可移植 bundle 与资源安全

- JSON bundle 固定为 `workflow-platform-definition` format version 1；
- source 记录 workflowId、`draft`/`published_version` selector、checksum 和 capturedAt，selector 组合显式互斥；
- workflow definition 必须通过 G3-02 canonical parser，导入时名称与 checksum 都必须和 bundle 一致；
- manifest 从 DSL 中确定性提取 connector/secret 引用并严格比对，拒绝缺失、额外、重复、非法 kind/reference 和 Secret/config 材料；
- Import 与跨项目 Clone 在重写后再次 canonical parse，目标 checksum 只来自规范化 DSL。

### 3.3 原子幂等、审计与租户隔离

- Clone/Import 使用独立 operation scope 和请求摘要，`Idempotency-Key` 在 24 小时内同键同请求安全返回原草稿；
- 事务顺序为 tenant context、幂等 claim、目标项目校验、草稿写入、追加来源审计、幂等完成；
- 审计动作固定为 `workflow.definition.clone` 和 `workflow.definition.import`，详情只含来源/目标标识、selector 与 checksum；
- PostgreSQL FORCE RLS 隐藏跨租户草稿和发布版本；目标名称冲突时草稿、审计和幂等记录全部回滚。

### 3.4 有界确定性版本差异

- 只接受正整数 `fromVersion/toVersion` 并从不可变 `workflow_version` 加载；
- 对象 key 使用排序集合，数组按索引递归，路径执行 RFC 6901 `~0`/`~1` 转义；
- 最多公开 1000 个 add/remove/replace change，单值 UTF-8 preview 最多 4096 bytes；
- 超限时保留类型、SHA-256 和 truncated 标志，响应本身使用 `Cache-Control: no-store`。

## 4. 关键文件

- `api/openapi/workflow-platform.v1.openapi.json`
- `config/definition/portability-api.json`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowPortabilityController.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowPortabilityService.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowPortabilityStore.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowExportBundle.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowJsonDiff.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowResourceReferences.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/internal/JdbcWorkflowDraftStore.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowPortabilityServiceTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowPortabilityControllerTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/internal/JdbcWorkflowDraftStoreIT.java`
- `scripts/definition/verify-workflow-portability-api.mjs`
- `scripts/definition/test-workflow-portability-api-verifier.mjs`
- `docs/architecture/workflow-portability-and-version-diff-api.md`

## 5. 自动化验收

### 5.1 G3-09、公共契约与追踪门禁

```text
G3-09 portability verification PASS: 4 implemented operations, integrity-bound Secret-free bundle, explicit resource mapping, atomic audited idempotency and bounded deterministic version diff.
G3-09 verifier negative tests PASS: 15/15 mutations rejected.
Public contract verification PASS: operations=19, OpenAPI schemas=37, compatibilitySchemas=12.
Public contract verifier tests PASS: positive contract plus 23 reverse mutations.
Traceability verification PASS: requirements=36, controlTasks=123/123, mvpGates=10/10.
```

反向变异覆盖 bundle format/资源上限/显式映射/diff 上限/Secret 材料/HTTP 上限、OpenAPI implementation/header/schema/query、兼容基线、根脚本和 G3-09 追踪证据漂移。

### 5.2 Maven 五模块与 PostgreSQL 18.4

Java 21.0.12 / Maven 3.9.16 离线 `clean verify` 最终结果：

- shared-contracts：16 项单元测试；
- control-plane：131 项单元测试 + 14 项 Testcontainers 集成测试；
- worker：1 项单元测试；
- test-support：2 项单元测试；
- 合计 164 项，0 失败、0 错误、0 跳过。

G3-09 Service/Controller 定向测试 9/9 通过；`JdbcWorkflowDraftStoreIT` 10/10 通过，其中 portability 场景验证草稿/发布快照、Clone/Import 幂等重放、两类追加审计、FORCE RLS 跨租户隐藏，以及名称冲突时草稿/审计/幂等全部回滚。Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 全绿；control-plane 指令/分支/行覆盖率分别为 87.70%/68.95%/86.81%。

### 5.3 桌面完整根门禁

- 非删除式同步后在 `C:\Users\Administrator\Desktop\workflow-platform` 运行完整 `pnpm verify`，全部历史架构、领域、Problem Details、公共契约、DSL G3-01～G3-05、Definition G3-06～G3-09、数据库、治理、追踪、配置、安全和前端门禁通过；
- Web Console ESLint、TypeScript typecheck、3 项 Vitest 和 Vite production build 通过；
- Gitleaks 固定 digest 镜像扫描 448 个 Git commit candidates 无泄漏，合成硬编码 Secret 被规则正确阻断；
- 最终共享交付文件逐项 SHA-256 为 0 缺失、0 不一致；桌面 `.git`、依赖/构建缓存和 4 个独有规划文件保留。

## 6. 遗留边界

- G3-10 继续扩展解析、图算法、兼容性、恶意表达式和并发更新组合测试；
- bundle format v1 保证当前 DSL v1 的格式、完整性和资源引用边界，跨 major DSL/bundle migration 与 upcaster 仍需版本演进策略；
- Clone/Import 创建草稿不等同于资源已获授权或定义可发布，Publish 仍会重新执行四阶段校验；
- Kafka relay、消费幂等、重试/死信和端到端执行属于 G4/G5，OIDC/RBAC、Provider/KMS/轮换/SSRF/Secret 使用审计属于 G6；
- Flyway 11.7.2 在 PostgreSQL 18.4 实测通过，但高于官方最高测试版本 17 的兼容性提示继续治理。

## 7. 结论

G3-09 已满足总控清单：草稿和不可变发布版本可安全克隆/导出，版本化 bundle 可在完整性、显式资源映射和 Secret-free 边界内导入，发布版本可生成有界确定性差异；Clone/Import 的草稿、追加审计和幂等终态具备事务原子性并受 FORCE RLS 保护。实现由正反机器契约、单元/MVC、真实 PostgreSQL 18.4、五模块 Java 质量门禁、桌面完整 `pnpm verify`、Secret 扫描和逐文件哈希共同证明。下一步执行 G3-10。
