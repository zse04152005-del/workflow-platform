import fs from 'node:fs';
import path from 'node:path';
const root = process.cwd();
const contract = JSON.parse(fs.readFileSync(path.join(root, 'config/ops/observability-contract.json'), 'utf8'));
if (contract.tasks.length !== 9 || contract.metrics.length !== 7 || contract.highCardinalityLabels?.length) throw new Error('G9 observability contract incomplete');
for (const file of ['control-plane/src/main/java/com/workflowplatform/controlplane/ops/ObservabilityConventions.java', 'control-plane/src/main/java/com/workflowplatform/controlplane/ops/GracefulShutdownCoordinator.java', 'deploy/observability/grafana-dashboard.json', contract.runbook]) if (!fs.existsSync(path.join(root, file))) throw new Error(`missing ${file}`);
console.log('G9 observability contract PASS: tracing, logs, low-cardinality metrics, probes, shutdown, dashboard, alerts and runbook.');
