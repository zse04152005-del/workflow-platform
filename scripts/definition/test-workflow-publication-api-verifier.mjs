import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'definition', 'verify-workflow-publication-api.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'publish-api.json'),
  openapi: path.join(repositoryRoot, 'api', 'openapi', 'workflow-platform.v1.openapi.json'),
  baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
  packageJson: path.join(repositoryRoot, 'package.json'),
};
const flags = {
  contract: '--contract',
  openapi: '--openapi',
  baseline: '--baseline',
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

const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g3-08-verifier-'));
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
  if (positive.status !== 0) throw new Error(`Positive G3-08 verifier failed:\n${output(positive)}`);

  expectFailure('transaction-reordered', 'contract', (value) => { value.transaction.reverse(); }, 'publication transaction order drift');
  expectFailure('idempotency-window-expanded', 'contract', (value) => { value.request.idempotencyWindowHours = 48; }, 'publication request contract drift');
  expectFailure('checksum-republish-allowed', 'contract', (value) => { value.publication.sameChecksumRepublish = 'new-version'; }, 'immutable publication contract drift');
  expectFailure('event-renamed', 'contract', (value) => { value.event.eventType = 'workflow.published'; }, 'publication event contract drift');
  expectFailure('missing-source-binding', 'contract', (value) => { value.sourceBindings.store = 'missing.java'; }, 'source binding does not exist');
  expectFailure('publish-planned', 'openapi', (value) => { value.paths['/api/v1/workflows/{workflowId}/publish'].post['x-workflow-implementation-status'] = 'planned'; }, 'must be marked implemented');
  expectFailure('header-optional', 'openapi', (value) => { value.components.parameters.IdempotencyKey.required = false; }, 'required header missing');
  expectFailure('missing-409', 'openapi', (value) => { delete value.paths['/api/v1/workflows/{workflowId}/publish'].post.responses['409']; }, 'response missing: 409');
  expectFailure('revision-not-required', 'openapi', (value) => { value.components.schemas.PublishWorkflowRequest.required = []; }, 'required fields drift');
  expectFailure('revision-int32', 'openapi', (value) => { value.components.schemas.PublishWorkflowRequest.properties.expectedDraftRevision.format = 'int32'; }, 'field bounds drifted');
  expectFailure('release-note-unbounded', 'openapi', (value) => { delete value.components.schemas.PublishWorkflowRequest.properties.releaseNote.maxLength; }, 'field bounds drifted');
  expectFailure('response-checksum-removed', 'openapi', (value) => { value.components.schemas.PublishedWorkflow.required.splice(2, 1); }, 'required fields drift');
  expectFailure('baseline-planned', 'baseline', (value) => { delete value.operations.find((entry) => entry.operationId === 'publishWorkflow').implementationStatus; }, 'compatibility baseline must mark');
  expectFailure('package-script-drift', 'packageJson', (value) => { value.scripts['definition:g3-08:verify'] = 'node missing.mjs'; }, 'package G3-08 verify script drifted');
  expectFailure('root-gate-removed', 'packageJson', (value) => { value.scripts.verify = value.scripts.verify.replace('pnpm run definition:g3-08:test', ''); }, 'pnpm verify binding');

  console.log(`G3-08 verifier negative tests PASS: ${mutationCount}/${mutationCount} mutations rejected.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
