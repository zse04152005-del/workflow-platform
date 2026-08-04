# ADR-001：技术栈、模块边界与初始部署拓扑

- 状态：Accepted
- 日期：2026-07-30
- 决策人：项目开发团队
- 关联任务：G1-01

## 背景

平台需要同时证明持久化执行、并发状态迁移、任务租约、消息最终一致、多租户安全和可观测性。原始大纲按产品终态列出了 Gateway、IAM、Definition、Trigger、Orchestrator、Scheduler、Human Task、Notification、AI Assistant 与多类 Worker。如果在 MVP 初期全部拆为独立微服务，会引入大量远程调用、部署、版本兼容和分布式事务成本，推迟执行内核的验证。

本 ADR 锁定第一版的语言、构建方式、代码边界和部署单元。消息与状态一致性的具体方案由 ADR-002 决定。

## 决策

### 1. 语言与框架

- 后端使用 Java 21。
- 应用框架使用 Spring Boot 3.x；具体补丁版本在创建父 `pom.xml` 时固定，升级必须通过依赖测试和变更记录。
- 构建使用 Maven Wrapper 和 Maven 多模块，确保开发机与 CI 使用同一 Maven 版本。
- 前端使用 TypeScript、React、Vite 与 Ant Design；具体版本由 lockfile 固定。
- 数据库迁移使用 Flyway，契约使用 OpenAPI 3.1 与 JSON Schema。

选择 Java 路线的原因：

1. Spring Transaction、Security、Kafka、Actuator、OpenTelemetry 与 Testcontainers 能直接覆盖本项目的主要工程难点。
2. Java 的强类型模型适合实现显式状态机、版本化契约和节点插件 SPI。
3. 该路线与项目“企业级工作流平台”的展示目标一致，且本地开发、测试与招聘市场的可理解度较高。

### 2. 初始部署单元

MVP 初期只有三个自研部署单元：

1. `control-plane`：HTTP API、租户/项目、流程定义、触发、编排、调度、人工任务、审计和运维能力。
2. `worker`：消费任务通知，领取数据库租约，执行节点，维护心跳并上报结果。
3. `web-console`：管理端页面。

`shared-contracts` 和 `test-support` 是构建模块，不单独部署。

### 3. Maven 模块

```text
workflow-platform/
├─ pom.xml
├─ control-plane/
├─ worker/
├─ shared-contracts/
├─ test-support/
├─ web-console/
├─ deploy/
├─ docs/
└─ tests/
```

初始 Maven 依赖方向：

```text
control-plane ─┐
               ├──> shared-contracts
worker ────────┘

test-support ───> shared-contracts
```

- `shared-contracts` 只能放置稳定的消息 Envelope、公共标识值对象、错误码和生成的契约模型；禁止放置 Repository、Spring Service 或共享业务数据库实体。
- `control-plane` 与 `worker` 禁止互相形成编译依赖。
- `test-support` 仅供测试作用域依赖，生产代码不得依赖它。
- 前端只通过公开 API/事件文档与后端协作，不读取后端内部模型。

### 4. Control Plane 内部模块

控制面采用模块化单体，至少包含以下业务模块：

| 模块 | 职责 | 可直接拥有的数据 |
|---|---|---|
| tenant | tenant、project、membership、role binding | 租户、项目与授权关系 |
| definition | DSL 草稿、校验、版本与发布 | definition/version/trigger/connector 元数据 |
| runtime | 实例状态机、DAG 推进与实例控制 | workflow/node/task execution |
| scheduler | 到期任务扫描、租约回收和 Outbox 发布协调 | schedule occurrence、调度游标 |
| human-task | 待办、决策、转交与超时 | human task |
| security | OIDC 适配、tenant context、密钥 Provider、出站策略 | secret reference 与安全配置 |
| audit | 追加审计与查询 | audit log |

模块间协作规则：

- 一个模块不得直接调用另一模块的 Repository，也不得直接更新另一模块拥有的表。
- 同进程同步协作通过模块公开的 application API；异步协作通过版本化的 domain event。
- 跨模块事务只允许由明确的 application service 编排，并必须在测试中声明一致性边界。
- 每个模块后续均可迁移为独立服务，但“可拆分”不等于现在就拆分。

### 5. 运行时依赖原则

- PostgreSQL 是工作流状态的唯一事实源。
- Redis 只能用于可重建缓存、短期限流和非权威协调；Redis 丢失不能导致实例状态丢失。
- Kafka 只传递通知和事件，消费者必须基于数据库当前状态判断能否执行。
- 大 Payload 和附件进入 S3 兼容对象存储，数据库保存引用、大小、内容类型和摘要。
- OIDC 身份由 Keycloak 作为本地开发 Provider；生产可替换为兼容的企业 IdP。

## 暂不采用的方案

### 方案 A：从第一天开始全微服务

不采用。它会在核心状态机尚未验证前引入服务发现、远程重试、跨服务追踪、契约升级和多套部署配置。后续只有满足拆分触发条件时才拆分。

### 方案 B：Go + Kratos

技术上可行，但本项目将大量使用事务、Security、Kafka、测试容器和企业 Java 生态。为了减少双栈维护并提高成果的可解释性，第一版不选择 Go。

### 方案 C：所有组件放在一个进程

不采用。Worker 执行不受信任的外部调用，资源特征与控制面不同，并需要独立扩缩容、故障隔离和租约恢复，因此从一开始独立部署。

### 方案 D：先做复杂拖拽画布

不采用。MVP 使用表单、JSON/YAML 编辑和只读 DAG 预览，先验证定义、执行和恢复闭环。

## 拆分触发条件

只有满足以下一项并有测量证据时，才新增独立后端服务：

1. 某模块需要独立扩缩容，且同进程资源隔离无法满足容量目标。
2. 某模块的故障会破坏控制面目标，而进程内隔离和限流仍不足。
3. 团队所有权或发布频率已经形成稳定的独立边界。
4. 安全或合规要求强制使用独立网络/数据边界。

拆分前必须新增 ADR，说明数据所有权、API/事件契约、迁移步骤、失败回滚和新增运维成本。

## 后果

### 正面影响

- 更快跑通第一条可靠流程，减少早期部署与联调成本。
- 控制面模块仍有明确边界，避免演变成无结构单体。
- Worker 从一开始具备独立扩容和故障隔离能力。
- Java/Spring 生态覆盖事务、安全、消息、观测和测试的主要需求。

### 代价与约束

- 控制面初期只能整体发布和扩容。
- 模块边界需要架构测试和代码评审持续约束，否则可能退化为共享数据库的耦合单体。
- Kafka、Keycloak、MinIO 等本地依赖会增加内存占用；后续 Compose 必须提供最小配置和健康检查。
- `shared-contracts` 必须保持精简，不能成为公共业务逻辑垃圾场。

## 验收标准

ADR-001 在满足以下条件时视为完成：

- 明确语言、框架、构建方式和版本锁定策略。
- 明确部署单元、Maven 模块、控制面内部模块与依赖方向。
- 明确 PostgreSQL、Kafka、Redis、对象存储和 OIDC Provider 的职责边界。
- 记录替代方案、代价与未来拆分触发条件。
- 与《项目大纲审计与优化基线》和《项目开发总控清单》不存在冲突。
