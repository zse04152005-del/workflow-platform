# 本地基础设施 Docker Compose

本目录提供 PostgreSQL、Kafka（KRaft）、Redis、MinIO 和 Keycloak 的本地开发栈。所有镜像同时锁定版本标签和多架构 manifest digest；端口只绑定 `127.0.0.1`，不能直接作为生产部署配置。

## 前置条件

- Docker Engine 24+。
- Docker Compose v2 或 Docker Desktop 内置 Compose。
- 建议为 Docker 分配至少 6 GiB 内存。

## 配置

示例配置只用于本机开发，不能复用到共享或生产环境：

```powershell
Copy-Item deploy\compose\.env.example deploy\compose\.env
```

如端口冲突，在 `deploy/compose/.env` 修改宿主机端口。`.env` 已被 Git 忽略，`.env.example` 才能提交。

## 启动与验收

在仓库根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\compose\start.ps1
```

`start.ps1` 会依次校验 Compose 配置、等待五个长期服务健康、执行两个幂等初始化任务，再运行协议级 smoke test。之所以分开执行，是因为 Compose `--wait` 会把正常退出的一次性容器视为未运行。

未创建 `.env` 时，启动脚本和 smoke test 都会回退使用 `.env.example`；也可显式传入该文件：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\compose\start.ps1 -EnvFile deploy\compose\.env.example
```

如只需重复验收已启动的环境：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\compose\smoke-test.ps1 -EnvFile deploy\compose\.env.example
```

Smoke test 不只查看容器状态，还会验证：

- 使用最小权限 `workflow_app` 通过 TCP 登录 PostgreSQL。
- Redis 认证后返回 `PONG`。
- Kafka 中存在 `workflow.task.ready`、`workflow.events`、`workflow.dlq`。
- MinIO 中存在私有 `workflow-payloads` Bucket。
- Keycloak 已导入 `workflow-platform` Realm 并可返回 OIDC metadata。

## 本地端点

| 服务 | 默认端点 | 用途 |
|---|---|---|
| PostgreSQL | `127.0.0.1:15432/workflow_platform` | 工作流权威状态 |
| Kafka | `127.0.0.1:9092` | 任务通知与事件 |
| Redis | `127.0.0.1:16379` | 限流与短期缓存，不存核心状态 |
| MinIO S3 API | `http://127.0.0.1:19000` | 大 Payload/附件 |
| MinIO Console | `http://127.0.0.1:19001` | 本地对象存储管理 |
| Keycloak | `http://127.0.0.1:8081` | OIDC 身份提供方 |
| OIDC metadata | `http://127.0.0.1:8081/realms/workflow-platform/.well-known/openid-configuration` | Realm 发现文档 |

应用使用 PostgreSQL 的 `workflow_app` 最小权限用户，Flyway 使用独立 `workflow_migrator` owner；`workflow_admin` 只用于本地容器首次初始化。`workflow_platform_admin` 是不能登录、没有 `BYPASSRLS` 的标记角色，不能授予普通应用账号。Keycloak 使用独立数据库、独立角色。MinIO Bucket 默认禁止匿名访问。Kafka 自动创建 Topic 被关闭，Topic 由 `kafka-init` 明确创建。

初始化脚本的角色分离只在空数据卷首次启动时生效。若本机数据卷创建于 G2-04 之前，需先确认没有需要保留的数据，再按“停止与重置”中的命令重建；不要在有业务数据的环境用重建数据卷代替正式权限迁移。

## 状态与日志

```powershell
docker compose --env-file deploy/compose/.env -f deploy/compose/compose.yml ps
docker compose --env-file deploy/compose/.env -f deploy/compose/compose.yml logs --tail 200 postgres kafka redis minio keycloak
```

## 停止与重置

停止容器但保留数据卷：

```powershell
docker compose --env-file deploy/compose/.env -f deploy/compose/compose.yml down
```

以下命令会永久删除本地 PostgreSQL、Kafka、Redis 和 MinIO 数据，只能在明确需要干净环境时执行：

```powershell
docker compose --env-file deploy/compose/.env -f deploy/compose/compose.yml down --volumes --remove-orphans
```

Keycloak Realm 导入和 PostgreSQL 初始化只在空数据卷首次启动时执行。修改初始化脚本后，要使用上述带 `--volumes` 的重置命令重建本地数据。

## 范围边界

- 全部监听器是本地明文开发配置；生产必须启用 TLS、外部密钥管理、网络策略和高可用拓扑。
- 单节点 Kafka、单实例 PostgreSQL/Redis/MinIO/Keycloak 只用于开发和测试，不代表生产可用性。
- 镜像更新必须同时更新标签、digest、兼容性验证和本任务证据，禁止改回 `latest`。
