# G4-01 Runtime 状态机与合法迁移矩阵验收证据

## 1. 任务

- 任务：G4-01 编写 ADR-003：实例/节点/attempt 状态机及所有允许迁移
- Requirement：`REQ-STATE-001`、`REQ-RETRY-001`、`REQ-CRON-001`
- 完成日期：2026-07-31
- 状态：`[x]`（开发源、桌面完整根门禁与逐文件一致性均已验收）

## 2. 开发前审计与优化

G2-06 V3 迁移已经建立四张 Runtime/Schedule 表、状态字段 CHECK、时间/租约/摘要约束和 Task 单次终态保护，但审计发现：

1. `workflow_instance` 与 `node_instance` 没有统一的 from/to 迁移图，应用可以在字段自洽的前提下跳过暂停/取消请求阶段；
2. `expected status + version`、事务审计和 Outbox 只写在 ADR-002 的原则里，尚未成为 G4-02 必须消费的机器契约；
3. `READY`、`RETRY_WAIT`、`WAITING_TIMER` 的 `next_schedule_at` 以及 `DISPATCHED/RUNNING` 的 lease 字段存在状态相关 CHECK，若迁移效果不显式设置/清除，会把后续推进器实现推入数据库冲突；
4. retry、迟到 fencing token、终态二次写和 schedule occurrence 领取边界容易由 Worker/Scheduler 各自解释。

本步补齐机器矩阵和 ADR-003，同时明确 G4-02、G4-05、G4-06、G4-09、G4-15、G4-16 的责任边界，避免把“状态定义完成”误报成“Runtime 执行内核完成”。

## 3. 实现结果

### 3.1 机器可读矩阵

`config/runtime/state-machine.json` 固定 4 个实体、45 条迁移：

- Instance：8 个状态、11 条迁移；`CREATED -> RUNNING`，暂停/取消请求与异步收口，`SUCCEEDED/FAILED/CANCELLED` 终态；
- Node：10 个状态、24 条迁移；`PENDING -> READY -> DISPATCHED -> RUNNING`，运行中租约到期可恢复到 `READY`，并支持重试/定时/审批等待和取消；
- Attempt：6 个状态、5 条迁移；只能从 `RUNNING` 进入 `SUCCEEDED/FAILED/TIMED_OUT/ABANDONED/CANCELLED`；
- Schedule occurrence：5 个状态、5 条迁移；`PENDING -> CLAIMED/SKIPPED`、`CLAIMED -> FIRED/FAILED/SKIPPED`。

每条迁移均要求 expected status/version、actor、guard 和 side effect。矩阵额外锁定：terminal 无 outgoing transition、版本原子加一、非法迁移 `INVALID_STATE_TRANSITION`、并发陈旧写 `VERSION_CONFLICT`、数据库权威和状态/审计/Outbox 同事务。

### 3.2 V3 字段一致性补强

- 进入 `READY/RETRY_WAIT/WAITING_TIMER` 必须设置 `next_schedule_at`；
- 进入 `DISPATCHED/WAITING_APPROVAL` 或终态必须清除不应存在的 schedule 字段；
- 离开 `DISPATCHED/RUNNING` 必须清除 lease，进入 `RUNNING` 保留当前 lease/fencing；
- attempt 历史终态不可改写，retry 只能创建新 attempt。

### 3.3 ADR 与可读架构

ADR-003 记录条件更新 SQL、0 行结果分类、actor/tenant/trace 边界、实例/节点/attempt/occurrence 迁移表、并发故障矩阵、观测低基数约束和后续 G4 任务分界。架构索引新增 Runtime 状态机页面和 ADR-003 链接。

## 4. 关键文件

- `config/runtime/state-machine.json`
- `docs/adr/ADR-003-runtime-instance-node-attempt-state-machines.md`
- `docs/architecture/runtime-state-machines.md`
- `scripts/runtime/verify-state-machine.mjs`
- `scripts/runtime/test-state-machine-verifier.mjs`
- `config/traceability/requirements.json`
- `docs/planning/03-需求追踪矩阵.md`

## 5. 自动化验收

### 5.1 G4-01 与追踪门禁

```text
G4-01 state machine verification PASS: 4 entities, 45 explicit transitions, expected status/version protocol, terminal guards, transactional audit/outbox boundary and ADR-003 traceability are consistent.
G4-01 verifier negative tests PASS: 20/20 mutations rejected.
Traceability verification PASS: requirements=36, controlTasks=123/123, mvpGates=10/10.
Traceability verifier tests PASS: positive baseline accepted; reverse controls passed.
```

反向变异覆盖 protocol version/error、invariant、四类实体状态/迁移/终态、字段 side effect、source binding、ADR/架构索引/需求矩阵/V3 状态源、根脚本和 REQ-STATE-001 证据。

### 5.2 Maven 五模块基线

Java 21.0.12 / Maven 3.9.16 离线 `clean verify`：shared-contracts 16、control-plane 139 unit + 15 integration、worker 1、test-support 2，共 173 项，0 失败、0 错误、0 跳过。Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 全绿；control-plane 覆盖率维持 88.60% 指令、70.42% 分支、87.58% 行。

### 5.3 桌面完整根门禁

桌面交付目录执行 `pnpm verify` 全量通过，包含架构、领域约定、Problem Details、公共契约、G3-01～G3-10、G4-01、ER/Flyway/Testcontainers、治理、追踪、配置、Secret、前端 lint/typecheck/test/build：

```text
G4-01 state machine verification PASS: 4 entities, 45 explicit transitions.
G4-01 verifier negative tests PASS: 20/20 mutations rejected.
Secret verification PASS: 463 commit candidates clean; synthetic hard-coded secret blocked.
Test Files 2 passed (2); Tests 3 passed (3).
vite build: 1616 modules transformed; built successfully.
pnpm verify exit code: 0
```

最终同步采用非删除式复制，明确排除 `.git`、`.env`、`node_modules`、`target`、`dist`，保留桌面独有规划材料。最终 SHA-256 对账结果：开发源共享文件 459 个、桌面交付文件 463 个，缺失 0、不一致 0；桌面额外 4 个文件均为根目录/`docs/planning` 下的原始大纲、审计基线和总控清单。工作区、桌面根目录及桌面 `docs/planning` 三份总控清单哈希一致：

```text
6DDDECE80431D33CA02F369B43C4B22C6614E3ACE35B40F24B2E90B87BD6CF60
```

## 6. 未实现边界

- G4-02 实现统一状态迁移组件和同事务审计/Outbox；
- G4-05 实现 DAG 前驱判定和 `PENDING -> READY` 推进；
- G4-06/G4-15 实现调度扫描、Cron/DST/misfire、catch-up 和禁止重叠；
- G4-09/G5 实现 claim、heartbeat、lease generation/fencing 和结果协议；
- G4-16 实现实例/节点/attempt 稳定游标分页。

## 7. 结论

G4-01 的状态定义、迁移矩阵和 ADR 已完成，开发源、Maven 基线、桌面完整根门禁、Secret 扫描、最终非删除式同步及 SHA-256 对账均已通过。下一步进入 G4-02。
