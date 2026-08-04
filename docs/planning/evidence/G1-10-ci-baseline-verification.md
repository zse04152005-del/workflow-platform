# G1-10 验收记录：持续集成与迁移校验基线

- 状态：PASS（本地等价验收）
- 验收日期：2026-07-30
- 权威目录：`C:\Users\Administrator\Desktop\workflow-platform`
- 对应任务：G1-10

## 交付内容

- GitHub Actions `CI` 工作流，覆盖 push main、Pull Request、手工触发。
- Java、Web/配置/Secret/迁移、工作流契约、Compose 契约 4 个独立 Job。
- 顶层 `contents: read` 最小权限；checkout 不保留凭据；禁止高风险 PR 触发和直接读取 Secrets。
- 分支级并发取消，每个 Job 10/20 分钟超时。
- checkout/setup-java/setup-node/pnpm action 全部使用完整 40 位 commit SHA。
- Temurin JDK 21.0.12+8（GitHub Runner 目录标识 `21.0.12+8.0.LTS`）、Maven 3.9.16 Wrapper、Node 24.14.0、pnpm 11.9.0 精确运行时契约。
- Web Job 在 setup-node 的 pnpm 缓存前安装 pnpm；不使用包管理器的 workflow/Compose Job 关闭自动 package-manager cache，避免 setup-node 启动阶段依赖未安装的 pnpm。
- 固定 digest 的 actionlint 语法/表达式/Shell 校验和结构性 CI 安全策略校验。
- 固定目录、命名、编码、版本唯一性与 SHA-256 不可变目录的迁移门禁。
- 未登记迁移、已登记迁移、篡改迁移三段临时反向测试。
- Docker Compose 模型校验：固定镜像、健康检查、init 生命周期和 loopback 端口。
- CI 架构、供应链固定、缓存、迁移演进和本地等价命令文档。

## CI 作业

| Job | 超时 | 验收命令/能力 |
|---|---:|---|
| `java-quality` | 20 分钟 | `./mvnw -B -ntp clean verify` |
| `web-and-policy` | 20 分钟 | frozen pnpm 安装、`pnpm verify`、迁移正反向 |
| `workflow-contract` | 10 分钟 | CI 策略校验 + actionlint |
| `compose-contract` | 10 分钟 | Compose JSON 模型契约 |

工作流结构校验：

```powershell
pnpm ci:verify
```

结果：退出码 `0`：

```text
CI workflow verification PASS: 4 jobs, 9 SHA-pinned Action uses, minimal permissions, actionlint rhysd/actionlint:1.7.7@sha256:887a259a5a534f3c4f36cb02dca341673c6089431057242cdc931e9f133147e9.
```

actionlint 镜像 multi-arch digest 已通过 `docker buildx imagetools inspect rhysd/actionlint:1.7.7` 验证。

## Action 与运行时固定

| 组件 | 固定值 |
|---|---|
| actions/checkout v5.0.1 | `93cb6efe18208431cddfb8368fd83d5badbf9bfd` |
| actions/setup-java v5.0.0 | `dded0888837ed1f317902acf8a20df0ad188d165` |
| actions/setup-node v5.0.0 | `a0853c24544627f65ddf259abe73b1d18a591444` |
| pnpm/action-setup v4.2.0 | `9fd676a19091d4595eefd76e4bd31c97133911f1` |
| Temurin JDK | 本地 `21.0.12+8`；setup-java `21.0.12+8.0.LTS` |
| Maven Wrapper | `3.9.16` + distribution SHA-256 |
| Node.js | `24.14.0` |
| pnpm | `11.9.0` |

Action commit 通过各官方 GitHub 仓库 `git ls-remote <repo> refs/tags/<tag>` 获取并记录，不使用猜测 SHA。

## 迁移门禁与反向测试

当前 G2-04 尚未引入首个 Flyway SQL，权威迁移数为 0：

```powershell
pnpm migrations:verify
```

```text
Migration verification PASS: 0 migration(s), unique ordered versions, immutable SHA-256 catalog.
```

门禁自测：

```powershell
pnpm migrations:test
```

```text
Migration verifier self-test PASS: unregistered migration blocked, registered migration accepted, checksum tampering blocked.
```

反例全部生成于带路径前缀保护的系统临时目录并在结束后清理，不向真实迁移目录写入伪 SQL。首个真实迁移加入后必须更新 `config/migrations/checksums.json`；G2 再增加 Flyway/Testcontainers 空库 migrate/validate。

## Compose 契约

```powershell
pnpm compose:verify
```

```text
Compose verification PASS: 7 fixed-digest services, 5 healthchecks, 2 one-shot initializers, loopback-only published ports.
```

该命令只解析 Compose 模型，不启动服务；G1-08 已提供协议级实机启动证据。

## 桌面本地等价验收

Node/Docker 门禁顺序执行：

```powershell
pnpm verify
pnpm migrations:verify
pnpm migrations:test
pnpm compose:verify
pnpm ci:verify
```

结果：全部退出码 `0`。`pnpm verify` 包含：3 env/40 键/6 Profile、Gitleaks 正反向、ESLint、TypeScript、3 项 Vitest 和 1616 模块 Vite 构建；加入 CI 文件后的完整门禁扫描 127 个候选文件。补入本证据并更新清单后的最终 Secret 复扫覆盖 128 个可提交候选文件，仍无泄漏，合成反例仍被阻断。

Java 权威目录离线复验：

```powershell
.\mvnw.cmd -B -o clean verify
```

结果：退出码 `0`，5 个 reactor 项目全部 `SUCCESS`，14 项测试全绿；Spotless、Checkstyle、PMD/CPD、SpotBugs、JaCoCo 全部通过。

## 关键文件

- `.github/workflows/ci.yml`
- `scripts/ci/verify-workflow.mjs`
- `scripts/compose/verify-contract.mjs`
- `scripts/database/verify-migrations.mjs`
- `scripts/database/test-migration-verifier.mjs`
- `config/migrations/checksums.json`
- `control-plane/src/main/resources/db/migration/.gitkeep`
- `docs/architecture/ci-quality-gates.md`
- `package.json`

## 已知边界

- 桌面 Git 仓库尚无 commit 和远程仓库，当前不能触发 GitHub 托管 Runner；本记录明确是 actionlint + 本地等价全门禁，不冒充 Hosted CI 运行结果。
- G2-04 之前没有真实 Flyway SQL；当前门禁验证迁移治理机制和 0 迁移基线，不宣称已验证数据库迁移执行。
- GitHub Runner 镜像标签仍由 GitHub 维护；关键语言/工具/Action/扫描器版本已在可控范围内固定。
- 未经用户身份授权不创建 commit、不推送远程、不修改分支保护。

## 结论

CI 作业边界、最小权限、并发/超时、Action/运行时固定、缓存、Java/Web/策略门禁、迁移正反向、Compose 契约、actionlint 与桌面本地等价验证均有可复现证据，G1-10 可以标记为 `[x]`。Hosted CI 首次运行结果在仓库建立远程并推送后补录。
