import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function parseArguments(argv) {
  const options = {
    core: path.join(repositoryRoot, 'config', 'dsl', 'core-model.json'),
    schema: path.join(repositoryRoot, 'schemas', 'dsl', 'workflow-definition-v1.schema.json'),
    baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
    javaRoot: path.join(
      repositoryRoot,
      'shared-contracts',
      'src',
      'main',
      'java',
      'com',
      'workflowplatform',
      'contracts',
      'dsl',
    ),
    javaTests: path.join(
      repositoryRoot,
      'shared-contracts',
      'src',
      'test',
      'java',
      'com',
      'workflowplatform',
      'contracts',
      'dsl',
      'WorkflowDslCoreModelTests.java',
    ),
    packageFile: path.join(repositoryRoot, 'package.json'),
    document: path.join(repositoryRoot, 'docs', 'architecture', 'dsl-core-model.md'),
    skipDocument: false,
  };
  const flags = new Map([
    ['--core', 'core'],
    ['--schema', 'schema'],
    ['--baseline', 'baseline'],
    ['--java-root', 'javaRoot'],
    ['--java-tests', 'javaTests'],
    ['--package', 'packageFile'],
    ['--document', 'document'],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (flags.has(argument)) {
      const value = argv[index + 1];
      if (!value) throw new Error(`${argument} requires a path`);
      options[flags.get(argument)] = path.resolve(repositoryRoot, value);
      index += 1;
    } else if (argument === '--skip-document') {
      options.skipDocument = true;
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return options;
}

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`Unable to read JSON ${file}: ${error.message}`);
  }
}

function readText(file, label, errors) {
  if (!existsSync(file)) {
    errors.push(`${label} is missing: ${file}`);
    return '';
  }
  return readFileSync(file, 'utf8');
}

function exactArray(actual, expected, label, errors) {
  if (!Array.isArray(actual) || JSON.stringify(actual) !== JSON.stringify(expected)) {
    errors.push(`${label} must equal ${expected.join(', ')}`);
  }
}

function referenceName(reference) {
  return typeof reference === 'string' ? reference.split('/').at(-1) : undefined;
}

function definition(schema, reference) {
  return schema.$defs?.[referenceName(reference?.$ref)];
}

function validateIdentity(core, schema, baseline, errors) {
  if (
    core.schemaVersion !== 1 ||
    core.contractVersion !== '1.0.0' ||
    core.task !== 'G3-01' ||
    core.requirement !== 'REQ-DSL-001'
  ) {
    errors.push('core model identity must remain G3-01/REQ-DSL-001 version 1.0.0');
  }
  if (
    core.dslSchema !== 'schemas/dsl/workflow-definition-v1.schema.json' ||
    core.javaPackage !== 'com.workflowplatform.contracts.dsl'
  ) {
    errors.push('core model source identity drifted');
  }
  if (
    baseline.policy?.dsl?.immutableSchemaVersion !== 1 ||
    baseline.dslSchema !== core.dslSchema ||
    core.root?.immutableVersion !== baseline.policy.dsl.immutableSchemaVersion ||
    schema.properties?.schemaVersion?.const !== core.root?.immutableVersion
  ) {
    errors.push('DSL schemaVersion v1 must match the compatibility baseline and core model');
  }
}

