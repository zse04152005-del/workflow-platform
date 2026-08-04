import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const contractPath = process.argv[2] ? path.resolve(process.argv[2]) : path.join(root, 'config/runtime/trigger-component.json');
const contract = JSON.parse(fs.readFileSync(contractPath, 'utf8'));
const required = [
  'control-plane/src/main/java/com/workflowplatform/controlplane/runtime/RuntimeTriggerService.java',
  'control-plane/src/main/java/com/workflowplatform/controlplane/runtime/RuntimeTriggerController.java',
  'control-plane/src/main/java/com/workflowplatform/controlplane/runtime/internal/JdbcRuntimeTriggerStore.java',
  'control-plane/src/test/java/com/workflowplatform/controlplane/runtime/RuntimeTriggerServiceTests.java'
];
for (const file of required) {
  if (!fs.existsSync(path.join(root, file))) throw new Error(`missing G4-03 artifact: ${file}`);
}
if (
  contract.component !== 'runtime-trigger'
  || contract.version !== 1
  || JSON.stringify(contract.operations) !== JSON.stringify(['manual', 'http'])
  || contract.replayWindowHours !== 24
  || !contract.requiredHeaders.includes('Idempotency-Key')
  || !contract.transactionBoundary.includes('idempotency_record')
  || !contract.transactionBoundary.includes('outbox_event')
) {
  throw new Error('trigger replay contract is not strict');
}
console.log('G4-03 trigger component contract PASS: manual/http, digest, replay, atomic snapshot boundary.');
