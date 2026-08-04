# G2-01 上下文图、容器图与模块依赖图验收记录

- 状态：`[x]`
- 完成日期：2026-07-30
- Requirement：`REQ-ARCH-001`（状态由 `planned` 更新为 `partial`；G11-03 最终架构图包仍为 planned）
- 权威项目目录：`C:\Users\Administrator\Desktop\workflow-platform`

## 1. 实现内容

建立 `config/architecture/model.json` 机器权威模型，并据此形成可版本管理的 Mermaid/C4 架构视图：

| 视图/约束 | 覆盖 |
|---|---:|
| 人员角色 | 6 |
| 外部系统 | 4 |
| 容器/基础设施 | 10 |
| 容器协议关系 | 20 |
| Control Plane 业务模块 | 7 |
| 数据所有权集合 | 8 |
| 明确禁止依赖 | 7 |
| 权威状态存储 | 1（仅 PostgreSQL） |

关键决策：

- 区分 `scaffolded`、`local-compose-available`、`planned`，不把目标架构冒充当前实现。
- Control Plane、Worker、Web Console 是三个自研部署单元；`shared-contracts`/`test-support` 不是部署单元。
- PostgreSQL 是唯一事实源；Kafka 只负责至少一次通知，Redis 只做可重建缓存/限流，MinIO 保存大对象。
- Worker 必须通过数据库租约/fencing 协议执行，Control Plane 与 Worker 禁止编译依赖。
- 七个控制面模块的允许依赖与源码 `@ModuleBoundary` 声明逐项比对并验证无环。
- 数据具备单一 owner；Worker 只对 runtime 执行状态具有受限条件写协议，不能写其他模块数据。
- `outbox_event` 由无业务规则的 platform adapter 通过事务绑定 OutboxPort 管理，禁止业务模块绕过 Outbox 直接发 Kafka。

## 2. 关键文件

| 文件 | 用途 |
|---|---|
| `config/architecture/model.json` | Actor、Container、协议、状态、模块依赖、数据 owner、禁止依赖的机器模型 |
| `docs/architecture/c4-architecture-views.md` | C4 Level 1/2、模块依赖 Mermaid、数据所有权、协议和失败语义 |
| `docs/architecture/README.md` | 架构文档索引与维护入口 |
| `scripts/architecture/verify-architecture-model.mjs` | 模型/源码/文档一致性和关键不变量校验 |
| `scripts/architecture/test-architecture-verifier.mjs` | 正向模型与九类反向控制 |
| `config/traceability/requirements.json` | 将 REQ-ARCH-001 的 G2-01 事件/测试/文档证据更新为真实文件 |
| `package.json` | 增加 `architecture:verify/test` 并接入 `pnpm verify`/CI |

## 3. 正反向验证

正向模型必须同时满足：所有 ID 唯一、边端点存在、协议/信任边界完整、PostgreSQL 单一事实源、关键容器边存在、模块图无环且与源码一致、数据 owner/writer 有效、FD-01～FD-07 完整、三幅 Mermaid 视图和索引存在。

以下九类反例均被自动阻断：

1. 重复 Container ID。
2. 指向不存在容器的悬空关系。
3. Kafka 被错误标记为第二事实源。
4. 删除 Worker -> PostgreSQL 租约协议边。
5. 制造 security -> runtime -> security 模块依赖环。
6. 模型依赖与 `package-info.java` 声明漂移。
7. 数据 owner 指向未知模块。
8. 删除 FD-07 禁止依赖规则。
9. 架构图文档路径不存在。

## 4. 验收命令与结果

在桌面权威目录执行：

```powershell
pnpm run architecture:verify
pnpm run architecture:test
pnpm run verify
./mvnw.cmd -B -ntp -o -pl control-plane -am test
pnpm run secrets:verify
```

结果：

- Architecture：PASS；6 人、4 外部系统、10 容器、20 协议边、7 模块、8 数据集合、7 禁止依赖，唯一事实源为 PostgreSQL。
- Architecture 反向测试：PASS；上述九类错误全部被阻断。
- `pnpm verify`：PASS；架构、治理、追踪、配置、Secret、Web lint/类型、3 项前端测试和生产构建全部通过。
- Maven 定向离线测试：BUILD SUCCESS；Shared Contracts 1 项 + Control Plane 10 项，共 11 项，Control Plane 中 9 项 ArchUnit 测试全绿。
- 最终 Gitleaks：145 个 Git 提交候选文件无泄漏，合成硬编码密码反例被阻断。

关键输出：

```text
Architecture verification PASS: people=6, externalSystems=4, containers=10, containerRelationships=20, modules=7, datasets=8, forbiddenDependencies=7, authoritativeStore=postgres.
Architecture verifier tests PASS: positive model accepted; duplicate ID, dangling edge, second truth store, missing edge, dependency cycle/drift, unknown owner, missing forbidden rule, and missing document blocked.
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 (control-plane)
BUILD SUCCESS
Secret verification PASS: 145 commit candidates clean; synthetic hard-coded secret blocked.
```

## 5. 验收结论与遗留风险

G2-01 验收通过。上下文、容器、同步/异步协议、模块依赖、数据所有权和禁止依赖均已有可读图和机器门禁，追踪矩阵已引用真实证据。

遗留边界：本任务锁定能力级数据 owner，不锁定最终列、外键、索引和 RLS；这些由 G2-02/G2-03 评审。OpenTelemetry Collector 与观测后端明确标记为 `planned`，不能视为已部署。G11-03 仍需发布前汇总最终架构图与至少五份 ADR，因此 `REQ-ARCH-001` 保持 `partial`。

下一步：G2-02——定义 ID、时间、时区、枚举、错误码、审计字段和软删除策略。
