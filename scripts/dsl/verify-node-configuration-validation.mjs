import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const failureCodes = [
  'trigger_schema_invalid',
  'node_schema_invalid',
  'parameter_type_invalid',
  'connector_reference_invalid',
  'connector_unavailable',
  'connector_use_forbidden',
  'secret_reference_invalid',
  'secret_unavailable',
  'secret_use_forbidden',
];

function parseArguments(argv) {
  const options = {
    contract: path.join(repositoryRoot, 'config', 'dsl', 'node-configuration-validation.json'),
    schema: path.join(repositoryRoot, 'schemas', 'dsl', 'workflow-definition-v1.schema.json'),
    javaRoot: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'definition'),
    javaTests: path.join(repositoryRoot, 'control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'definition', 'WorkflowConfigurationValidatorTests.java'),
    fixtures: path.join(repositoryRoot, 'tests', 'contracts', 'dsl', 'configuration'),
    packageFile: path.join(repositoryRoot, 'package.json'),
    document: path.join(repositoryRoot, 'docs', 'architecture', 'dsl-node-configuration-validation.md'),
    skipDocuments: false,
  };
  const flags = new Map([
    ['--contract', 'contract'],
    ['--schema', 'schema'],
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
  if (JSON.stringify(actual) !== JSON.stringify(expected)) errors.push(`${label} drifted from G3-04`);
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
    contract.task !== 'G3-04' ||
    contract.requirement !== 'REQ-DSL-001' ||
    contract.module !== 'definition'
  ) errors.push('node configuration contract identity must remain G3-04/REQ-DSL-001 version 1.0.0');
  exact(Object.keys(contract.triggerSchemas ?? {}), ['manual', 'http', 'cron'], 'trigger schema variants', errors);
  exact(Object.keys(contract.nodeSchemas ?? {}), ['start', 'end', 'http', 'condition', 'delay', 'human_task'], 'node schema variants', errors);
  if (
    contract.triggerSchemas?.http?.hmacRequiresSecret !== true ||
    contract.triggerSchemas?.http?.oidcForbidsHmacSecret !== true ||
    contract.triggerSchemas?.http?.idempotencyRequired !== true
  ) errors.push('HTTP trigger HMAC/OIDC/idempotency contract drifted');
  exact(contract.nodeSchemas?.http?.methods, ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'], 'HTTP method contract', errors);
  exact(contract.nodeSchemas?.delay?.exactlyOne, ['duration', 'expiresAtExpression'], 'delay XOR contract', errors);
  exact(contract.nodeSchemas?.human_task?.exactlyOne, ['candidateRole', 'assigneeExpression'], 'human assignment XOR contract', errors);
  if (
    JSON.stringify(contract.references?.lookupOrder) !== JSON.stringify(['project', 'tenant']) ||
    contract.references?.projectResourceShadowsTenantResource !== true ||
    contract.references?.crossTenantAllowed !== false ||
    contract.references?.crossProjectAllowed !== false ||
    contract.references?.deletedAllowed !== false ||
    contract.references?.secretMaterialLoaded !== false ||
    contract.references?.authorization !== 'explicit-kind-and-resource-id-use-grant'
  ) errors.push('resource scope and authorization contract drifted');
  exact(contract.references?.publishableStatuses, ['ACTIVE'], 'publishable resource status contract', errors);
  exact(contract.failureCodes, failureCodes, 'configuration failure codes', errors);
  if (!contract.deferred?.['G3-05'] || !contract.deferred?.['G3-07'] || !contract.deferred?.G6) {
    errors.push('configuration deferred boundaries must preserve G3-05, G3-07 and G6');
  }
  const expectedBindings = {
    dslSchema: 'schemas/dsl/workflow-definition-v1.schema.json',
    javaImplementation: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition/DeterministicWorkflowConfigurationValidator.java',
    javaResult: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition/ValidatedWorkflowConfiguration.java',
    javaContext: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition/WorkflowConfigurationValidationContext.java',
    javaResource: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition/IntegrationResource.java',
    javaTests: 'control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowConfigurationValidatorTests.java',
    fixtures: 'tests/contracts/dsl/configuration',
    verifier: 'scripts/dsl/verify-node-configuration-validation.mjs',
    verifierTests: 'scripts/dsl/test-node-configuration-validation-verifier.mjs',
  };
  exact(contract.sourceBindings, expectedBindings, 'configuration source bindings', errors);
  if (contract.document !== 'docs/architecture/dsl-node-configuration-validation.md') {
    errors.push('configuration architecture document binding drifted');
  }
}

function validateSchema(schema, errors) {
  const httpTrigger = schema.$defs?.httpTrigger;
  const httpConfig = schema.$defs?.httpNode?.properties?.config;
  if (!httpTrigger?.properties?.hmacSecretRef || !JSON.stringify(httpTrigger.allOf ?? []).includes('hmacSecretRef')) {
    errors.push('DSL HTTP trigger must bind hmacSecretRef conditionally');
  }
  if (
    httpConfig?.properties?.secretRefs?.type !== 'array' ||
    httpConfig?.properties?.secretRefs?.maxItems !== 20 ||
    httpConfig?.properties?.secretRefs?.uniqueItems !== true
  ) errors.push('DSL HTTP node secretRefs contract drifted');
  if (schema.$defs?.reference?.maxLength !== 200) errors.push('DSL managed reference maximum length drifted');
}

function validateJava(options, errors) {
  const implementation = readText(path.join(options.javaRoot, 'DeterministicWorkflowConfigurationValidator.java'), 'configuration validator', errors);
  const result = readText(path.join(options.javaRoot, 'ValidatedWorkflowConfiguration.java'), 'typed configuration result', errors);
  const context = readText(path.join(options.javaRoot, 'WorkflowConfigurationValidationContext.java'), 'validation context', errors);
  const resource = readText(path.join(options.javaRoot, 'IntegrationResource.java'), 'integration resource', errors);
  const failure = readText(path.join(options.javaRoot, 'WorkflowConfigurationValidationFailure.java'), 'configuration failures', errors);
  requireMarkers(implementation, [
    '@Component',
    'implements WorkflowConfigurationValidator',
    'validateHttpTrigger(',
    'validateCronTrigger(',
    'validateHttpNode(',
    'validateHumanTaskNode(',
    'resolveResource(',
    'context.projectId().equals(resource.projectId())',
    'resource.status() != Status.ACTIVE',
    'context.useGrants().contains(new ResourceUseGrant',
  ], 'configuration validator', errors);
  requireMarkers(result, ['sealed interface TriggerConfiguration', 'sealed interface NodeConfiguration', 'record ResolvedResource', 'Collections.unmodifiableMap'], 'typed configuration result', errors);
  requireMarkers(context, ['List.copyOf', 'Set.copyOf', 'requireUniqueResourceScopes'], 'validation context', errors);
  requireMarkers(resource, ['Secret-free connector or secret-reference metadata', 'ROTATION_REQUIRED', 'Connector status cannot require secret rotation'], 'integration resource', errors);
  for (const code of failureCodes) {
    if (!failure.includes(`("${code}")`)) errors.push(`configuration failure enum missing: ${code}`);
  }
}

function validateFixtures(schema, fixtureRoot, errors) {
  const ajv = new Ajv2020({
    allErrors: true,
    strict: true,
    strictRequired: false,
    allowUnionTypes: true,
    validateFormats: true,
  });
  addFormats(ajv);
  let validate;
  try {
    validate = ajv.compile(schema);
  } catch (error) {
    errors.push(`DSL schema compilation failed: ${error.message}`);
    return { validCount: 0, invalidCount: 0 };
  }
  const groups = [['valid', true], ['invalid', false]];
  const counts = { validCount: 0, invalidCount: 0 };
  for (const [group, expected] of groups) {
    const directory = path.join(fixtureRoot, group);
    if (!existsSync(directory)) {
      errors.push(`configuration ${group} fixture directory is missing`);
      continue;
    }
    const files = readdirSync(directory).filter((file) => file.endsWith('.json')).sort();
    counts[`${group}Count`] = files.length;
    for (const file of files) {
      const fixture = readJson(path.join(directory, file), `${group} fixture ${file}`, errors);
      const accepted = validate(fixture);
      if (accepted !== expected) errors.push(`${group} configuration fixture ${file} had unexpected schema result`);
    }
  }
  if (counts.validCount < 2 || counts.invalidCount < 5) errors.push('configuration fixtures must retain at least 2 valid and 5 invalid cases');
  return counts;
}

function validateTests(source, errors) {
  requireMarkers(source, [
    'validatesEveryNodeAndTriggerVariantIntoTypedImmutableConfiguration',
    'projectResourceShadowsTenantResourceEvenWhenProjectResourceIsDisabled',
    'CONNECTOR_USE_FORBIDDEN',
    'SECRET_USE_FORBIDDEN',
    'Status.ROTATION_REQUIRED',
    'OTHER_TENANT_ID',
    'OTHER_PROJECT_ID',
  ], 'configuration Java tests', errors);
  if ((source.match(/@Test/g) ?? []).length < 9) errors.push('configuration Java tests must retain at least 9 test methods');
}

function validatePackage(packageFile, errors) {
  const packageJson = readJson(packageFile, 'package.json', errors);
  if (packageJson.scripts?.['dsl:g3-04:verify'] !== 'node scripts/dsl/verify-node-configuration-validation.mjs') {
    errors.push('package.json must expose dsl:g3-04:verify');
  }
  if (packageJson.scripts?.['dsl:g3-04:test'] !== 'node scripts/dsl/test-node-configuration-validation-verifier.mjs') {
    errors.push('package.json must expose dsl:g3-04:test');
  }
  if (!packageJson.scripts?.verify?.includes('dsl:g3-04:verify') || !packageJson.scripts?.verify?.includes('dsl:g3-04:test')) {
    errors.push('workspace verify must include both G3-04 gates');
  }
}

function validateDocument(options, errors) {
  if (options.skipDocuments) return;
  const document = readText(options.document, 'G3-04 architecture document', errors);
  requireMarkers(document, ['G3-04', 'hmacSecretRef', 'secretRefs', '项目级同名对象', 'G3-05', 'G6'], 'G3-04 architecture document', errors);
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const errors = [];
  const contract = readJson(options.contract, 'G3-04 contract', errors);
  const schema = readJson(options.schema, 'DSL schema', errors);
  validateContract(contract, errors);
  validateSchema(schema, errors);
  validateJava(options, errors);
  const counts = validateFixtures(schema, options.fixtures, errors);
  validateTests(readText(options.javaTests, 'configuration Java tests', errors), errors);
  validatePackage(options.packageFile, errors);
  validateDocument(options, errors);
  if (errors.length > 0) {
    for (const error of errors) console.error(`ERROR: ${error}`);
    process.exitCode = 1;
    return;
  }
  console.log(`DSL G3-04 configuration verification PASS: 3 triggers, 6 nodes, 9 failure codes, ${counts.validCount} valid and ${counts.invalidCount} invalid fixtures.`);
}

main();
