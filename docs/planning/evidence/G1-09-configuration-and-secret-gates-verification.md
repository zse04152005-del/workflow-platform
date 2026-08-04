# G1-09 验收记录：配置分层与密钥扫描门禁

- 状态：PASS
- 验收日期：2026-07-30
- 权威目录：`C:\Users\Administrator\Desktop\workflow-platform`
- 对应任务：G1-09

## 交付内容

- 应用运行、Compose 基础设施引导、Vite 浏览器构建三类互不混用的 env 契约。
- 根 `.env.example` 应用运行配置，Compose/Web Console 保留各自作用域示例。
- control-plane、worker 的 `local`、`test`、`prod` 三层 Spring 配置，共 6 份 Profile 文件。
- 生产 Profile 的外部端点和敏感项只允许 `${ENV_VAR}` 注入，不提供本地/测试回退。
- 测试 Profile 使用明确的 `test-only` 值，并由 Spring 上下文测试验证实际加载。
- 40 个配置键的来源、使用者、默认值、敏感级别、覆盖规则和生产策略文档。
- 可执行配置漂移校验，新增/缺失/重复/空值、Vite 敏感键、生产回退、忽略规则漂移都会失败。
- Gitleaks 默认规则 + 项目通用硬编码 secret 赋值规则；扫描镜像固定 tag 和 multi-arch digest。
- 正向工作树扫描 + 临时合成硬编码密码反向测试，输出强制 100% redact。
- 配置和密钥门禁接入根 `pnpm verify`，贡献规范与 README 命令入口同步更新。

## 配置契约

| 配置域 | 示例文件 | 键数 | 敏感值策略 |
|---|---|---:|---|
| 后端应用运行 | `/.env.example` | 16 | 仅 `local-only-change-me` 本地占位值 |
| Compose 引导 | `/deploy/compose/.env.example` | 21 | 管理员/应用凭据分离，均明确本地专用 |
| 浏览器构建 | `/web-console/.env.example` | 3 | 禁止 password/secret/token/private key |

配置校验命令：

```powershell
pnpm config:verify
```

结果：退出码 `0`：

```text
Configuration verification PASS: 3 env contracts, 40 keys, 6 profile files, 2 no-fallback production profiles.
```

两份现有 Spring 上下文测试显式激活 `test` Profile，并断言测试环境、test-only 密码/OIDC Issuer/对象存储 secret/Worker 并发值，避免测试继承本地或生产配置。

## 密钥扫描门禁

固定扫描器：

```text
zricethezav/gitleaks:v8.28.0@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854
```

multi-arch manifest digest 通过 `docker buildx imagetools inspect` 验证；当前 Docker 拉取结果返回同一 digest。

```powershell
pnpm secrets:verify
```

最终结果：退出码 `0`；119 个 Git tracked/untracked 且未被忽略的可提交候选文件全部无泄漏。脚本随后只在受保护的系统临时目录生成一个合成硬编码密码，Gitleaks 以退出码 `1` 和规则 `workflow-generic-assigned-secret` 正确阻断；脚本把该失败视为反向测试成功，并清理临时目录。

门禁不把 `.env.example`、测试或文档整条路径加入 allowlist。项目规则只精确允许后缀为 `local-only-change-me` 和 `test-only` 的值；AWS 等真实 Access Key 继续由 Gitleaks 内置专用规则负责。

## 统一前端/配置门禁

```powershell
pnpm verify
```

结果：退出码 `0`，顺序完成：

1. 3 份 env / 40 键 / 6 Profile 配置契约；
2. Gitleaks 正向扫描和合成反向测试；
3. ESLint，warning 0；
4. TypeScript 严格类型检查；
5. Vitest：2 个测试文件、3 项测试全绿；
6. Vite 生产构建：1616 个模块转换成功。

## Java 全仓复验

桌面权威目录使用依赖缓存执行：

```powershell
.\mvnw.cmd -B -o clean verify
```

结果：退出码 `0`，5 个 reactor 项目全部 `SUCCESS`，14 项测试全绿；Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 全部通过，静态分析违规 0。control-plane 和 worker 测试日志均明确显示 active profile 为 `test`。

## 实机发现与修正

1. Git 默认会对中文规划文档路径使用八进制 quote。候选文件枚举改用 `core.quotepath=false` + NUL 分隔，中文、空格及特殊文件名不会遗漏或误解析。
2. 首版通用规则把 S3 `access-key` 身份标识误判为 secret。移除该通用词后不做路径豁免；实际 AWS Access Key 仍由内置专用模式扫描。
3. Maven 旧 `target/spotless-index` 由不同安全主体创建，首次无法清理。只清理已忽略的可重建 `target` 产物后，格式化和完整门禁正常通过，源码未受影响。

## 关键文件

- `/.env.example`
- `/.gitleaks.toml`
- `/.gitignore`
- `/scripts/config/verify-configuration.mjs`
- `/scripts/security/verify-secrets.mjs`
- `/control-plane/src/main/resources/application-{local,test,prod}.yml`
- `/worker/src/main/resources/application-{local,test,prod}.yml`
- `/docs/architecture/configuration-and-secret-handling.md`
- `/package.json`
- `/README.md`
- `/CONTRIBUTING.md`

## 已知边界

- 本任务保护仓库提交与配置分层，不替代生产 KMS/Vault、密钥轮换、连接器信封加密和审计；这些能力在 G6-07/G6-11 实现。
- Gitleaks 是基于模式的预防门禁，不能证明历史中从未存在密钥；发现真实泄漏仍必须先撤销/轮换并调查访问记录。
- 生产部署清单与密钥 Provider 尚未创建；`application-prod.yml` 只定义无回退注入契约，不宣称已有生产部署安全性。
- 浏览器中的所有 `VITE_*` 都按公开数据处理，后续不得加入 client secret。

## 结论

配置域、Profile 分层、覆盖规则、40 键目录、敏感默认值边界、配置漂移检查、固定版本密钥扫描、正反向门禁和统一构建入口均有可复现证据，G1-09 可以标记为 `[x]`。
