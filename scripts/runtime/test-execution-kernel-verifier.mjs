import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(root, 'scripts', 'runtime', 'verify-execution-kernel.mjs');
const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g4-execution-verifier-'));

function run(...arguments_) {
  return spawnSync(process.execPath, [verifier, ...arguments_], { cwd: root, encoding: 'utf8' });
}
function expectFailure(name, flag, source, mutate, fragment) {
  const target = path.join(temporaryRoot, `${name}${path.extname(source)}`);
  writeFileSync(target, mutate(readFileSync(source, 'utf8')), 'utf8');
  const result = run(flag, target);
  const output = `${result.stdout}${result.stderr}`;
  if (result.status === 0 || !output.includes(fragment)) {
    throw new Error(`${name} mutation was not rejected with ${fragment}:\n${output}`);
  }
}

try {
  const positive = run();
  if (positive.status !== 0) throw new Error(`positive execution-kernel verifier failed:\n${positive.stdout}${positive.stderr}`);

  const contract = path.join(root, 'config', 'runtime', 'execution-kernel.json');
  expectFailure('contract-artifact', '--contract', contract, (source) => source.replace('RuntimeQueryController+JdbcRuntimeQueryStore', 'RuntimeQueryService'), 'G4-16 artifact binding drift');

  const worker = path.join(root, 'worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'WorkerExecutionService.java');
  expectFailure('virtual-thread-boundary', '--worker-execution', worker, (source) => source.replace('Executors.newVirtualThreadPerTaskExecutor()', 'Executors.newCachedThreadPool()'), 'G4-10 bounded node execution is missing executable evidence');

  const controller = path.join(root, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeQueryController.java');
  expectFailure('query-limit', '--query-controller', controller, (source) => source.replaceAll('@Min(1) @Max(200)', ''), 'G4-16 query routes and bounds is missing executable evidence');

  const openapi = path.join(root, 'api', 'openapi', 'workflow-platform.v1.openapi.json');
  expectFailure('query-openapi', '--openapi', openapi, (source) => source.replace('"operationId": "listWorkflowInstanceAttempts"', '"operationId": "wrongOperation"'), 'operationId drift');

  console.log('G4-04..G4-16 execution-kernel verifier negative tests PASS: 4/4 implementation drift mutations rejected.');
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
