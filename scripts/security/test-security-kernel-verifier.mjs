import { spawnSync } from 'node:child_process';
const result = spawnSync(process.execPath, ['scripts/security/verify-security-kernel.mjs'], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.stderr || result.stdout);
console.log('G6 security kernel verifier self-test PASS.');
