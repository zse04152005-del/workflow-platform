import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const expectedErrorNamespaces = [
  'COMMON',
  'AUTH',
  'TENANT',
  'DEFINITION',
  'RUNTIME',
  'SCHEDULER',
  'HUMAN_TASK',
  'WORKER',
  'CONNECTOR',
];
const expectedErrorCategories = [
  'VALIDATION',
  'AUTHENTICATION',
  'AUTHORIZATION',
  'NOT_FOUND',
  'CONFLICT',
  'PRECONDITION_FAILED',
  'PRECONDITION_REQUIRED',
  'RATE_LIMIT',
  'DEPENDENCY',
  'INTERNAL',
];
const expectedAuditFields = ['created_at', 'created_by', 'updated_at', 'updated_by', 'version'];
const expectedDeletionStrategies = [
  'lifecycle-status',
  'soft-delete',
  'revocation',
  'immutable-history',
  'retention-purge',
  'regulated-erasure',
];
const expectedForbiddenJavaTypes = ['java.time.LocalDateTime', 'java.sql.Timestamp'];
const expectedForbiddenSqlTokens = ['timestamp without time zone', 'serial', 'bigserial'];

function parseArguments(argv) {
  const options = {
    conventions: path.join(repositoryRoot, 'config', 'domain', 'conventions.json'),
    skipDocument: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--skip-document') {
      options.skipDocument = true;
    } else if (argument === '--conventions') {
      const value = argv[index + 1];
      if (!value) throw new Error('--conventions requires a path');
      options.conventions = path.resolve(repositoryRoot, value);
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

function compile(pattern, label, errors) {
  if (typeof pattern !== 'string' || !pattern.startsWith('^') || !pattern.endsWith('$')) {
    errors.push(`${label} must be an anchored regular expression`);
    return null;
  }
  try {
    return new RegExp(pattern, 'u');
  } catch (error) {
    errors.push(`${label} is invalid: ${error.message}`);
    return null;
  }
}

function validateUniqueExact(actual, expected, label, errors) {
  if (!Array.isArray(actual)) {
    errors.push(`${label} must be an array`);
    return;
  }
  if (new Set(actual).size !== actual.length) errors.push(`${label} contains duplicate entries`);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    errors.push(`${label} must equal ${expected.join(', ')}`);
  }
}

function validatePatternExamples(regex, valid, invalid, label, errors, semantic = () => true) {
  if (!regex) return;
  if (!Array.isArray(valid) || valid.length === 0 || !Array.isArray(invalid) || invalid.length === 0) {
    errors.push(`${label} must have non-empty validExamples and invalidExamples`);
    return;
  }
  for (const value of valid) {
    if (typeof value !== 'string' || !regex.test(value) || !semantic(value)) {
      errors.push(`${label} valid example was rejected: ${String(value)}`);
    }
  }
  for (const value of invalid) {
    if (typeof value === 'string' && regex.test(value) && semantic(value)) {
      errors.push(`${label} invalid example was accepted: ${value}`);
    }
  }
}

function validCalendarInstant(value) {
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) return false;
  const normalized = new Date(parsed).toISOString();
  const withoutZeros = normalized.replace(/\.000Z$/u, 'Z');
  if (!value.includes('.')) return value === withoutZeros;
  return true;
}

function validateIdentifiers(conventions, errors) {
  const internal = conventions.identifiers?.internal;
  if (
    internal?.algorithm !== 'UUIDv7' ||
    internal?.standard !== 'RFC 9562' ||
    internal?.generatedBy !== 'application-before-persist' ||
    internal?.databaseType !== 'uuid' ||
    internal?.jsonFormat !== 'lowercase-canonical-hyphenated' ||
    internal?.orderingGuarantee !== 'index-locality-only-not-business-order'
  ) {
    errors.push('internal identifier policy must be application-generated canonical RFC 9562 UUIDv7 stored as uuid');
  }
  const uuidPattern = compile(internal?.pattern, 'identifiers.internal.pattern', errors);
  validatePatternExamples(
    uuidPattern,
    internal?.validExamples,
    internal?.invalidExamples,
    'UUIDv7',
    errors,
  );
  const idempotency = conventions.identifiers?.idempotencyKeys;
  if (
    idempotency?.scope !== 'tenant-operation-resource' ||
    idempotency?.transportMaxLength !== 128 ||
    idempotency?.opaque !== true ||
    !idempotency?.storage?.startsWith('sha256-')
  ) {
    errors.push('idempotency key policy must be opaque, <=128, tenant-operation-resource scoped, and SHA-256 backed');
  }
  if (conventions.identifiers?.businessKeys?.scope !== 'tenant-and-workflow') {
    errors.push('business keys must be tenant-and-workflow scoped');
  }
  if (conventions.identifiers?.externalReferences?.databaseType !== 'varchar') {
    errors.push('external references must remain varchar and must not be coerced to UUID');
  }
}

