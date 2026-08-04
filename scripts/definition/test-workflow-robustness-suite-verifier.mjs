import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'definition', 'verify-workflow-robustness-suite.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'robustness-suite.json'),
  baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
  packageJson: path.join(repositoryRoot, 'package.json'),
  traceability: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
};
const flags = {
  contract: '--contract',
  baseline: '--baseline',
  packageJson: '--package-json',
  traceability: '--traceability',
};

function run(overrides = {}) {
  const args = [verifier];
  for (const [name, file] of Object.entries(overrides)) args.push(flags[name], file);
  return spawnSync(process.execPath, args, { cwd: repositoryRoot, encoding: 'utf8' });
}

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g3-10-verifier-'));
let mutationCount = 0;

function expectFailure(name, target, mutate, expected) {
  mutationCount += 1;
  const value = JSON.parse(readFileSync(sources[target], 'utf8'));
  mutate(value);
  const file = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  const result = run({ [target]: file });
  if (result.status === 0 || !output(result).includes(expected)) {
    throw new Error(`${name} was not rejected with ${expected}:\n${output(result)}`);
  }
}

try {
  const positive = run();
  if (positive.status !== 0) throw new Error(`Positive G3-10 verifier failed:\n${output(positive)}`);

  expectFailure('seed-drift', 'contract', (value) => { value.generation.seed = 30311; }, 'G3-10 generation contract drift');
  expectFailure('parser-cases-drift', 'contract', (value) => { value.parser.permutationCases = 32; }, 'G3-10 parser contract drift');
  expectFailure('parser-attacks-drift', 'contract', (value) => { value.parser.combinedAttackCorpusMinimum = 8; }, 'G3-10 parser contract drift');
  expectFailure('dag-cases-drift', 'contract', (value) => { value.graph.generatedDagCases = 24; }, 'G3-10 graph contract drift');
  expectFailure('maximum-nodes-drift', 'contract', (value) => { value.graph.maximumGeneratedNodes = 100; }, 'G3-10 graph contract drift');
  expectFailure('cycle-cases-drift', 'contract', (value) => { value.graph.generatedCycleCases = 8; }, 'G3-10 graph contract drift');
  expectFailure('root-extension-policy-drift', 'contract', (value) => { value.compatibility.unknownOptionalRootFields = 'drop'; }, 'G3-10 compatibility contract drift');
  expectFailure('fixture-checksum-drift', 'contract', (value) => { value.compatibility.fixtures[0].canonicalChecksum = '0'.repeat(64); }, 'G3-10 compatibility contract drift');
  expectFailure('cel-corpus-drift', 'contract', (value) => { value.expressions.maliciousCelCasesMinimum = 12; }, 'G3-10 expression contract drift');
  expectFailure('jsonpath-corpus-drift', 'contract', (value) => { value.expressions.maliciousJsonPathCasesMinimum = 9; }, 'G3-10 expression contract drift');
  expectFailure('budget-bomb-drift', 'contract', (value) => { value.expressions.generatedBudgetBombs = 2; }, 'G3-10 expression contract drift');
  expectFailure('writer-count-drift', 'contract', (value) => { value.concurrency.writers = 2; }, 'G3-10 concurrency contract drift');
  expectFailure('success-count-drift', 'contract', (value) => { value.concurrency.expectedSuccesses = 2; }, 'G3-10 concurrency contract drift');
  expectFailure('loser-failure-drift', 'contract', (value) => { value.concurrency.expectedLoserFailure = 'conflict'; }, 'G3-10 concurrency contract drift');
  expectFailure('loser-claim-leak', 'contract', (value) => { value.concurrency.loserIdempotencyClaimsRolledBack = false; }, 'G3-10 concurrency contract drift');
  expectFailure('baseline-major-drift', 'baseline', (value) => { value.schemaVersion = 2; }, 'compatibility baseline must remain schemaVersion 1');
  expectFailure('package-script-drift', 'packageJson', (value) => { value.scripts['definition:g3-10:verify'] = 'node missing.mjs'; }, 'package G3-10 verify script drifted');
  expectFailure('root-binding-removed', 'packageJson', (value) => { value.scripts.verify = value.scripts.verify.replace(' && pnpm run definition:g3-10:test', ''); }, 'pnpm verify binding for definition:g3-10:test');
  expectFailure('dsl-control-task-removed', 'traceability', (value) => { value.requirements.find((entry) => entry.id === 'REQ-DSL-001').controlTasks = value.requirements.find((entry) => entry.id === 'REQ-DSL-001').controlTasks.filter((task) => task !== 'G3-10'); }, 'REQ-DSL-001 must map control task G3-10');
  expectFailure('dsl-test-trace-removed', 'traceability', (value) => { const requirement = value.requirements.find((entry) => entry.id === 'REQ-DSL-001'); requirement.tests = requirement.tests.filter((entry) => !entry.includes('WorkflowDefinitionRobustnessTests.java')); }, 'REQ-DSL-001 must trace both G3-10 robustness test layers');
  expectFailure('definition-test-trace-removed', 'traceability', (value) => { const requirement = value.requirements.find((entry) => entry.id === 'REQ-DEF-001'); requirement.tests = requirement.tests.filter((entry) => !entry.includes('[G3-10]')); }, 'REQ-DEF-001 must trace the G3-10 PostgreSQL contention test');

  console.log(`G3-10 verifier negative tests PASS: ${mutationCount}/${mutationCount} mutations rejected.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