function validateRoot(core, schema, errors) {
  exactArray(
    core.root?.required,
    ['schemaVersion', 'name', 'catalogVersion', 'triggers', 'nodes', 'edges'],
    'core root required fields',
    errors,
  );
  exactArray(
    core.root?.optional,
    ['description', 'inputSchema', 'outputSchema', 'metadata'],
    'core root optional fields',
    errors,
  );
  exactArray(schema.required, core.root?.required, 'DSL root required fields', errors);
  if (schema.additionalProperties !== false) errors.push('DSL root must reject additional properties');
  if (schema.properties?.catalogVersion?.minimum !== core.root?.catalogVersionMinimum) {
    errors.push('catalogVersion minimum drifted');
  }
  const limits = core.limits ?? {};
  const expectedLimits = {
    triggersMinimum: schema.properties?.triggers?.minItems,
    triggersMaximum: schema.properties?.triggers?.maxItems,
    nodesMinimum: schema.properties?.nodes?.minItems,
    nodesMaximum: schema.properties?.nodes?.maxItems,
    edgesMinimum: schema.properties?.edges?.minItems,
    edgesMaximum: schema.properties?.edges?.maxItems,
    metadataMaximum: schema.properties?.metadata?.maxProperties,
  };
  for (const [name, actual] of Object.entries(expectedLimits)) {
    if (limits[name] !== actual) errors.push(`${name} must match the DSL schema`);
  }
  if (
    limits.triggersMinimum !== 1 ||
    limits.triggersMaximum !== 20 ||
    limits.nodesMinimum !== 2 ||
    limits.nodesMaximum !== 500 ||
    limits.edgesMinimum !== 1 ||
    limits.edgesMaximum !== 2000 ||
    limits.metadataMaximum !== 32
  ) {
    errors.push('DSL resource limits drifted from the G3-01 baseline');
  }
}

function validateNodes(core, schema, errors) {
  exactArray(
    core.node?.types,
    ['start', 'end', 'http', 'condition', 'delay', 'human_task'],
    'core node types',
    errors,
  );
  exactArray(core.node?.commonRequired, ['id', 'type', 'executorVersion'], 'node common required fields', errors);
  exactArray(core.node?.commonOptional, ['displayName'], 'node common optional fields', errors);
  exactArray(core.node?.retryAllowed, ['http'], 'retry-enabled node types', errors);
  exactArray(core.node?.executionTimeoutAllowed, ['http'], 'timeout-enabled node types', errors);
  const nodeDefinitions = (schema.$defs?.node?.oneOf ?? []).map((entry) => definition(schema, entry));
  const actualTypes = nodeDefinitions.map((entry) => entry?.properties?.type?.const);
  exactArray(actualTypes, core.node?.types, 'DSL node types', errors);
  for (const nodeDefinition of nodeDefinitions) {
    const type = nodeDefinition?.properties?.type?.const ?? 'unknown';
    for (const field of core.node?.commonRequired ?? []) {
      if (!nodeDefinition?.required?.includes(field)) errors.push(`${type} node must require ${field}`);
    }
    if (nodeDefinition?.properties?.executorVersion?.minimum !== core.node?.executorVersionMinimum) {
      errors.push(`${type} executorVersion minimum drifted`);
    }
    if (!nodeDefinition?.properties?.displayName) errors.push(`${type} node must expose displayName`);
    if (type === 'http') {
      if (nodeDefinition.properties?.retry?.$ref !== '#/$defs/retryPolicy') {
        errors.push('HTTP node must bind retryPolicy');
      }
      if (nodeDefinition.properties?.timeout?.format !== 'duration') {
        errors.push('HTTP timeout must use duration format');
      }
    } else if (nodeDefinition?.properties?.retry || nodeDefinition?.properties?.timeout) {
      errors.push(`${type} node must not expose top-level retry or timeout`);
    }
  }
  if ((schema.$defs?.node?.oneOf ?? []).some((entry) => referenceName(entry.$ref)?.toLowerCase().includes('script'))) {
    errors.push('arbitrary script nodes are forbidden');
  }
}