function validateTime(conventions, errors) {
  const time = conventions.time;
  if (
    time?.javaType !== 'java.time.Instant' ||
    time?.databaseType !== 'timestamp(6) with time zone' ||
    time?.databaseAlias !== 'timestamptz(6)' ||
    time?.precision !== 'microseconds' ||
    time?.jsonFormat !== 'RFC3339-UTC-Z' ||
    time?.businessTimezone !== 'IANA-TZDB-zone-id-required' ||
    time?.intervalConvention !== 'start-inclusive-end-exclusive' ||
    time?.applicationClock !== 'injected-java.time.Clock' ||
    time?.implicitDslNowAllowed !== false
  ) {
    errors.push('time policy must use Instant/timestamptz(6)/UTC-Z/IANA zones/injected Clock/half-open intervals');
  }
  const timePattern = compile(time?.jsonPattern, 'time.jsonPattern', errors);
  validatePatternExamples(
    timePattern,
    time?.validExamples,
    time?.invalidExamples,
    'time',
    errors,
    validCalendarInstant,
  );
}

function validateEnums(conventions, errors) {
  const enums = conventions.enums;
  if (
    enums?.unknownRequiredValueAction !== 'reject-with-contract-error' ||
    enums?.unknownEventSchemaAction !== 'quarantine-and-alert' ||
    enums?.unknownOptionalFieldAction !== 'ignore' ||
    enums?.renameOrRemoveWithinMajor !== 'forbidden' ||
    enums?.persistAs !== 'varchar-with-check-or-reference-table' ||
    enums?.ordinalPersistenceAllowed !== false ||
    enums?.fallbackToFirstValueAllowed !== false
  ) {
    errors.push('enum policy must reject unknown required values, quarantine unknown schemas, ignore optional fields, and forbid ordinal/fallback behavior');
  }
  const statusPattern = compile(enums?.status?.pattern, 'enums.status.pattern', errors);
  validatePatternExamples(
    statusPattern,
    enums?.status?.validExamples,
    enums?.status?.invalidExamples,
    'status enum',
    errors,
    (value) => value !== 'UNKNOWN',
  );
  const discriminatorPattern = compile(
    enums?.discriminator?.pattern,
    'enums.discriminator.pattern',
    errors,
  );
  validatePatternExamples(
    discriminatorPattern,
    enums?.discriminator?.validExamples,
    enums?.discriminator?.invalidExamples,
    'discriminator enum',
    errors,
  );
}

function errorCodeIsValid(code, errorCodes) {
  const namespace = [...errorCodes.namespaces]
    .sort((left, right) => right.length - left.length)
    .find((candidate) => code.startsWith(`${candidate}_`));
  if (!namespace) return false;
  const remainder = code.slice(namespace.length + 1);
  return errorCodes.categories.some((category) => remainder.startsWith(`${category}_`));
}

function validateErrorCodes(conventions, errors) {
  const errorCodes = conventions.errorCodes;
  validateUniqueExact(errorCodes?.namespaces, expectedErrorNamespaces, 'errorCodes.namespaces', errors);
  validateUniqueExact(errorCodes?.categories, expectedErrorCategories, 'errorCodes.categories', errors);
  const pattern = compile(errorCodes?.pattern, 'errorCodes.pattern', errors);
  validatePatternExamples(
    pattern,
    errorCodes?.validExamples,
    errorCodes?.invalidExamples,
    'error code',
    errors,
    (value) => errorCodeIsValid(value, errorCodes),
  );
  if (
    errorCodes?.stableWithinMajor !== true ||
    errorCodes?.reuseAllowed !== false ||
    errorCodes?.httpMappingOwnedBy !== 'G2-09' ||
    errorCodes?.sensitiveDetailsAllowed !== false
  ) {
    errors.push('error codes must be stable/non-reusable/non-sensitive, with HTTP mapping owned by G2-09');
  }
}

