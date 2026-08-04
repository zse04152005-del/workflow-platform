# Runtime 与 Schedule V3 数据库迁移

- 状态：Accepted
- 日期：2026-07-30
- 对应任务：G2-06
- Requirement：`REQ-DATA-001`、`REQ-STATE-001`、`REQ-CRON-001`、`REQ-LEASE-001`、`REQ-RETRY-001`
- 权威迁移：`control-plane/src/main/resources/db/migration/V3__create_runtime_and_schedule.sql`
- SHA-256：`6e1519197f6e8b17c0bc1b2e17701ea0878741e6af5da1561090d845207acb66`

## 1. 已实现范围

V3 只追加创建 G2-06 分配的 `workflow_instance`、`node_instance`、`task_execution`、`schedule_occurrence` 四张表，共 69 列、10 个 `ON DELETE RESTRICT` 外键、13 个显式索引、26 个 CHECK、4 个强制 RLS policy 和 1 个 Task 终态保护触发器。V3 同时在 V2 表上新增两个前滚唯一约束，用于建立不可绕过的复合引用：

- instance 的 `(tenant_id, workflow_version_id, definition_id)` 必须引用同一个 `workflow_version` 的 `(tenant_id, id, definition_id)`；
- occurrence 的 `(tenant_id, trigger_config_id, timezone, misfire_policy)` 必须引用 Cron trigger 的同一份时区/misfire 快照。Manual/HTTP trigger 的两个快照字段为 NULL，而 occurrence 要求非 NULL，因此不能冒充 Cron 调度。

V1、V2 未被修改。已部署环境发现缺陷时只能新增 V4+ 前滚迁移。

## 2. 表与数据库不变量

| 表 | 已落库状态/字段约束 | 关键引用与唯一性 | 删除/保留 |
|---|---|---|---|
| `workflow_instance` | CREATED、运行/请求态和终态分别约束 started/completed；摘要为小写 SHA-256；traceId 为非零 32 位小写十六进制；审计/生命周期时间有序 | Definition 与 Version 三元一致；tenant+id 唯一 | 无运行角色 DELETE；按租户策略归档/清理 |
| `node_instance` | 10 个稳定状态；只有 READY/RETRY_WAIT/WAITING_TIMER 携带 next_schedule；只有 DISPATCHED/RUNNING 携带成对且未过期的 worker/lease；输出引用/摘要配对；错误字段只存在于 FAILED/RETRY_WAIT | instance+node_id 唯一；复合 tenant FK | 随 instance 保留；MVP 不分区 |
| `task_execution` | 只能以 RUNNING/version=0 插入；只允许一次 RUNNING→终态且 version 恰好 +1；终态字段、时间、输入输出摘要受 CHECK 约束 | node+attempt 与 tenant+execution_id 唯一 | 终态不可改写/软删；受控归档清理 |
| `schedule_occurrence` | PENDING/CLAIMED/FIRED/SKIPPED/FAILED 的 claimed/instance 字段严格对应；claimed 不早于 scheduled；timezone/misfire 非空且稳定 | trigger+scheduled_at 去重；Cron 快照复合 FK | 超过 catch-up/恢复窗口后受控归档/清理 |

这些约束只建立可运行的数据基础，不等同于状态机执行器已经实现。完整允许迁移图、DAG 推进、Cron 计算、DST/misfire 执行、暂停/恢复/取消事务和 Human Task 流程仍由 G4/G5/G7 完成。

## 3. Task 单次终态收口

`task_execution` 不能复用“完全不可变”的 V2 trigger，因为 Worker 必须把已打开的 attempt 从 RUNNING 收口到一个终态。`guard_task_execution_finalization()` 使用以下规则：