function validateRetry(core, schema, errors) {
  const retry = core.retry ?? {};
  const retrySchema = schema.$defs?.retryPolicy;
  exactArray(retry.required, ['maxAttempts', 'strategy', 'initialDelay'], 'retry required fields', errors);
  exactArray(
    retry.optional,
    ['maxDelay', 'maxElapsedTime', 'increment', 'multiplier', 'jitterRatio'],
    'retry optional fields',
    errors,
  );
  exactArray(retrySchema?.required, retry.required, 'DSL retry required fields', errors);
  exactArray(retrySchema?.properties?.strategy?.enum, ['fixed', 'linear', 'exponential'], 'DSL retry strategies', errors);
  exactArray(retry.strategies, retrySchema?.properties?.strategy?.enum, 'core retry strategies', errors);
  if (
    retrySchema?.properties?.maxAttempts?.minimum !== retry.maxAttemptsMinimum ||
    retrySchema?.properties?.maxAttempts?.maximum !== retry.maxAttemptsMaximum ||
    retry.maxAttemptsMinimum !== 1 ||
    retry.maxAttemptsMaximum !== 10
  ) {
    errors.push('retry maxAttempts bounds drifted');
  }
  if (
    retrySchema?.properties?.multiplier?.minimum !== retry.multiplierMinimum ||
    retrySchema?.properties?.multiplier?.maximum !== retry.multiplierMaximum ||
    retry.multiplierMinimum !== 1 ||
    retry.multiplierMaximum !== 10
  ) {
    errors.push('retry multiplier bounds drifted');
  }
  if (
    retrySchema?.properties?.jitterRatio?.minimum !== retry.jitterRatioMinimum ||
    retrySchema?.properties?.jitterRatio?.maximum !== retry.jitterRatioMaximum ||
    retry.jitterRatioMinimum !== 0 ||
    retry.jitterRatioMaximum !== 1
  ) {
    errors.push('retry jitterRatio bounds drifted');
  }
  for (const field of ['initialDelay', 'maxDelay', 'maxElapsedTime', 'increment']) {
    if (retrySchema?.properties?.[field]?.format !== 'duration') {
      errors.push(`retry ${field} must use duration format`);
    }
  }
  exactArray(retry.strategyFields?.fixed, [], 'fixed retry fields', errors);
  exactArray(retry.strategyFields?.linear, ['increment'], 'linear retry fields', errors);
  exactArray(retry.strategyFields?.exponential, ['multiplier'], 'exponential retry fields', errors);
  const rules = retrySchema?.allOf ?? [];
  const fixed = rules.find((rule) => rule.if?.properties?.strategy?.const === 'fixed');
  const linear = rules.find((rule) => rule.if?.properties?.strategy?.const === 'linear');
  const exponential = rules.find((rule) => rule.if?.properties?.strategy?.const === 'exponential');
  const fixedText = JSON.stringify(fixed?.then ?? {});
  if (!fixedText.includes('increment') || !fixedText.includes('multiplier')) {
    errors.push('fixed retry must forbid increment and multiplier');
  }
  if (!linear?.then?.required?.includes('increment') || !JSON.stringify(linear.then.not ?? {}).includes('multiplier')) {
    errors.push('linear retry must require increment and forbid multiplier');
  }
  if (
    !exponential?.then?.required?.includes('multiplier') ||
    !JSON.stringify(exponential.then.not ?? {}).includes('increment')
  ) {
    errors.push('exponential retry must require multiplier and forbid increment');
  }
}

function validateEdgesAndParameters(core, schema, errors) {
  const edge = core.edge ?? {};
  const edgeSchema = schema.$defs?.edge;
  exactArray(edge.required, ['id', 'from', 'to', 'on'], 'edge required fields', errors);
  exactArray(edge.optional, ['when', 'mapping'], 'edge optional fields', errors);
  exactArray(edgeSchema?.required, edge.required, 'DSL edge required fields', errors);
  exactArray(edge.transitions, ['success', 'failure', 'always', 'condition'], 'edge transitions', errors);
  exactArray(edgeSchema?.properties?.on?.enum, edge.transitions, 'DSL edge transitions', errors);
  const conditionRule = (edgeSchema?.allOf ?? []).find(
    (rule) => rule.if?.properties?.on?.const === 'condition',
  );
  if (
    edge.conditionRequiresWhen !== true ||
    !conditionRule?.then?.required?.includes('when') ||
    edge.nonConditionForbidsWhen !== true ||
    !JSON.stringify(conditionRule?.else ?? {}).includes('when')
  ) {
    errors.push('condition edge when rule drifted');
  }
  const parameters = core.parameters ?? {};
  exactArray(parameters.definitionSchemas, ['inputSchema', 'outputSchema'], 'definition parameter schemas', errors);
  if (
    parameters.mappingField !== 'mapping' ||
    edgeSchema?.properties?.mapping?.maxProperties !== parameters.mappingMaximum ||
    parameters.mappingMaximum !== 100
  ) {
    errors.push('parameter mapping maximum drifted');
  }
  if (edgeSchema?.properties?.mapping?.propertyNames?.pattern !== parameters.targetPattern) {
    errors.push('parameter mapping target pattern drifted');
  }
  if (
    parameters.expressionDefinition !== 'safeExpression' ||
    schema.$defs?.safeExpression?.maxLength !== parameters.expressionMaximumLength ||
    parameters.expressionMaximumLength !== 2048
  ) {
    errors.push('parameter mapping expression boundary drifted');
  }
}

