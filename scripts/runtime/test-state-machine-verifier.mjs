import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'runtime', 'verify-state-machine.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'runtime', 'state-machine.json'),
  adr: path.join(repositoryRoot, 'docs', 'adr', 'ADR-003-runtime-instance-node-attempt-state-machines.md'),
  architectureDocument: path.join(repositoryRoot, 'docs', 'architecture', 'runtime-state-machines.md'),
  architectureIndex: path.join(repositoryRoot, 'docs', 'architecture', 'README.md'),
  matrix: path.join(repositoryRoot, 'docs', 'planning', '03-需求追踪矩阵.md'),
  migration: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'resources', 'db', 'migration', 'V3__create_runtime_and_schedule.sql'),
  packageJson: path.join(repositoryRoot, 'package.json'),
  traceability: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
};
const flags = {
  contract: '--contract', adr: '--adr', architectureDocument: '--architecture-document', architectureIndex: '--architecture-index',
  matrix: '--matrix', migration: '--migration', packageJson: '--package-json', traceability: '--traceability',
};

function run(overrides = {}) {
  const args = [verifier];
  for (const [name, file] of Object.entries(overrides)) args.push(flags[name], file);
  return spawnSync(process.execPath, args, { cwd: repositoryRoot, encoding: 'utf8' });
}
function output(result) { return `${result.stdout ?? ''}${result.stderr ?? ''}`; }

const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g4-01-verifier-'));
let mutationCount = 0;
function expectFailure(name, target, mutate, expected) {
  mutationCount += 1;
  let value = ['contract', 'packageJson', 'traceability'].includes(target)
    ? JSON.parse(readFileSync(sources[target], 'utf8'))
    : readFileSync(sources[target], 'utf8');
  const mutated = mutate(value);
  if (mutated !== undefined) value = mutated;
  const file = path.join(temporaryRoot, `${name}${typeof value === 'string' ? '.md' : '.json'}`);
  writeFileSync(file, typeof value === 'string' ? value : `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  const result = run({ [target]: file });
  if (result.status === 0 || !output(result).includes(expected)) throw new Error(`${name} was not rejected with ${expected}:\n${output(result)}`);
}

try {
  const positive = run();
  if (positive.status !== 0) throw new Error(`Positive G4-01 verifier failed:\n${output(positive)}`);
  expectFailure('protocol-version', 'contract', (value) => { value.transitionProtocol.versionIncrement = 2; }, 'transition protocol drift');
  expectFailure('invalid-transition-code', 'contract', (value) => { value.transitionProtocol.invalidTransitionFailure = 'BAD_STATE'; }, 'transition protocol drift');
  expectFailure('invariant-removed', 'contract', (value) => { value.invariants.pop(); }, 'state invariants drift');
  expectFailure('instance-state-removed', 'contract', (value) => { value.entities[0].states.pop(); }, 'instance states drift');
  expectFailure('instance-transition-removed', 'contract', (value) => { value.entities[0].transitions.pop(); }, 'instance transition matrix drift');
  expectFailure('node-transition-mutated', 'contract', (value) => { value.entities[1].transitions[0].to = 'FAILED'; }, 'node transition matrix drift');
  expectFailure('node-effect-mutated', 'contract', (value) => { value.entities[1].transitions[2].effects = []; }, 'node transition lacks effect: READY>DISPATCHED:dispatch');
  expectFailure('attempt-terminal-mutated', 'contract', (value) => { value.entities[2].terminalStates = ['SUCCEEDED']; }, 'attempt terminal states drift');
  expectFailure('schedule-transition-removed', 'contract', (value) => { value.entities[3].transitions.shift(); }, 'schedule transition matrix drift');
  expectFailure('source-adr-drift', 'contract', (value) => { value.sourceBindings.adr = 'docs/adr/missing.md'; }, 'adr source binding does not exist');
  expectFailure('adr-token-removed', 'adr', (value) => value.replaceAll('VERSION_CONFLICT', 'CONFLICT'), 'ADR-003 is missing: VERSION_CONFLICT');
  expectFailure('architecture-token-removed', 'architectureDocument', (value) => value.replace('expected version', 'version'), 'runtime architecture document is missing: expected version');
  expectFailure('architecture-index-removed', 'architectureIndex', (value) => value.replace('runtime-state-machines.md', 'runtime-other.md'), 'architecture index is missing: runtime-state-machines.md');
  expectFailure('matrix-marker-removed', 'matrix', (value) => value.replaceAll('REQ-STATE-001', 'REQ-STATE-OTHER'), 'REQ-STATE-001 matrix row is missing: REQ-STATE-001');
  expectFailure('migration-state-removed', 'migration', (value) => value.replaceAll("'PAUSED'", "'PAUSED_REMOVED'"), 'V3 workflow_instance state is missing: \'PAUSED\'');
  expectFailure('package-script-drift', 'packageJson', (value) => { value.scripts['runtime:g4-01:verify'] = 'node missing.mjs'; }, 'package G4-01 verify script drifted');
  expectFailure('root-chain-drift', 'packageJson', (value) => { value.scripts.verify = value.scripts.verify.replace(' && pnpm run runtime:g4-01:test', ''); }, 'pnpm verify binding for runtime:g4-01:test');
  expectFailure('state-control-task-removed', 'traceability', (value) => { const req = value.requirements.find((entry) => entry.id === 'REQ-STATE-001'); req.controlTasks = req.controlTasks.filter((task) => task !== 'G4-01'); }, 'REQ-STATE-001 must map G4-01');
  expectFailure('state-doc-evidence-removed', 'traceability', (value) => { const req = value.requirements.find((entry) => entry.id === 'REQ-STATE-001'); req.docs = req.docs.filter((entry) => !entry.includes('[G4-01]')); }, 'REQ-STATE-001 must contain implemented G4-01 docs evidence');
  expectFailure('state-test-evidence-removed', 'traceability', (value) => { const req = value.requirements.find((entry) => entry.id === 'REQ-STATE-001'); req.tests = req.tests.filter((entry) => !entry.includes('[G4-01]')); }, 'REQ-STATE-001 must contain implemented G4-01 tests evidence');
  console.log(`G4-01 verifier negative tests PASS: ${mutationCount}/${mutationCount} mutations rejected.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
