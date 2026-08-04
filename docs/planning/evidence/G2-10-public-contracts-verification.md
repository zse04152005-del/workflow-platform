# G2-10 OpenAPI、DSL 与事件公共契约验收证据

- 验收日期：2026-07-30
- 状态：PASS
- 兼容基线版本：`1.0.0`
- 机器事实源：`api/openapi/workflow-platform.v1.openapi.json`、`schemas/dsl/workflow-definition-v1.schema.json`、`schemas/events/event-envelope-v1.schema.json`

## 1. 交付清单

- OpenAPI 3.1：`api/openapi/workflow-platform.v1.openapi.json`
- DSL JSON Schema 2020-12：`schemas/dsl/workflow-definition-v1.schema.json`
- Event Envelope JSON Schema 2020-12：`schemas/events/event-envelope-v1.schema.json`
- 兼容机器基线：`config/contracts/compatibility-baseline.json`
- 正向门禁：`scripts/contracts/verify-public-contracts.mjs`
- 反向门禁：`scripts/contracts/test-public-contracts-verifier.mjs`
- DSL/Event fixtures：`tests/contracts/dsl/`、`tests/contracts/events/`
- 架构与演进说明：`docs/architecture/public-contracts-and-compatibility.md`

## 2. 评估发现与改进

| 原风险/痛点 | 本步处理 |
|---|---|
| 文档中的 API 路径只有表格，后续模块容易各自发明字段和错误 | 建立 OpenAPI 3.1 权威源，统一 15 个 MVP operation、27 个 component Schema 与 RFC 9457 引用 |
| 幂等、草稿并发和租户来源是文字约定，容易漏在单个接口 | 所有非 GET 强制 `Idempotency-Key`；draft PUT 强制 `If-Match`；所有请求 Schema 禁止客户端 `tenantId` |
| DSL 节点/触发器/表达式边界未机器化 | 冻结 6 类节点、3 类触发器、executorVersion、connectorRef、duration、timezone/misfire 与受限表达式形状 |
| 事件字段齐全但没有可执行兼容策略 | Envelope v1 锁定 7 个必需字段、UUIDv7/UTC/traceId，并允许未知可选字段供消费者向前兼容 |
| 只有正例会导致“门禁看起来通过但无法拦截漂移” | 增加 10 个正反 fixture 和 22 类主动破坏变异 |
| 删除字段、改类型、删 enum 或新增请求必填项容易被误当成小改 | 兼容基线逐项冻结 operationId、成功状态、必需头、代表性字段/类型/enum 与 Major/ADR/upcaster 规则 |

## 3. 量化验收

| 项目 | 实测 |
|---|---:|
| OpenAPI operation | 15 |
| OpenAPI component Schema | 27 |
| 兼容基线 Schema | 8 |
| DSL MVP 节点类型 | 6 |
| DSL MVP 触发器类型 | 3 |
| Event Envelope 必需字段 | 7 |
| DSL 正例/反例 fixture | 2 / 3 |
| Event 正例/反例 fixture | 2 / 3 |
| 反向契约变异 | 22/22 拒绝 |

## 4. 已验证行为

- OpenAPI 固定 `/api/v1`、唯一 operationId、OIDC、cursor 分页、成功状态与 planned 实现状态。
- 所有写接口具有必需 `Idempotency-Key`，draft PUT 额外具有必需 `If-Match`。
- 请求模型不接受客户端 tenantId；租户只来自认证上下文。
- 所有 operation 的错误统一引用 `application/problem+json`，ProblemDetail 的 9 个必需成员、字段错误枚举和 50 项上限与 G2-09 一致。
- OpenAPI 全部 27 个 component Schema、DSL 和 Event Schema 均通过 JSON Schema 2020-12 编译，全部内部/外部 `$ref` 可达。
- DSL v1 拒绝缺失 schemaVersion、未知节点和明显不安全表达式；HTTP 节点只接受 connectorRef，不公开任意 endpoint。
- Event v1 拒绝全零 traceId、非 UTC 时间和缺失 tenantId，同时正例证明消费者可忽略未知可选字段。
- API 删除/operationId 重复/必需头缺失/错误引用漂移/tenantId 注入/响应字段删除/请求新增必填/类型改变/enum 删除/断裂 ref/OIDC 漂移，以及 DSL/Event 版本和兼容边界破坏均被反向门禁拒绝。

## 5. 验收命令与结果

```powershell
pnpm install --frozen-lockfile
pnpm contracts:public:verify
pnpm contracts:public:test
pnpm traceability:verify
pnpm traceability:test
pnpm verify
pnpm migrations:verify
pnpm migrations:test
pnpm compose:verify
pnpm ci:verify
mvn clean verify
mvn -o clean verify
```

- `pnpm install --frozen-lockfile` PASS：锁文件与 `ajv@8.17.1`、`ajv-formats@3.0.1` 精确依赖一致。
- 公共契约 PASS：15 operations、27 OpenAPI Schema、6 节点、3 触发器、7 事件必需字段、10 fixtures 全部符合基线。
- 反向控制 PASS：22 类契约破坏全部被拒绝。
- 需求追踪 PASS：36 个 Requirement、123/123 个 G2～G12 任务、10/10 个 MVP Gate 均覆盖。
- 整仓 `pnpm verify` PASS：架构、领域、Problem/Public Contract、ER、V1～V5 静态迁移、治理、追踪、配置、Secret、前端 lint/typecheck/3 项测试/build 全部通过。
- 迁移/Compose/CI PASS：V1～V5 校验和未漂移；未登记/登记/篡改反例正确；7 个固定 digest Compose 服务与 4-job/9-action CI 合同通过。
- Maven PASS：Java 21 / Maven 3.9.16 五模块联网与离线 `clean verify` 均成功，32 项 Java 测试无失败，Spotless/Checkstyle/PMD/CPD/SpotBugs/JaCoCo 全绿。
- Secret PASS：工作镜像 237 个、桌面 240 个提交候选无泄漏，合成硬编码密钥反例均被阻断。
- 桌面独立复验 PASS：`pnpm install --frozen-lockfile` 从缓存复用 312 个包、完整 `pnpm verify`、公共契约正反门禁和 Maven 离线五模块 `clean verify` 全部成功。
- 文件审计 PASS：工作镜像 237 个交付文件、桌面 240 个交付文件；237 个同名文件 0 缺失、0 SHA-256 差异。桌面额外文件仅为保留的 `00-原始项目开发大纲.md`、`01-项目大纲审计与优化基线.md`、`02-项目开发总控清单.md`；工作镜像无 `.git`，桌面原 `.git` 保留。

## 6. 边界与遗留风险

- OpenAPI operation 当前只是经过评审的 planned 契约，不代表业务控制器、鉴权、事务或运行引擎已经实现；G3～G7 按任务逐个接入并更新实现状态。
- JSON Schema 只验证 DSL 结构。唯一 Start、ID 唯一、可达性、边引用、非法环、参数类型、连接器/密钥存在和 CEL 资源限制由 G3 编译器负责。
- Envelope 当前只定义公共信封；每个具体 eventType 的 payload Schema、Topic/Key、生产者/消费者契约随对应业务能力交付。
- 真实 OIDC/RBAC/对象级授权和异步 tenant 传播由 G6 完成；当前不能用 Schema 代替授权。
- OpenAPI SDK/文档生成由 G11-02 完成；破坏性变更必须使用新 Major、ADR 和迁移资产，不能直接修改 v1 基线绕过门禁。
