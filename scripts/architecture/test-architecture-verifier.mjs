import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'architecture', 'verify-architecture-model.mjs');
const source = path.join(repositoryRoot, 'config', 'architecture', 'model.json');
const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'workflow-architecture-test-'));

function output(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function run(modelPath, skipDocuments = true) {
  const args = [verifier, '--model', modelPath];
  if (skipDocuments) args.push('--skip-documents');
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    windowsHide: true,
  });
}

function writeCase(name, mutate) {
  const model = JSON.parse(readFileSync(source, 'utf8'));
  mutate(model);
  const target = path.join(temporaryRoot, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(model, null, 2)}\n`, 'utf8');
  return target;
}

function expectFailure(name, mutate, expectedMessage, skipDocuments = true) {
  const result = run(writeCase(name, mutate), skipDocuments);
  const combined = output(result);
  if (result.status === 0 || !combined.includes(expectedMessage)) {
    throw new Error(`${name} was not blocked as expected (${expectedMessage}):\n${combined}`);
  }
}

try {
  const positive = run(source, false);
  if (positive.status !== 0) throw new Error(`Positive architecture model failed:\n${output(positive)}`);

  expectFailure(
    'duplicate-container',
    (model) => model.containers.push(structuredClone(model.containers[0])),
    'duplicate containers ID',
  );
  expectFailure(
    'dangling-relationship',
    (model) => {
      model.containerRelationships[0].to = 'missing-container';
    },
    'dangling target missing-container',
  );
  expectFailure(
    'second-authoritative-store',
    (model) => {
      model.containers.find((item) => item.id === 'kafka').authoritativeState = true;
    },
    'PostgreSQL must be the only authoritative state store',
  );
  expectFailure(
    'missing-required-edge',
    (model) => {
      model.containerRelationships = model.containerRelationships.filter(
        (item) => !(item.from === 'worker' && item.to === 'postgres'),
      );
    },
    'missing required container relationship: worker->postgres',
  );
  expectFailure(
    'module-cycle',
    (model) => {
      model.controlPlaneModules.find((item) => item.id === 'security').allowedDependencies = [
        'runtime',
      ];
    },
    'module dependency graph contains cycle',
  );
  expectFailure(
    'module-source-drift',
    (model) => {
      model.controlPlaneModules.find((item) => item.id === 'runtime').allowedDependencies.pop();
    },
    'module dependency drift for runtime',
  );
  expectFailure(
    'unknown-data-owner',
    (model) => {
      model.dataOwnership[0].owner = 'unknown-module';
    },
    'dataOwnership has unknown owner unknown-module',
  );
  expectFailure(
    'missing-forbidden-rule',
    (model) => model.forbiddenDependencies.pop(),
    'forbiddenDependencies must contain',
  );
  expectFailure(
    'missing-diagram-document',
    (model) => {
      model.documents.diagramViews = 'docs/architecture/does-not-exist.md';
    },
    'architecture document does not exist',
    false,
  );

  console.log(
    'Architecture verifier tests PASS: positive model accepted; duplicate ID, dangling edge, second truth store, missing edge, dependency cycle/drift, unknown owner, missing forbidden rule, and missing document blocked.',
  );
} finally {
  const resolvedTemporaryRoot = path.resolve(temporaryRoot);
  const safePrefix = path.resolve(os.tmpdir(), 'workflow-architecture-test-');
  if (!resolvedTemporaryRoot.startsWith(safePrefix)) {
    throw new Error(`Refusing to remove unexpected temporary path: ${resolvedTemporaryRoot}`);
  }
  rmSync(resolvedTemporaryRoot, { recursive: true, force: true });
}
