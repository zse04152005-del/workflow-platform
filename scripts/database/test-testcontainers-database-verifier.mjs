import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const verifier = path.join(repositoryRoot, 'scripts', 'database', 'verify-testcontainers-database.mjs');
const sources = {
  contract: path.join(repositoryRoot, 'config', 'database', 'g2-11-testcontainers.json'),
  erModel: path.join(repositoryRoot, 'config', 'database', 'er-model.json'),
  javaTest: path.join(
    repositoryRoot,
    'control-plane',
    'src',
    'test',
    'java',
    'com',
    'workflowplatform',
    'controlplane',
    'database',
    'DatabaseMigrationIT.java',
  ),
  fixture: path.join(
    repositoryRoot,
    'control-plane',
    'src',
    'test',
    'resources',
    'database',
    'g2-11-query-plan-fixture.sql',
  ),
  pom: path.join(repositoryRoot, 'control-plane', 'pom.xml'),
  runner: path.join(repositoryRoot, 'scripts', 'database', 'test-g2-11-testcontainers.ps1'),
  packageJson: path.join(repositoryRoot, 'package.json'),
  document: path.join(
    repositoryRoot,
    'docs',
    'architecture',
    'testcontainers-database-verification.md',
  ),
};
const optionNames = {
  contract: '--contract',
  erModel: '--er-model',
  javaTest: '--java-test',
  fixture: '--fixture',
  pom: '--pom',
  runner: '--runner',
  packageJson: '--package-json',
  document: '--document',
};
const baseline = Object.fromEntries(
  Object.entries(sources).map(([name, file]) => [name, readFileSync(file, 'utf8')]),
);

function replaceOnce(source, search, replacement, label) {
  const position = source.indexOf(search);
  if (position < 0) throw new Error(`${label}: mutation anchor is missing`);
  return source.slice(0, position) + replacement + source.slice(position + search.length);
}

function runVerifier(files) {
  const args = [verifier];
  for (const [name, file] of Object.entries(files)) args.push(optionNames[name], file);
  return spawnSync(process.execPath, args, { cwd: repositoryRoot, encoding: 'utf8' });
}

const directBaseline = spawnSync(process.execPath, [verifier], {
  cwd: repositoryRoot,
  encoding: 'utf8',
});
if (directBaseline.status !== 0) {
  throw new Error(`baseline G2-11 verifier failed:\n${directBaseline.stdout}\n${directBaseline.stderr}`);
}

const cases = [
  {
    name: 'postgres-digest-drift',
    target: 'contract',
    expected: 'PostgreSQL Testcontainers contract drift',
    mutate: (source) => replaceOnce(source, '1961f96e', '2961f96e', 'postgres-digest-drift'),
  },
  {
    name: 'catalog-uniqueness-drift',
    target: 'contract',
    expected: 'reviewed PostgreSQL catalog drift',
    mutate: (source) =>
      replaceOnce(source, '"uniquenessGuards": 44', '"uniquenessGuards": 43', 'catalog-drift'),
  },
  {
    name: 'er-query-index-drift',
    target: 'erModel',
    expected: 'G2-11 query contracts versus ER model Q-01 through Q-10 drift',
    mutate: (source) =>
      replaceOnce(
        source,
        '"index": "idx_audit_log_tenant_time"',
        '"index": "idx_audit_log_resource"',
        'er-query-index-drift',
      ),
  },
  {
    name: 'container-reuse-enabled',
    target: 'javaTest',
    expected: 'DatabaseMigrationIT contract is missing: .withReuse(false)',
    mutate: (source) => replaceOnce(source, '.withReuse(false)', '.withReuse(true)', 'reuse-enabled'),
  },
  {
    name: 'planner-hint-added',
    target: 'fixture',
    expected: 'planner hints are forbidden',
    mutate: (source) => `SET enable_seqscan = off;\n${source}`,
  },
  {
    name: 'java-query-index-drift',
    target: 'javaTest',
    expected: 'Java query-contract ID/index order drift',
    mutate: (source) =>
      replaceOnce(
        source,
        '"idx_dead_letter_open"',
        '"idx_dead_letter_created"',
        'java-query-index-drift',
      ),
  },
  {
    name: 'fixture-row-count-reduced',
    target: 'fixture',
    expected: 'query fixture must use generate_series(1, 6000)',
    mutate: (source) => replaceOnce(source, 'generate_series(1, 6000)', 'generate_series(1, 5999)', 'rows'),
  },
  {
    name: 'fixture-statistics-removed',
    target: 'fixture',
    expected: 'fixture ANALYZE table order drift',
    mutate: (source) => replaceOnce(source, 'ANALYZE public.audit_log;', '', 'analyze-removed'),
  },
  {
    name: 'junit-testcontainers-dependency-removed',
    target: 'pom',
    expected: 'Testcontainers test dependency is missing: junit-jupiter',
    mutate: (source) => replaceOnce(source, '<artifactId>junit-jupiter</artifactId>', '<artifactId>junit-jupiter-removed</artifactId>', 'pom'),
  },
  {
    name: 'runner-reactor-scope-reduced',
    target: 'runner',
    expected: 'G2-11 PowerShell runner is missing: -pl control-plane -am verify',
    mutate: (source) => replaceOnce(source, '-pl control-plane -am verify', '-pl control-plane verify', 'runner'),
  },
  {
    name: 'package-command-drift',
    target: 'packageJson',
    expected: 'package command drift: database:g2-11:verify',
    mutate: (source) =>
      replaceOnce(
        source,
        'node scripts/database/verify-testcontainers-database.mjs',
        'node scripts/database/verify-testcontainers-database-removed.mjs',
        'package-command',
      ),
  },
  {
    name: 'compatibility-warning-removed',
    target: 'document',
    expected: 'G2-11 architecture document is missing: 最高已测试版本为 PostgreSQL 17',
    mutate: (source) =>
      replaceOnce(
        source,
        '最高已测试版本为 PostgreSQL 17',
        'PostgreSQL 18 已获完全支持',
        'warning-removed',
      ),
  },
];

const tempRoot = mkdtempSync(path.join(tmpdir(), 'workflow-g2-11-verifier-'));
try {
  for (const testCase of cases) {
    const files = {};
    for (const [name, content] of Object.entries(baseline)) {
      const extension = path.extname(sources[name]);
      const file = path.join(tempRoot, `${testCase.name}-${name}${extension}`);
      writeFileSync(
        file,
        name === testCase.target ? testCase.mutate(content) : content,
        'utf8',
      );
      files[name] = file;
    }
    const result = runVerifier(files);
    const output = `${result.stdout}\n${result.stderr}`;
    if (result.status === 0 || !output.includes(testCase.expected)) {
      throw new Error(
        `${testCase.name}: verifier did not reject mutation with ${testCase.expected}\n${output}`,
      );
    }
  }
} finally {
  rmSync(tempRoot, { recursive: true, force: true });
}

console.log(`G2-11 verifier negative tests PASS: ${cases.length}/${cases.length} mutations rejected.`);
