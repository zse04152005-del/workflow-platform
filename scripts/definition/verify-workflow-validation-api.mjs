import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const defaults = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'validate-api.json'),
  openapi: path.join(repositoryRoot, 'api', 'openapi', 'workflow-platform.v1.openapi.json'),
  baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
  packageJson: path.join(repositoryRoot, 'package.json'),
};
const argumentNames = new Map([
  ['--contract', 'contract'],
  ['--openapi', 'openapi'],
  ['--baseline', 'baseline'],
  ['--package-json', 'packageJson'],
]);

function parseArguments(argv) {
  const options = { ...defaults };
  for (let index = 0; index < argv.length; index += 1) {
    const key = argumentNames.get(argv[index]);
    if (!key || !argv[index + 1]) throw new Error(`Invalid argument: ${String(argv[index])}`);
    options[key] = path.resolve(argv[index + 1]);
    index += 1;
  }
  return options;
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

function sourceFile(relative, label) {
  const file = path.resolve(repositoryRoot, relative ?? '');
  if (!file.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(file)) {
    fail(`${label} source binding does not exist: ${String(relative)}`);
  }
  return readFileSync(file, 'utf8');
}

function resolveParameter(openapi, parameter) {
  if (!parameter?.$ref) return parameter;
  return openapi.components?.parameters?.[parameter.$ref.split('/').at(-1)];
}

const options = parseArguments(process.argv.slice(2));
const contract = JSON.parse(readFileSync(options.contract, 'utf8'));
const openapi = JSON.parse(readFileSync(options.openapi, 'utf8'));
const baseline = JSON.parse(readFileSync(options.baseline, 'utf8'));
const packageJson = JSON.parse(readFileSync(options.packageJson, 'utf8'));

exact(
  [contract.schemaVersion, contract.contractVersion, contract.task, contract.requirements],
  [1, '1.0.0', 'G3-07', ['REQ-DSL-001', 'REQ-DEF-001']],
  'G3-07 contract identity',
);
exact(
  contract.pipeline,
  ['parse', 'graph', 'resource-and-configuration', 'expression'],
  'validation pipeline order',
);
exact(
  contract.requestModes,
  {
    emptyObject: 'current_draft',
    definitionProperty: 'supplied_definition',
    suppliedNameMustMatchTarget: true,
  },
  'request modes',
);
exact(
  contract.result,
  {
    required: ['source', 'valid', 'issueCount', 'truncated', 'issues'],
    currentDraftBinding: ['draftRevision', 'checksum'],
    suppliedDefinitionBinding: ['checksum'],
    maximumPublicIssues: 500,
    severity: ['error'],
    nodeIdWhenPathResolves: true,
    invalidDefinitionStatus: 200,
    publishCredential: false,
  },
  'validation result contract',
);
if (contract.issueCodes?.length !== 40 || new Set(contract.issueCodes).size !== 40) {
  fail('public issue catalog must contain exactly 40 unique codes');
}

const expectedOperation = contract.operation;
const operation = openapi.paths?.[expectedOperation.path]?.[expectedOperation.method];
if (!operation || operation.operationId !== expectedOperation.operationId) {
  fail('Validate API operation is missing');
}
if (operation['x-workflow-implementation-status'] !== 'implemented') {
  fail('validateWorkflow must be marked implemented');
}
const requiredHeaders = (operation.parameters ?? [])
  .map((parameter) => resolveParameter(openapi, parameter))
  .filter((parameter) => parameter?.required)
  .map((parameter) => parameter.name);
for (const header of expectedOperation.requiredHeaders) {
  if (!requiredHeaders.includes(header)) fail(`validateWorkflow required header missing: ${header}`);
}
for (const status of [expectedOperation.successStatus, expectedOperation.notFoundStatus, 'default']) {
  if (!operation.responses?.[status]) fail(`validateWorkflow response missing: ${status}`);
}
if (operation.requestBody?.content?.['application/json']?.schema?.$ref !== '#/components/schemas/ValidateWorkflowRequest') {
  fail('Validate API request schema drifted');
}
if (operation.responses['200']?.content?.['application/json']?.schema?.$ref !== '#/components/schemas/ValidationResult') {
  fail('Validate API response schema drifted');
}
if (!openapi.paths?.['/api/v1/workflows/{workflowId}/publish']?.post) {
  fail('G3-08 Publish API operation is missing');
}

const request = openapi.components?.schemas?.ValidateWorkflowRequest;
exact(request?.required, undefined, 'optional supplied definition');
if (request?.additionalProperties !== false || request?.properties?.definition?.$ref !== '#/components/schemas/WorkflowDefinition') {
  fail('ValidateWorkflowRequest schema drifted');
}
const issue = openapi.components?.schemas?.ValidationIssue;
exact(issue?.required, ['code', 'path', 'message', 'severity'], 'ValidationIssue required fields');
exact(issue?.properties?.code?.enum, contract.issueCodes, 'public issue codes');
exact(issue?.properties?.severity?.enum, ['error'], 'public issue severity');
if (issue?.properties?.path?.maxLength !== 256 || issue?.properties?.nodeId?.maxLength !== 80) {
  fail('ValidationIssue field bounds drifted');
}
const result = openapi.components?.schemas?.ValidationResult;
exact(result?.required, contract.result.required, 'ValidationResult required fields');
exact(result?.properties?.source?.enum, ['current_draft', 'supplied_definition'], 'result sources');
if (result?.properties?.issues?.maxItems !== contract.result.maximumPublicIssues) {
  fail('ValidationResult issue cap drifted');
}

const baselineOperation = (baseline.operations ?? []).find(
  (entry) => entry.path === expectedOperation.path && entry.method === expectedOperation.method,
);
if (baselineOperation?.implementationStatus !== 'implemented') {
  fail('compatibility baseline must mark validateWorkflow implemented');
}

exact(
  contract.resourceContext.selectedMetadata,
  ['id', 'tenant_id', 'project_id', 'normalized_name', 'status', 'deleted_at'],
  'resource metadata allowlist',
);
exact(
  contract.resourceContext.forbiddenSelection,
  ['endpoint', 'config', 'ciphertext', 'external_key'],
  'resource secret denylist',
);
exact(contract.resourceContext.permissions, ['connector:use', 'secret:use'], 'resource permissions');
exact(contract.resourceContext.grantShape, ['kind', 'resourceId'], 'resource grant shape');

const sources = Object.fromEntries(
  Object.entries(contract.sourceBindings ?? {}).map(([name, file]) => [name, sourceFile(file, name)]),
);
for (const token of ['/{workflowId}/validate', 'Idempotency-Key', 'ValidateWorkflowRequest']) {
  requireToken(sources.controller, token, 'WorkflowValidationController');
}
const validationMethod = sources.service.slice(
  sources.service.indexOf('public WorkflowValidationResult validate('),
  sources.service.indexOf('private ParsedWorkflowDefinition parse('),
);
const stageTokens = [
  'parsed = parse(',
  'graphValidator.validate(',
  'contextStore.load(',
  'configurationValidator.validate(',
  'expressionCompiler.compile(',
];
let previous = -1;
for (const token of stageTokens) {
  const current = validationMethod.indexOf(token);
  if (current < 0 || current <= previous) fail(`service pipeline order is missing: ${token}`);
  previous = current;
}
for (const token of [
  'MAXIMUM_PUBLIC_ISSUES = 500',
  'current_draft',
  'supplied_definition',
  'Persisted workflow draft is invalid',
  'requireValidIdempotencyKey',
]) {
  requireToken(sources.service, token, 'WorkflowValidationService');
}
for (const token of ['DSL_PARSE_', 'DSL_GRAPH_', 'DSL_CONFIGURATION_', 'DSL_EXPRESSION_', 'nodeId(']) {
  requireToken(sources.issueMapper, token, 'WorkflowValidationIssueMapper');
}
for (const token of [
  'SELECT public.app_set_tenant_context(?)',
  'RESOURCE_COLUMNS',
  'principal_subject',
  "m.status = 'ACTIVE'",
  "rb.status = 'ACTIVE'",
  "r.status = 'ACTIVE'",
  "scope_type = 'TENANT'",
  "scope_type = 'PROJECT'",
  'connector:use',
  'secret:use',
  'new ResourceUseGrant(resource.kind(), resource.id())',
]) {
  requireToken(sources.contextStore, token, 'JdbcWorkflowValidationContextStore');
}
for (const forbidden of contract.resourceContext.forbiddenSelection) {
  if (sources.contextStore.includes(forbidden)) {
    fail(`JdbcWorkflowValidationContextStore selects forbidden material: ${forbidden}`);
  }
}
for (const token of ['501', 'truncated()', 'DSL_PARSE_ROOT_NOT_OBJECT', 'verifyNoInteractions']) {
  requireToken(sources.serviceTest, token, 'WorkflowValidationServiceTests');
}
for (const token of ['status().isOk()', 'draftRevision', 'AUTH_AUTHENTICATION_REQUIRED']) {
  requireToken(sources.controllerTest, token, 'WorkflowValidationControllerTests');
}
for (const token of ['connectors.primary', 'secrets.signing', 'REVOKED_CONTEXT', 'useGrants()', 'CONTEXT_TWO']) {
  requireToken(sources.postgresTest, token, 'PostgreSQL validation context tests');
}
for (const token of ['G3-07', 'WorkflowValidationService', 'validate-api.json']) {
  requireToken(sources.traceability, token, 'G3-07 traceability');
}

const scripts = packageJson.scripts ?? {};
if (scripts['definition:g3-07:verify'] !== 'node scripts/definition/verify-workflow-validation-api.mjs') {
  fail('package G3-07 verify script drifted');
}
if (scripts['definition:g3-07:test'] !== 'node scripts/definition/test-workflow-validation-api-verifier.mjs') {
  fail('package G3-07 test script drifted');
}
for (const name of ['definition:g3-07:verify', 'definition:g3-07:test']) {
  requireToken(scripts.verify, `pnpm run ${name}`, `pnpm verify binding for ${name}`);
}

const document = sourceFile(contract.document, 'G3-07 document');
for (const token of ['current_draft', 'supplied_definition', '500', 'connector:use', 'secret:use', 'G3-08']) {
  requireToken(document, token, 'G3-07 document');
}

console.log(
  'G3-07 Validate API verification PASS: 4-stage short circuit, 40 stable issue codes, ' +
    '500 issue cap, revision/checksum binding, secret-free RBAC/RLS context.',
);
