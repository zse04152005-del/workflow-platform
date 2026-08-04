# G1-03 验收记录：Git 仓库与工程规范基线

- 状态：PASS
- 验收日期：2026-07-30
- 权威目录：`C:\Users\Administrator\Desktop\workflow-platform`
- 对应任务：G1-03

## 交付内容

- 在桌面项目目录初始化 Git 仓库，初始分支为 `main`。
- 建立 `.editorconfig`、`.gitattributes` 和 `.gitignore`。
- 建立项目 README、Apache License 2.0 和贡献/提交规范。
- 建立后端、Worker、共享契约、测试支持、前端、部署、文档和测试目录骨架。
- 设置仓库级 `core.autocrlf=false`、`core.safecrlf=true`，由 `.gitattributes` 统一跨平台换行行为。

## 自动检查结果

| 检查项 | 结果 |
|---|---|
| Git 工作树可识别 | PASS，`rev-parse --is-inside-work-tree = true` |
| 当前分支 | PASS，`main` |
| 仓库级换行配置 | PASS，`core.autocrlf=false`、`core.safecrlf=true` |
| 必需根文件与已有规划/ADR | PASS，11 项全部存在 |
| 必需工程目录 | PASS，14 项全部存在 |
| README 相对链接 | PASS，0 个断链 |
| 忽略规则样例 | PASS，Java target、node_modules、`.env`、测试结果目录均被忽略 |
| 示例环境配置 | PASS，`!**/.env.example` 显式放行 |
| Git 可纳入候选文件 | PASS，共识别 25 个未被误忽略的项目文件 |
| Apache License 2.0 | PASS，第 1～9 节及 `END OF TERMS AND CONDITIONS` 均存在 |

## 可复现命令

在 PowerShell 中执行：

```powershell
$projectRoot = 'C:\Users\Administrator\Desktop\workflow-platform'
$gitOptions = @('-c', "safe.directory=$projectRoot", '-C', $projectRoot)

git @gitOptions rev-parse --is-inside-work-tree
git @gitOptions branch --show-current
git @gitOptions config --local --get core.autocrlf
git @gitOptions config --local --get core.safecrlf
git @gitOptions status --short --branch

git @gitOptions check-ignore -v --no-index -- `
  'control-plane/target/classes/App.class' `
  'web-console/node_modules/react/index.js' `
  '.env' `
  'tests/results/report.json'

git @gitOptions check-ignore -v --no-index -- '.env.example'
git @gitOptions ls-files --others --exclude-standard
```

`safe.directory` 仅通过单次命令的 `-c` 参数指定，没有修改用户全局 Git 配置。

## 说明

- 当前为尚无提交的 `main` 分支；未冒用用户姓名或邮箱创建初始提交。
- 后续任务 G1-04 将创建 Maven 多模块和 Maven Wrapper，届时再执行真实构建验收。
- README 明确标注尚未完成的启动能力，没有填写未经验证的性能或可用性数字。

