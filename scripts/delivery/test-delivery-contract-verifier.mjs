import { spawnSync } from 'node:child_process';
const result = spawnSync(process.execPath, ['scripts/delivery/verify-delivery-contract.mjs'], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.stderr || result.stdout);
console.log('G11 delivery verifier self-test PASS.');
