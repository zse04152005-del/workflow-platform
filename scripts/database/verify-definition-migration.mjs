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
      'V2__create_definition_and_integrations.sql',
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

const taskTableNames = model.migrationGroups?.['G2-05'];
if (!Array.isArray(taskTableNames) || taskTableNames.length !== 5) {
  fail('ER model must define the five-table G2-05 migration group');
}
const tables = taskTableNames.map((name) => {
  const table = model.tables.find((candidate) => candidate.name === name);
  if (!table) fail(`ER model G2-05 references unknown table ${name}`);
  if (table.migrationTask !== 'G2-05') fail(`${name}: migrationTask must be G2-05`);
  return table;
});

const createdTables = [...sql.matchAll(/^CREATE TABLE public\.([a-z][a-z0-9_]*)\s*\(/gmu)].map(
  (match) => match[1],
);
assertExactSet(createdTables, taskTableNames, 'G2-05 CREATE TABLE set');

const expectedIndexes = [];
const expectedTriggers = [];
for (const table of tables) {
  const block = tableBlock(sql, table.name);
  const blockSignature = sqlSignature(block);
  const expectedColumns = profileColumns(model, table).filter(
    (column) => !column.introducedIn || column.introducedIn === 'G2-05',
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

  const tableUniques = table.uniqueConstraints.filter(
    (constraint) => !constraint.predicate && !constraint.introducedIn,
  );
  const expectedConstraintNames = [
    `pk_${table.name}`,
    ...tableUniques.map((constraint) => constraint.name),
    ...table.foreignKeys.map((foreignKey) => foreignKey.name),
    ...table.checks.map((check) => check.name),
  ];
  const actualConstraintNames = [
    ...block.matchAll(/\bCONSTRAINT\s+([a-z][a-z0-9_]*)\b/gmu),
  ].map((match) => match[1]);
  assertExactSet(actualConstraintNames, expectedConstraintNames, `${table.name} constraint set`);
  const primaryKey = `CONSTRAINT pk_${table.name} PRIMARY KEY (id)`;
  if (!blockSignature.includes(sqlSignature(primaryKey))) {
    fail(`${table.name}: named UUID primary key drift`);
  }

  for (const unique of tableUniques) {
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
  for (const unique of table.uniqueConstraints.filter(
    (constraint) => constraint.predicate && !constraint.introducedIn,
  )) {
    expectedIndexes.push(unique.name);
    const nulls = unique.nullsNotDistinct ? ' NULLS NOT DISTINCT' : '';
    const expected =
      `CREATE UNIQUE INDEX ${unique.name} ON public.${table.name} ` +
      `(${unique.columns.join(',')})${nulls} WHERE ${unique.predicate};`;
    if (!signature.includes(sqlSignature(expected))) {
      fail(`${table.name}.${unique.name}: partial uniqueness, NULL handling, or predicate drift`);
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

  if (table.profiles.includes('immutable-audit')) {
    expectedTriggers.push(`${table.name}_immutable`);
  }
}

const actualIndexes = [
  ...sql.matchAll(/^CREATE(?: UNIQUE)? INDEX ([a-z][a-z0-9_]*)/gmu),
].map((match) => match[1]);
assertExactSet(actualIndexes, expectedIndexes, 'G2-05 explicit index set');

const actualTriggers = [...sql.matchAll(/^CREATE TRIGGER ([a-z][a-z0-9_]*)/gmu)].map(
  (match) => match[1],
);
assertExactSet(actualTriggers, expectedTriggers, 'G2-05 immutable trigger set');
for (const table of tables.filter((candidate) => candidate.profiles.includes('immutable-audit'))) {
  const trigger =
    `CREATE TRIGGER ${table.name}_immutable BEFORE UPDATE OR DELETE ON public.${table.name} ` +
    'FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_row_change();';
  if (!signature.includes(sqlSignature(trigger))) {
    fail(`${table.name}: immutable UPDATE/DELETE trigger drift`);
  }
}

for (const requiredFunctionToken of [
  'CREATE OR REPLACE FUNCTION public.reject_immutable_row_change()',
  'RETURNS trigger',
  'LANGUAGE plpgsql',
  'VOLATILE',
  'SECURITY INVOKER',
  'SET search_path = pg_catalog, pg_temp',
  "ERRCODE = '55000'",
  'REVOKE ALL ON FUNCTION public.reject_immutable_row_change() FROM PUBLIC;',
]) {
  if (!sql.includes(requiredFunctionToken)) {
    fail(`immutable trigger function token is missing: ${requiredFunctionToken}`);
  }
}
if (/GRANT\s+EXECUTE\s+ON\s+FUNCTION\s+public\.reject_immutable_row_change\(\)\s+TO\s+PUBLIC/iu.test(sql)) {
  fail('immutable trigger function must not be executable by PUBLIC');
}

const mutableGrant =
  'GRANT SELECT, INSERT, UPDATE ON TABLE public.workflow_definition, public.connector, ' +
  'public.secret_reference TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";';
if (!signature.includes(sqlSignature(mutableGrant))) {
  fail('mutable runtime table grant contract drift');
}
const immutableGrant =
  'GRANT SELECT, INSERT ON TABLE public.workflow_version, public.trigger_config ' +
  'TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";';
if (!signature.includes(sqlSignature(immutableGrant))) {
  fail('immutable runtime table grant contract drift');
}
const grantStatements = sql
  .split(';')
  .map((statement) => compact(statement))
  .filter((statement) => /^GRANT\b/iu.test(statement));
if (grantStatements.some((statement) => /\bDELETE\b/iu.test(statement))) {
  fail('runtime roles must not receive DELETE privileges');
}
if (
  grantStatements.some(
    (statement) =>
      /public\.(?:workflow_version|trigger_config)\b/iu.test(statement) &&
      /\b(?:UPDATE|DELETE)\b/iu.test(statement),
  )
) {
  fail('immutable runtime tables must not receive UPDATE or DELETE privileges');
}

if (/\b(?:timestamp without time zone|serial|bigserial)\b/iu.test(sql)) {
  fail('migration contains a forbidden domain SQL type');
}

const columnCount = tables.reduce(
  (total, table) =>
    total +
    profileColumns(model, table).filter(
      (column) => !column.introducedIn || column.introducedIn === 'G2-05',
    ).length,
  0,
);
const foreignKeyCount = tables.reduce((total, table) => total + table.foreignKeys.length, 0);
const checkCount = tables.reduce((total, table) => total + table.checks.length, 0);
console.log(
  `G2-05 migration model verification PASS: ${tables.length} tables, ${columnCount} columns, ` +
    `${foreignKeyCount} RESTRICT foreign keys, ${expectedIndexes.length} indexes, ${checkCount} checks, ` +
    `${expectedTriggers.length} immutable triggers, forced RLS, partial NULL-aware uniqueness, and least privilege.`,
);
