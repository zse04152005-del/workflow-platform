import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(
  repositoryRoot,
  'scripts',
  'database',
  'verify-human-audit-quota-migration.mjs',
);
const modelPath = path.join(repositoryRoot, 'config', 'database', 'er-model.json');
const migrationPath = path.join(
  repositoryRoot,
  'control-plane',
  'src',
  'main',
  'resources',
  'db',
  'migration',
  'V5__create_human_audit_and_quota.sql',
);
const baselineModel = readFileSync(modelPath, 'utf8');
const baselineMigration = readFileSync(migrationPath, 'utf8');

function runVerifier(erModel, migration) {
  return spawnSync(
    process.execPath,
    [verifier, '--er-model', erModel, '--migration', migration],
    { cwd: repositoryRoot, encoding: 'utf8' },
  );
}

function replaceOnce(source, search, replacement, label) {
  const position = source.indexOf(search);
  if (position < 0) throw new Error(`${label}: mutation anchor is missing`);
  return source.slice(0, position) + replacement + source.slice(position + search.length);
}

const baseline = runVerifier(modelPath, migrationPath);
if (baseline.status !== 0) {
  throw new Error(`baseline G2-08 verifier failed:\n${baseline.stdout}\n${baseline.stderr}`);
}

const cases = [
  {
    name: 'missing-human-task-table',
    expected: 'G2-08 CREATE TABLE set drift',
    mutate: (sql) => replaceOnce(
      sql,
      'CREATE TABLE public.human_task (',
      'CREATE TABLE public.human_task_removed (',
      'missing-human-task-table',
    ),
  },
  {
    name: 'deadline-index-loses-tenant-prefix',
    expected: 'idx_human_task_deadline: column order, sort order, or predicate drift',
    mutate: (sql) => replaceOnce(
      sql,
      'ON public.human_task (tenant_id, deadline_at, id)',
      'ON public.human_task (deadline_at, id)',
      'deadline-index-loses-tenant-prefix',
    ),
  },
  {
    name: 'audit-trace-index-loses-tenant-prefix',
    expected: 'idx_audit_log_trace: column order, sort order, or predicate drift',
    mutate: (sql) => replaceOnce(
      sql,
      'ON public.audit_log (tenant_id, trace_id, occurred_at DESC, id DESC)',
      'ON public.audit_log (trace_id, occurred_at DESC, id DESC)',
      'audit-trace-index-loses-tenant-prefix',
    ),
  },
  {
    name: 'audit-platform-policy-broadened',
    expected: 'audit_log: tenant/platform-row RLS policy drift',
    mutate: (sql) => replaceOnce(
      sql,
      'tenant_id IS NULL AND public.app_is_platform_admin()',
      'tenant_id IS NULL',
      'audit-platform-policy-broadened',
    ),
  },
  {
    name: 'human-transfer-guard-removed',
    expected: 'required guard token is missing: NEW.transfer_count <> OLD.transfer_count + 1',
    mutate: (sql) => replaceOnce(
      sql,
      'NEW.transfer_count <> OLD.transfer_count + 1',
      'NEW.transfer_count < OLD.transfer_count',
      'human-transfer-guard-removed',
    ),
  },
  {
    name: 'human-terminal-immutability-removed',
    expected: 'required guard token is missing: terminal human task is immutable',
    mutate: (sql) => replaceOnce(
      sql,
      'terminal human task is immutable',
      'terminal human task may be updated',
      'human-terminal-immutability-removed',
    ),
  },
  {
    name: 'audit-delete-trigger-removed',
    expected: 'audit_log_append_only_guard: trigger event/table/function contract drift',
    mutate: (sql) => replaceOnce(
      sql,
      'BEFORE INSERT OR UPDATE OR DELETE ON public.audit_log',
      'BEFORE INSERT OR UPDATE ON public.audit_log',
      'audit-delete-trigger-removed',
    ),
  },
  {
    name: 'audit-update-granted',
    expected: 'audit_log append-only grant contract drift',
    mutate: (sql) => replaceOnce(
      sql,
      'GRANT SELECT, INSERT ON TABLE public.audit_log',
      'GRANT SELECT, INSERT, UPDATE ON TABLE public.audit_log',
      'audit-update-granted',
    ),
  },
  {
    name: 'quota-advisory-lock-removed',
    expected: 'required guard token is missing: pg_catalog.pg_advisory_xact_lock',
    mutate: (sql) => replaceOnce(
      sql,
      'pg_catalog.pg_advisory_xact_lock',
      'pg_catalog.pg_advisory_unlock',
      'quota-advisory-lock-removed',
    ),
  },
  {
    name: 'quota-overlap-end-predicate-removed',
    expected: 'required guard token is missing: existing.window_end > NEW.window_start',
    mutate: (sql) => replaceOnce(
      sql,
      'existing.window_end > NEW.window_start',
      'existing.window_end = NEW.window_start',
      'quota-overlap-end-predicate-removed',
    ),
  },
  {
    name: 'quota-limit-snapshot-mutable',
    expected: 'required guard token is missing: quota usage identity, window, and limit snapshot cannot change',
    mutate: (sql) => replaceOnce(
      sql,
      'quota usage identity, window, and limit snapshot cannot change',
      'quota usage identity may change',
      'quota-limit-snapshot-mutable',
    ),
  },
  {
    name: 'human-form-check-drift',
    expected: 'ck_human_task_form: CHECK expression drift',
    mutate: (sql) => replaceOnce(
      sql,
      "form_schema <> '{}'::jsonb",
      "form_schema = '{}'::jsonb",
      'human-form-check-drift',
    ),
  },
  {
    name: 'human-node-cascade-delete',
    expected: 'fk_human_task_node: composite target or ON DELETE RESTRICT drift',
    mutate: (sql) => replaceOnce(
      sql,
      'REFERENCES public.node_instance (tenant_id, id) ON DELETE RESTRICT',
      'REFERENCES public.node_instance (tenant_id, id) ON DELETE CASCADE',
      'human-node-cascade-delete',
    ),
  },
  {
    name: 'quota-force-rls-removed',
    expected: 'quota_usage: required RLS statement is missing',
    mutate: (sql) => replaceOnce(
      sql,
      'ALTER TABLE public.quota_usage FORCE ROW LEVEL SECURITY;',
      'ALTER TABLE public.quota_usage NO FORCE ROW LEVEL SECURITY;',
      'quota-force-rls-removed',
    ),
  },
  {
    name: 'forbidden-bigserial',
    expected: 'migration contains a forbidden domain SQL type',
    mutate: (sql) => `${sql}\n-- forbidden mutation: bigserial\n`,
  },
];

const temporaryDirectory = mkdtempSync(path.join(tmpdir(), 'workflow-g2-08-verifier-'));
try {
  const temporaryModel = path.join(temporaryDirectory, 'er-model.json');
  writeFileSync(temporaryModel, baselineModel, 'utf8');

  for (const testCase of cases) {
    const temporaryMigration = path.join(temporaryDirectory, `${testCase.name}.sql`);
    const mutated = testCase.mutate(baselineMigration);
    if (mutated === baselineMigration) throw new Error(`${testCase.name}: mutation changed nothing`);
    writeFileSync(temporaryMigration, mutated, 'utf8');
    const result = runVerifier(temporaryModel, temporaryMigration);
    const output = `${result.stdout}\n${result.stderr}`;
    if (result.status === 0) {
      throw new Error(`${testCase.name}: verifier accepted a broken migration`);
    }
    if (!output.includes(testCase.expected)) {
      throw new Error(
        `${testCase.name}: expected ${JSON.stringify(testCase.expected)} but received:\n${output}`,
      );
    }
  }
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}

console.log(
  `G2-08 migration verifier negative controls PASS: ${cases.length} human-task, audit, quota, RLS, index, and least-privilege mutations rejected.`,
);