function validateAudit(conventions, errors) {
  const audit = conventions.audit;
  validateUniqueExact(audit?.mutableRecordFields, expectedAuditFields, 'audit.mutableRecordFields', errors);
  validateUniqueExact(
    audit?.softDeleteFields,
    ['deleted_at', 'deleted_by', 'deletion_reason'],
    'audit.softDeleteFields',
    errors,
  );
  if (
    audit?.tenantOwnedField !== 'tenant_id' ||
    audit?.privilegedReasonField !== 'reason' ||
    audit?.actorReferenceFormat !== '<actor_type>:<stable_subject_id>' ||
    audit?.systemActorPrefix !== 'system:' ||
    audit?.optimisticVersion?.databaseType !== 'bigint' ||
    audit?.optimisticVersion?.initialValue !== 0 ||
    audit?.optimisticVersion?.increment !== 1 ||
    audit?.optimisticVersion?.clientWritable !== false ||
    audit?.secretOrSensitivePayloadAllowed !== false
  ) {
    errors.push('audit policy must pin tenant/reason/actor/version fields and forbid sensitive payloads');
  }
  const requiredAuditLogFields = [
    'tenant_id',
    'actor_type',
    'actor_id',
    'action',
    'resource_type',
    'resource_id',
    'occurred_at',
    'reason',
    'trace_id',
    'source_ip',
    'before_digest_or_redacted',
    'after_digest_or_redacted',
  ];
  validateUniqueExact(audit?.auditLogFields, requiredAuditLogFields, 'audit.auditLogFields', errors);
}

function validateDeletion(conventions, errors) {
  const deletion = conventions.deletion;
  if (
    deletion?.defaultStrategy !== 'explicit-lifecycle-not-generic-soft-delete' ||
    deletion?.hardDeleteViaOrdinaryApiAllowed !== false
  ) {
    errors.push('deletion must use explicit lifecycle strategies and forbid ordinary API hard deletes');
  }
  if (!Array.isArray(deletion?.strategies)) {
    errors.push('deletion.strategies must be an array');
    return;
  }
  const ids = deletion.strategies.map((strategy) => strategy.id);
  if (new Set(ids).size !== ids.length) errors.push('deletion.strategies contains duplicate IDs');
  if (JSON.stringify(ids) !== JSON.stringify(expectedDeletionStrategies)) {
    errors.push(`deletion.strategies must equal ${expectedDeletionStrategies.join(', ')}`);
  }
  const entities = new Set();
  for (const strategy of deletion.strategies) {
    if (!Array.isArray(strategy.appliesTo) || strategy.appliesTo.length === 0) {
      errors.push(`${strategy.id} must have appliesTo examples`);
    } else {
      for (const entity of strategy.appliesTo) {
        if (entities.has(entity)) errors.push(`deletion entity assigned to multiple strategies: ${entity}`);
        entities.add(entity);
      }
    }
    if (typeof strategy.rule !== 'string' || !strategy.rule.trim()) {
      errors.push(`${strategy.id} must have a rule`);
    }
  }
}

function collectFiles(root, suffix) {
  if (!existsSync(root)) return [];
  const files = [];
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) visit(target);
      else if (entry.isFile() && entry.name.endsWith(suffix)) files.push(target);
    }
  };
  visit(root);
  return files;
}

