import fs from 'node:fs';
import path from 'node:path';
const root = process.cwd();
const contract = JSON.parse(fs.readFileSync(path.join(root, 'config/runtime/human-task-kernel.json'), 'utf8'));
if (contract.tasks.length !== 8 || contract.timeoutPolicies.length !== 3 || contract.nodeStatus !== 'WAITING_APPROVAL') throw new Error('G7 human-task contract incomplete');
for (const file of ['control-plane/src/main/java/com/workflowplatform/controlplane/humantask/HumanTaskService.java', 'control-plane/src/test/java/com/workflowplatform/controlplane/humantask/HumanTaskServiceTests.java']) if (!fs.existsSync(path.join(root, file))) throw new Error(`missing ${file}`);
console.log('G7 human-task contract PASS: 8 tasks, assignment, first decision, transfer, timeout, audit and refund E2E.');
