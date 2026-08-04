# G5-12～G5-13 Payload 对象存储与故障注入验证证据

完成日期：2026-08-04

## 已完成范围

- G5-12：新增独立 `payload-storage` Maven 模块和真实 MinIO 实现；以 64 KiB 默认阈值、16 MiB 最大输入限制控制内联/外置，输入与超阈值 Worker 输出均使用 `s3://<bucket>/payload/v1/<tenant-id>/<sha256>` 引用。
- G5-12：对象读取同时校验租户路径、tenant/SHA-256/size/retention/archive-at/retain-until 元数据及内容摘要；稳定区分非法引用、跨租户访问、对象缺失、完整性失败和存储后端故障。
- G5-12：冻结 `transient-7d`、`runtime-30d`、`audit-365d` 三类策略；归档写入真实 MinIO object tag，保留期内拒绝删除，到期后执行真实删除。
- G5-13：注入真实 PostgreSQL Outbox publisher lease 到期重领、真实 Kafka 重复 task envelope、本地 TLS peer 接收后停滞、真实 PostgreSQL 容器 pause/unpause 四类故障。

## 关键不变量

- 小 Payload 可内联；超阈值 Payload 只在 PostgreSQL 保存引用与摘要，`input_payload` 置空，事件不携带大对象正文。
- Scheduler 条件计算和 Worker 执行前必须按事件租户读取对象并校验摘要；Start/End 可复用已验证对象引用，插件产生的超阈值 inline 输出在提交结果前外置化。
- Outbox 发布方在 broker 已接收但数据库未标记成功时，租约到期后以相同 event id 重领；消费者仍只推进一次。
- 同一 Kafka task envelope 发布两次时，attempt 和 `processed_event` 各只产生一次有效事实。
- 真实网络停滞只产生可重试超时分类，不产生成功输出。
- PostgreSQL 不可用期间 attempt、lease、audit、Outbox、`processed_event` 均无半提交；恢复后同一任务只成功一次。

## 自动化证据

真实 MinIO：

```text
MinioTenantPayloadStoreIT       3/3
```

Worker 与真实 PostgreSQL 故障专项：

```text
BuiltinWorkerNodeExecutorTests          5/5
ManagedHttpWorkerNodeExecutorTests      9/9
WorkerExecutionServiceTests            11/11
JdbcWorkerTaskStoreIT                    9/9
```

PostgreSQL + Kafka + MinIO 组合 E2E：

```text
RuntimeHappyPathE2EIT            2/2
```

组合 E2E 验证大输入真实外置、Scheduler/Worker 授权读取、完整流程推进和 Kafka 重复不重复执行。上述 Maven 验证的 Spotless、Checkstyle、PMD、CPD、SpotBugs 与 JaCoCo 均通过。

机器契约：

```text
node scripts/runtime/verify-reliability-kernel.mjs       PASS
node scripts/runtime/test-reliability-kernel-verifier.mjs PASS（17 个反向变异）
```

## 关键文件

- `payload-storage/src/main/java/com/workflowplatform/payload/MinioTenantPayloadStore.java`
- `payload-storage/src/main/java/com/workflowplatform/payload/PayloadRetentionPolicy.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/runtime/RuntimeTriggerService.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/runtime/internal/JdbcRuntimeSchedulingStore.java`
- `worker/src/main/java/com/workflowplatform/worker/WorkerExecutionService.java`
- `worker/src/main/java/com/workflowplatform/worker/internal/JdbcWorkerTaskStore.java`
- `tests/integration/fault-injection-matrix.json`
- `config/runtime/reliability-kernel.json`

## 后续边界

G5-12、G5-13 及 Gate G5 已闭环。`REQ-E2E-001` 仍保持 `partial`：订单/退款/告警三条完整演示流、Kafka 积压和更广泛的 G10 故障/容量场景继续由 G10-04～G10-05 验收，不影响本次四类故障注入结论。
