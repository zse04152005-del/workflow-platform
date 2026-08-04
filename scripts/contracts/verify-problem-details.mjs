import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const expectedStandardMembers = ['type', 'title', 'status', 'detail', 'instance'];
const expectedExtensions = ['code', 'traceId', 'errors', 'retryable'];
const expectedOptionalExtensions = ['retryAfterSeconds'];
const expectedFieldMembers = ['field', 'code', 'message'];
const expectedFieldCodes = [
  'required',
  'invalid_format',
  'invalid_size',
  'out_of_range',
  'invalid_type',
  'invalid',
];
const expectedCategoryStatus = {
  VALIDATION: 400,
  AUTHENTICATION: 401,
  AUTHORIZATION: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  PRECONDITION_FAILED: 412,
  PRECONDITION_REQUIRED: 428,
  RATE_LIMIT: 429,
  DEPENDENCY: 503,
  INTERNAL: 500,
};
const expectedCatalog = [
  'COMMON_VALIDATION_INVALID_FIELD',
  'COMMON_VALIDATION_MALFORMED_REQUEST',
  'COMMON_VALIDATION_MISSING_PARAMETER',
  'COMMON_VALIDATION_TYPE_MISMATCH',
  'AUTH_AUTHENTICATION_REQUIRED',
  'AUTH_AUTHORIZATION_DENIED',
  'COMMON_NOT_FOUND_RESOURCE',
  'COMMON_CONFLICT_STATE',
  'DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH',
  'DEFINITION_PRECONDITION_REQUIRED_IF_MATCH',
  'COMMON_RATE_LIMIT_EXCEEDED',
  'CONNECTOR_DEPENDENCY_UNAVAILABLE',
  'COMMON_INTERNAL_UNEXPECTED',
];
const expectedForbiddenMembers = [
  'rejectedValue',
  'exception',
  'exceptionClass',
  'stackTrace',
  'sql',
  'rawPayload',
  'token',
  'password',
  'secret',
  'authorization',
  'cookie',
];

