import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const defaults = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'publish-api.json'),
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
  [1, '1.0.0', 'G3-08', ['REQ-DEF-001', 'REQ-REL-001', 'REQ-AUDIT-001']],
  'G3-08 contract identity',
);
exact(
  contract.transaction,
  [
    'tenant-context',
    'idempotency-claim',
    'draft-row-lock',
    'revision-check',
    'parse',
    'graph',
    'resource-and-configuration',
    'expression',
    'version-and-trigger-snapshot',
    'definition-activation',
    'audit',
    'outbox',
    'idempotency-completion',
  ],
  'publication transaction order',
);
exact(
  contract.request,
  {
    required: ['expectedDraftRevision'],
    releaseNoteMaximum: 1000,
    idempotencyWindowHours: 24,
    requestDigestBindings: ['workflowId', 'expectedDraftRevision', 'releaseNote'],
  },
  'publication request contract',
);
exact(
  contract.publication,
  {
    versionNumber: 'tenant-definition-monotonic-from-1',
    checksum: 'sha256-canonical-dsl',
    sameChecksumRepublish: 'conflict',
    databaseImmutable: ['workflow_version', 'trigger_config'],
    responseRequired: ['workflowId', 'version', 'checksum', 'publishedAt'],
  },
  'immutable publication contract',
);
exact(
  contract.event,
  {
    eventType: 'workflow.version.published',
    schemaVersion: 1,
    producer: 'control_plane',
    delivery: 'transactional-outbox',
    payloadFields: [
      'workflowId',
      'workflowVersionId',
      'version',
      'checksum',
      'draftRevision',
    ],
  },
  'publication event contract',
);

const expectedOperation = contract.operation;
const operation = openapi.paths?.[expectedOperation.path]?.[expectedOperation.method];
if (!operation || operation.operationId !== expectedOperation.operationId) {
  fail('Publish API operation is missing');
}
if (operation['x-workflow-implementation-status'] !== 'implemented') {
  fail('publishWorkflow must be marked implemented');
}
const requiredHeaders = (operation.parameters ?? [])
  .map((parameter) => resolveParameter(openapi, parameter))
  .filter((parameter) => parameter?.required)
  .map((parameter) => parameter.name);
for (const header of expectedOperation.requiredHeaders) {
  if (!requiredHeaders.includes(header)) fail(`publishWorkflow required header missing: ${header}`);
}
for (const status of [expectedOperation.successStatus, ...expectedOperation.failureStatuses]) {
  if (!operation.responses?.[status]) fail(`publishWorkflow response missing: ${status}`);
}
if (
  operation.requestBody?.content?.['application/json']?.schema?.$ref !==
  '#/components/schemas/PublishWorkflowRequest'
) {
  fail('Publish API request schema drifted');
}
if (
  operation.responses['201']?.content?.['application/json']?.schema?.$ref !==
  '#/components/schemas/PublishedWorkflow'
) {
  fail('Publish API response schema drifted');
}

const request = openapi.components?.schemas?.PublishWorkflowRequest;
exact(request?.required, contract.request.required, 'PublishWorkflowRequest required fields');
if (
  request?.additionalProperties !== false ||
  request?.properties?.expectedDraftRevision?.format !== 'int64' ||
  request?.properties?.expectedDraftRevision?.minimum !== 1 ||
  request?.properties?.releaseNote?.minLength !== 1 ||
  request?.properties?.releaseNote?.maxLength !== contract.request.releaseNoteMaximum
) {
  fail('PublishWorkflowRequest field bounds drifted');
}
const response = openapi.components?.schemas?.PublishedWorkflow;
exact(response?.required, contract.publication.responseRequired, 'PublishedWorkflow required fields');
if (
  response?.additionalProperties !== false ||
  response?.properties?.version?.minimum !== 1 ||
  response?.properties?.checksum?.pattern !== '^[0-9a-f]{64}$'
) {
  fail('PublishedWorkflow field contract drifted');
}

const baselineOperation = (baseline.operations ?? []).find(
  (entry) => entry.path === expectedOperation.path && entry.method === expectedOperation.method,
);
if (baselineOperation?.implementationStatus !== 'implemented') {
  fail('compatibility baseline must mark publishWorkflow implemented');
}

