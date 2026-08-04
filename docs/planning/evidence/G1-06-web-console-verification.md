# G1-06 验收记录：Web Console 工程骨架

- 状态：PASS
- 验收日期：2026-07-30
- 权威目录：`C:\Users\Administrator\Desktop\workflow-platform`
- 对应任务：G1-06

## 交付内容

- 根级 pnpm workspace、统一前端命令和精确依赖锁文件。
- React 19 + TypeScript + Vite + Ant Design 的 `web-console` 工程。
- ESLint、TypeScript 严格检查、Vitest 和 Testing Library 测试基线。
- 响应式平台首页、API/OIDC 环境变量示例和生产分包配置。
- Node/pnpm 版本固定、严格 peer dependency 和最小依赖构建许可。

## 版本基线

| 组件 | 固定版本 |
|---|---:|
| Node.js | 24.14.0 |
| pnpm | 11.9.0 |
| React / ReactDOM | 19.2.8 |
| Ant Design | 6.5.2 |
| TypeScript | 6.0.3 |
| Vite | 7.3.6 |
| Vitest | 4.1.10 |
| ESLint | 10.8.0 |

版本由 `.node-version`、`.nvmrc`、根/前端 `package.json` 和 `pnpm-lock.yaml` 共同约束。

## 供应链约束

- `engine-strict=true`：Node/pnpm 版本不匹配时安装失败。
- `save-exact=true`：新增依赖默认使用精确版本。
- `strict-peer-dependencies=true` 且 `auto-install-peers=false`：peer dependency 问题不能被隐式掩盖。
- `verify-store-integrity=true`：校验 pnpm store 内容。
- `pnpm-workspace.yaml` 只允许 `esbuild` 执行安装脚本。
- 验收时显式移除了环境中继承的 `NODE_TLS_REJECT_UNAUTHORIZED=0`，在正常 TLS 校验下恢复依赖并验证锁文件。
- `pnpm-lock.yaml` SHA-256：`c1f907ff06548b8b1758a4030f766e9952aa4ec0fb81be6c295f97a3a81cb8d0`。

## 自动化验收

在项目根目录执行：

```powershell
pnpm install --frozen-lockfile
pnpm run verify
pnpm peers check
```

结果：全部退出码 `0`。

| 检查 | 结果 |
|---|---|
| ESLint | PASS，`--max-warnings=0` |
| TypeScript | PASS，项目引用严格类型检查 |
| Vitest | 2 个测试文件、3 项测试通过，0 失败 |
| Vite 生产构建 | PASS，1616 个模块完成转换 |
| Peer dependencies | PASS，`No peer dependency issues found` |

最终生产产物：

| 产物 | 原始大小 | gzip |
|---|---:|---:|
| 页面入口 JS | 4.09 kB | 2.26 kB |
| React vendor | 192.91 kB | 60.52 kB |
| 其他 vendor | 398.24 kB | 131.38 kB |
| CSS | 2.38 kB | 1.03 kB |

构建无 chunk size 警告。

## 浏览器验收

使用生产构建的本地预览页 `http://127.0.0.1:4173/` 完成桌面和移动端检查；验收后已停止临时预览进程并清理浏览器标签页。

| 场景 | 检查结果 |
|---|---|
| 桌面端 1265 px 内容区 | H1、就绪提示、交付路径和三张能力卡片各出现 1 次 |
| 桌面端横向布局 | `scrollWidth = clientWidth = 1265`，无水平溢出 |
| 390 px 移动视口 | 实际内容区 375 px，步骤纵向排列、能力卡片单列显示 |
| 移动端横向布局 | `scrollWidth = clientWidth = 375`，越界元素 0 个 |
| 浏览器控制台 | warning 0、error 0 |
| 视觉检查 | 桌面首尾、移动首屏及能力卡片区域均无重叠、裁切或不可读内容 |

## 关键文件

- `package.json`
- `pnpm-workspace.yaml`
- `pnpm-lock.yaml`
- `.node-version`
- `.nvmrc`
- `.npmrc`
- `web-console/package.json`
- `web-console/vite.config.ts`
- `web-console/eslint.config.js`
- `web-console/tsconfig*.json`
- `web-console/.env.example`
- `web-console/src/App.tsx`
- `web-console/src/App.css`
- `web-console/src/App.test.tsx`

## 已知边界

- 本任务只建立工程与最小页面，OIDC 登录、路由、真实 API 数据和业务页面按 G8 等后续任务实现。
- 当前浏览器检查是可复现的人工/自动 DOM 验收；持续集成中的 Playwright E2E 门禁将在 G8-11 建立。
- `node_modules`、`dist` 和测试报告均是可再生文件，已由 Git 忽略。

## 结论

工具版本、依赖锁定、统一命令、最小页面、静态检查、测试、生产构建和响应式浏览器验收均有证据，G1-06 可以标记为 `[x]`。

