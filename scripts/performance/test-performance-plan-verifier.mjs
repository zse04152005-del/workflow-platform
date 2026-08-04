import { spawnSync } from 'node:child_process';
const result = spawnSync(process.execPath, ['scripts/performance/verify-performance-plan.mjs'], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.stderr || result.stdout);
console.log('G10 performance-plan verifier self-test PASS.');
