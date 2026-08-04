# G5-09～G5-11 实例重放与控制验证证据

完成日期：2026-08-03

## 已完成范围

- G5-09：仅允许终态实例重放；支持创建全新实例，以及从 `FAILED`/`CANCELLED` 节点开始重放；源实例和 `task_execution` 历史不修改。
- G5-10：实现带强制原因和 `Idempotency-Key` 的暂停/恢复 API；暂停阻断新派发，等待 `DISPATCHED`/`RUNNING` 节点到达安全点，并保留 `WAITING_TIMER`。
- G5-11：实现协作式取消；未开始、待派发、重试、计时器和审批等待节点可取消，已运行节点允许完成并在下一次调度收敛时清理新产生的重试/等待状态。

公开 API：

- `POST /api/v1/instances/{instanceId}/pause`
- `POST /api/v1/instances/{instanceId}/resume`
- `POST /api/v1/instances/{instanceId}/cancel`
- `POST /api/v1/instances/{instanceId}/replay`

四条 API 均返回 `202 ActionAccepted`，要求 1～500 字符的安全原因和 8～128 字符的安全幂等键。幂等记录保留 24 小时；相同键和相同请求回放首次 action ID、接受时间和目标实例，相同键但请求摘要不同返回冲突。首次响应时间使用 ISO-8601 持久化，保留 PostgreSQL 微秒精度。

## 状态与事务不变量

- pause：`RUNNING -> PAUSE_REQUESTED -> PAUSED`；只有无 `DISPATCHED`/`RUNNING` 节点时进入 `PAUSED`。
- resume：`PAUSED -> RUNNING`，随后恢复到期任务派发资格。
- cancel：`RUNNING|PAUSE_REQUESTED|PAUSED -> CANCEL_REQUESTED -> CANCELLED`；不声称能撤销已经发生的 HTTP、消息或其他外部副作用。
- replay/new-instance：创建 `CREATED` 实例，所有节点为 `PENDING`。
- replay/failed-node：创建 `RUNNING` 实例，目标节点为 `READY`、下游为 `PENDING`、成功上游复制为 `SUCCEEDED`，只复制受控 payload reference/digest。
- 控制状态、审计、Outbox 和幂等首次响应在同一 PostgreSQL 事务内提交。
- RLS 下跨租户实例按不存在处理；失败操作的幂等 claim 随事务回滚。
- replay 和 cancel 的 PostgreSQL IT 在操作前后比较源 `task_execution` 快照，验证历史 attempt 行数和字段不变。

## 自动化证据

定向服务与控制器测试：

```text
RuntimeControlServiceTests       3/3
RuntimeControlControllerTests    2/2
```

真实 PostgreSQL 18.4 Testcontainers：

```text
JdbcRuntimeControlStoreIT        6/6
JdbcRuntimeSchedulingStoreIT    11/11
```

干净回归命令：

```powershell
.\mvnw.cmd -B -pl control-plane -am clean verify `
  '-Dit.test=JdbcRuntimeControlStoreIT,JdbcRuntimeSchedulingStoreIT' `
  '-Dfailsafe.failIfNoSpecifiedTests=false'
```

结果：共享契约单测 20/20、控制面单测 176/176、集成测试 17/17；Checkstyle、PMD、CPD、SpotBugs、JaCoCo 全部通过，三模块 Reactor `BUILD SUCCESS`。

契约门禁：

```text
pnpm run contracts:public:verify  PASS（21 operations）
pnpm run contracts:public:test    PASS（23 reverse mutations）
```

## 尚未由本证据关闭的范围

本证据不关闭 G5 整体 Gate。死信人工备注/再次派发、真实对象存储 Payload、故障注入和完整 G5 验收器仍分别由 G5-08、G5-12、G5-13 处理。Flyway 11.7.2 对 PostgreSQL 18.4 的支持上限告警仍作为既有兼容风险保留。
