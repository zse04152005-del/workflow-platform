# 租户上下文、RLS 与 RBAC 初始迁移

- 状态：Accepted
- 日期：2026-07-30
- 对应任务：G2-04
- Requirement：`REQ-IAM-001`、`REQ-DATA-001`
- 权威迁移：`control-plane/src/main/resources/db/migration/V1__create_tenant_and_rbac.sql`

## 1. 本次实现范围

首个 Flyway 迁移只创建 ER 模型分配给 G2-04 的五张表：`tenant`、`project`、`membership`、`role`、`role_binding`。它同时创建事务级租户上下文函数、七个显式查询索引、七个 `ON DELETE RESTRICT` 外键、五个强制 RLS policy，以及两个 `NULLS NOT DISTINCT` 自然唯一约束。

内部 ID 由应用在持久化前生成 RFC 9562 UUIDv7，数据库列使用 `uuid`；审计与撤销时间使用 `timestamptz(6)` 和事务时钟。`membership`、`role_binding` 不向运行时角色授予 `DELETE`，撤销必须更新 `status/revoked_at/revoked_by/revocation_reason` 并保留历史。

## 2. 数据库角色与所有权

| 角色 | 登录 | 用途 | 权限边界 |
|---|---:|---|---|
| `workflow_admin` | 是 | 仅本地容器首次引导 | 不提供给应用进程 |
| `workflow_migrator` | 是 | Flyway DDL 与对象 owner | 无 superuser/createdb/createrole/bypassrls |
| `workflow_app` | 是 | 控制面运行时 DML | 仅 Schema USAGE、函数 EXECUTE、表 SELECT/INSERT/UPDATE；无 CREATE/DELETE |
| `workflow_platform_admin` | 否 | 受控平台运维标记角色 | NOLOGIN、无 BYPASSRLS；只授予经审批的独立运维登录角色 |

运行时账号和迁移账号必须分离。生产环境不得让 `workflow_app` 成为数据库、Schema、表或 RLS 函数 owner，也不得授予 `BYPASSRLS`、`CREATEROLE`、`SUPERUSER` 或直接成员管理能力。

## 3. 事务级租户上下文

每个读取或修改租户数据的业务事务必须先由服务端从已经验证的 OIDC/授权上下文得到 `tenantId`，再调用：

```sql
BEGIN;
SELECT public.app_set_tenant_context(:verified_tenant_id);
-- tenant-owned reads and writes
COMMIT;
```

`app_set_tenant_context` 使用 `set_config(..., true)`，只在当前事务有效；提交或回滚后上下文消失。`app_tenant_id()` 在未设置或空值时返回 `NULL`，RLS 比较因此关闭读取并拒绝写入；无效 UUID 会直接失败，不能回退到默认租户。

PostgreSQL 自定义 GUC 本身不是身份凭据。持有应用数据库账号的调用方能够选择租户值，因此 G6 必须保证只有服务端认证/授权组件可建立上下文，不能接受未经验证的请求参数直传。RLS 的职责是阻断遗漏租户过滤、跨租户对象引用和错误 Repository 查询，不替代 OIDC、对象级授权或数据库凭据保护。

## 4. 平台管理员旁路

`app_is_platform_admin()` 不读取可伪造的布尔 GUC，而是检查 `session_user` 是否属于预置的 NOLOGIN 标记角色。普通应用角色即使设置 `workflow.platform_admin=true` 或尝试 `SET ROLE workflow_platform_admin` 也不能开启旁路。

旁路仅写入 `tenant` 表 policy，用于平台级租户治理。`project`、`membership`、`role`、`role_binding` 仍要求显式 tenant context，避免一次平台运维会话无边界读取全部业务数据。

## 5. 跨租户完整性与 RLS

- 五张表全部同时 `ENABLE` 与 `FORCE ROW LEVEL SECURITY`，表 owner 也不能意外跳过 policy。
- `project`、`membership`、`role`、`role_binding` 的 policy 对读使用 `USING`、对写使用同值 `WITH CHECK`。
- 租户实体引用使用 `(tenant_id, referenced_id) -> (tenant_id, id)`；数据库层拒绝跨租户 project、membership、role 引用。
- 所有外键 `ON DELETE RESTRICT`；运行时无 DELETE 权限，生命周期与撤销通过显式状态表达。
- Flyway placeholder `workflowApplicationRole` 和 `workflowPlatformAdminRole` 只接受部署配置中已审查的数据库标识符。

## 6. 迁移与验收

迁移只追加并登记 SHA-256，登记后禁止修改、改名或删除：

```powershell
pnpm migrations:verify
pnpm migrations:test
pnpm database:g2-04:verify
pnpm database:g2-04:test
pnpm database:g2-04:integration
```

前三类门禁验证文件名、顺序、checksum、ER 列/约束/索引/RLS/最小权限一致性及合成反例；集成命令使用带 digest 的一次性 PostgreSQL 18.4 与 Flyway 11.7.2 容器，验证空库 migrate、第二次 migrate、系统目录、约束、跨租户负向、无上下文关闭、角色伪造拒绝和撤销留痕。一次性容器与网络使用 `workflow-g2-04-*` 前缀并在结束时清理，不接触本地 Compose 数据卷。

## 7. 已知边界

- Flyway 11.7.2 来自 Spring Boot 3.5.16 BOM；它能完成 PostgreSQL 18.4 验收，但 CLI 会提示官方已测试上限为 PostgreSQL 17。G2-11 必须重新评审 Flyway/PostgreSQL 兼容矩阵，并在升级前后执行相同迁移套件。
- G2-04 证明五张表和 RLS 基础正确，不代表 G6 的 OIDC、权限矩阵、对象级授权和后台任务 tenant propagation 已完成。
- 前一发布版本升级、全部 22 表、并发锁与十个查询计划仍由 G2-05～G2-11 完成。
