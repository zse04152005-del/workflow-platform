# G2-09 RFC 9457 统一错误响应验收证据

- 验收日期：2026-07-30
- 状态：PASS
- 契约版本：`1.0.0`
- 机器事实源：`config/api/problem-details.json`

## 1. 交付清单

- 机器契约：`config/api/problem-details.json`
- 共享模型：`shared-contracts/src/main/java/com/workflowplatform/contracts/problem/`
- Spring MVC 映射：`control-plane/src/main/java/com/workflowplatform/controlplane/api/error/`
- 静态验证：`scripts/contracts/verify-problem-details.mjs`
- 反向控制：`scripts/contracts/test-problem-details-verifier.mjs`
- 架构说明：`docs/architecture/problem-details-contract.md`

## 2. 量化验收

| 项目 | 实测 |
|---|---:|
| 基础稳定错误码 | 11 |
| 错误类别 | 8 |
| RFC 标准成员 | 5 |
| 必需扩展成员 | 4 |
| 字段级错误码 | 6 |
| 单响应字段错误上限 | 50 |
| 静态反向变异 | 18/18 拒绝 |
| G2-09 相关 Java 测试 | 24（control-plane）+ 5（shared-contracts） |

## 3. 已验证行为

- `application/problem+json`、RFC 9457 标准成员、`code/traceId/errors/retryable` 和 path-only instance。
- W3C `traceparent`、`X-Correlation-ID`、安全随机生成的三级 traceId 解析，全零/非法值拒绝回显。
- Bean Validation 字段错误按字段稳定排序、去重、限 50 项，使用静态 code/message，完全不读取 `rejectedValue`。
- Malformed JSON、缺失参数、类型错误、404、显式业务问题、401、429 和未分类 500 的统一映射。
- 401 的 `WWW-Authenticate: Bearer`、429/503 的 `Retry-After`、所有错误的 `Cache-Control: no-store` 与 `X-Trace-Id`。
- query、敏感字段值、异常 message、异常类名和 stack trace 不进入响应。

## 4. 验收命令

```powershell
pnpm contracts:problem:verify
pnpm contracts:problem:test
pnpm verify
pnpm compose:verify
pnpm ci:verify
mvn clean verify
mvn -o clean verify
```

最终整仓、离线构建和桌面副本复验结果见本文件第 5 节。

## 5. 最终门禁结果

- Problem Details 静态门禁 PASS：11 个基础稳定错误码、8 类 HTTP 映射、9 个必需成员、6 个字段错误码、50 项上限与全部 Java 源绑定一致。
- 反向控制 PASS：错误 RFC/媒体类型/成员、开放额外成员、宽松或全零 trace、顺序漂移、rejected value、无界字段错误、脱敏成员缺失、重复/非法错误码、状态/URI/重试性/内部 detail/源绑定破坏等 18 类变异全部被拒绝。
- Java PASS：`shared-contracts` 5 项、`control-plane` 24 项相关测试通过；实际 MockMvc 响应覆盖 validation、malformed JSON、缺失参数、类型错误、404、401、429 和 500 脱敏。
- 质量门禁 PASS：Spotless、Checkstyle、PMD/CPD、SpotBugs 均 0 违规，JaCoCo line 70% / branch 60% 阈值通过。
- 整仓 PASS：`pnpm verify`、Compose/CI 合同、前端 lint/typecheck/3 项测试/Vite build 通过；219 个提交候选无 Secret，合成硬编码密钥被拦截。
- 迁移回归 PASS：V1～V5 校验和未漂移，未登记迁移、登记迁移与篡改反例门禁通过。
- Java 可复现构建 PASS：Java 21 / Maven 3.9.16 五模块联网与离线 `clean verify` 均成功，全仓 32 项 Java 测试无失败。
- 桌面副本 PASS：非破坏同步后，独立 `pnpm verify`、Problem Details 正反向门禁、Maven 离线 `clean verify` 与文件哈希审计全部通过。

## 6. 边界与遗留风险

- 当前冻结错误契约和 Spring MVC 基础异常映射；OpenAPI 3.1 引用、DSL/Event Schema 由 G2-10 完成。
- OIDC/RBAC 的真实认证与授权异常由 G6 接入，现阶段仅验证 401/403 的标准目录行为。
- 领域资源、并发版本、幂等和 Human Task 等细分错误码随对应功能加入，但必须保持同一命名、HTTP 和脱敏门禁。
- OpenTelemetry/跨 Kafka trace 传播与结构化日志由 G9 完成；当前仅提供 HTTP traceId 解析和响应关联。
