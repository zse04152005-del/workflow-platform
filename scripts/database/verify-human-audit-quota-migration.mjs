import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function parseArguments(argv) {
  const options = {
    erModel: path.join(repositoryRoot, 'config', 'database', 'er-model.json'),
    migration: path.join(
      repositoryRoot,
      'control-plane',
      'src',
      'main',
      'resources',
      'db',
      'migration',
      'V5__create_human_audit_and_quota.sql',
    ),
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument !== '--er-model' && argument !== '--migration') {
      throw new Error(`Unknown argument: ${argument}`);
    }
    const value = argv[index + 1];
    if (!value) throw new Error(`${argument} requires a path`);
    options[argument === '--er-model' ? 'erModel' : 'migration'] = path.resolve(value);
    index += 1;
  }
  return options;
}

function fail(message) {
  throw new Error(message);
}

function escapeRegularExpression(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
}

function compact(value) {
  return value.replace(/\s+/gu, ' ').trim();
}

function sqlSignature(value) {
  return value.replace(/\s+/gu, '').toLowerCase();
}

function assertExactSet(actual, expected, label) {
  const sortedActual = [...actual].sort();
  const sortedExpected = [...expected].sort();
  if (JSON.stringify(sortedActual) !== JSON.stringify(sortedExpected)) {
    const missing = sortedExpected.filter((item) => !sortedActual.includes(item));
    const unexpected = sortedActual.filter((item) => !sortedExpected.includes(item));
    fail(`${label} drift; missing=[${missing}], unexpected=[${unexpected}]`);
  }
}

function profileColumns(model, table) {
  return [
    ...table.profiles.flatMap((profile) => {
      const columns = model.columnProfiles[profile];
      if (!columns) fail(`${table.name} references unknown column profile ${profile}`);
      return columns;
    }),
    ...table.columns,
  ];
}

function tableBlock(sql, tableName) {
  const startToken = `CREATE TABLE public.${tableName} (`;
  const start = sql.indexOf(startToken);
  if (start < 0) fail(`${tableName}: CREATE TABLE statement is missing`);
  const end = sql.indexOf('\n);', start + startToken.length);
  if (end < 0) fail(`${tableName}: CREATE TABLE statement is not terminated`);
  return sql.slice(start + startToken.length, end);
}

function verifyColumn(tableName, block, column) {
  const linePattern = new RegExp(
    `^\\s{4}${escapeRegularExpression(column.name)}\\s+([^\\r\\n,]+)(?:,)?$`,
    'mu',
  );
  const match = linePattern.exec(block);
  if (!match) fail(`${tableName}.${column.name}: column definition is missing`);
  const definition = compact(match[1]).toLowerCase();
  const expectedType = compact(column.type).toLowerCase();
  if (!definition.startsWith(expectedType)) {
    fail(`${tableName}.${column.name}: expected type ${column.type}, found ${definition}`);
  }
  if (column.nullable === false && !definition.includes(' not null')) {
    fail(`${tableName}.${column.name}: NOT NULL is missing`);
  }
  if (column.nullable === true && definition.includes(' not null')) {
    fail(`${tableName}.${column.name}: nullable column is declared NOT NULL`);
  }
  if (column.default && !definition.includes(`default ${compact(column.default).toLowerCase()}`)) {
    fail(`${tableName}.${column.name}: expected default ${column.default}`);
  }
}

function indexColumns(index) {
  const orders = String(index.order ?? '')
    .split(',')
    .map((value) => value.trim().toUpperCase())
    .filter(Boolean);
  const offset = index.columns.length - orders.length;
  if (offset < 0) fail(`${index.name}: index order contains too many entries`);
  return index.columns.map((column, position) => {
    const order = position >= offset ? orders[position - offset] : '';
    if (order && order !== 'ASC' && order !== 'DESC') {
      fail(`${index.name}: unsupported index order ${order}`);
    }
    return `${column}${order === 'DESC' ? ' DESC' : ''}`;
  });
}

const options = parseArguments(process.argv.slice(2));
const model = JSON.parse(readFileSync(options.erModel, 'utf8'));
const sql = readFileSync(options.migration, 'utf8');
const signature = sqlSignature(sql);

const taskTableNames = model.migrationGroups?.['G2-08'];
if (!Array.isArray(taskTableNames) || taskTableNames.length !== 3) {
  fail('ER model must define the three-table G2-08 migration group');
}
const tables = taskTableNames.map((name) => {
  const table = model.tables.find((candidate) => candidate.name === name);
  if (!table) fail(`ER model G2-08 references unknown table ${name}`);
  if (table.migrationTask !== 'G2-08') fail(`${name}: migrationTask must be G2-08`);
  return table;
});

