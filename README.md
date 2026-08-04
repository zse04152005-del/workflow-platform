# 企业级事件驱动智能工作流平台

面向企业审批、定时任务、HTTP/Webhook 集成、业务补偿和跨系统自动化场景的可靠工作流平台。平台使用持久化状态机推进流程，通过 Outbox、至少一次投递、任务租约、fencing token 和业务幂等处理服务重启、消息重复与 Worker 故障。

> 项目正在按总控清单逐步开发。未通过自动化验收的能力不会在本文中标记为已完成；性能数据只在可复现压测后公布。

## 当前基线

- 后端：Java 21、Spring Boot 3.5.16、Maven Wrapper 3.9.16 多模块。
- 前端：TypeScript 6、React 19、Vite 7、Ant Design 6。
- 数据与消息：PostgreSQL 18、Kafka 4（KRaft）、Redis 8、MinIO。
- 身份：OIDC；本地开发使用 Keycloak 26。
- 观测：OpenTelemetry、Prometheus、Grafana。
- 初始拓扑：模块化 `control-plane` + 独立 `worker` + `web-console`。

后端版本由父 `pom.xml` 和 Wrapper 配置锁定；前端 Node/pnpm 版本由根配置固定，JavaScript 依赖由 `pnpm-lock.yaml` 精确锁定。架构与一致性语义以 ADR 为准。

## 设计原则

1. PostgreSQL 是工作流状态的唯一事实源，Redis 和 Kafka 都不能代替权威状态。
2. 发布版本不可变，实例始终绑定启动时的准确版本。
3. 内部状态推进使用事务、乐观锁和消费者去重；外部接口不承诺 exactly-once。
4. Worker 必须取得数据库租约，迟到结果必须通过 fencing token 隔离。
5. 多租户条件、权限校验、密钥引用和审计是基础能力，不是发布后的补丁。
6. AI 只提供建议和草稿，不能直接发布流程或执行高风险操作。

## 目录结构

```text
workflow-platform/
├─ control-plane/      # 控制面 Spring Boot 应用
├─ worker/             # 独立任务执行进程
├─ shared-contracts/   # 稳定的 API/事件契约和值对象
├─ test-support/       # 测试容器与公共测试设施
├─ web-console/        # 管理端前端
├─ deploy/
│  ├─ compose/
│  ├─ helm/
│  └─ observability/
├─ docs/
│  ├─ adr/
│  ├─ architecture/
│  ├─ api/
│  ├─ planning/
│  └─ runbook/
└─ tests/
   ├─ integration/
   ├─ e2e/
   └─ performance/
```

## 文档入口

- [原始项目开发大纲](docs/planning/00-原始项目开发大纲.md)
- [项目大纲审计与优化基线](docs/planning/01-项目大纲审计与优化基线.md)
- [项目开发总控清单](docs/planning/02-项目开发总控清单.md)
- [需求追踪矩阵](docs/planning/03-需求追踪矩阵.md)
- [统一 Definition of Done](docs/governance/definition-of-done.md)
- [版本与变更管理规范](docs/governance/versioning-and-change-management.md)
- [ID、时间、枚举、错误码、审计与删除约定](docs/architecture/domain-data-conventions.md)
- [核心数据库 ER、约束、索引与分区基线](docs/architecture/database-er-model.md)
- [OpenAPI、DSL 与事件公共契约](docs/architecture/public-contracts-and-compatibility.md)
- [DSL v1 核心模型与确定性边界](docs/architecture/dsl-core-model.md)
- [DSL JSON/YAML 解析、规范化与 checksum](docs/architecture/dsl-parser-normalization.md)
- [DSL v1 工作流图校验](docs/architecture/dsl-graph-validation.md)
- [租户上下文、RLS 与 RBAC 初始迁移](docs/architecture/tenant-context-and-rbac-migration.md)
- [可靠消息、幂等、死信与 Worker Lease V4 数据库迁移](docs/architecture/reliability-and-lease-migration.md)
- [ADR-001：技术栈、模块边界与初始部署拓扑](docs/adr/ADR-001-technology-stack-and-module-boundaries.md)
- [ADR-002：可靠消息、幂等、任务租约与一致性边界](docs/adr/ADR-002-reliable-messaging-idempotency-and-task-leases.md)
- [控制面模块边界](docs/architecture/control-plane-module-boundaries.md)
- [Java 质量门禁](docs/architecture/java-quality-gates.md)
- [配置分层与密钥处理基线](docs/architecture/configuration-and-secret-handling.md)
- [持续集成与质量门禁基线](docs/architecture/ci-quality-gates.md)
- [贡献与提交规范](CONTRIBUTING.md)

## 本地开发与构建

### 后端

前置条件：安装 Java 21 JDK，并让 `JAVA_HOME` 指向该 JDK。Maven 由仓库中的 Wrapper 下载，不要求系统预装 Maven。

Windows PowerShell：

```powershell
.\mvnw.cmd -B clean verify
```

Linux/macOS：

```bash
./mvnw -B clean verify
```

该命令会检查 Java/Maven 和插件版本、编译四个 Maven 模块、执行测试并生成 JAR。Maven 分发包固定 SHA-256，下载校验失败时构建会停止。

`clean verify` 同时执行 Spotless、Checkstyle、PMD/CPD、SpotBugs 和 JaCoCo 覆盖率门禁。只修复 Java 格式时运行：

