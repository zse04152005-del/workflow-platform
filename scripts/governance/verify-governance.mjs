import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const expectedBranchTypes = [
  'feat',
  'fix',
  'docs',
  'test',
  'refactor',
  'perf',
  'build',
  'ci',
  'chore',
];
const expectedCommitTypes = [...expectedBranchTypes, 'revert'];
const expectedDodIds = Array.from({ length: 12 }, (_, index) =>
  `DOD-${String(index + 1).padStart(2, '0')}`,
);

function parseArguments(argv) {
  const options = {
    policy: path.join(repositoryRoot, 'config', 'governance', 'policy.json'),
    skipDocuments: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--skip-documents') {
      options.skipDocuments = true;
    } else if (argument === '--policy') {
      const value = argv[index + 1];
      if (!value) throw new Error('--policy requires a path');
      options.policy = path.resolve(repositoryRoot, value);
      index += 1;
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return options;
}

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`Unable to read JSON ${file}: ${error.message}`);
  }
}

function compilePattern(pattern, label, errors) {
  if (typeof pattern !== 'string' || !pattern.startsWith('^') || !pattern.endsWith('$')) {
    errors.push(`${label} must be an anchored regular expression`);
    return null;
  }
  try {
    return new RegExp(pattern, 'u');
  } catch (error) {
    errors.push(`${label} is not a valid regular expression: ${error.message}`);
    return null;
  }
}

function validateUniqueExactList(actual, expected, label, errors) {
  if (!Array.isArray(actual)) {
    errors.push(`${label} must be an array`);
    return;
  }
  if (new Set(actual).size !== actual.length) errors.push(`${label} contains duplicate entries`);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    errors.push(`${label} must equal: ${expected.join(', ')}`);
  }
}

function validateExamples(regex, validExamples, invalidExamples, label, errors, maxLength) {
  if (!regex) return;
  if (!Array.isArray(validExamples) || validExamples.length === 0) {
    errors.push(`${label}.validExamples must not be empty`);
  } else {
    for (const example of validExamples) {
      if (typeof example !== 'string' || !regex.test(example)) {
        errors.push(`${label} valid example was rejected: ${String(example)}`);
      }
      if (maxLength && example.length > maxLength) {
        errors.push(`${label} valid example exceeds max length ${maxLength}: ${example}`);
      }
    }
  }
  if (!Array.isArray(invalidExamples) || invalidExamples.length === 0) {
    errors.push(`${label}.invalidExamples must not be empty`);
  } else {
    for (const example of invalidExamples) {
      if (typeof example === 'string' && regex.test(example)) {
        errors.push(`${label} invalid example was accepted: ${example}`);
      }
    }
  }
}

function parseSemver(value) {
  const match = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/u.exec(value ?? '');
  return match ? match.slice(1).map(Number) : null;
}

function validateVersions(policy, errors) {
  const versions = policy.versions;
  if (!versions || typeof versions !== 'object') {
    errors.push('versions policy is required');
    return;
  }
  if (!parseSemver(versions.currentBaseVersion)) {
    errors.push(`versions.currentBaseVersion is not stable SemVer: ${String(versions.currentBaseVersion)}`);
  }
  if (versions.mavenDevelopmentVersion !== `${versions.currentBaseVersion}-SNAPSHOT`) {
    errors.push('versions.mavenDevelopmentVersion must be <currentBaseVersion>-SNAPSHOT');
  }
  const rootPackage = readJson(path.join(repositoryRoot, 'package.json'));
  const webPackage = readJson(path.join(repositoryRoot, 'web-console', 'package.json'));
  if (rootPackage.version !== versions.currentBaseVersion) {
    errors.push(`root package version ${rootPackage.version} != ${versions.currentBaseVersion}`);
  }
  if (webPackage.version !== versions.currentBaseVersion) {
    errors.push(`web-console version ${webPackage.version} != ${versions.currentBaseVersion}`);
  }
  const pom = readFileSync(path.join(repositoryRoot, 'pom.xml'), 'utf8');
  const mavenVersion = pom.match(
    /<artifactId>workflow-platform-parent<\/artifactId>\s*<version>([^<]+)<\/version>/u,
  )?.[1];
  if (mavenVersion !== versions.mavenDevelopmentVersion) {
    errors.push(`Maven development version ${String(mavenVersion)} != ${versions.mavenDevelopmentVersion}`);
  }
  const releaseTag = compilePattern(versions.releaseTagPattern, 'versions.releaseTagPattern', errors);
  validateExamples(
    releaseTag,
    [versions.stableTagExample, versions.prereleaseTagExample],
    versions.invalidTagExamples,
    'release tag',
    errors,
  );
  const requiredVersionRules = {
    preOneBreakingIncrement: 'minor',
    stableBreakingIncrement: 'major',
    additiveIncrement: 'minor',
    compatibleFixIncrement: 'patch',
  };
  for (const [key, expected] of Object.entries(requiredVersionRules)) {
    if (versions[key] !== expected) errors.push(`versions.${key} must be ${expected}`);
  }
}

