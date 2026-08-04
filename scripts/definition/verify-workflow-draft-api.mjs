import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const defaults = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'draft-api.json'),
  openapi: path.join(repositoryRoot, 'api', 'openapi', 'workflow-platform.v1.openapi.json'),
  problem: path.join(repositoryRoot, 'config', 'api', 'problem-details.json'),
  packageJson: path.join(repositoryRoot, 'package.json'),
};
const argumentNames = new Map([
  ['--contract', 'contract'],
  ['--openapi', 'openapi'],
  ['--problem', 'problem'],
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
  const name = parameter.$ref.split('/').at(-1);
  return openapi.components?.parameters?.[name];
}

const options = parseArguments(process.argv.slice(2));
const contract = JSON.parse(readFileSync(options.contract, 'utf8'));
const openapi = JSON.parse(readFileSync(options.openapi, 'utf8'));
const problem = JSON.parse(readFileSync(options.problem, 'utf8'));
const packageJson = JSON.parse(readFileSync(options.packageJson, 'utf8'));

exact(
  [contract.schemaVersion, contract.contractVersion, contract.task, contract.requirement],
  [1, '1.0.0', 'G3-06', 'REQ-DEF-001'],
  'G3-06 contract identity',
);
exact(
  contract.etag,
  {
    prefix: 'wf-draft:',
    strongOnly: true,
    binds: ['workflowId', 'draftRevision', 'draftChecksum'],
    reject: ['missing', 'weak', 'wildcard', 'list', 'malformed', 'stale', 'other-workflow'],
    missingStatus: 428,
    mismatchStatus: 412,
  },
  'strong ETag contract',
);
exact(
  contract.tenantBoundary,
  {
    source: 'trusted-request-attribute',
    clientTenantHeaderAllowed: false,
    databaseContextFunction: 'public.app_set_tenant_context',
    forcedRlsRequired: true,
    crossTenantReadStatus: 404,
  },
  'tenant boundary contract',
);
exact(
  contract.idempotency,
  {
    table: 'idempotency_record',
    scope: ['workflow:create', 'workflow:draft:update:{workflowId}'],
    keyTransport: { minLength: 8, maxLength: 128, pattern: '^[A-Za-z0-9._~-]+$' },
    keyStoredAs: 'sha256',
    requestStoredAs: 'sha256',
    ttlHours: 24,
    expiredKeyAction: 'reset-same-row-after-expiry-under-lock',
    sameTransactionAsWrite: true,
    differentRequestStatus: 409,
  },
  'idempotency contract',
);

const idempotencyParameter = openapi.components?.parameters?.IdempotencyKey;
if (idempotencyParameter?.name !== 'Idempotency-Key' || idempotencyParameter?.required !== true) {
  fail('OpenAPI Idempotency-Key parameter must be required');
}
exact(idempotencyParameter.schema, { type: 'string', ...contract.idempotency.keyTransport }, 'Idempotency-Key schema');

for (const expected of contract.operations ?? []) {
  const operation = openapi.paths?.[expected.path]?.[expected.method];
  if (!operation || operation.operationId !== expected.operationId) {
    fail(`implemented operation missing: ${expected.method.toUpperCase()} ${expected.path}`);
  }
  if (operation['x-workflow-implementation-status'] !== 'implemented') {
    fail(`${expected.operationId} must be marked implemented`);
  }
  if (!operation.responses?.[expected.successStatus]) {
    fail(`${expected.operationId} success response is missing`);
  }
  const headers = (operation.parameters ?? [])
    .map((parameter) => resolveParameter(openapi, parameter))
    .filter((parameter) => parameter?.required)
    .map((parameter) => parameter.name);
  for (const header of expected.headers) {
    if (!headers.includes(header)) fail(`${expected.operationId} required header missing: ${header}`);
  }
  if (!operation.responses[expected.successStatus].headers?.ETag) {
    fail(`${expected.operationId} success ETag header missing`);
  }
}
const update = openapi.paths['/api/v1/workflows/{workflowId}/draft'].put;
for (const status of ['412', '428']) {
  if (update.responses?.[status]?.$ref !== '#/components/responses/ProblemResponse') {
    fail(`updateWorkflowDraft response ${status} must use ProblemResponse`);
  }
}

const problemByCode = new Map((problem.catalog ?? []).map((entry) => [entry.code, entry]));
for (const [name, code] of Object.entries(contract.problemCodes ?? {})) {
  if (!problemByCode.has(code)) fail(`${name} problem code is missing: ${code}`);
}
if (problemByCode.get(contract.problemCodes.missingIfMatch)?.status !== 428) {
  fail('missing If-Match problem status must be 428');
}
if (problemByCode.get(contract.problemCodes.etagMismatch)?.status !== 412) {
  fail('ETag mismatch problem status must be 412');
}

const sources = Object.fromEntries(
  Object.entries(contract.sourceBindings ?? {}).map(([name, file]) => [name, sourceFile(file, name)]),
);
for (const token of ['wf-draft:', 'workflowId()', 'revision()', 'checksum()', 'parseIfMatch']) {
  requireToken(sources.etag, token, 'StrongDraftEtag');
}
requireToken(sources.service, '^[A-Za-z0-9._~-]{8,128}$', 'WorkflowDraftService');
for (const token of ['W/', '"*"', 'other-workflow']) {
  if (sources.etag.includes(token)) fail(`StrongDraftEtag must not accept ${token}`);
}
for (const token of [
  'SELECT public.app_set_tenant_context(?)',
  'new JdbcIdempotencyRecords',
  'idempotencyRecords.claim',
  'idempotencyRecords.complete',
  'UNIQUE_VIOLATION_SQL_STATE = "23505"',
  'draft_revision = draft_revision + 1',
  'WHERE id = ? AND draft_revision = ?',
  'AND draft_checksum = ?',
  'version = version + 1',
]) {
  requireToken(sources.store, token, 'JdbcWorkflowDraftStore');
}
for (const token of [
  'ON CONFLICT (tenant_id, operation_scope, key_digest) DO NOTHING',
  "status = 'COMPLETED'",
  "interval '24 hours'",
  "status = 'PROCESSING', response_status = NULL",
  'expires_at <= transaction_timestamp() AS expired',
  'version = version + 1',
]) {
  requireToken(sources.idempotencyRecords, token, 'JdbcIdempotencyRecords');
}
for (const token of [
  'STRICT_DUPLICATE_DETECTION',
  '.maxNestingDepth(MAXIMUM_NESTING_DEPTH)',
  '.maxDocumentLength(MAXIMUM_HTTP_DOCUMENT_CHARACTERS)',
  '.maxTokenCount(MAXIMUM_TOKEN_COUNT)',
]) {
  requireToken(sources.jsonConfiguration, token, 'WorkflowDraftJacksonConfiguration');
}
requireToken(sources.requestContext, 'REQUEST_ATTRIBUTE', 'trusted request context');
requireToken(sources.resolver, 'getAttribute(WorkflowRequestContext.REQUEST_ATTRIBUTE)', 'context resolver');
if (/X-Tenant-Id|Tenant-Id/iu.test(`${sources.controller}\n${sources.resolver}`)) {
  fail('client tenant header must not be accepted by production HTTP sources');
}
for (const token of ['newFixedThreadPool(2)', 'PRECONDITION_FAILED', 'duplicateName', 'CONTEXT_TWO', 'seedsExpiredCompletedIdempotencyRecord', 'migrationsExecuted']) {
  requireToken(sources.integrationTest, token, 'JdbcWorkflowDraftStoreIT');
}

const migration = sourceFile(
  `control-plane/src/main/resources/db/migration/${contract.persistence.migration}`,
  'G3-06 migration',
);
requireToken(migration, 'ALTER TABLE public.workflow_definition', 'G3-06 migration');
requireToken(migration, 'ADD COLUMN description varchar(1000)', 'G3-06 migration');
requireToken(migration, 'OLD.expires_at <= transaction_timestamp()', 'G3-06 migration');
requireToken(migration, "NEW.status = 'PROCESSING'", 'G3-06 migration');
requireToken(migration, 'NEW.version <> OLD.version + 1', 'G3-06 migration');

const scripts = packageJson.scripts ?? {};
if (scripts['definition:g3-06:verify'] !== 'node scripts/definition/verify-workflow-draft-api.mjs') {
  fail('package G3-06 verify script drifted');
}
if (scripts['definition:g3-06:test'] !== 'node scripts/definition/test-workflow-draft-api-verifier.mjs') {
  fail('package G3-06 test script drifted');
}
for (const name of ['definition:g3-06:verify', 'definition:g3-06:test']) {
  requireToken(scripts.verify, `pnpm run ${name}`, `pnpm verify binding for ${name}`);
}

const document = sourceFile(contract.document, 'G3-06 document');
for (const token of ['ETag', 'If-Match', 'Idempotency-Key', '412', '428', 'RLS', 'pnpm definition:g3-06:verify']) {
  requireToken(document, token, 'G3-06 document');
}

console.log(
  `G3-06 workflow draft API verification PASS: operations=${contract.operations.length}, strong ETag=412/428, ` +
    'idempotent writes=24h, tenant context=trusted attribute + forced RLS, PostgreSQL concurrency test bound.',
);
