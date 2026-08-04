import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const envContracts = new Map([
  [
    '.env.example',
    [
      'SPRING_PROFILES_ACTIVE',
      'CONTROL_PLANE_PORT',
      'WORKFLOW_DATABASE_URL',
      'WORKFLOW_DATABASE_USERNAME',
      'WORKFLOW_DATABASE_PASSWORD',
      'WORKFLOW_DATABASE_MIGRATION_USERNAME',
      'WORKFLOW_DATABASE_MIGRATION_PASSWORD',
      'WORKFLOW_DATABASE_APPLICATION_ROLE',
      'WORKFLOW_DATABASE_PLATFORM_ADMIN_ROLE',
      'WORKFLOW_KAFKA_BOOTSTRAP_SERVERS',
      'WORKFLOW_KAFKA_PUBLISHER_ENABLED',
      'WORKFLOW_TASK_READY_TOPIC',
      'WORKFLOW_EVENTS_TOPIC',
      'WORKFLOW_REDIS_HOST',
      'WORKFLOW_REDIS_PORT',
      'WORKFLOW_REDIS_PASSWORD',
      'WORKFLOW_S3_ENDPOINT',
      'WORKFLOW_S3_ACCESS_KEY',
      'WORKFLOW_S3_SECRET_KEY',
      'WORKFLOW_S3_BUCKET',
      'WORKFLOW_PAYLOAD_INLINE_THRESHOLD_BYTES',
      'WORKFLOW_OIDC_ISSUER',
      'WORKFLOW_OTEL_ENDPOINT',
      'WORKFLOW_WORKER_CONCURRENCY',
      'WORKFLOW_WORKER_KAFKA_ENABLED',
      'WORKFLOW_WORKER_CONSUMER_NAME',
      'WORKFLOW_WORKER_INSTANCE_ID',
    ],
  ],
  [
    'deploy/compose/.env.example',
    [
      'COMPOSE_PROJECT_NAME',
      'POSTGRES_PORT',
      'POSTGRES_ADMIN_USER',
      'POSTGRES_ADMIN_PASSWORD',
      'POSTGRES_DB',
      'POSTGRES_MIGRATION_USER',
      'POSTGRES_MIGRATION_PASSWORD',
      'POSTGRES_APP_USER',
      'POSTGRES_APP_PASSWORD',
      'POSTGRES_PLATFORM_ADMIN_ROLE',
      'KAFKA_PORT',
      'REDIS_PORT',
      'REDIS_PASSWORD',
      'MINIO_API_PORT',
      'MINIO_CONSOLE_PORT',
      'MINIO_ROOT_USER',
      'MINIO_ROOT_PASSWORD',
      'MINIO_BUCKET',
      'KEYCLOAK_PORT',
      'KEYCLOAK_DB',
      'KEYCLOAK_DB_USER',
      'KEYCLOAK_DB_PASSWORD',
      'KEYCLOAK_ADMIN_USERNAME',
      'KEYCLOAK_ADMIN_PASSWORD',
    ],
  ],
  [
    'web-console/.env.example',
    ['VITE_API_BASE_URL', 'VITE_OIDC_ISSUER', 'VITE_OIDC_CLIENT_ID'],
  ],
]);

const sensitiveExamples = new Map([
  [
    '.env.example',
    [
      'WORKFLOW_DATABASE_PASSWORD',
      'WORKFLOW_DATABASE_MIGRATION_PASSWORD',
      'WORKFLOW_REDIS_PASSWORD',
      'WORKFLOW_S3_SECRET_KEY',
    ],
  ],
  [
    'deploy/compose/.env.example',
    [
      'POSTGRES_ADMIN_PASSWORD',
      'POSTGRES_MIGRATION_PASSWORD',
      'POSTGRES_APP_PASSWORD',
      'REDIS_PASSWORD',
      'MINIO_ROOT_PASSWORD',
      'KEYCLOAK_DB_PASSWORD',
      'KEYCLOAK_ADMIN_PASSWORD',
    ],
  ],
]);

const productionContracts = new Map([
  [
    'control-plane/src/main/resources/application-prod.yml',
    [
      'CONTROL_PLANE_PORT',
      'WORKFLOW_DATABASE_URL',
      'WORKFLOW_DATABASE_USERNAME',
      'WORKFLOW_DATABASE_PASSWORD',
      'WORKFLOW_DATABASE_MIGRATION_USERNAME',
      'WORKFLOW_DATABASE_MIGRATION_PASSWORD',
      'WORKFLOW_DATABASE_APPLICATION_ROLE',
      'WORKFLOW_DATABASE_PLATFORM_ADMIN_ROLE',
      'WORKFLOW_KAFKA_BOOTSTRAP_SERVERS',
      'WORKFLOW_KAFKA_PUBLISHER_ENABLED',
      'WORKFLOW_TASK_READY_TOPIC',
      'WORKFLOW_EVENTS_TOPIC',
      'WORKFLOW_REDIS_HOST',
      'WORKFLOW_REDIS_PORT',
      'WORKFLOW_REDIS_PASSWORD',
      'WORKFLOW_S3_ENDPOINT',
      'WORKFLOW_S3_ACCESS_KEY',
      'WORKFLOW_S3_SECRET_KEY',
      'WORKFLOW_S3_BUCKET',
      'WORKFLOW_PAYLOAD_INLINE_THRESHOLD_BYTES',
      'WORKFLOW_OIDC_ISSUER',
      'WORKFLOW_OTEL_ENDPOINT',
    ],
  ],
  [
    'worker/src/main/resources/application-prod.yml',
    [
      'WORKFLOW_DATABASE_URL',
      'WORKFLOW_DATABASE_USERNAME',
      'WORKFLOW_DATABASE_PASSWORD',
      'WORKFLOW_KAFKA_BOOTSTRAP_SERVERS',
      'WORKFLOW_REDIS_HOST',
      'WORKFLOW_REDIS_PORT',
      'WORKFLOW_REDIS_PASSWORD',
      'WORKFLOW_S3_ENDPOINT',
      'WORKFLOW_S3_ACCESS_KEY',
      'WORKFLOW_S3_SECRET_KEY',
      'WORKFLOW_S3_BUCKET',
      'WORKFLOW_PAYLOAD_INLINE_THRESHOLD_BYTES',
      'WORKFLOW_OTEL_ENDPOINT',
      'WORKFLOW_WORKER_CONCURRENCY',
      'WORKFLOW_WORKER_KAFKA_ENABLED',
      'WORKFLOW_WORKER_CONSUMER_NAME',
      'WORKFLOW_WORKER_INSTANCE_ID',
    ],
  ],
]);

