import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'dsl', 'verify-node-configuration-validation.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'dsl', 'node-configuration-validation.json'),
  schema: path.join(repositoryRoot, 'schemas', 'dsl', 'workflow-definition-v1.schema.json'),
  javaRoot: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'definition'),
  javaTests: path.join(repositoryRoot, 'control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'definition', 'WorkflowConfigurationValidatorTests.java'),
  fixtures: path.join(repositoryRoot, 'tests', 'contracts', 'dsl', 'configuration'),
  packageFile: path.join(repositoryRoot, 'package.json'),
  document: path.join(repositoryRoot, 'docs', 'architecture', 'dsl-node-configuration-validation.md'),
};
const flags = {
  contract: '--contract',
  schema: '--schema',
  javaRoot: '--java-root',
  javaTests: '--java-tests',
  fixtures: '--fixtures',
  packageFile: '--package',
  document: '--document',
};
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-dsl-configuration-test-'));
let mutationCount = 0;

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function run(overrides = {}, full = false) {
  const args = [verifier];
  for (const [kind, file] of Object.entries(overrides)) args.push(flags[kind], file);
  if (!full) args.push('--skip-documents');
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 20 * 1024 * 1024,
    windowsHide: true,
  });
}

function writeJsonCase(name, kind, mutate) {
  const value = JSON.parse(readFileSync(sources[kind], 'utf8'));
  mutate(value);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  return target;
}

function writeTextCase(name, kind, mutate, extension = 'txt') {
  const target = path.join(temporaryRoot, `${name}.${extension}`);
  writeFileSync(target, mutate(readFileSync(sources[kind], 'utf8')), 'utf8');
  return target;
}

function expectFailure(name, overrides, expected, full = false) {
  mutationCount += 1;
  const result = run(overrides, full);
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expected)) {
    throw new Error(`${name} was not blocked as expected (${expected}):\n${combined}`);
  }
}

function expectJsonFailure(name, kind, mutate, expected) {
  expectFailure(name, { [kind]: writeJsonCase(name, kind, mutate) }, expected);
}

function mutatedJavaRoot(name, file, mutate) {
  const target = path.join(temporaryRoot, name);
  cpSync(sources.javaRoot, target, { recursive: true });
  const javaFile = path.join(target, file);
  writeFileSync(javaFile, mutate(readFileSync(javaFile, 'utf8')), 'utf8');
  return target;
}

try {
  const positive = run({}, true);
  if (positive.status !== 0) throw new Error(`Positive G3-04 contract failed:\n${output(positive)}`);

  expectJsonFailure('contract-identity', 'contract', (value) => {
    value.task = 'G3-99';
  }, 'node configuration contract identity');
  expectJsonFailure('http-auth-contract', 'contract', (value) => {
    value.triggerSchemas.http.hmacRequiresSecret = false;
  }, 'HTTP trigger HMAC/OIDC/idempotency contract');
  expectJsonFailure('lookup-order', 'contract', (value) => {
    value.references.lookupOrder.reverse();
  }, 'resource scope and authorization contract');
  expectJsonFailure('publishable-status', 'contract', (value) => {
    value.references.publishableStatuses.push('ROTATION_REQUIRED');
  }, 'publishable resource status contract');
  expectJsonFailure('failure-code', 'contract', (value) => {
    value.failureCodes.pop();
  }, 'configuration failure codes');
  expectJsonFailure('schema-hmac-secret', 'schema', (value) => {
    delete value.$defs.httpTrigger.properties.hmacSecretRef;
  }, 'DSL HTTP trigger must bind hmacSecretRef conditionally');
  expectJsonFailure('schema-secret-limit', 'schema', (value) => {
    value.$defs.httpNode.properties.config.properties.secretRefs.maxItems = 200;
  }, 'DSL HTTP node secretRefs contract');
  expectFailure(
    'java-project-precedence',
    {
      javaRoot: mutatedJavaRoot('java-project-precedence', 'DeterministicWorkflowConfigurationValidator.java', (source) =>
        source.replace('context.projectId().equals(resource.projectId())', 'resource.projectId() == null'),
      ),
    },
    'configuration validator marker missing: context.projectId().equals(resource.projectId())',
  );
  expectFailure(
    'java-active-status',
    {
      javaRoot: mutatedJavaRoot('java-active-status', 'DeterministicWorkflowConfigurationValidator.java', (source) =>
        source.replace('resource.status() != Status.ACTIVE', 'resource.status() == Status.DISABLED'),
      ),
    },
    'configuration validator marker missing: resource.status() != Status.ACTIVE',
  );
  expectFailure(
    'java-result-sealed',
    {
      javaRoot: mutatedJavaRoot('java-result-sealed', 'ValidatedWorkflowConfiguration.java', (source) =>
        source.replace('sealed interface NodeConfiguration', 'interface NodeConfiguration'),
      ),
    },
    'typed configuration result marker missing: sealed interface NodeConfiguration',
  );
  expectFailure(
    'java-resource-secret-free',
    {
      javaRoot: mutatedJavaRoot('java-resource-secret-free', 'IntegrationResource.java', (source) =>
        source.replace('Secret-free connector or secret-reference metadata', 'Integration metadata'),
      ),
    },
    'integration resource marker missing: Secret-free connector or secret-reference metadata',
  );
  expectFailure(
    'java-tests-cross-tenant',
    { javaTests: writeTextCase('java-tests-cross-tenant', 'javaTests', (source) => source.replaceAll('OTHER_TENANT_ID', 'REMOVED_TENANT_ID'), 'java') },
    'configuration Java tests marker missing: OTHER_TENANT_ID',
  );
  const fixtureRoot = path.join(temporaryRoot, 'fixtures-too-small');
  cpSync(sources.fixtures, fixtureRoot, { recursive: true });
  for (const name of ['duplicate-secret-reference.json', 'human-timeout-missing-deadline.json']) {
    rmSync(path.join(fixtureRoot, 'invalid', name));
  }
  expectFailure('fixture-count', { fixtures: fixtureRoot }, 'configuration fixtures must retain at least 2 valid and 5 invalid cases');
  expectJsonFailure('package-gate', 'packageFile', (value) => {
    delete value.scripts['dsl:g3-04:verify'];
  }, 'package.json must expose dsl:g3-04:verify');
  expectFailure(
    'document-secret-boundary',
    { document: writeTextCase('document-secret-boundary', 'document', (source) => source.replaceAll('hmacSecretRef', 'hmacRef'), 'md') },
    'G3-04 architecture document marker missing: hmacSecretRef',
    true,
  );

  console.log(`DSL G3-04 verifier self-test PASS: ${mutationCount} negative mutations blocked.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
