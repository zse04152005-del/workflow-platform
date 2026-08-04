# 工作流定义鲁棒性与兼容性回归套件

## 1. 目的与边界

- 任务：G3-10
- Requirement：`REQ-DSL-001`、`REQ-DEF-001`
- 机器契约：`config/definition/robustness-suite.json`
- 固定种子：`30310`
- 状态：Accepted

本套件补足 G3-02～G3-09 的组合测试空白：同一个确定性编译链在输入排列变化、生成图、冻结 DSL v1 历史语料、恶意表达式和真实 PostgreSQL 高竞争下仍保持相同语义。它验证正确性和可重复性，不替代 G10 的容量、压测、长稳或故障注入。

## 2. 固定种子生成策略

生成器使用 JDK 自带的 `java.util.Random(30310)`，不依赖网络或随机测试框架。失败时只需保留任务版本、种子和案例索引即可复现；修改生成规模或种子必须同时修改机器契约、Java 测试、反向门禁和本文。

| 测试域 | 规模 | 必须保持的属性 |
|---|---:|---|
| JSON 键序和等价数字排列 | 64 | canonical 与 checksum 不变；canonical 再解析幂等 |
| 线性 DAG | 48 | 2～500 节点；确定性拓扑序；最大深度无递归栈风险 |
| 有向环变异 | 16 | 两次编译都以相同 `CYCLE_NOT_ALLOWED` 违规集合拒绝 |

解析组合语料至少包含 9 类攻击：重复 BOM、重复键、NUL、拼接多文档、非法 UTF-8、YAML alias、merge key、自定义 tag 和 65 层深度输入。失败消息不得回显标记 payload。

## 3. DSL v1 冻结兼容基线

三个历史 fixture 作为 append-only 的 DSL v1 语料。测试使用生产解析器重新计算 canonical checksum，再执行图、节点配置和表达式编译：

| Fixture | canonical checksum |
|---|---|
| `v1-minimal.json` | `cb3b9f4cf6e2d7ba19da1a94421a4e1176c161bd1175bfd00d7ecbcf1655607a` |
| `v1-root-extension.json` | `577ef1eef8e139a20316fbd574beeaa3f404fc04d6e6b735d7c556165fc7bd34` |
| `v1-condition.json` | `bef2fc4af7a2e714f4a0e2e7dec30d03bb7d76255a4cde05695810b242c9cf90` |

兼容策略为：未知可选根字段进入 canonical 文档并在运行时忽略；`x-vendor` 证明扩展可保留；没有显式 upcaster 时拒绝未来 `schemaVersion`。冻结 checksum 的变化属于兼容性事件，不能用“更新期望值”掩盖行为漂移。公共 OpenAPI/Schema 兼容基线本身仍保持 `schemaVersion: 1` 和 `contractVersion: 1.0.0`。

## 4. 恶意表达式与预算

CEL 反例不少于 13 项，覆盖函数/方法调用、Runtime/System 环境访问、反射形状、非确定性标识符、对象创建、三元/语句/列表和宏。JSONPath 反例不少于 10 项，覆盖递归下降、通配符、filter、slice、union、script、非法根和负索引。

另有 3 个生成式预算炸弹分别触发 token、AST/nesting 和 path segment 上限。所有案例只允许稳定的编译失败枚举与字段定位，不得执行表达式，也不得把攻击 payload、环境变量或 Secret 写入异常消息。

## 5. PostgreSQL 高竞争更新

`JdbcWorkflowDraftStoreIT` 使用固定 digest 的 PostgreSQL 18.4 Testcontainers 镜像。8 个 writer 各持有独立 Idempotency-Key，但共享同一强 ETag；`CountDownLatch` 保证请求在同一 start barrier 后竞争：

1. 只允许 1 个更新提交；
2. 其余 7 个以 `PRECONDITION_FAILED`（HTTP 412 语义）结束；
3. draft revision 只增加 1；
4. 只留下 1 条 `COMPLETED` 幂等记录；
5. 失败事务的 claim 必须回滚，`PROCESSING` 数量为 0。

该案例证明“强 ETag 比较、草稿写入、revision 更新和幂等 claim”属于同一事务边界。30 秒只用于阻止测试永久挂起，不是性能 SLO。

## 6. 机器门禁与复现

```powershell
pnpm definition:g3-10:verify
pnpm definition:g3-10:test
./mvnw.cmd -o -pl control-plane -am -Dtest=WorkflowDefinitionRobustnessTests -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw.cmd -o -pl control-plane -am -Dit.test=JdbcWorkflowDraftStoreIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

正向门禁校验生成规模、冻结 checksum、攻击族、源码绑定、兼容基线、追踪证据和根 `pnpm verify` 绑定。反向测试必须拒绝种子、规模、兼容策略、checksum、攻击数量、8 writer 成败不变量、幂等回滚、包脚本和 Requirement 追踪的漂移。

## 7. 演进规则与遗留边界

- 新增 DSL v1 fixture 时只能追加，并先确认其 canonical checksum；已有 fixture 不得静默重写。
- 修复生产缺陷时，先加入能固定复现的最小反例，再修改实现；固定种子并不禁止追加显式回归语料。
- DSL major 升级必须引入单独的 v2 fixture/upcaster 策略，不能把 v1 期望直接改成 v2。
- G3-10 不宣称 Kafka 运行链路、长时间并发、吞吐或灾备已完成；这些分别属于 G4/G5 和 G10。
- 下一步 G4-01：定义 Runtime 状态机与合法迁移矩阵，并继续沿用确定性错误和可审计门禁原则。
