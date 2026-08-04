import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'definition', 'verify-workflow-draft-api.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'draft-api.json'),
  openapi: path.join(repositoryRoot, 'api', 'openapi', 'workflow-platform.v1.openapi.json'),
  problem: path.join(repositoryRoot, 'config', 'api', 'problem-details.json'),
  packageJson: path.join(repositoryRoot, 'package.json'),
};
const flags = {
  contract: '--contract',
  openapi: '--openapi',
  problem: '--problem',
  packageJson: '--package-json',
};

function run(overrides = {}) {
  const args = [verifier];
  for (const [name, file] of Object.entries(overrides)) args.push(flags[name], file);
  return spawnSync(process.execPath, args, { cwd: repositoryRoot, encoding: 'utf8' });
}

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g3-06-verifier-'));
let mutationCount = 0;

function expectFailure(name, target, mutate, expected) {
  mutationCount += 1;
  const value = JSON.parse(readFileSync(sources[target], 'utf8'));
  mutate(value);
  const file = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  const result = run({ [target]: file });
  if (result.status === 0 || !output(result).includes(expected)) {
    throw new Error(`${name} was not rejected with ${expected}:\n${output(result)}`);
  }
}

try {
  const positive = run();
  if (positive.status !== 0) throw new Error(`Positive G3-06 verifier failed:\n${output(positive)}`);

  expectFailure('weak-etag-policy', 'contract', (value) => { value.etag.strongOnly = false; }, 'strong ETag contract drift');
  expectFailure('tenant-header-enabled', 'contract', (value) => { value.tenantBoundary.clientTenantHeaderAllowed = true; }, 'tenant boundary contract drift');
  expectFailure('missing-etag-binding', 'contract', (value) => { value.etag.binds.pop(); }, 'strong ETag contract drift');
  expectFailure('idempotency-limit-drift', 'contract', (value) => { value.idempotency.keyTransport.maxLength = 200; }, 'idempotency contract drift');
  expectFailure('expired-key-policy-drift', 'contract', (value) => { value.idempotency.expiredKeyAction = 'overwrite-anytime'; }, 'idempotency contract drift');
  expectFailure('missing-shared-idempotency-binding', 'contract', (value) => { value.sourceBindings.idempotencyRecords = 'missing/JdbcIdempotencyRecords.java'; }, 'idempotencyRecords source binding does not exist');
  expectFailure('create-planned', 'openapi', (value) => { value.paths['/api/v1/workflows'].post['x-workflow-implementation-status'] = 'planned'; }, 'createWorkflow must be marked implemented');
  expectFailure('openapi-idempotency-limit-drift', 'openapi', (value) => { value.components.parameters.IdempotencyKey.schema.maxLength = 200; }, 'Idempotency-Key schema drift');
  expectFailure('if-match-optional', 'openapi', (value) => { value.components.parameters.IfMatch.required = false; }, 'required header missing: If-Match');
  expectFailure('missing-412', 'openapi', (value) => { delete value.paths['/api/v1/workflows/{workflowId}/draft'].put.responses['412']; }, 'response 412 must use ProblemResponse');
  expectFailure('wrong-428-code-status', 'problem', (value) => { value.catalog.find((entry) => entry.code === 'DEFINITION_PRECONDITION_REQUIRED_IF_MATCH').status = 400; }, 'missing If-Match problem status must be 428');
  expectFailure('missing-problem-code', 'problem', (value) => { value.catalog = value.catalog.filter((entry) => entry.code !== 'DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH'); }, 'etagMismatch problem code is missing');
  expectFailure('package-script-drift', 'packageJson', (value) => { value.scripts['definition:g3-06:verify'] = 'node missing.mjs'; }, 'package G3-06 verify script drifted');

  console.log(`G3-06 verifier negative tests PASS: ${mutationCount}/${mutationCount} mutations rejected.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
