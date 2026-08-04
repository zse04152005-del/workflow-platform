import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(root, 'scripts', 'runtime', 'verify-runtime-happy-path-e2e.mjs');
const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g4-17-verifier-'));

function run(...arguments_) {
  return spawnSync(process.execPath, [verifier, ...arguments_], { cwd: root, encoding: 'utf8' });
}
function reject(name, flag, source, mutate, fragment) {
  const target = path.join(temporaryRoot, `${name}${path.extname(source)}`);
  writeFileSync(target, mutate(readFileSync(source, 'utf8')), 'utf8');
  const result = run(flag, target);
  const output = `${result.stdout}${result.stderr}`;
  if (result.status === 0 || !output.includes(fragment)) throw new Error(`${name} mutation was not rejected:\n${output}`);
}

try {
  const positive = run();
  if (positive.status !== 0) throw new Error(`positive G4-17 verifier failed:\n${positive.stdout}${positive.stderr}`);
  reject(
    'in-memory-database', '--test', path.join(root, 'test-support', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'RuntimeHappyPathE2EIT.java'),
    (source) => source.replace('PostgreSQLContainer<?>', 'Object'), 'PostgreSQLContainer<?>',
  );
  reject(
    'missing-restart', '--test', path.join(root, 'test-support', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'RuntimeHappyPathE2EIT.java'),
    (source) => source.replace('"scheduler-after-restart"', '"scheduler-e2e"'), 'scheduler-after-restart',
  );
  reject(
    'flow-drift', '--contract', path.join(root, 'config', 'runtime', 'happy-path-e2e.json'),
    (source) => source.replace('"delay", ', ''), 'G4-17 ordered flow drift',
  );
  console.log('G4-17 verifier negative tests PASS: 3/3 fake-database, missing-restart and flow-drift mutations rejected.');
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
