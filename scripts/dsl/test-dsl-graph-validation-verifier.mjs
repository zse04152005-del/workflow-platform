import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'dsl', 'verify-dsl-graph-validation.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'dsl', 'graph-validation.json'),
  javaRoot: path.join(
    repositoryRoot,
    'control-plane',
    'src',
    'main',
    'java',
    'com',
    'workflowplatform',
    'controlplane',
    'definition',
  ),
  javaTests: path.join(
    repositoryRoot,
    'control-plane',
    'src',
    'test',
    'java',
    'com',
    'workflowplatform',
    'controlplane',
    'definition',
    'WorkflowGraphValidatorTests.java',
  ),
  fixtures: path.join(repositoryRoot, 'tests', 'contracts', 'dsl', 'graph'),
  packageFile: path.join(repositoryRoot, 'package.json'),
  document: path.join(repositoryRoot, 'docs', 'architecture', 'dsl-graph-validation.md'),
  architectureIndex: path.join(repositoryRoot, 'docs', 'architecture', 'README.md'),
  requirements: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
  matrix: path.join(repositoryRoot, 'docs', 'planning', '03-需求追踪矩阵.md'),
};
const flags = {
  contract: '--contract',
  javaRoot: '--java-root',
  javaTests: '--java-tests',
  fixtures: '--fixtures',
  packageFile: '--package',
  document: '--document',
  architectureIndex: '--architecture-index',
  requirements: '--requirements',
  matrix: '--matrix',
};
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-dsl-graph-test-'));
let mutationCount = 0;

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function run(overrides = {}, full = false) {
  const args = [verifier];
  for (const [kind, file] of Object.entries(overrides)) args.push(flags[kind], file);
  if (!full) args.push('--skip-documents');
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 20 * 1024 * 1024,
    windowsHide: true,
  });
}

function writeJsonCase(name, kind, mutate) {
  const value = JSON.parse(readFileSync(sources[kind], 'utf8'));
  mutate(value);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  return target;
}

function writeTextCase(name, kind, mutate, extension = 'txt') {
  const value = readFileSync(sources[kind], 'utf8');
  const target = path.join(temporaryRoot, `${name}.${extension}`);
  writeFileSync(target, mutate(value), 'utf8');
  return target;
}

function expectFailure(name, overrides, expectedMessage, full = false) {
  mutationCount += 1;
  const result = run(overrides, full);
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expectedMessage)) {
    throw new Error(`${name} was not blocked as expected (${expectedMessage}):\n${combined}`);
  }
}

function expectJsonFailure(name, kind, mutate, expectedMessage, full = false) {
  expectFailure(name, { [kind]: writeJsonCase(name, kind, mutate) }, expectedMessage, full);
}

function mutatedJavaRoot(name, file, mutate) {
  const target = path.join(temporaryRoot, name);
  cpSync(sources.javaRoot, target, { recursive: true });
  const javaFile = path.join(target, file);
  writeFileSync(javaFile, mutate(readFileSync(javaFile, 'utf8')), 'utf8');
  return target;
}

function mutatedFixtureRoot(name, mutate) {
  const target = path.join(temporaryRoot, name);
  cpSync(sources.fixtures, target, { recursive: true });
  mutate(target);
  return target;
}

