import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'dsl', 'verify-expression-sandbox.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'dsl', 'expression-sandbox.json'),
  javaRoot: path.join(repositoryRoot, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'definition'),
  javaTests: path.join(repositoryRoot, 'control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'definition', 'WorkflowExpressionCompilerTests.java'),
  fixtures: path.join(repositoryRoot, 'tests', 'contracts', 'dsl', 'expressions'),
  packageFile: path.join(repositoryRoot, 'package.json'),
  document: path.join(repositoryRoot, 'docs', 'architecture', 'dsl-expression-sandbox.md'),
};
const flags = {
  contract: '--contract',
  javaRoot: '--java-root',
  javaTests: '--java-tests',
  fixtures: '--fixtures',
  packageFile: '--package',
  document: '--document',
};
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-expression-sandbox-test-'));
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
  if (positive.status !== 0) throw new Error(`Positive G3-05 contract failed:\n${output(positive)}`);

  expectJsonFailure('contract-identity', 'contract', (value) => {
    value.task = 'G3-99';
  }, 'expression contract identity');
  expectJsonFailure('cel-functions', 'contract', (value) => {
    value.languages.CEL.functionInvocationAllowed = true;
  }, 'CEL deterministic subset contract');
  expectJsonFailure('jsonpath-recursion', 'contract', (value) => {
    value.languages.JSON_PATH.recursiveDescentAllowed = true;
  }, 'JSONPath forbidden capability drifted: recursiveDescentAllowed');
  expectJsonFailure('jsonpath-filter', 'contract', (value) => {
    value.languages.JSON_PATH.filterAllowed = true;
  }, 'JSONPath forbidden capability drifted: filterAllowed');
  expectJsonFailure('static-typing', 'contract', (value) => {
    value.staticTyping.closedObjectRejectsUnknownProperty = false;
  }, 'expression static typing contract');
  expectJsonFailure('token-budget', 'contract', (value) => {
    value.sandbox.maximumTokens = 4096;
  }, 'expression sandbox drifted');
  expectJsonFailure('timeout-budget', 'contract', (value) => {
    value.sandbox.timeoutMilliseconds = 1000;
  }, 'expression sandbox drifted');
  expectJsonFailure('result-budget', 'contract', (value) => {
    value.sandbox.maximumResultBytes = 10485760;
  }, 'expression sandbox drifted');
  expectJsonFailure('source-echo', 'contract', (value) => {
    value.determinism.sourceEchoedInFailure = true;
  }, 'expression isolation and source-binding contract');
  expectJsonFailure('failure-code', 'contract', (value) => {
    value.failureCodes.pop();
  }, 'expression failure codes');
  expectJsonFailure('source-binding', 'contract', (value) => {
    value.sourceBindings.javaGuard = 'missing.java';
  }, 'expression source bindings');
  expectFailure(
    'java-cel-parser',
    {
      javaRoot: mutatedJavaRoot('java-cel-parser', 'DeterministicWorkflowExpressionCompiler.java', (source) =>
        source.replace('class CelParser', 'class RemovedCelParser'),
      ),
    },
    'expression compiler marker missing: class CelParser',
  );
  expectFailure(
    'java-jsonpath-parser',
    {
      javaRoot: mutatedJavaRoot('java-jsonpath-parser', 'DeterministicWorkflowExpressionCompiler.java', (source) =>
        source.replace('class NamedRootJsonPathParser', 'class RemovedJsonPathParser'),
      ),
    },
    'expression compiler marker missing: class NamedRootJsonPathParser',
  );
  expectFailure(
    'java-sha-binding',
    {
      javaRoot: mutatedJavaRoot('java-sha-binding', 'DeterministicWorkflowExpressionCompiler.java', (source) =>
        source.replace('MessageDigest.getInstance("SHA-256")', 'MessageDigest.getInstance("MD5")'),
      ),
    },
    'expression compiler marker missing: MessageDigest.getInstance("SHA-256")',
  );
  expectFailure(
    'java-runtime-guard',
    {
      javaRoot: mutatedJavaRoot('java-runtime-guard', 'ExpressionEvaluationGuard.java', (source) =>
        source.replace('maximumResultBytes()', 'removedResultBytes()'),
      ),
    },
    'expression runtime guard marker missing: maximumResultBytes()',
  );
  expectFailure(
    'java-tests-timeout',
    {
      javaTests: writeTextCase(
        'java-tests-timeout',
        'javaTests',
        (source) => source.replace('runtimeGuardEnforcesStepsAndMonotonicTimeout', 'removedTimeoutTest'),
        'java',
      ),
    },
    'expression Java tests marker missing: runtimeGuardEnforcesStepsAndMonotonicTimeout',
  );
  const fixtureRoot = path.join(temporaryRoot, 'fixtures-too-small');
  cpSync(sources.fixtures, fixtureRoot, { recursive: true });
  rmSync(path.join(fixtureRoot, 'invalid', 'jsonpath-budget.json'));
  expectFailure('fixture-count', { fixtures: fixtureRoot }, 'expression fixtures must retain at least 3 valid and 7 invalid cases');
  expectJsonFailure('package-gate', 'packageFile', (value) => {
    delete value.scripts['dsl:g3-05:verify'];
  }, 'package.json must expose dsl:g3-05:verify');
  expectFailure(
    'document-runtime-boundary',
    {
      document: writeTextCase(
        'document-runtime-boundary',
        'document',
        (source) => source.replaceAll('ExpressionEvaluationGuard', 'RemovedGuard'),
        'md',
      ),
    },
    'G3-05 architecture document marker missing: ExpressionEvaluationGuard',
    true,
  );

  console.log(`DSL G3-05 verifier self-test PASS: ${mutationCount} negative mutations blocked.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
