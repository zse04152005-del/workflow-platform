# G1-05 验收记录：控制面模块边界

- 状态：PASS
- 验收日期：2026-07-30
- 权威目录：`C:\Users\Administrator\Desktop\workflow-platform`
- 对应任务：G1-05
- 架构规则依赖：ArchUnit 1.4.2

## 交付内容

- `@ModuleBoundary`：声明逻辑模块名称和允许的编译时依赖。
- `@ModuleInternal`：标记模块内部实现包。
- 七个模块根包：tenant、definition、runtime、scheduler、human-task、security、audit。
- 七个对应的 `internal` 实现包标记。
- `ControlPlaneModuleArchitectureTests` 自动架构测试。
- `docs/architecture/control-plane-module-boundaries.md` 模块依赖和包可见性说明。
- README 架构文档入口。

## 依赖方向

| 模块 | 允许依赖 |
|---|---|
| `security` | 无 |
| `audit` | 无 |
| `tenant` | `security`、`audit` |
| `definition` | `tenant`、`security`、`audit` |
| `runtime` | `definition`、`tenant`、`security`、`audit` |
| `scheduler` | `runtime`、`audit` |
| `human-task` | `runtime`、`tenant`、`security`、`audit` |

声明图和实际字节码依赖图都必须无环。

## 自动化约束

架构测试持续验证：

1. 模块集合必须且只能包含规定的七个模块。
2. 每个模块拥有唯一根包和一个标准内部实现包。
3. 允许依赖只能引用已知模块，不能自依赖，声明图不能成环。
4. 实际跨模块字节码依赖必须出现在允许依赖列表中。
5. 模块根包是唯一公开 API；其他模块和根启动层不能访问目标模块任何子包。
6. 名称以 `Repository` 结尾的类型不能被其他模块或启动层直接使用。
7. 实际模块字节码依赖图必须无环。

## 规则反例验证

测试作用域包含三类故意违规场景，生产类扫描通过 `DO_NOT_INCLUDE_TESTS` 排除这些夹具；同一条规则单独扫描夹具时必须抛出架构失败：

| 违规场景 | 预期拦截理由 | 结果 |
|---|---|---|
| `tenant -> runtime.RuntimePublicProbe` | tenant 未声明依赖 runtime | PASS，规则主动拒绝 |
| `tenant -> runtime.internal.RuntimeInternalProbe` | 跨模块访问非公开子包 | PASS，规则主动拒绝 |
| `runtime -> definition.internal.DefinitionRepository` | 跨模块 Repository 访问 | PASS，规则主动拒绝 |

共使用 6 个测试夹具类型，避免规则因当前业务实现尚少而“空跑通过”。

## 验收命令

控制面定向测试：

```powershell
.\mvnw.cmd -B -pl control-plane -am test
```

结果：退出码 `0`，PASS。

全仓离线验收：

```powershell
.\mvnw.cmd -B -o clean verify
```

结果：退出码 `0`，PASS。

Codex 从项目目录外执行时使用等价的 `-f C:\Users\Administrator\Desktop\workflow-platform\pom.xml` 参数。

## 测试结果

| 范围 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `ControlPlaneModuleArchitectureTests` | 9 | 0 | 0 | 0 |
| 全仓全部测试 | 14 | 0 | 0 | 0 |

四个 Maven 模块仍全部生成 `0.1.0-SNAPSHOT` JAR。

## 文件证据

- `control-plane/src/main/java/com/workflowplatform/controlplane/ModuleBoundary.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/ModuleInternal.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/{module}/package-info.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/{module}/internal/package-info.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/ControlPlaneModuleArchitectureTests.java`
- `docs/architecture/control-plane-module-boundaries.md`

## 已知非阻塞提示

Spring Boot 测试仍会输出 Mockito 动态 Agent 的 Java 21 未来兼容预警；当前构建与架构测试均成功。该提示已由 G1-04 记录，并在 G1-07 测试质量基线处理。

## 结论

模块范围、依赖方向、内部 API、Repository 隔离、无环约束及反例有效性都有自动化证据，G1-05 可以标记为 `[x]`。

