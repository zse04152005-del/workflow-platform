import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const failureCodes = [
  'expression_syntax_invalid',
  'expression_type_mismatch',
  'expression_identifier_unknown',
  'expression_operation_forbidden',
  'expression_budget_exceeded',
];
const sandbox = {
  maximumTokens: 256,
  maximumAstNodes: 256,
  maximumNesting: 32,
  maximumPathSegments: 32,
  maximumEvaluationSteps: 512,
  timeoutMilliseconds: 25,
  maximumResultBytes: 262144,
  maximumResultDepth: 64,
  maximumResultEntries: 4096,
  clock: 'monotonic',
  resultTypes: ['null', 'string', 'boolean', 'finite-number', 'array', 'string-key-object'],
};

function parseArguments(argv) {
  const options = {
    contract: path.join(repositoryRoot, 'config', 'dsl', 'expression-sandbox.json'),
    javaRoot: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'definition'),
    javaTests: path.join(repositoryRoot, 'control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'definition', 'WorkflowExpressionCompilerTests.java'),
    fixtures: path.join(repositoryRoot, 'tests', 'contracts', 'dsl', 'expressions'),
    packageFile: path.join(repositoryRoot, 'package.json'),
    document: path.join(repositoryRoot, 'docs', 'architecture', 'dsl-expression-sandbox.md'),
    skipDocuments: false,
  };
  const flags = new Map([
    ['--contract', 'contract'],
    ['--java-root', 'javaRoot'],
    ['--java-tests', 'javaTests'],
    ['--fixtures', 'fixtures'],
    ['--package', 'packageFile'],
    ['--document', 'document'],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (flags.has(argument)) {
      if (!argv[index + 1]) throw new Error(`${argument} requires a path`);
      options[flags.get(argument)] = path.resolve(repositoryRoot, argv[index + 1]);
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

function exact(actual, expected, label, errors) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) errors.push(`${label} drifted from G3-05`);
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
    contract.task !== 'G3-05' ||
    contract.requirement !== 'REQ-DSL-001' ||
    contract.module !== 'definition'
  ) errors.push('expression contract identity must remain G3-05/REQ-DSL-001 version 1.0.0');
  exact(Object.keys(contract.languages ?? {}), ['CEL', 'JSON_PATH'], 'expression languages', errors);
  exact(contract.languages?.CEL?.sites, ['condition-node', 'condition-edge'], 'CEL sites', errors);
  if (
    contract.languages?.CEL?.profile !== 'workflow-cel-v1' ||
    contract.languages?.CEL?.functionInvocationAllowed !== false ||
    contract.languages?.CEL?.methodInvocationAllowed !== false ||
    contract.languages?.CEL?.comprehensionsAllowed !== false
  ) errors.push('CEL deterministic subset contract drifted');
  exact(
    contract.languages?.JSON_PATH?.roots,
    ['input', 'output', 'response', 'node', 'trigger'],
    'JSONPath roots',
    errors,
  );
  const jsonPath = contract.languages?.JSON_PATH ?? {};
  for (const field of [
    'standardDollarRootAllowed',
    'recursiveDescentAllowed',
    'wildcardAllowed',
    'filterAllowed',
    'sliceAllowed',
    'unionAllowed',
    'scriptAllowed',
  ]) {
    if (jsonPath[field] !== false) errors.push(`JSONPath forbidden capability drifted: ${field}`);
  }
  if (
    contract.staticTyping?.closedObjectRejectsUnknownProperty !== true ||
    contract.staticTyping?.emptyOrUnsupportedSchemaFallsBackToDynamic !== true ||
    contract.staticTyping?.expectedTypes?.['condition-node'] !== 'BOOLEAN' ||
    contract.staticTyping?.expectedTypes?.['delay-expires-at'] !== 'STRING'
  ) errors.push('expression static typing contract drifted');
  exact(contract.sandbox, sandbox, 'expression sandbox', errors);
  exact(
    contract.determinism?.forbiddenIdentifiers,
    ['now', 'random', 'System', 'Runtime', 'class', 'new', 'import'],
    'forbidden expression identifiers',
    errors,
  );
  if (
    contract.determinism?.reflectionAllowed !== false ||
    contract.determinism?.networkAllowed !== false ||
    contract.determinism?.filesystemAllowed !== false ||
    contract.determinism?.environmentAllowed !== false ||
    contract.determinism?.threadCreationAllowed !== false ||
    contract.determinism?.sourceEchoedInFailure !== false ||
    contract.determinism?.compiledSourceBinding !== 'lower-case-sha256'
  ) errors.push('expression isolation and source-binding contract drifted');
  exact(contract.failureCodes, failureCodes, 'expression failure codes', errors);
  if (!contract.deferred?.['G3-07'] || !contract.deferred?.['G4-12'] || !contract.deferred?.['G4-13'] || !contract.deferred?.['G3-10']) {
    errors.push('expression deferred runtime and API boundaries drifted');
  }
  const expectedBindings = {
    javaImplementation: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition/DeterministicWorkflowExpressionCompiler.java',
    javaResult: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition/CompiledWorkflowExpressions.java',
    javaPolicy: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition/ExpressionSandboxPolicy.java',
    javaGuard: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition/ExpressionEvaluationGuard.java',
    javaTests: 'control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowExpressionCompilerTests.java',
    fixtures: 'tests/contracts/dsl/expressions',
    verifier: 'scripts/dsl/verify-expression-sandbox.mjs',
    verifierTests: 'scripts/dsl/test-expression-sandbox-verifier.mjs',
  };
  exact(contract.sourceBindings, expectedBindings, 'expression source bindings', errors);
  if (contract.document !== 'docs/architecture/dsl-expression-sandbox.md') {
    errors.push('expression architecture document binding drifted');
  }
}

function validateJava(options, errors) {
  const implementation = readText(path.join(options.javaRoot, 'DeterministicWorkflowExpressionCompiler.java'), 'expression compiler', errors);
  const result = readText(path.join(options.javaRoot, 'CompiledWorkflowExpressions.java'), 'compiled expression result', errors);
  const compiled = readText(path.join(options.javaRoot, 'CompiledExpression.java'), 'compiled expression metadata', errors);
  const policy = readText(path.join(options.javaRoot, 'ExpressionSandboxPolicy.java'), 'expression policy', errors);
  const guard = readText(path.join(options.javaRoot, 'ExpressionEvaluationGuard.java'), 'expression runtime guard', errors);
  const failure = readText(path.join(options.javaRoot, 'WorkflowExpressionCompilationFailure.java'), 'expression failures', errors);
  requireMarkers(implementation, [
    '@Component',
    'implements WorkflowExpressionCompiler',
    'class CelLexer',
    'class CelParser',
    'class NamedRootJsonPathParser',
    'FORBIDDEN_IDENTIFIERS',
    'closedObject',
    'maximumPathSegments()',
    'MessageDigest.getInstance("SHA-256")',
  ], 'expression compiler', errors);
  requireMarkers(result, ['CompiledWorkflowExpressions', 'Collections.unmodifiableMap', 'ExpressionSandboxPolicy'], 'compiled expression result', errors);
  requireMarkers(compiled, ['sourceSha256', 'estimatedEvaluationSteps', '^[0-9a-f]{64}$'], 'compiled expression metadata', errors);
  requireMarkers(policy, ['256, 256, 32, 32, 512, 25, 262_144, 64, 4_096'], 'expression policy', errors);
  requireMarkers(guard, [
    'System::nanoTime',
    'maximumEvaluationSteps()',
    'maximumResultBytes()',
    'maximumResultDepth()',
    'maximumResultEntries()',
    'RESULT_TYPE',
  ], 'expression runtime guard', errors);
  for (const code of failureCodes) {
    if (!failure.includes(`("${code}")`)) errors.push(`expression failure enum missing: ${code}`);
  }
}

function validateFixtures(root, errors) {
  const counts = { valid: 0, invalid: 0 };
  for (const group of ['valid', 'invalid']) {
    const directory = path.join(root, group);
    if (!existsSync(directory)) {
      errors.push(`expression ${group} fixture directory is missing`);
      continue;
    }
    const files = readdirSync(directory).filter((file) => file.endsWith('.json')).sort();
    counts[group] = files.length;
    for (const file of files) {
      const fixture = readJson(path.join(directory, file), `${group} expression fixture ${file}`, errors);
      if (!['CEL', 'JSON_PATH'].includes(fixture.language)) errors.push(`${file} has an unknown expression language`);
      if (typeof fixture.site !== 'string' || fixture.site.length === 0) errors.push(`${file} is missing its expression site`);
      if (group === 'valid') {
        const sources = fixture.sources ?? [fixture.source];
        if (!Array.isArray(sources) || sources.some((source) => typeof source !== 'string' || source.length === 0)) {
          errors.push(`${file} must contain non-empty valid sources`);
        }
        if (typeof fixture.expectedType !== 'string') errors.push(`${file} is missing expectedType`);
      } else if (!failureCodes.includes(fixture.expectedFailure)) {
        errors.push(`${file} has an unknown expected expression failure`);
      }
    }
  }
  if (counts.valid < 3 || counts.invalid < 7) errors.push('expression fixtures must retain at least 3 valid and 7 invalid cases');
  return counts;
}

function validateTests(source, errors) {
  requireMarkers(source, [
    'compilesEveryCelAndJsonPathSiteIntoImmutablePayloadFreeMetadata',
    'appliesCelPrecedenceNumericTypingAndBracketAccess',
    'rejectsFunctionInvocationAndNondeterministicOrReflectiveOperations',
    'rejectsJsonPathRecursionFiltersAndNonStringSelections',
    'runtimeGuardEnforcesStepsAndMonotonicTimeout',
    'runtimeGuardEnforcesResultBytesDepthEntriesAndJsonTypes',
    'sourceSha256',
  ], 'expression Java tests', errors);
  if ((source.match(/@Test/g) ?? []).length < 10) errors.push('expression Java tests must retain at least 10 test methods');
}

function validatePackage(packageFile, errors) {
  const packageJson = readJson(packageFile, 'package.json', errors);
  if (packageJson.scripts?.['dsl:g3-05:verify'] !== 'node scripts/dsl/verify-expression-sandbox.mjs') {
    errors.push('package.json must expose dsl:g3-05:verify');
  }
  if (packageJson.scripts?.['dsl:g3-05:test'] !== 'node scripts/dsl/test-expression-sandbox-verifier.mjs') {
    errors.push('package.json must expose dsl:g3-05:test');
  }
  if (!packageJson.scripts?.verify?.includes('dsl:g3-05:verify') || !packageJson.scripts?.verify?.includes('dsl:g3-05:test')) {
    errors.push('workspace verify must include both G3-05 gates');
  }
}

function validateDocument(options, errors) {
  if (options.skipDocuments) return;
  const document = readText(options.document, 'G3-05 architecture document', errors);
  requireMarkers(document, [
    'G3-05',
    'workflow-cel-v1',
    'workflow-named-root-jsonpath-v1',
    'ExpressionEvaluationGuard',
    '25 ms',
    'G4-12',
    'G4-13',
  ], 'G3-05 architecture document', errors);
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const errors = [];
  const contract = readJson(options.contract, 'G3-05 expression contract', errors);
  validateContract(contract, errors);
  validateJava(options, errors);
  const counts = validateFixtures(options.fixtures, errors);
  validateTests(readText(options.javaTests, 'expression Java tests', errors), errors);
  validatePackage(options.packageFile, errors);
  validateDocument(options, errors);
  if (errors.length > 0) {
    for (const error of errors) console.error(`ERROR: ${error}`);
    process.exitCode = 1;
    return;
  }
  console.log(`DSL G3-05 expression verification PASS: 2 profiles, 5 failure codes, ${counts.valid} valid and ${counts.invalid} invalid fixtures.`);
}

main();
