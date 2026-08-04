# G3-01 DSL 核心模型验收证据

- 验收日期：2026-07-31
- 状态：PASS
- 目标：固定 DSL `schemaVersion`、节点公共字段、边、参数、重试和超时模型，不虚报后续编译能力

## 1. 交付清单

- 机器契约：`config/dsl/core-model.json`
- DSL Schema：`schemas/dsl/workflow-definition-v1.schema.json`
- Java 模型：`shared-contracts/src/main/java/com/workflowplatform/contracts/dsl`
- Java 测试：`shared-contracts/src/test/java/com/workflowplatform/contracts/dsl/WorkflowDslCoreModelTests.java`
- 正反 fixture：`tests/contracts/dsl/valid`、`tests/contracts/dsl/invalid`
- 静态门禁：`scripts/dsl/verify-dsl-core-model.mjs`
- 反向控制：`scripts/dsl/test-dsl-core-model-verifier.mjs`
- 架构说明：`docs/architecture/dsl-core-model.md`
- 需求追踪：`config/traceability/requirements.json`、`docs/planning/03-需求追踪矩阵.md`

## 2. 已实现能力

- 冻结 `schemaVersion = 1`，根定义绑定 `catalogVersion`，每个节点绑定 `executorVersion`；catalog/executor 版本必须大于等于 1。
- 固定 6 类节点、3 类触发器及节点 `id/type/executorVersion/displayName` 公共字段；任意脚本节点保持禁止。
- 固定边 `id/from/to/on/when/mapping`，4 类 transition，condition 必须有 `when`、非 condition 禁止 `when`，单边最多 100 项映射且 Java 层拒绝重复 target。
- fixed/linear/exponential 三种重试策略已建模；`maxAttempts` 为 1～10，策略字段互斥，支持 `maxDelay`、`maxElapsedTime`、`jitterRatio`。
- Java 层对 timeout、initial/max delay、max elapsed time 和 linear increment 执行正值 Duration 校验；最长退避/重试窗口不得短于初始间隔。
- Workflow、Trigger、Node、Edge、Schema 与嵌套 JSON Map/List 均做深层不可变复制；公开 accessor 返回独立不可修改副本。
- 表达式形状继续阻断 now/random/System/Runtime/class 等确定性漂移入口；这不等同于 G3-05 的语法、类型与沙箱完成。

## 3. 自动化验收结果

```powershell
pnpm dsl:g3-01:verify
pnpm dsl:g3-01:test
pnpm contracts:public:verify
pnpm contracts:public:test
pnpm traceability:verify
pnpm traceability:test
mvn -pl shared-contracts -am verify
pnpm verify
pnpm migrations:verify
pnpm migrations:test
pnpm compose:verify
pnpm ci:verify
mvn clean verify
mvn -o clean verify
```

结果：

- G3-01 正向门禁 PASS：`schemaVersion 1`、6 节点、3 重试策略、3 个合法和 5 个非法 DSL fixture 全部符合预期。
- G3-01 反向门禁 PASS：版本、节点/策略集合、资源上限、条件边、mapping、表达式、Java 不变量、测试标记、文档与根命令共 18 类变异全部被拒绝。
- Java 模型测试 PASS：G3-01 新增 11 项；shared-contracts 合计 16 项，PMD/CPD、SpotBugs 0 告警，JaCoCo 门槛通过。
- 公共契约与追踪 PASS：22 类公共契约变异、5 类追踪反例全部被拒绝；36 个 Requirement、G2～G12 的 123/123 控制任务、MVP Gate 10/10 继续覆盖。
- 整仓 `pnpm verify` PASS：工作镜像 266 个提交候选无 Secret，前端 3 项测试、lint、typecheck 与生产构建通过。
- 迁移、Compose、CI 独立门禁 PASS：V1～V5 checksum、7 个固定 digest 服务/5 个 healthcheck/2 个初始化任务、4 个 CI Job/9 个 SHA 固定 Action 均未漂移。
- Maven 五模块联网和离线 `clean verify` 均 PASS：47 项测试、0 失败、0 错误、0 跳过；其中 PostgreSQL 18.4 Testcontainers 4/4 再次通过。

## 4. 桌面同步与独立复验

- 使用 Robocopy `/E` 同步至 `C:\Users\Administrator\Desktop\workflow-platform`，未使用 `/MIR`；首轮复制 26 个变化文件。
- 同步排除 `.git`、`.env`、`node_modules`、`target`、`dist`，桌面原有 `.git`、`node_modules`、`target` 均被保留。
- 桌面独立 `pnpm verify` PASS：269 个候选文件无 Secret，G3-01 正反门禁和前端 3 项测试/构建通过。
- 桌面独立 `mvn -pl shared-contracts -am verify` PASS：16 项测试、PMD/CPD、SpotBugs 与 JaCoCo 全部通过。
- 最终排除 `.git/.env/node_modules/target/dist` 后，工作镜像与桌面项目按相对路径和 SHA-256 逐文件审计；结果记录在本任务完成输出中。

## 5. 修正记录与遗留风险

实现期间真实发现并修正：

- AJV strict 模式需要与公共契约门禁一致地关闭 `strictRequired`，否则合法的跨层 `required` 规则会被错误拒绝。
- 首轮 Java `verify` 暴露 29 条 PMD 可维护性问题，已通过常量、命名和断言说明修正，没有降低规则。
- 第二轮 SpotBugs 不能静态证明 record 集合 accessor 的不可变性，已改为返回独立不可修改副本，最终 0 告警。

仍未实现且必须保持 planned：

- G3-02：JSON/YAML 解析、canonical JSON、checksum、字节大小限制和确定性 mapping 排序。
- G3-03：唯一 Start、ID 唯一、引用存在、可达性和环规则。
- G3-04：节点专属参数、connector/secret 引用和权限校验。
- G3-05：CEL/JSONPath 语法、类型与资源沙箱。
- Flyway 11.7.2 对 PostgreSQL 18.4 仍有“最高已测试 PostgreSQL 17”的既有告警；本任务未改变数据库版本或迁移。

G3-01 满足 Definition of Done，可以在总控清单标记 `[x]`；下一步进入 G3-02。
