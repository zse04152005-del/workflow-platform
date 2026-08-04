import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const file = (...parts) => path.join(root, ...parts);
const defaults = {
  contract: file('config', 'runtime', 'reliability-kernel.json'),
  adr: file('docs', 'adr', 'ADR-004-retry-lease-recovery-dead-letter-replay.md'),
  runtimeMigration: file('control-plane', 'src', 'main', 'resources', 'db', 'migration', 'V3__create_runtime_and_schedule.sql'),
  reliabilityMigration: file('control-plane', 'src', 'main', 'resources', 'db', 'migration', 'V4__create_reliability_and_leases.sql'),
  transitionStore: file('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeTransitionStore.java'),
  schedulingStore: file('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeSchedulingStore.java'),
  workerStore: file('worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'internal', 'JdbcWorkerTaskStore.java'),
  workerExecution: file('worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'WorkerExecutionService.java'),
  triggerService: file('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeTriggerService.java'),
  payloadStore: file('payload-storage', 'src', 'main', 'java', 'com', 'workflowplatform', 'payload', 'MinioTenantPayloadStore.java'),
  retentionPolicy: file('payload-storage', 'src', 'main', 'java', 'com', 'workflowplatform', 'payload', 'PayloadRetentionPolicy.java'),
  retryPlanner: file('worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'internal', 'WorkerRetryPlanner.java'),
  externalHook: file('worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'ExternalResultQueryHook.java'),
  redactor: file('shared-contracts', 'src', 'main', 'java', 'com', 'workflowplatform', 'contracts', 'security', 'SensitiveTextRedactor.java'),
  deadController: file('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'DeadLetterDispositionController.java'),
  deadStore: file('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcDeadLetterDispositionStore.java'),
  openapi: file('api', 'openapi', 'workflow-platform.v1.openapi.json'),
  transitionTest: file('control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeTransitionStoreIT.java'),
  schedulingTest: file('control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeSchedulingStoreIT.java'),
  workerStoreTest: file('worker', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'internal', 'JdbcWorkerTaskStoreIT.java'),
  retryTest: file('worker', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'internal', 'WorkerRetryPlannerTests.java'),
  workerExecutionTest: file('worker', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'WorkerExecutionServiceTests.java'),
  deadStoreTest: file('control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeControlStoreIT.java'),
  deadControllerTest: file('control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'DeadLetterDispositionControllerTests.java'),
  redactorTest: file('shared-contracts', 'src', 'test', 'java', 'com', 'workflowplatform', 'contracts', 'security', 'SensitiveTextRedactorTests.java'),
  outboxTest: file('control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcOutboxPublicationStoreIT.java'),
  managedHttpTest: file('worker', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'ManagedHttpWorkerNodeExecutorTests.java'),
  payloadTest: file('payload-storage', 'src', 'test', 'java', 'com', 'workflowplatform', 'payload', 'MinioTenantPayloadStoreIT.java'),
  kafkaTest: file('test-support', 'src', 'test', 'java', 'com', 'workflowplatform', 'testsupport', 'KafkaTaskDeliveryIT.java'),
  e2eTest: file('test-support', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'RuntimeHappyPathE2EIT.java'),
  faultMatrix: file('tests', 'integration', 'fault-injection-matrix.json'),
  packageJson: file('package.json'),
};
const argumentNames = new Map(
  Object.keys(defaults).map((name) => [`--${name.replace(/[A-Z]/gu, (letter) => `-${letter.toLowerCase()}`)}`, name]),
);

function options(argv) {
  const result = { ...defaults };
  for (let index = 0; index < argv.length; index += 1) {
    const name = argumentNames.get(argv[index]);
    if (!name || !argv[index + 1]) throw new Error(`Invalid argument: ${String(argv[index])}`);
    result[name] = path.resolve(argv[index + 1]);
    index += 1;
  }
  return result;
}
function fail(message) { throw new Error(message); }
function text(target, label) {
  if (!existsSync(target)) fail(`${label} does not exist: ${target}`);
  return readFileSync(target, 'utf8');
}
function token(source, expected, label) {
  if (!source.includes(expected)) fail(`${label} is missing executable evidence: ${expected}`);
}
function tokens(source, values, label) {
  for (const value of values) token(source, value, label);
}
function exact(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} drift; expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}
function operation(openapi, apiPath, operationId) {
  const value = openapi.paths?.[apiPath]?.post;
  if (!value) fail(`G5-08 OpenAPI operation is missing: POST ${apiPath}`);
  exact(value.operationId, operationId, `G5-08 ${apiPath} operationId`);
  exact(value['x-workflow-implementation-status'], 'implemented', `G5-08 ${apiPath} implementation status`);
  if (!value.responses?.['202']) fail(`G5-08 ${apiPath} response 202 is missing`);
  const parameters = [...(openapi.paths[apiPath].parameters ?? []), ...(value.parameters ?? [])];
  if (!parameters.some((parameter) => parameter.$ref === '#/components/parameters/IdempotencyKey')) {
    fail(`G5-08 ${apiPath} requires Idempotency-Key`);
  }
}

const input = options(process.argv.slice(2));
const contract = JSON.parse(text(input.contract, 'G5 reliability contract'));
const adr = text(input.adr, 'ADR-004');
const runtimeMigration = text(input.runtimeMigration, 'runtime migration');
const reliabilityMigration = text(input.reliabilityMigration, 'reliability migration');
const transitionStore = text(input.transitionStore, 'runtime transition store');
const schedulingStore = text(input.schedulingStore, 'lease recovery store');
const workerStore = text(input.workerStore, 'Worker transaction store');
const workerExecution = text(input.workerExecution, 'Worker execution service');
const triggerService = text(input.triggerService, 'runtime trigger service');
const payloadStore = text(input.payloadStore, 'MinIO tenant payload store');
const retentionPolicy = text(input.retentionPolicy, 'payload retention policy');
const retryPlanner = text(input.retryPlanner, 'Worker retry planner');
const externalHook = text(input.externalHook, 'external result query hook');
const redactor = text(input.redactor, 'sensitive text redactor');
const deadController = text(input.deadController, 'dead-letter controller');
const deadStore = text(input.deadStore, 'dead-letter disposition store');
const openapi = JSON.parse(text(input.openapi, 'OpenAPI contract'));
const transitionTest = text(input.transitionTest, 'transition PostgreSQL test');
const schedulingTest = text(input.schedulingTest, 'scheduling PostgreSQL test');
const workerStoreTest = text(input.workerStoreTest, 'Worker PostgreSQL test');
const retryTest = text(input.retryTest, 'retry unit test');
const workerExecutionTest = text(input.workerExecutionTest, 'Worker execution test');
const deadStoreTest = text(input.deadStoreTest, 'dead-letter PostgreSQL test');
const deadControllerTest = text(input.deadControllerTest, 'dead-letter controller test');
const redactorTest = text(input.redactorTest, 'redactor test');
const outboxTest = text(input.outboxTest, 'Outbox crash test');
const managedHttpTest = text(input.managedHttpTest, 'real network timeout test');
const payloadTest = text(input.payloadTest, 'MinIO payload integration test');
const kafkaTest = text(input.kafkaTest, 'Kafka duplicate integration test');
const e2eTest = text(input.e2eTest, 'PostgreSQL/Kafka recovery E2E test');
const faultMatrix = JSON.parse(text(input.faultMatrix, 'fault injection matrix'));
const packageJson = JSON.parse(text(input.packageJson, 'package.json'));

exact([contract.component, contract.version, contract.gate], ['runtime-reliability-kernel', 3, 'G5'], 'G5 contract identity');
exact(contract.verifiedTasks, Array.from({ length: 13 }, (_, index) => `G5-${String(index + 1).padStart(2, '0')}`), 'G5-01..G5-13 verified task set');
exact(contract.completedDependencies, [], 'G5 completed dependency set');
exact(contract.deferredTasks, [], 'G5 deferred task set');
exact(contract.counterLifecycle, {
  dispatch_count: 'successful-dispatch',
  attempt_no: 'claim-enters-running',
  lease_generation: 'successful-dispatch',
}, 'G5-02 counter lifecycle');
exact(contract.retry?.strategies, ['fixed', 'linear', 'exponential'], 'G5-04 retry strategies');
exact(contract.retry?.guards, ['maximum-attempts', 'maximum-window', 'retry-after'], 'G5-05 retry guards');
exact(contract.fencing, ['worker-id', 'lease-generation', 'lease-not-expired'], 'G5-03 fencing tuple');
exact(contract.delivery, 'at-least-once', 'G5-07 delivery mode');
exact(contract.payload, {
  inlineThresholdProperty: 'workflow.object-storage.inline-threshold-bytes',
  maximumInputBytes: 16777216,
  namespace: 's3://<bucket>/payload/v1/<tenant-id>/<sha256>',
  integrity: 'sha256-metadata-and-content',
  authorization: 'tenant-prefix-and-object-metadata',
  retentionClasses: ['transient-7d', 'runtime-30d', 'audit-365d'],
  lifecycle: ['hot', 'archived', 'delete-after-retain-until'],
}, 'G5-12 payload contract');
exact(contract.faultInjection, [
  'outbox-before-mark-crash', 'kafka-duplicate', 'real-network-timeout', 'postgresql-short-outage',
], 'G5-13 fault injection contract');
for (const fault of contract.faultInjection) {
  if (!faultMatrix.faults?.some((entry) => entry.id === fault && entry.evidence)) {
    fail(`G5-13 fault matrix lacks bound evidence: ${fault}`);
  }
}

tokens(adr, [
  '`dispatch_count`', '`attempt_no`', '`lease_generation`', '`fixed`、`linear` 或 `exponential`',
  'Retry-After', '`ExternalResultQueryHook`', '`processed_event`', '迟到结果', '租约到期',
  '人工备注', '不可改写',
], 'G5-01 ADR-004 semantics');

tokens(runtimeMigration, [
  'attempt_no integer NOT NULL DEFAULT 0', 'dispatch_count integer NOT NULL DEFAULT 0',
  'lease_generation bigint NOT NULL DEFAULT 0', 'CONSTRAINT ck_node_instance_counters CHECK',
  'UNIQUE (tenant_id, node_instance_id, attempt_no)', 'CHECK (attempt_no > 0 AND version >= 0)',
  'idx_node_instance_lease_expiry',
], 'G5-02 counter and lease schema');
tokens(reliabilityMigration, [
  'uq_processed_event_consumer_event UNIQUE (consumer_name, event_id)',
  'UNIQUE (tenant_id, node_instance_id, lease_generation)', 'uq_worker_lease_active_node',
  "WHERE status = 'ACTIVE'", "status IN ('REQUEUED', 'RESOLVED', 'DISMISSED')",
  'error_summary_redacted varchar(2000) NOT NULL',
], 'G5-02/G5-07/G5-08 reliability schema');

tokens(transitionStore, [
  'lease_generation = CASE WHEN ? = \'DISPATCHED\'', 'THEN lease_generation + 1 ELSE lease_generation END',
  'dispatch_count = CASE WHEN ? = \'DISPATCHED\'', 'THEN dispatch_count + 1 ELSE dispatch_count END',
  'attempt_no = CASE WHEN ? = \'RUNNING\' THEN attempt_no + 1 ELSE attempt_no END',
  'AND current_worker_id = ? AND lease_generation = ? AND lease_expire_at > ?',
  'RuntimeTransitionException.Failure.FENCING_TOKEN_CONFLICT',
], 'G5-02/G5-03 transition semantics');

tokens(workerStore, [
  "UPDATE public.node_instance SET status = 'RUNNING'", 'attempt_no = attempt_no + 1',
  'AND current_worker_id = ?', 'AND lease_generation = ?', 'AND lease_expire_at > ?',
  'INSERT INTO public.task_execution', 'INSERT INTO public.worker_lease',
  'INSERT INTO public.processed_event', 'ON CONFLICT (consumer_name, event_id) DO NOTHING',
  'SensitiveTextRedactor.redact(result.errorSummaryRedacted(), 1000)',
  'INSERT INTO public.dead_letter',
], 'G5-02/G5-03/G5-07/G5-08 Worker transaction boundary');

tokens(retryPlanner, [
  'result.retryable()', 'case "fixed"', 'case "linear"', 'case "exponential"',
  'stableJitter(stableSeed, completedAttempts)', 'task.attemptNo() >= maximumAttempts',
  'elapsed.compareTo(maximumWindow) >= 0', 'elapsed.plus(delay).compareTo(maximumWindow) > 0',
  'retryAfter.compareTo(delay) > 0', 'NodeExecutionResult.retryWait(',
], 'G5-04/G5-05 bounded retry planner');
tokens(externalHook, ['boolean supports(String nodeType)', 'Optional<WorkerTaskStore.NodeExecutionResult> query('], 'G5-05 external result hook contract');
tokens(workerExecution, ['List<ExternalResultQueryHook> resultQueryHooks', 'for (ExternalResultQueryHook hook : resultQueryHooks)', 'hook.query(task, executionResult)', 'isTerminalResolution(queried.get())'], 'G5-05 external result hook execution');
tokens(triggerService, [
  'workflow.object-storage.inline-threshold-bytes:65536', 'MAXIMUM_INPUT_BYTES = 16 * 1024 * 1024',
  'payloadPolicy.shouldInline(inputBytes)', 'payloadStore.put(', 'RUNTIME_RETENTION_CLASS',
  'placement.reference()', 'placement.digest()',
], 'G5-12 trigger threshold and object placement');
tokens(payloadStore, [
  'PREFIX = "payload/v1/"', 'TENANT_METADATA = "tenant-id"', 'DIGEST_METADATA = "sha256"',
  'PayloadDigests.sha256(payload)', 'payload reference does not belong to the requesting tenant',
  '!identity.digest().equals(PayloadDigests.sha256(payload))', 'archivedAt.isBefore(metadata.archiveAt())',
  'client.setObjectTags(', 'deletedAt.isBefore(metadata.retainUntil())', 'client.removeObject(',
], 'G5-12 tenant, digest and lifecycle enforcement');
tokens(retentionPolicy, [
  'TRANSIENT_7D("transient-7d"', 'RUNTIME_30D("runtime-30d"', 'AUDIT_365D("audit-365d"',
], 'G5-12 frozen retention classes');
token(schedulingStore, 'payloadStore.read(', 'G5-12 scheduler authorized payload resolution');
tokens(workerExecution, [
  'payloadStore.read(', 'PAYLOAD_NOT_FOUND', 'PAYLOAD_STORAGE_UNAVAILABLE',
  'PAYLOAD_ACCESS_DENIED', 'PAYLOAD_INTEGRITY_FAILED', 'externalizeOutput(',
  'Base64.getUrlDecoder()', 'payloadStore.put(', '"runtime-30d"',
], 'G5-12 Worker authorized payload resolution');

tokens(schedulingStore, [
  'n.lease_expire_at <= ?', 'FOR UPDATE OF n SKIP LOCKED', 'WORKER_LEASE_EXPIRED',
  '"ABANDONED"', "UPDATE public.worker_lease SET status = 'EXPIRED'", 'recoverExpired(',
], 'G5-06 expired lease recovery');

tokens(redactor, [
  'authorization|proxy-authorization|token|access[_-]?token|', 'JWT', 'CONTROL',
  'maximumLength', 'REDACTED',
], 'G5-08 bounded secret redaction');
tokens(deadController, [
  '@RequestMapping(path = "/api/v1/dead-letters"', '@PostMapping(path = "/{deadLetterId}/notes"',
  '@PostMapping(path = "/{deadLetterId}/redispatch"', 'Idempotency-Key',
  '@NotBlank @Size(max = 500) String note', '@NotBlank @Size(max = 500) String reason',
], 'G5-08 dead-letter HTTP boundary');
tokens(deadStore, [
  'FOR UPDATE OF d', 'if (!"OPEN".equals(row.status()))', "SET status = 'REQUEUED'",
  "AND status = 'OPEN' AND version = ?", 'RuntimeControlAction.REPLAY', 'row.nodeId()',
  'runtime.dead_letter.note', 'runtime.dead_letter.redispatch',
  'workflow.runtime.dead_letter.note.added', 'workflow.runtime.dead_letter.redispatched',
  '"noteDigest"', 'INSERT INTO public.audit_log', 'INSERT INTO public.outbox_event',
  'completeIdempotency(command, result)', "interval '24 hours'",
], 'G5-08 governed dead-letter disposition transaction');
operation(openapi, '/api/v1/dead-letters/{deadLetterId}/notes', 'addDeadLetterNote');
operation(openapi, '/api/v1/dead-letters/{deadLetterId}/redispatch', 'redispatchDeadLetter');
for (const schema of ['AddDeadLetterNoteRequest', 'RedispatchDeadLetterRequest', 'DeadLetterActionAccepted']) {
  if (!openapi.components?.schemas?.[schema]) fail(`G5-08 OpenAPI schema is missing: ${schema}`);
}

tokens(transitionTest, ['@Testcontainers', 'workerClaimIncrementsOnlyTheAttemptCounter', 'fencingTokenIsCheckedForWorkerClaim'], 'G5-02/G5-03 PostgreSQL transition evidence');
tokens(schedulingTest, ['@Testcontainers', 'recoversExpiredDispatchAndRunningLeaseWithoutAcceptingTheLateFence', "status = 'ABANDONED'", 'lease_generation = 2'], 'G5-06 PostgreSQL recovery evidence');
tokens(workerStoreTest, [
  '@Testcontainers', 'staleGenerationCannotWriteALateResult',
  'currentFenceCommitsResultAttemptLeaseAuditOutboxAndDedupTogether',
  'retryableFailureUsesFrozenPolicyAndRetryAfterInTheSameTransaction',
  'terminalFailureCreatesARedactedReferenceOnlyDeadLetter',
], 'G5-03/G5-05/G5-07/G5-08 Worker PostgreSQL evidence');
tokens(retryTest, ['plansFixedLinearAndExponentialDelays', 'appliesRetryAfterAndStopsAtAttemptOrElapsedWindow', 'terminalizesMissingPolicyAndRejectsMalformedPolicy'], 'G5-04/G5-05 retry tests');
tokens(workerExecutionTest, ['externalResultQueryPreventsRetryWhenTheRemoteOperationAlreadySucceeded', 'failedOrNonTerminalExternalQueriesFallBackToTheOriginalRetryableFailure'], 'G5-05 external result tests');
token(workerExecutionTest, 'externalizesOversizedInlinePluginOutputWithTheVerifiedDigest', 'G5-12 Worker output externalization test');
tokens(deadStoreTest, ['@Testcontainers', 'appendsARedactedDeadLetterNoteIdempotentlyAndHidesCrossTenantRows', 'redispatchesAsAFailedNodeReplayWithoutRewritingSourceHistory'], 'G5-08 PostgreSQL dead-letter evidence');
tokens(deadControllerTest, ['exposesAcceptedNoteAndRedispatchRoutes', 'requiresIdempotencyAndBoundedNonBlankBodies'], 'G5-08 MVC evidence');
tokens(redactorTest, ['redactsCredentialShapesJwtAndControlsWithinTheBound', 'handlesBlankBoundsAndTruncationDeterministically'], 'G5-08 redaction tests');
tokens(e2eTest, [
  '@Testcontainers', 'PostgreSQLContainer<?>', 'KafkaContainer',
  'killedWorkerLeaseIsRecoveredAndItsLateResultIsFenced',
  'try (AnnotationConfigApplicationContext killedWorker = workerStoreContext())',
  'assertThrows(', '() -> store.complete(abandoned, "worker-before-kill", success)',
  "attempt_no = 1 AND status = 'ABANDONED'",
  "lease_generation = 1 AND status = 'EXPIRED'", '"2:2:2",',
], 'G5-03/G5-06 real PostgreSQL/Kafka crash recovery evidence');
tokens(payloadTest, [
  '@Testcontainers', 'GenericContainer<?> MINIO',
  'writesReadsAndRejectsCrossTenantDigestTamperingAndMissingObjects',
  'enforcesArchiveAndDeleteBoundariesAgainstRealObjectTagsAndRemoval',
  'PayloadStorageException.Reason.ACCESS_DENIED', 'PayloadStorageException.Reason.INTEGRITY',
  'PayloadStorageException.Reason.NOT_FOUND', 'PayloadStorageException.Reason.RETENTION',
], 'G5-12 real MinIO negative-boundary evidence');
tokens(outboxTest, [
  'eventIsResentAfterPublisherCrashesBeforeMarkingSuccess', "publisher_id = 'dead-publisher'",
  'assertEquals(2, reclaimed.getFirst().publishAttempts())',
], 'G5-13 Outbox duplicate evidence');
tokens(kafkaTest, [
  '@Testcontainers', 'KafkaContainer', 'publishesRoutesDeduplicatesAndRetainsFailedOffsets',
  'duplicate:', 'awaitCommittedOffset(2L)',
], 'G5-13 Kafka duplicate and offset evidence');
tokens(managedHttpTest, [
  'realTlsPeerStallTimesOutWithoutProducingASuccess', 'new ServerSocket(', '8443',
  'new ManagedHttpWorkerNodeExecutor.JdkHttpExchange()', 'HTTP_TRANSPORT_ERROR',
], 'G5-13 real network timeout evidence');
tokens(workerStoreTest, [
  'realDatabasePauseRollsBackWithoutHalfCommitAndRecoversExactlyOnce',
  'pauseContainerCmd(POSTGRES.getContainerId()).exec()',
  'unpauseContainerCmd(POSTGRES.getContainerId()).exec()',
  "AND status = 'RUNNING'", "AND status = 'ACTIVE'", 'EVENT_OUTAGE_ID',
], 'G5-13 real PostgreSQL short-outage invariants');
tokens(e2eTest, [
  'GenericContainer<?> MINIO', 'input_payload IS NULL AND input_ref LIKE \'s3://%\'',
  'duplicateTaskEvent', 'awaitCommittedTaskOffset(offsetBeforeDuplicate + 1)',
  'SELECT count(*) FROM public.task_execution',
], 'G5-12/G5-13 PostgreSQL/Kafka/MinIO combined evidence');

exact(packageJson.scripts?.['runtime:g5:verify'], 'node scripts/runtime/verify-reliability-kernel.mjs', 'G5 verify script');
exact(packageJson.scripts?.['runtime:g5:test'], 'node scripts/runtime/test-reliability-kernel-verifier.mjs', 'G5 verifier-test script');
token(packageJson.scripts?.verify ?? '', 'pnpm run runtime:g5:verify', 'root G5 verify binding');
token(packageJson.scripts?.verify ?? '', 'pnpm run runtime:g5:test', 'root G5 verifier-test binding');

console.log('G5-01..G5-13 reliability kernel PASS: runtime fencing/retry/recovery/dead-letter controls, tenant-scoped SHA-256 MinIO payload lifecycle, and Outbox/Kafka/network/PostgreSQL fault invariants are bound to executable evidence.');