function validateTimeoutsAndDeterminism(core, schema, errors) {
  exactArray(core.timeout?.delayFields, ['duration', 'expiresAtExpression'], 'delay time fields', errors);
  if (
    core.timeout?.executionField !== 'timeout' ||
    core.timeout?.humanDeadlineField !== 'deadline' ||
    core.timeout?.durationFormat !== 'duration' ||
    core.timeout?.runtimeDurationMustBePositive !== true
  ) {
    errors.push('timeout model identity drifted');
  }
  if (
    schema.$defs?.delayNode?.properties?.config?.properties?.duration?.format !== 'duration' ||
    schema.$defs?.humanTaskNode?.properties?.config?.properties?.deadline?.format !== 'duration'
  ) {
    errors.push('delay and human-task durations must use duration format');
  }
  exactArray(
    core.determinism?.versionBindings,
    ['schemaVersion', 'catalogVersion', 'executorVersion'],
    'deterministic version bindings',
    errors,
  );
  exactArray(
    core.determinism?.forbiddenExpressionTokens,
    ['now', 'random', 'System', 'Runtime', 'class'],
    'forbidden expression tokens',
    errors,
  );
  if (core.determinism?.arbitraryScriptNodeAllowed !== false) {
    errors.push('arbitrary script node must remain forbidden');
  }
  const safePattern = schema.$defs?.safeExpression?.pattern ?? '';
  for (const token of core.determinism?.forbiddenExpressionTokens ?? []) {
    if (!safePattern.includes(token)) errors.push(`safe expression pattern must block ${token}`);
  }
}

function validateDeferredAndBindings(core, errors) {
  exactArray(Object.keys(core.deferred ?? {}), ['G3-02', 'G3-03', 'G3-04', 'G3-05', 'G3-10'], 'deferred task boundaries', errors);
  const expectedBindings = {
    javaModelRoot: 'shared-contracts/src/main/java/com/workflowplatform/contracts/dsl',
    javaTests:
      'shared-contracts/src/test/java/com/workflowplatform/contracts/dsl/WorkflowDslCoreModelTests.java',
    verifier: 'scripts/dsl/verify-dsl-core-model.mjs',
    verifierTests: 'scripts/dsl/test-dsl-core-model-verifier.mjs',
  };
  for (const [name, expected] of Object.entries(expectedBindings)) {
    if (core.sourceBindings?.[name] !== expected) errors.push(`source binding ${name} drifted`);
  }
  if (core.document !== 'docs/architecture/dsl-core-model.md') {
    errors.push('core model document binding drifted');
  }
}

