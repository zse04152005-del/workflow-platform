# G1-07 验收记录：Java 质量门禁与覆盖率基线

- 状态：PASS
- 验收日期：2026-07-30
- 权威目录：`C:\Users\Administrator\Desktop\workflow-platform`
- 对应任务：G1-07

## 交付内容

- Spotless + Google Java Format AOSP 确定性格式门禁。
- Checkstyle 样式与基础正确性门禁，覆盖 main/test Java。
- PMD 源码正确性、安全、性能和最佳实践规则。
- PMD CPD 100 token 重复代码门禁。
- SpotBugs Max effort / Low threshold 字节码缺陷门禁。
- JaCoCo 单模块行覆盖率 70%、分支覆盖率 60% 门禁和 HTML/XML 报告。
- Surefire/Failsafe 显式 Mockito Java Agent，消除运行时自附加兼容预警。
- 空白 Checkstyle/SpotBugs 例外清单和强制例外治理规则。
- Java 质量门禁文档、README/贡献指南命令入口。

## 固定版本

| 组件 | 版本 |
|---|---:|
| Spotless Maven Plugin | 3.9.0 |
| Google Java Format | 1.36.0 |
| Maven Checkstyle Plugin | 3.6.0 |
| Checkstyle Engine | 13.9.0 |
| Maven PMD Plugin | 3.28.0 |
| PMD Engine | 7.17.0 |
| SpotBugs Maven Plugin | 4.10.3.0 |
| JaCoCo Maven Plugin | 0.8.15 |
| Maven Dependency Plugin | 3.11.0 |
| Maven Surefire/Failsafe | 3.5.4 |

插件版本都在根 `pom.xml` 显式锁定，并继续受 Maven Enforcer `requirePluginVersions` 约束。

## 规则设计

| 门禁 | 阶段 | 验收口径 |
|---|---|---|
| Spotless | `validate` | 所有 Java 文件符合 Google Java Format AOSP |
| Checkstyle | `validate` | error 级违规 0 |
| PMD | `verify` | 最佳实践、易错、安全、性能违规 0 |
| PMD CPD | `verify` | 100 token 以上重复块 0 |
| SpotBugs | `verify` | Low 及以上缺陷 0，分析错误 0 |
| JaCoCo | `verify` | 每个有业务代码的模块行覆盖率 ≥ 70%、分支覆盖率 ≥ 60% |

PMD 未启用 `LooseCoupling` 和 `UnitTestContainsTooManyAsserts`：ArchUnit 必须使用 `JavaClasses` 专用类型，而架构不变量测试需要在同一场景检查多个相关断言。其他测试质量规则保持启用；现有缺失断言消息、Spring 上下文空断言和夹具命名问题均通过代码修正，而非抑制。

## 覆盖率口径与结果

结构性类型不计入覆盖率：Spring Boot 启动类、模块边界注解、`package-info` 和纯编译期契约版本常量。当前 control-plane、worker、shared-contracts 除这些类型外尚无业务实现，因此分析类数为 0；首个业务类加入后会自动进入同一阈值。

`test-support` 当前包含 1 个可分析生产类 `FixtureIds`：

| Counter | Covered | Missed | Ratio | Minimum |
|---|---:|---:|---:|---:|
| Line | 3 | 0 | 100% | 70% |
| Branch | 3 | 1 | 75% | 60% |

报告：`test-support/target/site/jacoco/index.html` 和 `test-support/target/site/jacoco/jacoco.xml`。

## 自动化验收

格式应用：

```powershell
.\mvnw.cmd -B spotless:apply
```

依赖缓存后的全仓可复现验收：

```powershell
.\mvnw.cmd -B -o clean verify
```

结果：退出码 `0`，5 个 reactor 项目全部 `SUCCESS`，总耗时约 50 秒。

| 模块 | Checkstyle | PMD | CPD | SpotBugs bugs | SpotBugs errors |
|---|---:|---:|---:|---:|---:|
| shared-contracts | 0 | 0 | 0 | 0 | 0 |
| control-plane | 0 | 0 | 0 | 0 | 0 |
| worker | 0 | 0 | 0 | 0 | 0 |
| test-support | 0 | 0 | 0 | 0 | 0 |

测试总计 14 项：shared-contracts 1、control-plane 10、worker 1、test-support 2；Failures 0、Errors 0、Skipped 0。四个模块继续生成 `0.1.0-SNAPSHOT` JAR。

最终测试输出不再包含 Mockito self-attaching/dynamic agent 预警，也没有 bootstrap classpath/CDS 共享预警。

## 门禁反例验证

使用 Maven `--define` 只在单次命令中把分支阈值提高到 80%，项目文件不发生修改：

```powershell
.\mvnw.cmd -B -o -pl test-support -am --define jacoco.branch.minimum=0.80 verify
```

结果：预期退出码 `1`；JaCoCo 明确报告：`branches covered ratio is 0.75, but expected minimum is 0.80`。这证明阈值会阻断不合格构建，不是空配置。

## 关键文件

- `pom.xml`
- `config/checkstyle/checkstyle.xml`
- `config/checkstyle/suppressions.xml`
- `config/pmd/ruleset.xml`
- `config/spotbugs/exclude.xml`
- `docs/architecture/java-quality-gates.md`
- `CONTRIBUTING.md`
- `test-support/src/main/java/com/workflowplatform/testsupport/FixtureIds.java`

## 已知边界

- 本任务建立 Java 质量门禁；前端继续使用 ESLint、TypeScript、Vitest 和 Vite 的 `pnpm verify`。
- 依赖漏洞、许可证和密钥扫描在 G1-09/G1-10 及安全阶段接入，不把静态代码分析冒充供应链扫描。
- 当前 Checkstyle/SpotBugs 抑制文件均为空；任何未来例外必须携带问题 ID、责任人、原因和到期日期。

## 结论

格式、样式、源码/字节码分析、重复代码、覆盖率、显式测试 Agent、例外治理、正向离线构建和反向阈值测试都有可复现证据，G1-07 可以标记为 `[x]`。