function parseArguments(argv) {
  const options = {
    contract: path.join(repositoryRoot, 'config', 'api', 'problem-details.json'),
    skipDocument: false,
    skipSources: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--contract') {
      const value = argv[index + 1];
      if (!value) throw new Error('--contract requires a path');
      options.contract = path.resolve(repositoryRoot, value);
      index += 1;
    } else if (argument === '--skip-document') {
      options.skipDocument = true;
    } else if (argument === '--skip-sources') {
      options.skipSources = true;
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

function exactArray(actual, expected, label, errors) {
  if (!Array.isArray(actual)) {
    errors.push(`${label} must be an array`);
    return;
  }
  if (new Set(actual).size !== actual.length) errors.push(`${label} contains duplicates`);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    errors.push(`${label} must equal ${expected.join(', ')}`);
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

function categoryOf(code, conventions) {
  const namespace = [...conventions.errorCodes.namespaces]
    .sort((left, right) => right.length - left.length)
    .find((candidate) => code.startsWith(`${candidate}_`));
  if (!namespace) return null;
  const remainder = code.slice(namespace.length + 1);
  return conventions.errorCodes.categories.find((category) => remainder.startsWith(`${category}_`)) ?? null;
}

function validateShape(contract, errors) {
  if (contract.schemaVersion !== 1) errors.push('schemaVersion must be 1');
  if (!/^1\.[0-9]+\.[0-9]+$/u.test(contract.contractVersion ?? '')) {
    errors.push('contractVersion must be stable 1.x SemVer');
  }
  if (contract.task !== 'G2-09' || contract.requirement !== 'REQ-CONTRACT-001') {
    errors.push('contract must be owned by G2-09 and REQ-CONTRACT-001');
  }
  if (contract.standard !== 'RFC 9457') errors.push('standard must be RFC 9457');
  if (contract.mediaType !== 'application/problem+json') {
    errors.push('mediaType must be application/problem+json');
  }
  if (
    typeof contract.typeBaseUri !== 'string' ||
    !contract.typeBaseUri.startsWith('https://') ||
    !contract.typeBaseUri.endsWith('/')
  ) {
    errors.push('typeBaseUri must be an absolute HTTPS URI ending in /');
  }
  exactArray(contract.problem?.requiredStandardMembers, expectedStandardMembers, 'problem.requiredStandardMembers', errors);
  exactArray(contract.problem?.requiredExtensions, expectedExtensions, 'problem.requiredExtensions', errors);
  exactArray(contract.problem?.optionalExtensions, expectedOptionalExtensions, 'problem.optionalExtensions', errors);
  if (
    contract.problem?.instancePolicy !== 'request-path-only' ||
    contract.problem?.queryAllowedInInstance !== false ||
    contract.problem?.additionalMembersAllowed !== false
  ) {
    errors.push('problem members must be closed and instance must contain request path only');
  }
}

function validateTrace(contract, errors) {
  const trace = contract.trace;
  const tracePattern = compile(trace?.pattern, 'trace.pattern', errors);
  const traceparentPattern = compile(trace?.traceparentPattern, 'trace.traceparentPattern', errors);
  if (tracePattern) {
    for (const invalid of [
      '0000000000000000000000000000000',
      '4BF92F3577B34DA6A3CE929D0E0E4736',
      'not-a-trace-id',
      '4bf92f3577b34da6a3ce929d0e0e47360',
    ]) {
      if (tracePattern.test(invalid)) errors.push(`trace.pattern invalid trace example accepted: ${invalid}`);
    }
    if (!tracePattern.test('4bf92f3577b34da6a3ce929d0e0e4736')) {
      errors.push('trace.pattern rejected the valid 32-character lowercase trace ID');
    }
  }
  if (
    traceparentPattern &&
    !traceparentPattern.test('00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01')
  ) {
    errors.push('trace.traceparentPattern rejected a valid W3C traceparent');
  }
  if (trace?.allZeroAllowed !== false) errors.push('all-zero trace IDs must be forbidden');
  exactArray(trace?.sourceOrder, ['traceparent', 'X-Correlation-ID', 'generated'], 'trace.sourceOrder', errors);
  if (
    trace?.responseHeader !== 'X-Trace-Id' ||
    trace?.invalidInboundAction !== 'ignore-and-generate' ||
    trace?.generatedBy !== 'application-cryptographic-random-128-bit'
  ) {
    errors.push('trace propagation and generation policy drifted');
  }
}

function validateValidation(contract, errors) {
  const validation = contract.validation;
  if (validation?.errorsProperty !== 'errors') errors.push('validation.errorsProperty must be errors');
  exactArray(validation?.fieldErrorMembers, expectedFieldMembers, 'validation.fieldErrorMembers', errors);
  exactArray(validation?.allowedFieldCodes, expectedFieldCodes, 'validation.allowedFieldCodes', errors);
  exactArray(validation?.order, expectedFieldMembers, 'validation.order', errors);
  const fieldPattern = compile(validation?.fieldPathPattern, 'validation.fieldPathPattern', errors);
  const codePattern = compile(validation?.fieldCodePattern, 'validation.fieldCodePattern', errors);
  if (fieldPattern) {
    for (const value of ['name', 'items[0].displayName', '_request']) {
      if (!fieldPattern.test(value)) errors.push(`field path valid example rejected: ${value}`);
    }
    for (const value of ['../../password', 'items[-1]', '0name']) {
      if (fieldPattern.test(value)) errors.push(`field path invalid example accepted: ${value}`);
    }
  }
  if (codePattern) {
    for (const code of expectedFieldCodes) {
      if (!codePattern.test(code)) errors.push(`field code rejected: ${code}`);
    }
    if (codePattern.test('INVALID')) errors.push('field code pattern accepted uppercase INVALID');
  }
  if (validation?.maxErrors !== 50) errors.push('validation.maxErrors must be 50');
  if (
    validation?.rejectedValueAllowed !== false ||
    validation?.frameworkMessageAllowed !== false ||
    validation?.messageSource !== 'curated-static-catalog'
  ) {
    errors.push('validation must forbid rejected values and framework messages and use curated messages');
  }
}

function validateSecurity(contract, errors) {
  if (
    contract.headers?.cacheControl !== 'no-store' ||
    contract.headers?.authenticationChallenge !== 'Bearer' ||
    contract.headers?.retryAfterHeader !== 'Retry-After'
  ) {
    errors.push('problem response header policy drifted');
  }
  const sanitization = contract.sanitization;
  exactArray(
    sanitization?.forbiddenResponseMembers,
    expectedForbiddenMembers,
    'sanitization.forbiddenResponseMembers',
    errors,
  );
  if (
    sanitization?.dynamicDetailAllowed !== false ||
    sanitization?.internalDetailSource !== 'static-catalog-only' ||
    sanitization?.responseMayContainStackTrace !== false
  ) {
    errors.push('responses must use static safe details and forbid stack traces');
  }
}

function validateCatalog(contract, conventions, errors) {
  if (JSON.stringify(contract.categoryStatus) !== JSON.stringify(expectedCategoryStatus)) {
    errors.push('categoryStatus mapping drifted');
  }
  if (!Array.isArray(contract.catalog)) {
    errors.push('catalog must be an array');
    return;
  }
  const codes = contract.catalog.map((entry) => entry.code);
  exactArray(codes, expectedCatalog, 'catalog codes', errors);
  const types = new Set();
  for (const entry of contract.catalog) {
    const category = categoryOf(entry.code ?? '', conventions);
    if (!new RegExp(conventions.errorCodes.pattern, 'u').test(entry.code ?? '') || !category) {
      errors.push(`catalog contains invalid stable code: ${String(entry.code)}`);
      continue;
    }
    if (entry.category !== category) {
      errors.push(`${entry.code} category must be ${category}`);
    }
    if (entry.status !== expectedCategoryStatus[category]) {
      errors.push(`${entry.code} status must match categoryStatus.${category}`);
    }
    if (
      typeof entry.type !== 'string' ||
      !entry.type.startsWith(contract.typeBaseUri) ||
      entry.type === contract.typeBaseUri
    ) {
      errors.push(`${entry.code} type must be a distinct URI below typeBaseUri`);
    }
    if (types.has(entry.type)) errors.push(`duplicate problem type URI: ${entry.type}`);
    types.add(entry.type);
    if (
      typeof entry.title !== 'string' ||
      !entry.title.trim() ||
      typeof entry.detail !== 'string' ||
      !entry.detail.trim()
    ) {
      errors.push(`${entry.code} title and detail must be non-empty static strings`);
    }
    const shouldRetry = category === 'RATE_LIMIT' || category === 'DEPENDENCY';
    if (entry.retryable !== shouldRetry) {
      errors.push(`${entry.code} retryable must be ${shouldRetry}`);
    }
    if (/password\s*=|token\s*=|select\s+.+\s+from|exception\b|stack\s*trace/iu.test(entry.detail ?? '')) {
      errors.push(`${entry.code} detail contains forbidden sensitive or internal text`);
    }
  }
  const internal = contract.catalog.find((entry) => entry.code === 'COMMON_INTERNAL_UNEXPECTED');
  if (internal?.detail !== 'An unexpected error occurred.') {
    errors.push('COMMON_INTERNAL_UNEXPECTED must retain the static generic detail');
  }
}

function sourceFile(relative, label, errors) {
  const file = path.resolve(repositoryRoot, relative ?? '');
  if (!file.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(file)) {
    errors.push(`${label} source binding does not exist: ${String(relative)}`);
    return null;
  }
  return readFileSync(file, 'utf8');
}

function validateSources(contract, errors) {
  const bindings = contract.sourceBindings ?? {};
  const problemCode = sourceFile(bindings.problemCode, 'problemCode', errors);
  const fieldError = sourceFile(bindings.fieldError, 'fieldError', errors);
  const factory = sourceFile(bindings.responseFactory, 'responseFactory', errors);
  const handler = sourceFile(bindings.exceptionHandler, 'exceptionHandler', errors);
  const mapper = sourceFile(bindings.validationMapper, 'validationMapper', errors);
  if (problemCode) {
    const javaCodes = [...problemCode.matchAll(/^\s{4}([A-Z][A-Z0-9_]*)\(/gmu)].map((match) => match[1]);
    if (JSON.stringify(javaCodes) !== JSON.stringify(expectedCatalog)) {
      errors.push('ProblemCode enum must exactly match the machine catalog order');
    }
    for (const entry of contract.catalog ?? []) {
      for (const token of [entry.code, entry.title, entry.detail]) {
        if (!problemCode.includes(token)) errors.push(`ProblemCode enum is missing catalog token: ${token}`);
      }
    }
  }
  if (fieldError && (fieldError.includes('rejectedValue') || !fieldError.includes('String field, String code, String message'))) {
    errors.push('FieldValidationError must contain only field/code/message and no rejectedValue');
  }
  if (factory) {
    for (const token of [
      'MediaType.APPLICATION_PROBLEM_JSON',
      'CacheControl.noStore()',
      'request.getRequestURI()',
      'problem.setProperty("code"',
      'problem.setProperty("traceId"',
      'problem.setProperty("errors"',
      'problem.setProperty("retryable"',
      'HttpHeaders.WWW_AUTHENTICATE',
      'HttpHeaders.RETRY_AFTER',
    ]) {
      if (!factory.includes(token)) errors.push(`responseFactory is missing required token: ${token}`);
    }
    for (const forbidden of ['getQueryString()', 'getParameterMap()', 'getParameter(']) {
      if (factory.includes(forbidden)) errors.push(`responseFactory uses forbidden request data: ${forbidden}`);
    }
  }
  if (handler) {
    for (const token of [
      'MethodArgumentNotValidException',
      'HttpMessageNotReadableException',
      'MissingServletRequestParameterException',
      'MethodArgumentTypeMismatchException',
      'COMMON_INTERNAL_UNEXPECTED',
    ]) {
      if (!handler.includes(token)) errors.push(`exceptionHandler is missing required mapping: ${token}`);
    }
    for (const forbidden of ['getRejectedValue()', 'getDefaultMessage()', 'getMessage()']) {
      if (handler.includes(forbidden)) errors.push(`exceptionHandler leaks dynamic data via ${forbidden}`);
    }
  }
  if (mapper) {
    for (const token of ['MAX_ERRORS = 50', 'invalid_format', 'invalid_size', 'out_of_range', 'invalid_type']) {
      if (!mapper.includes(token)) errors.push(`validationMapper is missing curated token: ${token}`);
    }
    for (const forbidden of ['getRejectedValue()', 'getDefaultMessage()']) {
      if (mapper.includes(forbidden)) errors.push(`validationMapper leaks framework data via ${forbidden}`);
    }
  }
}

function validateDocument(contract, errors) {
  const file = path.resolve(repositoryRoot, contract.document ?? '');
  if (!file.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(file)) {
    errors.push(`problem contract document does not exist: ${String(contract.document)}`);
    return;
  }
  const document = readFileSync(file, 'utf8');
  for (const token of [
    'RFC 9457',
    'application/problem+json',
    'traceId',
    'rejectedValue',
    'COMMON_INTERNAL_UNEXPECTED',
    'pnpm contracts:problem:verify',
  ]) {
    if (!document.includes(token)) errors.push(`problem contract document is missing token: ${token}`);
  }
}

function validatePackageScripts(errors) {
  const scripts = readJson(path.join(repositoryRoot, 'package.json')).scripts ?? {};
  if (scripts['contracts:problem:verify'] !== 'node scripts/contracts/verify-problem-details.mjs') {
    errors.push('package.json contracts:problem:verify script is missing or drifted');
  }
  if (scripts['contracts:problem:test'] !== 'node scripts/contracts/test-problem-details-verifier.mjs') {
    errors.push('package.json contracts:problem:test script is missing or drifted');
  }
  if (!scripts.verify?.includes('contracts:problem:verify') || !scripts.verify?.includes('contracts:problem:test')) {
    errors.push('pnpm verify must include both Problem Details gates');
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const contract = readJson(options.contract);
  const conventions = readJson(path.join(repositoryRoot, 'config', 'domain', 'conventions.json'));
  const errors = [];
  validateShape(contract, errors);
  validateTrace(contract, errors);
  validateValidation(contract, errors);
  validateSecurity(contract, errors);
  validateCatalog(contract, conventions, errors);
  if (!options.skipSources) validateSources(contract, errors);
  validatePackageScripts(errors);
  if (!options.skipDocument) validateDocument(contract, errors);
  if (errors.length > 0) {
    throw new Error(`Problem Details verification failed:\n- ${errors.join('\n- ')}`);
  }
  console.log(
    `Problem Details verification PASS: standard=${contract.standard}, mediaType=${contract.mediaType}, ` +
      `catalog=${contract.catalog.length}, categories=${Object.keys(contract.categoryStatus).length}, ` +
      `requiredMembers=${contract.problem.requiredStandardMembers.length + contract.problem.requiredExtensions.length}, ` +
      `fieldCodes=${contract.validation.allowedFieldCodes.length}, maxErrors=${contract.validation.maxErrors}.`,
  );
}

main();
