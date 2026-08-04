import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'domain', 'verify-domain-conventions.mjs');
const source = path.join(repositoryRoot, 'config', 'domain', 'conventions.json');
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-domain-conventions-test-'));

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function run(conventionsPath, skipDocument = true) {
  const args = [verifier, '--conventions', conventionsPath];
  if (skipDocument) args.push('--skip-document');
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    windowsHide: true,
  });
}

function writeCase(name, mutate) {
  const conventions = JSON.parse(readFileSync(source, 'utf8'));
  mutate(conventions);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(conventions, null, 2)}\n`, 'utf8');
  return target;
}

function expectFailure(name, mutate, expectedMessage, skipDocument = true) {
  const result = run(writeCase(name, mutate), skipDocument);
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expectedMessage)) {
    throw new Error(`${name} was not blocked as expected (${expectedMessage}):\n${combined}`);
  }
}

try {
  const positive = run(source, false);
  if (positive.status !== 0) throw new Error(`Positive domain conventions failed:\n${output(positive)}`);

  expectFailure(
    'broad-uuid-pattern',
    (conventions) => {
      conventions.identifiers.internal.pattern = '^.+$';
    },
    'UUIDv7 invalid example was accepted',
  );
  expectFailure(
    'broad-time-pattern',
    (conventions) => {
      conventions.time.jsonPattern = '^.+$';
    },
    'time invalid example was accepted',
  );
  expectFailure(
    'enum-fallback',
    (conventions) => {
      conventions.enums.fallbackToFirstValueAllowed = true;
    },
    'forbid ordinal/fallback behavior',
  );
  expectFailure(
    'duplicate-error-namespace',
    (conventions) => conventions.errorCodes.namespaces.push('RUNTIME'),
    'errorCodes.namespaces contains duplicate entries',
  );
  expectFailure(
    'missing-audit-field',
    (conventions) => conventions.audit.mutableRecordFields.pop(),
    'audit.mutableRecordFields must equal',
  );
  expectFailure(
    'duplicate-deletion-entity',
    (conventions) => conventions.deletion.strategies[1].appliesTo.push('tenant'),
    'deletion entity assigned to multiple strategies: tenant',
  );
  expectFailure(
    'removed-source-guard',
    (conventions) => conventions.sourceGuards.forbiddenJavaTypes.pop(),
    'sourceGuards.forbiddenJavaTypes must equal',
  );
  expectFailure(
    'missing-document',
    (conventions) => {
      conventions.document = 'docs/architecture/does-not-exist.md';
    },
    'domain conventions document does not exist',
    false,
  );

  console.log(
    'Domain conventions tests PASS: positive policy accepted; broad UUID/time rules, enum fallback, duplicate namespace, missing audit field, conflicting deletion strategy, removed source guard, and missing document blocked.',
  );
} finally {
  const resolvedTemporaryRoot = path.resolve(temporaryRoot);
  const safePrefix = path.resolve(os.tmpdir(), 'workflow-domain-conventions-test-');
  if (!resolvedTemporaryRoot.startsWith(safePrefix)) {
    throw new Error(`Refusing to remove unexpected temporary path: ${resolvedTemporaryRoot}`);
  }
  rmSync(resolvedTemporaryRoot, { recursive: true, force: true });
}
