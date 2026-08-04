# 配置分层与密钥处理基线

## 1. 目标与边界

本基线定义 G1 阶段可执行的配置契约：配置来源、覆盖顺序、环境分层、敏感级别、示例值策略和提交前扫描。业务连接器密钥的加密存储、KMS/Vault Provider、轮换和细粒度授权属于 G6-07；本任务不以本地 `.env` 冒充生产密钥管理。

任何新配置键必须同时更新对应 `.env.example`、本目录、Profile 文件（如适用）和 `scripts/config/verify-configuration.mjs`，否则统一门禁失败。

## 2. 配置域

| 配置域 | 示例文件 | 使用者 | 是否可含敏感示例 |
|---|---|---|---|
| 应用运行 | `/.env.example` | control-plane、worker | 只能使用带 `local-only-change-me` 的本地占位值 |
| 基础设施引导 | `/deploy/compose/.env.example` | Docker Compose、初始化容器 | 同上；包含管理员级本地引导凭据 |
| 浏览器构建 | `/web-console/.env.example` | Vite/Web Console | 禁止；`VITE_*` 会进入浏览器产物 |

三份文件职责不同，不能把 Compose 管理员密码直接作为前端变量，也不能让浏览器变量承担服务端密钥。真实本地值写入同目录 `.env` 或 `.env.local`；这些文件受 `.gitignore` 保护。

## 3. Spring 配置分层与覆盖顺序

control-plane 和 worker 都提供：

- `application.yml`：跨环境公共项，并以 `optional:file:.env[.properties]` 读取仓库根目录、已忽略的 `.env`。
- `application-local.yml`：本地端点可有安全默认值；密码和 secret 不提供提交内默认值。
- `application-test.yml`：只使用明确的 `test-only` 值，可由测试容器动态覆盖。
- `application-prod.yml`：所有外部端点和敏感项都必须由环境注入，不允许本地/测试值或 `${VAR:default}` 回退。

同一键的有效优先级从高到低为：

1. 命令行参数；
2. JVM System Properties / OS 环境变量；
3. 未提交的根 `.env`；
4. 当前激活的 `application-{profile}.yml`；
5. 公共 `application.yml`。

开发默认 Profile 为 `local`；测试必须显式使用 `test`；生产部署必须显式使用 `prod`，并由部署平台注入全部必需环境变量。生产配置不读取或分发仓库中的示例密码。

## 4. 应用运行配置目录

