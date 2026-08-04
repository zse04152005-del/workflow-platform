import { createHash } from 'node:crypto';
import {
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { TextDecoder } from 'node:util';

const repositoryRootOption = process.argv.indexOf('--repository-root');
if (repositoryRootOption >= 0 && !process.argv[repositoryRootOption + 1]) {
  throw new Error('--repository-root requires a path');
}
const repositoryRoot =
  repositoryRootOption >= 0
    ? path.resolve(process.argv[repositoryRootOption + 1])
    : path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const migrationDirectory = path.join(
  repositoryRoot,
  'control-plane',
  'src',
  'main',
  'resources',
  'db',
  'migration',
);
const checksumFile = path.join(repositoryRoot, 'config', 'migrations', 'checksums.json');
const writeMode = process.argv.includes('--write');
const migrationName = /^V([1-9][0-9]*(?:_[0-9]+)*)__([a-z][a-z0-9_]*)\.sql$/u;

function fail(message) {
  throw new Error(message);
}

if (!existsSync(migrationDirectory) || !statSync(migrationDirectory).isDirectory()) {
  fail(`Migration directory is missing: ${migrationDirectory}`);
}

const directoryEntries = readdirSync(migrationDirectory, { withFileTypes: true });
const unsupportedEntries = directoryEntries.filter(
  (entry) => entry.name !== '.gitkeep' && (!entry.isFile() || !entry.name.endsWith('.sql')),
);
if (unsupportedEntries.length > 0) {
  fail(`Unsupported migration directory entries: ${unsupportedEntries.map((entry) => entry.name)}`);
}

const migrations = directoryEntries
  .filter((entry) => entry.isFile() && entry.name.endsWith('.sql'))
  .map((entry) => {
    const nameMatch = migrationName.exec(entry.name);
    if (!nameMatch) {
      fail(
        `Invalid migration name ${entry.name}; expected V<positive_version>__<lower_snake_case>.sql`,
      );
    }
    const filePath = path.join(migrationDirectory, entry.name);
    const bytes = readFileSync(filePath);
    if (bytes.length === 0) fail(`${entry.name} must not be empty`);
    if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
      fail(`${entry.name} must not contain a UTF-8 BOM`);
    }
    let contents;
    try {
      contents = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch {
      fail(`${entry.name} must be valid UTF-8`);
    }
    if (contents.includes('\r')) fail(`${entry.name} must use LF line endings`);
    if (!contents.endsWith('\n')) fail(`${entry.name} must end with a newline`);
    const version = nameMatch[1].split('_').map(Number);
    const checksum = createHash('sha256').update(bytes).digest('hex');
    return { name: entry.name, version, checksum };
  });

function compareVersions(left, right) {
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    const difference = (left[index] ?? 0) - (right[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

migrations.sort((left, right) => compareVersions(left.version, right.version));
for (let index = 1; index < migrations.length; index += 1) {
  if (compareVersions(migrations[index - 1].version, migrations[index].version) === 0) {
    fail(`Duplicate migration version: ${migrations[index - 1].name}, ${migrations[index].name}`);
  }
}

if (writeMode) {
  const generated = {
    schemaVersion: 1,
    algorithm: 'sha256',
    migrations: Object.fromEntries(migrations.map(({ name, checksum }) => [name, checksum])),
  };
  writeFileSync(checksumFile, `${JSON.stringify(generated, null, 2)}\n`, 'utf8');
  console.log(`Migration checksum catalog updated: ${migrations.length} migration(s).`);
  process.exit(0);
}

const catalog = JSON.parse(readFileSync(checksumFile, 'utf8'));
if (catalog.schemaVersion !== 1 || catalog.algorithm !== 'sha256') {
  fail('Migration checksum catalog metadata must be schemaVersion=1 and algorithm=sha256');
}
if (catalog.migrations === null || typeof catalog.migrations !== 'object') {
  fail('Migration checksum catalog migrations must be an object');
}

const actualNames = migrations.map(({ name }) => name).sort();
const catalogNames = Object.keys(catalog.migrations).sort();
if (JSON.stringify(actualNames) !== JSON.stringify(catalogNames)) {
  const missing = actualNames.filter((name) => !(name in catalog.migrations));
  const stale = catalogNames.filter((name) => !actualNames.includes(name));
  fail(`Migration checksum catalog drift; missing=[${missing}], stale=[${stale}]`);
}
for (const { name, checksum } of migrations) {
  if (catalog.migrations[name] !== checksum) {
    fail(`Migration checksum mismatch for ${name}; applied migrations are immutable`);
  }
}

console.log(
  `Migration verification PASS: ${migrations.length} migration(s), unique ordered versions, immutable SHA-256 catalog.`,
);
