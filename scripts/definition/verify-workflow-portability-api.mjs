import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const defaults = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'portability-api.json'),
  openapi: path.join(repositoryRoot, 'api', 'openapi', 'workflow-platform.v1.openapi.json'),
  baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
  packageJson: path.join(repositoryRoot, 'package.json'),
  traceability: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
};
const argumentNames = new Map([
  ['--contract', 'contract'],
  ['--openapi', 'openapi'],
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

const input = options(process.argv.slice(2));
const contract = JSON.parse(readFileSync(input.contract, 'utf8'));
const openapi = JSON.parse(readFileSync(input.openapi, 'utf8'));
const baseline = JSON.parse(readFileSync(input.baseline, 'utf8'));
const packageJson = JSON.parse(readFileSync(input.packageJson, 'utf8'));
const traceability = JSON.parse(readFileSync(input.traceability, 'utf8'));

exact(
  [contract.schemaVersion, contract.contractVersion, contract.task, contract.requirement],
  [1, '1.0.0', 'G3-09', 'REQ-DEF-001'],
  'G3-09 contract identity',
);
exact(contract.bundle, {
  format: 'workflow-platform-definition',
  formatVersion: 1,
  sourceKinds: ['draft', 'published_version'],
  checksum: 'sha256-canonical-dsl',
  maximumResourceReferences: 10000,
  resourceFields: ['kind', 'reference'],
  forbiddenFields: ['tenantId', 'projectId', 'endpoint', 'config', 'ciphertext', 'externalKey', 'keyVersion', 'secretMaterial'],
}, 'portable bundle contract');
exact(contract.resourceMapping, {
  knownDslFields: ['hmacSecretRef', 'connectorRef', 'secretRefs'],
  importPolicy: 'complete-explicit-mapping',
  sameProjectClonePolicy: 'identity-with-optional-overrides',
  crossProjectClonePolicy: 'complete-explicit-mapping',
  unknownOrDuplicateMapping: 'reject',
}, 'resource mapping policy');
exact(contract.diff, {
  sources: 'published-versions-only',
  pathFormat: 'rfc6901-json-pointer',
  ordering: 'object-key-lexical-array-index',
  operations: ['add', 'remove', 'replace'],
  maximumPublicChanges: 1000,
  maximumValuePreviewBytes: 4096,
  oversizedPreview: ['type', 'sha256', 'truncated'],
}, 'version diff contract');
if (contract.security?.secretMaterialAllowed !== false
  || contract.security?.forcedRlsRequired !== true
  || contract.security?.publishRevalidationStillRequired !== true
  || contract.security?.maximumHttpDocumentCharacters !== 2200000) {
  fail('portability security boundary drifted');
}

const baselineById = new Map((baseline.operations ?? []).map((entry) => [entry.operationId, entry]));
for (const expected of contract.operations ?? []) {
  const operation = openapi.paths?.[expected.path]?.[expected.method];
  if (!operation || operation.operationId !== expected.operationId) {
    fail(`implemented portability operation missing: ${expected.operationId}`);
  }
  if (operation['x-workflow-implementation-status'] !== 'implemented') {
    fail(`${expected.operationId} must be marked implemented`);
  }
  if (!operation.responses?.[expected.successStatus]) {
    fail(`${expected.operationId} success response is missing`);
  }
  const headers = (operation.parameters ?? [])
    .map((parameter) => resolveParameter(openapi, parameter))
    .filter((parameter) => parameter?.required && parameter.in === 'header')
    .map((parameter) => parameter.name);
  exact(headers, expected.requiredHeaders, `${expected.operationId} required headers`);
  if (baselineById.get(expected.operationId)?.implementationStatus !== 'implemented') {
    fail(`${expected.operationId} compatibility baseline must be implemented`);
  }
}

const clone = openapi.components?.schemas?.CloneWorkflowRequest;
const imported = openapi.components?.schemas?.ImportWorkflowRequest;
const bundle = openapi.components?.schemas?.WorkflowExportBundle;
const source = openapi.components?.schemas?.WorkflowExportSource;
const mapping = openapi.components?.schemas?.WorkflowResourceMapping;
const diff = openapi.components?.schemas?.WorkflowVersionDiff;
exact(clone?.required, ['targetProjectId', 'name'], 'CloneWorkflowRequest.required');
exact(imported?.required, ['targetProjectId', 'name', 'bundle', 'resourceMappings'], 'ImportWorkflowRequest.required');
exact(bundle?.required, ['format', 'formatVersion', 'source', 'workflow', 'resources'], 'WorkflowExportBundle.required');
if (bundle?.properties?.format?.const !== contract.bundle.format
  || bundle?.properties?.formatVersion?.const !== contract.bundle.formatVersion
  || bundle?.properties?.resources?.maxItems !== contract.bundle.maximumResourceReferences) {
  fail('OpenAPI portable bundle limits drifted');
}
exact(source?.properties?.kind?.enum, contract.bundle.sourceKinds, 'WorkflowExportSource.kind');
for (const forbidden of contract.bundle.forbiddenFields) {
  if (source?.properties?.[forbidden] || bundle?.properties?.[forbidden]) {
    fail(`portable bundle exposes forbidden field: ${forbidden}`);
  }
}
if (mapping?.additionalProperties !== false || mapping?.required?.length !== 3) {
  fail('WorkflowResourceMapping must be closed and require all fields');
}
if (diff?.properties?.changes?.maxItems !== contract.diff.maximumPublicChanges) {
  fail('WorkflowVersionDiff changes cap drifted');
}
const exportParameters = openapi.paths['/api/v1/workflows/{workflowId}/export'].get.parameters;
if (!exportParameters.some((parameter) => parameter.name === 'version' && parameter.required === false)) {
  fail('export optional version selector is missing');
}
const diffParameters = openapi.paths['/api/v1/workflows/{workflowId}/versions/diff'].get.parameters;
for (const name of ['fromVersion', 'toVersion']) {
  if (!diffParameters.some((parameter) => parameter.name === name && parameter.required === true)) {
    fail(`diff required query parameter is missing: ${name}`);
  }
}

const sources = Object.fromEntries(
  Object.entries(contract.sourceBindings ?? {}).map(([name, file]) => [name, sourceFile(file, name)]),
);
for (const token of ['/{workflowId}/clone', '/{workflowId}/export', '/import', '/{workflowId}/versions/diff', 'Idempotency-Key', 'CacheControl.noStore']) {
  requireToken(sources.controller, token, 'WorkflowPortabilityController');
}
for (const token of ['store.load', 'source.checksum().equals', 'WorkflowResourceReferences.requireManifest', 'crossProject', 'workflow:clone:', 'workflow:import', 'workflow.definition.clone', 'workflow.definition.import']) {
  requireToken(sources.service, token, 'WorkflowPortabilityService');
}
for (const token of [...contract.resourceMapping.knownDslFields, 'requireComplete', 'putIfAbsent']) {
  requireToken(sources.resourceMapper, token, 'WorkflowResourceReferences');
}
for (const token of ['MAXIMUM_PUBLIC_CHANGES = 1000', 'MAXIMUM_PREVIEW_BYTES = 4096', 'new TreeSet', 'replace("~", "~0")', 'sha256']) {
  requireToken(sources.jsonDiff, token, 'WorkflowJsonDiff');
}
for (const token of ['SELECT public.app_set_tenant_context(?)', 'FROM public.workflow_version', 'FROM public.workflow_definition', 'JdbcIdempotencyRecords', 'public.audit_log', 'command.auditAction()']) {
  requireToken(sources.store, token, 'JdbcWorkflowDraftStore');
}
for (const forbidden of ['ciphertext', 'external_key', 'key_version', ' endpoint']) {
  if (sources.store.includes(forbidden)) fail(`portability store must not read secret/config material: ${forbidden}`);
}
requireToken(sources.httpBounds, 'MAXIMUM_HTTP_DOCUMENT_CHARACTERS = 2_200_000', 'HTTP bundle bound');
for (const name of ['serviceTest', 'controllerTest', 'postgresTest']) {
  requireToken(sources[name], 'G3-09', `${name} marker`);
}
const requirement = (traceability.requirements ?? []).find((entry) => entry.id === contract.requirement);
if (!requirement || !(requirement.api ?? []).some((entry) => entry.includes('implemented:') && entry.includes('[G3-09]'))) {
  fail('REQ-DEF-001 must trace G3-09 implemented artifacts');
}

const scripts = packageJson.scripts ?? {};
if (scripts['definition:g3-09:verify'] !== 'node scripts/definition/verify-workflow-portability-api.mjs') {
  fail('package G3-09 verify script drifted');
}
if (scripts['definition:g3-09:test'] !== 'node scripts/definition/test-workflow-portability-api-verifier.mjs') {
  fail('package G3-09 test script drifted');
}
for (const name of ['definition:g3-09:verify', 'definition:g3-09:test']) {
  requireToken(scripts.verify, `pnpm run ${name}`, `pnpm verify binding for ${name}`);
}
const document = sourceFile(contract.document, 'G3-09 document');
for (const token of ['Idempotency-Key', 'canonical SHA-256', 'Secret', '10,000', '1000', '4096', 'FORCE RLS', 'G3-10']) {
  requireToken(document, token, 'G3-09 document');
}

console.log('G3-09 portability verification PASS: 4 implemented operations, integrity-bound Secret-free bundle, explicit resource mapping, atomic audited idempotency and bounded deterministic version diff.');
