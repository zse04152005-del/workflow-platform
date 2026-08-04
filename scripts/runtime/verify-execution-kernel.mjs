import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const java = (...parts) => path.join(repositoryRoot, ...parts);
const defaults = {
  contract: java('config', 'runtime', 'execution-kernel.json'),
  migration: java('control-plane', 'src', 'main', 'resources', 'db', 'migration', 'V7__add_runtime_execution_snapshots.sql'),
  triggerStore: java('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeTriggerStore.java'),
  schedulingStore: java('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeSchedulingStore.java'),
  outboxStore: java('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcOutboxPublicationStore.java'),
  kafkaSink: java('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'KafkaOutboxEventSink.java'),
  cronCalculator: java('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'CronTriggerCalculator.java'),
  cronStore: java('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcCronSchedulingStore.java'),
  queryController: java('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeQueryController.java'),
  queryService: java('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeQueryService.java'),
  queryStore: java('control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeQueryStore.java'),
  schedulingTest: java('control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeSchedulingStoreIT.java'),
  queryControllerTest: java('control-plane', 'src', 'test', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'RuntimeQueryControllerTests.java'),
  workerExecution: java('worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'WorkerExecutionService.java'),
  workerContract: java('worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'WorkerTaskStore.java'),
  workerStore: java('worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'internal', 'JdbcWorkerTaskStore.java'),
  builtinWorker: java('worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'BuiltinWorkerNodeExecutor.java'),
  condition: java('shared-contracts', 'src', 'main', 'java', 'com', 'workflowplatform', 'contracts', 'expression', 'DeterministicConditionEvaluator.java'),
  httpWorker: java('worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'ManagedHttpWorkerNodeExecutor.java'),
  workerTest: java('worker', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'WorkerExecutionServiceTests.java'),
  httpTest: java('worker', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'ManagedHttpWorkerNodeExecutorTests.java'),
  openapi: java('api', 'openapi', 'workflow-platform.v1.openapi.json'),
  packageJson: java('package.json'),
};
const argumentNames = new Map(Object.keys(defaults).map((name) => [`--${name.replace(/[A-Z]/gu, (letter) => `-${letter.toLowerCase()}`)}`, name]));

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
function text(file, label) {
  if (!existsSync(file)) fail(`${label} does not exist: ${file}`);
  return readFileSync(file, 'utf8');
}
function token(source, expected, label) {
  if (!source.includes(expected)) fail(`${label} is missing executable evidence: ${expected}`);
}
function tokens(source, expected, label) {
  for (const value of expected) token(source, value, label);
}
function exact(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} drift; expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

const input = options(process.argv.slice(2));
const contract = JSON.parse(text(input.contract, 'execution-kernel contract'));
const migration = text(input.migration, 'runtime snapshot migration');
const triggerStore = text(input.triggerStore, 'runtime trigger store');
const schedulingStore = text(input.schedulingStore, 'runtime scheduling store');
const outboxStore = text(input.outboxStore, 'outbox publication store');
const kafkaSink = text(input.kafkaSink, 'Kafka outbox sink');
const cronCalculator = text(input.cronCalculator, 'Cron calculator');
const cronStore = text(input.cronStore, 'Cron persistence store');
const queryController = text(input.queryController, 'runtime query controller');
const queryService = text(input.queryService, 'runtime query service');
const queryStore = text(input.queryStore, 'runtime query persistence store');
const schedulingTest = text(input.schedulingTest, 'runtime scheduling PostgreSQL test');
const queryControllerTest = text(input.queryControllerTest, 'runtime query MVC test');
const workerExecution = text(input.workerExecution, 'Worker execution service');
const workerContract = text(input.workerContract, 'Worker execution contract');
const workerStore = text(input.workerStore, 'Worker JDBC store');
const builtinWorker = text(input.builtinWorker, 'built-in Worker executor');
const condition = text(input.condition, 'Condition evaluator');
const httpWorker = text(input.httpWorker, 'managed HTTP Worker executor');
const workerTest = text(input.workerTest, 'Worker execution tests');
const httpTest = text(input.httpTest, 'managed HTTP tests');
const openapi = JSON.parse(text(input.openapi, 'OpenAPI contract'));
const packageJson = JSON.parse(text(input.packageJson, 'package.json'));

exact([contract.component, contract.version], ['runtime-execution-kernel', 1], 'execution-kernel identity');
exact(Object.keys(contract.tasks ?? {}), Array.from({ length: 13 }, (_, index) => `G4-${String(index + 4).padStart(2, '0')}`), 'ordered execution-kernel task set');
const expectedArtifacts = {
  'G4-04': 'RuntimeInstanceSnapshot',
  'G4-05': 'DagProgressor',
  'G4-06': 'DueTaskScanner',
  'G4-07': 'OutboxPublisher',
  'G4-08': 'TaskEventConsumer',
  'G4-09': 'WorkerLeaseService',
  'G4-10': 'WorkerExecutionService+WorkerTaskStore',
  'G4-11': 'BuiltinWorkerNodeExecutor+JdbcRuntimeSchedulingStore',
  'G4-12': 'DeterministicConditionEvaluator',
  'G4-13': 'ManagedHttpWorkerNodeExecutor',
  'G4-14': 'BuiltinWorkerNodeExecutor.delay+JdbcRuntimeSchedulingStore',
  'G4-15': 'CronSchedulingService+JdbcCronSchedulingStore',
  'G4-16': 'RuntimeQueryController+JdbcRuntimeQueryStore',
};
for (const [task, artifact] of Object.entries(expectedArtifacts)) {
  exact(contract.tasks[task]?.artifact, artifact, `${task} artifact binding`);
  if ((contract.tasks[task]?.invariants ?? []).length < 3) fail(`${task} invariant contract is incomplete`);
}
exact(contract.persistentAuthority, {
  state: 'postgresql',
  dueIndex: 'tenant_id,status,next_schedule_at,id',
  leaseFence: 'worker_id,lease_generation,lease_expire_at',
  pagination: 'created_at DESC,id DESC',
}, 'persistent authority');

tokens(migration, ['input_payload jsonb', 'config_snapshot jsonb', "jsonb_typeof(input_payload) = 'object'"], 'G4-04 immutable snapshot migration');
tokens(triggerStore, ['input_digest, input_payload, trace_id', 'workflow_instance_id, node_id, node_type, status, config_snapshot', 'snapshot.put("_executorVersion"', 'snapshot.put("_timeout"', 'snapshot.set("_retry"', '"_managedEndpoint"'], 'G4-04/G4-10 trigger snapshots');
tokens(schedulingStore, ['progressor.evaluate(', 'selectConditionEdges(', 'conditionEvaluator.evaluate(', 'LIMIT ? FOR UPDATE OF n SKIP LOCKED', '"DISPATCHED"', 'convergeInstance(', '"ALL_SELECTED_NODES_SUCCEEDED"', '"FAILED"'], 'G4-05/G4-06/G4-11/G4-12/G4-14 scheduling');
tokens(outboxStore, ['FOR UPDATE SKIP LOCKED', 'markPublished(', 'next_publish_at'], 'G4-07 Outbox persistence');
tokens(kafkaSink, ['KafkaTemplate<String, String>', 'kafka.send(', '.get(10, TimeUnit.SECONDS)'], 'G4-07 Kafka publication');
tokens(workerStore, ['public.processed_event', 'lease_generation = ?', 'lease_expire_at > ?', 'FOR UPDATE OF n', 'INSERT INTO public.processed_event'], 'G4-08/G4-09 Worker deduplication and fencing');

tokens(workerExecution, ['Executors.newVirtualThreadPerTaskExecutor()', 'MAXIMUM_TIMEOUT = Duration.ofMinutes(30)', 'future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)', 'future.cancel(true)', '"NODE_TIMEOUT"', '"NODE_EXECUTION_INTERRUPTED"', '"NODE_EXECUTION_UNEXPECTED"', 'store.complete('], 'G4-10 bounded node execution');
tokens(workerContract, ['MAXIMUM_REFERENCE_LENGTH = 8192', 'MAXIMUM_ERROR_SUMMARY_LENGTH = 1000', 'Pattern.compile("[0-9a-f]{64}")', "errorSummaryRedacted.indexOf('\\n')", 'boolean retryable'], 'G4-10 output and error boundary');
tokens(builtinWorker, ['Set.of("start", "end", "condition", "delay")', 'case "start", "end"', 'conditionEvaluator.evaluate(', 'NodeExecutionResult.waitingTimer(', 'task.attemptNo() > FIRST_ATTEMPT'], 'G4-11/G4-12/G4-14 built-in execution');
tokens(condition, ['MAXIMUM_TOKENS = 256', 'MAXIMUM_DEPTH = 32', 'if (!(result instanceof Boolean', 'parseOr()', 'parseAnd()', 'condition expression contains an unsupported token'], 'G4-12 deterministic Condition evaluator');
tokens(httpWorker, ['MAXIMUM_REDIRECTS = 5', 'Set.of(-1, 443, 8443)', 'FORBIDDEN_HEADERS', 'response.status() == 408', 'response.status() == 429', 'response.status() >= 500', 'expression.startsWith(INPUT_EXPRESSION + ".")', 'expression.startsWith("response.body.")', 'readNBytes(maximumBytes + 1)', 'notGlobal(InetAddress address)'], 'G4-13 managed HTTP executor');
tokens(cronCalculator, ['CronExpression.parse(', 'ZoneId', 'CATCH_UP_LIMITED', 'FIRE_ONCE', 'SKIP'], 'G4-15 Cron calculation');
tokens(cronStore, ['public.schedule_occurrence', 'FOR UPDATE OF o SKIP LOCKED', 'calculator.overlapAllowed(', 'triggers.manual(', '"FIRED"'], 'G4-15 persistent Cron scheduling');

tokens(queryController, ['@GetMapping("/{instanceId}")', '@GetMapping("/{instanceId}/nodes")', '@GetMapping("/{instanceId}/attempts")', '@Min(1) @Max(200)'], 'G4-16 query routes and bounds');
tokens(queryService, ['Base64.getUrlEncoder()', 'Instant.parse(parts[0])', 'ProblemCode.COMMON_VALIDATION_INVALID_FIELD', 'limit + 1'], 'G4-16 cursor validation');
tokens(queryStore, ['JdbcRuntimeSupport.setTenantContext(jdbc, context)', '(" + alias + ".created_at, " + alias + ".id) < (?, ?)', 'ORDER BY i.created_at DESC, i.id DESC LIMIT ?', 'JOIN public.workflow_instance i', 'FROM public.task_execution a JOIN public.node_instance n'], 'G4-16 RLS keyset persistence');

tokens(workerTest, ['enforcesThePersistedNodeTimeoutAndClassifiesItAsRetryable', 'redactsUnexpectedPluginFailures', 'rejectsInvalidPersistedTimeoutBeforeCallingThePlugin'], 'G4-10 Worker tests');
tokens(httpTest, ['rejectsPrivateDnsHeaderInjectionAndUnmanagedScheme', 'classifiesServerFailuresAndRejectsOversizedResponses', 'mapsManagedRequestAndSelectsBoundedResponse'], 'G4-13 managed HTTP tests');
tokens(schedulingTest, ['convergesTheInstanceAfterTheEndNodeSucceeds', 'materializesFiresAndDeduplicatesPersistentCronOccurrences', 'recordsNoOverlapOccurrencesAsSkipped', 'queriesInstancesNodesAndAttemptsWithStableTenantCursors'], 'G4-11/G4-15/G4-16 PostgreSQL tests');
tokens(queryControllerTest, ['exposesStableInstanceAndKeysetPageContracts', 'exposesNodeAndAttemptHistoriesWithoutRawPayloads', 'returnsGenericNotFoundAndNeverTrustsTenantHeaders', 'mapsInvalidPaginationInputsToPublicValidationErrors'], 'G4-16 MVC tests');

const expectedOperations = [
  ['/api/v1/instances', 'listWorkflowInstances', 'RuntimeInstancePage'],
  ['/api/v1/instances/{instanceId}', 'getWorkflowInstance', 'RuntimeInstanceView'],
  ['/api/v1/instances/{instanceId}/nodes', 'listWorkflowInstanceNodes', 'RuntimeNodePage'],
  ['/api/v1/instances/{instanceId}/attempts', 'listWorkflowInstanceAttempts', 'RuntimeAttemptPage'],
];
for (const [apiPath, operationId, schema] of expectedOperations) {
  const operation = openapi.paths?.[apiPath]?.get;
  exact(operation?.operationId, operationId, `${apiPath} operationId`);
  exact(operation?.['x-workflow-implementation-status'], 'implemented', `${apiPath} implementation status`);
  exact(operation?.responses?.['200']?.content?.['application/json']?.schema?.$ref, `#/components/schemas/${schema}`, `${apiPath} response schema`);
}
for (const schema of ['RuntimeInstanceView', 'RuntimeNodeView', 'RuntimeAttemptView', 'RuntimeInstancePage', 'RuntimeNodePage', 'RuntimeAttemptPage']) {
  if (!openapi.components?.schemas?.[schema]) fail(`G4-16 OpenAPI schema is missing: ${schema}`);
}
const openapiText = JSON.stringify(openapi.components.schemas.RuntimeAttemptView);
if (openapiText.includes('inputPayload') || openapiText.includes('outputPayload')) fail('G4-16 OpenAPI exposes a raw payload field');

exact(packageJson.scripts?.['runtime:g4-04-16:verify'], 'node scripts/runtime/verify-execution-kernel.mjs', 'G4-04..G4-16 verify script binding');
exact(packageJson.scripts?.['runtime:g4-04-16:test'], 'node scripts/runtime/test-execution-kernel-verifier.mjs', 'G4-04..G4-16 verifier test binding');
token(packageJson.scripts?.verify ?? '', 'pnpm run runtime:g4-04-16:verify', 'root verify execution-kernel binding');
token(packageJson.scripts?.verify ?? '', 'pnpm run runtime:g4-04-16:test', 'root verifier-test binding');

console.log('G4-04..G4-16 execution kernel PASS: PostgreSQL snapshots/scheduling/Cron/query, Kafka Outbox, fenced Worker, bounded Condition/HTTP execution, MVC/OpenAPI and executable tests are bound.');
