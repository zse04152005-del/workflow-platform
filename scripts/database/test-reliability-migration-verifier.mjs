import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'database', 'verify-reliability-migration.mjs');
const model = path.join(repositoryRoot, 'config', 'database', 'er-model.json');
const migration = path.join(
  repositoryRoot,
  'control-plane',
  'src',
  'main',
  'resources',
  'db',
  'migration',
  'V4__create_reliability_and_leases.sql',
);
const source = readFileSync(migration, 'utf8');
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-g2-07-verifier-test-'));

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
    throw new Error(`Positive G2-07 verifier failed:\n${output(positive)}`);
  }

  expectFailure(
    'missing-force-rls',
    (sql) => sql.replace(
      'ALTER TABLE public.outbox_event FORCE ROW LEVEL SECURITY;',
      '-- FORCE RLS removed',
    ),
    'required RLS statement is missing',
  );
  expectFailure(
    'attempt-node-binding',
    (sql) => sql.replace(
      'FOREIGN KEY (tenant_id, task_execution_id, node_instance_id)',
      'FOREIGN KEY (tenant_id, task_execution_id)',
    ),
    'fk_dead_letter_execution: composite target or ON DELETE RESTRICT drift',
  );
  expectFailure(
    'predecessor-binding',
    (sql) => sql.replace(
      'UNIQUE (tenant_id, id, node_instance_id);',
      'UNIQUE (tenant_id, id);',
    ),
    'uq_task_execution_dead_letter_binding: predecessor UNIQUE binding drift',
  );
  expectFailure(
    'outbox-tenant-index',
    (sql) => sql.replace(
      'ON public.outbox_event (tenant_id, next_publish_at, id)',
      'ON public.outbox_event (next_publish_at, id)',
    ),
    'idx_outbox_event_unpublished: uniqueness, column order, sort order, or predicate drift',
  );
  expectFailure(
    'worker-tenant-index',
    (sql) => sql.replace(
      'ON public.worker_lease (tenant_id, expires_at, id)',
      'ON public.worker_lease (expires_at, id)',
    ),
    'idx_worker_lease_expiry: uniqueness, column order, sort order, or predicate drift',
  );
  expectFailure(
    'active-lease-uniqueness',
    (sql) => sql.replace(
      "WHERE status = 'ACTIVE';",
      "WHERE status IN ('ACTIVE', 'EXPIRED');",
    ),
    'uq_worker_lease_active_node: uniqueness, column order, sort order, or predicate drift',
  );
  expectFailure(
    'weak-outbox-lease',
    (sql) => sql.replace(
      'AND publish_lease_until > created_at',
      'AND publish_lease_until IS NOT NULL',
    ),
    'ck_outbox_event_lease: CHECK expression drift',
  );
  expectFailure(
    'weak-idempotency-response',
    (sql) => sql.replace(
      'AND response_status BETWEEN 100 AND 599',
      'AND response_status IS NOT NULL',
    ),
    'ck_idempotency_record_response: CHECK expression drift',
  );
  expectFailure(
    'weak-dead-letter-lifecycle',
    (sql) => sql.replace(
      'AND disposition_count > 0',
      'AND disposition_count >= 0',
    ),
    'ck_dead_letter_disposition: CHECK expression drift',
  );
  expectFailure(
    'worker-audit-column',
    (sql) => sql.replace(
      'CREATE TABLE public.worker_lease (\n' +
        '    id uuid NOT NULL,\n' +
        '    tenant_id uuid NOT NULL,\n' +
        '    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),\n' +
        '    created_by varchar(255) NOT NULL,\n' +
        '    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),\n' +
        '    updated_by varchar(255) NOT NULL,',
      'CREATE TABLE public.worker_lease (\n' +
        '    id uuid NOT NULL,\n' +
        '    tenant_id uuid NOT NULL,\n' +
        '    created_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),\n' +
        '    created_by varchar(255) NOT NULL,\n' +
        '    updated_at timestamptz(6) NOT NULL DEFAULT transaction_timestamp(),',
    ),
    'worker_lease columns drift',
  );
  expectFailure(
    'missing-processed-guard',
    (sql) => sql.replace(
      'CREATE TRIGGER processed_event_immutability_guard',
      '-- CREATE TRIGGER processed_event_immutability_guard',
    ),
    'G2-07 trigger set drift',
  );
  expectFailure(
    'weak-worker-fencing',
    (sql) => sql.replaceAll(
      '           AND lease_generation = NEW.lease_generation\n',
      '',
    ),
    'guard_worker_lease_transition: required guard token is missing',
  );
  expectFailure(
    'processed-update-grant',
    (sql) => sql.replace(
      'GRANT SELECT, INSERT ON TABLE public.processed_event',
      'GRANT SELECT, INSERT, UPDATE ON TABLE public.processed_event',
    ),
    'processed_event append-only grant contract drift',
  );
  expectFailure(
    'delete-grant',
    (sql) => sql.replace(
      'GRANT SELECT, INSERT, UPDATE ON TABLE',
      'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE',
    ),
    'G2-07 mutable reliability-table grant contract drift',
  );
  expectFailure(
    'untracked-table',
    (sql) => `${sql}\nCREATE TABLE public.reliability_shadow (id uuid);\n`,
    'G2-07 CREATE TABLE set drift',
  );

  console.log(
    'G2-07 verifier self-test PASS: forced RLS, attempt/node binding, Outbox and Worker tenant-leading indexes, ' +
      'active-lease uniqueness, publish/idempotency/dead-letter checks, mutable Worker audit, lifecycle guards, ' +
      'fencing tokens, append-only consumer deduplication, no DELETE grants, and exact table ownership are mutation-tested.',
  );
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
