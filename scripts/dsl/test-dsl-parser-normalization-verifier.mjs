import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'dsl', 'verify-dsl-parser-normalization.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'dsl', 'parser-normalization.json'),
  pom: path.join(repositoryRoot, 'control-plane', 'pom.xml'),
  javaRoot: path.join(
    repositoryRoot,
    'control-plane',
    'src',
    'main',
    'java',
    'com',
    'workflowplatform',
    'controlplane',
    'definition',
  ),
  javaTests: path.join(
    repositoryRoot,
    'control-plane',
    'src',
    'test',
    'java',
    'com',
    'workflowplatform',
    'controlplane',
    'definition',
    'WorkflowDefinitionParserTests.java',
  ),
  fixtures: path.join(repositoryRoot, 'tests', 'contracts', 'dsl', 'parser'),
  packageFile: path.join(repositoryRoot, 'package.json'),
  document: path.join(repositoryRoot, 'docs', 'architecture', 'dsl-parser-normalization.md'),
  requirements: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
  matrix: path.join(repositoryRoot, 'docs', 'planning', '03-需求追踪矩阵.md'),
};
const flags = {
  contract: '--contract',
  pom: '--pom',
  javaRoot: '--java-root',
  javaTests: '--java-tests',
  fixtures: '--fixtures',
  packageFile: '--package',
  document: '--document',
  requirements: '--requirements',
  matrix: '--matrix',
};
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-dsl-parser-test-'));
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

function mutatedFixtureRoot(name, mutate) {
  const target = path.join(temporaryRoot, name);
  cpSync(sources.fixtures, target, { recursive: true });
  mutate(target);
  return target;
}