function validateJava(options, errors) {
  const expectedFiles = [
    'DslModelSupport.java',
    'EdgeTransition.java',
    'NodeType.java',
    'ParameterMapping.java',
    'RetryPolicy.java',
    'RetryStrategy.java',
    'TriggerType.java',
    'WorkflowDefinition.java',
    'WorkflowEdge.java',
    'WorkflowNode.java',
    'WorkflowParameters.java',
    'WorkflowTrigger.java',
    'package-info.java',
  ];
  if (!existsSync(options.javaRoot)) {
    errors.push(`Java DSL model root is missing: ${options.javaRoot}`);
    return;
  }
  const actualFiles = readdirSync(options.javaRoot).filter((name) => name.endsWith('.java')).sort();
  exactArray(actualFiles, [...expectedFiles].sort(), 'Java DSL model files', errors);
  const sources = Object.fromEntries(
    expectedFiles.map((name) => [name, readText(path.join(options.javaRoot, name), `Java ${name}`, errors)]),
  );
  const records = {
    'WorkflowDefinition.java': [
      'int schemaVersion',
      'String name',
      'int catalogVersion',
      'List<WorkflowTrigger> triggers',
      'List<WorkflowNode> nodes',
      'List<WorkflowEdge> edges',
      'WorkflowParameters parameters',
    ],
    'WorkflowNode.java': ['String id', 'NodeType type', 'int executorVersion', 'Duration timeout', 'RetryPolicy retry'],
    'WorkflowEdge.java': ['String from', 'String to', 'EdgeTransition on', 'String when', 'List<ParameterMapping> mapping'],
    'WorkflowTrigger.java': ['String id', 'TriggerType type', 'Map<String, Object> configuration'],
    'WorkflowParameters.java': ['Map<String, Object> inputSchema', 'Map<String, Object> outputSchema'],
    'ParameterMapping.java': ['String target', 'String expression'],
    'RetryPolicy.java': [
      'int maxAttempts',
      'RetryStrategy strategy',
      'Duration initialDelay',
      'Duration maxDelay',
      'Duration maxElapsedTime',
      'Duration increment',
      'BigDecimal multiplier',
      'BigDecimal jitterRatio',
    ],
  };
  for (const [file, components] of Object.entries(records)) {
    const recordName = file.replace('.java', '');
    if (!new RegExp(`public\\s+record\\s+${recordName}\\s*\\(`).test(sources[file])) {
      errors.push(`${recordName} must remain a public record`);
    }
    for (const component of components) {
      if (!sources[file].includes(component)) errors.push(`${recordName} component missing: ${component}`);
    }
  }
  const enumWires = {
    'NodeType.java': ['start', 'end', 'http', 'condition', 'delay', 'human_task'],
    'TriggerType.java': ['manual', 'http', 'cron'],
    'EdgeTransition.java': ['success', 'failure', 'always', 'condition'],
    'RetryStrategy.java': ['fixed', 'linear', 'exponential'],
  };
  for (const [file, wires] of Object.entries(enumWires)) {
    for (const wire of wires) {
      if (!sources[file].includes(`\"${wire}\"`)) errors.push(`${file} wire value missing: ${wire}`);
    }
  }
  const requiredInvariants = [
    [sources['WorkflowDefinition.java'], 'CURRENT_SCHEMA_VERSION = 1', 'Java schemaVersion invariant'],
    [sources['WorkflowNode.java'], 'Only HTTP nodes may declare timeout or retry', 'Java HTTP policy invariant'],
    [sources['WorkflowEdge.java'], 'Only condition edges may declare when', 'Java condition edge invariant'],
    [sources['RetryPolicy.java'], 'linear retry requires increment and forbids multiplier', 'Java linear retry invariant'],
    [sources['RetryPolicy.java'], 'maxElapsedTime must not be less than initialDelay', 'Java retry window invariant'],
    [sources['DslModelSupport.java'], 'Collections.unmodifiableMap', 'Java deep immutable map invariant'],
    [sources['DslModelSupport.java'], 'Collections.unmodifiableList', 'Java deep immutable list invariant'],
    [sources['DslModelSupport.java'], 'non-deterministic token', 'Java deterministic expression shape invariant'],
  ];
  for (const [source, token, label] of requiredInvariants) {
    if (!source.includes(token)) errors.push(`${label} is missing`);
  }
  const tests = readText(options.javaTests, 'Java DSL core model tests', errors);
  if ((tests.match(/@Test/g) ?? []).length < 11) errors.push('Java DSL core model tests must contain at least 11 cases');
  for (const token of [
    'buildsAnImmutableWorkflowDefinition',
    'supportsFixedLinearAndExponentialRetryPolicies',
    'validatesEdgesAndParameterMappings',
    'keepsGraphAndSemanticValidationDeferredToLaterG3Tasks',
  ]) {
    if (!tests.includes(token)) errors.push(`Java DSL test coverage marker missing: ${token}`);
  }
}

