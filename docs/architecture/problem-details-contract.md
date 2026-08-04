# RFC 9457 统一错误响应契约

- 状态：Accepted
- 日期：2026-07-30
- 对应任务：G2-09
- Requirement：`REQ-CONTRACT-001`
- 机器契约：`config/api/problem-details.json`
- 契约版本：`1.0.0`
- 适用实现：Spring MVC 控制面

本规范锁定公开 HTTP 错误的稳定形状、错误码、trace 关联、字段校验和脱敏边界。后续 G2-10 的 OpenAPI 3.1 必须引用这份机器契约，业务模块不得另建不兼容的错误包装层。

## 1. 响应媒体类型与固定形状

所有受管错误响应使用 `application/problem+json`，同时返回 `Cache-Control: no-store` 与 `X-Trace-Id`。响应包含 RFC 9457 的五个标准成员和四个必需扩展成员：

```json
{
  "type": "https://api.workflow-platform.example/problems/invalid-field",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more request fields are invalid.",
  "instance": "/api/workflows",
  "code": "COMMON_VALIDATION_INVALID_FIELD",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [
    {
      "field": "definition.name",
      "code": "required",
      "message": "value is required"
    }
  ],
  "retryable": false
}
```

约束：

- `type` 是稳定、绝对的 HTTPS URI；同一 Major 内不改义、不复用。
- `title` 与 `detail` 来自静态目录，不读取异常 message、类名、SQL 或堆栈。
- `status` 同时存在于 HTTP status line 和响应体，两者必须一致。
- `instance` 只包含编码后的 request path，不包含 scheme、host、query 或 fragment，避免查询参数泄露。
- `code` 遵循 `<DOMAIN>_<CATEGORY>_<DETAIL>`，基础目录当前包含 13 个稳定码。
- `errors` 总是存在；非字段校验错误返回空数组。
- `retryAfterSeconds` 仅对可重试的限流或依赖错误出现，并同步写入 `Retry-After`。
- 401 响应返回 `WWW-Authenticate: Bearer`。

## 2. HTTP 类别映射

| CATEGORY | HTTP status | 基础语义 |
|---|---:|---|
| `VALIDATION` | 400 | 请求形状、字段、参数或类型无效 |
| `AUTHENTICATION` | 401 | 缺失或无效身份凭据 |
| `AUTHORIZATION` | 403 | 已认证主体无权执行操作 |
| `NOT_FOUND` | 404 | 资源或路由不存在 |
| `CONFLICT` | 409 | 当前状态、版本或幂等语义冲突 |
| `PRECONDITION_FAILED` | 412 | 提供的强 ETag 与当前草稿修订不一致 |
| `PRECONDITION_REQUIRED` | 428 | 修改草稿时缺少必需的 `If-Match` |
| `RATE_LIMIT` | 429 | 请求速率或配额被限制 |
| `DEPENDENCY` | 503 | 必要下游暂时不可用 |
| `INTERNAL` | 500 | 未分类内部失败 |

映射目录的机器事实源是 `config/api/problem-details.json`，Java 的 `ProblemCode` 枚举必须与目录同序一致。新错误码必须先加入机器目录、自动化反向测试和公开说明；不得把已有错误码换到不同 HTTP 语义。

## 3. traceId 解析与传播

解析顺序固定为：

1. 合法 W3C `traceparent` 中的 32 位 trace-id；
2. 合法 `X-Correlation-ID`（32 位小写十六进制）；
3. 应用使用密码学安全随机源生成 128 位 traceId。

全零 trace-id、全零 span-id、大小写或长度错误的入站值不回显，按无效值忽略并重新生成。响应体的 `traceId` 与 `X-Trace-Id` 必须相同。G9-01 接入 OpenTelemetry 后可以替换“缺失时生成”的实现，但不得改变公开格式和优先级。

## 4. 字段级 validation errors

每项只能包含 `field`、`code`、`message`，明确禁止 `rejectedValue`：

- `field` 是受限字段路径，支持 `items[0].name`；不安全路径归一为 `_request`。
- `code` 仅使用 `required`、`invalid_format`、`invalid_size`、`out_of_range`、`invalid_type`、`invalid`。
- `message` 来自内部静态映射，不使用 Bean Validation 默认 message，也不插入原值。
- 数组按 `(field, code, message)` 排序、去重，最多 50 项，保证快照稳定并阻止放大响应。

## 5. 脱敏与失败收口

响应不得出现 `rejectedValue`、token、password、secret、Authorization、Cookie、原始 Payload、SQL、异常类名或 stack trace。框架校验 message 和 `Exception.getMessage()` 均不是公开数据源。

未分类异常统一映射为：

```text
code   = COMMON_INTERNAL_UNEXPECTED
detail = An unexpected error occurred.
```

服务端日志是否记录堆栈由 G9 的结构化日志与脱敏策略管理；“允许日志记录”不代表允许进入 HTTP 响应。

## 6. Spring MVC 接入边界

- `shared-contracts` 提供 `ProblemDescriptor`、`ProblemCode` 与不含原值的 `FieldValidationError`。
- `control-plane` 的 `ProblemDetailsResponseFactory` 使用 Spring 原生 `ProblemDetail` 构造响应。
- `GlobalProblemDetailsHandler` 当前覆盖 Bean Validation、Malformed JSON、缺失参数、类型转换、静态资源 404、显式应用错误和未分类异常。
- `ApiProblemException` 只接受冻结目录项，不接受任意动态 detail；限流/依赖错误可以声明 1～86400 秒的重试时间。
- 项目当前没有 WebFlux；若未来引入，必须复用同一机器契约与共享目录，并新增等价契约测试。

## 7. 验收与后续任务

```powershell
pnpm contracts:problem:verify
pnpm contracts:problem:test
mvn clean verify
```

- 静态验证锁定 RFC、媒体类型、成员、URI、类别映射、错误目录、trace、字段错误、脱敏、Java 源绑定和 package 门禁。
- 反向变异验证故意破坏标准、媒体类型、成员、trace、字段错误、安全边界、状态、URI、重试性和源绑定，必须全部失败。
- Java 测试验证真实序列化响应不回显 query、rejectedValue、异常 message 或类名。
- G2-10：将本契约引用到 OpenAPI 3.1，并补充 DSL/Event Schema 与兼容性检查。
- G6：把 OIDC/RBAC/配额/依赖异常映射到相应目录码。
- G9：把 traceId 与 OpenTelemetry、结构化日志和审计关联起来。
