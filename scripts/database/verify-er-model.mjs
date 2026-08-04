import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const expectedGroups = {
  'G2-04': ['tenant', 'project', 'membership', 'role', 'role_binding'],
  'G2-05': [
    'workflow_definition',
    'workflow_version',
    'trigger_config',
    'connector',
    'secret_reference',
  ],
  'G2-06': ['workflow_instance', 'node_instance', 'task_execution', 'schedule_occurrence'],
  'G2-07': [
    'outbox_event',
    'processed_event',
    'idempotency_record',
    'dead_letter',
    'worker_lease',
  ],
  'G2-08': ['human_task', 'audit_log', 'quota_usage'],
};
const allowedColumnType = /^(uuid|varchar\([1-9][0-9]*\)|char\([1-9][0-9]*\)|text|text\[\]|jsonb|bytea|integer|bigint|boolean|timestamptz\(6\)|inet)$/u;
const expectedQueryIds = Array.from({ length: 10 }, (_, index) =>
  `Q-${String(index + 1).padStart(2, '0')}`,
);

function parseArguments(argv) {
  const options = {
    model: path.join(repositoryRoot, 'config', 'database', 'er-model.json'),
    skipDocument: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--skip-document') {
      options.skipDocument = true;
    } else if (argument === '--model') {
      const value = argv[index + 1];
      if (!value) throw new Error('--model requires a path');
      options.model = path.resolve(repositoryRoot, value);
      index += 1;
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return options;
}

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`Unable to read JSON ${file}: ${error.message}`);
  }
}

function expandColumns(model, table, errors) {
  const columns = [];
  for (const profile of table.profiles ?? []) {
    const profileColumns = model.columnProfiles?.[profile];
    if (!Array.isArray(profileColumns)) {
      errors.push(`${table.name}: unknown column profile ${String(profile)}`);
      continue;
    }
    columns.push(...profileColumns);
  }
  if (!Array.isArray(table.columns)) {
    errors.push(`${table.name}: columns must be an array`);
  } else {
    columns.push(...table.columns);
  }
  const seen = new Set();
  for (const column of columns) {
    if (!column || typeof column.name !== 'string' || !/^[a-z][a-z0-9_]*$/u.test(column.name)) {
      errors.push(`${table.name}: invalid column name ${String(column?.name)}`);
      continue;
    }
    if (seen.has(column.name)) errors.push(`${table.name}: duplicate column ${column.name}`);
    seen.add(column.name);
    if (!allowedColumnType.test(column.type ?? '')) {
      errors.push(`${table.name}.${column.name}: unsupported column type ${String(column.type)}`);
    }
    if (typeof column.nullable !== 'boolean') {
      errors.push(`${table.name}.${column.name}: nullable must be boolean`);
    }
  }
  return new Map(columns.map((column) => [column.name, column]));
}

function ownerMapFromArchitecture() {
  const architecture = readJson(path.join(repositoryRoot, 'config', 'architecture', 'model.json'));
  const owners = new Map();
  for (const entry of architecture.dataOwnership ?? []) {
    for (const item of entry.dataset.split(',').map((value) => value.trim())) {
      owners.set(item, entry.owner);
    }
  }
  return owners;
}

function validateGroups(model, errors) {
  if (JSON.stringify(model.migrationGroups) !== JSON.stringify(expectedGroups)) {
    errors.push('migrationGroups must exactly match G2-04 through G2-08 control-task table sets');
  }
  const grouped = Object.values(model.migrationGroups ?? {}).flat();
  if (new Set(grouped).size !== grouped.length) errors.push('migrationGroups contains duplicate tables');
  const tableNames = (model.tables ?? []).map((table) => table.name);
  const missing = grouped.filter((table) => !tableNames.includes(table));
  const ungrouped = tableNames.filter((table) => !grouped.includes(table));
  if (missing.length || ungrouped.length) {
    errors.push(`migration group/table drift: missing=[${missing}], ungrouped=[${ungrouped}]`);
  }
}

