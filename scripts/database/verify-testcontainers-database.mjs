import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const defaults = {
  contract: path.join(repositoryRoot, 'config', 'database', 'g2-11-testcontainers.json'),
  erModel: path.join(repositoryRoot, 'config', 'database', 'er-model.json'),
  javaTest: path.join(
    repositoryRoot,
    'control-plane',
    'src',
    'test',
    'java',
    'com',
    'workflowplatform',
    'controlplane',
    'database',
    'DatabaseMigrationIT.java',
  ),
  fixture: path.join(
    repositoryRoot,
    'control-plane',
    'src',
    'test',
    'resources',
    'database',
    'g2-11-query-plan-fixture.sql',
  ),
  pom: path.join(repositoryRoot, 'control-plane', 'pom.xml'),
  runner: path.join(repositoryRoot, 'scripts', 'database', 'test-g2-11-testcontainers.ps1'),
  packageJson: path.join(repositoryRoot, 'package.json'),
  document: path.join(
    repositoryRoot,
    'docs',
    'architecture',
    'testcontainers-database-verification.md',
  ),
};

const argumentNames = new Map([
  ['--contract', 'contract'],
  ['--er-model', 'erModel'],
  ['--java-test', 'javaTest'],
  ['--fixture', 'fixture'],
  ['--pom', 'pom'],
  ['--runner', 'runner'],
  ['--package-json', 'packageJson'],
  ['--document', 'document'],
]);

function parseArguments(argv) {
  const options = { ...defaults };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const name = argumentNames.get(argument);
    if (!name) throw new Error(`Unknown argument: ${argument}`);
    const value = argv[index + 1];
    if (!value) throw new Error(`${argument} requires a path`);
    options[name] = path.resolve(value);
    index += 1;
  }
  return options;
}

function fail(message) {
  throw new Error(message);
}

function requireToken(source, token, label) {
  if (!source.includes(token)) fail(`${label} is missing: ${token}`);
}

function requireExact(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} drift; expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function requireDependency(pom, artifactId) {
  const pattern = new RegExp(
    `<dependency>\\s*<groupId>org\\.testcontainers</groupId>\\s*` +
      `<artifactId>${artifactId}</artifactId>\\s*<scope>test</scope>\\s*</dependency>`,
    'u',
  );
  if (!pattern.test(pom)) fail(`Testcontainers test dependency is missing: ${artifactId}`);
}

const options = parseArguments(process.argv.slice(2));
const contract = JSON.parse(readFileSync(options.contract, 'utf8'));
const erModel = JSON.parse(readFileSync(options.erModel, 'utf8'));
const javaTest = readFileSync(options.javaTest, 'utf8');
const fixture = readFileSync(options.fixture, 'utf8');
const pom = readFileSync(options.pom, 'utf8');
const runner = readFileSync(options.runner, 'utf8');
const packageJson = JSON.parse(readFileSync(options.packageJson, 'utf8'));
const document = readFileSync(options.document, 'utf8');

requireExact(
  {
    schemaVersion: contract.schemaVersion,
    contractVersion: contract.contractVersion,
    task: contract.task,
    requirement: contract.requirement,
  },
  {
    schemaVersion: 1,
    contractVersion: '1.0.0',
    task: 'G2-11',
    requirement: 'REQ-TEST-001',
  },
  'G2-11 contract identity',
);
requireExact(
  contract.postgres,
  {
    image:
      'postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296',
    expectedVersionPrefix: '18.4',
    startupTimeoutSeconds: 120,
    reuse: false,
  },
  'PostgreSQL Testcontainers contract',
);
requireExact(
  contract.flyway,
  {
    expectedVersion: '11.7.2',
    migrationCount: 7,
    currentSchemaVersion: '7',
    repeatMigrationCount: 0,
    validateAfterMigration: true,
  },
  'Flyway migration contract',
);
requireExact(
  contract.catalog,
  {
    businessTables: 22,
    businessColumns: 330,
    primaryKeys: 22,
    foreignKeys: 39,
    uniqueConstraints: 41,
    standaloneUniqueIndexes: 3,
    uniquenessGuards: 44,
    checkConstraints: 102,
    explicitIndexes: 47,
    forcedRlsTables: 22,
  },
  'reviewed PostgreSQL catalog',
);
requireExact(
  contract.fixture,
  {
    rowsPerQueryFamily: 6000,
    statisticsCommand: 'ANALYZE',
    plannerHintsAllowed: false,
    planFormat: 'JSON',
    requireExactIndexName: true,
    forbidSequentialScan: true,
  },
  'query-plan fixture contract',
);

requireExact(
  contract.constraintProbes.map((probe) => probe.id),
  ['C-01', 'C-02', 'C-03', 'C-04', 'C-05', 'C-06'],
  'constraint probe IDs',
);
for (const sqlState of ['23514', '23503', '23505', '42501', '55000']) {
  requireToken(javaTest, `"${sqlState}"`, `SQLSTATE ${sqlState} probe`);
}
for (const target of contract.constraintProbes.map((probe) => probe.target)) {
  if (!target.startsWith('tenant_') && !target.startsWith('audit_log_')) {
    requireToken(
      `${javaTest}\n${document}`,
      target,
      `constraint probe target ${target}`,
    );
  }
}

requireExact(
  contract.queryContracts,
  erModel.requiredQueryContracts,
  'G2-11 query contracts versus ER model Q-01 through Q-10',
);
if (contract.queryContracts.length !== 10) fail('G2-11 must bind exactly ten query contracts');

