import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(root, 'scripts', 'runtime', 'verify-trigger-component.mjs');
const source = path.join(root, 'config', 'runtime', 'trigger-component.json');
const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g4-03-verifier-'));

function run(contract) {
  return spawnSync(process.execPath, [verifier, contract], {
    cwd: root,
    encoding: 'utf8',
  });
}

function expectFailure(name, mutate) {
  const contract = JSON.parse(readFileSync(source, 'utf8'));
  mutate(contract);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(contract, null, 2)}\n`, 'utf8');
  const result = run(target);
  if (result.status === 0 || !`${result.stdout}${result.stderr}`.includes('trigger replay contract is not strict')) {
    throw new Error(`${name} mutation was not rejected:\n${result.stdout}${result.stderr}`);
  }
}

try {
  const positive = run(source);
  if (positive.status !== 0) {
    throw new Error(`positive G4-03 verifier failed:\n${positive.stdout}${positive.stderr}`);
  }
  expectFailure('replay-window', (contract) => { contract.replayWindowHours = 1; });
  expectFailure('idempotency-header', (contract) => { contract.requiredHeaders = []; });
  expectFailure('transaction-boundary', (contract) => { contract.transactionBoundary.pop(); });
  console.log('G4-03 trigger verifier negative tests PASS: 3/3 mutations rejected.');
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