function constraintNames(table) {
  return [
    ...(table.foreignKeys ?? []).map((item) => item.name),
    ...(table.uniqueConstraints ?? []).map((item) => item.name),
    ...(table.checks ?? []).map((item) => item.name),
  ];
}

function indexSupports(columns, indexes, uniques) {
  return [...indexes, ...uniques].some(
    (item) =>
      Array.isArray(item.columns) &&
      columns.every((column, index) => item.columns[index] === column),
  );
}

function targetColumnsAreUnique(target, targetColumns, columnsByTable) {
  const targetColumnsMap = columnsByTable.get(target.name);
  if (
    targetColumns.length === 1 &&
    targetColumnsMap?.get(targetColumns[0])?.primaryKey === true
  ) {
    return true;
  }
  return (target.uniqueConstraints ?? []).some(
    (constraint) => JSON.stringify(constraint.columns) === JSON.stringify(targetColumns),
  );
}

function validateTable(
  model,
  table,
  tablesByName,
  columnsByTable,
  architectureOwners,
  deletionStrategies,
  globalNames,
  errors,
) {
  if (!/^[a-z][a-z0-9_]*$/u.test(table.name ?? '')) errors.push(`invalid table name ${String(table.name)}`);
  const columns = columnsByTable.get(table.name);
  const id = columns?.get('id');
  if (!id || id.type !== 'uuid' || id.nullable || id.primaryKey !== true) {
    errors.push(`${table.name}: id must be a non-null UUID primary key`);
  }
  if (architectureOwners.get(table.name) !== table.owner) {
    errors.push(
      `${table.name}: owner drift; ER=${String(table.owner)}, architecture=${String(architectureOwners.get(table.name))}`,
    );
  }
  if (!Object.hasOwn(expectedGroups, table.migrationTask)) {
    errors.push(`${table.name}: invalid migrationTask ${String(table.migrationTask)}`);
  }
  if (!deletionStrategies.has(table.deletionStrategy)) {
    errors.push(`${table.name}: unknown deletionStrategy ${String(table.deletionStrategy)}`);
  }
  if (table.deletionStrategy === 'soft-delete' && !table.profiles?.includes('soft-delete')) {
    errors.push(`${table.name}: soft-delete strategy requires soft-delete column profile`);
  }
  if (table.deletionStrategy === 'revocation' && !table.profiles?.includes('revocation')) {
    errors.push(`${table.name}: revocation strategy requires revocation column profile`);
  }

  if (!['self', 'required', 'platform-optional'].includes(table.tenantScope)) {
    errors.push(`${table.name}: invalid tenantScope ${String(table.tenantScope)}`);
  }
  const tenantColumn = columns?.get('tenant_id');
  if (table.tenantScope === 'self') {
    if (table.name !== 'tenant' || tenantColumn) errors.push(`${table.name}: self scope is only valid for tenant without tenant_id`);
  } else {
    if (!tenantColumn) errors.push(`${table.name}: tenant-scoped table must contain tenant_id`);
    if (table.tenantScope === 'required' && tenantColumn?.nullable !== false) {
      errors.push(`${table.name}: required tenant_id must be NOT NULL`);
    }
    if (table.tenantScope === 'platform-optional' && tenantColumn?.nullable !== true) {
      errors.push(`${table.name}: platform-optional tenant_id must be nullable`);
    }
    const tenantForeignKey = (table.foreignKeys ?? []).find(
      (foreignKey) =>
        JSON.stringify(foreignKey.columns) === JSON.stringify(['tenant_id']) &&
        foreignKey.targetTable === 'tenant' &&
        JSON.stringify(foreignKey.targetColumns) === JSON.stringify(['id']),
    );
    if (!tenantForeignKey) errors.push(`${table.name}: tenant-scoped table lacks tenant foreign key`);
    if (table.tenantScope === 'required') {
      const identity = (table.uniqueConstraints ?? []).find(
        (constraint) =>
          JSON.stringify(constraint.columns) === JSON.stringify(['tenant_id', 'id']),
      );
      if (!identity) errors.push(`${table.name}: missing (tenant_id,id) unique identity for composite FKs`);
    }
  }
  if (table.rls?.enabled !== true || typeof table.rls?.policy !== 'string' || !table.rls.policy.trim()) {
    errors.push(`${table.name}: RLS must be enabled with an explicit policy`);
  }

  const foreignKeys = table.foreignKeys ?? [];
  const uniques = table.uniqueConstraints ?? [];
  const indexes = table.indexes ?? [];
  for (const foreignKey of foreignKeys) {
    if (!foreignKey.name?.startsWith('fk_')) errors.push(`${table.name}: invalid FK name ${String(foreignKey.name)}`);
    if (foreignKey.onDelete !== 'RESTRICT') {
      errors.push(`${table.name}.${foreignKey.name}: ON DELETE must be RESTRICT`);
    }
    const target = tablesByName.get(foreignKey.targetTable);
    if (!target) {
      errors.push(`${table.name}.${foreignKey.name}: missing target table ${String(foreignKey.targetTable)}`);
      continue;
    }
    if (
      !Array.isArray(foreignKey.columns) ||
      !Array.isArray(foreignKey.targetColumns) ||
      foreignKey.columns.length !== foreignKey.targetColumns.length ||
      foreignKey.columns.length === 0
    ) {
      errors.push(`${table.name}.${foreignKey.name}: source/target FK columns must be same non-zero length`);
      continue;
    }
    foreignKey.columns.forEach((column, index) => {
      const sourceColumn = columns?.get(column);
      const targetColumn = columnsByTable.get(target.name)?.get(foreignKey.targetColumns[index]);
      if (!sourceColumn) errors.push(`${table.name}.${foreignKey.name}: missing source column ${column}`);
      if (!targetColumn) errors.push(`${table.name}.${foreignKey.name}: missing target column ${foreignKey.targetColumns[index]}`);
      if (sourceColumn && targetColumn && sourceColumn.type !== targetColumn.type) {
        errors.push(`${table.name}.${foreignKey.name}: type mismatch ${sourceColumn.type} != ${targetColumn.type}`);
      }
    });
    if (!targetColumnsAreUnique(target, foreignKey.targetColumns, columnsByTable)) {
      errors.push(`${table.name}.${foreignKey.name}: target columns are not PK/unique`);
    }
    if (
      table.tenantScope === 'required' &&
      target.name !== 'tenant' &&
      target.tenantScope !== 'self' &&
      (foreignKey.columns[0] !== 'tenant_id' || foreignKey.targetColumns[0] !== 'tenant_id')
    ) {
      errors.push(`${table.name}.${foreignKey.name}: cross-tenant FK must start with tenant_id`);
    }
    if (!indexSupports(foreignKey.columns, indexes, uniques)) {
      errors.push(`${table.name}.${foreignKey.name}: FK lacks a supporting leading index`);
    }
  }

  for (const unique of uniques) {
    if (!unique.name?.startsWith('uq_')) errors.push(`${table.name}: invalid unique name ${String(unique.name)}`);
    if (!Array.isArray(unique.columns) || unique.columns.length === 0) {
      errors.push(`${table.name}.${unique.name}: unique columns must not be empty`);
      continue;
    }
    for (const column of unique.columns) {
      if (!columns?.has(column)) errors.push(`${table.name}.${unique.name}: missing unique column ${column}`);
    }
    if (
      table.tenantScope === 'required' &&
      unique.columns[0] !== 'tenant_id' &&
      !unique.globalScopeReason
    ) {
      errors.push(`${table.name}.${unique.name}: tenant unique must start with tenant_id or explain global scope`);
    }
  }
  for (const check of table.checks ?? []) {
    if (!check.name?.startsWith('ck_')) errors.push(`${table.name}: invalid check name ${String(check.name)}`);
    if (typeof check.expression !== 'string' || !check.expression.trim()) {
      errors.push(`${table.name}.${check.name}: check expression is empty`);
    }
  }
  for (const index of indexes) {
    if (!index.name?.startsWith('idx_')) errors.push(`${table.name}: invalid index name ${String(index.name)}`);
    if (!Array.isArray(index.columns) || index.columns.length === 0) {
      errors.push(`${table.name}.${index.name}: index columns must not be empty`);
    } else {
      for (const column of index.columns) {
        if (!columns?.has(column)) errors.push(`${table.name}.${index.name}: missing index column ${column}`);
      }
    }
    if (typeof index.purpose !== 'string' || !index.purpose.trim()) {
      errors.push(`${table.name}.${index.name}: index purpose is required`);
    }
  }
  for (const name of [...constraintNames(table), ...indexes.map((index) => index.name)]) {
    if (typeof name !== 'string') continue;
    if (name.length > 63) errors.push(`${table.name}: PostgreSQL name exceeds 63 bytes/chars: ${name}`);
    if (globalNames.has(name)) errors.push(`duplicate database object name: ${name}`);
    globalNames.add(name);
  }

  if (!table.partitioning || typeof table.partitioning.mode !== 'string') {
    errors.push(`${table.name}: partitioning decision is required`);
  } else if (table.partitioning.mode === 'deferred-range-monthly') {
    const key = columns?.get(table.partitioning.key);
    if (!key || key.type !== 'timestamptz(6)') {
      errors.push(`${table.name}: monthly partition key must exist and be timestamptz(6)`);
    }
    if (typeof table.partitioning.activateWhen !== 'string' || !table.partitioning.activateWhen.trim()) {
      errors.push(`${table.name}: deferred partition must state activation condition`);
    }
  } else if (table.partitioning.mode !== 'none-mvp') {
    errors.push(`${table.name}: unsupported partitioning mode ${table.partitioning.mode}`);
  }
  if (typeof table.retention !== 'string' || !table.retention.trim()) {
    errors.push(`${table.name}: retention decision is required`);
  }
}

