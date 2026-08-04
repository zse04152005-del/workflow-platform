import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const defaults = {
  contract: path.join(root, 'config', 'runtime', 'happy-path-e2e.json'),
  test: path.join(root, 'test-support', 'src', 'test', 'java', 'com', 'workflowplatform', 'worker', 'RuntimeHappyPathE2EIT.java'),
  pom: path.join(root, 'test-support', 'pom.xml'),
  scheduling: path.join(root, 'control-plane', 'src', 'main', 'java', 'com', 'workflowplatform', 'controlplane', 'runtime', 'internal', 'JdbcRuntimeSchedulingStore.java'),
  worker: path.join(root, 'worker', 'src', 'main', 'java', 'com', 'workflowplatform', 'worker', 'BuiltinWorkerNodeExecutor.java'),
  packageJson: path.join(root, 'package.json'),
};
const flags = new Map([
  ['--contract', 'contract'], ['--test', 'test'], ['--pom', 'pom'], ['--scheduling', 'scheduling'],
  ['--worker', 'worker'], ['--package-json', 'packageJson'],
]);

function options(argv) {
  const result = { ...defaults };
  for (let index = 0; index < argv.length; index += 1) {
    const name = flags.get(argv[index]);
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
  if (!source.includes(expected)) fail(`${label} is missing: ${expected}`);
}
function minimumOccurrences(source, expected, minimum, label) {
  const count = source.split(expected).length - 1;
  if (count < minimum) fail(`${label} requires ${minimum} occurrences of: ${expected}`);
}
function exact(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) fail(`${label} drift`);
}

const input = options(process.argv.slice(2));
const contract = JSON.parse(text(input.contract, 'G4-17 contract'));
const test = text(input.test, 'G4-17 integration test');
const pom = text(input.pom, 'test-support pom');
const scheduling = text(input.scheduling, 'persistent scheduler');
const worker = text(input.worker, 'built-in Worker executor');
const packageJson = JSON.parse(text(input.packageJson, 'package.json'));

exact(
  [contract.component, contract.version, contract.task, contract.requirement],
  ['runtime-happy-path-e2e', 1, 'G4-17', 'REQ-E2E-001'],
  'G4-17 identity',
);
exact(contract.flow, ['http-trigger', 'start', 'condition', 'managed-http', 'delay', 'end'], 'G4-17 ordered flow');
exact(contract.infrastructure, ['postgresql-testcontainer', 'kafka-testcontainer'], 'G4-17 infrastructure');
if ((contract.assertions ?? []).length !== 8) fail('G4-17 assertion contract must contain 8 boundaries');
exact(contract.test, 'test-support/src/test/java/com/workflowplatform/worker/RuntimeHappyPathE2EIT.java', 'G4-17 test binding');

for (const expected of [
  '@Testcontainers', 'PostgreSQLContainer<?>', 'KafkaContainer', 'RuntimeTriggerResult replay',
  'assertTrue(replay.replayed())', 'executeNext(control, instanceId, "condition", "SUCCEEDED")',
  'nodeStatus(instanceId, "fallback-end")', 'ManagedHttpWorkerNodeExecutor(',
  'policy.validate(request.uri())', 'executeNext(control, instanceId, "delay", "WAITING_TIMER")',
  'SELECT next_schedule_at::text', 'scheduler-after-restart',
  'awaitInstanceStatus(instanceId, "SUCCEEDED")', 'public.processed_event',
  '.attempts(CONTEXT, instanceId, null, 20)', 'config_snapshot->>\'_managedEndpoint\'',
]) token(test, expected, 'G4-17 executable E2E evidence');
minimumOccurrences(test, 'try (ControlRuntime control = controlRuntime())', 2, 'G4-17 control-plane restart lifecycle');

for (const artifact of ['<artifactId>postgresql</artifactId>', '<artifactId>kafka</artifactId>', '<artifactId>flyway-core</artifactId>']) {
  token(pom, artifact, 'G4-17 test dependency');
}
for (const expected of ['selectConditionEdges(', 'conditionEvaluator.evaluate(', 'ALL_SELECTED_NODES_SUCCEEDED']) {
  token(scheduling, expected, 'G4-17 condition/restart scheduling');
}
for (const expected of ['Duration.parse(configuredDuration)', 'NodeExecutionResult.waitingTimer(', 'task.attemptNo() > FIRST_ATTEMPT']) {
  token(worker, expected, 'G4-17 persistent Delay execution');
}
exact(packageJson.scripts?.['runtime:g4-17:verify'], 'node scripts/runtime/verify-runtime-happy-path-e2e.mjs', 'G4-17 verify script');
exact(packageJson.scripts?.['runtime:g4-17:test'], 'node scripts/runtime/test-runtime-happy-path-e2e-verifier.mjs', 'G4-17 verifier test script');
token(packageJson.scripts?.verify ?? '', 'pnpm run runtime:g4-17:verify', 'root G4-17 verify binding');
token(packageJson.scripts?.verify ?? '', 'pnpm run runtime:g4-17:test', 'root G4-17 verifier-test binding');

console.log('G4-17 runtime happy-path E2E contract PASS: real PostgreSQL/Kafka, idempotent HTTP trigger, selected Condition branch, managed HTTP, persisted Delay restart and final query assertions are bound.');
