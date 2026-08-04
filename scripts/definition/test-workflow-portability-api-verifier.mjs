import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'definition', 'verify-workflow-portability-api.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'definition', 'portability-api.json'),
  openapi: path.join(repositoryRoot, 'api', 'openapi', 'workflow-platform.v1.openapi.json'),
  baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
  packageJson: path.join(repositoryRoot, 'package.json'),
  traceability: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
};
const flags = {
  contract: '--contract',
  openapi: '--openapi',
  baseline: '--baseline',
  packageJson: '--package-json',
  traceability: '--traceability',
};

function run(overrides = {}) {
  const args = [verifier];
  for (const [name, file] of Object.entries(overrides)) args.push(flags[name], file);
  return spawnSync(process.execPath, args, { cwd: repositoryRoot, encoding: 'utf8' });
}

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g3-09-verifier-'));
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
  if (positive.status !== 0) throw new Error(`Positive G3-09 verifier failed:\n${output(positive)}`);

  expectFailure('format-drift', 'contract', (value) => { value.bundle.format = 'unsafe'; }, 'portable bundle contract drift');
  expectFailure('resource-cap-drift', 'contract', (value) => { value.bundle.maximumResourceReferences = 20000; }, 'portable bundle contract drift');
  expectFailure('implicit-import-mapping', 'contract', (value) => { value.resourceMapping.importPolicy = 'preserve'; }, 'resource mapping policy drift');
  expectFailure('diff-cap-drift', 'contract', (value) => { value.diff.maximumPublicChanges = 5000; }, 'version diff contract drift');
  expectFailure('secret-material-enabled', 'contract', (value) => { value.security.secretMaterialAllowed = true; }, 'portability security boundary drifted');
  expectFailure('http-bound-drift', 'contract', (value) => { value.security.maximumHttpDocumentCharacters = 9999999; }, 'portability security boundary drifted');
  expectFailure('clone-planned', 'openapi', (value) => { value.paths['/api/v1/workflows/{workflowId}/clone'].post['x-workflow-implementation-status'] = 'planned'; }, 'cloneWorkflow must be marked implemented');
  expectFailure('clone-header-removed', 'openapi', (value) => { value.paths['/api/v1/workflows/{workflowId}/clone'].post.parameters.pop(); }, 'cloneWorkflow required headers drift');
  expectFailure('import-mapping-optional', 'openapi', (value) => { value.components.schemas.ImportWorkflowRequest.required.pop(); }, 'ImportWorkflowRequest.required drift');
  expectFailure('bundle-format-drift', 'openapi', (value) => { value.components.schemas.WorkflowExportBundle.properties.format.const = 'other'; }, 'OpenAPI portable bundle limits drifted');
  expectFailure('bundle-tenant-leak', 'openapi', (value) => { value.components.schemas.WorkflowExportSource.properties.tenantId = { type: 'string' }; }, 'portable bundle exposes forbidden field');
  expectFailure('diff-query-optional', 'openapi', (value) => { value.paths['/api/v1/workflows/{workflowId}/versions/diff'].get.parameters.find((item) => item.name === 'fromVersion').required = false; }, 'diff required query parameter is missing');
  expectFailure('baseline-status-drift', 'baseline', (value) => { value.operations.find((item) => item.operationId === 'importWorkflow').implementationStatus = 'planned'; }, 'importWorkflow compatibility baseline must be implemented');
  expectFailure('package-script-drift', 'packageJson', (value) => { value.scripts['definition:g3-09:verify'] = 'node missing.mjs'; }, 'package G3-09 verify script drifted');
  expectFailure('traceability-g3-09-removed', 'traceability', (value) => { const requirement = value.requirements.find((entry) => entry.id === 'REQ-DEF-001'); requirement.api = requirement.api.filter((entry) => !entry.includes('[G3-09]')); }, 'REQ-DEF-001 must trace G3-09 implemented artifacts');

  console.log(`G3-09 verifier negative tests PASS: ${mutationCount}/${mutationCount} mutations rejected.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
