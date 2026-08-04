# G1-04 验收记录：Maven 多模块与 Wrapper 构建

- 状态：PASS
- 验收日期：2026-07-30
- 权威目录：`C:\Users\Administrator\Desktop\workflow-platform`
- 对应任务：G1-04

## 交付内容

- 父工程 `workflow-platform-parent:0.1.0-SNAPSHOT`。
- Maven Reactor 模块：`shared-contracts`、`control-plane`、`worker`、`test-support`。
- Spring Boot 控制面和 Worker 最小可启动应用。
- 共享契约版本常量和确定性测试 ID 工具。
- Maven Wrapper：Wrapper Plugin 3.3.4、Maven 3.9.16、`only-script` 模式。
- 构建版本和环境约束：Java 21、Spring Boot 3.5.16、关键 Maven 插件显式版本。
- `.mvn/maven.config` 和 `.mvn/jvm.config`，统一校验、编码和构建行为。

## 锁定版本

| 组件 | 版本 | 锁定位置 |
|---|---:|---|
| Java release | 21 | 父 `pom.xml` 与 Maven Enforcer |
| 验收 JDK | Eclipse Temurin 21.0.12+8 | 临时构建工具，不进入仓库 |
| Spring Boot | 3.5.16 | 父 POM parent 与插件版本 |
| Maven | 3.9.16 | Wrapper `distributionUrl` |
| Maven Wrapper Plugin | 3.3.4 | 父 POM 与 Wrapper properties |
| Maven Compiler Plugin | 3.14.1 | 父 POM |
| Maven Surefire/Failsafe | 3.5.4 | 父 POM |
| Maven Enforcer Plugin | 3.6.2 | 父 POM |
| Maven Site Plugin | 3.21.0 | 父 POM pluginManagement |

Maven 分发包 SHA-256：

```text
5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce
```

该摘要写入 `.mvn/wrapper/maven-wrapper.properties`，Wrapper 下载校验失败时会立即停止。

## 构建命令与结果

首次联网验收：

```powershell
$projectRoot = 'C:\Users\Administrator\Desktop\workflow-platform'
.\mvnw.cmd -B -q -f "$projectRoot\pom.xml" clean verify
```

结果：退出码 `0`，PASS。

依赖下载完成后的离线复现：

```powershell
.\mvnw.cmd -B -q -o -f "$projectRoot\pom.xml" clean verify
```

结果：退出码 `0`，PASS。该次构建证明 Maven Wrapper、插件和项目依赖均已被明确解析，不依赖浮动 `LATEST/RELEASE` 版本。

## 测试结果

| 测试套件 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `ContractVersionTests` | 1 | 0 | 0 | 0 |
| `ControlPlaneApplicationTests` | 1 | 0 | 0 | 0 |
| `WorkerApplicationTests` | 1 | 0 | 0 | 0 |
| `TestFixtureIdsTests` | 2 | 0 | 0 | 0 |
| **合计** | **5** | **0** | **0** | **0** |

两个 Spring Boot 测试均成功创建并关闭 ApplicationContext。

## 构建产物

| 产物 | 字节数 | 结果 |
|---|---:|---|
| `shared-contracts/target/shared-contracts-0.1.0-SNAPSHOT.jar` | 2,814 | PASS |
| `control-plane/target/control-plane-0.1.0-SNAPSHOT.jar` | 25,325,105 | PASS |
| `worker/target/worker-0.1.0-SNAPSHOT.jar` | 16,307,810 | PASS |
| `test-support/target/test-support-0.1.0-SNAPSHOT.jar` | 3,102 | PASS |

所有 `target/` 均被 `.gitignore` 排除，不会污染源代码提交。

## 验收过程中的问题与修正

1. 第一次调用 Wrapper 时当前目录不在桌面项目，Maven 返回 `MissingProjectException`；补充显式 `-f <desktop>/pom.xml` 后进入实际构建。
2. Enforcer 首次实际运行发现 Maven 超级 POM 隐式提供 `maven-site-plugin:3.12.1`；父 POM 显式锁定 `3.21.0` 后，`requirePluginVersions` 通过。
3. 最终联网和离线两次 `clean verify` 均为退出码 0。

这些中间失败没有被当作完成证据；本文只以最终成功构建和 Surefire XML 为验收依据。

## 已知非阻塞提示

Spring Boot 测试基础设施加载 Mockito 时，Java 21 输出“动态 Agent 自附加未来将禁用”的预警。当前测试与构建均成功，且项目限定 JDK 21；G1-07 建立测试质量基线时将显式配置测试 Agent 或调整测试监听器，消除未来兼容风险。

## 结论

G1-04 的父 POM、四模块、Wrapper、版本锁定、编译、测试、打包和离线复现要求全部满足，可以标记为 `[x]`。

