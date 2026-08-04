import { copyFileSync, lstatSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const gitleaksImage =
  'zricethezav/gitleaks:v8.28.0@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854';
const temporaryPrefix = path.join(os.tmpdir(), 'workflow-platform-secret-scan-');
const temporaryRoot = mkdtempSync(temporaryPrefix);

function run(command, args, options = {}) {
  return spawnSync(command, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    windowsHide: true,
    ...options,
  });
}

function commandOutput(result) {
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function stageCommitCandidates(destination) {
  const gitResult = run('git', [
    '-c',
    `safe.directory=${repositoryRoot.replaceAll('\\', '/')}`,
    '-c',
    'core.quotepath=false',
    '-C',
    repositoryRoot,
    'ls-files',
    '--cached',
    '--others',
    '--exclude-standard',
    '-z',
  ]);
  if (gitResult.status !== 0) {
    throw new Error(`Unable to enumerate commit candidates:\n${commandOutput(gitResult)}`);
  }

  const candidates = gitResult.stdout.split('\0').filter(Boolean);
  if (candidates.length === 0) throw new Error('No tracked or untracked commit candidates were found');

  for (const relativePath of candidates) {
    const source = path.resolve(repositoryRoot, relativePath);
    if (!source.startsWith(`${repositoryRoot}${path.sep}`)) {
      throw new Error(`Git returned a path outside the repository: ${relativePath}`);
    }
    if (lstatSync(source).isSymbolicLink()) {
      throw new Error(`Secret scanning requires explicit handling for symlink: ${relativePath}`);
    }
    const target = path.join(destination, relativePath);
    mkdirSync(path.dirname(target), { recursive: true });
    copyFileSync(source, target);
  }
  return candidates.length;
}

function scan(directory, expectedStatus) {
  const mount = `type=bind,source=${directory},target=/scan,readonly`;
  const result = run('docker', [
    'run',
    '--rm',
    '--mount',
    mount,
    '--workdir',
    '/scan',
    gitleaksImage,
    'dir',
    '.',
    '--config',
    '/scan/.gitleaks.toml',
    '--redact=100',
    '--no-banner',
    '--verbose',
    '--exit-code',
    '1',
  ]);
  if (result.error) throw result.error;
  if (result.status !== expectedStatus) {
    throw new Error(
      `Gitleaks returned ${result.status}; expected ${expectedStatus}:\n${commandOutput(result)}`,
    );
  }
  return commandOutput(result);
}

try {
  const positiveRoot = path.join(temporaryRoot, 'positive');
  mkdirSync(positiveRoot, { recursive: true });
  const candidateCount = stageCommitCandidates(positiveRoot);
  scan(positiveRoot, 0);

  const negativeRoot = path.join(temporaryRoot, 'negative');
  mkdirSync(negativeRoot, { recursive: true });
  copyFileSync(path.join(repositoryRoot, '.gitleaks.toml'), path.join(negativeRoot, '.gitleaks.toml'));
  const syntheticSecret = ['WORKFLOW_DATABASE_PASSWORD=', 'CorrectHorse', 'BatteryStaple_9x!'].join('');
  writeFileSync(path.join(negativeRoot, 'synthetic-secret.env'), `${syntheticSecret}\n`, 'utf8');
  const negativeOutput = scan(negativeRoot, 1);
  if (!negativeOutput.includes('workflow-generic-assigned-secret')) {
    throw new Error(`Negative control did not trigger the project-specific rule:\n${negativeOutput}`);
  }

  console.log(
    `Secret verification PASS: ${candidateCount} commit candidates clean; synthetic hard-coded secret blocked; image=${gitleaksImage}`,
  );
} finally {
  const resolvedTemporaryRoot = path.resolve(temporaryRoot);
  const resolvedPrefix = path.resolve(os.tmpdir(), 'workflow-platform-secret-scan-');
  if (!resolvedTemporaryRoot.startsWith(resolvedPrefix)) {
    throw new Error(`Refusing to remove unexpected temporary path: ${resolvedTemporaryRoot}`);
  }
  rmSync(resolvedTemporaryRoot, { recursive: true, force: true });
}
