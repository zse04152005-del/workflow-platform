import { spawnSync } from 'node:child_process';
const result = spawnSync(process.execPath, ['scripts/ops/verify-observability-contract.mjs'], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.stderr || result.stdout);
console.log('G9 observability verifier self-test PASS.');