| 键 | 使用者 | 敏感 | 本地示例/默认 | 生产规则 |
|---|---|---:|---|---|
| `SPRING_PROFILES_ACTIVE` | 两个后端 | 否 | `local` | 必须显式为 `prod` |
| `CONTROL_PLANE_PORT` | control-plane | 否 | `8080` | 必须注入 |
| `WORKFLOW_DATABASE_URL` | 两个后端 | 条件敏感 | 本地 JDBC URL | 必须注入，禁止内嵌密码 |
| `WORKFLOW_DATABASE_USERNAME` | 两个后端 | 条件敏感 | `workflow_app` | 必须注入最小权限账号 |
| `WORKFLOW_DATABASE_PASSWORD` | 两个后端 | 是 | 本地专用占位值 | Secret Provider/环境注入，无默认 |
| `WORKFLOW_DATABASE_MIGRATION_USERNAME` | control-plane Flyway | 条件敏感 | `workflow_migrator` | 独立 DDL owner，不能与运行时账号共用 |
| `WORKFLOW_DATABASE_MIGRATION_PASSWORD` | control-plane Flyway | 是 | 本地专用占位值 | 仅迁移 Job/启动阶段注入，无默认 |
| `WORKFLOW_DATABASE_APPLICATION_ROLE` | control-plane Flyway | 否 | `workflow_app` | 经审查的数据库标识符，用于最小 DML GRANT |
| `WORKFLOW_DATABASE_PLATFORM_ADMIN_ROLE` | control-plane Flyway | 否 | `workflow_platform_admin` | 经审查的 NOLOGIN 标记角色，禁止普通应用成为成员 |
| `WORKFLOW_KAFKA_BOOTSTRAP_SERVERS` | 两个后端 | 否 | `127.0.0.1:9092` | 必须注入 |
| `WORKFLOW_KAFKA_PUBLISHER_ENABLED` | control-plane | 否 | `true` | 必须注入，禁止生产回退 |
| `WORKFLOW_TASK_READY_TOPIC` | 两个后端 | 否 | `workflow.task.ready` | 必须注入，生产者/消费者必须一致 |
| `WORKFLOW_EVENTS_TOPIC` | control-plane | 否 | `workflow.events` | 必须注入 |
| `WORKFLOW_REDIS_HOST` | 两个后端 | 否 | `127.0.0.1` | 必须注入 |
| `WORKFLOW_REDIS_PORT` | 两个后端 | 否 | `16379` | 必须注入 |
| `WORKFLOW_REDIS_PASSWORD` | 两个后端 | 是 | 本地专用占位值 | Secret Provider/环境注入，无默认 |
| `WORKFLOW_S3_ENDPOINT` | 两个后端 | 否 | `http://127.0.0.1:19000` | 必须注入并使用 TLS |
| `WORKFLOW_S3_ACCESS_KEY` | 两个后端 | 条件敏感 | 本地 MinIO 用户名 | 生产视为敏感身份 |
| `WORKFLOW_S3_SECRET_KEY` | 两个后端 | 是 | 本地专用占位值 | Secret Provider/环境注入，无默认 |
| `WORKFLOW_S3_BUCKET` | 两个后端 | 否 | `workflow-payloads` | 必须注入 |
| `WORKFLOW_PAYLOAD_INLINE_THRESHOLD_BYTES` | 两个后端 | 否 | `65536` | 生产必须为 Control Plane 与 Worker 注入相同的 1～67108864 字节阈值 |
| `WORKFLOW_OIDC_ISSUER` | control-plane | 否 | 本地 Keycloak Realm | 必须注入 HTTPS Issuer |
| `WORKFLOW_OTEL_ENDPOINT` | 两个后端 | 否 | `http://127.0.0.1:4317` | 必须注入；鉴权另用敏感变量 |
| `WORKFLOW_WORKER_CONCURRENCY` | worker | 否 | `4` | 必须按容量注入 |
| `WORKFLOW_WORKER_KAFKA_ENABLED` | worker | 否 | `true` | 必须注入，禁止生产回退 |
| `WORKFLOW_WORKER_CONSUMER_NAME` | worker | 否 | `workflow-worker` | 必须注入并保持稳定 |
| `WORKFLOW_WORKER_INSTANCE_ID` | worker | 否 | `workflow-worker-local-1` | 必须为每个运行实例注入稳定且唯一的身份 |

URL、用户名和访问键虽然不总是密码，仍不得在日志、错误响应和前端产物中无条件暴露。

## 5. Compose 引导配置目录

