# G4-17 Runtime 快乐路径真实 E2E 验收

## 结论

G4-17 已完成并可标记为 `[x]`。测试不是内存替身：它使用 PostgreSQL 18.4 与 Kafka 4.3.1 Testcontainers、V1～V7 Flyway 迁移、真实应用角色/RLS、生产 Outbox/Kafka/Worker/调度/查询组件，完整跑通：

`HTTP Trigger -> Start -> Condition -> Managed HTTP -> Delay -> 控制面重建 -> Delay 恢复 -> End -> Instance SUCCEEDED`

## 关键断言

- 同一 Idempotency-Key 的 HTTP trigger 回放同一 instance，不重复推进；
- Condition 基于冻结输入选择分支，fallback 节点为 `CANCELLED`；
- HTTP endpoint 在触发时冻结，Header/Body 映射和结果摘要由生产执行器处理；
- Delay 保存 `next_schedule_at`，销毁并重建控制面上下文后继续执行；
- Outbox 经真实 Kafka 发送，Worker 消费并以数据库 processed-event 去重；
- 最终 6 个节点、6 个 attempt、6 个 processed event，实例状态为 `SUCCEEDED`；
- 查询服务只返回 payload reference/digest，不回传原始敏感输入。

受管 HTTP 使用生产策略、映射和结果提取组件；网络 exchange 在测试内返回确定响应，避免把第三方网络稳定性混入平台 E2E，同时仍执行 endpoint policy。

## 自动化证据

2026-08-03 实际执行：

```text
mvnw.cmd -B -pl test-support -am verify -Dit.test=RuntimeHappyPathE2EIT -Dfailsafe.failIfNoSpecifiedTests=false
pnpm run runtime:g4-17:verify
pnpm run runtime:g4-17:test
```

结果：五模块 `BUILD SUCCESS`；`RuntimeHappyPathE2EIT` 1/1 通过；PMD、CPD、SpotBugs、JaCoCo 全绿；G4-17 正向契约通过；3/3 假数据库、缺失重启和流程漂移负向篡改被拒绝。

G10 仍需补齐其余两条业务演示流和系统化故障注入；本证据只声明 G4-17 指定的单条真实快乐路径。