function validateFixtures(schema, errors) {
  const ajv = new Ajv2020({
    allErrors: true,
    strict: true,
    strictRequired: false,
    allowUnionTypes: true,
  });
  addFormats(ajv);
  let validate;
  try {
    validate = ajv.compile(schema);
  } catch (error) {
    errors.push(`DSL schema compilation failed: ${error.message}`);
    return { validCount: 0, invalidCount: 0 };
  }
  const validDirectory = path.join(repositoryRoot, 'tests', 'contracts', 'dsl', 'valid');
  const invalidDirectory = path.join(repositoryRoot, 'tests', 'contracts', 'dsl', 'invalid');
  const validFiles = readdirSync(validDirectory).filter((name) => name.endsWith('.json')).sort();
  const invalidFiles = readdirSync(invalidDirectory).filter((name) => name.endsWith('.json')).sort();
  for (const name of validFiles) {
    if (!validate(readJson(path.join(validDirectory, name)))) {
      errors.push(`DSL valid fixture rejected: ${name}`);
    }
  }
  for (const name of invalidFiles) {
    if (validate(readJson(path.join(invalidDirectory, name)))) {
      errors.push(`DSL invalid fixture accepted: ${name}`);
    }
  }
  for (const required of ['linear-retry.json', 'refund-approval.json']) {
    if (!validFiles.includes(required)) errors.push(`required valid fixture is missing: ${required}`);
  }
  for (const required of ['linear-retry-missing-increment.json', 'fixed-retry-with-multiplier.json']) {
    if (!invalidFiles.includes(required)) errors.push(`required invalid fixture is missing: ${required}`);
  }
  return { validCount: validFiles.length, invalidCount: invalidFiles.length };
}

function validatePackage(options, errors) {
  const packageJson = readJson(options.packageFile);
  const scripts = packageJson.scripts ?? {};
  if (scripts['dsl:g3-01:verify'] !== 'node scripts/dsl/verify-dsl-core-model.mjs') {
    errors.push('package script dsl:g3-01:verify drifted');
  }
  if (scripts['dsl:g3-01:test'] !== 'node scripts/dsl/test-dsl-core-model-verifier.mjs') {
    errors.push('package script dsl:g3-01:test drifted');
  }
  if (!scripts.verify?.includes('pnpm run dsl:g3-01:verify') || !scripts.verify?.includes('pnpm run dsl:g3-01:test')) {
    errors.push('root pnpm verify must include both G3-01 DSL gates');
  }
}

function validateDocument(options, errors) {
  if (options.skipDocument) return;
  const document = readText(options.document, 'DSL core model document', errors);
  for (const token of [
    '# DSL v1 核心模型与确定性边界',
    'schemaVersion',
    'fixed',
    'linear',
    'exponential',
    '深层不可变',
    'G3-02',
    'G3-03',
    'G3-04',
    'G3-05',
    '不负责',
  ]) {
    if (!document.includes(token)) errors.push(`DSL core model document marker missing: ${token}`);
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const errors = [];
  const core = readJson(options.core);
  const schema = readJson(options.schema);
  const baseline = readJson(options.baseline);
  validateIdentity(core, schema, baseline, errors);
  validateRoot(core, schema, errors);
  validateNodes(core, schema, errors);
  validateRetry(core, schema, errors);
  validateEdgesAndParameters(core, schema, errors);
  validateTimeoutsAndDeterminism(core, schema, errors);
  validateDeferredAndBindings(core, errors);
  validateJava(options, errors);
  const fixtureCounts = validateFixtures(schema, errors);
  validatePackage(options, errors);
  validateDocument(options, errors);
  if (errors.length > 0) {
    console.error(`DSL G3-01 core model verification FAILED (${errors.length}):`);
    for (const error of errors) console.error(`- ${error}`);
    process.exitCode = 1;
    return;
  }
  console.log(
    `DSL G3-01 core model verification PASS: schemaVersion 1, 6 node types, 3 retry strategies, ${fixtureCounts.validCount} valid and ${fixtureCounts.invalidCount} invalid fixtures.`,
  );
}

main();