function validateQueryContracts(model, tablesByName, errors) {
  const contracts = model.requiredQueryContracts;
  if (!Array.isArray(contracts)) {
    errors.push('requiredQueryContracts must be an array');
    return;
  }
  const ids = contracts.map((contract) => contract.id);
  if (JSON.stringify(ids) !== JSON.stringify(expectedQueryIds)) {
    errors.push(`requiredQueryContracts must contain ${expectedQueryIds.join(', ')}`);
  }
  for (const contract of contracts) {
    const table = tablesByName.get(contract.table);
    if (!table) {
      errors.push(`${contract.id}: unknown query table ${String(contract.table)}`);
      continue;
    }
    if (!(table.indexes ?? []).some((index) => index.name === contract.index)) {
      errors.push(`${contract.id}: required index ${String(contract.index)} is missing from ${table.name}`);
    }
    if (typeof contract.purpose !== 'string' || !contract.purpose.trim()) {
      errors.push(`${contract.id}: query purpose is required`);
    }
  }
}

function validatePartitionCandidates(model, tablesByName, errors) {
  if (!Array.isArray(model.partitionCandidates)) {
    errors.push('partitionCandidates must be an array');
    return;
  }
  if (new Set(model.partitionCandidates).size !== model.partitionCandidates.length) {
    errors.push('partitionCandidates contains duplicates');
  }
  const actual = [...tablesByName.values()]
    .filter((table) => table.partitioning?.mode === 'deferred-range-monthly')
    .map((table) => table.name);
  if (JSON.stringify(model.partitionCandidates) !== JSON.stringify(actual)) {
    errors.push(`partitionCandidates drift: declared=[${model.partitionCandidates}], actual=[${actual}]`);
  }
}