function fail(message) {
  throw new Error(message);
}

function readRepositoryFile(relativePath) {
  return readFileSync(path.join(repositoryRoot, relativePath), 'utf8');
}

function parseEnv(relativePath) {
  const values = new Map();
  const lines = readRepositoryFile(relativePath).split(/\r?\n/u);
  lines.forEach((line, index) => {
    const trimmed = line.trim();
    if (trimmed === '' || trimmed.startsWith('#')) return;
    const match = /^([A-Z][A-Z0-9_]*)=(.*)$/u.exec(trimmed);
    if (!match) fail(`${relativePath}:${index + 1} is not KEY=value`);
    const [, key, value] = match;
    if (values.has(key)) fail(`${relativePath} contains duplicate key ${key}`);
    if (value === '') fail(`${relativePath} contains an empty example value for ${key}`);
    values.set(key, value);
  });
  return values;
}

function assertExactKeys(relativePath, values, expectedKeys) {
  const actual = [...values.keys()].sort();
  const expected = [...expectedKeys].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    const missing = expected.filter((key) => !values.has(key));
    const unexpected = actual.filter((key) => !expected.includes(key));
    fail(`${relativePath} key drift; missing=[${missing}], unexpected=[${unexpected}]`);
  }
}

const parsedEnvs = new Map();
for (const [relativePath, expectedKeys] of envContracts) {
  const values = parseEnv(relativePath);
  assertExactKeys(relativePath, values, expectedKeys);
  parsedEnvs.set(relativePath, values);
}

for (const [relativePath, keys] of sensitiveExamples) {
  const values = parsedEnvs.get(relativePath);
  for (const key of keys) {
    if (!values.get(key).endsWith('local-only-change-me')) {
      fail(`${relativePath}:${key} must be an explicit local-only placeholder`);
    }
  }
}

for (const key of parsedEnvs.get('web-console/.env.example').keys()) {
  if (/(?:PASSWORD|SECRET|TOKEN|PRIVATE_KEY)/u.test(key)) {
    fail(`Vite client configuration must never expose sensitive key ${key}`);
  }
}

for (const moduleName of ['control-plane', 'worker']) {
  const basePath = `${moduleName}/src/main/resources/application.yml`;
  const base = readRepositoryFile(basePath);
  if (!base.includes('optional:file:.env[.properties]')) {
    fail(`${basePath} must import the ignored root .env file as optional properties`);
  }
  if (!/profiles:\s*\r?\n\s+default:\s+local/u.test(base)) {
    fail(`${basePath} must declare local as the explicit developer default profile`);
  }

  const local = readRepositoryFile(`${moduleName}/src/main/resources/application-local.yml`);
  for (const key of ['WORKFLOW_DATABASE_PASSWORD', 'WORKFLOW_REDIS_PASSWORD', 'WORKFLOW_S3_SECRET_KEY']) {
    if (!local.includes(`\${${key}}`)) {
      fail(`${moduleName} local profile must require ${key} without a committed default`);
    }
  }

  const test = readRepositoryFile(`${moduleName}/src/main/resources/application-test.yml`);
  if (!test.includes('test-only')) {
    fail(`${moduleName} test profile must use visibly non-production test-only placeholders`);
  }
}

for (const [relativePath, requiredKeys] of productionContracts) {
  const contents = readRepositoryFile(relativePath);
  if (/local-only|test-only|\$\{[A-Z0-9_]+:[^}]+\}/u.test(contents)) {
    fail(`${relativePath} contains a local/test value or a production fallback`);
  }
  const placeholders = new Set(
    [...contents.matchAll(/\$\{([A-Z][A-Z0-9_]*)\}/gu)].map((match) => match[1]),
  );
  const missing = requiredKeys.filter((key) => !placeholders.has(key));
  const unexpected = [...placeholders].filter((key) => !requiredKeys.includes(key));
  if (missing.length > 0 || unexpected.length > 0) {
    fail(`${relativePath} placeholder drift; missing=[${missing}], unexpected=[${unexpected}]`);
  }
}

const gitignore = readRepositoryFile('.gitignore');
for (const requiredRule of [
  '.env',
  '.env.*',
  '!.env.example',
  '!**/.env.example',
  '*.pem',
  '*.key',
  '*.p12',
]) {
  if (!gitignore.split(/\r?\n/u).includes(requiredRule)) {
    fail(`.gitignore is missing required secret protection rule ${requiredRule}`);
  }
}

const totalEnvKeys = [...parsedEnvs.values()].reduce((total, values) => total + values.size, 0);
console.log(
  `Configuration verification PASS: ${envContracts.size} env contracts, ${totalEnvKeys} keys, 6 profile files, 2 no-fallback production profiles.`,
);
