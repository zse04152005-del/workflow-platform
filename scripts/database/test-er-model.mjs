import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'database', 'verify-er-model.mjs');
const source = path.join(repositoryRoot, 'config', 'database', 'er-model.json');
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-er-model-test-'));

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function run(modelPath, skipDocument = true) {
  const args = [verifier, '--model', modelPath];
  if (skipDocument) args.push('--skip-document');
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    windowsHide: true,
  });
}

function writeCase(name, mutate) {
  const model = JSON.parse(readFileSync(source, 'utf8'));
  mutate(model);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(model, null, 2)}\n`, 'utf8');
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
  if (positive.status !== 0) throw new Error(`Positive ER model failed:\n${output(positive)}`);

  expectFailure(
    'duplicate-table',
    (model) => model.tables.push(structuredClone(model.tables[0])),
    'ER model contains duplicate table names',
  );
  expectFailure(
    'missing-tenant-column',
    (model) => {
      model.tables.find((table) => table.name === 'workflow_instance').profiles = [
        'uuid-pk',
        'mutable-audit',
      ];
    },
    'workflow_instance: tenant-scoped table must contain tenant_id',
  );
  expectFailure(
    'rls-disabled',
    (model) => {
      model.tables.find((table) => table.name === 'secret_reference').rls.enabled = false;
    },
    'secret_reference: RLS must be enabled',
  );
  expectFailure(
    'cross-tenant-fk',
    (model) => {
      const foreignKey = model.tables
        .find((table) => table.name === 'node_instance')
        .foreignKeys.find((item) => item.targetTable === 'workflow_instance');
      foreignKey.columns = ['workflow_instance_id'];
      foreignKey.targetColumns = ['id'];
    },
    'cross-tenant FK must start with tenant_id',
  );
  expectFailure(
    'cascade-delete',
    (model) => {
      model.tables.find((table) => table.name === 'task_execution').foreignKeys[1].onDelete =
        'CASCADE';
    },
    'ON DELETE must be RESTRICT',
  );
  expectFailure(
    'owner-drift',
    (model) => {
      model.tables.find((table) => table.name === 'outbox_event').owner = 'runtime';
    },
    'outbox_event: owner drift',
  );
  expectFailure(
    'missing-query-index',
    (model) => {
      const table = model.tables.find((item) => item.name === 'node_instance');
      table.indexes = table.indexes.filter((index) => index.name !== 'idx_node_instance_schedule');
    },
    'Q-03: required index idx_node_instance_schedule is missing',
  );
  expectFailure(
    'invalid-partition-key',
    (model) => {
      model.tables.find((table) => table.name === 'audit_log').partitioning.key = 'actor_id';
    },
    'audit_log: monthly partition key must exist and be timestamptz(6)',
  );
  expectFailure(
    'missing-er-document',
    (model) => {
      model.document = 'docs/architecture/does-not-exist.md';
    },
    'ER document does not exist',
    false,
  );

  console.log(
    'ER model tests PASS: positive model accepted; duplicate table, missing tenant_id, disabled RLS, cross-tenant FK, cascade delete, owner drift, missing query index, invalid partition key, and missing document blocked.',
  );
} finally {
  const resolvedTemporaryRoot = path.resolve(temporaryRoot);
  const safePrefix = path.resolve(os.tmpdir(), 'workflow-er-model-test-');
  if (!resolvedTemporaryRoot.startsWith(safePrefix)) {
    throw new Error(`Refusing to remove unexpected temporary path: ${resolvedTemporaryRoot}`);
  }
  rmSync(resolvedTemporaryRoot, { recursive: true, force: true });
}
