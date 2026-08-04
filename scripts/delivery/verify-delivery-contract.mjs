import fs from 'node:fs';
import path from 'node:path';
const root = process.cwd();
const contract = JSON.parse(fs.readFileSync(path.join(root, 'config/delivery/delivery-contract.json'), 'utf8'));
if (contract.tasks.length !== 10 || contract.deployment.length !== 6 || contract.release.length < 7) throw new Error('G11 delivery contract incomplete');
for (const file of ['deploy/compose/compose.yml', 'deploy/helm/workflow-platform/Chart.yaml', 'deploy/kustomize/base/kustomization.yaml', '.github/workflows/image-security.yml', 'scripts/demo/run-demo.ps1', 'docs/release/0.1.0-MVP.md']) if (!fs.existsSync(path.join(root, file))) throw new Error(`missing delivery artifact ${file}`);
console.log('G11 delivery contract PASS: local stack, docs, manifests, CI security, migration policy, demo, operations and MVP notes.');
