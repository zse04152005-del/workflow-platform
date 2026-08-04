import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'contracts', 'verify-problem-details.mjs');
const source = path.join(repositoryRoot, 'config', 'api', 'problem-details.json');
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-problem-contract-test-'));
let mutationCount = 0;

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function run(contractPath, full = false) {
  const args = [verifier, '--contract', contractPath];
  if (!full) args.push('--skip-document');
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    windowsHide: true,
  });
}

function writeCase(name, mutate) {
  const contract = JSON.parse(readFileSync(source, 'utf8'));
  mutate(contract);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(contract, null, 2)}\n`, 'utf8');
  return target;
}

function expectFailure(name, mutate, expectedMessage) {
  mutationCount += 1;
  const result = run(writeCase(name, mutate));
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expectedMessage)) {
    throw new Error(`${name} was not blocked as expected (${expectedMessage}):\n${combined}`);
  }
}

try {
  const positive = run(source, true);
  if (positive.status !== 0) throw new Error(`Positive Problem Details contract failed:\n${output(positive)}`);

  expectFailure('wrong-standard', (contract) => { contract.standard = 'RFC 7807'; }, 'standard must be RFC 9457');
  expectFailure('wrong-media-type', (contract) => { contract.mediaType = 'application/json'; }, 'mediaType must be application/problem+json');
  expectFailure('missing-standard-member', (contract) => { contract.problem.requiredStandardMembers.pop(); }, 'problem.requiredStandardMembers must equal');
  expectFailure('open-members', (contract) => { contract.problem.additionalMembersAllowed = true; }, 'problem members must be closed');
  expectFailure('broad-trace-pattern', (contract) => { contract.trace.pattern = '^.+$'; }, 'trace.pattern invalid trace example accepted');
  expectFailure('zero-trace-allowed', (contract) => { contract.trace.allZeroAllowed = true; }, 'all-zero trace IDs must be forbidden');
  expectFailure('trace-source-order', (contract) => { contract.trace.sourceOrder.reverse(); }, 'trace.sourceOrder must equal');
  expectFailure('rejected-value', (contract) => { contract.validation.rejectedValueAllowed = true; }, 'validation must forbid rejected values');
  expectFailure('field-shape', (contract) => { contract.validation.fieldErrorMembers.push('rejectedValue'); }, 'validation.fieldErrorMembers must equal');
  expectFailure('unbounded-errors', (contract) => { contract.validation.maxErrors = 1000; }, 'validation.maxErrors must be 50');
  expectFailure('missing-sensitive-member', (contract) => { contract.sanitization.forbiddenResponseMembers.pop(); }, 'sanitization.forbiddenResponseMembers must equal');
  expectFailure('duplicate-code', (contract) => { contract.catalog[1].code = contract.catalog[0].code; }, 'catalog codes contains duplicates');
  expectFailure('invalid-code', (contract) => { contract.catalog[0].code = 'bad-code'; }, 'catalog contains invalid stable code');
  expectFailure('status-mismatch', (contract) => { contract.catalog[6].status = 200; }, 'status must match categoryStatus.NOT_FOUND');
  expectFailure('insecure-type', (contract) => { contract.catalog[0].type = 'http://example.test/problem'; }, 'type must be a distinct URI below typeBaseUri');
  expectFailure('wrong-retryability', (contract) => { contract.catalog[7].retryable = true; }, 'retryable must be false');
  expectFailure('internal-leak', (contract) => { contract.catalog[10].detail = 'SQLException password=secret'; }, 'detail contains forbidden sensitive or internal text');
  expectFailure('missing-source', (contract) => { contract.sourceBindings.exceptionHandler = 'missing/handler.java'; }, 'exceptionHandler source binding does not exist');

  console.log(`Problem Details verifier tests PASS: positive contract plus ${mutationCount} reverse mutations.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
