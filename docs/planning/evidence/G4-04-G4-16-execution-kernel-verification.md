# G4-04～G4-16 持久化执行内核验收

## 结论

G4-04～G4-16 均已完成并可在总控清单标记为 `[x]`。生产路径以 PostgreSQL 为状态事实源，以事务 Outbox/Kafka 传递任务提示，Worker 领取数据库租约后执行；源码契约、真实数据库/Kafka 测试及 Java 全套质量门禁均通过。

## 已实现能力

- G4-04：实例冻结 workflow version、输入、摘要、business key、发起人和 trace context。
- G4-05/G4-06：DAG 前驱判定、条件分支取消、实例收敛，以及 READY/RETRY_WAIT/WAITING_TIMER 的 `FOR UPDATE SKIP LOCKED` 到期扫描。
- G4-07/G4-08：事务 Outbox、Kafka 发布、processed-event 去重、消费失败不确认和重启重试。
- G4-09/G4-10：数据库 claim/heartbeat、单调 lease generation/fencing，虚拟线程执行、30 分钟硬上限、输出摘要和脱敏错误分类。
- G4-11/G4-12：Start/End、确定性 Condition 求值、首个命中条件边、fallback 及未选分支 `CANCELLED`。
- G4-13：受管 HTTPS endpoint、逐跳 DNS/IP SSRF 防护、Header/Body 映射、结果提取、响应上限和 408/429/5xx 重试分类。
- G4-14：ISO-8601 `duration`、持久化 `next_schedule_at`，不使用进程内定时器。
- G4-15：显式时区、DST、SKIP/FIRE_ONCE/CATCH_UP_LIMITED、occurrence 唯一性和禁止重叠。
- G4-16：实例、节点、attempt 四条查询 API，`created_at DESC,id DESC` keyset cursor、200 行上限、RLS 和 payload 引用边界。

## 自动化证据

2026-08-03 实际执行：

```text
mvnw.cmd -B -pl worker -am verify
pnpm run runtime:g4-04-16:verify
pnpm run runtime:g4-04-16:test
```

结果：

- Shared Contracts 20 项单测通过；
- Control Plane 171 项单测通过；
- Worker 22 项单测与 6 项 PostgreSQL Testcontainers 集成测试通过；
- PMD、CPD、SpotBugs、JaCoCo 全部通过，未降低 60% 分支覆盖阈值；
- G4-04～G4-16 正向源码验收通过；
- 4/4 契约、虚拟线程边界、查询上限和 OpenAPI 漂移负向篡改被拒绝。

完整运行链路另见 `G4-17-runtime-happy-path-e2e.md`。G5 的 Worker kill、租约回收、死信和故障恢复仍按后续任务验收，不计入本阶段完成证据。
