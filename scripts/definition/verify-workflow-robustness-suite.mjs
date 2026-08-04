import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const defaults = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'robustness-suite.json'),
  baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
  packageJson: path.join(repositoryRoot, 'package.json'),
  traceability: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
};
const argumentNames = new Map([
  ['--contract', 'contract'],
  ['--baseline', 'baseline'],
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

function repositoryFile(relative, label) {
  const file = path.resolve(repositoryRoot, relative ?? '');
  if (!file.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(file)) {
    fail(`${label} does not exist inside the repository: ${String(relative)}`);
  }
  return file;
}

function sourceFile(relative, label) {
  return readFileSync(repositoryFile(relative, label), 'utf8');
}

function hasImplementedArtifact(requirement, field, file) {
  return (requirement?.[field] ?? []).some(
    (entry) => entry.includes('implemented:') && entry.includes(`file:${file}`) && entry.includes('[G3-10]'),
  );
}

const input = options(process.argv.slice(2));
const contract = JSON.parse(readFileSync(input.contract, 'utf8'));
const baseline = JSON.parse(readFileSync(input.baseline, 'utf8'));
const packageJson = JSON.parse(readFileSync(input.packageJson, 'utf8'));
const traceability = JSON.parse(readFileSync(input.traceability, 'utf8'));

exact(
  [contract.schemaVersion, contract.contractVersion, contract.task, contract.requirements],
  [1, '1.0.0', 'G3-10', ['REQ-DSL-001', 'REQ-DEF-001']],
  'G3-10 contract identity',
);
exact(contract.generation, {
  algorithm: 'java.util.Random',
  seed: 30310,
  reproducible: true,
  networkRequired: false,
}, 'G3-10 generation contract');
exact(contract.parser, {
  permutationCases: 64,
  properties: [
    'object-key-order-independent',
    'equivalent-number-normalization',
    'canonical-reparse-idempotent',
    'checksum-stable',
  ],
  combinedAttackCorpusMinimum: 9,
  failurePayloadEchoAllowed: false,
}, 'G3-10 parser contract');
exact(contract.graph, {
  generatedDagCases: 48,
  maximumGeneratedNodes: 500,
  generatedCycleCases: 16,
  properties: [
    'valid-dag-accepted',
    'topological-order-deterministic',
    'maximum-depth-stack-safe',
    'cycle-mutation-rejected-deterministically',
  ],
}, 'G3-10 graph contract');

const expectedFixtures = [
  {
    path: 'tests/contracts/dsl/robustness/compatibility/v1-minimal.json',
    canonicalChecksum: 'cb3b9f4cf6e2d7ba19da1a94421a4e1176c161bd1175bfd00d7ecbcf1655607a',
  },
  {
    path: 'tests/contracts/dsl/robustness/compatibility/v1-root-extension.json',
    canonicalChecksum: '577ef1eef8e139a20316fbd574beeaa3f404fc04d6e6b735d7c556165fc7bd34',
  },
  {
    path: 'tests/contracts/dsl/robustness/compatibility/v1-condition.json',
    canonicalChecksum: 'bef2fc4af7a2e714f4a0e2e7dec30d03bb7d76255a4cde05695810b242c9cf90',
  },
];
exact(contract.compatibility, {
  immutableSchemaVersion: 1,
  unknownOptionalRootFields: 'preserve-canonical-ignore-runtime',
  futureSchemaWithoutUpcaster: 'reject',
  fixtures: expectedFixtures,
}, 'G3-10 compatibility contract');

for (const fixture of expectedFixtures) {
  const source = sourceFile(fixture.path, `compatibility fixture ${fixture.path}`);
  const parsed = JSON.parse(source);
  if (parsed.schemaVersion !== 1) fail(`${fixture.path} must remain a DSL v1 fixture`);
}
requireToken(
  sourceFile(expectedFixtures[1].path, 'root extension fixture'),
  '"x-vendor"',
  'root extension fixture',
);

exact(contract.expressions, {
  maliciousCelCasesMinimum: 13,
  maliciousJsonPathCasesMinimum: 10,
  generatedBudgetBombs: 3,
  attackFamilies: [
    'function-invocation',
    'method-invocation',
    'reflection-runtime-environment',
    'nondeterministic-identifiers',
    'unsupported-language-constructs',
    'recursive-descent-wildcard-filter-slice-union-script',
    'token-ast-nesting-path-budget',
  ],
  failurePayloadEchoAllowed: false,
}, 'G3-10 expression contract');
exact(contract.concurrency, {
  database: 'PostgreSQL Testcontainers',
  writers: 8,
  startBarrier: true,
  sameStrongEtag: true,
  expectedSuccesses: 1,
  expectedLoserFailure: 'precondition_failed',
  expectedRevisionIncrement: 1,
  loserIdempotencyClaimsRolledBack: true,
  timeoutSeconds: 30,
}, 'G3-10 concurrency contract');
exact(contract.resolvedDeferredContracts, [
  'config/dsl/core-model.json',
  'config/dsl/parser-normalization.json',
  'config/dsl/graph-validation.json',
  'config/dsl/expression-sandbox.json',
], 'G3-10 deferred-contract resolution');

if (baseline.schemaVersion !== 1 || baseline.contractVersion !== '1.0.0') {
  fail('compatibility baseline must remain schemaVersion 1 and contractVersion 1.0.0');
}

for (const [name, file] of Object.entries(contract.sourceBindings ?? {})) {
  repositoryFile(file, `${name} source binding`);
}
const sources = Object.fromEntries(
  ['javaTests', 'postgresTests'].map((name) => [
    name,
    sourceFile(contract.sourceBindings?.[name], `${name} source binding`),
  ]),
);
const javaTokens = [
  'G3-10 deterministic property, compatibility and malicious-input regression suite',
  'GENERATION_SEED = 30_310L',
  'PARSER_PERMUTATION_CASES = 64',
  'GENERATED_DAG_CASES = 48',
  'GENERATED_CYCLE_CASES = 16',
  'new Random(GENERATION_SEED)',
  'doubleBom(minimal)',
  'minimalJson() + minimalJson()',
  'minimalYaml() + "extension: &shared [one]',
  'extension: !workflow/unsafe',
  '"[".repeat(65)',
  'nodeCount = index == 0 ? 500 : 2 + random.nextInt(499)',
  'frozenDslV1CompatibilityCorpusCompilesWithCanonicalChecksums',
  'optionalRootExtensionsRemainPreservedWhileFutureMajorRequiresAnUpcaster',
  'Runtime.getRuntime() != null',
  'System.getenv(\'HOME\') != null',
  'input.items.exists(x, x == 1)',
  'input.items[?(@.enabled)]',
  'input.items[(@.length-1)]',
  '"(".repeat(33)',
  '" && input.approved".repeat(100)',
  '".value".repeat(33)',
  'BUDGET_EXCEEDED',
  'assertFalse(exception.getMessage().contains(PAYLOAD_MARKER))',
];
for (const token of javaTokens) requireToken(sources.javaTests, token, 'WorkflowDefinitionRobustnessTests');

const postgresTokens = [
  'G3_10_CONCURRENT_WRITERS = 8',
  '@Order(11)',
  'g3_10AllowsOneOfEightBarrierSynchronizedWritersAndRollsBackLoserClaims',
  'new CountDownLatch(G3_10_CONCURRENT_WRITERS)',
  'Executors.newFixedThreadPool(G3_10_CONCURRENT_WRITERS)',
  'PRECONDITION_FAILED',
  'initial.draftRevision() + 1',
  "status = 'COMPLETED'",
  "status = 'PROCESSING'",
  'ready.await(30, TimeUnit.SECONDS)',
];
for (const token of postgresTokens) requireToken(sources.postgresTests, token, 'JdbcWorkflowDraftStoreIT');

const scripts = packageJson.scripts ?? {};
if (scripts['definition:g3-10:verify'] !== 'node scripts/definition/verify-workflow-robustness-suite.mjs') {
  fail('package G3-10 verify script drifted');
}
if (scripts['definition:g3-10:test'] !== 'node scripts/definition/test-workflow-robustness-suite-verifier.mjs') {
  fail('package G3-10 test script drifted');
}
for (const name of ['definition:g3-10:verify', 'definition:g3-10:test']) {
  requireToken(scripts.verify, `pnpm run ${name}`, `pnpm verify binding for ${name}`);
}

const requirements = new Map((traceability.requirements ?? []).map((entry) => [entry.id, entry]));
const dslRequirement = requirements.get('REQ-DSL-001');
const definitionRequirement = requirements.get('REQ-DEF-001');
for (const [id, requirement] of [
  ['REQ-DSL-001', dslRequirement],
  ['REQ-DEF-001', definitionRequirement],
]) {
  if (!(requirement?.controlTasks ?? []).includes('G3-10')) fail(`${id} must map control task G3-10`);
  if (!hasImplementedArtifact(requirement, 'docs', contract.document)) {
    fail(`${id} must trace the G3-10 robustness document`);
  }
}
if (!hasImplementedArtifact(dslRequirement, 'tests', contract.sourceBindings.javaTests)
    || !hasImplementedArtifact(dslRequirement, 'tests', contract.sourceBindings.verifierTests)) {
  fail('REQ-DSL-001 must trace both G3-10 robustness test layers');
}
if (!hasImplementedArtifact(definitionRequirement, 'tests', contract.sourceBindings.postgresTests)) {
  fail('REQ-DEF-001 must trace the G3-10 PostgreSQL contention test');
}

const document = sourceFile(contract.document, 'G3-10 document');
for (const token of [
  '30310',
  '64',
  '48',
  '500',
  '16',
  'canonical checksum',
  'DSL v1',
  'CEL',
  'JSONPath',
  '8',
  'ETag',
  'Testcontainers',
  'G4-01',
]) {
  requireToken(document, token, 'G3-10 document');
}

console.log(
  'G3-10 robustness verification PASS: seed=30310, parser=64, DAG=48/500 nodes, cycles=16, DSL v1 fixtures=3, CEL/JSONPath/budget attacks=13/10/3 and PostgreSQL writers=8.',
);
