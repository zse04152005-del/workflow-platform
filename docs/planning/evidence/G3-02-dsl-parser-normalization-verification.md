# G3-02 DSL 解析、规范化与 checksum 验收证据

状态：PASS  
完成日期：2026-07-31  
总控任务：G3-02  
需求：REQ-DSL-001

## 1. 验收结论

G3-02 已完成 JSON/YAML 显式格式解析、严格 UTF-8、单文档和根 object 门禁、canonical JSON v1、lowercase SHA-256、G3-01 核心模型映射，以及输入/流/canonical 输出资源限制。实现和测试均未提前声明 G3-03 图语义、G3-04 节点专属权限或 G3-05 表达式沙箱能力。

同语义 JSON/YAML fixture 产生完全相同的 canonical JSON 与 checksum；object key 和 mapping target 使用 Java String 自然序，array 保持顺序；整数/小数不经过浮点数，末尾零被移除，负零归一为 0，科学计数法以有界流式方式展开为 plain decimal；Unicode 不做 normalization。

## 2. 审核中发现并关闭的问题

| 问题 | 风险 | 修正与证据 |
|---|---|---|
| Jackson `readValues` 会把根数组按值序列处理 | 空根数组被误报 `empty_input`，根类型契约失真 | 改用 `JsonParser` 读取唯一完整根树；JSON/YAML 根数组测试固定为 `root_not_object` |
| 只把 LoaderOptions 交给 YAML 流解析器不足以拒绝集合别名 | 锚点/别名可绕过预期安全限制 | 增加 SnakeYAML SafeConstructor 安全预检，共用 aliases=0、递归键/tag/depth/code point 策略；恶意 YAML 测试通过 |
| 先生成完整 canonical byte array 再检查 1 MiB | 短科学计数法输入可放大为超大 plain decimal，形成内存耗尽窗口 | 实现 `BoundedCanonicalOutput`，写入和零扩展前预留预算，超过上限立即以 `canonical_output_too_large` 终止 |
| Maven PMD 首轮报告 46 项规范问题 | 值对象意图、enum switch、测试断言可读性不满足质量基线 | 逐项修正，未降低规则；最终 PMD/CPD/SpotBugs/Checkstyle/Spotless/JaCoCo 全部 PASS |
| 系统 PATH 为 Node 22.12.0，而项目声明 Node 24.14.0 | 低版本“碰巧通过”不能作为正式工具链证据 | 定位捆绑 Node 24.14.0，并用 Node 24.14.0 + pnpm 11.9.0 完整重跑 `pnpm verify`，结果 PASS |

## 3. 固定契约

机器事实源：`config/dsl/parser-normalization.json`。

- 格式：显式 `json` / `yaml`；不自动猜测；
- 原始输入：1～1,048,576 bytes，严格 UTF-8，可选一个 BOM；
- 文档：恰好一个，根必须为 object，JSON/YAML 重复键均拒绝；
- 流约束：深度 64、字符 1,048,576、Token 200,000、数字 100、字符串 262,144、字段名 200；
- YAML：集合别名 0、递归键 false、merge compose false、自定义全局 tag false、boolean-like words 按字符串；
- canonical：object key 自然序、array 原序、任意精度 plain decimal、负零为零、无空白、无 Unicode normalization；
- canonical 输出：最多 1,048,576 UTF-8 bytes，边写边限流；
- checksum：canonical UTF-8 bytes 的 SHA-256，小写 64 位十六进制；
- 稳定失败码：9 个，对外消息不含 Payload 或第三方解析器原始消息。

## 4. 关键实现与测试文件

- `config/dsl/parser-normalization.json`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/JacksonWorkflowDefinitionParser.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowDefinitionCanonicalizer.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowDefinitionModelMapper.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/ParsedWorkflowDefinition.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowDefinitionParserTests.java`
- `tests/contracts/dsl/parser`
- `scripts/dsl/verify-dsl-parser-normalization.mjs`
- `scripts/dsl/test-dsl-parser-normalization-verifier.mjs`
- `docs/architecture/dsl-parser-normalization.md`

## 5. 自动化验收结果

| 门禁 | 结果 |
|---|---|
| `pnpm dsl:g3-02:verify` | PASS：JSON/YAML、有界 UTF-8、canonical JSON、SHA-256、9 个失败码、21 项 Java 用例和延期边界一致 |
| `pnpm dsl:g3-02:test` | PASS：正向基线 + 28 类反向变异全部被拒绝 |
| `pnpm dsl:g3-01:verify/test` | PASS：G3-01 核心模型与 18 类反向控制无回归 |
| `pnpm contracts:public:verify/test` | PASS：OpenAPI/DSL/Event 公共契约与 22 类反向控制无回归 |
| `pnpm traceability:verify/test` | PASS：36 个 Requirement、123/123 任务、10/10 MVP Gate |
| `pnpm verify`（Node 24.14.0、pnpm 11.9.0） | PASS：架构、领域、错误、公共契约、G3-01/G3-02、ER/迁移、治理、追踪、配置、Secret、前端 lint/typecheck/test/build 全绿 |
| `pnpm migrations:verify/test` | PASS：5 个迁移、版本顺序和 SHA-256 catalog；未登记与篡改反例被拒绝 |
| `pnpm compose:verify` | PASS：7 个固定 digest 服务、5 个 healthcheck、2 个 one-shot initializer、端口仅 loopback |
| `pnpm ci:verify` | PASS：4 个 Job、9 个 SHA 固定 Action、最小权限、actionlint |
| Maven 五模块联网 `clean verify` | PASS |
| Maven 五模块离线 `-o clean verify` | PASS |
| Java 测试汇总 | 68 项，0 failures / 0 errors / 0 skipped；其中 G3-02 Java 21 项，PostgreSQL 18.4 集成 4 项 |
| Java 质量 | Spotless、Checkstyle、PMD、CPD、SpotBugs、JaCoCo 全部 PASS |
| Secret | PASS：最终 288 个提交候选无 Secret，合成硬编码 Secret 被拒绝；Gitleaks 镜像固定 digest |

## 6. 桌面独立复验与一致性

交付目录：`C:\Users\Administrator\Desktop\workflow-platform`。

- 使用 Robocopy `/E` 增量同步，排除 `.git`、`.env`、`node_modules`、`target`、`dist`，不使用 `/MIR`；
- 桌面额外的 `00-原始项目开发大纲.md`、`01-项目大纲审计与优化基线.md`、`02-项目开发总控清单.md` 全部保留；
- 直接在桌面副本运行 G3-02 正向门禁、28 类反向变异和 Maven 离线 `-pl control-plane -am test`，61 项单测全部通过；
- 最终逐文件 SHA-256 审计覆盖 288 个共享交付文件：工作镜像与桌面副本 0 missing、0 mismatch；桌面仅保留三份预期规划基线作为额外文件。

## 7. 遗留边界

- G3-03 负责唯一 Start、节点/边 ID、引用存在、可达性和非法环；G3-02 测试明确允许重复 Start 与悬空边进入下一编译阶段；
- G3-04 负责节点专属参数、connector/secret 引用与权限；
- G3-05 负责 CEL/JSONPath 语法、类型和资源沙箱；
- G3-10 扩展跨版本属性测试和更大恶意输入组合；
- Flyway 11.7.2 对 PostgreSQL 18.4 仍给出支持上限提示，继续由既有兼容性治理跟踪，不影响本任务解析器验收。

下一步：G3-03 图校验。
