import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'planning', 'verify-traceability.mjs');
const source = path.join(repositoryRoot, 'config', 'traceability', 'requirements.json');
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-traceability-test-'));

function run(requirementsPath, skipMarkdown = true) {
  const args = [verifier, '--requirements', requirementsPath];
  if (skipMarkdown) args.push('--skip-markdown');
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    windowsHide: true,
  });
}

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function writeCase(name, mutate) {
  const document = JSON.parse(readFileSync(source, 'utf8'));
  mutate(document);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(document, null, 2)}\n`, 'utf8');
  return target;
}

function expectFailure(name, mutate, expectedMessage) {
  const target = writeCase(name, mutate);
  const result = run(target);
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expectedMessage)) {
    throw new Error(
      `${name} negative control was not blocked as expected (${expectedMessage}):\n${combined}`,
    );
  }
}

try {
  const positive = run(source, false);
  if (positive.status !== 0) {
    throw new Error(`Positive traceability baseline failed:\n${output(positive)}`);
  }

  expectFailure(
    'duplicate-id',
    (document) => document.requirements.push(structuredClone(document.requirements[0])),
    'duplicate Requirement ID',
  );
  expectFailure(
    'missing-task',
    (document) => {
      const requirement = document.requirements.find((item) => item.id === 'REQ-P2-SCALE-001');
      requirement.controlTasks = [];
    },
    'missing control task coverage: G12-11',
  );
  expectFailure(
    'empty-artifact',
    (document) => {
      document.requirements[0].api = [];
    },
    'artifact mapping must not be empty',
  );
  expectFailure(
    'phase2-as-mvp',
    (document) => {
      const requirement = document.requirements.find((item) => item.id === 'REQ-P2-AI-001');
      requirement.scope = 'mvp';
      requirement.implementationStatus = 'planned';
    },
    'MVP requirements may not map G12 tasks',
  );
  expectFailure(
    'missing-implemented-file',
    (document) => {
      document.requirements[0].api = ['implemented: file:missing/traceability-contract.yaml'];
    },
    'implemented file does not exist',
  );

  console.log(
    'Traceability verifier tests PASS: positive baseline accepted; duplicate ID, missing task, empty artifact, phase mismatch, and missing implemented file blocked.',
  );
} finally {
  const resolvedTemporaryRoot = path.resolve(temporaryRoot);
  const safePrefix = path.resolve(os.tmpdir(), 'workflow-traceability-test-');
  if (!resolvedTemporaryRoot.startsWith(safePrefix)) {
    throw new Error(`Refusing to remove unexpected temporary path: ${resolvedTemporaryRoot}`);
  }
  rmSync(resolvedTemporaryRoot, { recursive: true, force: true });
}
