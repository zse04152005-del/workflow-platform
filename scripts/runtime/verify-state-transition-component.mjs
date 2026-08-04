import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const defaults = {
  contract: path.join(repositoryRoot, 'config', 'runtime', 'transition-component.json'),
  stateMachine: path.join(repositoryRoot, 'config', 'runtime', 'state-machine.json'),
  service: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeStateTransitionService.java'),
  policy: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeTransitionPolicy.java'),
  effects: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeTransitionEffects.java'),
  exception: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeTransitionException.java'),
  jdbcStore: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeTransitionStore.java'),
  jdbcSupport: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeSupport.java'),
  unitTest: path.join(repositoryRoot, 'control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeStateTransitionServiceTests.java'),
  integrationTest: path.join(repositoryRoot, 'control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeTransitionStoreIT.java'),
  architecture: path.join(repositoryRoot, 'docs', 'architecture', 'runtime-state-transition-component.md'),
  architectureIndex: path.join(repositoryRoot, 'docs', 'architecture', 'README.md'),
  adr: path.join(repositoryRoot, 'docs', 'adr', 'ADR-003-runtime-instance-node-attempt-state-machines.md'),
  evidence: path.join(repositoryRoot, 'docs', 'planning', 'evidence', 'G4-02-runtime-state-transition-component.md'),
  matrix: path.join(repositoryRoot, 'docs', 'planning', '03-需求追踪矩阵.md'),
  traceability: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
  packageJson: path.join(repositoryRoot, 'package.json'),
};
const argumentNames = new Map([
  ['--contract', 'contract'], ['--state-machine', 'stateMachine'], ['--service', 'service'], ['--policy', 'policy'],
  ['--effects', 'effects'], ['--exception', 'exception'], ['--jdbc-store', 'jdbcStore'], ['--jdbc-support', 'jdbcSupport'], ['--unit-test', 'unitTest'],
  ['--integration-test', 'integrationTest'], ['--architecture', 'architecture'], ['--architecture-index', 'architectureIndex'],
  ['--adr', 'adr'], ['--evidence', 'evidence'], ['--matrix', 'matrix'], ['--traceability', 'traceability'],
  ['--package-json', 'packageJson'],
]);

