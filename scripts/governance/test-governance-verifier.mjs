import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'governance', 'verify-governance.mjs');
const source = path.join(repositoryRoot, 'config', 'governance', 'policy.json');
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-governance-test-'));

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function run(policyPath, skipDocuments = true) {
  const args = [verifier, '--policy', policyPath];
  if (skipDocuments) args.push('--skip-documents');
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    windowsHide: true,
  });
}

function writeCase(name, mutate) {
  const policy = JSON.parse(readFileSync(source, 'utf8'));
  mutate(policy);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(policy, null, 2)}\n`, 'utf8');
  return target;
}

function expectFailure(name, mutate, expectedMessage, skipDocuments = true) {
  const result = run(writeCase(name, mutate), skipDocuments);
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expectedMessage)) {
    throw new Error(`${name} was not blocked as expected (${expectedMessage}):\n${combined}`);
  }
}

try {
  const positive = run(source, false);
  if (positive.status !== 0) throw new Error(`Positive governance policy failed:\n${output(positive)}`);

  expectFailure(
    'duplicate-branch-type',
    (policy) => policy.branching.allowedTypes.push('feat'),
    'branching.allowedTypes contains duplicate entries',
  );
  expectFailure(
    'broad-branch-pattern',
    (policy) => {
      policy.branching.pattern = '^.+$';
    },
    'branch invalid example was accepted',
  );
  expectFailure(
    'broad-commit-pattern',
    (policy) => {
      policy.commits.headerPattern = '^.+$';
    },
    'commit invalid example was accepted',
  );
  expectFailure(
    'invalid-semver',
    (policy) => {
      policy.versions.currentBaseVersion = '01.0.0';
      policy.versions.mavenDevelopmentVersion = '01.0.0-SNAPSHOT';
    },
    'is not stable SemVer',
  );
  expectFailure(
    'missing-dod',
    (policy) => policy.definitionOfDone.requiredChecks.pop(),
    'definitionOfDone.requiredChecks must contain',
  );
  expectFailure(
    'broad-migration-pattern',
    (policy) => {
      policy.contracts.database.migrationPattern = '^.+$';
    },
    'migration filename invalid example was accepted',
  );
  expectFailure(
    'missing-document',
    (policy) => policy.requiredDocuments.push('docs/governance/does-not-exist.md'),
    'required governance document does not exist',
    false,
  );

  console.log(
    'Governance verifier tests PASS: positive policy accepted; duplicate type, broad branch/commit/migration rules, invalid SemVer, missing DoD, and missing document blocked.',
  );
} finally {
  const resolvedTemporaryRoot = path.resolve(temporaryRoot);
  const safePrefix = path.resolve(os.tmpdir(), 'workflow-governance-test-');
  if (!resolvedTemporaryRoot.startsWith(safePrefix)) {
    throw new Error(`Refusing to remove unexpected temporary path: ${resolvedTemporaryRoot}`);
  }
  rmSync(resolvedTemporaryRoot, { recursive: true, force: true });
}
