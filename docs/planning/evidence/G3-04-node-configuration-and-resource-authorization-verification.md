# G3-04 节点配置、集成引用与授权校验验收证据

## 1. 任务

- 任务：G3-04 实现节点 Schema、参数类型、连接器引用、密钥引用和权限校验
- Requirement：`REQ-DSL-001`
- 完成日期：2026-07-31
- 状态：`[x]`

## 2. 开发前审计与优化

审计原 DSL、V2 数据库边界和 G3-01～G3-03 后确认五个缺口：

1. HTTP Trigger 可声明 HMAC，但没有密钥引用字段，无法证明发布时密钥存在；
2. HTTP Node 只有 `connectorRef`，没有表达调用所需受管 Secret 的方式；
3. connector/secret 同时支持 project 级和 tenant 级对象，原契约没有固定同名解析与遮蔽语义；
4. `DISABLED`、软删除和 Secret `ROTATION_REQUIRED` 的发布策略未机器化；
5. 若错误返回引用值或区分跨租户对象状态，会形成资源枚举和 Secret 元数据泄漏。

本次以 DSL v1 向后兼容的可选扩展补入 `hmacSecretRef` 和 `secretRefs`；固定“项目优先、租户回退、项目同名对象遮蔽租户对象”；只允许未删除的 `ACTIVE` 对象；存在性通过后仍要求 kind + resource ID 的显式 use grant。校验上下文和结果均不含 ciphertext、external key、Provider 材料、endpoint 或任意 Secret 值。

## 3. 实现结果

### 3.1 专属配置与类型化结果

- 三类 Trigger：Manual、HTTP、Cron。
- 六类 Node：Start、End、HTTP、Condition、Delay、Human Task。
- HTTP Trigger：OIDC/HMAC XOR；HMAC 强制 `hmacSecretRef`；幂等标志必须为 `true`。
- Cron：表达式形状、真实 IANA `ZoneId`、misfire/catch-up 联动和 `1..100` 上限。
- HTTP Node：connector、五种方法、路径、Header、Body/Result 表达式及最多 20 个不可重复 `secretRefs`。
- Delay：正 duration 与 expires-at 表达式严格二选一。
- Human Task：role/assignee 二选一，form/timeout/deadline 联动。
- 输出 `ValidatedWorkflowConfiguration` 的九种 sealed 类型化变体及不可变集合。

### 3.2 引用和权限

- 输入固定 tenant/project、Secret-free 资源目录快照和显式 use grants。
- 禁止跨 tenant、跨 project；项目资源优先于 tenant 资源。
- 项目同名对象存在时不允许通过 tenant fallback 绕过禁用、删除或待轮换状态。
- connector 和 secret 只允许 `ACTIVE`；Secret `ROTATION_REQUIRED` 拒绝发布。
- 九个稳定失败码只携带有界 JSON Pointer；`unavailable` 合并不存在、越作用域、禁用、删除和待轮换状态。

## 4. 关键文件

- `schemas/dsl/workflow-definition-v1.schema.json`
- `config/dsl/node-configuration-validation.json`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/DeterministicWorkflowConfigurationValidator.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/ValidatedWorkflowConfiguration.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowConfigurationValidationContext.java`
- `control-plane/src/main/java/com/workflowplatform/controlplane/definition/IntegrationResource.java`
- `control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowConfigurationValidatorTests.java`
- `tests/contracts/dsl/configuration`
- `scripts/dsl/verify-node-configuration-validation.mjs`
- `scripts/dsl/test-node-configuration-validation-verifier.mjs`
- `docs/architecture/dsl-node-configuration-validation.md`

## 5. 自动化验收

### 5.1 G3-04 契约门禁

```text
DSL G3-04 configuration verification PASS: 3 triggers, 6 nodes, 9 failure codes, 2 valid and 6 invalid fixtures.
DSL G3-04 verifier self-test PASS: 15 negative mutations blocked.
```

Java G3-04 有 9 个测试方法，覆盖全部九种配置变体、项目优先/租户回退、项目遮蔽、类型错误、重复引用、跨 tenant/project、禁用、软删除、待轮换、无授权、稳定错误、脱敏与深层不可变。

### 5.2 Maven 五模块

执行 Java 21 / Maven 3.9.16 五模块 `mvn verify`：

- shared-contracts：16 项；
- control-plane unit：81 项；
- control-plane Testcontainers integration：4 项；
- worker：1 项；
- test-support：2 项；
- 合计：104 项，0 失败、0 错误、0 跳过。

PostgreSQL 18.4 Testcontainers、Flyway V1～V5、PMD、CPD、SpotBugs、Checkstyle、Spotless 与 JaCoCo 全部通过。

### 5.3 整仓 Node/前端/Secret 门禁

Node 24.14.0 / pnpm 11.9.0 `pnpm verify` 全部通过：

- G3-01～G3-04、公共契约、架构、领域、数据库、治理、追踪和配置正反门禁全绿；
- Gitleaks 固定镜像扫描提交候选无泄漏，合成硬编码 Secret 被正确阻断；
- Web Console lint、TypeScript typecheck、3 项 Vitest 和 Vite production build 通过。

最终证据写入后再次扫描 344 个提交候选，全部无泄漏；合成硬编码 Secret 继续被正确阻断。

## 6. 遗留边界

- G3-05 继续负责 CEL/JSONPath 的语法、静态类型、求值预算和资源沙箱；本任务只验证表达式为非空、有界字符串，不虚报表达式安全完成。
- G3-06～G3-09 继续负责草稿、Validate/Publish API、不可变版本、克隆/导入导出/差异；当前类型化结果尚未接 API 生命周期。
- G6 继续负责真实 OIDC/RBAC 授权决策、Secret Provider/KMS、轮换、SSRF 出站策略与使用审计。
- Flyway 当前仍提示 PostgreSQL 18.4 高于其声明支持上限；真实迁移和查询门禁已通过，但版本治理提示继续保留。

## 7. 结论

G3-04 已满足总控清单：节点/触发器专属 Schema、参数类型、connector/secret 引用存在性、tenant/project 作用域、生命周期与显式使用权限均已实现并由正反门禁锁定。下一步执行 G3-05。