function options(argv) {
  const result = { ...defaults };
  for (let index = 0; index < argv.length; index += 1) {
    const name = argumentNames.get(argv[index]);
    if (!name || !argv[index + 1]) throw new Error(`Invalid argument: ${String(argv[index])}`);
    result[name] = path.resolve(argv[index + 1]);
    index += 1;
  }
  return result;
}
function fail(message) { throw new Error(message); }
function exact(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) fail(`${label} drift; expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
}
function inputFile(file, label) {
  const resolved = path.resolve(file);
  if (!existsSync(resolved)) fail(`${label} does not exist: ${file}`);
  return resolved;
}
function repositoryFile(file, label) {
  const resolved = inputFile(file, label);
  if (!resolved.startsWith(`${repositoryRoot}${path.sep}`)) fail(`${label} is outside repository: ${file}`);
  return resolved;
}
function text(file, label) { return readFileSync(inputFile(file, label), 'utf8'); }
function token(source, expected, label) {
  if (!source.includes(expected)) fail(`${label} is missing: ${expected}`);
}

const input = options(process.argv.slice(2));
const contract = JSON.parse(text(input.contract, 'G4-02 contract'));
const stateMachine = JSON.parse(text(input.stateMachine, 'G4-01 state machine'));
const traceability = JSON.parse(text(input.traceability, 'traceability'));
const packageJson = JSON.parse(text(input.packageJson, 'package.json'));
const service = text(input.service, 'runtime transition service');
const policy = text(input.policy, 'runtime transition policy');
const effects = text(input.effects, 'runtime transition effects');
const exception = text(input.exception, 'runtime transition exception');
const jdbcStore = text(input.jdbcStore, 'runtime JDBC store');
const jdbcSupport = text(input.jdbcSupport, 'runtime JDBC support');
const unitTest = text(input.unitTest, 'runtime unit test');
const integrationTest = text(input.integrationTest, 'runtime PostgreSQL integration test');
const architecture = text(input.architecture, 'runtime transition architecture');
const architectureIndex = text(input.architectureIndex, 'architecture index');
const adr = text(input.adr, 'ADR-003');
const evidence = text(input.evidence, 'G4-02 evidence');
const matrix = text(input.matrix, 'traceability matrix');

exact(
  [contract.schemaVersion, contract.contractVersion, contract.task, contract.requirements, contract.status, contract.entities, contract.transitionCount],
  [1, '1.0.0', 'G4-02', ['REQ-STATE-001'], 'implemented', ['instance', 'node', 'attempt', 'schedule'], 45],
  'G4-02 contract identity',
);
exact(contract.protocol, {
  conditionalKey: ['tenant_id', 'id', 'status', 'version'],
  versionIncrement: 1,
  databaseAuthoritative: true,
  transactionBoundary: ['state_row', 'audit_log', 'outbox_event'],
  tenantContextFunction: 'public.app_set_tenant_context',
  actorAllowListRequired: true,
  workerFencingRequired: true,
  eventEnvelopeSchemaVersion: 1,
  secretFreePayload: true,
}, 'G4-02 transition protocol');
exact(contract.failures, [
  'NOT_FOUND', 'VERSION_CONFLICT', 'INVALID_STATE_TRANSITION', 'ACTOR_NOT_ALLOWED', 'INVALID_EFFECTS', 'FENCING_TOKEN_CONFLICT',
], 'G4-02 stable failures');
exact(contract.tests, {
  unit: 4,
  postgresIntegration: 8,
  covers: [
    'all_45_rules_registered', 'illegal_transition_rejected_before_io', 'actor_allow_list', 'state_dependent_effects',
    'four_entity_persistence', 'optimistic_lock_concurrency', 'worker_fencing', 'independent_counter_increment',
    'tenant_rls_non_disclosure', 'audit_outbox_atomic_rollback',
  ],
}, 'G4-02 test contract');
for (const [name, file] of Object.entries(contract.sourceBindings ?? {})) {
  repositoryFile(path.join(repositoryRoot, file), `${name} source binding`);
}

const expectedTransitions = new Set();
for (const entity of stateMachine.entities ?? []) {
  for (const transition of entity.transitions ?? []) {
    expectedTransitions.add(`${entity.id.toUpperCase()}|${transition.from}|${transition.to}|${transition.command}`);
  }
}
if (expectedTransitions.size !== 45) fail(`state machine must still contain 45 transitions; actual=${expectedTransitions.size}`);
const implementedTransitions = new Set();
const rulePattern = /rule\(\s*RuntimeEntityType\.([A-Z]+),\s*"([A-Z_]+)",\s*"([A-Z_]+)",\s*"([a-z0-9_]+)"/gu;
for (const match of policy.matchAll(rulePattern)) implementedTransitions.add(`${match[1]}|${match[2]}|${match[3]}|${match[4]}`);
exact([...implementedTransitions].sort(), [...expectedTransitions].sort(), 'Java 45-edge allow-list');

for (const required of ['RuntimeTransitionPolicy.requireAllowed(command)', 'store.transition(command, eventType)']) token(service, required, 'runtime transition service');
for (const required of ['Instant nextScheduleAt', 'String workerId', 'Long expectedLeaseGeneration', 'String errorSummaryRedacted', 'String outputDigest']) token(effects, required, 'typed transition effects');
for (const failure of contract.failures) token(exception, failure, 'runtime transition exception');
for (const required of [
  'TransactionTemplate', 'version = version + 1', 'AND id = ? AND status = ? AND version = ?',
  'insertAudit(command', 'insertOutbox(command', 'FENCING_TOKEN_CONFLICT', 'TimeOrderedUuidV7.generate()',
  'errorSummaryDigest', 'public.audit_log', 'public.outbox_event',
]) token(jdbcStore, required, 'runtime JDBC transaction store');
for (const required of ['app_set_tenant_context', 'transaction_timestamp()', 'ConnectionCallback<Void>']) {
  token(jdbcSupport, required, 'runtime JDBC transaction support');
}
for (const required of ['assertEquals(45, RuntimeTransitionPolicy.ruleCount())', 'illegalTransitionAndActorAreRejectedBeforePersistence', 'stateSpecificEffectsAndWorkerGuardAreRequired']) {
  token(unitTest, required, 'G4-02 unit tests');
}
for (const required of [
  'commitsStateAuditAndOutboxTogetherForInstance', 'supportsNodeAttemptAndScheduleEntities',
  'staleExpectedVersionIsAStableVersionConflict', 'fencingTokenIsCheckedForWorkerClaim',
  'workerClaimIncrementsOnlyTheAttemptCounter',
  'missingOutboxPermissionRollsBackStateAndAudit', 'concurrentWritersPermitExactlyOneVersionZeroTransition',
  'anotherTenantCannotDiscoverOrMutateTheRow', 'PostgreSQLContainer',
]) token(integrationTest, required, 'G4-02 PostgreSQL integration tests');
for (const required of ['G4-02', '45', 'expected status', 'expected version', 'audit_log', 'outbox_event', 'FENCING_TOKEN_CONFLICT', 'RLS']) {
  token(architecture, required, 'runtime transition architecture');
}
for (const required of ['runtime-state-transition-component.md', 'Runtime 统一状态迁移事务组件']) token(architectureIndex, required, 'architecture index');
for (const required of ['G4-02 实施状态', 'RuntimeStateTransitionService', 'JdbcRuntimeTransitionStore']) token(adr, required, 'ADR-003 implementation note');
for (const required of ['G4-02', '4/4 PASS', '8/8 PASS', 'VERSION_CONFLICT', 'FENCING_TOKEN_CONFLICT']) token(evidence, required, 'G4-02 evidence');
for (const required of ['REQ-STATE-001', 'G4-02', '4 单元 + 8 PostgreSQL 集成测试']) token(matrix, required, 'REQ-STATE-001 matrix row');

const scripts = packageJson.scripts ?? {};
exact(scripts['runtime:g4-02:verify'], 'node scripts/runtime/verify-state-transition-component.mjs', 'G4-02 verify script');
exact(scripts['runtime:g4-02:test'], 'node scripts/runtime/test-state-transition-component-verifier.mjs', 'G4-02 test script');
for (const name of ['runtime:g4-02:verify', 'runtime:g4-02:test']) token(scripts.verify, `pnpm run ${name}`, `root verify binding ${name}`);

const requirement = (traceability.requirements ?? []).find((entry) => entry.id === 'REQ-STATE-001');
if (!requirement?.controlTasks?.includes('G4-02')) fail('REQ-STATE-001 must map G4-02');
for (const field of ['events', 'tables', 'tests', 'docs']) {
  if (!(requirement[field] ?? []).some((entry) => entry.includes('implemented:') && entry.includes('[G4-02]'))) {
    fail(`REQ-STATE-001 must contain implemented G4-02 ${field} evidence`);
  }
}

console.log('G4-02 state transition component PASS: 45 rules, 4 entities, expected status/version, RLS, fencing and atomic state/audit/Outbox implementation are bound to 4 unit + 8 PostgreSQL integration tests.');