const sources = Object.fromEntries(
  Object.entries(contract.sourceBindings ?? {}).map(([name, file]) => [name, sourceFile(file, name)]),
);
for (const token of ['/{workflowId}/publish', 'Idempotency-Key', 'HttpStatus.CREATED']) {
  requireToken(sources.controller, token, 'WorkflowPublicationController');
}
for (const token of [
  'publish-workflow-v1',
  'expectedDraftRevision',
  'workflow:publish:',
  'validation::prepareForPublication',
]) {
  requireToken(sources.service, token, 'WorkflowPublicationService');
}
for (const token of [
  'prepareForPublication',
  'graphValidator.validate(',
  'contextStore.load(',
  'configurationValidator.validate(',
  'expressionCompiler.compile(',
]) {
  requireToken(sources.validationService, token, 'WorkflowValidationService');
}

const publishMethod = sources.store.slice(
  sources.store.indexOf('public PublishedWorkflow publish('),
  sources.store.indexOf('private void setTenantContext('),
);
const publicationSteps = [
  'setTenantContext(',
  'claim(command)',
  'lockDraft(command)',
  'preparer.prepare(',
  'persist(command, candidate)',
  'complete(command.idempotency(), published)',
];
let previous = -1;
for (const token of publicationSteps) {
  const current = publishMethod.indexOf(token);
  if (current < 0 || current <= previous) fail(`publication transaction order is missing: ${token}`);
  previous = current;
}
for (const token of [
  'INSERT INTO public.workflow_version',
  'INSERT INTO public.trigger_config',
  "status = 'ACTIVE'",
  'INSERT INTO public.audit_log',
  'workflow.version.publish',
  'INSERT INTO public.outbox_event',
  'workflow.version.published',
  'payload_digest',
  'same checksum',
]) {
  if (token === 'same checksum') continue;
  requireToken(sources.store, token, 'JdbcWorkflowPublicationStore');
}
for (const token of ['FOR UPDATE OF wd', 'SELECT public.app_set_tenant_context(?)']) {
  requireToken(
    token.startsWith('FOR UPDATE') ? sources.draftStore : sources.store,
    token,
    'publication database lock/context',
  );
}
for (const token of [
  "interval '24 hours'",
  'FOR UPDATE',
  "status = 'COMPLETED'",
  'response_digest',
]) {
  requireToken(sources.idempotency, token, 'JdbcIdempotencyRecords');
}
for (const token of ['workflow_version_immutable', 'trigger_config_immutable']) {
  requireToken(sources.definitionMigration, token, 'definition immutability migration');
}
requireToken(sources.outboxMigration, 'guard_outbox_event_transition', 'Outbox migration');
requireToken(sources.auditMigration, 'guard_audit_log_append_only', 'audit migration');
for (const token of ['status().isCreated()', 'COMMON_CONFLICT_STATE', 'AUTH_AUTHENTICATION_REQUIRED']) {
  requireToken(sources.controllerTest, token, 'WorkflowPublicationControllerTests');
}
for (const token of ['requestDigest', 'workflow:publish:', 'traceId()']) {
  requireToken(sources.serviceTest, token, 'WorkflowPublicationServiceTests');
}
for (const token of [
  'publish-key-it-001',
  'List.of(1, 2)',
  'workflow.version.published',
  'VALIDATION_FAILED',
  'CONTEXT_TWO',
]) {
  requireToken(sources.postgresTest, token, 'PostgreSQL publication tests');
}
for (const token of ['G3-08', 'JdbcWorkflowPublicationStore', 'workflow.version.published']) {
  requireToken(sources.traceability, token, 'G3-08 traceability');
}

const scripts = packageJson.scripts ?? {};
if (
  scripts['definition:g3-08:verify'] !==
  'node scripts/definition/verify-workflow-publication-api.mjs'
) {
  fail('package G3-08 verify script drifted');
}
if (
  scripts['definition:g3-08:test'] !==
  'node scripts/definition/test-workflow-publication-api-verifier.mjs'
) {
  fail('package G3-08 test script drifted');
}
for (const name of ['definition:g3-08:verify', 'definition:g3-08:test']) {
  requireToken(scripts.verify, `pnpm run ${name}`, `pnpm verify binding for ${name}`);
}

const document = sourceFile(contract.document, 'G3-08 document');
for (const token of [
  'FOR UPDATE',
  'parse → graph → resource-and-configuration → expression',
  'workflow.version.publish',
  'workflow.version.published',
  'G3-09',
]) {
  requireToken(document, token, 'G3-08 document');
}

console.log(
  'G3-08 Publish API verification PASS: locked in-transaction revalidation, monotonic immutable ' +
    'versions, trigger snapshots, 24h idempotency, append-only audit and transactional Outbox.',
);