1. INSERT 只接受 `RUNNING` 且 `version = 0`；禁止直接伪造终态历史。
2. UPDATE 只接受旧状态 RUNNING、新状态为 SUCCEEDED/FAILED/TIMED_OUT/ABANDONED/CANCELLED。
3. version 必须恰好 `old + 1`，updated_at 不得倒退。
4. id、tenant、node、attempt、execution、started、输入快照和创建审计字段不得改变。
5. DELETE 以及任何终态二次 UPDATE 均以 SQLSTATE `55000` 拒绝。

函数为 `SECURITY INVOKER`，固定 `search_path = pg_catalog, pg_temp`，PUBLIC、应用角色和平台管理员均无直接 EXECUTE。真实 PostgreSQL 测试覆盖应用角色、平台管理员和表 owner，证明权限差异不能绕过终态保护。

## 4. RLS 与最小权限

四表全部执行 `ENABLE ROW LEVEL SECURITY` 和 `FORCE ROW LEVEL SECURITY`。policy 的 USING/WITH CHECK 都要求 `tenant_id = app_tenant_id()`：

- 无 tenant context 时普通应用和平台管理员均看不到运行态行；
- 设置事务级 context 后只能访问当前 tenant；
- 跨租户读、写和复合引用均被 RLS/FK 拒绝；
- `workflow_app` 与 `workflow_platform_admin` 只获得 SELECT/INSERT/UPDATE，没有 DELETE、DDL、BYPASSRLS 或对象所有权。

Worker/Scheduler 的专用服务角色和更细的列/操作权限由 G4/G6 接入；V3 不提前创建未定义身份，也不声称应用 RBAC 已完成。

## 5. 调度与租约索引

强制 RLS 查询要求事务内先设置 tenant context，因此高竞争扫描索引以 tenant 开头：

- `idx_node_instance_schedule (tenant_id,status,next_schedule_at,id)`，只含 READY/RETRY_WAIT/WAITING_TIMER；
- `idx_node_instance_lease_expiry (tenant_id,lease_expire_at,id)`，只含 DISPATCHED/RUNNING；
- `idx_schedule_occurrence_due (tenant_id,status,scheduled_at,id)`，只含 PENDING。

这些索引锁定 Q-03/Q-04/Q-06 查询契约；G2-11 仍需用代表性数据和 `EXPLAIN` 验证选择性、`SKIP LOCKED` 行为与写放大，当前不伪造性能数字。

## 6. 升级与验收

G2-06 集成套件先迁移到 V2，使用应用角色写入双租户 tenant/project、definition/version、Cron trigger、connector 和 secret_reference，再升级 V3 并重复 migrate。目录测试验证 V2 数据无损、历史仅包含 V1/V2/V3、对象 owner/ACL/RLS/trigger/constraint/index 与机器模型一致。

```powershell
pnpm er:verify
pnpm er:test
pnpm database:g2-06:verify
pnpm database:g2-06:test
pnpm database:g2-04:integration
pnpm database:g2-05:integration
pnpm database:g2-06:integration
pnpm migrations:verify
pnpm migrations:test
```

测试容器使用随机加密凭据和 `workflow-g2-06-*` 随机资源名，结束后自动清理，不挂载现有 Compose 数据卷。Flyway 11.7.2 在 PostgreSQL 18.4 实测通过，但 CLI 声明的已测试上限仍为 PostgreSQL 17；兼容矩阵和查询计划由 G2-11 继续复审。

## 7. 未实现边界

- 未实现实例触发/查询 API、DAG 推进、完整状态迁移服务或乐观锁写入协议；这些属于 G4。
- 未实现 Cron 表达式计算、DST/misfire 执行、积压上限、禁止重叠或调度事件；这些属于 G4-15。
- 未实现独立 worker_lease、心跳、fencing、Outbox、去重、死信与重放；这些属于 G2-07/G4/G5。
- 未实现对象存储上传/授权、Payload 大小阈值和归档作业；V3 仅保存受约束的 ref/digest。
- `WAITING_APPROVAL` 只是节点状态基础；human_task、决策、候选人、超时和审计属于 G2-08/G7。
