import {
  copyFileSync,
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
const verifier = path.join(repositoryRoot, 'scripts', 'database', 'verify-definition-migration.mjs');
const sourceModel = path.join(repositoryRoot, 'config', 'database', 'er-model.json');
const sourceMigration = path.join(
  repositoryRoot,
  'control-plane',
  'src',
  'main',
  'resources',
  'db',
  'migration',
  'V2__create_definition_and_integrations.sql',
);
const temporaryPrefix = path.join(os.tmpdir(), 'workflow-platform-g2-05-verifier-');
const temporaryRoot = mkdtempSync(temporaryPrefix);
const model = path.join(temporaryRoot, 'er-model.json');
const migration = path.join(temporaryRoot, 'V2__create_definition_and_integrations.sql');

function run() {
  return spawnSync(process.execPath, [verifier, '--er-model', model, '--migration', migration], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 2 * 1024 * 1024,
    windowsHide: true,
  });
}

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function expect(status, fragment) {
  const result = run();
  const actualOutput = output(result);
  if (result.status !== status || !actualOutput.includes(fragment)) {
    throw new Error(
      `Unexpected G2-05 verifier result; status=${result.status}, expected=${status}, output=${actualOutput}`,
    );
  }
}

function mutateMigration(replace, replacement) {
  copyFileSync(sourceMigration, migration);
  const original = readFileSync(migration, 'utf8');
  if (!original.includes(replace)) throw new Error(`Mutation token is missing: ${replace}`);
  writeFileSync(migration, original.replace(replace, replacement), 'utf8');
}

try {
  mkdirSync(temporaryRoot, { recursive: true });
  copyFileSync(sourceModel, model);
  copyFileSync(sourceMigration, migration);
  expect(0, 'G2-05 migration model verification PASS');

  mutateMigration(
    'ALTER TABLE public.workflow_definition FORCE ROW LEVEL SECURITY;',
    '-- FORCE RLS removed by negative test',
  );
  expect(1, 'workflow_definition: required RLS statement is missing');

  mutateMigration(
    'REFERENCES public.workflow_definition (tenant_id, id) ON DELETE RESTRICT',
    'REFERENCES public.workflow_definition (id, tenant_id) ON DELETE RESTRICT',
  );
  expect(1, 'fk_workflow_version_definition: composite target or ON DELETE RESTRICT drift');

  mutateMigration('NULLS NOT DISTINCT\n    WHERE deleted_at IS NULL;', 'WHERE deleted_at IS NULL;');
  expect(1, 'uq_connector_active_name: partial uniqueness, NULL handling, or predicate drift');

  mutateMigration('WHERE deleted_at IS NULL;', 'WHERE deleted_at IS NOT NULL;');
  expect(1, 'uq_connector_active_name: partial uniqueness, NULL handling, or predicate drift');

  mutateMigration(
    'CREATE TRIGGER workflow_version_immutable',
    '-- immutable trigger removed\n-- CREATE TRIGGER workflow_version_immutable',
  );
  expect(1, 'G2-05 immutable trigger set drift');

  mutateMigration(
    'GRANT SELECT, INSERT ON TABLE',
    'GRANT SELECT, INSERT, UPDATE ON TABLE',
  );
  expect(1, 'immutable runtime table grant contract drift');

  mutateMigration('AND ciphertext IS NULL', '-- ciphertext exclusivity removed');
  expect(1, 'ck_secret_reference_material: CHECK expression drift');

  mutateMigration("draft_checksum ~ '^[0-9a-f]{64}$'", "draft_checksum <> ''");
  expect(1, 'ck_workflow_definition_checksum: CHECK expression drift');

  mutateMigration("jsonb_typeof(dsl) = 'object'", "jsonb_typeof(dsl) IN ('object', 'array')");
  expect(1, 'ck_workflow_version_dsl_object: CHECK expression drift');

  mutateMigration('version_no DESC);', 'version_no ASC);');
  expect(1, 'idx_workflow_version_definition: column order, sort order, or predicate drift');

  mutateMigration(
    'CREATE TABLE public.workflow_definition (',
    'CREATE TABLE public.untracked (id uuid);\n\nCREATE TABLE public.workflow_definition (',
  );
  expect(1, 'G2-05 CREATE TABLE set drift');

  console.log(
    'G2-05 verifier self-test PASS: forced RLS, tenant FK order, partial NULL-aware uniqueness, ' +
      'immutability, grants, secret XOR, checksum/JSON checks, DESC order, and untracked tables are blocked.',
  );
} finally {
  const resolvedTemporaryRoot = path.resolve(temporaryRoot);
  const resolvedPrefix = path.resolve(os.tmpdir(), 'workflow-platform-g2-05-verifier-');
  if (!resolvedTemporaryRoot.startsWith(resolvedPrefix)) {
    throw new Error(`Refusing to remove unexpected temporary path: ${resolvedTemporaryRoot}`);
  }
  rmSync(resolvedTemporaryRoot, { recursive: true, force: true });
}