try {
  const positive = run({}, true);
  if (positive.status !== 0) throw new Error(`Positive DSL parser contract failed:\n${output(positive)}`);

  expectJsonFailure('contract-identity', 'contract', (contract) => {
    contract.task = 'G3-99';
  }, 'parser contract identity');
  expectJsonFailure('source-formats', 'contract', (contract) => {
    contract.formats = ['json'];
  }, 'parser source formats');
  expectJsonFailure('input-byte-limit', 'contract', (contract) => {
    contract.input.maximumBytes = 2097152;
  }, 'parser input contract');
  expectJsonFailure('duplicate-key-policy', 'contract', (contract) => {
    contract.input.duplicateKeysAllowed = true;
  }, 'parser input contract');
  expectJsonFailure('nesting-limit', 'contract', (contract) => {
    contract.streamConstraints.maximumNestingDepth = 128;
  }, 'parser stream constraints');
  expectJsonFailure('token-limit', 'contract', (contract) => {
    contract.streamConstraints.maximumTokenCount = 400000;
  }, 'parser stream constraints');
  expectJsonFailure('yaml-boolean-policy', 'contract', (contract) => {
    contract.yaml.booleanLikeWords = 'booleans';
  }, 'YAML safety contract');
  expectJsonFailure('yaml-alias-limit', 'contract', (contract) => {
    contract.yaml.maximumAliasesForCollections = 10;
  }, 'YAML safety contract');
  expectJsonFailure('canonical-key-order', 'contract', (contract) => {
    contract.normalization.objectKeyOrder = 'source-order';
  }, 'canonical JSON normalization contract');
  expectJsonFailure('canonical-number-rule', 'contract', (contract) => {
    contract.normalization.numbers = 'double';
  }, 'canonical JSON normalization contract');
  expectJsonFailure('canonical-byte-limit', 'contract', (contract) => {
    contract.normalization.maximumCanonicalBytes = 2097152;
  }, 'canonical JSON normalization contract');
  expectJsonFailure('checksum-algorithm', 'contract', (contract) => {
    contract.checksum.algorithm = 'MD5';
  }, 'parser checksum contract');
  expectJsonFailure('failure-code', 'contract', (contract) => {
    contract.failureCodes.pop();
  }, 'parser failure codes');
  expectJsonFailure('semantic-boundary', 'contract', (contract) => {
    contract.modelBinding.graphSemanticsValidated = true;
  }, 'parser model binding');
  expectJsonFailure('source-binding', 'contract', (contract) => {
    contract.sourceBindings.javaPackage = 'shared-contracts';
  }, 'parser source bindings');

  expectFailure(
    'yaml-dependency',
    {
      pom: writeTextCase(
        'yaml-dependency',
        'pom',
        (source) =>
          source.replace(
            /\s*<dependency>\s*<groupId>com\.fasterxml\.jackson\.dataformat<\/groupId>\s*<artifactId>jackson-dataformat-yaml<\/artifactId>\s*<\/dependency>/,
            '',
          ),
        'xml',
      ),
    },
    'control-plane parser dependency marker missing',
  );
  expectFailure(
    'java-duplicate-detection',
    {
      javaRoot: mutatedJavaRoot('java-duplicate-detection', 'JacksonWorkflowDefinitionParser.java', (source) =>
        source.replaceAll('STRICT_DUPLICATE_DETECTION', 'AUTO_CLOSE_SOURCE'),
      ),
    },
    'Jackson workflow parser marker missing: STRICT_DUPLICATE_DETECTION',
  );
  expectFailure(
    'java-yaml-preflight',
    {
      javaRoot: mutatedJavaRoot('java-yaml-preflight', 'JacksonWorkflowDefinitionParser.java', (source) =>
        source.replace('new SafeConstructor(yamlLoaderOptions())', 'new SafeConstructor(new LoaderOptions())'),
      ),
    },
    'Jackson workflow parser marker missing: new SafeConstructor(yamlLoaderOptions())',
  );
  expectFailure(
    'java-input-limit',
    {
      javaRoot: mutatedJavaRoot('java-input-limit', 'JacksonWorkflowDefinitionParser.java', (source) =>
        source.replace('MAXIMUM_INPUT_BYTES = 1_048_576', 'MAXIMUM_INPUT_BYTES = 2_097_152'),
      ),
    },
    'Jackson workflow parser marker missing: MAXIMUM_INPUT_BYTES = 1_048_576',
  );
  expectFailure(
    'java-number-normalization',
    {
      javaRoot: mutatedJavaRoot('java-number-normalization', 'WorkflowDefinitionCanonicalizer.java', (source) =>
        source.replaceAll('stripTrailingZeros()', 'plus()'),
      ),
    },
    'workflow canonicalizer marker missing: stripTrailingZeros()',
  );
  expectFailure(
    'java-checksum',
    {
      javaRoot: mutatedJavaRoot('java-checksum', 'ParsedWorkflowDefinition.java', (source) =>
        source.replaceAll('SHA-256', 'MD5'),
      ),
    },
    'parsed workflow result marker missing: SHA-256',
  );
  expectFailure(
    'java-test-boundary',
    {
      javaTests: writeTextCase(
        'java-test-boundary',
        'javaTests',
        (source) => source.replace('leavesGraphSemanticsForG303', 'renamedGraphBoundary'),
        'java',
      ),
    },
    'Java parser test coverage marker missing: leavesGraphSemanticsForG303',
  );
  expectFailure(
    'fixture-missing',
    {
      fixtures: mutatedFixtureRoot('fixture-missing', (root) =>
        rmSync(path.join(root, 'invalid', 'custom-tag.yaml')),
      ),
    },
    'parser fixture is missing: invalid/custom-tag.yaml',
  );
  expectFailure(
    'fixture-boolean',
    {
      fixtures: mutatedFixtureRoot('fixture-boolean', (root) => {
        const file = path.join(root, 'equivalent', 'workflow.yaml');
        writeFileSync(file, readFileSync(file, 'utf8').replace('enabled: yes', 'enabled: true'), 'utf8');
      }),
    },
    'equivalent YAML fixture marker missing: enabled: yes',
  );
  expectJsonFailure('package-script', 'packageFile', (packageJson) => {
    delete packageJson.scripts['dsl:g3-02:test'];
  }, 'package script dsl:g3-02:test');
  expectFailure(
    'document-boundary',
    {
      document: writeTextCase(
        'document-boundary',
        'document',
        (source) => source.replaceAll('canonical_output_too_large', 'output_too_large'),
        'md',
      ),
    },
    'parser architecture document marker missing: canonical_output_too_large',
    true,
  );
  expectFailure(
    'requirements-evidence',
    {
      requirements: writeJsonCase('requirements-evidence', 'requirements', (requirements) => {
        const requirement = requirements.requirements.find((entry) => entry.id === 'REQ-DSL-001');
        requirement.tables = requirement.tables.filter((entry) => !entry.includes('parser-normalization.json'));
      }),
    },
    'REQ-DSL-001 G3-02 marker missing: file:config/dsl/parser-normalization.json',
    true,
  );
  expectFailure(
    'matrix-evidence',
    {
      matrix: writeTextCase(
        'matrix-evidence',
        'matrix',
        (source) => source.replace('Java 21 项', 'Java cases'),
        'md',
      ),
    },
    'REQ-DSL-001 matrix marker missing: Java 21 项',
    true,
  );

  console.log(`DSL G3-02 verifier tests PASS: positive contract plus ${mutationCount} reverse mutations.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
