import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'runtime', 'verify-state-transition-component.mjs');
const sources = {
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
const flags = {
  contract: '--contract', stateMachine: '--state-machine', service: '--service', policy: '--policy', effects: '--effects',
  exception: '--exception', jdbcStore: '--jdbc-store', jdbcSupport: '--jdbc-support', unitTest: '--unit-test', integrationTest: '--integration-test',
  architecture: '--architecture', architectureIndex: '--architecture-index', adr: '--adr', evidence: '--evidence',
  matrix: '--matrix', traceability: '--traceability', packageJson: '--package-json',
};
function run(overrides = {}) {
  const args = [verifier];
  for (const [name, file] of Object.entries(overrides)) args.push(flags[name], file);
  return spawnSync(process.execPath, args, { cwd: repositoryRoot, encoding: 'utf8' });
}
function output(result) { return `${result.stdout ?? ''}${result.stderr ?? ''}`; }

const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g4-02-verifier-'));
let mutationCount = 0;
function expectFailure(name, target, mutate, expected) {
  mutationCount += 1;
  let value = ['contract', 'stateMachine', 'traceability', 'packageJson'].includes(target)
    ? JSON.parse(readFileSync(sources[target], 'utf8'))
    : readFileSync(sources[target], 'utf8');
  const changed = mutate(value);
  if (changed !== undefined) value = changed;
  const extension = typeof value === 'string' ? path.extname(sources[target]) || '.txt' : '.json';
  const file = path.join(temporaryRoot, `${name}${extension}`);
  writeFileSync(file, typeof value === 'string' ? value : `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  const result = run({ [target]: file });
  if (result.status === 0 || !output(result).includes(expected)) {
    throw new Error(`${name} was not rejected with ${expected}:\n${output(result)}`);
  }
}

try {
  const positive = run();
  if (positive.status !== 0) throw new Error(`Positive G4-02 verifier failed:\n${output(positive)}`);
  expectFailure('contract-task', 'contract', (value) => { value.task = 'G4-X'; }, 'G4-02 contract identity drift');
  expectFailure('protocol-key', 'contract', (value) => { value.protocol.conditionalKey.pop(); }, 'G4-02 transition protocol drift');
  expectFailure('failure-removed', 'contract', (value) => { value.failures.pop(); }, 'G4-02 stable failures drift');
  expectFailure('test-count', 'contract', (value) => { value.tests.postgresIntegration = 7; }, 'G4-02 test contract drift');
  expectFailure('machine-edge', 'stateMachine', (value) => { value.entities[0].transitions.pop(); }, 'state machine must still contain 45 transitions');
  expectFailure('policy-edge', 'policy', (value) => value.replace('"CREATED",', '"CREATED_BROKEN",'), 'Java 45-edge allow-list drift');
  expectFailure('service-bypass', 'service', (value) => value.replace('RuntimeTransitionPolicy.requireAllowed(command)', '"event"'), 'runtime transition service is missing');
  expectFailure('conditional-lock', 'jdbcStore', (value) => value.replaceAll('AND id = ? AND status = ? AND version = ?', 'AND id = ?'), 'runtime JDBC transaction store is missing');
  expectFailure('transaction-removed', 'jdbcStore', (value) => value.replaceAll('TransactionTemplate', 'TransactionRunner'), 'runtime JDBC transaction store is missing: TransactionTemplate');
  expectFailure('tenant-context-removed', 'jdbcSupport', (value) => value.replace('app_set_tenant_context', 'missing_tenant_context'), 'runtime JDBC transaction support is missing: app_set_tenant_context');
  expectFailure('audit-removed', 'jdbcStore', (value) => value.replace('insertAudit(command', 'skipAudit(command'), 'runtime JDBC transaction store is missing: insertAudit(command');
  expectFailure('outbox-removed', 'jdbcStore', (value) => value.replace('insertOutbox(command', 'skipOutbox(command'), 'runtime JDBC transaction store is missing: insertOutbox(command');
  expectFailure('fencing-removed', 'jdbcStore', (value) => value.replaceAll('FENCING_TOKEN_CONFLICT', 'VERSION_CONFLICT'), 'runtime JDBC transaction store is missing: FENCING_TOKEN_CONFLICT');
  expectFailure('uuidv7-removed', 'jdbcStore', (value) => value.replace('TimeOrderedUuidV7.generate()', 'UUID.randomUUID()'), 'runtime JDBC transaction store is missing: TimeOrderedUuidV7.generate()');
  expectFailure('unit-rule-count', 'unitTest', (value) => value.replace('assertEquals(45, RuntimeTransitionPolicy.ruleCount())', 'assertEquals(44, RuntimeTransitionPolicy.ruleCount())'), 'G4-02 unit tests is missing');
  expectFailure('integration-concurrency', 'integrationTest', (value) => value.replace('concurrentWritersPermitExactlyOneVersionZeroTransition', 'removedConcurrencyTest'), 'G4-02 PostgreSQL integration tests is missing');
  expectFailure('integration-rollback', 'integrationTest', (value) => value.replace('missingOutboxPermissionRollsBackStateAndAudit', 'removedRollbackTest'), 'G4-02 PostgreSQL integration tests is missing');
  expectFailure('integration-tenant', 'integrationTest', (value) => value.replace('anotherTenantCannotDiscoverOrMutateTheRow', 'removedTenantTest'), 'G4-02 PostgreSQL integration tests is missing');
  expectFailure('architecture-fencing', 'architecture', (value) => value.replaceAll('FENCING_TOKEN_CONFLICT', 'FENCING_CONFLICT'), 'runtime transition architecture is missing: FENCING_TOKEN_CONFLICT');
  expectFailure('architecture-index', 'architectureIndex', (value) => value.replace('runtime-state-transition-component.md', 'runtime-transition-other.md'), 'architecture index is missing: runtime-state-transition-component.md');
  expectFailure('adr-implementation', 'adr', (value) => value.replace('G4-02 实施状态', '后续实施'), 'ADR-003 implementation note is missing: G4-02 实施状态');
  expectFailure('matrix-tests', 'matrix', (value) => value.replace('4 单元 + 8 PostgreSQL 集成测试', '专项测试'), 'REQ-STATE-001 matrix row is missing: 4 单元 + 8 PostgreSQL 集成测试');
  expectFailure('package-script', 'packageJson', (value) => { value.scripts['runtime:g4-02:verify'] = 'node missing.mjs'; }, 'G4-02 verify script drift');
  expectFailure('root-binding', 'packageJson', (value) => { value.scripts.verify = value.scripts.verify.replace(' && pnpm run runtime:g4-02:test', ''); }, 'root verify binding runtime:g4-02:test');
  expectFailure('trace-event', 'traceability', (value) => { const req = value.requirements.find((entry) => entry.id === 'REQ-STATE-001'); req.events = req.events.filter((entry) => !entry.includes('[G4-02]')); }, 'REQ-STATE-001 must contain implemented G4-02 events evidence');
  expectFailure('trace-doc', 'traceability', (value) => { const req = value.requirements.find((entry) => entry.id === 'REQ-STATE-001'); req.docs = req.docs.filter((entry) => !entry.includes('[G4-02]')); }, 'REQ-STATE-001 must contain implemented G4-02 docs evidence');
  console.log(`G4-02 verifier negative tests PASS: ${mutationCount}/${mutationCount} mutations rejected.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
