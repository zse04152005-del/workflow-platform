# G5 可靠性、恢复与实例控制验收索引

G5-01～G5-08 已于 2026-08-04 完成实现与专项验收，详见
[`G5-01-G5-08-reliability-verification.md`](G5-01-G5-08-reliability-verification.md)。

G5-09～G5-11 的重放、暂停/恢复与协作式取消证据见
[`G5-09-G5-11-runtime-control-verification.md`](G5-09-G5-11-runtime-control-verification.md)。

G5-12～G5-13 的 Payload 对象存储与故障注入证据见
[`G5-12-G5-13-verification.md`](G5-12-G5-13-verification.md)。

截至 2026-08-04，G5-01～G5-13 均已实现并通过机器契约、反向变异、真实 PostgreSQL/Kafka/MinIO 集成测试和全量质量门禁，Gate G5 已闭环。
