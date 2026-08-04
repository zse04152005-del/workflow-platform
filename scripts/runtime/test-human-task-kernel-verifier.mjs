import { spawnSync } from 'node:child_process';
const result = spawnSync(process.execPath, ['scripts/runtime/verify-human-task-kernel.mjs'], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.stderr || result.stdout);
console.log('G7 human-task verifier self-test PASS.');
