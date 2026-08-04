import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'definition', 'verify-workflow-validation-api.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'validate-api.json'),
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

const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g3-07-verifier-'));
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
  if (positive.status !== 0) throw new Error(`Positive G3-07 verifier failed:\n${output(positive)}`);

  expectFailure('pipeline-reordered', 'contract', (value) => { value.pipeline.reverse(); }, 'validation pipeline order drift');
  expectFailure('issue-cap-expanded', 'contract', (value) => { value.result.maximumPublicIssues = 1000; }, 'validation result contract drift');
  expectFailure('publish-credential', 'contract', (value) => { value.result.publishCredential = true; }, 'validation result contract drift');
  expectFailure('secret-selection-enabled', 'contract', (value) => { value.resourceContext.selectedMetadata.push('ciphertext'); }, 'resource metadata allowlist drift');
  expectFailure('permission-drift', 'contract', (value) => { value.resourceContext.permissions[0] = 'connector:read'; }, 'resource permissions drift');
  expectFailure('missing-source-binding', 'contract', (value) => { value.sourceBindings.service = 'missing.java'; }, 'source binding does not exist');
  expectFailure('validate-planned', 'openapi', (value) => { value.paths['/api/v1/workflows/{workflowId}/validate'].post['x-workflow-implementation-status'] = 'planned'; }, 'must be marked implemented');
  expectFailure('header-optional', 'openapi', (value) => { value.components.parameters.IdempotencyKey.required = false; }, 'required header missing');
  expectFailure('missing-404', 'openapi', (value) => { delete value.paths['/api/v1/workflows/{workflowId}/validate'].post.responses['404']; }, 'response missing: 404');
  expectFailure('warning-severity', 'openapi', (value) => { value.components.schemas.ValidationIssue.properties.severity.enum.push('warning'); }, 'public issue severity drift');
  expectFailure('issue-code-removed', 'openapi', (value) => { value.components.schemas.ValidationIssue.properties.code.enum.pop(); }, 'public issue codes drift');
  expectFailure('result-binding-removed', 'openapi', (value) => { value.components.schemas.ValidationResult.required.pop(); }, 'ValidationResult required fields drift');
  expectFailure('publish-operation-removed', 'openapi', (value) => { delete value.paths['/api/v1/workflows/{workflowId}/publish']; }, 'Publish API operation is missing');
  expectFailure('baseline-status-removed', 'baseline', (value) => { delete value.operations.find((entry) => entry.operationId === 'validateWorkflow').implementationStatus; }, 'compatibility baseline must mark');
  expectFailure('package-script-drift', 'packageJson', (value) => { value.scripts['definition:g3-07:verify'] = 'node missing.mjs'; }, 'package G3-07 verify script drifted');

  console.log(`G3-07 verifier negative tests PASS: ${mutationCount}/${mutationCount} mutations rejected.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
