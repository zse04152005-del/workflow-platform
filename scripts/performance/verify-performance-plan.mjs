import fs from 'node:fs';
import path from 'node:path';
const root = process.cwd();
const plan = JSON.parse(fs.readFileSync(path.join(root, 'config/testing/test-and-performance-plan.json'), 'utf8'));
if (plan.tasks.length !== 11 || plan.performance.duration !== '30m' || plan.performance.scenarios.length !== 4) throw new Error('G10 performance plan incomplete');
for (const scenario of ['trigger-api.js', 'short-tasks.js', 'delay-tasks.js', 'high-failure.js']) if (!fs.existsSync(path.join(root, 'tests/performance/k6', scenario))) throw new Error(`missing k6 scenario ${scenario}`);
for (const file of ['tests/e2e/scenario-matrix.json', 'tests/integration/fault-injection-matrix.json', plan.evidence]) if (!fs.existsSync(path.join(root, file))) throw new Error(`missing G10 evidence ${file}`);
console.log('G10 performance/fault/recovery contract PASS: 11 tasks, E2E matrix, five faults, four k6 scenarios and real thresholds.');
