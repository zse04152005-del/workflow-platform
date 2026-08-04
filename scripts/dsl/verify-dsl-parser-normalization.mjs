import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function parseArguments(argv) {
  const options = {
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
    architectureIndex: path.join(repositoryRoot, 'docs', 'architecture', 'README.md'),
    requirements: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
    matrix: path.join(repositoryRoot, 'docs', 'planning', '03-需求追踪矩阵.md'),
    skipDocuments: false,
  };
  const flags = new Map([
    ['--contract', 'contract'],
    ['--pom', 'pom'],
    ['--java-root', 'javaRoot'],
    ['--java-tests', 'javaTests'],
    ['--fixtures', 'fixtures'],
    ['--package', 'packageFile'],
    ['--document', 'document'],
    ['--architecture-index', 'architectureIndex'],
    ['--requirements', 'requirements'],
    ['--matrix', 'matrix'],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (flags.has(argument)) {
      const value = argv[index + 1];
      if (!value) throw new Error(`${argument} requires a path`);
      options[flags.get(argument)] = path.resolve(repositoryRoot, value);
      index += 1;
    } else if (argument === '--skip-documents') {
      options.skipDocuments = true;
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return options;
}

function readText(file, label, errors) {
  if (!existsSync(file)) {
    errors.push(`${label} is missing: ${file}`);
    return '';
  }
  return readFileSync(file, 'utf8');
}

function readJson(file, label, errors) {
  const source = readText(file, label, errors);
  if (!source) return {};
  try {
    return JSON.parse(source);
  } catch (error) {
    errors.push(`${label} must be valid JSON: ${error.message}`);
    return {};
  }
}

function exactArray(actual, expected, label, errors) {
  if (!Array.isArray(actual) || JSON.stringify(actual) !== JSON.stringify(expected)) {
    errors.push(`${label} must equal ${expected.join(', ')}`);
  }
}

function exactObject(actual, expected, label, errors) {
  if (!actual || JSON.stringify(actual) !== JSON.stringify(expected)) {
    errors.push(`${label} drifted from the G3-02 contract`);
  }
}

function requireMarkers(source, markers, label, errors) {
  for (const marker of markers) {
    if (!source.includes(marker)) errors.push(`${label} marker missing: ${marker}`);
  }
}

function validateContract(contract, errors) {
  if (
    contract.schemaVersion !== 1 ||
    contract.contractVersion !== '1.0.0' ||
    contract.task !== 'G3-02' ||
    contract.requirement !== 'REQ-DSL-001' ||
    contract.module !== 'definition'
  ) {
    errors.push('parser contract identity must remain G3-02/REQ-DSL-001 version 1.0.0');
  }
  exactArray(contract.formats, ['json', 'yaml'], 'parser source formats', errors);
  exactObject(
    contract.input,
    {
      encoding: 'UTF-8',
      minimumBytes: 1,
      maximumBytes: 1048576,
      optionalUtf8Bom: true,
      documentsExactly: 1,
      rootType: 'object',
      duplicateKeysAllowed: false,
    },
    'parser input contract',
    errors,
  );
  exactObject(
    contract.streamConstraints,
    {
      maximumNestingDepth: 64,
      maximumDocumentCharacters: 1048576,
      maximumTokenCount: 200000,
      maximumNumberLength: 100,
      maximumStringLength: 262144,
      maximumNameLength: 200,
    },
    'parser stream constraints',
    errors,
  );
  exactObject(
    contract.yaml,
    {
      allowDuplicateKeys: false,
      booleanLikeWords: 'strings',
      maximumAliasesForCollections: 0,
      allowRecursiveKeys: false,
      mergeOnCompose: false,
      allowCustomGlobalTags: false,
      codePointLimit: 1048576,
      nestingDepthLimit: 64,
    },
    'YAML safety contract',
    errors,
  );
  exactObject(
    contract.normalization,
    {
      algorithm: 'workflow-canonical-json-v1',
      objectKeyOrder: 'java-string-natural-order',
      arrayOrder: 'preserve-source-order',
      mappingOrder: 'canonical-object-key-order',
      numbers: 'arbitrary-precision-strip-trailing-zero-plain-decimal',
      negativeZero: 'zero',
      whitespace: 'none',
      unicodeNormalization: 'none',
      encoding: 'UTF-8',
      maximumCanonicalBytes: 1048576,
    },
    'canonical JSON normalization contract',
    errors,
  );
  exactObject(
    contract.checksum,
    {
      algorithm: 'SHA-256',
      input: 'canonical-UTF-8-json-bytes',
      encoding: 'lowercase-hex',
      pattern: '^[0-9a-f]{64}$',
    },
    'parser checksum contract',
    errors,
  );
  exactArray(
    contract.failureCodes,
    [
      'empty_input',
      'input_too_large',
      'invalid_utf8',
      'malformed_document',
      'resource_limit_exceeded',
      'multiple_documents',
      'root_not_object',
      'core_model_invalid',
      'canonical_output_too_large',
    ],
    'parser failure codes',
    errors,
  );
  if (
    contract.modelBinding?.javaType !== 'com.workflowplatform.contracts.dsl.WorkflowDefinition' ||
    contract.modelBinding?.preserveUnknownSourceFieldsInCanonicalJson !== true ||
    contract.modelBinding?.graphSemanticsValidated !== false ||
    contract.modelBinding?.nodeSpecificSchemasValidated !== false ||
    contract.modelBinding?.expressionSemanticsValidated !== false
  ) {
    errors.push('parser model binding and deferred semantic boundaries drifted');
  }
  exactArray(
    Object.keys(contract.deferred ?? {}),
    ['G3-03', 'G3-04', 'G3-05', 'G3-10'],
    'parser deferred tasks',
    errors,
  );
  const expectedBindings = {
    javaPackage: 'control-plane/src/main/java/com/workflowplatform/controlplane/definition',
    javaTests:
      'control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowDefinitionParserTests.java',
    fixtures: 'tests/contracts/dsl/parser',
    verifier: 'scripts/dsl/verify-dsl-parser-normalization.mjs',
    verifierTests: 'scripts/dsl/test-dsl-parser-normalization-verifier.mjs',
  };
  exactObject(contract.sourceBindings, expectedBindings, 'parser source bindings', errors);
  if (contract.document !== 'docs/architecture/dsl-parser-normalization.md') {
    errors.push('parser architecture document binding drifted');
  }
}

function validatePom(pom, errors) {
  requireMarkers(
    pom,
    [
      '<groupId>com.fasterxml.jackson.dataformat</groupId>',
      '<artifactId>jackson-dataformat-yaml</artifactId>',
    ],
    'control-plane parser dependency',
    errors,
  );
}

function validateJava(options, errors) {
  const expectedFiles = [
    'JacksonWorkflowDefinitionParser.java',
    'ParsedWorkflowDefinition.java',
    'WorkflowDefinitionCanonicalizer.java',
    'WorkflowDefinitionModelMapper.java',
    'WorkflowDefinitionParseException.java',
    'WorkflowDefinitionParseFailure.java',
    'WorkflowDefinitionParser.java',
    'WorkflowDefinitionSourceFormat.java',
  ];
  if (!existsSync(options.javaRoot)) {
    errors.push(`Java parser root is missing: ${options.javaRoot}`);
    return;
  }
  const actual = new Set(readdirSync(options.javaRoot));
  for (const file of expectedFiles) {
    if (!actual.has(file)) errors.push(`Java parser file is missing: ${file}`);
  }
  const sources = Object.fromEntries(
    expectedFiles.map((file) => [file, readText(path.join(options.javaRoot, file), `Java ${file}`, errors)]),
  );
  requireMarkers(
    sources['JacksonWorkflowDefinitionParser.java'],
    [
      '@Component',
      'MAXIMUM_INPUT_BYTES = 1_048_576',
      'MAXIMUM_CANONICAL_BYTES = 1_048_576',
      'MAXIMUM_NESTING_DEPTH = 64',
      'MAXIMUM_TOKEN_COUNT = 200_000',
      'maxNumberLength(MAXIMUM_NUMBER_LENGTH)',
      'maxStringLength(MAXIMUM_STRING_LENGTH)',
      'maxNameLength(MAXIMUM_NAME_LENGTH)',
      'STRICT_DUPLICATE_DETECTION',
      'new SafeConstructor(yamlLoaderOptions())',
      'setMaxAliasesForCollections(0)',
      'setAllowRecursiveKeys(false)',
      'setMergeOnCompose(false)',
      'new UnTrustedTagInspector()',
      'PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS',
      'CodingErrorAction.REPORT',
      'sourceParser.nextToken()',
      'validateYaml(text)',
      'CANONICAL_OUTPUT_TOO_LARGE',
    ],
    'Jackson workflow parser',
    errors,
  );
  requireMarkers(
    sources['WorkflowDefinitionCanonicalizer.java'],
    [
      'new TreeMap<>()',
      'stripTrailingZeros()',
      'normalized.signum() == 0',
      'BigDecimal.ZERO',
      'BoundedCanonicalOutput',
      'reserve(totalLength)',
      'writePlainDecimal',
      'StandardCharsets.UTF_8',
    ],
    'workflow canonicalizer',
    errors,
  );
  requireMarkers(
    sources['ParsedWorkflowDefinition.java'],
    [
      'SHA-256',
      'HexFormat.of()',
      '.formatHex(',
      'canonicalJson.getBytes(StandardCharsets.UTF_8)',
      'public byte[] canonicalBytes()',
      'public String checksum()',
    ],
    'parsed workflow result',
    errors,
  );
  requireMarkers(
    sources['WorkflowDefinitionModelMapper.java'],
    [
      'new WorkflowDefinition(',
      'mapParameterMappings',
      'configuration.remove',
      'case "start"',
      'case "human_task"',
      'case "exponential"',
    ],
    'workflow model mapper',
    errors,
  );
  requireMarkers(
    sources['WorkflowDefinitionParseException.java'],
    ['Workflow definition source rejected: ', 'public WorkflowDefinitionParseFailure failure()', 'public int line()'],
    'parser exception',
    errors,
  );
  requireMarkers(
    sources['WorkflowDefinitionParser.java'],
    ['public interface WorkflowDefinitionParser', 'ParsedWorkflowDefinition parse(byte[] source'],
    'parser public port',
    errors,
  );
  if (sources['WorkflowDefinitionParseException.java'].includes('cause.getMessage()')) {
    errors.push('parser public exception must not expose dependency error messages');
  }
  const failureSource = sources['WorkflowDefinitionParseFailure.java'];
  for (const wire of [
    'empty_input',
    'input_too_large',
    'invalid_utf8',
    'malformed_document',
    'resource_limit_exceeded',
    'multiple_documents',
    'root_not_object',
    'core_model_invalid',
    'canonical_output_too_large',
  ]) {
    if (!failureSource.includes(`"${wire}"`)) errors.push(`Java parser failure wire missing: ${wire}`);
  }
}

function validateTestsAndFixtures(options, errors) {
  const tests = readText(options.javaTests, 'Java parser tests', errors);
  const testCount = (tests.match(/@Test\b/g) ?? []).length;
  if (testCount < 21) errors.push('Java parser tests must contain at least 21 executable cases');
  requireMarkers(
    tests,
    [
      'producesIdenticalCanonicalJsonAndChecksumForEquivalentJsonAndYaml',
      'rejectsDuplicateJsonAndYamlKeys',
      'rejectsDocumentsBeyondTheTokenLimit',
      'rejectsYamlCollectionAliasesRecursiveKeysAndCustomTags',
      'rejectsCanonicalOutputBeyondTheByteLimit',
      'keepsPublicFailureMessagesStableAndPayloadSafe',
      'leavesGraphSemanticsForG303',
      'assertArrayEquals(',
      'canonicalBytes must return a defensive copy',
    ],
    'Java parser test coverage',
    errors,
  );
  const fixtureFiles = [
    'equivalent/workflow.json',
    'equivalent/workflow.yaml',
    'invalid/duplicate-key.json',
    'invalid/multiple-documents.yaml',
    'invalid/root-array.json',
    'invalid/collection-alias.yaml',
    'invalid/custom-tag.yaml',
  ];
  for (const relative of fixtureFiles) {
    if (!existsSync(path.join(options.fixtures, relative))) {
      errors.push(`parser fixture is missing: ${relative}`);
    }
  }
  const equivalentJson = readJson(
    path.join(options.fixtures, 'equivalent', 'workflow.json'),
    'equivalent JSON fixture',
    errors,
  );
  if (
    equivalentJson.name !== 'parser-equivalence' ||
    equivalentJson.nodes?.[0]?.config?.zeta !== 1 ||
    equivalentJson.nodes?.[0]?.config?.array?.join(',') !== '2,1'
  ) {
    errors.push('equivalent JSON fixture must exercise number and array normalization');
  }
  const equivalentYaml = readText(
    path.join(options.fixtures, 'equivalent', 'workflow.yaml'),
    'equivalent YAML fixture',
    errors,
  );
  requireMarkers(
    equivalentYaml,
    ['enabled: yes', 'disabled: off', 'zeta: 1.0', 'alpha: -0.00', 'unicode: é'],
    'equivalent YAML fixture',
    errors,
  );
  const duplicate = readText(
    path.join(options.fixtures, 'invalid', 'duplicate-key.json'),
    'duplicate-key fixture',
    errors,
  );
  if ((duplicate.match(/"schemaVersion"/g) ?? []).length !== 2) {
    errors.push('duplicate-key fixture must contain two schemaVersion keys');
  }
}

function validatePackage(packageJson, errors) {
  const scripts = packageJson.scripts ?? {};
  if (scripts['dsl:g3-02:verify'] !== 'node scripts/dsl/verify-dsl-parser-normalization.mjs') {
    errors.push('package script dsl:g3-02:verify is missing or drifted');
  }
  if (scripts['dsl:g3-02:test'] !== 'node scripts/dsl/test-dsl-parser-normalization-verifier.mjs') {
    errors.push('package script dsl:g3-02:test is missing or drifted');
  }
  const verify = scripts.verify ?? '';
  for (const command of ['pnpm run dsl:g3-02:verify', 'pnpm run dsl:g3-02:test']) {
    if (!verify.includes(command)) errors.push(`root verify must include ${command}`);
  }
}

function validateDocuments(options, errors) {
  const document = readText(options.document, 'parser architecture document', errors);
  requireMarkers(
    document,
    [
      'G3-02',
      'workflow-canonical-json-v1',
      '1 MiB',
      'SHA-256',
      'UTF-8',
      '重复键',
      '集合别名',
      'G3-03',
      'G3-04',
      'G3-05',
      'canonical_output_too_large',
    ],
    'parser architecture document',
    errors,
  );
  const index = readText(options.architectureIndex, 'architecture index', errors);
  if (!index.includes('(dsl-parser-normalization.md)')) {
    errors.push('architecture index must link dsl-parser-normalization.md');
  }
  const requirements = readJson(options.requirements, 'traceability requirements', errors);
  const requirement = requirements.requirements?.find((entry) => entry.id === 'REQ-DSL-001');
  if (!requirement) {
    errors.push('REQ-DSL-001 is missing from traceability requirements');
  } else {
    const serialized = JSON.stringify(requirement);
    for (const marker of [
      'file:config/dsl/parser-normalization.json',
      'file:control-plane/src/test/java/com/workflowplatform/controlplane/definition/WorkflowDefinitionParserTests.java',
      'file:docs/architecture/dsl-parser-normalization.md',
      '[G3-02]',
    ]) {
      if (!serialized.includes(marker)) errors.push(`REQ-DSL-001 G3-02 marker missing: ${marker}`);
    }
  }
  const matrix = readText(options.matrix, 'traceability matrix', errors);
  const row = matrix.split(/\r?\n/).find((line) => line.startsWith('| `REQ-DSL-001` |')) ?? '';
  for (const marker of ['JSON/YAML', 'canonical JSON', 'SHA-256', 'Java 21 项', 'G3-03']) {
    if (!row.includes(marker)) errors.push(`REQ-DSL-001 matrix marker missing: ${marker}`);
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const errors = [];
  const contract = readJson(options.contract, 'parser normalization contract', errors);
  validateContract(contract, errors);
  validatePom(readText(options.pom, 'control-plane pom', errors), errors);
  validateJava(options, errors);
  validateTestsAndFixtures(options, errors);
  validatePackage(readJson(options.packageFile, 'package.json', errors), errors);
  if (!options.skipDocuments) validateDocuments(options, errors);
  if (errors.length > 0) {
    console.error('DSL G3-02 parser normalization verification failed:');
    for (const error of errors) console.error(`- ${error}`);
    process.exitCode = 1;
    return;
  }
  console.log(
    'DSL G3-02 parser normalization PASS: JSON/YAML, bounded UTF-8 parsing, canonical JSON, SHA-256, 9 stable failures, 21 Java cases and deferred G3-03/G3-04/G3-05 boundaries.',
  );
}

main();
