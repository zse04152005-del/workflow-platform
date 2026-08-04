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
      'V1__create_tenant_and_rbac.sql',
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

const options = parseArguments(process.argv.slice(2));
const model = JSON.parse(readFileSync(options.erModel, 'utf8'));
const sql = readFileSync(options.migration, 'utf8');
const compactSql = compact(sql);

const taskTableNames = model.migrationGroups?.['G2-04'];
if (!Array.isArray(taskTableNames) || taskTableNames.length !== 5) {
  fail('ER model must define the five-table G2-04 migration group');
}
const tables = taskTableNames.map((name) => {
  const table = model.tables.find((candidate) => candidate.name === name);
  if (!table) fail(`ER model G2-04 references unknown table ${name}`);
  if (table.migrationTask !== 'G2-04') fail(`${name}: migrationTask must be G2-04`);
  return table;
});

const createdTables = [...sql.matchAll(/^CREATE TABLE public\.([a-z][a-z0-9_]*)\s*\(/gmu)].map(
  (match) => match[1],
);
assertExactSet(createdTables, taskTableNames, 'G2-04 CREATE TABLE set');

const expectedIndexes = [];
for (const table of tables) {
  const block = tableBlock(sql, table.name);
  const expectedColumns = profileColumns(model, table);
  const declaredColumnNames = [...block.matchAll(/^\s{4}([a-z][a-z0-9_]*)\s+/gmu)]
    .map((match) => match[1])
    .filter((name) => name !== 'constraint');
  assertExactSet(declaredColumnNames, expectedColumns.map((column) => column.name), `${table.name} columns`);
  for (const column of expectedColumns) verifyColumn(table.name, block, column);

  for (const constraint of [
    ...table.uniqueConstraints,
    ...table.foreignKeys,
    ...table.checks,
  ]) {
    if (!new RegExp(`\\bCONSTRAINT ${escapeRegularExpression(constraint.name)}\\b`, 'u').test(block)) {
      fail(`${table.name}: constraint ${constraint.name} is missing`);
    }
  }

  for (const unique of table.uniqueConstraints) {
    if (unique.nullsNotDistinct) {
      const pattern = new RegExp(
        `CONSTRAINT ${escapeRegularExpression(unique.name)}\\s+UNIQUE NULLS NOT DISTINCT`,
        'u',
      );
      if (!pattern.test(compact(block))) {
        fail(`${table.name}.${unique.name}: NULLS NOT DISTINCT is missing`);
      }
    }
  }

  for (const foreignKey of table.foreignKeys) {
    const sourceColumns = foreignKey.columns.join('\\s*,\\s*');
    const targetColumns = foreignKey.targetColumns.join('\\s*,\\s*');
    const pattern = new RegExp(
      `CONSTRAINT ${escapeRegularExpression(foreignKey.name)}\\s+FOREIGN KEY \\(${sourceColumns}\\)\\s+REFERENCES public\\.${escapeRegularExpression(foreignKey.targetTable)} \\(${targetColumns}\\) ON DELETE RESTRICT`,
      'u',
    );
    if (!pattern.test(compact(block))) {
      fail(`${table.name}.${foreignKey.name}: composite target or ON DELETE RESTRICT drift`);
    }
  }

  for (const index of table.indexes) expectedIndexes.push(index.name);
  for (const requiredRlsStatement of [
    `ALTER TABLE public.${table.name} ENABLE ROW LEVEL SECURITY;`,
    `ALTER TABLE public.${table.name} FORCE ROW LEVEL SECURITY;`,
  ]) {
    if (!sql.includes(requiredRlsStatement)) {
      fail(`${table.name}: required RLS statement is missing: ${requiredRlsStatement}`);
    }
  }
  const expectedPolicy =
    table.name === 'tenant'
      ? 'id = public.app_tenant_id() OR public.app_is_platform_admin()'
      : 'tenant_id = public.app_tenant_id()';
  if (!compactSql.includes(`USING (${expectedPolicy}) WITH CHECK (${expectedPolicy})`)) {
    fail(`${table.name}: RLS USING/WITH CHECK policy drift`);
  }
}

const actualIndexes = [...sql.matchAll(/^CREATE INDEX ([a-z][a-z0-9_]*)/gmu)].map(
  (match) => match[1],
);
assertExactSet(actualIndexes, expectedIndexes, 'G2-04 explicit index set');

for (const requiredToken of [
  "current_setting('workflow.tenant_id', true)",
  "set_config('workflow.tenant_id', p_tenant_id::text, true)",
  "to_regrole('${workflowPlatformAdminRole}')",
  'session_user',
  'SECURITY INVOKER',
  'REVOKE ALL ON FUNCTION public.app_tenant_id() FROM PUBLIC;',
  'REVOKE CREATE ON SCHEMA public FROM PUBLIC;',
  'TO "${workflowApplicationRole}", "${workflowPlatformAdminRole}";',
]) {
  if (!sql.includes(requiredToken)) fail(`tenant context or least-privilege token missing: ${requiredToken}`);
}
if (/GRANT\s+[^;]*\bDELETE\b/isu.test(sql)) {
  fail('runtime roles must not receive DELETE privileges');
}
if (/\b(?:timestamp without time zone|serial|bigserial)\b/iu.test(sql)) {
  fail('migration contains a forbidden domain SQL type');
}

console.log(
  `G2-04 migration model verification PASS: ${tables.length} tables, ${tables.reduce((total, table) => total + profileColumns(model, table).length, 0)} columns, ${tables.reduce((total, table) => total + table.foreignKeys.length, 0)} RESTRICT foreign keys, ${expectedIndexes.length} indexes, forced RLS, and least-privilege context helpers.`,
);
