import { spawnSync } from 'node:child_process';
const result = spawnSync(process.execPath, ['scripts/console/verify-console-contract.mjs'], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.stderr || result.stdout);
console.log('G8 console verifier self-test PASS.');