function validateDocument(model, errors) {
  const file = path.resolve(repositoryRoot, model.document ?? '');
  if (!file.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(file)) {
    errors.push(`ER document does not exist: ${String(model.document)}`);
    return;
  }
  const document = readFileSync(file, 'utf8');
  if ((document.match(/```mermaid/gu) ?? []).length < 3) {
    errors.push('ER document must contain at least three Mermaid diagrams');
  }
  for (const table of model.tables ?? []) {
    if (!document.includes(table.name)) errors.push(`ER document is missing table ${table.name}`);
  }
  for (const contract of model.requiredQueryContracts ?? []) {
    if (!document.includes(contract.id) || !document.includes(contract.index)) {
      errors.push(`ER document is missing query contract ${contract.id}`);
    }
  }
  for (const token of [
    'RFC 9562 UUIDv7',
    'ON DELETE RESTRICT',
    'NULLS NOT DISTINCT',
    'PostgreSQL 是唯一事实源',
    '不表示 Flyway 表已创建',
  ]) {
    if (!document.includes(token)) errors.push(`ER document is missing review token: ${token}`);
  }
}

function validatePackageScripts(errors) {
  const scripts = readJson(path.join(repositoryRoot, 'package.json')).scripts ?? {};
  if (scripts['er:verify'] !== 'node scripts/database/verify-er-model.mjs') {
    errors.push('package.json er:verify script is missing or drifted');
  }
  if (scripts['er:test'] !== 'node scripts/database/test-er-model.mjs') {
    errors.push('package.json er:test script is missing or drifted');
  }
  if (!scripts.verify?.includes('er:verify') || !scripts.verify?.includes('er:test')) {
    errors.push('pnpm verify must include er:verify and er:test');
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const model = readJson(options.model);
  const errors = [];
  if (model.schemaVersion !== 1) errors.push('schemaVersion must be 1');
  if (!/^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/u.test(model.modelVersion ?? '')) {
    errors.push('modelVersion must be stable SemVer');
  }
  if (model.task !== 'G2-03') errors.push('ER model must remain linked to G2-03');
  if (model.database !== 'PostgreSQL 18') errors.push('ER model database must be PostgreSQL 18');
  if (model.idStrategy !== 'RFC9562-UUIDv7' || model.timeStrategy !== 'timestamptz(6)-UTC') {
    errors.push('ER model must inherit UUIDv7 and timestamptz(6) UTC conventions');
  }

  validateGroups(model, errors);
  if (!Array.isArray(model.tables) || model.tables.length !== 22) {
    errors.push(`ER model must contain exactly 22 tables; found ${String(model.tables?.length)}`);
  }
  const tableNames = (model.tables ?? []).map((table) => table.name);
  if (new Set(tableNames).size !== tableNames.length) errors.push('ER model contains duplicate table names');
  const tablesByName = new Map((model.tables ?? []).map((table) => [table.name, table]));
  const columnsByTable = new Map(
    (model.tables ?? []).map((table) => [table.name, expandColumns(model, table, errors)]),
  );
  const architectureOwners = ownerMapFromArchitecture();
  const conventions = readJson(path.join(repositoryRoot, 'config', 'domain', 'conventions.json'));
  const deletionStrategies = new Set(
    (conventions.deletion?.strategies ?? []).map((strategy) => strategy.id),
  );
  const globalNames = new Set();
  for (const table of model.tables ?? []) {
    validateTable(
      model,
      table,
      tablesByName,
      columnsByTable,
      architectureOwners,
      deletionStrategies,
      globalNames,
      errors,
    );
  }
  validateQueryContracts(model, tablesByName, errors);
  validatePartitionCandidates(model, tablesByName, errors);
  validatePackageScripts(errors);
  if (!options.skipDocument) validateDocument(model, errors);

  if (errors.length > 0) {
    throw new Error(`ER model verification failed:\n- ${errors.join('\n- ')}`);
  }
  const columnCount = [...columnsByTable.values()].reduce((sum, columns) => sum + columns.size, 0);
  const foreignKeyCount = (model.tables ?? []).reduce(
    (sum, table) => sum + (table.foreignKeys?.length ?? 0),
    0,
  );
  const indexCount = (model.tables ?? []).reduce(
    (sum, table) => sum + (table.indexes?.length ?? 0),
    0,
  );
  console.log(
    `ER model verification PASS: tables=${model.tables.length}, columns=${columnCount}, foreignKeys=${foreignKeyCount}, indexes=${indexCount}, ` +
      `queryContracts=${model.requiredQueryContracts.length}, partitionCandidates=${model.partitionCandidates.length}, RLS=${model.tables.length}/22, onDelete=RESTRICT.`,
  );
}

main();
