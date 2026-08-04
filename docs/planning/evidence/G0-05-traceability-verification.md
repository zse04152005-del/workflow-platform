# G0-05 需求追踪矩阵验收记录

- 状态：`[x]`
- 完成日期：2026-07-30
- 任务：建立“需求 -> API/事件 -> 表 -> 测试 -> 文档”的双向追踪矩阵
- 权威项目目录：`C:\Users\Administrator\Desktop\workflow-platform`

## 1. 审阅范围与决策

完整复核以下三份基线文档后再建立矩阵：

- `docs/planning/00-原始项目开发大纲.md`：范围、角色、用例、DSL、服务、状态机、表、API、Topic、可靠性、安全、观测、测试、部署、里程碑与风险。
- `docs/planning/01-项目大纲审计与优化基线.md`：A-01～A-18 修订项、模块化 MVP、核心不变量、MVP/二期/不做边界及 10 项最终 Gate。
- `docs/planning/02-项目开发总控清单.md`：G2～G12 的 123 个后续任务。

采用以下防伪规则：

1. `config/traceability/requirements.json` 是机器权威源，Markdown 是人类可读视图。
2. 尚未产生真实实现的 API、事件、表、测试和文档均显式标为 `planned`；不创建虚假文件或接口。
3. `implemented` 必须包含 `file:<repository-path>`，且文件必须真实存在。
4. MVP 只能映射 G2～G11，二期只能映射 G12，明确不做项不得领取实现任务。
5. 五类产物映射均不可为空；不适用必须使用 `not-applicable` 并说明原因。

## 2. 交付内容

| 文件 | 用途 |
|---|---|
| `config/traceability/requirements.json` | 36 个稳定 Requirement ID 的机器源 |
| `docs/planning/03-需求追踪矩阵.md` | 人类可读的完整矩阵、覆盖摘要和十项 Gate 反向索引 |
| `scripts/planning/verify-traceability.mjs` | 唯一性、范围、五类产物、任务覆盖、Gate 覆盖和真实文件校验 |
| `scripts/planning/test-traceability-verifier.mjs` | 正向基线与五类反向控制测试 |
| `package.json` | 增加 `traceability:verify`、`traceability:test`，并接入 `pnpm verify`/CI 既有门禁 |

## 3. 覆盖结果

| 维度 | 结果 |
|---|---:|
| Requirement 总数 | 36 |
| MVP | 30 |
| 二期 | 5 |
| 明确不做 | 1 |
| G2～G12 任务覆盖 | 123/123 |
| 审计基线 MVP Gate 覆盖 | 10/10 |
| 每项强制产物维度 | API、事件、表、测试、文档，共 5 类 |

## 4. 验收命令与结果

在桌面权威目录执行：

```powershell
pnpm run traceability:verify
pnpm run traceability:test
pnpm run verify
```

结果：

- `traceability:verify`：PASS；36 个 Requirement、123/123 个总控任务、10/10 个 MVP Gate、5 类产物字段全部通过。
- `traceability:test`：PASS；正向基线被接受。
- 反向控制全部被阻断：重复 Requirement ID、遗漏 `G12-11`、空 API 映射、把二期需求错标为 MVP、声明不存在的 `implemented` 文件。
- `pnpm verify`：PASS；新增追踪门禁、配置门禁、Secret 扫描、Web lint、TypeScript、3 项前端测试和生产构建全部通过。
- Gitleaks：133 个 Git 提交候选文件无泄漏；合成硬编码密码反例被规则正确拦截。

关键输出：

```text
Traceability verification PASS: requirements=36 (mvp=30, phase2=5, out-of-scope=1), controlTasks=123/123, mvpGates=10/10, artifactFields=5.
Traceability verifier tests PASS: positive baseline accepted; duplicate ID, missing task, empty artifact, phase mismatch, and missing implemented file blocked.
Secret verification PASS: 133 commit candidates clean; synthetic hard-coded secret blocked.
Test Files  2 passed (2)
Tests       3 passed (3)
vite build: 1616 modules transformed; built successfully
```

## 5. 验收结论与遗留风险

G0-05 验收通过。需求、实现任务和五类交付证据之间已有可机器执行的防漏链路，G2～G12 不存在未归属任务，二期与明确不做边界不会混入 MVP。

当前矩阵描述的是规划基线，不能证明业务能力已实现；后续每完成一个任务，必须把相应产物从 `planned` 更新为真实文件引用并重新运行门禁。Hosted CI 仍需在未来建立远程仓库后首跑，本次只声明已执行的本地等价门禁。

下一步：G0-06——定义统一 Definition of Done、分支/提交规范和版本规则。
