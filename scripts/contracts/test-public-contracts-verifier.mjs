import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'contracts', 'verify-public-contracts.mjs');
const sources = {
  openapi: path.join(repositoryRoot, 'api', 'openapi', 'workflow-platform.v1.openapi.json'),
  baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
  dslSchema: path.join(repositoryRoot, 'schemas', 'dsl', 'workflow-definition-v1.schema.json'),
  eventSchema: path.join(repositoryRoot, 'schemas', 'events', 'event-envelope-v1.schema.json'),
};
const flags = {
  openapi: '--openapi',
  baseline: '--baseline',
  dslSchema: '--dsl-schema',
  eventSchema: '--event-schema',
};
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-public-contract-test-'));
let mutationCount = 0;

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function run(overrides = {}, full = false) {
  const args = [verifier];
  for (const [kind, file] of Object.entries(overrides)) args.push(flags[kind], file);
  if (!full) args.push('--skip-document');
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 20 * 1024 * 1024,
    windowsHide: true,
  });
}

function writeCase(name, kind, mutate) {
  const contract = JSON.parse(readFileSync(sources[kind], 'utf8'));
  mutate(contract);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(contract, null, 2)}\n`, 'utf8');
  return target;
}

function expectFailure(name, kind, mutate, expectedMessage) {
  mutationCount += 1;
  const result = run({ [kind]: writeCase(name, kind, mutate) });
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expectedMessage)) {
    throw new Error(`${name} was not blocked as expected (${expectedMessage}):\n${combined}`);
  }
}

try {
  const positive = run({}, true);
  if (positive.status !== 0) throw new Error(`Positive public contracts failed:\n${output(positive)}`);

  expectFailure('removed-operation', 'openapi', (api) => {
    delete api.paths['/api/v1/workflows/{workflowId}'].get;
  }, 'required operation missing');
  expectFailure('duplicate-operation-id', 'openapi', (api) => {
    api.paths['/api/v1/workflows/{workflowId}'].get.operationId = 'listWorkflows';
  }, 'duplicate operationId');
  expectFailure('implementation-status-drift', 'openapi', (api) => {
    api.paths['/api/v1/workflows'].post['x-workflow-implementation-status'] = 'planned';
  }, 'implementation status must remain truthful: implemented');
  expectFailure('write-without-idempotency', 'openapi', (api) => {
    api.components.parameters.IdempotencyKey.required = false;
  }, 'write operation requires Idempotency-Key');
  expectFailure('draft-without-if-match', 'openapi', (api) => {
    api.components.parameters.IfMatch.required = false;
  }, 'draft update requires If-Match');
  expectFailure('wrong-error-response', 'openapi', (api) => {
    api.paths['/api/v1/workflows'].post.responses.default.$ref = '#/components/responses/Unknown';
  }, 'error response must reference ProblemResponse');
  expectFailure('client-tenant-id', 'openapi', (api) => {
    api.components.schemas.CreateWorkflowRequest.properties.tenantId = { type: 'string' };
  }, 'request schema must not contain client tenantId');
  expectFailure('removed-response-property', 'openapi', (api) => {
    delete api.components.schemas.WorkflowDetail.properties.name;
  }, 'compatibility property removed');
  expectFailure('new-required-request-field', 'openapi', (api) => {
    api.components.schemas.CreateWorkflowRequest.required.push('description');
  }, 'new required request field');
  expectFailure('changed-field-type', 'openapi', (api) => {
    api.components.schemas.WorkflowDetail.properties.name.type = 'integer';
  }, 'compatibility type changed');
  expectFailure('removed-enum-value', 'openapi', (api) => {
    api.components.schemas.InstanceSummary.properties.status.enum.pop();
  }, 'enum value removed');
  expectFailure('broken-ref', 'openapi', (api) => {
    api.paths['/api/v1/workflows'].get.responses['200'].content['application/json'].schema.$ref = '#/components/schemas/Missing';
  }, 'broken internal $ref');
  expectFailure('missing-oidc', 'openapi', (api) => {
    api.components.securitySchemes.oidc.type = 'apiKey';
  }, 'must define an HTTPS OIDC security scheme');

  expectFailure('dsl-version-changed', 'dslSchema', (schema) => {
    schema.properties.schemaVersion.const = 2;
  }, 'DSL schemaVersion v1 changed');
  expectFailure('dsl-required-field-removed', 'dslSchema', (schema) => {
    schema.required.pop();
  }, 'DSL required fields must equal');
  expectFailure('dsl-node-type-removed', 'dslSchema', (schema) => {
    schema.$defs.node.oneOf.pop();
  }, 'DSL node types must equal');
  expectFailure('dsl-valid-fixture-rejected', 'dslSchema', (schema) => {
    schema.properties.nodes.maxItems = 1;
  }, 'dsl valid fixture rejected');
  expectFailure('dsl-invalid-fixture-accepted', 'dslSchema', (schema) => {
    schema.$defs.safeExpression.pattern = '^[\\s\\S]+$';
  }, 'dsl invalid fixture accepted');

  expectFailure('event-required-field-removed', 'eventSchema', (schema) => {
    schema.required = schema.required.filter((field) => field !== 'tenantId');
  }, 'Event Envelope required fields must equal');
  expectFailure('event-closed-to-unknown', 'eventSchema', (schema) => {
    schema.additionalProperties = false;
  }, 'must allow unknown optional fields');
  expectFailure('event-version-changed', 'eventSchema', (schema) => {
    schema.properties.schemaVersion.const = 2;
  }, 'Event Envelope schemaVersion v1 changed');
  expectFailure('event-invalid-fixture-accepted', 'eventSchema', (schema) => {
    schema.$defs.traceId.pattern = '^[0-9a-f]{32}$';
  }, 'events invalid fixture accepted');

  expectFailure('baseline-major-prefix', 'baseline', (baseline) => {
    baseline.policy.api.majorPathPrefix = '/api/latest';
  }, 'API compatibility prefix must be /api/v1');

  console.log(`Public contract verifier tests PASS: positive contract plus ${mutationCount} reverse mutations.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
