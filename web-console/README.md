# Web Console

企业级事件驱动智能工作流平台的管理端。当前阶段提供 React、TypeScript、Vite、Ant Design、ESLint 和 Vitest 工程基线；流程定义、实例、人工待办和运维页面将在 G8 阶段逐步实现。

## 环境要求

- Node.js 24.14.0
- pnpm 11.9.0

版本同时记录在根目录 `.node-version`、`.nvmrc` 和 `package.json`。

## 安装与开发

从项目根目录执行：

```powershell
pnpm install --frozen-lockfile
pnpm dev
```

默认仅监听 `127.0.0.1:5173`。如需连接本地后端，复制 `.env.example` 为 `.env.local` 并调整非敏感公开配置；Vite 的 `VITE_*` 变量会进入浏览器产物，禁止放入任何密码或密钥。

## 质量命令

```powershell
pnpm lint
pnpm typecheck
pnpm test
pnpm build
pnpm preview
pnpm verify
```

`pnpm verify` 按顺序运行 lint、严格类型检查、单元/组件测试和生产构建。