try {
  const positive = run({}, true);
  if (positive.status !== 0) throw new Error(`Positive DSL graph contract failed:\n${output(positive)}`);

  expectJsonFailure('contract-identity', 'contract', (contract) => {
    contract.task = 'G3-99';
  }, 'graph contract identity');
  expectJsonFailure('input-node-bound', 'contract', (contract) => {
    contract.input.maximumNodes = 1000;
  }, 'graph input bounds');
  expectJsonFailure('node-start-count', 'contract', (contract) => {
    contract.nodeInvariants.startCount = 2;
  }, 'graph node invariants');
  expectJsonFailure('edge-self-loop-policy', 'contract', (contract) => {
    contract.edgeInvariants.selfLoopsAllowed = true;
  }, 'graph edge invariants');
  expectJsonFailure('transition-matrix', 'contract', (contract) => {
    contract.transitionMatrix.start.push('failure');
  }, 'graph transition matrix');
  expectJsonFailure('outgoing-rules', 'contract', (contract) => {
    contract.outgoingRules.conditionMinimum = 1;
  }, 'graph outgoing rules');
  expectJsonFailure('cycle-policy', 'contract', (contract) => {
    contract.cyclePolicy.directedCyclesAllowed = true;
  }, 'graph cycle policy');
  expectJsonFailure('topology-determinism', 'contract', (contract) => {
    contract.determinism.topologicalTieBreak = 'node-id';
  }, 'graph determinism contract');
  expectJsonFailure('result-immutability', 'contract', (contract) => {
    contract.result.immutable = false;
  }, 'validated graph result contract');
  expectJsonFailure('failure-code', 'contract', (contract) => {
    contract.failureCodes.pop();
  }, 'graph failure codes');
  expectJsonFailure('deferred-boundary', 'contract', (contract) => {
    delete contract.deferred['G3-05'];
  }, 'graph deferred boundaries');
  expectJsonFailure('source-binding', 'contract', (contract) => {
    contract.sourceBindings.javaPackage = 'shared-contracts';
  }, 'graph source bindings');
  expectJsonFailure('document-binding', 'contract', (contract) => {
    contract.document = 'docs/architecture/other.md';
  }, 'graph architecture document binding drifted');

  expectFailure(
    'java-component',
    {
      javaRoot: mutatedJavaRoot('java-component', 'DeterministicWorkflowGraphValidator.java', (source) =>
        source.replace('@Component', '@Deprecated'),
      ),
    },
    'deterministic graph validator marker missing: @Component',
  );
  expectFailure(
    'java-node-index',
    {
      javaRoot: mutatedJavaRoot('java-node-index', 'DeterministicWorkflowGraphValidator.java', (source) =>
        source.replaceAll('indexNodes(', 'collectNodes('),
      ),
    },
    'deterministic graph validator marker missing: indexNodes(',
  );
  expectFailure(
    'java-transition-matrix',
    {
      javaRoot: mutatedJavaRoot('java-transition-matrix', 'DeterministicWorkflowGraphValidator.java', (source) =>
        source.replaceAll('transitionAllowed(', 'isRouteAllowed('),
      ),
    },
    'deterministic graph validator marker missing: transitionAllowed(',
  );
  expectFailure(
    'java-forward-reachability',
    {
      javaRoot: mutatedJavaRoot('java-forward-reachability', 'DeterministicWorkflowGraphValidator.java', (source) =>
        source.replaceAll('reachableFromStart(', 'walkForward('),
      ),
    },
    'deterministic graph validator marker missing: reachableFromStart(',
  );
  expectFailure(
    'java-reverse-reachability',
    {
      javaRoot: mutatedJavaRoot('java-reverse-reachability', 'DeterministicWorkflowGraphValidator.java', (source) =>
        source.replaceAll('reachesAnyEnd(', 'walkBackward('),
      ),
    },
    'deterministic graph validator marker missing: reachesAnyEnd(',
  );
  expectFailure(
    'java-cycle-detection',
    {
      javaRoot: mutatedJavaRoot('java-cycle-detection', 'DeterministicWorkflowGraphValidator.java', (source) =>
        source.replaceAll('findCycleEdges(', 'findBackLinks('),
      ),
    },
    'deterministic graph validator marker missing: findCycleEdges(',
  );
  expectFailure(
    'java-topological-order',
    {
      javaRoot: mutatedJavaRoot('java-topological-order', 'DeterministicWorkflowGraphValidator.java', (source) =>
        source.replace('PriorityQueue<Integer>', 'ArrayDeque<Integer>'),
      ),
    },
    'deterministic graph validator marker missing: PriorityQueue<Integer>',
  );
  expectFailure(
    'java-duplicate-route',
    {
      javaRoot: mutatedJavaRoot('java-duplicate-route', 'DeterministicWorkflowGraphValidator.java', (source) =>
        source.replaceAll('WorkflowGraphValidationFailure.DUPLICATE_EDGE_ROUTE', 'WorkflowGraphValidationFailure.DUPLICATE_EDGE_ID'),
      ),
    },
    'deterministic graph validator marker missing: WorkflowGraphValidationFailure.DUPLICATE_EDGE_ROUTE',
  );
  expectFailure(
    'java-failure-wire-name',
    {
      javaRoot: mutatedJavaRoot('java-failure-wire-name', 'WorkflowGraphValidationFailure.java', (source) =>
        source.replace('"cycle_not_allowed"', '"cycle_allowed"'),
      ),
    },
    'Java graph failure wire names',
  );
  expectFailure(
    'java-pointer-bound',
    {
      javaRoot: mutatedJavaRoot('java-pointer-bound', 'WorkflowGraphViolation.java', (source) =>
        source.replace('MAXIMUM_PATH_LENGTH = 128', 'MAXIMUM_PATH_LENGTH = 1024'),
      ),
    },
    'graph violation value marker missing: MAXIMUM_PATH_LENGTH = 128',
  );
  expectFailure(
    'java-payload-safe-message',
    {
      javaRoot: mutatedJavaRoot('java-payload-safe-message', 'WorkflowGraphValidationException.java', (source) =>
        source.replace('Workflow graph rejected: graph_invalid', 'Workflow graph rejected: invalid payload'),
      ),
    },
    'graph validation exception marker missing: Workflow graph rejected: graph_invalid',
  );
  expectFailure(
    'java-violation-copy',
    {
      javaRoot: mutatedJavaRoot('java-violation-copy', 'WorkflowGraphValidationException.java', (source) =>
        source.replace('List.copyOf(violations)', 'violations'),
      ),
    },
    'graph validation exception marker missing: List.copyOf(violations)',
  );
  expectFailure(
    'java-result-copy',
    {
      javaRoot: mutatedJavaRoot('java-result-copy', 'ValidatedWorkflowGraph.java', (source) =>
        source.replace('this.topologicalNodeIds = List.copyOf(topologicalNodeIds)', 'this.topologicalNodeIds = topologicalNodeIds'),
      ),
    },
    'validated graph result marker missing: this.topologicalNodeIds = List.copyOf(topologicalNodeIds)',
  );
  expectFailure(
    'java-interface-result',
    {
      javaRoot: mutatedJavaRoot('java-interface-result', 'WorkflowGraphValidator.java', (source) =>
        source.replace('ValidatedWorkflowGraph validate(WorkflowDefinition definition)', 'void validate(WorkflowDefinition definition)'),
      ),
    },
    'graph validator interface marker missing: ValidatedWorkflowGraph validate(WorkflowDefinition definition)',
  );

  expectFailure(
    'java-test-count',
    {
      javaTests: writeTextCase('java-test-count', 'javaTests', (source) => source.replace('@Test', '// removed'), 'java'),
    },
    'Java graph validator test count must remain 27',
  );
  expectFailure(
    'java-cycle-test',
    {
      javaTests: writeTextCase(
        'java-cycle-test',
        'javaTests',
        (source) => source.replace('rejectsEveryDirectedCycleBecauseDslV1HasNoBoundedLoopConstruct', 'renamedCycleTest'),
        'java',
      ),
    },
    'Java graph validator tests marker missing: rejectsEveryDirectedCycleBecauseDslV1HasNoBoundedLoopConstruct',
  );

  expectFailure(
    'fixture-missing',
    {
      fixtures: mutatedFixtureRoot('fixture-missing', (root) =>
        rmSync(path.join(root, 'invalid', 'cycle.json')),
      ),
    },
    'invalid graph fixture set',
  );
  expectFailure(
    'fixture-cycle-shape',
    {
      fixtures: mutatedFixtureRoot('fixture-cycle-shape', (root) => {
        const file = path.join(root, 'invalid', 'cycle.json');
        writeFileSync(
          file,
          readFileSync(file, 'utf8').replace('"from": "task_b", "to": "task_a"', '"from": "task_b", "to": "end"'),
          'utf8',
        );
      }),
    },
    'cycle fixture marker missing: "from": "task_b", "to": "task_a"',
  );

  expectJsonFailure('package-script', 'packageFile', (packageJson) => {
    packageJson.scripts['dsl:g3-03:verify'] = 'node scripts/other.mjs';
  }, 'root package script dsl:g3-03:verify');
  expectJsonFailure('verify-chain', 'packageFile', (packageJson) => {
    packageJson.scripts.verify = packageJson.scripts.verify.replace(' && pnpm run dsl:g3-03:test', '');
  }, 'root verify chain must run G3-03');

  expectFailure(
    'architecture-document',
    {
      document: writeTextCase(
        'architecture-document',
        'document',
        (source) =>
          source.replace(
            'Java 27 项测试覆盖 3 个合法 fixture、18 类非法 fixture、500 节点最大深度',
            'Java 测试覆盖若干案例',
          ),
        'md',
      ),
    },
    'graph architecture document marker missing: Java 27 项测试覆盖 3 个合法 fixture、18 类非法 fixture、500 节点最大深度',
    true,
  );
  expectFailure(
    'architecture-index',
    {
      architectureIndex: writeTextCase(
        'architecture-index',
        'architectureIndex',
        (source) => source.replace('dsl-graph-validation.md', 'dsl-other.md'),
        'md',
      ),
    },
    'architecture index marker missing: dsl-graph-validation.md',
    true,
  );
  expectJsonFailure('requirements-evidence', 'requirements', (requirements) => {
    const requirement = requirements.requirements.find((entry) => entry.id === 'REQ-DSL-001');
    requirement.tests = requirement.tests.filter((entry) => !entry.includes('test-dsl-graph-validation-verifier.mjs'));
  }, 'REQ-DSL-001 graph evidence marker missing: test-dsl-graph-validation-verifier.mjs', true);
  expectFailure(
    'matrix-evidence',
    {
      matrix: writeTextCase(
        'matrix-evidence',
        'matrix',
        (source) => source.replace('Java 27 项', 'Java 若干项'),
        'md',
      ),
    },
    'REQ-DSL-001 matrix row marker missing: Java 27 项',
    true,
  );

  console.log(`DSL G3-03 verifier tests PASS: ${mutationCount} negative mutation classes rejected.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