function validateSourceGuards(conventions, errors) {
  const guards = conventions.sourceGuards;
  validateUniqueExact(
    guards?.forbiddenJavaTypes,
    expectedForbiddenJavaTypes,
    'sourceGuards.forbiddenJavaTypes',
    errors,
  );
  validateUniqueExact(
    guards?.forbiddenSqlTokens,
    expectedForbiddenSqlTokens,
    'sourceGuards.forbiddenSqlTokens',
    errors,
  );
  if (!Array.isArray(guards?.scanRoots) || guards.scanRoots.length !== 3) {
    errors.push('sourceGuards.scanRoots must contain the three Java source roots');
  } else {
    for (const sourceRoot of guards.scanRoots) {
      const absolute = path.resolve(repositoryRoot, sourceRoot);
      if (!absolute.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(absolute)) {
        errors.push(`source guard root does not exist: ${sourceRoot}`);
        continue;
      }
      for (const file of collectFiles(absolute, '.java')) {
        const content = readFileSync(file, 'utf8');
        for (const forbidden of expectedForbiddenJavaTypes) {
          if (content.includes(forbidden)) {
            errors.push(`forbidden Java time type ${forbidden} in ${path.relative(repositoryRoot, file)}`);
          }
        }
      }
    }
  }
  const migrationRoot = path.resolve(repositoryRoot, guards?.migrationRoot ?? '');
  if (!migrationRoot.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(migrationRoot)) {
    errors.push(`migration guard root does not exist: ${String(guards?.migrationRoot)}`);
  } else {
    for (const file of collectFiles(migrationRoot, '.sql')) {
      const content = readFileSync(file, 'utf8').toLowerCase();
      for (const forbidden of expectedForbiddenSqlTokens) {
        const pattern = new RegExp(`\\b${forbidden.replaceAll(' ', '\\s+')}\\b`, 'u');
        if (pattern.test(content)) {
          errors.push(`forbidden SQL token ${forbidden} in ${path.relative(repositoryRoot, file)}`);
        }
      }
    }
  }
}

function validateDocument(conventions, errors) {
  const file = path.resolve(repositoryRoot, conventions.document ?? '');
  if (!file.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(file)) {
    errors.push(`domain conventions document does not exist: ${String(conventions.document)}`);
    return;
  }
  const document = readFileSync(file, 'utf8');
  for (const token of [
    'RFC 9562 UUIDv7',
    'timestamp(6) with time zone',
    'IANA TZDB',
    'UPPER_SNAKE_CASE',
    '<DOMAIN>_<CATEGORY>_<DETAIL>',
    'created_at',
    'explicit-lifecycle-not-generic-soft-delete',
    'regulated-erasure',
    'G2-09',
    'G4-01',
  ]) {
    if (!document.includes(token)) errors.push(`domain conventions document is missing token: ${token}`);
  }
}

function validatePackageScripts(errors) {
  const scripts = readJson(path.join(repositoryRoot, 'package.json')).scripts ?? {};
  if (scripts['domain:verify'] !== 'node scripts/domain/verify-domain-conventions.mjs') {
    errors.push('package.json domain:verify script is missing or drifted');
  }
  if (scripts['domain:test'] !== 'node scripts/domain/test-domain-conventions.mjs') {
    errors.push('package.json domain:test script is missing or drifted');
  }
  if (!scripts.verify?.includes('domain:verify') || !scripts.verify?.includes('domain:test')) {
    errors.push('pnpm verify must include domain:verify and domain:test');
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const conventions = readJson(options.conventions);
  const errors = [];
  if (conventions.schemaVersion !== 1) errors.push('schemaVersion must be 1');
  if (!/^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/u.test(conventions.policyVersion ?? '')) {
    errors.push('policyVersion must be stable SemVer');
  }
  if (conventions.task !== 'G2-02') errors.push('domain conventions must remain linked to G2-02');
  validateIdentifiers(conventions, errors);
  validateTime(conventions, errors);
  validateEnums(conventions, errors);
  validateErrorCodes(conventions, errors);
  validateAudit(conventions, errors);
  validateDeletion(conventions, errors);
  validateSourceGuards(conventions, errors);
  validatePackageScripts(errors);
  if (!options.skipDocument) validateDocument(conventions, errors);

  if (errors.length > 0) {
    throw new Error(`Domain conventions verification failed:\n- ${errors.join('\n- ')}`);
  }
  console.log(
    `Domain conventions verification PASS: UUIDv7=${conventions.identifiers.internal.validExamples.length} fixtures, time=${conventions.time.validExamples.length} fixtures, ` +
      `errorNamespaces=${conventions.errorCodes.namespaces.length}, errorCategories=${conventions.errorCodes.categories.length}, auditFields=${conventions.audit.mutableRecordFields.length}, ` +
      `deletionStrategies=${conventions.deletion.strategies.length}, forbiddenJavaTypes=${conventions.sourceGuards.forbiddenJavaTypes.length}, forbiddenSqlTokens=${conventions.sourceGuards.forbiddenSqlTokens.length}.`,
  );
}

main();