const createdTables = [...sql.matchAll(/^CREATE TABLE public\.([a-z][a-z0-9_]*)\s*\(/gmu)].map(
  (match) => match[1],
);
assertExactSet(createdTables, taskTableNames, 'G2-08 CREATE TABLE set');

const expectedIndexes = [];
for (const table of tables) {
  const block = tableBlock(sql, table.name);
  const blockSignature = sqlSignature(block);
  const expectedColumns = profileColumns(model, table);
  const declaredColumnNames = [...block.matchAll(/^\s{4}([a-z][a-z0-9_]*)\s+/gmu)].map(
    (match) => match[1],
  );
  assertExactSet(
    declaredColumnNames,
    expectedColumns.map((column) => column.name),
    `${table.name} columns`,
  );
  for (const column of expectedColumns) verifyColumn(table.name, block, column);

  const expectedConstraintNames = [
    `pk_${table.name}`,
    ...table.uniqueConstraints.map((constraint) => constraint.name),
    ...table.foreignKeys.map((foreignKey) => foreignKey.name),
    ...table.checks.map((check) => check.name),
  ];
  const actualConstraintNames = [
    ...block.matchAll(/\bCONSTRAINT\s+([a-z][a-z0-9_]*)\b/gmu),
  ].map((match) => match[1]);
  assertExactSet(actualConstraintNames, expectedConstraintNames, `${table.name} constraint set`);
  if (!blockSignature.includes(sqlSignature(`CONSTRAINT pk_${table.name} PRIMARY KEY (id)`))) {
    fail(`${table.name}: named UUID primary key drift`);
  }

  for (const unique of table.uniqueConstraints) {
    const expected = `CONSTRAINT ${unique.name} UNIQUE (${unique.columns.join(',')})`;
    if (!blockSignature.includes(sqlSignature(expected))) {
      fail(`${table.name}.${unique.name}: UNIQUE column order drift`);
    }
  }
  for (const foreignKey of table.foreignKeys) {
    const expected =
      `CONSTRAINT ${foreignKey.name} FOREIGN KEY (${foreignKey.columns.join(',')}) ` +
      `REFERENCES public.${foreignKey.targetTable} (${foreignKey.targetColumns.join(',')}) ` +
      'ON DELETE RESTRICT';
    if (!blockSignature.includes(sqlSignature(expected))) {
      fail(`${table.name}.${foreignKey.name}: composite target or ON DELETE RESTRICT drift`);
    }
  }
  for (const check of table.checks) {
    const expected = `CONSTRAINT ${check.name} CHECK (${check.expression})`;
    if (!blockSignature.includes(sqlSignature(expected))) {
      fail(`${table.name}.${check.name}: CHECK expression drift`);
    }
  }

  for (const index of table.indexes) {
    expectedIndexes.push(index.name);
    const predicate = index.predicate ? ` WHERE ${index.predicate}` : '';
    const expected =
      `CREATE INDEX ${index.name} ON public.${table.name} ` +
      `(${indexColumns(index).join(',')})${predicate};`;
    if (!signature.includes(sqlSignature(expected))) {
      fail(`${table.name}.${index.name}: column order, sort order, or predicate drift`);
    }
  }

  for (const requiredRlsStatement of [
    `ALTER TABLE public.${table.name} ENABLE ROW LEVEL SECURITY;`,
    `ALTER TABLE public.${table.name} FORCE ROW LEVEL SECURITY;`,
  ]) {
    if (!sql.includes(requiredRlsStatement)) {
      fail(`${table.name}: required RLS statement is missing: ${requiredRlsStatement}`);
    }
  }
}

const actualIndexes = [
  ...sql.matchAll(/^CREATE INDEX ([a-z][a-z0-9_]*)/gmu),
].map((match) => match[1]);
assertExactSet(actualIndexes, expectedIndexes, 'G2-08 explicit index set');

for (const tableName of ['human_task', 'quota_usage']) {
  const policy =
    `CREATE POLICY ${tableName}_tenant_isolation ON public.${tableName} ` +
    'USING (tenant_id = public.app_tenant_id()) ' +
    'WITH CHECK (tenant_id = public.app_tenant_id());';
  if (!signature.includes(sqlSignature(policy))) {
    fail(`${tableName}: strict tenant RLS policy drift`);
  }
}
const auditPolicy =
  'CREATE POLICY audit_log_tenant_isolation ON public.audit_log ' +
  'USING (tenant_id = public.app_tenant_id() OR ' +
  '(tenant_id IS NULL AND public.app_is_platform_admin())) ' +
  'WITH CHECK (tenant_id = public.app_tenant_id() OR ' +
  '(tenant_id IS NULL AND public.app_is_platform_admin()));';
if (!signature.includes(sqlSignature(auditPolicy))) {
  fail('audit_log: tenant/platform-row RLS policy drift');
}

const guardContracts = [
  {
    functionName: 'guard_human_task_transition',
    triggerName: 'human_task_transition_guard',
    events: 'INSERT OR UPDATE',
    table: 'human_task',
    tokens: [
      "NEW.status <> 'WAITING'",
      "node.status = 'WAITING_APPROVAL'",
      'FOR KEY SHARE',
      "OLD.status <> 'WAITING'",
      'NEW.transfer_count <> OLD.transfer_count + 1',
      'WAITING human task update must be one auditable transfer',
      "NEW.status = 'DECIDED'",
      "NEW.status = 'TIMED_OUT'",
      'terminal human task is immutable',
    ],
  },
  {
    functionName: 'guard_audit_log_append_only',
    triggerName: 'audit_log_append_only_guard',
    events: 'INSERT OR UPDATE OR DELETE',
    table: 'audit_log',
    tokens: [
      "TG_OP = 'INSERT'",
      'NEW.tenant_id IS NULL AND NOT public.app_is_platform_admin()',
      'audit log is append-only',
    ],
  },
  {
    functionName: 'guard_quota_usage_transition',
    triggerName: 'quota_usage_transition_guard',
    events: 'INSERT OR UPDATE',
    table: 'quota_usage',
    tokens: [
      'pg_catalog.pg_advisory_xact_lock',
      'pg_catalog.hashtextextended',
      'existing.window_start < NEW.window_end',
      'existing.window_end > NEW.window_start',
      'NEW.used_value <= OLD.used_value',
      'quota usage identity, window, and limit snapshot cannot change',
    ],
  },
];

for (const guard of guardContracts) {
  for (const token of [
    `CREATE OR REPLACE FUNCTION public.${guard.functionName}()`,
    'RETURNS trigger',
    'LANGUAGE plpgsql',
    'VOLATILE',
    'SECURITY INVOKER',
    'SET search_path = pg_catalog, pg_temp',
    `REVOKE ALL ON FUNCTION public.${guard.functionName}() FROM PUBLIC;`,
    ...guard.tokens,
  ]) {
    if (!sql.includes(token)) {
      fail(`${guard.functionName}: required guard token is missing: ${token}`);
    }
  }
  const trigger =
    `CREATE TRIGGER ${guard.triggerName} BEFORE ${guard.events} ON public.${guard.table} ` +
    `FOR EACH ROW EXECUTE FUNCTION public.${guard.functionName}();`;
  if (!signature.includes(sqlSignature(trigger))) {
    fail(`${guard.triggerName}: trigger event/table/function contract drift`);
  }
}

const actualFunctions = [
  ...sql.matchAll(/^CREATE OR REPLACE FUNCTION public\.([a-z][a-z0-9_]*)\(\)/gmu),
].map((match) => match[1]);
assertExactSet(
  actualFunctions,
  guardContracts.map((guard) => guard.functionName),
  'G2-08 guard function set',
);
const actualTriggers = [...sql.matchAll(/^CREATE TRIGGER ([a-z][a-z0-9_]*)/gmu)].map(
  (match) => match[1],
);
assertExactSet(
  actualTriggers,
  guardContracts.map((guard) => guard.triggerName),
  'G2-08 trigger set',
);
if (/GRANT\s+EXECUTE\s+ON\s+FUNCTION\s+public\.guard_[a-z0-9_]+\(\)\s+TO\s+PUBLIC/iu.test(sql)) {
  fail('G2-08 guard functions must not be executable by PUBLIC');
}

const mutableGrant =
  'GRANT SELECT, INSERT, UPDATE ON TABLE public.human_task, public.quota_usage ' +
  'TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";';
if (!signature.includes(sqlSignature(mutableGrant))) {
  fail('G2-08 mutable-table grant contract drift');
}
const auditGrant =
  'GRANT SELECT, INSERT ON TABLE public.audit_log ' +
  'TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";';
if (!signature.includes(sqlSignature(auditGrant))) {
  fail('audit_log append-only grant contract drift');
}
const grantStatements = sql
  .split(';')
  .map((statement) => compact(statement))
  .filter((statement) => /^GRANT\b/iu.test(statement));
if (grantStatements.some((statement) => /\bDELETE\b/iu.test(statement))) {
  fail('G2-08 runtime roles must not receive DELETE privileges');
}
if (grantStatements.some(
  (statement) => /\bUPDATE\b/iu.test(statement) && /\baudit_log\b/iu.test(statement),
)) {
  fail('audit_log must not receive UPDATE privileges');
}

if (/\b(?:timestamp without time zone|serial|bigserial)\b/iu.test(sql)) {
  fail('migration contains a forbidden domain SQL type');
}

const columnCount = tables.reduce(
  (total, table) => total + profileColumns(model, table).length,
  0,
);
const foreignKeyCount = tables.reduce((total, table) => total + table.foreignKeys.length, 0);
const checkCount = tables.reduce((total, table) => total + table.checks.length, 0);
console.log(
  `G2-08 human/audit/quota migration model verification PASS: ${tables.length} tables, ` +
    `${columnCount} columns, ${foreignKeyCount} RESTRICT foreign keys, ${expectedIndexes.length} indexes, ` +
    `${checkCount} checks, ${guardContracts.length} lifecycle guards, forced RLS, append-only audit, ` +
    'tenant-leading indexes, non-overlapping quota windows, and least privilege.',
);
