import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'database', 'verify-migrations.mjs');
const temporaryPrefix = path.join(os.tmpdir(), 'workflow-platform-migration-test-');
const temporaryRoot = mkdtempSync(temporaryPrefix);

function runVerifier(extraArguments = []) {
  return spawnSync(
    process.execPath,
    [verifier, '--repository-root', temporaryRoot, ...extraArguments],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
      maxBuffer: 1024 * 1024,
      windowsHide: true,
    },
  );
}

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function expect(result, status, messageFragment) {
  const actualOutput = output(result);
  if (result.status !== status || !actualOutput.includes(messageFragment)) {
    throw new Error(
      `Unexpected migration verifier result; status=${result.status}, expected=${status}, output=${actualOutput}`,
    );
  }
}

try {
  const migrationDirectory = path.join(
    temporaryRoot,
    'control-plane',
    'src',
    'main',
    'resources',
    'db',
    'migration',
  );
  const catalogDirectory = path.join(temporaryRoot, 'config', 'migrations');
  mkdirSync(migrationDirectory, { recursive: true });
  mkdirSync(catalogDirectory, { recursive: true });
  const catalogFile = path.join(catalogDirectory, 'checksums.json');
  writeFileSync(
    catalogFile,
    `${JSON.stringify({ schemaVersion: 1, algorithm: 'sha256', migrations: {} }, null, 2)}\n`,
    'utf8',
  );

  const migrationFile = path.join(migrationDirectory, 'V1__create_baseline.sql');
  writeFileSync(migrationFile, 'CREATE TABLE baseline (id bigint PRIMARY KEY);\n', 'utf8');
  expect(runVerifier(), 1, 'Migration checksum catalog drift');

  expect(runVerifier(['--write']), 0, 'Migration checksum catalog updated: 1 migration(s).');
  expect(runVerifier(), 0, 'Migration verification PASS: 1 migration(s)');

  const originalMigration = readFileSync(migrationFile, 'utf8');
  writeFileSync(migrationFile, `${originalMigration}-- tampered after registration\n`, 'utf8');
  expect(runVerifier(), 1, 'Migration checksum mismatch');

  console.log(
    'Migration verifier self-test PASS: unregistered migration blocked, registered migration accepted, checksum tampering blocked.',
  );
} finally {
  const resolvedTemporaryRoot = path.resolve(temporaryRoot);
  const resolvedPrefix = path.resolve(os.tmpdir(), 'workflow-platform-migration-test-');
  if (!resolvedTemporaryRoot.startsWith(resolvedPrefix)) {
    throw new Error(`Refusing to remove unexpected temporary path: ${resolvedTemporaryRoot}`);
  }
  rmSync(resolvedTemporaryRoot, { recursive: true, force: true });
}
