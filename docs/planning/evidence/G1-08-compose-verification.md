# G1-08 验收记录：Docker Compose 本地基础设施

- 状态：PASS
- 验收日期：2026-07-30
- 权威目录：`C:\Users\Administrator\Desktop\workflow-platform`
- 对应任务：G1-08

## 交付内容

- PostgreSQL 18、Kafka 4 KRaft、Redis 8、MinIO、Keycloak 26 五项长期服务。
- `kafka-init` 幂等创建 3 个显式 Topic；Kafka 禁止自动创建 Topic。
- `minio-init` 幂等创建私有 `workflow-payloads` Bucket。
- PostgreSQL 管理员、业务应用、Keycloak 独立角色和数据库边界。
- Keycloak 导入 `workflow-platform` Realm；Web Console 使用 Authorization Code + PKCE S256，禁用 Direct Access Grant。
- 所有宿主机端口仅绑定 `127.0.0.1`，四类数据使用命名卷持久化。
- 五项长期服务健康检查、两项一次性任务退出码检查和协议级 smoke test。
- `start.ps1` 封装可靠、幂等的启动顺序；README 提供启动、停止、重置和排障入口。

## 环境与固定镜像

- Docker Client/Server：29.6.2
- Docker Compose：5.3.1
- Docker Desktop：4.84.0
- Docker Context：`desktop-linux`

| 服务 | 固定镜像 |
|---|---|
| PostgreSQL | `postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296` |
| Kafka / kafka-init | `apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837` |
| Redis | `redis:8.6.5-alpine@sha256:cd218f4b106a332c5c992e38a9480bfb9d7e9f8f7b0ec9a0023bfa36d9a408f9` |
| MinIO | `minio/minio:RELEASE.2025-09-07T16-13-09Z@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` |
| MinIO Client | `minio/mc:RELEASE.2025-08-13T08-35-41Z@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727` |
| Keycloak | `quay.io/keycloak/keycloak:26.6.4@sha256:0aae0de7fca85525f727d3354df17896092de8bb26ae4c12d89c77e5df8cbce4` |

`docker compose config --images` 输出 7 行，全部同时包含版本标签和 multi-arch digest，无 `latest`。

## 网络、端口和持久化

| 服务 | 宿主机端点 | 容器端口 | 状态 |
|---|---|---:|---|
| PostgreSQL | `127.0.0.1:15432` | 5432 | healthy |
| Kafka | `127.0.0.1:9092` | 9092 | healthy |
| Redis | `127.0.0.1:16379` | 6379 | healthy |
| MinIO API | `127.0.0.1:19000` | 9000 | healthy |
| MinIO Console | `127.0.0.1:19001` | 9001 | healthy |
| Keycloak | `127.0.0.1:8081` | 8080 | healthy |

PostgreSQL、Kafka、Redis、MinIO 分别使用 `postgres-data`、`kafka-data`、`redis-data`、`minio-data` 命名卷。Keycloak 状态存入独立的 PostgreSQL `keycloak` 数据库，不使用容器临时文件作为权威状态。

默认宿主机端口避开机器上既有 `ai-gateway-platform` 的 PostgreSQL、Redis、ClickHouse 映射；没有停止或修改其他项目。Compose 仍允许通过私有 `.env` 覆盖端口。

## 自动化验收

配置解析：

```powershell
docker compose --env-file deploy\compose\.env.example -f deploy\compose\compose.yml config --quiet
docker compose --env-file deploy\compose\.env.example -f deploy\compose\compose.yml config --images
```

结果：退出码 `0`；镜像列表全部固定标签与 digest。

端到端启动与验收：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\compose\start.ps1 -EnvFile deploy\compose\.env.example
```

结果：退出码 `0`；脚本可重复执行且初始化操作幂等。最终 smoke test 输出：

```text
Status                : PASS
HealthyServices       : 5
CompletedInitServices : 2
PostgreSQL            : workflow_app|workflow_platform
Redis                 : PONG
KafkaTopics           : workflow.dlq,workflow.events,workflow.task.ready
MinioBucket           : workflow-payloads
MinioAnonymousHttp    : 403
KeycloakIssuer        : http://127.0.0.1:8081/realms/workflow-platform
KeycloakClient        : workflow-web-console (Authorization Code + PKCE S256)
```

状态复核显示五个长期容器均为 `healthy`，`kafka-init` 和 `minio-init` 均为 `Exited (0)`。

## 实机发现与修正

1. 本机 `9000`、`5432`、`6379` 已由其他项目占用。将本项目默认宿主机端口调整为 `19000`、`15432`、`16379`，保留容器内部标准端口和 `.env` 覆盖能力。
2. Compose 5.3.1 的 `up -d --wait` 会把正常退出的一次性初始化容器视为未运行。`start.ps1` 先等待五个长期服务，再单独执行两个幂等 init 服务，避免伪失败。
3. Windows PowerShell 原生参数转发会破坏含 `$VAR` 的内嵌 Bash 片段。smoke test 改为读取 env 文件并以参数数组、`docker exec --env` 调用客户端，避免跨平台引号依赖。
4. 本机执行策略默认禁止直接运行 `.ps1`。文档采用仅影响当前进程的 `-ExecutionPolicy Bypass`，不修改系统级策略。

## 关键文件

- `deploy/compose/compose.yml`
- `deploy/compose/.env.example`
- `deploy/compose/start.ps1`
- `deploy/compose/smoke-test.ps1`
- `deploy/compose/postgres/init/01-create-application-databases.sh`
- `deploy/compose/keycloak/realm/workflow-platform-realm.json`
- `deploy/compose/README.md`
- `README.md`

## 已知边界

- 示例密码只用于单机开发；共享和生产环境必须接入外部密钥管理并轮换凭据。
- 单节点 Kafka、单实例数据库/缓存/对象存储/身份服务不代表生产高可用拓扑。
- 本地监听使用明文协议；生产必须启用 TLS、网络策略、备份恢复和容量治理。
- Docker 数据卷默认保留；只有明确需要重建初始化状态时才执行带 `--volumes` 的重置命令。

## 结论

版本与 digest 锁定、健康检查、持久化、最小权限初始化、本地网络边界、幂等启动流程和协议级验证均有可复现证据，G1-08 可以标记为 `[x]`。
