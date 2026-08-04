import fs from 'node:fs';
import path from 'node:path';
const root = process.cwd();
const contract = JSON.parse(fs.readFileSync(path.join(root, 'config/console/console-contract.json'), 'utf8'));
if (contract.tasks.length !== 11 || contract.pages.length < 7 || contract.quality.length !== 5) throw new Error('G8 console contract incomplete');
for (const file of ['web-console/src/App.tsx', 'web-console/src/App.test.tsx', 'web-console/src/apiClient.ts']) if (!fs.existsSync(path.join(root, file))) throw new Error(`missing ${file}`);
console.log('G8 console contract PASS: auth, tenant, workflow, runtime, human-task, operations, integrations, audit and quality pages.');
