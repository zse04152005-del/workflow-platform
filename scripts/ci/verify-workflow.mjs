import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const workflowPath = path.join(repositoryRoot, '.github', 'workflows', 'ci.yml');
const workflow = readFileSync(workflowPath, 'utf8');
const actionlintImage =
  'rhysd/actionlint:1.7.7@sha256:887a259a5a534f3c4f36cb02dca341673c6089431057242cdc931e9f133147e9';

function fail(message) {
  throw new Error(message);
}

for (const forbidden of ['pull_request_target:', 'workflow_run:', '${{ secrets.']) {
  if (workflow.includes(forbidden)) fail(`CI workflow contains forbidden token ${forbidden}`);
}
for (const required of [
  'permissions:\n  contents: read',
  'cancel-in-progress: true',
  'runs-on: ubuntu-24.04',
  'persist-credentials: false',
  'java-version: "21.0.12+8.0.LTS"',
  'node-version-file: .node-version',
  'package-manager-cache: false',
  'version: 11.9.0',
  './mvnw -B -ntp clean verify',
  'pnpm install --frozen-lockfile',
  'pnpm verify',
  'pnpm migrations:verify',
  'pnpm migrations:test',
  'node scripts/ci/verify-workflow.mjs',
  'node scripts/compose/verify-contract.mjs',
]) {
  if (!workflow.includes(required)) fail(`CI workflow is missing required contract: ${required}`);
}

const expectedActions = new Map([
  ['actions/checkout', '93cb6efe18208431cddfb8368fd83d5badbf9bfd'],
  ['actions/setup-java', 'dded0888837ed1f317902acf8a20df0ad188d165'],
  ['actions/setup-node', 'a0853c24544627f65ddf259abe73b1d18a591444'],
  ['pnpm/action-setup', '9fd676a19091d4595eefd76e4bd31c97133911f1'],
]);
const actionReferences = [...workflow.matchAll(/uses:\s*([^@\s]+)@([0-9a-fA-F]{40})(?:\s|$)/gu)];
if (actionReferences.length === 0) fail('CI workflow contains no fully pinned action references');
for (const [, action, commit] of actionReferences) {
  const expectedCommit = expectedActions.get(action);
  if (!expectedCommit) fail(`Unexpected third-party Action ${action}`);
  if (commit !== expectedCommit) fail(`${action} must be pinned to reviewed commit ${expectedCommit}`);
}
const allUses = [...workflow.matchAll(/uses:\s*([^\s#]+)/gu)].map((match) => match[1]);
if (allUses.length !== actionReferences.length) {
  fail('Every uses: reference must be pinned to a full 40-character commit SHA');
}

const expectedJobs = ['java-quality', 'web-and-policy', 'workflow-contract', 'compose-contract'];
for (const job of expectedJobs) {
  if (!workflow.includes(`\n  ${job}:\n`)) fail(`CI workflow is missing required job ${job}`);
}
const jobCount = expectedJobs.length;
const timeoutCount = (workflow.match(/timeout-minutes:/gu) ?? []).length;
if (jobCount !== 4 || timeoutCount !== jobCount) {
  fail(`Expected 4 jobs and one timeout per job; jobs=${jobCount}, timeouts=${timeoutCount}`);
}
const checkoutCount = actionReferences.filter((match) => match[1] === 'actions/checkout').length;
const credentialProtectionCount = (workflow.match(/persist-credentials:\s+false/gu) ?? []).length;
if (checkoutCount !== jobCount || credentialProtectionCount !== checkoutCount) {
  fail('Every job must check out with persist-credentials=false');
}
const packageManagerCacheDisabledCount =
  (workflow.match(/package-manager-cache:\s+false/gu) ?? []).length;
if (packageManagerCacheDisabledCount !== 2) {
  fail(
    `Expected workflow-only and Compose-only jobs to disable automatic package-manager caching; found ${packageManagerCacheDisabledCount}`,
  );
}

const actionlint = spawnSync(
  'docker',
  [
    'run',
    '--rm',
    '--mount',
    `type=bind,source=${repositoryRoot},target=/repo,readonly`,
    '--workdir',
    '/repo',
    actionlintImage,
    '.github/workflows/ci.yml',
  ],
  {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    windowsHide: true,
  },
);
if (actionlint.error) throw actionlint.error;
if (actionlint.status !== 0) {
  fail(`actionlint failed:\n${actionlint.stdout}${actionlint.stderr}`);
}

console.log(
  `CI workflow verification PASS: ${jobCount} jobs, ${actionReferences.length} SHA-pinned Action uses, minimal permissions, actionlint ${actionlintImage}.`,
);
