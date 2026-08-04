# 持续集成与质量门禁基线

## 1. 触发、权限与并发

主工作流位于 `.github/workflows/ci.yml`，在以下事件运行：

- 推送到 `main`；
- 任意 Pull Request；
- 手工 `workflow_dispatch`。

工作流顶层权限仅为 `contents: read`，所有 checkout 都设置 `persist-credentials: false`。禁止 `pull_request_target`、`workflow_run` 和直接读取 `${{ secrets.* }}`，避免不受信任 PR 获得高权限上下文。

并发组使用 workflow + ref；同一分支的新运行会取消旧运行。每个 Job 都有 10 或 20 分钟超时，不允许无限挂起。

## 2. 作业边界

| Job | 超时 | 核心命令 | 覆盖范围 |
|---|---:|---|---|
| `java-quality` | 20 分钟 | `./mvnw -B -ntp clean verify` | 编译、Spotless、Checkstyle、PMD/CPD、SpotBugs、14 项测试、JaCoCo |
| `web-and-policy` | 20 分钟 | `pnpm verify`、`pnpm migrations:verify`、`pnpm migrations:test` | 配置契约、Secret 正反向、G2-04 ER/迁移一致性、lint、类型、3 项前端测试、构建、迁移 checksum 正反向 |
| `workflow-contract` | 10 分钟 | `node scripts/ci/verify-workflow.mjs` | CI 安全策略、Action SHA、actionlint 语法/表达式/Shell 检查 |
| `compose-contract` | 10 分钟 | `node scripts/compose/verify-contract.mjs` | 7 个固定镜像、5 个健康检查、2 个 init、loopback 端口 |

Job 相互独立，能在 GitHub UI 中直接区分 Java、Web/策略、工作流或 Compose 失败，不使用一个无法定位的大型脚本掩盖错误。

## 3. 供应链固定

| 组件 | 版本 | 固定引用 |
|---|---|---|
| Ubuntu Runner | 24.04 | `ubuntu-24.04` |
| Temurin JDK | 21.0.12+8 | setup-java 输入 |
| Maven | 3.9.16 | Maven Wrapper + distribution SHA-256 |
| Node.js | 24.14.0 | `.node-version` |
| pnpm | 11.9.0 | packageManager + action 输入 |
| actions/checkout | v5.0.1 | `93cb6efe18208431cddfb8368fd83d5badbf9bfd` |
| actions/setup-java | v5.0.0 | `dded0888837ed1f317902acf8a20df0ad188d165` |
| actions/setup-node | v5.0.0 | `a0853c24544627f65ddf259abe73b1d18a591444` |
| pnpm/action-setup | v4.2.0 | `9fd676a19091d4595eefd76e4bd31c97133911f1` |
| Gitleaks | v8.28.0 | `sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854` |
| actionlint | 1.7.7 | `sha256:887a259a5a534f3c4f36cb02dca341673c6089431057242cdc931e9f133147e9` |

Action 的可读版本只写在注释中，真正执行引用必须是 40 位 commit SHA。`scripts/ci/verify-workflow.mjs` 会拒绝 tag/branch、未知 Action 或已评审 SHA 漂移。

## 4. 缓存与可复现性

- setup-java 使用 Maven 缓存；依赖仍受 POM、Wrapper 严格 checksum 和 Enforcer 约束。
- setup-node 使用 pnpm 缓存，安装必须带 `--frozen-lockfile`。
- 缓存只加速下载，不缓存构建结果来替代编译/测试。
- Gitleaks/actionlint 镜像固定 digest；Docker 拉取到错误内容时由 digest 校验阻断。
- CI 不自动格式化、改写 checksum 或提交代码，只做只读验证。

## 5. 迁移门禁

G2-04 已引入并登记首个 Flyway SQL `V1__create_tenant_and_rbac.sql`。迁移治理契约包括：

- 目录固定为 `control-plane/src/main/resources/db/migration`；
- 文件名必须为 `V<正版本>__<lower_snake_case>.sql`；
- 版本唯一、可排序；文件必须 UTF-8、LF、无 BOM、非空且有末尾换行；
- `config/migrations/checksums.json` 保存 SHA-256，不允许已登记迁移静默修改；
- `pnpm migrations:test` 在临时目录证明未登记迁移被拒绝、登记后通过、篡改后被拒绝。

新增迁移后先评审 SQL，再显式运行 `pnpm migrations:checksum` 更新 checksum 清单并提交差异。`pnpm verify` 运行 G2-04～G2-08 的模型一致性/反向门禁，以及 G2-11 的 Testcontainers 契约正反向门禁。本地 `pnpm database:g2-11:integration` 使用固定 digest 的 PostgreSQL 18.4 Testcontainers，覆盖空库 V1→V7、第二次 migrate、22 表 Catalog、约束、RLS 和 Q-01～Q-10 默认优化器计划；真实数据库集成仍需显式运行，不在无 Docker 的纯静态 Job 中伪造通过。

## 6. 本地等价验收

GitHub 托管运行前，可在仓库根目录执行：

```powershell
.\mvnw.cmd -B clean verify
pnpm install --frozen-lockfile
pnpm verify
pnpm migrations:verify
pnpm migrations:test
pnpm database:g2-04:integration
pnpm database:g2-11:integration
pnpm compose:verify
pnpm ci:verify
```

其中 `secrets:verify`、`compose:verify`、`ci:verify` 需要 Docker Engine。工作流定义只有在 actionlint、结构策略和上述本地等价门禁全部通过后才能标记为完成；尚未连接远程仓库时，不把本地验证冒充 GitHub 托管运行结果。
