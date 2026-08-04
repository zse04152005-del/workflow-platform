# G4-02 Runtime 统一状态迁移事务组件验收证据

## 1. 任务

- 任务：G4-02 实现状态迁移组件：expected status + version 乐观锁 + 同事务审计/Outbox
- Requirement：`REQ-STATE-001`
- 完成日期：2026-07-31
- 状态：`[x]`

## 2. 开发前审计与优化

G4-01 当前冻结四类实体、45 条边；第 45 条是 G5-06 租约恢复增补的 `RUNNING -> READY:lease_expired`。生产代码以唯一迁移入口解决矩阵外迁移、actor 越权、status/version 双条件乐观锁、0 行确定分类、状态相关字段副作用、Worker fencing、RLS tenant context、审计/Outbox 原子性和事件脱敏。只实现通用 `UPDATE status` 会在调度/恢复组件间形成第二套语义，因此统一事务端口仍是唯一入口。

## 3. 实现结果

- `RuntimeTransitionPolicy` 显式注册 45 条边及允许 actor，禁止 enum ordinal 推断；
- `RuntimeTransitionCommand/Effects` 强类型承载 expected status/version、reason code、trace、调度时间、lease/fencing、输出摘要和脱敏错误；
- `JdbcRuntimeTransitionStore` 对四表执行 tenant/id/status/version 条件更新，version 原子加一；
- 0 行按 NOT_FOUND、VERSION_CONFLICT、INVALID_STATE_TRANSITION、FENCING_TOKEN_CONFLICT 分类；
- 同一 `TransactionTemplate` 内写状态、append-only `audit_log` 和 unpublished `outbox_event`；
- UUIDv7、W3C trace context、低敏 payload 和错误摘要哈希遵循既有领域/事件约定。

## 4. 自动化验收

```text
Maven/JDK 21 control-plane -am verify PASS
RuntimeStateTransitionServiceTests: 4/4 PASS
JdbcRuntimeTransitionStoreIT (PostgreSQL 18.4): 8/8 PASS
PMD/CPD/SpotBugs/Checkstyle/Spotless/JaCoCo PASS
```

集成测试覆盖 instance/node/attempt/schedule、同版本双 writer 仅一项成功、旧版本稳定冲突、Worker 错误 generation 拒绝、跨租户 NOT_FOUND、不具备 Outbox INSERT 权限时状态与审计全部回滚。

## 5. 关键文件

- `config/runtime/transition-component.json`
- `control-plane/src/main/java/com/workflowplatform/controlplane/runtime/RuntimeStateTransitionService.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/runtime/RuntimeTransitionPolicy.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/runtime/internal/JdbcRuntimeTransitionStore.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/runtime/RuntimeStateTransitionServiceTests.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/runtime/internal/JdbcRuntimeTransitionStoreIT.java`
- `docs/architecture/runtime-state-transition-component.md`

桌面交付仓库补充验收：完整 `pnpm verify` PASS（含 Web Console lint/typecheck/test/build）；Secret 扫描 `480` 个提交候选无泄漏；开发源与桌面逐文件 SHA-256 对账 `476/480`，共享文件缺失 `0`、不一致 `0`，桌面独有规划文件 `4` 个。

## 6. 未实现边界

- G4-03/G4-04：触发 API、幂等回放和实例快照创建；
- G4-05/G4-06：DAG 推进及并发安全到期扫描；
- G4-07/G4-08：Outbox 发布与 Kafka 入口；
- G4-09：Worker lease/heartbeat 完整生命周期。

## 7. 结论

G4-02 的生产组件、PostgreSQL 专项测试、控制面依赖模块 Maven 门禁、静态质量门禁、桌面根 Node 门禁、Secret 扫描和逐文件 SHA-256 对账均已完成并标记 `[x]`，下一步进入 G4-03。
