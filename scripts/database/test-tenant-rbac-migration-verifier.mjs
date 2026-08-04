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
const verifier = path.join(
  repositoryRoot,
  'scripts',
  'database',
  'verify-tenant-rbac-migration.mjs',
);
const sourceModel = path.join(repositoryRoot, 'config', 'database', 'er-model.json');
const sourceMigration = path.join(
  repositoryRoot,
  'control-plane',
  'src',
  'main',
  'resources',
  'db',
  'migration',
  'V1__create_tenant_and_rbac.sql',
);
const temporaryPrefix = path.join(os.tmpdir(), 'workflow-platform-g2-04-verifier-');
const temporaryRoot = mkdtempSync(temporaryPrefix);
const model = path.join(temporaryRoot, 'er-model.json');
const migration = path.join(temporaryRoot, 'V1__create_tenant_and_rbac.sql');

function run() {
  return spawnSync(
    process.execPath,
    [verifier, '--er-model', model, '--migration', migration],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
      maxBuffer: 2 * 1024 * 1024,
      windowsHide: true,
    },
  );
}

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function expect(status, fragment) {
  const result = run();
  const actualOutput = output(result);
  if (result.status !== status || !actualOutput.includes(fragment)) {
    throw new Error(
      `Unexpected G2-04 verifier result; status=${result.status}, expected=${status}, output=${actualOutput}`,
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
  expect(0, 'G2-04 migration model verification PASS');

  mutateMigration(
    'ALTER TABLE public.tenant FORCE ROW LEVEL SECURITY;',
    '-- FORCE RLS removed by negative test',
  );
  expect(1, 'tenant: required RLS statement is missing');

  mutateMigration(
    'REFERENCES public.project (tenant_id, id) ON DELETE RESTRICT',
    'REFERENCES public.project (id, tenant_id) ON DELETE RESTRICT',
  );
  expect(1, 'fk_membership_project: composite target or ON DELETE RESTRICT drift');

  mutateMigration(
    'UNIQUE NULLS NOT DISTINCT (tenant_id, project_id, principal_subject)',
    'UNIQUE (tenant_id, project_id, principal_subject)',
  );
  expect(1, 'uq_membership_scope_subject: NULLS NOT DISTINCT is missing');

  mutateMigration(
    'CREATE TABLE public.tenant (',
    'CREATE TABLE public.untracked (id uuid);\n\nCREATE TABLE public.tenant (',
  );
  expect(1, 'G2-04 CREATE TABLE set drift');

  mutateMigration(
    'GRANT SELECT, INSERT, UPDATE ON TABLE',
    'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE',
  );
  expect(1, 'runtime roles must not receive DELETE privileges');

  mutateMigration('created_at timestamptz(6)', 'created_at timestamp');
  expect(1, 'tenant.created_at: expected type timestamptz(6)');

  console.log(
    'G2-04 verifier self-test PASS: forced RLS, composite FK, NULL-aware uniqueness, untracked table, DELETE grant, and timestamp drift are blocked.',
  );
} finally {
  const resolvedTemporaryRoot = path.resolve(temporaryRoot);
  const resolvedPrefix = path.resolve(os.tmpdir(), 'workflow-platform-g2-04-verifier-');
  if (!resolvedTemporaryRoot.startsWith(resolvedPrefix)) {
    throw new Error(`Refusing to remove unexpected temporary path: ${resolvedTemporaryRoot}`);
  }
  rmSync(resolvedTemporaryRoot, { recursive: true, force: true });
}
