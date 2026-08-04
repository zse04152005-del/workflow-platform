# Testcontainers 全量数据库验收基线

## 1. 范围与结论

G2-11 使用 Testcontainers 1.21.4 和固定 digest 的 PostgreSQL 18.4，对当前 Flyway V1～V7 的完整 Schema 做真实验收。V6 为 G3-06 追加可空 `description` 字段并实现幂等键过期窗口的受限重置；V7 为 G4-04/G4-10 追加不可变输入与节点配置快照；V1～V5 仍保持冻结。该门禁补齐全目录计数、统一约束反例、默认优化器查询计划和重复迁移验证，但不替代 G10 的高并发锁竞争、长时间压测与容量基准。

验收命令：

```powershell
pnpm database:g2-11:verify
pnpm database:g2-11:test
pnpm database:g2-11:integration
```

静态正向门禁锁定配置、Java、POM、fixture、执行脚本、ER 查询契约和本文档；反向门禁证明 12 类关键漂移会被拒绝。集成命令通过 Maven Wrapper 启动一次性 PostgreSQL 容器，JVM 退出时由 Ryuk 清理，不复用容器、不挂载 Compose 数据卷。

## 2. 固定版本与隔离

- PostgreSQL 镜像：`postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296`。
- Flyway：11.7.2；运行时从 `META-INF/maven/org.flywaydb/flyway-core/pom.properties` 读取并断言版本。
- Testcontainers：1.21.4，由 Spring Boot 3.5.16 依赖管理解析。
- 数据库、用户与密码仅供一次测试进程使用；随机密码不会写入仓库或日志证据。
- 测试容器 `reuse=false`，使用随机宿主端口和 G2-11 标签，不访问现有开发数据库。

## 3. 迁移与 Catalog 契约

空库必须按 V1→V7 执行 7 项迁移，`validate` 成功，第二次 `migrate` 执行 0 项，当前版本为 7。业务目录实测口径排除 `flyway_schema_history`：

| 项目 | 数量 |
|---|---:|
| 业务表 / 列 | 22 / 330 |
| PK / FK / CHECK | 22 / 39 / 102 |
| UNIQUE 约束 | 41 |
| 独立部分 UNIQUE 索引 | 3 |
| 唯一性保障合计 | 44 |
| `idx_` 业务查询索引 | 47 |
| ENABLE + FORCE RLS | 22 |

41 个 `pg_constraint.contype='u'` 与 3 个不依附约束的条件唯一索引必须分别统计；后者用于 connector、secret reference 和 active worker lease 的条件唯一性。不能把三者错误声明为普通 UNIQUE 约束。

## 4. 约束、RLS 与生命周期反例

集成测试验证以下失败模式及 SQLSTATE：

- `ck_tenant_status`：非法状态返回 `23514`。
- `fk_workflow_definition_project`：跨租户复合外键返回 `23503`。
- `uq_tenant_normalized_name`：重复租户规范名返回 `23505`。
- `tenant_tenant_isolation`：应用角色跨租户读取为 0 行，跨租户写入返回 `42501`。
- `audit_log_append_only_guard`：更新追加型审计记录返回 `55000`。

这些探针验证数据库强制边界，不使用超级用户绕过 RLS，也不把应用层校验冒充数据库约束。

## 5. 查询计划契约

fixture 为关键查询族各生成 6000 行代表性数据并执行 `ANALYZE`。Q-01～Q-10 与 `config/database/er-model.json` 完全一致；测试使用 `EXPLAIN (FORMAT JSON, COSTS TRUE)`，要求计划出现审核过的精确索引名，且不得出现 `Seq Scan`。

禁止使用 `enable_seqscan=off`、`enable_indexscan` 或其他 planner hint 制造索引命中。验收只接受 PostgreSQL 默认优化器基于真实统计信息作出的计划，因此数据规模、选择性和 `ANALYZE` 都属于测试契约。

## 6. 已知兼容风险

Flyway 11.7.2 在本套 PostgreSQL 18.4 测试中迁移、校验和重复执行均通过，但 Flyway 运行时仍明确警告：最高已测试版本为 PostgreSQL 17。该事实不能通过屏蔽日志或放宽断言消除。

发布前应二选一：升级到官方明确覆盖 PostgreSQL 18 的 Flyway 版本并重跑同一套门禁，或把生产 PostgreSQL 固定在 Flyway 官方支持矩阵内。当前证据只能证明本项目 V1～V7 在该组合上实测通过，不能扩大解释为厂商正式支持。

## 7. 后续边界

- G10 继续覆盖并发 `SKIP LOCKED`、advisory lock 竞争、fencing、死锁与长事务。
- 分区仍保持测量后启用；G2-11 不因单次查询计划通过就提前引入分区复杂度。
- 计划耗时受主机与容器冷启动影响，本任务锁定访问路径，不伪造生产延迟或吞吐数字。
