# Java 质量门禁

## 目标

G1-07 将格式、源码规则、字节码缺陷、重复代码、测试覆盖率和测试 Agent 配置纳入 Maven `verify` 生命周期。任何门禁失败都会使构建失败，不能只在开发者本机提示后继续。

## 门禁组成

| 门禁 | 工具 | 生命周期 | 失败条件 |
|---|---|---|---|
| 确定性格式 | Spotless + Google Java Format AOSP | `validate` | 任一 Java 文件未按统一格式提交 |
| 样式与基础正确性 | Checkstyle | `validate` | 任一 error 级规则违规 |
| 源码静态分析 | PMD | `verify` | 最佳实践、易错、安全或性能规则违规 |
| 重复代码 | PMD CPD | `verify` | 100 token 以上的 Java 重复块 |
| 字节码静态分析 | SpotBugs | `verify` | Low 及以上置信级别缺陷或分析错误 |
| 覆盖率 | JaCoCo | `verify` | 单模块行覆盖率低于 70% 或分支覆盖率低于 60% |

根 `pom.xml` 锁定所有插件和规则引擎版本，Maven Enforcer 的 `requirePluginVersions` 防止使用隐式插件版本。

## 覆盖率口径

JaCoCo 按 Maven 模块生成报告并检查 `BUNDLE` 级阈值：

- 行覆盖率最低 70%。
- 分支覆盖率最低 60%。
- 没有可执行分支的模块不会伪造分支数据。

只排除没有业务逻辑的结构性类型：Spring Boot 启动类、模块边界注解、`package-info` 和纯编译期契约版本常量。不得通过新增宽泛通配符规避业务代码覆盖率；新增排除必须在本文件说明原因并经评审。

HTML 报告位于各模块的 `target/site/jacoco/index.html`，机器数据位于 `target/jacoco.exec`。

## Mockito Agent

测试 JVM 通过 Maven Dependency Plugin 解析实际的 `mockito-core` JAR，并由 Surefire/Failsafe 使用显式 `-javaagent` 启动。这样不依赖 Mockito 运行时自附加，也不会使用 `-XX:+EnableDynamicAgentLoading` 隐藏未来 JDK 兼容风险。JaCoCo 和 Mockito 两个 Agent 在同一 forked JVM 中共同生效；测试 JVM 显式使用 `-Xshare:off`，避免 Agent 修改 bootstrap classpath 时产生无意义的 CDS 共享预警。

PMD 的 `LooseCoupling` 和 `UnitTestContainsTooManyAsserts` 两条规则未启用：前者会误报 ArchUnit 必需的 `JavaClasses` 专用类型，后者强制“一测试一个断言”，会降低架构不变量测试的内聚性。断言消息、无断言测试和其他正确性规则仍保持启用。

## 日常命令

自动修复 Java 格式：

```powershell
.\mvnw.cmd -B spotless:apply
```

执行全部后端质量门禁：

```powershell
.\mvnw.cmd -B clean verify
```

依赖已缓存时验证可复现构建：

```powershell
.\mvnw.cmd -B -o clean verify
```

前端质量门禁继续由根级 `pnpm verify` 执行，后端 `clean verify` 不隐式安装或构建前端依赖。

## 例外治理

- Checkstyle 抑制写入 `config/checkstyle/suppressions.xml`。
- SpotBugs 排除写入 `config/spotbugs/exclude.xml`。
- PMD 例外优先通过精确规则配置处理，禁止使用空泛的整包排除。
- 每个例外必须记录问题 ID、责任人、原因和到期日期；当前例外文件均为空。
- 生成代码应通过独立生成目录排除，不能与手写代码混放后整体跳过扫描。
