import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const defaults = {
  contract: path.join(repositoryRoot, 'config', 'runtime', 'state-machine.json'),
  adr: path.join(repositoryRoot, 'docs', 'adr', 'ADR-003-runtime-instance-node-attempt-state-machines.md'),
  architectureDocument: path.join(repositoryRoot, 'docs', 'architecture', 'runtime-state-machines.md'),
  architectureIndex: path.join(repositoryRoot, 'docs', 'architecture', 'README.md'),
  matrix: path.join(repositoryRoot, 'docs', 'planning', '03-需求追踪矩阵.md'),
  migration: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'resources', 'db', 'migration', 'V3__create_runtime_and_schedule.sql'),
  packageJson: path.join(repositoryRoot, 'package.json'),
  traceability: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
};
const argumentNames = new Map([
  ['--contract', 'contract'],
  ['--adr', 'adr'],
  ['--architecture-document', 'architectureDocument'],
  ['--architecture-index', 'architectureIndex'],
  ['--matrix', 'matrix'],
  ['--migration', 'migration'],
  ['--package-json', 'packageJson'],
  ['--traceability', 'traceability'],
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

function fail(message) {
  throw new Error(message);
}

function exact(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} drift; expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function requireToken(source, token, label) {
  if (!source.includes(token)) fail(`${label} is missing: ${token}`);
}

function repositoryFile(file, label) {
  const resolved = path.resolve(file);
  if (!resolved.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(resolved)) {
    fail(`${label} does not exist inside repository: ${file}`);
  }
  return resolved;
}

function inputFile(file, label) {
  const resolved = path.resolve(file);
  if (!existsSync(resolved)) fail(`${label} does not exist: ${file}`);
  return resolved;
}

function readText(file, label) {
  return readFileSync(inputFile(file, label), 'utf8');
}

const input = options(process.argv.slice(2));
const contract = JSON.parse(readFileSync(inputFile(input.contract, 'state machine contract'), 'utf8'));
const packageJson = JSON.parse(readFileSync(inputFile(input.packageJson, 'package.json'), 'utf8'));
const traceability = JSON.parse(readFileSync(inputFile(input.traceability, 'traceability'), 'utf8'));
const adr = readText(input.adr, 'ADR-003');
const architectureDocument = readText(input.architectureDocument, 'runtime architecture document');
const architectureIndex = readText(input.architectureIndex, 'architecture index');
const matrix = readText(input.matrix, 'traceability matrix');
const migration = readText(input.migration, 'V3 runtime migration');

exact(
  [contract.schemaVersion, contract.contractVersion, contract.task, contract.requirements, contract.status, contract.scope],
  [1, '1.0.0', 'G4-01', ['REQ-STATE-001', 'REQ-RETRY-001', 'REQ-CRON-001'], 'implemented', 'state-definition-only'],
  'G4-01 state machine identity',
);
exact(contract.transitionProtocol, {
  expectedStatusField: 'status',
  expectedVersionField: 'version',
  versionIncrement: 1,
  conditionalUpdateRequired: true,
  staleWriteFailure: 'VERSION_CONFLICT',
  invalidTransitionFailure: 'INVALID_STATE_TRANSITION',
  terminalWritePolicy: 'reject',
  databaseAuthoritative: true,
  enumOrdinalInferenceForbidden: true,
  transactionBoundary: ['state_row', 'audit_log', 'outbox_event'],
  outboxEventRequiredFor: ['instance', 'node', 'attempt', 'schedule'],
}, 'transition protocol');
exact(contract.invariants, [
  'every transition names an expected source status',
  'every transition compares and increments version atomically',
  'terminal states have no outgoing transitions',
  'retry creates a new attempt and never rewrites historical attempt state',
  'a stale worker token cannot complete or mutate a node',
  'state, audit and outbox commit or roll back together',
  'database remains the only authoritative runtime state',
], 'state invariants');

const expectedEntities = {
  instance: {
    table: 'workflow_instance',
    states: ['CREATED', 'RUNNING', 'PAUSE_REQUESTED', 'PAUSED', 'CANCEL_REQUESTED', 'CANCELLED', 'SUCCEEDED', 'FAILED'],
    initial: ['CREATED'],
    terminal: ['CANCELLED', 'SUCCEEDED', 'FAILED'],
    transitions: [
      'CREATED>RUNNING:start', 'RUNNING>PAUSE_REQUESTED:request_pause', 'RUNNING>CANCEL_REQUESTED:request_cancel',
      'RUNNING>SUCCEEDED:complete_success', 'RUNNING>FAILED:complete_failure', 'PAUSE_REQUESTED>PAUSED:pause',
      'PAUSE_REQUESTED>CANCEL_REQUESTED:request_cancel', 'PAUSE_REQUESTED>FAILED:pause_failure',
      'PAUSED>RUNNING:resume', 'PAUSED>CANCEL_REQUESTED:request_cancel', 'CANCEL_REQUESTED>CANCELLED:cancel',
    ],
  },
  node: {
    table: 'node_instance',
    states: ['PENDING', 'READY', 'DISPATCHED', 'RUNNING', 'RETRY_WAIT', 'WAITING_TIMER', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED'],
    initial: ['PENDING'],
    terminal: ['SUCCEEDED', 'FAILED', 'CANCELLED'],
    transitions: [
      'PENDING>READY:make_ready', 'PENDING>CANCELLED:cancel_pending', 'READY>DISPATCHED:dispatch',
      'READY>WAITING_TIMER:schedule_timer', 'READY>WAITING_APPROVAL:wait_approval', 'READY>CANCELLED:cancel_ready',
      'DISPATCHED>RUNNING:claim', 'DISPATCHED>READY:lease_expired', 'DISPATCHED>CANCELLED:cancel_dispatched',
      'RUNNING>READY:lease_expired', 'RUNNING>SUCCEEDED:complete_success', 'RUNNING>FAILED:complete_failure', 'RUNNING>RETRY_WAIT:schedule_retry',
      'RUNNING>WAITING_TIMER:wait_timer', 'RUNNING>WAITING_APPROVAL:wait_approval', 'RUNNING>CANCELLED:cancel_running',
      'RETRY_WAIT>READY:retry_due', 'RETRY_WAIT>CANCELLED:cancel_retry', 'WAITING_TIMER>READY:timer_due',
      'WAITING_TIMER>CANCELLED:cancel_timer', 'WAITING_APPROVAL>READY:approval_resume',
      'WAITING_APPROVAL>SUCCEEDED:approval_complete', 'WAITING_APPROVAL>FAILED:approval_reject',
      'WAITING_APPROVAL>CANCELLED:cancel_approval',
    ],
  },
  attempt: {
    table: 'task_execution',
    states: ['RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'ABANDONED', 'CANCELLED'],
    initial: ['RUNNING'],
    terminal: ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'ABANDONED', 'CANCELLED'],
    transitions: [
      'RUNNING>SUCCEEDED:finish_success', 'RUNNING>FAILED:finish_failure', 'RUNNING>TIMED_OUT:timeout',
      'RUNNING>ABANDONED:abandon', 'RUNNING>CANCELLED:cancel',
    ],
  },
  schedule: {
    table: 'schedule_occurrence',
    states: ['PENDING', 'CLAIMED', 'FIRED', 'SKIPPED', 'FAILED'],
    initial: ['PENDING'],
    terminal: ['FIRED', 'SKIPPED', 'FAILED'],
    transitions: [
      'PENDING>CLAIMED:claim_occurrence', 'PENDING>SKIPPED:skip_occurrence', 'CLAIMED>FIRED:fire_occurrence',
      'CLAIMED>FAILED:fail_occurrence', 'CLAIMED>SKIPPED:skip_claimed_occurrence',
    ],
  },
};

exact((contract.entities ?? []).map((entity) => entity.id), Object.keys(expectedEntities), 'entity order');
for (const [id, expected] of Object.entries(expectedEntities)) {
  const entity = contract.entities.find((item) => item.id === id);
  if (!entity) fail(`missing state entity: ${id}`);
  exact(entity.table, expected.table, `${id} table`);
  exact(entity.states, expected.states, `${id} states`);
  exact(entity.initialStates, expected.initial, `${id} initial states`);
  exact(entity.terminalStates, expected.terminal, `${id} terminal states`);
  const transitions = (entity.transitions ?? []).map((transition) => `${transition.from}>${transition.to}:${transition.command}`);
  exact(transitions, expected.transitions, `${id} transition matrix`);
  const states = new Set(entity.states);
  const terminals = new Set(entity.terminalStates);
  const seen = new Set();
  for (const transition of entity.transitions ?? []) {
    if (!states.has(transition.from) || !states.has(transition.to)) fail(`${id} transition references unknown state`);
    if (terminals.has(transition.from)) fail(`${id} terminal state has outgoing transition: ${transition.from}`);
    const key = `${transition.from}>${transition.to}:${transition.command}`;
    if (seen.has(key)) fail(`${id} duplicate transition: ${key}`);
    seen.add(key);
    for (const guard of ['expected_status', 'expected_version']) {
      if (!(transition.guards ?? []).some((item) => item.startsWith(guard))) fail(`${id} transition lacks ${guard}: ${key}`);
    }
    if (!Array.isArray(transition.actors) || transition.actors.length === 0) fail(`${id} transition lacks actor: ${key}`);
    if (!Array.isArray(transition.effects) || transition.effects.length === 0) fail(`${id} transition lacks effect: ${key}`);
    const hasEffect = (effect) => transition.effects.includes(effect);
    if (id === 'instance' && terminals.has(transition.to) && !hasEffect('set_completed_at')) {
      fail(`${id} transition ${key} must include set_completed_at`);
    }
    if (id === 'attempt' && terminals.has(transition.to) && !hasEffect('set_finished_at')) {
      fail(`${id} transition ${key} must include set_finished_at`);
    }
    if (id === 'node') {
      if (transition.to === 'READY' && !hasEffect('set_next_schedule_at')) fail(`${id} transition ${key} must include set_next_schedule_at`);
      if (transition.to === 'DISPATCHED' && !hasEffect('clear_next_schedule_at')) fail(`${id} transition ${key} must include clear_next_schedule_at`);
      if (transition.from === 'READY' && ['WAITING_APPROVAL', 'CANCELLED'].includes(transition.to) && !hasEffect('clear_next_schedule_at')) {
        fail(`${id} transition ${key} must include clear_next_schedule_at`);
      }
      if (['RETRY_WAIT', 'WAITING_TIMER'].includes(transition.to) && !hasEffect('set_next_schedule_at')) fail(`${id} transition ${key} must include set_next_schedule_at`);
      if (['DISPATCHED', 'RUNNING'].includes(transition.from) && transition.to !== 'RUNNING' && !hasEffect('clear_lease')) {
        fail(`${id} transition ${key} must include clear_lease`);
      }
    }
  }
}

for (const [name, file] of Object.entries(contract.sourceBindings ?? {})) repositoryFile(path.join(repositoryRoot, file), `${name} source binding`);
for (const token of [
  'G4-01', 'expectedStatus', 'expectedVersion', 'VERSION_CONFLICT', 'INVALID_STATE_TRANSITION',
  'terminal 状态无 outgoing transition', 'instance', 'node', 'attempt', 'schedule', 'Outbox',
]) requireToken(adr, token, 'ADR-003');
for (const token of ['state-machine.json', 'expected status', 'expected version', 'terminal', 'fencing token', 'PostgreSQL']) {
  requireToken(architectureDocument, token, 'runtime architecture document');
}
for (const token of ['runtime-state-machines.md', 'ADR-003']) requireToken(architectureIndex, token, 'architecture index');
for (const token of ['REQ-STATE-001', 'G4-01', '状态机']) requireToken(matrix, token, 'REQ-STATE-001 matrix row');
for (const [table, states] of Object.entries(Object.fromEntries(Object.entries(expectedEntities).map(([id, value]) => [value.table, value.states])))) {
  requireToken(migration, table, 'V3 runtime migration');
  for (const state of states) requireToken(migration, `'${state}'`, `V3 ${table} state`);
}

const scripts = packageJson.scripts ?? {};
if (scripts['runtime:g4-01:verify'] !== 'node scripts/runtime/verify-state-machine.mjs') fail('package G4-01 verify script drifted');
if (scripts['runtime:g4-01:test'] !== 'node scripts/runtime/test-state-machine-verifier.mjs') fail('package G4-01 test script drifted');
for (const name of ['runtime:g4-01:verify', 'runtime:g4-01:test']) requireToken(scripts.verify, `pnpm run ${name}`, `pnpm verify binding for ${name}`);

const requirements = new Map((traceability.requirements ?? []).map((entry) => [entry.id, entry]));
const stateRequirement = requirements.get('REQ-STATE-001');
if (!stateRequirement?.controlTasks?.includes('G4-01')) fail('REQ-STATE-001 must map G4-01');
for (const field of ['tests', 'docs']) {
  if (!(stateRequirement[field] ?? []).some((entry) => entry.includes('implemented:') && entry.includes('[G4-01]'))) {
    fail(`REQ-STATE-001 must contain implemented G4-01 ${field} evidence`);
  }
}
if (!stateRequirement.docs.some((entry) => entry.includes('ADR-003-runtime-instance-node-attempt-state-machines.md'))) fail('REQ-STATE-001 ADR-003 evidence is missing');

console.log('G4-01 state machine verification PASS: 4 entities, 45 explicit transitions, expected status/version protocol, terminal guards, transactional audit/outbox boundary and ADR-003 traceability are consistent.');