```powershell
.\mvnw.cmd -B spotless:apply
```

### 前端 Web Console

前置条件：使用 Node.js 24.14.0 和 pnpm 11.9.0。仓库通过 `.node-version`、`.nvmrc`、`packageManager` 和严格 Engine 检查固定工具版本。

首次安装依赖：

```powershell
pnpm install --frozen-lockfile
```

如需覆盖本地 API 或 OIDC 地址，将 `web-console/.env.example` 复制为 `web-console/.env.local`，不要在示例文件中写入真实密钥。

根目录统一命令：

```powershell
pnpm dev        # 启动 Vite 开发服务器
pnpm lint       # ESLint，禁止 warning
pnpm typecheck  # TypeScript 严格类型检查
pnpm test       # Vitest 单元/组件测试
pnpm build      # 生产构建
pnpm preview    # 预览最近一次生产构建
pnpm verify     # 架构/领域约定/ER/治理/追踪/配置/Secret + lint + typecheck + test + build
```

### 本地基础设施

前置条件：Docker Desktop 或 Docker Engine + Compose。首次使用可复制示例配置；其中只含明确标注的本地开发凭据：

```powershell
Copy-Item deploy\compose\.env.example deploy\compose\.env
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\compose\start.ps1
```

启动脚本会等待 PostgreSQL、Kafka、Redis、MinIO、Keycloak 健康，幂等创建 Kafka Topics 和 MinIO 私有 Bucket，并自动执行协议级 smoke test。未创建 `.env` 时会回退到 `.env.example`。端口、停止、重置和故障排查说明见 [Docker Compose 本地基础设施](deploy/compose/README.md)。

### 配置与密钥门禁

应用运行、基础设施引导和浏览器构建分别使用根目录、`deploy/compose`、`web-console` 下的 `.env.example`，三者不能混用。根应用配置的本地启动方式：

```powershell
Copy-Item .env.example .env
```

后端配置分为 `local`、`test`、`prod`；生产层外部连接与敏感项均无提交内默认值。配置契约及密钥正反向门禁：

```powershell
pnpm config:verify
pnpm secrets:verify
pnpm verify
```

`secrets:verify` 使用固定 tag+digest 的 Gitleaks 镜像扫描全部可提交文件，并确认运行时生成的合成反例会被阻断；扫描输出强制 redact。详细覆盖顺序、47 个配置键和事件响应流程见[配置分层与密钥处理基线](docs/architecture/configuration-and-secret-handling.md)。

### CI 契约

GitHub Actions 将 Java、Web/策略、工作流、Compose 拆为 4 个最小权限 Job。提交前可额外执行：

```powershell
pnpm migrations:verify
pnpm migrations:test
pnpm compose:verify
pnpm ci:verify
```

迁移门禁已冻结登记 V1～V5；G2-04～G2-08 静态门禁逐组锁定 ER 列、复合外键、索引、强制 RLS、生命周期 guard 和最小权限，并以反向变异自测。最新的 V4→V5 真实 PostgreSQL 验收命令：

```powershell
pnpm database:g2-08:integration
```

该命令只创建并清理带 `workflow-g2-07-*` 前缀的一次性容器/网络，不改动 Compose 数据卷。Action、Gitleaks、actionlint 和数据库验收镜像都固定到 commit SHA 或镜像 digest，详见[持续集成与质量门禁基线](docs/architecture/ci-quality-gates.md)。

### 治理与需求追踪门禁

任务完成标准、分支/提交命名、SemVer 和 API/DSL/Event/数据库兼容规则都有机器可执行基线：

```powershell
pnpm architecture:verify
pnpm architecture:test
pnpm domain:verify
pnpm domain:test
pnpm contracts:public:verify
pnpm contracts:public:test
pnpm er:verify
pnpm er:test
pnpm governance:verify
pnpm governance:test
pnpm traceability:verify
pnpm traceability:test
pnpm definition:g3-10:verify
pnpm definition:g3-10:test
```

正向规则和反向控制均已接入 `pnpm verify`，因此 CI 会阻断架构模型漂移/依赖环/多事实源、重复或遗漏 Requirement、范围串线、无效分支/Commit/Tag、版本漂移、缺失 DoD、过宽迁移命名和不存在的治理文档。G3-10 还锁定固定种子生成规模、DSL v1 canonical checksum、恶意 CEL/JSONPath 语料和 PostgreSQL 八写者强 ETag 竞争不变量。详细规则见[架构文档索引](docs/architecture/README.md)、[工作流定义鲁棒性套件](docs/architecture/workflow-definition-robustness-suite.md)、[统一 Definition of Done](docs/governance/definition-of-done.md)和[版本与变更管理规范](docs/governance/versioning-and-change-management.md)。

## 进度与验收

所有任务状态、Gate、验收标准和证据统一维护在[项目开发总控清单](docs/planning/02-项目开发总控清单.md)。

任务只有在实现、测试、文档和可复现证据齐全后才能标记为 `[x]`。每次完成任务还必须记录完成日期、关键文件、验收命令和遗留风险。

## 安全说明

- 禁止提交真实 token、密码、私钥、连接器密钥和生产数据。
- 发现安全问题时不要创建包含利用细节的公开 Issue；正式安全报告流程将在 G6 阶段建立。
- 示例配置只能使用无权限的本地开发凭据，并必须明确标记用途。

## License

本项目采用 [Apache License 2.0](LICENSE)。
