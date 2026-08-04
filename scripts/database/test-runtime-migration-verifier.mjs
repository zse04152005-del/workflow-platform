import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'database', 'verify-runtime-migration.mjs');
const model = path.join(repositoryRoot, 'config', 'database', 'er-model.json');
const migration = path.join(
  repositoryRoot,
  'control-plane',
  'src',
  'main',
  'resources',
  'db',
  'migration',
  'V3__create_runtime_and_schedule.sql',
);
const source = readFileSync(migration, 'utf8');
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-g2-06-verifier-test-'));

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function run(migrationPath) {
  return spawnSync(
    process.execPath,
    [verifier, '--er-model', model, '--migration', migrationPath],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
      maxBuffer: 10 * 1024 * 1024,
      windowsHide: true,
    },
  );
}

function expectFailure(name, mutate, expectedMessage) {
  const target = path.join(temporaryRoot, `${name}.sql`);
  writeFileSync(target, mutate(source), 'utf8');
  const result = run(target);
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expectedMessage)) {
    throw new Error(`${name} was not blocked as expected (${expectedMessage}):\n${combined}`);
  }
}

try {
  const positive = run(migration);
  if (positive.status !== 0) {
    throw new Error(`Positive G2-06 verifier failed:\n${output(positive)}`);
  }

  expectFailure(
    'missing-force-rls',
    (sql) => sql.replace(
      'ALTER TABLE public.workflow_instance FORCE ROW LEVEL SECURITY;',
      '-- FORCE RLS removed',
    ),
    'required RLS statement is missing',
  );
  expectFailure(
    'definition-version-binding',
    (sql) => sql.replace(
      'FOREIGN KEY (tenant_id, workflow_version_id, definition_id)',
      'FOREIGN KEY (tenant_id, workflow_version_id)',
    ),
    'fk_workflow_instance_version: composite target or ON DELETE RESTRICT drift',
  );
  expectFailure(
    'cron-snapshot-binding',
    (sql) => sql.replace(
      'FOREIGN KEY (tenant_id, trigger_config_id, timezone, misfire_policy)',
      'FOREIGN KEY (tenant_id, trigger_config_id)',
    ),
    'fk_schedule_occurrence_trigger: composite target or ON DELETE RESTRICT drift',
  );
  expectFailure(
    'weak-node-status',
    (sql) => sql.replace("'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED'", "'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED'"),
    'ck_node_instance_status: CHECK expression drift',
  );
  expectFailure(
    'weak-node-lease',
    (sql) => sql.replace('AND lease_expire_at > updated_at', 'AND lease_expire_at IS NOT NULL'),
    'ck_node_instance_lease: CHECK expression drift',
  );
  expectFailure(
    'weak-node-schedule',
    (sql) => sql.replace('AND next_schedule_at >= updated_at', 'AND next_schedule_at IS NOT NULL'),
    'ck_node_instance_schedule: CHECK expression drift',
  );
  expectFailure(
    'missing-task-trigger',
    (sql) => sql.replace(
      'CREATE TRIGGER task_execution_finalization_guard',
      '-- CREATE TRIGGER task_execution_finalization_guard',
    ),
    'G2-06 trigger set drift',
  );
  expectFailure(
    'delete-grant',
    (sql) => sql.replace('GRANT SELECT, INSERT, UPDATE ON TABLE', 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE'),
    'G2-06 runtime table grant contract drift',
  );
  expectFailure(
    'weak-digest',
    (sql) => sql.replace("input_digest ~ '^[0-9a-f]{64}$'", 'input_digest IS NOT NULL'),
    'ck_workflow_instance_input_digest: CHECK expression drift',
  );
  expectFailure(
    'weak-trace',
    (sql) => sql.replace("AND trace_id <> repeat('0', 32)", ''),
    'ck_workflow_instance_trace_id: CHECK expression drift',
  );
  expectFailure(
    'tenant-index-drift',
    (sql) => sql.replace(
      'ON public.node_instance (tenant_id, status, next_schedule_at, id)',
      'ON public.node_instance (status, next_schedule_at, id)',
    ),
    'idx_node_instance_schedule: column order, sort order, or predicate drift',
  );
  expectFailure(
    'partial-index-drift',
    (sql) => sql.replace("WHERE status = 'PENDING';", "WHERE status IN ('PENDING', 'CLAIMED');"),
    'idx_schedule_occurrence_due: column order, sort order, or predicate drift',
  );
  expectFailure(
    'untracked-table',
    (sql) => `${sql}\nCREATE TABLE public.runtime_shadow (id uuid);\n`,
    'G2-06 CREATE TABLE set drift',
  );

  console.log(
    'G2-06 verifier self-test PASS: forced RLS, definition/version and Cron snapshot bindings, ' +
      'state/lease/schedule checks, one-way task finalization, no DELETE grants, digest/trace guards, ' +
      'tenant-leading partial indexes, and exact table ownership are mutation-tested.',
  );
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