const queryStarts = [
  ...javaTest.matchAll(/new QueryContract\(\s*"(Q-\d{2})",\s*"([a-z][a-z0-9_]*)"/gu),
];
requireExact(
  queryStarts.map((match) => ({ id: match[1], index: match[2] })),
  contract.queryContracts.map(({ id, index }) => ({ id, index })),
  'Java query-contract ID/index order',
);
for (let index = 0; index < queryStarts.length; index += 1) {
  const start = queryStarts[index].index;
  const end = queryStarts[index + 1]?.index ?? javaTest.indexOf('private static Flyway', start);
  const block = javaTest.slice(start, end);
  requireToken(
    block,
    `FROM public.${contract.queryContracts[index].table}`,
    `${contract.queryContracts[index].id} table binding`,
  );
}

for (const token of [
  '@Testcontainers',
  '@Container',
  '.asCompatibleSubstituteFor("postgres")',
  '.withStartupTimeout(Duration.ofSeconds(120))',
  '.withReuse(false)',
  'Flyway.configure()',
  '.locations("classpath:db/migration")',
  '.cleanDisabled(true)',
  'flyway.validateWithResult()',
  'MigrateResult repeatMigration = flyway.migrate()',
  'dependencyVersion("org.flywaydb", "flyway-core")',
  'assertEquals(0, repeatMigrationCount',
  'assertEquals(22, businessTableCount(connection)',
  'assertConstraintCount(connection, "p", 22)',
  'assertConstraintCount(connection, "f", 39)',
  'assertConstraintCount(connection, "c", 102)',
  'assertEquals(41, uniqueConstraintCount',
  'assertEquals(3, standaloneUniqueIndexCount',
  'assertEquals(10, QUERY_CONTRACTS.size()',
  'EXPLAIN (FORMAT JSON, COSTS TRUE)',
  '"\\"Node Type\\": \\"Seq Scan\\""',
]) {
  requireToken(javaTest, token, 'DatabaseMigrationIT contract');
}
requireToken(javaTest, contract.postgres.image, 'pinned PostgreSQL image');

const plannerSources = [javaTest, fixture, runner].join('\n');
if (/enable_(?:seqscan|indexscan|bitmapscan)/iu.test(plannerSources)) {
  fail('planner hints are forbidden; plans must use the default PostgreSQL optimizer');
}

const series = [...fixture.matchAll(/generate_series\(1,\s*(\d+)\)/gu)].map((match) =>
  Number(match[1]),
);
if (series.length < 7 || series.some((rows) => rows !== contract.fixture.rowsPerQueryFamily)) {
  fail('query fixture must use generate_series(1, 6000) for every query family');
}
const analyzedTables = [...fixture.matchAll(/^ANALYZE public\.([a-z][a-z0-9_]*);$/gmu)].map(
  (match) => match[1],
);
requireExact(
  analyzedTables,
  [
    'workflow_instance',
    'node_instance',
    'schedule_occurrence',
    'outbox_event',
    'human_task',
    'dead_letter',
    'audit_log',
  ],
  'fixture ANALYZE table order',
);

requireDependency(pom, 'junit-jupiter');
requireDependency(pom, 'postgresql');
for (const token of ['mvnw.cmd', '-pl control-plane -am verify', 'G2-11']) {
  requireToken(runner, token, 'G2-11 PowerShell runner');
}

const expectedScripts = {
  'database:g2-11:verify': 'node scripts/database/verify-testcontainers-database.mjs',
  'database:g2-11:test': 'node scripts/database/test-testcontainers-database-verifier.mjs',
  'database:g2-11:integration':
    'powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/database/test-g2-11-testcontainers.ps1',
};
for (const [name, command] of Object.entries(expectedScripts)) {
  if (packageJson.scripts?.[name] !== command) fail(`package command drift: ${name}`);
}
for (const name of ['database:g2-11:verify', 'database:g2-11:test']) {
  requireToken(packageJson.scripts.verify, `pnpm run ${name}`, `pnpm verify binding for ${name}`);
}

requireExact(
  contract.sourceBindings,
  {
    integrationTest:
      'control-plane/src/test/java/com/workflowplatform/controlplane/database/DatabaseMigrationIT.java',
    queryFixture: 'control-plane/src/test/resources/database/g2-11-query-plan-fixture.sql',
    runner: 'scripts/database/test-g2-11-testcontainers.ps1',
    verifier: 'scripts/database/verify-testcontainers-database.mjs',
    verifierTests: 'scripts/database/test-testcontainers-database-verifier.mjs',
  },
  'G2-11 source bindings',
);
if (contract.document !== 'docs/architecture/testcontainers-database-verification.md') {
  fail('G2-11 document binding drift');
}
for (const token of [
  'pnpm database:g2-11:verify',
  'pnpm database:g2-11:test',
  'pnpm database:g2-11:integration',
  'PostgreSQL 18.4',
  'Flyway 11.7.2',
  '最高已测试版本为 PostgreSQL 17',
  'Q-01～Q-10',
  'enable_seqscan',
]) {
  requireToken(document, token, 'G2-11 architecture document');
}

console.log(
  'G2-11 Testcontainers database verification PASS: PostgreSQL 18.4 digest, Flyway 11.7.2, ' +
    '22 tables/330 columns, 22 PK, 39 FK, 41 UNIQUE constraints + 3 standalone unique indexes, ' +
    '102 CHECK, 47 operational indexes, 22 forced-RLS tables, 6 probes and Q-01～Q-10 default-planner contracts.',
);
