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
      'V3__create_runtime_and_schedule.sql',
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

const taskTableNames = model.migrationGroups?.['G2-06'];
if (!Array.isArray(taskTableNames) || taskTableNames.length !== 4) {
  fail('ER model must define the four-table G2-06 migration group');
}
const tables = taskTableNames.map((name) => {
  const table = model.tables.find((candidate) => candidate.name === name);
  if (!table) fail(`ER model G2-06 references unknown table ${name}`);
  if (table.migrationTask !== 'G2-06') fail(`${name}: migrationTask must be G2-06`);
  return table;
});

const createdTables = [...sql.matchAll(/^CREATE TABLE public\.([a-z][a-z0-9_]*)\s*\(/gmu)].map(
  (match) => match[1],
);
assertExactSet(createdTables, taskTableNames, 'G2-06 CREATE TABLE set');

const predecessorTables = ['workflow_version', 'trigger_config'];
const expectedPredecessorConstraints = [];
for (const tableName of predecessorTables) {
  const table = model.tables.find((candidate) => candidate.name === tableName);
  if (!table) fail(`ER model is missing predecessor table ${tableName}`);
  const introduced = table.uniqueConstraints.filter(
    (constraint) => constraint.introducedIn === 'G2-06',
  );
  if (introduced.length !== 1) {
    fail(`${tableName}: exactly one G2-06 predecessor UNIQUE constraint is required`);
  }
  for (const unique of introduced) {
    expectedPredecessorConstraints.push(unique.name);
    const statement =
      `ALTER TABLE public.${tableName} ADD CONSTRAINT ${unique.name} ` +
      `UNIQUE (${unique.columns.join(',')});`;
    if (!signature.includes(sqlSignature(statement))) {
      fail(`${unique.name}: predecessor UNIQUE binding drift`);
    }
  }
}
const actualPredecessorConstraints = [
  ...sql.matchAll(
    /^ALTER TABLE public\.(?:workflow_version|trigger_config)[\s\S]*?ADD CONSTRAINT\s+([a-z][a-z0-9_]*)\s+UNIQUE/gimu,
  ),
].map((match) => match[1]);
assertExactSet(
  actualPredecessorConstraints,
  expectedPredecessorConstraints,
  'G2-06 predecessor UNIQUE constraint set',
);

const expectedIndexes = [];
for (const table of tables) {
  const block = tableBlock(sql, table.name);
  const blockSignature = sqlSignature(block);
  const expectedColumns = profileColumns(model, table).filter(
    (column) => !column.introducedIn || column.introducedIn === 'G2-06',
  );
  const declaredColumnNames = [...block.matchAll(/^\s{4}([a-z][a-z0-9_]*)\s+/gmu)].map(
    (match) => match[1],
  );
  assertExactSet(
    declaredColumnNames,
    expectedColumns.map((column) => column.name),
    `${table.name} columns`,
  );
  for (const column of expectedColumns) verifyColumn(table.name, block, column);

  const migrationUniqueConstraints = table.uniqueConstraints.filter(
    (constraint) => !constraint.introducedIn || constraint.introducedIn === 'G2-06',
  );
  const migrationChecks = table.checks.filter(
    (check) => !check.introducedIn || check.introducedIn === 'G2-06',
  );
  const expectedConstraintNames = [
    `pk_${table.name}`,
    ...migrationUniqueConstraints.map((constraint) => constraint.name),
    ...table.foreignKeys.map((foreignKey) => foreignKey.name),
    ...migrationChecks.map((check) => check.name),
  ];
  const actualConstraintNames = [
    ...block.matchAll(/\bCONSTRAINT\s+([a-z][a-z0-9_]*)\b/gmu),
  ].map((match) => match[1]);
  assertExactSet(actualConstraintNames, expectedConstraintNames, `${table.name} constraint set`);
  if (!blockSignature.includes(sqlSignature(`CONSTRAINT pk_${table.name} PRIMARY KEY (id)`))) {
    fail(`${table.name}: named UUID primary key drift`);
  }

  for (const unique of migrationUniqueConstraints) {
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
  for (const check of migrationChecks) {
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
  const policy =
    `CREATE POLICY ${table.name}_tenant_isolation ON public.${table.name} ` +
    'USING (tenant_id = public.app_tenant_id()) ' +
    'WITH CHECK (tenant_id = public.app_tenant_id());';
  if (!signature.includes(sqlSignature(policy))) {
    fail(`${table.name}: tenant RLS policy drift`);
  }
}

const actualIndexes = [
  ...sql.matchAll(/^CREATE(?: UNIQUE)? INDEX ([a-z][a-z0-9_]*)/gmu),
].map((match) => match[1]);
assertExactSet(actualIndexes, expectedIndexes, 'G2-06 explicit index set');

for (const requiredGuardToken of [
  'CREATE OR REPLACE FUNCTION public.guard_task_execution_finalization()',
  'RETURNS trigger',
  'LANGUAGE plpgsql',
  'VOLATILE',
  'SECURITY INVOKER',
  'SET search_path = pg_catalog, pg_temp',
  "IF TG_OP = 'INSERT'",
  "IF TG_OP = 'DELETE'",
  "OLD.status <> 'RUNNING'",
  "NEW.status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'ABANDONED', 'CANCELLED')",
  'NEW.version <> OLD.version + 1',
  'NEW.updated_at < OLD.updated_at',
  'IS DISTINCT FROM ROW(',
  "ERRCODE = '55000'",
  'REVOKE ALL ON FUNCTION public.guard_task_execution_finalization() FROM PUBLIC;',
]) {
  if (!sql.includes(requiredGuardToken)) {
    fail(`task_execution finalization guard token is missing: ${requiredGuardToken}`);
  }
}
const trigger =
  'CREATE TRIGGER task_execution_finalization_guard ' +
  'BEFORE INSERT OR UPDATE OR DELETE ON public.task_execution ' +
  'FOR EACH ROW EXECUTE FUNCTION public.guard_task_execution_finalization();';
if (!signature.includes(sqlSignature(trigger))) {
  fail('task_execution INSERT/UPDATE/DELETE finalization trigger drift');
}
const actualTriggers = [...sql.matchAll(/^CREATE TRIGGER ([a-z][a-z0-9_]*)/gmu)].map(
  (match) => match[1],
);
assertExactSet(actualTriggers, ['task_execution_finalization_guard'], 'G2-06 trigger set');
if (/GRANT\s+EXECUTE\s+ON\s+FUNCTION\s+public\.guard_task_execution_finalization\(\)\s+TO\s+PUBLIC/iu.test(sql)) {
  fail('task_execution guard must not be executable by PUBLIC');
}

const grant =
  'GRANT SELECT, INSERT, UPDATE ON TABLE public.workflow_instance, public.node_instance, ' +
  'public.task_execution, public.schedule_occurrence ' +
  'TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";';
if (!signature.includes(sqlSignature(grant))) {
  fail('G2-06 runtime table grant contract drift');
}
const grantStatements = sql
  .split(';')
  .map((statement) => compact(statement))
  .filter((statement) => /^GRANT\b/iu.test(statement));
if (grantStatements.some((statement) => /\bDELETE\b/iu.test(statement))) {
  fail('runtime roles must not receive DELETE privileges');
}

if (/\b(?:timestamp without time zone|serial|bigserial)\b/iu.test(sql)) {
  fail('migration contains a forbidden domain SQL type');
}

const columnCount = tables.reduce(
  (total, table) =>
    total +
    profileColumns(model, table).filter(
      (column) => !column.introducedIn || column.introducedIn === 'G2-06',
    ).length,
  0,
);
const foreignKeyCount = tables.reduce((total, table) => total + table.foreignKeys.length, 0);
const checkCount = tables.reduce(
  (total, table) =>
    total +
    table.checks.filter(
      (check) => !check.introducedIn || check.introducedIn === 'G2-06',
    ).length,
  0,
);
console.log(
  `G2-06 runtime migration model verification PASS: ${tables.length} tables, ${columnCount} columns, ` +
    `${foreignKeyCount} RESTRICT foreign keys, ${expectedIndexes.length} indexes, ${checkCount} checks, ` +
    `${expectedPredecessorConstraints.length} predecessor bindings, forced RLS, guarded task finalization, ` +
    'tenant-leading due indexes, and least privilege.',
);