function validateContracts(policy, errors) {
  const contracts = policy.contracts;
  if (!contracts || typeof contracts !== 'object') {
    errors.push('contracts policy is required');
    return;
  }
  if (
    contracts.openApi?.major !== 1 ||
    contracts.openApi?.pathPrefix !== '/api/v1' ||
    contracts.openApi?.compatibleWindowPreviousMinor !== 1 ||
    contracts.openApi?.breakingAction !== 'new-major-path-and-adr'
  ) {
    errors.push('OpenAPI policy must pin /api/v1, one previous minor, and major+ADR breaking changes');
  }
  for (const type of ['dsl', 'events']) {
    if (contracts[type]?.schemaVersion !== 1 || contracts[type]?.versionField !== 'schemaVersion') {
      errors.push(`${type} policy must start at integer schemaVersion=1`);
    }
  }
  const database = contracts.database;
  if (
    database?.appendOnly !== true ||
    database?.checksumAlgorithm !== 'sha256' ||
    database?.compatibilityStrategy !== 'expand-migrate-contract'
  ) {
    errors.push('database policy must be append-only SHA-256 expand-migrate-contract');
  }
  const migrationPattern = compilePattern(
    database?.migrationPattern,
    'contracts.database.migrationPattern',
    errors,
  );
  validateExamples(
    migrationPattern,
    ['V1__initial_schema.sql', 'V1_1__add_runtime_index.sql', 'V20260730_1__tenant_rls.sql'],
    ['V0__invalid.sql', 'R__repeatable.sql', 'V1__Uppercase.sql', 'V1__bad-name.sql'],
    'migration filename',
    errors,
  );

  const migrationDirectory = path.join(
    repositoryRoot,
    'control-plane',
    'src',
    'main',
    'resources',
    'db',
    'migration',
  );
  if (!existsSync(migrationDirectory)) {
    errors.push(`migration directory does not exist: ${migrationDirectory}`);
  } else if (migrationPattern) {
    for (const entry of readdirSync(migrationDirectory, { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith('.sql') && !migrationPattern.test(entry.name)) {
        errors.push(`existing migration violates governance policy: ${entry.name}`);
      }
    }
  }
  const migrationCatalog = readJson(
    path.join(repositoryRoot, 'config', 'migrations', 'checksums.json'),
  );
  if (migrationCatalog.schemaVersion !== 1 || migrationCatalog.algorithm !== 'sha256') {
    errors.push('migration checksum catalog must use schemaVersion=1 and sha256');
  }
  const migrationVerifier = readFileSync(
    path.join(repositoryRoot, 'scripts', 'database', 'verify-migrations.mjs'),
    'utf8',
  );
  for (const token of ['applied migrations are immutable', "createHash('sha256')", 'Duplicate migration version']) {
    if (!migrationVerifier.includes(token)) {
      errors.push(`migration verifier is missing governance invariant: ${token}`);
    }
  }
}

function validateDefinitionOfDone(policy, errors) {
  const definition = policy.definitionOfDone;
  if (!definition || typeof definition !== 'object') {
    errors.push('definitionOfDone policy is required');
    return;
  }
  const checks = definition.requiredChecks;
  if (!Array.isArray(checks)) {
    errors.push('definitionOfDone.requiredChecks must be an array');
    return;
  }
  const ids = checks.map((check) => check?.id);
  if (new Set(ids).size !== ids.length) {
    errors.push('definitionOfDone.requiredChecks contains duplicate IDs');
  }
  if (JSON.stringify(ids) !== JSON.stringify(expectedDodIds)) {
    errors.push(`definitionOfDone.requiredChecks must contain ${expectedDodIds.join(', ')}`);
  }
  for (const check of checks) {
    if (typeof check?.name !== 'string' || !check.name.trim()) {
      errors.push(`${String(check?.id)} must have a non-empty name`);
    }
  }
  if (definition.notApplicableRequiresReason !== true || definition.completionMarker !== '[x]') {
    errors.push('Definition of Done must require N/A reasons and use [x] as completion marker');
  }
}

function validateDocuments(policy, errors) {
  const documents = policy.requiredDocuments;
  if (!Array.isArray(documents) || documents.length < 4) {
    errors.push('requiredDocuments must list at least four governance/traceability documents');
    return;
  }
  for (const file of documents) {
    const resolved = path.resolve(repositoryRoot, file);
    if (!resolved.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(resolved)) {
      errors.push(`required governance document does not exist: ${String(file)}`);
    }
  }
  const dodPath = path.join(repositoryRoot, 'docs', 'governance', 'definition-of-done.md');
  if (existsSync(dodPath)) {
    const dod = readFileSync(dodPath, 'utf8');
    for (const check of policy.definitionOfDone?.requiredChecks ?? []) {
      if (!dod.includes(`### ${check.id} ${check.name}`)) {
        errors.push(`Definition of Done document is missing heading ${check.id} ${check.name}`);
      }
    }
    for (const token of ['N/A', 'pnpm governance:verify', 'Requirement', '工作镜像与桌面权威目录']) {
      if (!dod.includes(token)) errors.push(`Definition of Done document is missing token: ${token}`);
    }
  }
  const versionPath = path.join(
    repositoryRoot,
    'docs',
    'governance',
    'versioning-and-change-management.md',
  );
  if (existsSync(versionPath)) {
    const versioning = readFileSync(versionPath, 'utf8');
    for (const token of [
      '/api/v1',
      'schemaVersion',
      'expand-migrate-contract',
      'BREAKING CHANGE:',
      '0.1.0-SNAPSHOT',
      'V<positive_version>__<lower_snake_case>.sql',
      '不授权自动提交或推送',
    ]) {
      if (!versioning.includes(token)) errors.push(`Versioning document is missing token: ${token}`);
    }
  }
  const contributing = readFileSync(path.join(repositoryRoot, 'CONTRIBUTING.md'), 'utf8');
  for (const token of ['definition-of-done.md', 'versioning-and-change-management.md', 'governance:verify']) {
    if (!contributing.includes(token)) errors.push(`CONTRIBUTING.md is missing governance link/token: ${token}`);
  }
  const readme = readFileSync(path.join(repositoryRoot, 'README.md'), 'utf8');
  for (const token of ['统一 Definition of Done', '版本与变更管理规范', 'governance:verify']) {
    if (!readme.includes(token)) errors.push(`README.md is missing governance entry/token: ${token}`);
  }
}

function validatePackageScripts(policy, errors) {
  const packageDocument = readJson(path.join(repositoryRoot, 'package.json'));
  const scripts = packageDocument.scripts ?? {};
  if (!Array.isArray(policy.requiredPackageScripts)) {
    errors.push('requiredPackageScripts must be an array');
    return;
  }
  for (const script of policy.requiredPackageScripts) {
    if (typeof scripts[script] !== 'string' || !scripts[script]) {
      errors.push(`package.json is missing required script ${String(script)}`);
    }
  }
  if (scripts['governance:verify'] !== 'node scripts/governance/verify-governance.mjs') {
    errors.push('governance:verify must invoke the reviewed verifier directly');
  }
  if (scripts['governance:test'] !== 'node scripts/governance/test-governance-verifier.mjs') {
    errors.push('governance:test must invoke the reviewed negative-control test directly');
  }
  if (!scripts.verify?.includes('governance:verify') || !scripts.verify?.includes('governance:test')) {
    errors.push('pnpm verify must include governance:verify and governance:test');
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const policy = readJson(options.policy);
  const errors = [];
  if (policy.schemaVersion !== 1) errors.push('schemaVersion must be 1');
  if (!parseSemver(policy.policyVersion)) errors.push('policyVersion must be stable SemVer');

  if (!policy.branching || typeof policy.branching !== 'object') {
    errors.push('branching policy is required');
  } else {
    if (policy.branching.defaultBranch !== 'main') errors.push('default branch must be main');
    validateUniqueExactList(
      policy.branching.allowedTypes,
      expectedBranchTypes,
      'branching.allowedTypes',
      errors,
    );
    const branchPattern = compilePattern(policy.branching.pattern, 'branching.pattern', errors);
    validateExamples(
      branchPattern,
      policy.branching.validExamples,
      policy.branching.invalidExamples,
      'branch',
      errors,
    );
  }

  if (!policy.commits || typeof policy.commits !== 'object') {
    errors.push('commit policy is required');
  } else {
    validateUniqueExactList(
      policy.commits.allowedTypes,
      expectedCommitTypes,
      'commits.allowedTypes',
      errors,
    );
    if (policy.commits.maxHeaderLength !== 100) errors.push('commit maxHeaderLength must be 100');
    const commitPattern = compilePattern(policy.commits.headerPattern, 'commits.headerPattern', errors);
    validateExamples(
      commitPattern,
      policy.commits.validExamples,
      policy.commits.invalidExamples,
      'commit',
      errors,
      policy.commits.maxHeaderLength,
    );
  }

  validateVersions(policy, errors);
  validateContracts(policy, errors);
  validateDefinitionOfDone(policy, errors);
  validatePackageScripts(policy, errors);
  if (!options.skipDocuments) validateDocuments(policy, errors);

  if (errors.length > 0) {
    throw new Error(`Governance verification failed:\n- ${errors.join('\n- ')}`);
  }
  console.log(
    `Governance verification PASS: policy=${policy.policyVersion}, branches=${policy.branching.validExamples.length} positive/${policy.branching.invalidExamples.length} negative, ` +
      `commits=${policy.commits.validExamples.length} positive/${policy.commits.invalidExamples.length} negative, ` +
      `baseVersion=${policy.versions.currentBaseVersion}, DoD=${policy.definitionOfDone.requiredChecks.length}/12, documents=${policy.requiredDocuments.length}.`,
  );
}

main();
