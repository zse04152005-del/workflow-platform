import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const composeFile = path.join(repositoryRoot, 'deploy', 'compose', 'compose.yml');
const envFile = path.join(repositoryRoot, 'deploy', 'compose', '.env.example');

const result = spawnSync(
  'docker',
  ['compose', '--env-file', envFile, '-f', composeFile, 'config', '--format', 'json'],
  {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    windowsHide: true,
  },
);
if (result.error) throw result.error;
if (result.status !== 0) {
  throw new Error(`docker compose config failed:\n${result.stdout}${result.stderr}`);
}

const model = JSON.parse(result.stdout);
const services = Object.entries(model.services ?? {});
if (services.length !== 7) throw new Error(`Expected 7 Compose services, found ${services.length}`);

const longRunning = ['postgres', 'kafka', 'redis', 'minio', 'keycloak'];
const initServices = ['kafka-init', 'minio-init'];
for (const [name, service] of services) {
  if (!service.image.includes('@sha256:') || /:latest(?:@|$)/u.test(service.image)) {
    throw new Error(`${name} must use a non-latest tag plus immutable sha256 digest`);
  }
  for (const port of service.ports ?? []) {
    if (port.host_ip !== '127.0.0.1') {
      throw new Error(`${name} published port ${port.published} is not loopback-only`);
    }
  }
}
for (const name of longRunning) {
  const service = model.services[name];
  if (!service?.healthcheck?.test) throw new Error(`${name} must define a healthcheck`);
}
for (const name of initServices) {
  const service = model.services[name];
  if (!service || service.restart !== 'no') {
    throw new Error(`${name} must remain an explicit one-shot service with restart=no`);
  }
}

console.log(
  'Compose verification PASS: 7 fixed-digest services, 5 healthchecks, 2 one-shot initializers, loopback-only published ports.',
);
