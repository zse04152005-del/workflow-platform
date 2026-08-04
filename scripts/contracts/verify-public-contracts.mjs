import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const canonicalOpenApiDirectory = path.join(repositoryRoot, 'api', 'openapi');
const httpMethods = new Set(['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace']);

function parseArguments(argv) {
  const options = {
    openapi: path.join(repositoryRoot, 'api', 'openapi', 'workflow-platform.v1.openapi.json'),
    baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
    dslSchema: path.join(repositoryRoot, 'schemas', 'dsl', 'workflow-definition-v1.schema.json'),
    eventSchema: path.join(repositoryRoot, 'schemas', 'events', 'event-envelope-v1.schema.json'),
    skipDocument: false,
  };
  const names = new Map([
    ['--openapi', 'openapi'],
    ['--baseline', 'baseline'],
    ['--dsl-schema', 'dslSchema'],
    ['--event-schema', 'eventSchema'],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (names.has(argument)) {
      const value = argv[index + 1];
      if (!value) throw new Error(`${argument} requires a path`);
      options[names.get(argument)] = path.resolve(repositoryRoot, value);
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

function exactArray(actual, expected, label, errors) {
  if (!Array.isArray(actual)) {
    errors.push(`${label} must be an array`);
    return;
  }
  if (new Set(actual).size !== actual.length) errors.push(`${label} contains duplicates`);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    errors.push(`${label} must equal ${expected.join(', ')}`);
  }
}

function pointerValue(document, ref) {
  if (typeof ref !== 'string' || !ref.startsWith('#/')) return undefined;
  return ref
    .slice(2)
    .split('/')
    .map((part) => part.replaceAll('~1', '/').replaceAll('~0', '~'))
    .reduce((value, part) => value?.[part], document);
}

function resolveLocal(document, value) {
  if (!value || typeof value !== 'object' || typeof value.$ref !== 'string') return value;
  return value.$ref.startsWith('#/') ? pointerValue(document, value.$ref) : value;
}

function walk(value, visit, pointer = '#', seen = new Set()) {
  if (!value || typeof value !== 'object' || seen.has(value)) return;
  seen.add(value);
  visit(value, pointer);
  if (Array.isArray(value)) {
    value.forEach((item, index) => walk(item, visit, `${pointer}/${index}`, seen));
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    walk(child, visit, `${pointer}/${key.replaceAll('~', '~0').replaceAll('/', '~1')}`, seen);
  }
}

function validateReferences(openapi, dslSchema, errors) {
  walk(openapi, (value, pointer) => {
    if (typeof value.$ref !== 'string') return;
    if (value.$ref.startsWith('#/')) {
      if (pointerValue(openapi, value.$ref) === undefined) {
        errors.push(`broken internal $ref at ${pointer}: ${value.$ref}`);
      }
      return;
    }
    const target = path.resolve(canonicalOpenApiDirectory, value.$ref);
    if (!target.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(target)) {
      errors.push(`broken external $ref at ${pointer}: ${value.$ref}`);
    }
  });
  const expectedDslRef = '../../schemas/dsl/workflow-definition-v1.schema.json';
  if (openapi.components?.schemas?.WorkflowDefinition?.$ref !== expectedDslRef) {
    errors.push(`WorkflowDefinition must reference ${expectedDslRef}`);
  }
  if (dslSchema.$id !== 'https://schemas.workflow-platform.example/dsl/workflow-definition-v1.schema.json') {
    errors.push('DSL schema $id drifted');
  }
}

function operationEntries(openapi) {
  const entries = [];
  for (const [apiPath, pathItem] of Object.entries(openapi.paths ?? {})) {
    for (const [method, operation] of Object.entries(pathItem ?? {})) {
      if (httpMethods.has(method)) entries.push({ apiPath, method, operation, pathItem });
    }
  }
  return entries;
}

function requiredHeaderNames(openapi, entry) {
  const parameters = [...(entry.pathItem.parameters ?? []), ...(entry.operation.parameters ?? [])];
  return parameters
    .map((parameter) => resolveLocal(openapi, parameter))
    .filter((parameter) => parameter?.in === 'header' && parameter.required === true)
    .map((parameter) => parameter.name);
}

function requestSchema(openapi, operation) {
  const requestBody = resolveLocal(openapi, operation.requestBody);
  return resolveLocal(openapi, requestBody?.content?.['application/json']?.schema);
}

function containsTenantId(openapi, schema, visited = new Set()) {
  if (!schema || typeof schema !== 'object' || visited.has(schema)) return false;
  visited.add(schema);
  if (schema.$ref?.startsWith('#/')) return containsTenantId(openapi, pointerValue(openapi, schema.$ref), visited);
  if (Object.keys(schema.properties ?? {}).some((name) => name.toLowerCase() === 'tenantid')) return true;
  return Object.values(schema).some((child) => containsTenantId(openapi, child, visited));
}

function validateOpenApi(openapi, baseline, problemContract, errors) {
  if (openapi.openapi !== '3.1.0') errors.push('OpenAPI version must be 3.1.0');
  if (openapi.jsonSchemaDialect !== 'https://json-schema.org/draft/2020-12/schema') {
    errors.push('OpenAPI jsonSchemaDialect must be JSON Schema 2020-12');
  }
  if (openapi.info?.version !== '1.0.0') errors.push('OpenAPI public contract version must be 1.0.0');
  const oidc = openapi.components?.securitySchemes?.oidc;
  if (oidc?.type !== 'openIdConnect' || !oidc.openIdConnectUrl?.startsWith('https://')) {
    errors.push('OpenAPI must define an HTTPS OIDC security scheme');
  }
  if (!openapi.security?.some((item) => Array.isArray(item.oidc))) {
    errors.push('OpenAPI must require OIDC security by default');
  }

  const entries = operationEntries(openapi);
  const operationIds = entries.map((entry) => entry.operation.operationId);
  if (operationIds.some((id) => typeof id !== 'string' || !id)) errors.push('every operation must have operationId');
  if (new Set(operationIds).size !== operationIds.length) errors.push('duplicate operationId detected');
  const statusByOperationId = new Map(
    (baseline.operations ?? []).map((operation) => [operation.operationId, operation.implementationStatus ?? 'planned']),
  );

  for (const entry of entries) {
    const label = `${entry.method.toUpperCase()} ${entry.apiPath}`;
    if (!entry.apiPath.startsWith('/api/v1/')) errors.push(`${label} must remain below /api/v1`);
    const expectedStatus = statusByOperationId.get(entry.operation.operationId) ?? 'planned';
    if (!['planned', 'implemented'].includes(expectedStatus)) {
      errors.push(`${label} baseline implementation status is invalid: ${String(expectedStatus)}`);
    } else if (entry.operation['x-workflow-implementation-status'] !== expectedStatus) {
      errors.push(`${label} implementation status must remain truthful: ${expectedStatus}`);
    }
    if (!entry.operation.responses?.default || entry.operation.responses.default.$ref !== '#/components/responses/ProblemResponse') {
      errors.push(`${label} error response must reference ProblemResponse`);
    }
    const headerNames = requiredHeaderNames(openapi, entry);
    if (entry.method !== 'get' && !headerNames.includes('Idempotency-Key')) {
      errors.push(`${label} write operation requires Idempotency-Key`);
    }
    if (entry.apiPath.endsWith('/draft') && entry.method === 'put' && !headerNames.includes('If-Match')) {
      errors.push(`${label} draft update requires If-Match`);
    }
    if (containsTenantId(openapi, requestSchema(openapi, entry.operation))) {
      errors.push(`${label} request schema must not contain client tenantId`);
    }
  }

  for (const expected of baseline.operations ?? []) {
    const operation = openapi.paths?.[expected.path]?.[expected.method];
    const label = `${expected.method.toUpperCase()} ${expected.path}`;
    if (!operation) {
      errors.push(`required operation missing: ${label}`);
      continue;
    }
    if (operation.operationId !== expected.operationId) {
      errors.push(`${label} operationId must remain ${expected.operationId}`);
    }
    if (!operation.responses?.[expected.successStatus]) {
      errors.push(`${label} success response ${expected.successStatus} is missing`);
    }
    const entry = { apiPath: expected.path, method: expected.method, operation, pathItem: openapi.paths[expected.path] };
    const actualHeaders = requiredHeaderNames(openapi, entry);
    for (const header of expected.requiredHeaders ?? []) {
      if (!actualHeaders.includes(header)) errors.push(`${label} required header missing: ${header}`);
    }
  }

  const problemResponse = openapi.components?.responses?.ProblemResponse;
  if (problemResponse?.content?.['application/problem+json']?.schema?.$ref !== '#/components/schemas/ProblemDetail') {
    errors.push('ProblemResponse must use application/problem+json and ProblemDetail');
  }
  const problem = openapi.components?.schemas?.ProblemDetail;
  const requiredProblemMembers = [
    ...problemContract.problem.requiredStandardMembers,
    ...problemContract.problem.requiredExtensions,
  ];
  exactArray(problem?.required, requiredProblemMembers, 'ProblemDetail.required', errors);
  if (problem?.additionalProperties !== false) errors.push('ProblemDetail must reject additional members');
  exactArray(
    problem?.properties?.errors?.items && openapi.components.schemas.FieldValidationError.required,
    problemContract.validation.fieldErrorMembers,
    'FieldValidationError.required',
    errors,
  );
  exactArray(
    openapi.components?.schemas?.FieldValidationError?.properties?.code?.enum,
    problemContract.validation.allowedFieldCodes,
    'FieldValidationError.code enum',
    errors,
  );
  if (problem?.properties?.errors?.maxItems !== problemContract.validation.maxErrors) {
    errors.push(`ProblemDetail errors maxItems must be ${problemContract.validation.maxErrors}`);
  }
}

function schemaDescriptor(schema) {
  if (!schema || typeof schema !== 'object') return 'missing';
  if (schema.$ref) return `$ref:${schema.$ref.split('/').at(-1)}`;
  if (Array.isArray(schema.enum)) return 'enum';
  return schema.type ?? (schema.const !== undefined ? typeof schema.const : 'unknown');
}

function validateCompatibility(openapi, baseline, errors) {
  if (
    baseline.schemaVersion !== 1 ||
    baseline.contractVersion !== '1.0.0' ||
    baseline.task !== 'G2-10' ||
    baseline.requirement !== 'REQ-CONTRACT-001'
  ) {
    errors.push('compatibility baseline identity must remain G2-10/REQ-CONTRACT-001 version 1.0.0');
  }
  if (baseline.policy?.api?.majorPathPrefix !== '/api/v1') errors.push('API compatibility prefix must be /api/v1');
  exactArray(
    baseline.policy?.api?.breakingChangeRequires,
    ['new-major-path', 'ADR', 'migration-guide'],
    'API breaking change requirements',
    errors,
  );
  if (baseline.policy?.dsl?.immutableSchemaVersion !== 1 || baseline.policy?.event?.immutableSchemaVersion !== 1) {
    errors.push('DSL and Event v1 artifacts must remain immutable at schemaVersion 1');
  }
  if (baseline.policy?.event?.consumersIgnoreUnknownOptionalFields !== true) {
    errors.push('event consumers must ignore unknown optional fields');
  }

  for (const [name, frozen] of Object.entries(baseline.schemas ?? {})) {
    const current = openapi.components?.schemas?.[name];
    if (!current) {
      errors.push(`compatibility schema removed: ${name}`);
      continue;
    }
    for (const [property, descriptor] of Object.entries(frozen.properties ?? {})) {
      const actual = current.properties?.[property];
      if (!actual) {
        errors.push(`compatibility property removed: ${name}.${property}`);
      } else if (schemaDescriptor(actual) !== descriptor) {
        errors.push(`compatibility type changed: ${name}.${property}`);
      }
    }
    const currentRequired = current.required ?? [];
    if (frozen.role === 'response') {
      for (const property of frozen.required ?? []) {
        if (!currentRequired.includes(property)) errors.push(`response required property removed: ${name}.${property}`);
      }
    } else {
      for (const property of currentRequired) {
        if (!(frozen.required ?? []).includes(property)) {
          errors.push(`new required request field: ${name}.${property}`);
        }
      }
    }
    for (const [property, values] of Object.entries(frozen.enums ?? {})) {
      const actual = current.properties?.[property]?.enum ?? [];
      for (const value of values) {
        if (!actual.includes(value)) errors.push(`enum value removed: ${name}.${property}.${value}`);
      }
    }
  }
}

function referencedConstValues(schema, definitionName) {
  const definition = schema.$defs?.[definitionName];
  return (definition?.oneOf ?? [])
    .map((entry) => entry.$ref?.split('/').at(-1))
    .map((name) => schema.$defs?.[name]?.properties?.type?.const)
    .filter(Boolean);
}

function validateDslShape(schema, baseline, errors) {
  if (schema.$schema !== 'https://json-schema.org/draft/2020-12/schema') {
    errors.push('DSL must use JSON Schema 2020-12');
  }
  if (schema.properties?.schemaVersion?.const !== baseline.policy.dsl.immutableSchemaVersion) {
    errors.push('DSL schemaVersion v1 changed; create a new schema file and upcaster');
  }
  exactArray(schema.required, baseline.dsl.required, 'DSL required fields', errors);
  exactArray(referencedConstValues(schema, 'node'), baseline.dsl.nodeTypes, 'DSL node types', errors);
  exactArray(referencedConstValues(schema, 'trigger'), baseline.dsl.triggerTypes, 'DSL trigger types', errors);
  for (const nodeName of ['startNode', 'endNode', 'httpNode', 'conditionNode', 'delayNode', 'humanTaskNode']) {
    if (!schema.$defs?.[nodeName]?.required?.includes('executorVersion')) {
      errors.push(`${nodeName} must require executorVersion`);
    }
  }
  const expressionPattern = schema.$defs?.safeExpression?.pattern ?? '';
  for (const token of ['now', 'random', 'System', 'Runtime', 'class']) {
    if (!expressionPattern.includes(token)) errors.push(`safeExpression must block ${token}`);
  }
  const httpProperties = schema.$defs?.httpNode?.properties?.config?.properties ?? {};
  if (!httpProperties.connectorRef || httpProperties.endpoint || httpProperties.url) {
    errors.push('HTTP node must use connectorRef and must not expose arbitrary endpoint/url');
  }
  if (baseline.dsl.nodeTypes.includes('script')) errors.push('arbitrary script node is out of scope');
}

function validateEventShape(schema, baseline, errors) {
  if (schema.$schema !== 'https://json-schema.org/draft/2020-12/schema') {
    errors.push('Event Envelope must use JSON Schema 2020-12');
  }
  if (schema.properties?.schemaVersion?.const !== baseline.policy.event.immutableSchemaVersion) {
    errors.push('Event Envelope schemaVersion v1 changed; publish a new major or event type');
  }
  exactArray(schema.required, baseline.eventEnvelope.required, 'Event Envelope required fields', errors);
  if (schema.additionalProperties !== baseline.eventEnvelope.additionalProperties) {
    errors.push('Event Envelope must allow unknown optional fields for consumer forward compatibility');
  }
  if (schema.properties?.payload?.type !== 'object') errors.push('Event Envelope payload must be an object');
  if (!schema.$defs?.uuidV7?.pattern?.includes('-7')) errors.push('Event Envelope IDs must be UUIDv7');
  if (!schema.$defs?.utcInstant?.pattern?.endsWith('Z$')) errors.push('Event Envelope occurredAt must be UTC Z');
  if (!schema.$defs?.traceId?.pattern?.includes('0{32}')) errors.push('Event Envelope traceId must reject all-zero values');
}

function createAjv() {
  const ajv = new Ajv2020({
    allErrors: true,
    strict: true,
    strictRequired: false,
    allowUnionTypes: true,
  });
  addFormats(ajv);
  return ajv;
}

function compileOpenApiSchemas(openapi, dslSchema, errors) {
  const schemas = structuredClone(openapi.components?.schemas ?? {});
  schemas.WorkflowDefinition = structuredClone(dslSchema);
  walk(schemas, (value) => {
    if (typeof value.$ref === 'string' && value.$ref.startsWith('#/components/schemas/')) {
      value.$ref = value.$ref.replace('#/components/schemas/', '#/$defs/');
    }
  });
  try {
    createAjv().compile({
      $schema: 'https://json-schema.org/draft/2020-12/schema',
      $defs: schemas,
    });
  } catch (error) {
    errors.push(`OpenAPI component schema compilation failed: ${error.message}`);
  }
  return Object.keys(schemas).length;
}

function fixtureFiles(group, validity) {
  const directory = path.join(repositoryRoot, 'tests', 'contracts', group, validity);
  return readdirSync(directory)
    .filter((name) => name.endsWith('.json'))
    .sort()
    .map((name) => path.join(directory, name));
}

function validateFixtures(schema, group, errors) {
  let validate;
  try {
    validate = createAjv().compile(schema);
  } catch (error) {
    errors.push(`${group} schema compilation failed: ${error.message}`);
    return { valid: 0, invalid: 0 };
  }
  const validFiles = fixtureFiles(group, 'valid');
  const invalidFiles = fixtureFiles(group, 'invalid');
  for (const file of validFiles) {
    if (!validate(readJson(file))) {
      errors.push(`${group} valid fixture rejected: ${path.basename(file)} (${createAjv().errorsText(validate.errors)})`);
    }
  }
  for (const file of invalidFiles) {
    if (validate(readJson(file))) errors.push(`${group} invalid fixture accepted: ${path.basename(file)}`);
  }
  return { valid: validFiles.length, invalid: invalidFiles.length };
}

function validatePackageScripts(errors) {
  const scripts = readJson(path.join(repositoryRoot, 'package.json')).scripts ?? {};
  if (scripts['contracts:public:verify'] !== 'node scripts/contracts/verify-public-contracts.mjs') {
    errors.push('package.json contracts:public:verify script is missing or drifted');
  }
  if (scripts['contracts:public:test'] !== 'node scripts/contracts/test-public-contracts-verifier.mjs') {
    errors.push('package.json contracts:public:test script is missing or drifted');
  }
  if (!scripts.verify?.includes('contracts:public:verify') || !scripts.verify?.includes('contracts:public:test')) {
    errors.push('pnpm verify must include both public contract gates');
  }
}

function validateDocument(baseline, errors) {
  const file = path.resolve(repositoryRoot, baseline.document ?? '');
  if (!file.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(file)) {
    errors.push(`public contract document does not exist: ${String(baseline.document)}`);
    return;
  }
  const document = readFileSync(file, 'utf8');
  for (const token of [
    'OpenAPI 3.1',
    'JSON Schema 2020-12',
    'Idempotency-Key',
    'If-Match',
    'tenantId',
    'application/problem+json',
    'upcaster',
    'pnpm contracts:public:verify',
  ]) {
    if (!document.includes(token)) errors.push(`public contract document is missing token: ${token}`);
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const openapi = readJson(options.openapi);
  const baseline = readJson(options.baseline);
  const dslSchema = readJson(options.dslSchema);
  const eventSchema = readJson(options.eventSchema);
  const problemContract = readJson(path.join(repositoryRoot, 'config', 'api', 'problem-details.json'));
  const errors = [];
  validateReferences(openapi, dslSchema, errors);
  validateOpenApi(openapi, baseline, problemContract, errors);
  validateCompatibility(openapi, baseline, errors);
  validateDslShape(dslSchema, baseline, errors);
  validateEventShape(eventSchema, baseline, errors);
  const openApiSchemaCount = compileOpenApiSchemas(openapi, dslSchema, errors);
  const dslFixtures = validateFixtures(dslSchema, 'dsl', errors);
  const eventFixtures = validateFixtures(eventSchema, 'events', errors);
  validatePackageScripts(errors);
  if (!options.skipDocument) validateDocument(baseline, errors);
  if (errors.length > 0) throw new Error(`Public contract verification failed:\n- ${errors.join('\n- ')}`);
  console.log(
    `Public contract verification PASS: operations=${baseline.operations.length}, OpenAPI schemas=${openApiSchemaCount}, compatibilitySchemas=${Object.keys(baseline.schemas).length}, ` +
      `DSL nodes=${baseline.dsl.nodeTypes.length}, triggers=${baseline.dsl.triggerTypes.length}, ` +
      `DSL fixtures=${dslFixtures.valid}+${dslFixtures.invalid}, Event fields=${baseline.eventEnvelope.required.length}, ` +
      `Event fixtures=${eventFixtures.valid}+${eventFixtures.invalid}.`,
  );
}

main();
