import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(root, 'scripts', 'runtime', 'verify-reliability-kernel.mjs');
const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g5-verifier-'));
const sources = {
  contract: path.join(root, 'config', 'runtime', 'reliability-kernel.json'),
  transitionStore: path.join(root, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeTransitionStore.java'),
  workerStore: path.join(root, 'worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'internal', 'JdbcWorkerTaskStore.java'),
  payloadStore: path.join(root, 'payload-storage', 'src', 'main', 'java', 'com', 'workflowplatform', 'payload', 'MinioTenantPayloadStore.java'),
  retryPlanner: path.join(root, 'worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'internal', 'WorkerRetryPlanner.java'),
  schedulingStore: path.join(root, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeSchedulingStore.java'),
  redactor: path.join(root, 'shared-contracts', 'src', 'main', 'java', 'com', 'workflowplatform', 'contracts', 'security', 'SensitiveTextRedactor.java'),
  deadStore: path.join(root, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcDeadLetterDispositionStore.java'),
  openapi: path.join(root, 'api', 'openapi', 'workflow-platform.v1.openapi.json'),
  e2eTest: path.join(root, 'test-support', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'RuntimeHappyPathE2EIT.java'),
  payloadTest: path.join(root, 'payload-storage', 'src', 'test', 'java', 'com', 'workflowplatform', 'payload', 'MinioTenantPayloadStoreIT.java'),
  managedHttpTest: path.join(root, 'worker', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'ManagedHttpWorkerNodeExecutorTests.java'),
  workerStoreTest: path.join(root, 'worker', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'internal', 'JdbcWorkerTaskStoreIT.java'),
};
let mutationCount = 0;

function flag(name) {
  return `--${name.replace(/[A-Z]/gu, (letter) => `-${letter.toLowerCase()}`)}`;
}
function output(result) { return `${result.stdout ?? ''}${result.stderr ?? ''}`; }
function run(overrides = {}) {
  const arguments_ = [verifier];
  for (const [name, target] of Object.entries(overrides)) arguments_.push(flag(name), target);
  return spawnSync(process.execPath, arguments_, {
    cwd: root,
    encoding: 'utf8',
    maxBuffer: 20 * 1024 * 1024,
    windowsHide: true,
  });
}
function reject(name, kind, mutate, expected) {
  mutationCount += 1;
  const source = readFileSync(sources[kind], 'utf8');
  const mutated = mutate(source);
  if (mutated === source) throw new Error(`${name} mutation did not change its source`);
  const target = path.join(temporaryRoot, `${name}${path.extname(sources[kind])}`);
  writeFileSync(target, mutated, 'utf8');
  const result = run({ [kind]: target });
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expected)) {
    throw new Error(`${name} mutation was not rejected (${expected}):\n${combined}`);
  }
}

try {
  const positive = run();
  if (positive.status !== 0) throw new Error(`positive G5 verifier failed:\n${output(positive)}`);
  reject('task-scope-drift', 'contract', (source) => {
    const contract = JSON.parse(source);
    contract.verifiedTasks.pop();
    return `${JSON.stringify(contract, null, 2)}\n`;
  }, 'G5-01..G5-13 verified task set drift');
  reject('attempt-counter-on-retry', 'transitionStore', (source) => source.replace("? = 'RUNNING' THEN attempt_no + 1", "? = 'RETRY_WAIT' THEN attempt_no + 1"), "? = 'RUNNING' THEN attempt_no + 1");
  reject('expired-fence-accepted', 'workerStore', (source) => source.replaceAll('lease_expire_at > ?', 'lease_expire_at IS NOT NULL'), 'lease_expire_at > ?');
  reject('exponential-retry-removed', 'retryPlanner', (source) => source.replace('case "exponential"', 'case "removed-exponential"'), 'case "exponential"');
  reject('processed-event-not-transactional', 'workerStore', (source) => source.replace('INSERT INTO public.processed_event', 'INSERT INTO public.untracked_event'), 'INSERT INTO public.processed_event');
  reject('lease-expiry-not-closed', 'schedulingStore', (source) => source.replace("UPDATE public.worker_lease SET status = 'EXPIRED'", "UPDATE public.worker_lease SET status = 'ACTIVE'"), "UPDATE public.worker_lease SET status = 'EXPIRED'");
  reject('authorization-not-redacted', 'redactor', (source) => source.replace('authorization|proxy-authorization|', 'proxy-authorization|'), 'authorization|proxy-authorization|');
  reject('dead-letter-not-requeued', 'deadStore', (source) => source.replace("SET status = 'REQUEUED'", "SET status = 'RESOLVED'"), "SET status = 'REQUEUED'");
  reject('dead-letter-api-removed', 'openapi', (source) => {
    const api = JSON.parse(source);
    delete api.paths['/api/v1/dead-letters/{deadLetterId}/redispatch'];
    return `${JSON.stringify(api, null, 2)}\n`;
  }, 'G5-08 OpenAPI operation is missing');
  reject('crash-recovery-e2e-removed', 'e2eTest', (source) => source.replace('killedWorkerLeaseIsRecoveredAndItsLateResultIsFenced', 'recoveryScenarioRemoved'), 'killedWorkerLeaseIsRecoveredAndItsLateResultIsFenced');
  reject('payload-tenant-authorization-removed', 'payloadStore', (source) => source.replace('payload reference does not belong to the requesting tenant', 'tenant check removed'), 'payload reference does not belong to the requesting tenant');
  reject('payload-content-digest-removed', 'payloadStore', (source) => source.replace('!identity.digest().equals(PayloadDigests.sha256(payload))', 'false'), '!identity.digest().equals(PayloadDigests.sha256(payload))');
  reject('payload-expiry-delete-removed', 'payloadStore', (source) => source.replace('client.removeObject(', 'client.statObject('), 'client.removeObject(');
  reject('real-minio-negative-tests-removed', 'payloadTest', (source) => source.replace('enforcesArchiveAndDeleteBoundariesAgainstRealObjectTagsAndRemoval', 'lifecycleScenarioRemoved'), 'enforcesArchiveAndDeleteBoundariesAgainstRealObjectTagsAndRemoval');
  reject('database-outage-test-removed', 'workerStoreTest', (source) => source.replace('realDatabasePauseRollsBackWithoutHalfCommitAndRecoversExactlyOnce', 'databaseOutageScenarioRemoved'), 'realDatabasePauseRollsBackWithoutHalfCommitAndRecoversExactlyOnce');
  reject('network-timeout-test-removed', 'managedHttpTest', (source) => source.replace('realTlsPeerStallTimesOutWithoutProducingASuccess', 'networkTimeoutScenarioRemoved'), 'realTlsPeerStallTimesOutWithoutProducingASuccess');
  reject('combined-minio-kafka-e2e-removed', 'e2eTest', (source) => source.replaceAll('duplicateTaskEvent', 'removedDuplicateTaskEvent'), 'duplicateTaskEvent');
  console.log(`G5 reliability verifier tests PASS: positive contract plus ${mutationCount} reverse mutations.`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