| 键 | 敏感 | 用途/规则 |
|---|---:|---|
| `COMPOSE_PROJECT_NAME` | 否 | 固定本地资源前缀，避免误操作其他项目 |
| `POSTGRES_PORT` | 否 | PostgreSQL 宿主机端口 |
| `POSTGRES_ADMIN_USER` | 条件敏感 | 仅初始化使用的本地管理员身份 |
| `POSTGRES_ADMIN_PASSWORD` | 是 | 本地管理员占位密码 |
| `POSTGRES_DB` | 否 | 应用数据库名 |
| `POSTGRES_MIGRATION_USER` | 条件敏感 | 独立迁移 owner，不提供给运行时业务连接池 |
| `POSTGRES_MIGRATION_PASSWORD` | 是 | 迁移账号本地占位密码 |
| `POSTGRES_APP_USER` | 条件敏感 | 应用最小权限角色 |
| `POSTGRES_APP_PASSWORD` | 是 | 应用本地占位密码 |
| `POSTGRES_PLATFORM_ADMIN_ROLE` | 否 | NOLOGIN 平台运维标记角色；成员资格必须受控 |
| `KAFKA_PORT` | 否 | Kafka 外部监听端口 |
| `REDIS_PORT` | 否 | Redis 宿主机端口 |
| `REDIS_PASSWORD` | 是 | Redis 本地占位密码 |
| `MINIO_API_PORT` | 否 | MinIO S3 API 端口 |
| `MINIO_CONSOLE_PORT` | 否 | MinIO Console 端口 |
| `MINIO_ROOT_USER` | 条件敏感 | 仅本地引导的 MinIO 管理身份 |
| `MINIO_ROOT_PASSWORD` | 是 | MinIO 本地占位密码 |
| `MINIO_BUCKET` | 否 | 私有 Payload Bucket 名 |
| `KEYCLOAK_PORT` | 否 | Keycloak HTTP 端口 |
| `KEYCLOAK_DB` | 否 | Keycloak 独立数据库名 |
| `KEYCLOAK_DB_USER` | 条件敏感 | Keycloak 独立数据库角色 |
| `KEYCLOAK_DB_PASSWORD` | 是 | Keycloak 数据库本地占位密码 |
| `KEYCLOAK_ADMIN_USERNAME` | 条件敏感 | 本地引导管理员身份 |
| `KEYCLOAK_ADMIN_PASSWORD` | 是 | Keycloak 本地管理员占位密码 |

Compose 示例值只能用于单机开发。共享环境禁止继续使用示例值，且不得把管理员凭据提供给应用进程。

## 6. Web Console 公共配置目录

| 键 | 用途 | 规则 |
|---|---|---|
| `VITE_API_BASE_URL` | 浏览器 API 基址 | 公开值，不得包含 URL userinfo/token |
| `VITE_OIDC_ISSUER` | OIDC Realm Issuer | 公开发现地址 |
| `VITE_OIDC_CLIENT_ID` | OIDC 公共客户端 ID | 不是 client secret；必须配合 PKCE S256 |

任何名称含 `PASSWORD`、`SECRET`、`TOKEN` 或 `PRIVATE_KEY` 的 Vite 变量都会被配置门禁拒绝。

## 7. 密钥扫描门禁

仓库使用 Gitleaks `v8.28.0`，镜像同时固定 multi-arch digest：

```text
zricethezav/gitleaks:v8.28.0@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854
```

`.gitleaks.toml` 继承内置规则，并补充通用 password/token/secret 赋值规则。`pnpm secrets:verify` 会：

1. 使用 `git ls-files --cached --others --exclude-standard` 只收集可能提交的文件；
2. 在只读临时快照上扫描，输出强制 100% redact；
3. 再运行一个仅在临时目录生成的合成硬编码密钥反例；
4. 只有正常仓库无泄漏且反例被项目规则阻断时才通过；
5. 使用受路径前缀保护的临时目录，结束后清理，不把反例写入仓库。

配置契约和密钥门禁命令：

```powershell
pnpm config:verify
pnpm secrets:verify
pnpm verify
```

禁止用宽泛路径 allowlist 跳过 `.env.example`、测试或文档；确需例外时必须限定到精确规则/值，记录问题 ID、责任人、理由和到期日期。

当前唯一全局例外为 G3-06 Flyway 迁移清单：只在 `config/migrations/checksums.json` 中，同时满足 `Vx__name.sql` 文件名和 64 位小写十六进制 SHA-256 的完整行才允许通过，避免内置 `generic-api-key` 将公开完整性摘要误判为凭据。例外责任人为 `platform-security`，到期日为 2027-07-31；路径或清单格式变化时必须重新审计，合成硬编码 Secret 负向控制不得豁免。

## 8. 事件响应

一旦发现真实密钥进入工作区或 Git 历史：立即停止传播，不以“删除当前文件”视为修复；先撤销/轮换凭据，再清理历史和缓存，评估访问日志与影响范围，并记录安全事件。任何证据文档只能记录被 redact 的扫描结果，不能复制泄漏值。
