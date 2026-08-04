import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'dsl', 'verify-dsl-core-model.mjs');
const sources = {
  core: path.join(repositoryRoot, 'config', 'dsl', 'core-model.json'),
  schema: path.join(repositoryRoot, 'schemas', 'dsl', 'workflow-definition-v1.schema.json'),
  baseline: path.join(repositoryRoot, 'config', 'contracts', 'compatibility-baseline.json'),
  packageFile: path.join(repositoryRoot, 'package.json'),
  document: path.join(repositoryRoot, 'docs', 'architecture', 'dsl-core-model.md'),
  javaRoot: path.join(
    repositoryRoot,
    'shared-contracts',
    'src',
    'main',
    'java',
    'com',
    'workflowplatform',
    'contracts',
    'dsl',
  ),
  javaTests: path.join(
    repositoryRoot,
    'shared-contracts',
    'src',
    'test',
    'java',
    'com',
    'workflowplatform',
    'contracts',
    'dsl',
    'WorkflowDslCoreModelTests.java',
  ),
};
const flags = {
  core: '--core',
  schema: '--schema',
  baseline: '--baseline',
  packageFile: '--package',
  document: '--document',
  javaRoot: '--java-root',
  javaTests: '--java-tests',
};
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-dsl-core-test-'));
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

function writeJsonCase(name, kind, mutate) {
  const value = JSON.parse(readFileSync(sources[kind], 'utf8'));
  mutate(value);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  return target;
}

function writeTextCase(name, kind, mutate, extension = 'txt') {
  const value = readFileSync(sources[kind], 'utf8');
  const target = path.join(temporaryRoot, `${name}.${extension}`);
  writeFileSync(target, mutate(value), 'utf8');
  return target;
}

function expectFailure(name, overrides, expectedMessage, full = false) {
  mutationCount += 1;
  const result = run(overrides, full);
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expectedMessage)) {
    throw new Error(`${name} was not blocked as expected (${expectedMessage}):\n${combined}`);
  }
}

function expectJsonFailure(name, kind, mutate, expectedMessage) {
  expectFailure(name, { [kind]: writeJsonCase(name, kind, mutate) }, expectedMessage);
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
  if (positive.status !== 0) throw new Error(`Positive DSL core model failed:\n${output(positive)}`);

  expectJsonFailure('core-identity', 'core', (core) => {
    core.task = 'G3-99';
  }, 'core model identity');
  expectJsonFailure('core-node-removed', 'core', (core) => {
    core.node.types.pop();
  }, 'core node types');
  expectJsonFailure('core-retry-removed', 'core', (core) => {
    core.retry.strategies.pop();
  }, 'core retry strategies');
  expectJsonFailure('core-linear-field', 'core', (core) => {
    core.retry.strategyFields.linear = [];
  }, 'linear retry fields');
  expectJsonFailure('core-resource-limit', 'core', (core) => {
    core.limits.nodesMaximum = 5000;
  }, 'nodesMaximum must match');
  expectJsonFailure('core-deferred-boundary', 'core', (core) => {
    delete core.deferred['G3-05'];
  }, 'deferred task boundaries');

  expectJsonFailure('schema-version', 'schema', (schema) => {
    schema.properties.schemaVersion.const = 2;
  }, 'DSL schemaVersion v1');
  expectJsonFailure('schema-linear-removed', 'schema', (schema) => {
    schema.$defs.retryPolicy.properties.strategy.enum = ['fixed', 'exponential'];
  }, 'DSL retry strategies');
  expectJsonFailure('schema-linear-increment', 'schema', (schema) => {
    schema.$defs.retryPolicy.allOf[1].then.required = [];
  }, 'linear retry must require increment');
  expectJsonFailure('schema-edge-condition', 'schema', (schema) => {
    schema.$defs.edge.allOf[0].then.required = [];
  }, 'condition edge when rule');
  expectJsonFailure('schema-mapping-limit', 'schema', (schema) => {
    schema.$defs.edge.properties.mapping.maxProperties = 1000;
  }, 'parameter mapping maximum');
  expectJsonFailure('schema-unsafe-expression', 'schema', (schema) => {
    schema.$defs.safeExpression.pattern = '^[\\s\\S]+$';
  }, 'safe expression pattern must block');

  expectJsonFailure('baseline-version', 'baseline', (baseline) => {
    baseline.policy.dsl.immutableSchemaVersion = 2;
  }, 'DSL schemaVersion v1');
  expectJsonFailure('package-script', 'packageFile', (packageJson) => {
    delete packageJson.scripts['dsl:g3-01:test'];
  }, 'package script dsl:g3-01:test');

  expectFailure(
    'java-schema-version',
    {
      javaRoot: mutatedJavaRoot('java-schema-version', 'WorkflowDefinition.java', (source) =>
        source.replace('CURRENT_SCHEMA_VERSION = 1', 'CURRENT_SCHEMA_VERSION = 2'),
      ),
    },
    'Java schemaVersion invariant',
  );
  expectFailure(
    'java-retry-invariant',
    {
      javaRoot: mutatedJavaRoot('java-retry-invariant', 'RetryPolicy.java', (source) =>
        source.replace('linear retry requires increment and forbids multiplier', 'invalid linear retry'),
      ),
    },
    'Java linear retry invariant',
  );
  expectFailure(
    'java-deferred-test',
    {
      javaTests: writeTextCase(
        'java-deferred-test',
        'javaTests',
        (source) =>
          source.replace(
            'keepsGraphAndSemanticValidationDeferredToLaterG3Tasks',
            'renamedDeferredBoundaryTest',
          ),
        'java',
      ),
    },
    'Java DSL test coverage marker missing',
  );
  expectFailure(
    'document-boundary',
    {
      document: writeTextCase(
        'document-boundary',
        'document',
        (source) => source.replaceAll('G3-05', 'later-expression-task'),
        'md',
      ),
    },
    'DSL core model document marker missing: G3-05',
    true,
  );

  console.log(`DSL G3-01 verifier tests PASS: positive contract plus ${mutationCount} reverse mutations.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
