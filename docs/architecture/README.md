# 架构文档索引

架构基线遵循“机器模型 + 可读视图 + ADR + 自动化门禁”。影响一致性、安全边界、公开契约、数据所有权或部署拓扑的修改必须先更新 ADR，并运行对应校验。

| 文档 | 状态 | 内容 |
|---|---|---|
| [系统上下文、容器与模块依赖视图](c4-architecture-views.md) | Accepted | C4 Level 1/2、控制面模块图、协议、数据所有权和禁止依赖 |
| [控制面模块边界](control-plane-module-boundaries.md) | Accepted | 七个模块、根包公开边界与 ArchUnit 约束 |
| [ID、时间、枚举、错误码、审计与删除约定](domain-data-conventions.md) | Accepted | UUIDv7、UTC/时区、稳定枚举/错误码、审计字段和显式删除策略 |
| [RFC 9457 统一错误响应契约](problem-details-contract.md) | Accepted | `application/problem+json`、稳定错误码、traceId、字段错误、Spring MVC 映射与脱敏边界 |
| [OpenAPI、DSL 与事件公共契约](public-contracts-and-compatibility.md) | Accepted | OpenAPI 3.1、DSL/Event JSON Schema 2020-12、幂等/ETag/租户边界与破坏性变更门禁 |
| [DSL v1 核心模型与确定性边界](dsl-core-model.md) | Accepted | `schemaVersion`、公共节点/边/参数、三类重试、正值时间、深层不可变和 G3-02～G3-05 职责边界 |
| [DSL JSON/YAML 解析、规范化与 checksum](dsl-parser-normalization.md) | Accepted | 严格 UTF-8、YAML 安全预检、canonical JSON、有界 plain decimal、SHA-256、稳定失败码与 G3-03～G3-05 延期边界 |
| [DSL v1 工作流图校验](dsl-graph-validation.md) | Accepted | 唯一 Start、ID/引用/transition、双向可达、禁止有向环、确定性拓扑序与 16 个稳定失败码 |
| [DSL 节点配置、集成引用与授权校验](dsl-node-configuration-validation.md) | Accepted | 六类节点/三类触发器专属 Schema、类型化结果、connector/secret 作用域、生命周期与显式 use grant |
| [DSL CEL/JSONPath 表达式与资源沙箱](dsl-expression-sandbox.md) | Accepted | 受限 CEL、具名根 JSONPath、静态类型、编译预算与运行时资源守卫 |
| [工作流草稿 API、强 ETag 与租户并发边界](workflow-draft-api.md) | Accepted | create/get/update、If-Match 412/428、幂等事务、可信上下文、RLS 与并发写入 |
| [工作流 Validate API、精确定位与资源授权快照](workflow-validation-api.md) | Accepted | current/supplied 校验、revision/checksum、四阶段短路、节点/字段错误、Secret-free RBAC/RLS 快照 |
| [工作流定义鲁棒性与兼容性回归套件](workflow-definition-robustness-suite.md) | Accepted | 固定种子 canonical/图属性、冻结 DSL v1 checksum、CEL/JSONPath 恶意语料与八写者同 ETag 竞争 |
| [Runtime 状态机与合法迁移矩阵](runtime-state-machines.md) | Accepted | ADR-003、实例/节点/attempt/occurrence 状态、expected status/version、终态和后续 G4 边界 |
| [Runtime 统一状态迁移事务组件](runtime-state-transition-component.md) | Accepted | 45 条边的生产 allow-list、乐观锁、RLS、fencing、同事务审计/Outbox 和确定失败分类 |
| [核心数据库 ER、约束、索引与分区基线](database-er-model.md) | Accepted | 22 张核心表、复合租户外键、RLS、关键查询索引和分区候选 |
| [租户上下文、RLS 与 RBAC 初始迁移](tenant-context-and-rbac-migration.md) | Accepted | 五张 G2-04 表、事务级 tenant context、强制 RLS、角色分离与真实 PostgreSQL 验收 |
| [工作流定义、版本、触发器与集成配置迁移](definition-and-integration-migration.md) | Accepted | 五张 G2-05 表、不可变发布历史、作用域唯一性、Secret XOR、强制 RLS 与 V1→V2 验收 |
| [Runtime 与 Schedule V3 数据库迁移](runtime-and-schedule-migration.md) | Accepted | 四张 G2-06 表、状态/时间/租约不变量、Cron 快照、Task 单次终态收口、强制 RLS 与 V2→V3 验收 |
| [可靠消息、幂等、死信与 Worker Lease V4 数据库迁移](reliability-and-lease-migration.md) | Accepted | 五张 G2-07 表、Outbox/去重/幂等/死信/租约约束、五个生命周期 guard、强制 RLS 与 V3→V4 验收 |
| [Human Task、追加审计与配额窗口 V5 数据库迁移](human-audit-quota-migration.md) | Accepted | 三张 G2-08 表、首次决策/转交/超时、平台审计、非重叠配额窗口、强制 RLS 与 V4→V5 验收 |
| [Testcontainers 全量数据库验收基线](testcontainers-database-verification.md) | Accepted | PostgreSQL 18.4 固定镜像、V1～V7、完整 Catalog、约束/RLS 反例与 Q-01～Q-10 默认优化器计划 |
| [Java 质量门禁](java-quality-gates.md) | Accepted | 格式、静态分析、测试和覆盖率规则 |
| [配置分层与密钥处理](configuration-and-secret-handling.md) | Accepted | local/test/prod、env 契约、Secret 门禁 |
| [CI 质量门禁](ci-quality-gates.md) | Accepted | GitHub Actions、固定版本和本地等价验收 |

相关决策：

- [ADR-001：技术栈、模块边界与初始部署拓扑](../adr/ADR-001-technology-stack-and-module-boundaries.md)
- [ADR-002：可靠消息、幂等、任务租约与一致性边界](../adr/ADR-002-reliable-messaging-idempotency-and-task-leases.md)
- [ADR-003：Runtime 实例、节点与 attempt 状态机](../adr/ADR-003-runtime-instance-node-attempt-state-machines.md)

机器源与命令：

```text
config/architecture/model.json
pnpm architecture:verify
pnpm architecture:test
```

图中 `scaffolded`、`local-compose-available`、`planned` 必须据实维护，不能用目标架构冒充当前实现。
