import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const failureCodes = [
  'start_node_count_invalid',
  'end_node_required',
  'duplicate_node_id',
  'duplicate_edge_id',
  'unknown_edge_source',
  'unknown_edge_target',
  'self_loop_not_allowed',
  'incoming_edge_to_start',
  'outgoing_edge_from_end',
  'transition_not_allowed',
  'duplicate_edge_route',
  'outgoing_transition_ambiguous',
  'outgoing_edge_count_invalid',
  'node_unreachable',
  'node_cannot_reach_end',
  'cycle_not_allowed',
];

const validFixtures = ['condition-default.json', 'linear.json', 'multiple-ends.json'];
const invalidFixtures = [
  'ambiguous-transition.json',
  'cannot-reach-end.json',
  'cycle.json',
  'dangling-source.json',
  'dangling-target.json',
  'duplicate-edge-id.json',
  'duplicate-node-id.json',
  'duplicate-route.json',
  'incoming-start.json',
  'invalid-condition-outdegree.json',
  'invalid-start-outdegree.json',
  'invalid-transition.json',
  'missing-end.json',
  'missing-start.json',
  'multiple-starts.json',
  'outgoing-end.json',
  'self-loop.json',
  'unreachable-node.json',
];

function parseArguments(argv) {
  const options = {
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
    skipDocuments: false,
  };
  const flags = new Map([
    ['--contract', 'contract'],
    ['--java-root', 'javaRoot'],
    ['--java-tests', 'javaTests'],
    ['--fixtures', 'fixtures'],
    ['--package', 'packageFile'],
    ['--document', 'document'],
    ['--architecture-index', 'architectureIndex'],
    ['--requirements', 'requirements'],
    ['--matrix', 'matrix'],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (flags.has(argument)) {
      const value = argv[index + 1];
      if (!value) throw new Error(`${argument} requires a path`);
      options[flags.get(argument)] = path.resolve(repositoryRoot, value);
      index += 1;
    } else if (argument === '--skip-documents') {
      options.skipDocuments = true;
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return options;
}

function readText(file, label, errors) {
  if (!existsSync(file)) {
    errors.push(`${label} is missing: ${file}`);
    return '';
  }
  return readFileSync(file, 'utf8');
}

function readJson(file, label, errors) {
  const source = readText(file, label, errors);
  if (!source) return {};
  try {
    return JSON.parse(source);
  } catch (error) {
    errors.push(`${label} must be valid JSON: ${error.message}`);
    return {};
  }
}

function exactArray(actual, expected, label, errors) {
  if (!Array.isArray(actual) || JSON.stringify(actual) !== JSON.stringify(expected)) {
    errors.push(`${label} must equal ${expected.join(', ')}`);
  }
}

function exactObject(actual, expected, label, errors) {
  if (!actual || JSON.stringify(actual) !== JSON.stringify(expected)) {
    errors.push(`${label} drifted from the G3-03 contract`);
  }
}

function requireMarkers(source, markers, label, errors) {
  for (const marker of markers) {
    if (!source.includes(marker)) errors.push(`${label} marker missing: ${marker}`);
  }
}

function validateContract(contract, errors) {
  if (
    contract.schemaVersion !== 1 ||
    contract.contractVersion !== '1.0.0' ||
    contract.task !== 'G3-03' ||
    contract.requirement !== 'REQ-DSL-001' ||
    contract.module !== 'definition'
  ) {
    errors.push('graph contract identity must remain G3-03/REQ-DSL-001 version 1.0.0');
  }
  exactObject(
    contract.input,
    {
      schemaVersion: 1,
      javaType: 'com.workflowplatform.contracts.dsl.WorkflowDefinition',
      maximumNodes: 500,
      maximumEdges: 2000,
    },
    'graph input bounds',
    errors,
  );
  exactObject(
    contract.nodeInvariants,
    {
      startCount: 1,
      minimumEndCount: 1,
      nodeIdsUnique: true,
      allNodesReachableFromStart: true,
      allNonEndNodesReachSomeEnd: true,
    },
    'graph node invariants',
    errors,
  );
  exactObject(
    contract.edgeInvariants,
    {
      edgeIdsUnique: true,
      sourceAndTargetMustExist: true,
      selfLoopsAllowed: false,
      incomingEdgesToStartAllowed: false,
      outgoingEdgesFromEndAllowed: false,
      duplicateRoutesAllowed: false,
      routeIdentity: ['from', 'to', 'on'],
    },
    'graph edge invariants',
    errors,
  );
  exactObject(
    contract.transitionMatrix,
    {
      start: ['success', 'always'],
      end: [],
      http: ['success', 'failure', 'always'],
      condition: ['condition', 'always'],
      delay: ['success', 'failure', 'always'],
      human_task: ['success', 'failure', 'always'],
    },
    'graph transition matrix',
    errors,
  );
  exactObject(
    contract.outgoingRules,
    {
      startExactly: 1,
      conditionMinimum: 2,
      conditionRequiresConditionalBranch: true,
      otherNonEndMinimum: 1,
      maximumPerNonConditionTransition: 1,
      alwaysExclusiveOutsideCondition: true,
      conditionAlwaysMaximum: 1,
    },
    'graph outgoing rules',
    errors,
  );
  exactObject(
    contract.cyclePolicy,
    {
      directedCyclesAllowed: false,
      reason: 'DSL v1 has no explicit bounded loop construct',
      algorithm: 'depth-first-search-three-color',
    },
    'graph cycle policy',
    errors,
  );
  exactObject(
    contract.determinism,
    {
      violationOrder: 'validation-phase-then-definition-order',
      violationLocation: 'bounded-json-pointer',
      maximumViolationPathLength: 128,
      topologicalTieBreak: 'node-definition-index',
      complexity: 'O(V+E)',
      maximumTraversalDepth: 500,
    },
    'graph determinism contract',
    errors,
  );
  exactObject(
    contract.result,
    {
      javaType: 'com.workflowplatform.controlplane.definition.ValidatedWorkflowGraph',
      fields: ['definition', 'startNodeId', 'endNodeIds', 'topologicalNodeIds'],
      immutable: true,
    },
    'validated graph result contract',
    errors,
  );
  exactArray(contract.failureCodes, failureCodes, 'graph failure codes', errors);
  exactObject(
    contract.deferred,
    {
      'G3-04': 'node-specific schemas, connector and secret authorization',
      'G3-05': 'CEL and JSONPath parsing, typing and resource sandbox',
      'G3-10': 'cross-version properties and extended malicious-input combinations',
    },
    'graph deferred boundaries',
    errors,
  );
  exactObject(
    contract.sourceBindings,
    {
      javaPackage: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition',
      javaImplementation: 'DeterministicWorkflowGraphValidator.java',
      javaTests:
        'control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowGraphValidatorTests.java',
      fixtures: 'tests/contracts/dsl/graph',
      verifier: 'scripts/dsl/verify-dsl-graph-validation.mjs',
      verifierTests: 'scripts/dsl/test-dsl-graph-validation-verifier.mjs',
    },
    'graph source bindings',
    errors,
  );
  if (contract.document !== 'docs/architecture/dsl-graph-validation.md') {
    errors.push('graph architecture document binding drifted');
  }
}

function validateJava(options, errors) {
  const implementation = readText(
    path.join(options.javaRoot, 'DeterministicWorkflowGraphValidator.java'),
    'deterministic graph validator',
    errors,
  );
  requireMarkers(
    implementation,
    [
      '@Component',
      'implements WorkflowGraphValidator',
      'indexNodes(',
      'indexEdges(',
      'validateOutgoingRules(',
      'transitionAllowed(',
      'reachableFromStart(',
      'reachesAnyEnd(',
      'findCycleEdges(',
      'PriorityQueue<Integer>',
      'topologicalOrder(',
      'WorkflowGraphValidationFailure.DUPLICATE_EDGE_ROUTE',
      'WorkflowGraphValidationFailure.CYCLE_NOT_ALLOWED',
    ],
    'deterministic graph validator',
    errors,
  );

  const validatorInterface = readText(
    path.join(options.javaRoot, 'WorkflowGraphValidator.java'),
    'graph validator interface',
    errors,
  );
  requireMarkers(
    validatorInterface,
    ['@FunctionalInterface', 'ValidatedWorkflowGraph validate(WorkflowDefinition definition)'],
    'graph validator interface',
    errors,
  );

  const failureEnum = readText(
    path.join(options.javaRoot, 'WorkflowGraphValidationFailure.java'),
    'graph validation failure enum',
    errors,
  );
  const enumWireNames = [...failureEnum.matchAll(/\("([a-z_]+)"\)/g)].map((match) => match[1]);
  exactArray(enumWireNames, failureCodes, 'Java graph failure wire names', errors);
  requireMarkers(failureEnum, ['public String wireName()', 'private final String value'], 'graph failure enum', errors);

  const violation = readText(
    path.join(options.javaRoot, 'WorkflowGraphViolation.java'),
    'graph violation value',
    errors,
  );
  requireMarkers(
    violation,
    [
      'record WorkflowGraphViolation',
      'MAXIMUM_PATH_LENGTH = 128',
      "path.charAt(0) != '/'",
      'path must be a bounded JSON Pointer',
    ],
    'graph violation value',
    errors,
  );

  const exception = readText(
    path.join(options.javaRoot, 'WorkflowGraphValidationException.java'),
    'graph validation exception',
    errors,
  );
  requireMarkers(
    exception,
    ['List.copyOf(violations)', 'Workflow graph rejected: graph_invalid', 'violations must not be empty'],
    'graph validation exception',
    errors,
  );

  const result = readText(
    path.join(options.javaRoot, 'ValidatedWorkflowGraph.java'),
    'validated graph result',
    errors,
  );
  requireMarkers(
    result,
    [
      'final class ValidatedWorkflowGraph',
      'this.endNodeIds = List.copyOf(endNodeIds)',
      'this.topologicalNodeIds = List.copyOf(topologicalNodeIds)',
      'public List<String> topologicalNodeIds()',
    ],
    'validated graph result',
    errors,
  );

  const tests = readText(options.javaTests, 'Java graph validator tests', errors);
  const javaTestCount = (tests.match(/@Test\b/g) ?? []).length;
  if (javaTestCount !== 27) {
    errors.push(`Java graph validator test count must remain 27, found ${javaTestCount}`);
  }
  requireMarkers(
    tests,
    [
      'acceptsLinearGraphAndReturnsCompilerMetadata',
      'acceptsConditionBranchesWithAnAlwaysDefault',
      'acceptsMaximumNodeDepthWithinBound',
      'rejectsDuplicateNodeIds',
      'rejectsUnknownEdgeSource',
      'rejectsIncomingEdgesToStart',
      'rejectsAlwaysCombinedWithOutcomeRoutes',
      'rejectsNodesThatAreNotReachableFromStart',
      'rejectsNodesThatCannotReachAnyEnd',
      'rejectsEveryDirectedCycleBecauseDslV1HasNoBoundedLoopConstruct',
      'reportsLocalViolationsInStableDefinitionOrder',
      'exposesAllStableFailureWireNames',
      'keepsValidationResultsAndViolationsImmutable',
    ],
    'Java graph validator tests',
    errors,
  );
}

function listJsonFiles(directory, label, errors) {
  if (!existsSync(directory)) {
    errors.push(`${label} is missing: ${directory}`);
    return [];
  }
  return readdirSync(directory)
    .filter((entry) => entry.endsWith('.json'))
    .sort();
}

function validateFixtures(fixturesRoot, errors) {
  const validRoot = path.join(fixturesRoot, 'valid');
  const invalidRoot = path.join(fixturesRoot, 'invalid');
  exactArray(listJsonFiles(validRoot, 'valid graph fixtures', errors), validFixtures, 'valid graph fixture set', errors);
  exactArray(
    listJsonFiles(invalidRoot, 'invalid graph fixtures', errors),
    invalidFixtures,
    'invalid graph fixture set',
    errors,
  );
  for (const [directory, names] of [
    [validRoot, validFixtures],
    [invalidRoot, invalidFixtures],
  ]) {
    for (const name of names) {
      const fixture = readJson(path.join(directory, name), `graph fixture ${name}`, errors);
      if (
        fixture.schemaVersion !== 1 ||
        !Array.isArray(fixture.nodes) ||
        !Array.isArray(fixture.edges) ||
        !Array.isArray(fixture.triggers)
      ) {
        errors.push(`graph fixture ${name} must bind DSL v1 nodes, edges and triggers`);
      }
    }
  }
  const conditionDefault = readText(
    path.join(validRoot, 'condition-default.json'),
    'condition default fixture',
    errors,
  );
  requireMarkers(conditionDefault, ['"on": "condition"', '"on": "always"'], 'condition default fixture', errors);
  const cycle = readText(path.join(invalidRoot, 'cycle.json'), 'cycle fixture', errors);
  requireMarkers(cycle, ['"from": "task_b", "to": "task_a"', '"from": "task_b", "to": "end"'], 'cycle fixture', errors);
}

function validatePackage(packageFile, errors) {
  const packageJson = readJson(packageFile, 'root package.json', errors);
  const scripts = packageJson.scripts ?? {};
  if (scripts['dsl:g3-03:verify'] !== 'node scripts/dsl/verify-dsl-graph-validation.mjs') {
    errors.push('root package script dsl:g3-03:verify is not bound to the graph verifier');
  }
  if (scripts['dsl:g3-03:test'] !== 'node scripts/dsl/test-dsl-graph-validation-verifier.mjs') {
    errors.push('root package script dsl:g3-03:test is not bound to graph verifier tests');
  }
  const verify = scripts.verify ?? '';
  const parserIndex = verify.indexOf('dsl:g3-02:test');
  const graphVerifyIndex = verify.indexOf('dsl:g3-03:verify');
  const graphTestIndex = verify.indexOf('dsl:g3-03:test');
  const erIndex = verify.indexOf('er:verify');
  if (!(parserIndex < graphVerifyIndex && graphVerifyIndex < graphTestIndex && graphTestIndex < erIndex)) {
    errors.push('root verify chain must run G3-03 verify/test after G3-02 and before ER gates');
  }
}

function validateDocuments(options, errors) {
  const document = readText(options.document, 'graph architecture document', errors);
  requireMarkers(
    document,
    [
      'Accepted（G3-03）',
      'config/dsl/graph-validation.json',
      'Start 必须恰好一个',
      '至少存在一条最终通向任意 End 的路径',
      '所有有向环',
      '16 个失败码',
      'O(V+E)',
      'G3-04',
      'G3-05',
      'Java 27 项测试覆盖 3 个合法 fixture、18 类非法 fixture、500 节点最大深度',
    ],
    'graph architecture document',
    errors,
  );

  const architectureIndex = readText(options.architectureIndex, 'architecture index', errors);
  requireMarkers(
    architectureIndex,
    ['dsl-graph-validation.md', '唯一 Start', '16 个稳定失败码'],
    'architecture index',
    errors,
  );

  const requirements = readJson(options.requirements, 'traceability requirements', errors);
  const requirement = (requirements.requirements ?? []).find((entry) => entry.id === 'REQ-DSL-001');
  if (!requirement) {
    errors.push('REQ-DSL-001 is missing from traceability requirements');
  } else {
    const serialized = JSON.stringify(requirement);
    for (const marker of [
      'config/dsl/graph-validation.json',
      'WorkflowGraphValidatorTests.java',
      'test-dsl-graph-validation-verifier.mjs',
      'docs/architecture/dsl-graph-validation.md',
      'G3-04/G3-05',
    ]) {
      if (!serialized.includes(marker)) errors.push(`REQ-DSL-001 graph evidence marker missing: ${marker}`);
    }
    if (requirement.implementationStatus !== 'implemented') {
      errors.push('REQ-DSL-001 must be implemented after G3-10 closes the compiler/API test scope');
    }
  }

  const matrix = readText(options.matrix, 'traceability matrix', errors);
  const matrixLine = matrix.split(/\r?\n/).find((line) => line.includes('`REQ-DSL-001`')) ?? '';
  requireMarkers(
    matrixLine,
    ['图校验', 'Java 27 项', 'G3-04', 'G3-05', 'implemented'],
    'REQ-DSL-001 matrix row',
    errors,
  );
}

const options = parseArguments(process.argv.slice(2));
const errors = [];
validateContract(readJson(options.contract, 'graph validation contract', errors), errors);
validateJava(options, errors);
validateFixtures(options.fixtures, errors);
validatePackage(options.packageFile, errors);
if (!options.skipDocuments) validateDocuments(options, errors);

if (errors.length > 0) {
  for (const error of errors) console.error(`- ${error}`);
  process.exit(1);
}

console.log(
  'DSL G3-03 graph validation PASS: unique Start/IDs/references, legal transitions, bidirectional reachability, no directed cycles, deterministic topology, 16 stable failures, 27 Java cases and 21 fixtures.',
);
