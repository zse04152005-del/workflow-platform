import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const expectedModules = ['security', 'audit', 'tenant', 'definition', 'runtime', 'scheduler', 'human-task'];
const expectedForbiddenDependencies = Array.from(
  { length: 7 },
  (_, index) => `FD-${String(index + 1).padStart(2, '0')}`,
);
const allowedDeploymentStatuses = new Set([
  'scaffolded',
  'local-compose-available',
  'planned',
]);
const allowedDataClasses = new Set(['public', 'internal', 'confidential', 'secret']);

function parseArguments(argv) {
  const options = {
    model: path.join(repositoryRoot, 'config', 'architecture', 'model.json'),
    skipDocuments: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--skip-documents') {
      options.skipDocuments = true;
    } else if (argument === '--model') {
      const value = argv[index + 1];
      if (!value) throw new Error('--model requires a path');
      options.model = path.resolve(repositoryRoot, value);
      index += 1;
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return options;
}

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`Unable to read JSON ${file}: ${error.message}`);
  }
}

function validateIdCollection(items, label, globalIds, errors) {
  if (!Array.isArray(items) || items.length === 0) {
    errors.push(`${label} must be a non-empty array`);
    return;
  }
  const local = new Set();
  for (const item of items) {
    if (!item || typeof item !== 'object' || typeof item.id !== 'string') {
      errors.push(`${label} contains an entry without an ID`);
      continue;
    }
    if (!/^[a-z][a-z0-9-]*$/u.test(item.id)) errors.push(`${label} has invalid ID ${item.id}`);
    if (local.has(item.id)) errors.push(`duplicate ${label} ID: ${item.id}`);
    if (globalIds.has(item.id)) errors.push(`duplicate global architecture ID: ${item.id}`);
    local.add(item.id);
    globalIds.add(item.id);
    if (typeof item.name === 'string' && !item.name.trim()) errors.push(`${item.id} has an empty name`);
    if (typeof item.responsibility !== 'string' || !item.responsibility.trim()) {
      errors.push(`${item.id} must have a responsibility`);
    }
  }
}

function validateRelationships(relationships, knownIds, label, errors, context = false) {
  if (!Array.isArray(relationships) || relationships.length === 0) {
    errors.push(`${label} must be a non-empty array`);
    return;
  }
  const keys = new Set();
  for (const relationship of relationships) {
    const key = `${relationship?.from}->${relationship?.to}`;
    if (keys.has(key)) errors.push(`${label} contains duplicate relationship ${key}`);
    keys.add(key);
    if (!knownIds.has(relationship?.from)) {
      errors.push(`${label} has dangling source ${String(relationship?.from)}`);
    }
    if (!knownIds.has(relationship?.to)) {
      errors.push(`${label} has dangling target ${String(relationship?.to)}`);
    }
    if (typeof relationship?.protocol !== 'string' || !relationship.protocol.trim()) {
      errors.push(`${label} ${key} must declare a protocol`);
    }
    if (typeof relationship?.description !== 'string' || !relationship.description.trim()) {
      errors.push(`${label} ${key} must declare a description`);
    }
    if (context && !allowedDataClasses.has(relationship?.dataClass)) {
      errors.push(`${label} ${key} has invalid dataClass ${String(relationship?.dataClass)}`);
    }
    if (!context && typeof relationship?.trustBoundaryCrossing !== 'boolean') {
      errors.push(`${label} ${key} must declare trustBoundaryCrossing`);
    }
  }
}

function requireRelationship(relationships, from, to, protocolToken, errors) {
  const relationship = relationships.find((item) => item.from === from && item.to === to);
  if (!relationship) {
    errors.push(`missing required container relationship: ${from}->${to}`);
  } else if (!relationship.protocol.includes(protocolToken)) {
    errors.push(`${from}->${to} protocol must contain ${protocolToken}`);
  }
}

function parseModuleBoundary(sourceFile, errors) {
  if (!existsSync(sourceFile)) {
    errors.push(`module boundary source does not exist: ${path.relative(repositoryRoot, sourceFile)}`);
    return null;
  }
  const source = readFileSync(sourceFile, 'utf8');
  const moduleId = source.match(
    /@ModuleBoundary\(\s*(?:value\s*=\s*)?"([^"]+)"/u,
  )?.[1];
  if (!moduleId) {
    errors.push(`unable to parse @ModuleBoundary in ${path.relative(repositoryRoot, sourceFile)}`);
    return null;
  }
  const dependencyBlock = source.match(/allowedDependencies\s*=\s*\{([^}]*)\}/u)?.[1] ?? '';
  const dependencies = [...dependencyBlock.matchAll(/"([^"]+)"/gu)].map((match) => match[1]);
  return { moduleId, dependencies };
}

function findCycle(modules) {
  const graph = new Map(modules.map((module) => [module.id, module.allowedDependencies]));
  const visiting = new Set();
  const visited = new Set();
  function visit(node, trail) {
    if (visiting.has(node)) return [...trail, node];
    if (visited.has(node)) return null;
    visiting.add(node);
    for (const dependency of graph.get(node) ?? []) {
      const cycle = visit(dependency, [...trail, node]);
      if (cycle) return cycle;
    }
    visiting.delete(node);
    visited.add(node);
    return null;
  }
  for (const node of graph.keys()) {
    const cycle = visit(node, []);
    if (cycle) return cycle;
  }
  return null;
}

function validateModules(model, errors) {
  const modules = model.controlPlaneModules;
  if (!Array.isArray(modules)) {
    errors.push('controlPlaneModules must be an array');
    return;
  }
  const ids = modules.map((module) => module.id);
  if (new Set(ids).size !== ids.length) errors.push('controlPlaneModules contains duplicate IDs');
  if (JSON.stringify(ids) !== JSON.stringify(expectedModules)) {
    errors.push(`controlPlaneModules must equal ${expectedModules.join(', ')}`);
  }
  const known = new Set(ids);
  for (const module of modules) {
    if (!Array.isArray(module.allowedDependencies)) {
      errors.push(`${module.id}.allowedDependencies must be an array`);
      continue;
    }
    if (new Set(module.allowedDependencies).size !== module.allowedDependencies.length) {
      errors.push(`${module.id}.allowedDependencies contains duplicates`);
    }
    for (const dependency of module.allowedDependencies) {
      if (!known.has(dependency)) errors.push(`${module.id} has unknown dependency ${dependency}`);
      if (dependency === module.id) errors.push(`${module.id} may not depend on itself`);
    }
    const source = path.join(
      repositoryRoot,
      'control-plane',
      'src',
      'main',
      'java',
      'com',
      'workflowplatform',
      'controlplane',
      module.javaPackageSegment,
      'package-info.java',
    );
    const declaration = parseModuleBoundary(source, errors);
    if (
      declaration &&
      (declaration.moduleId !== module.id ||
        JSON.stringify(declaration.dependencies) !== JSON.stringify(module.allowedDependencies))
    ) {
      errors.push(
        `module dependency drift for ${module.id}: model=[${module.allowedDependencies}], source=${declaration.moduleId}[${declaration.dependencies}]`,
      );
    }
  }
  const cycle = findCycle(modules);
  if (cycle) errors.push(`module dependency graph contains cycle: ${cycle.join(' -> ')}`);
}

function validateDataOwnership(model, errors) {
  const owners = new Set([
    ...(model.controlPlaneModules ?? []).map((module) => module.id),
    ...(model.technicalComponents ?? []).map((component) => component.id),
  ]);
  const writers = new Set([...owners, 'worker']);
  const seenDatasets = new Set();
  const seenTables = new Set();
  if (!Array.isArray(model.dataOwnership) || model.dataOwnership.length === 0) {
    errors.push('dataOwnership must be a non-empty array');
    return;
  }
  for (const entry of model.dataOwnership) {
    if (typeof entry?.dataset !== 'string' || !entry.dataset.trim()) {
      errors.push('dataOwnership entry must have a dataset');
      continue;
    }
    if (seenDatasets.has(entry.dataset)) errors.push(`duplicate dataOwnership dataset: ${entry.dataset}`);
    seenDatasets.add(entry.dataset);
    for (const table of entry.dataset.split(',').map((item) => item.trim())) {
      if (seenTables.has(table)) errors.push(`table/capability appears under multiple owners: ${table}`);
      seenTables.add(table);
    }
    if (!owners.has(entry.owner)) errors.push(`dataOwnership has unknown owner ${String(entry.owner)}`);
    if (!Array.isArray(entry.authorizedWriters) || entry.authorizedWriters.length === 0) {
      errors.push(`${entry.dataset} must have at least one authorized writer`);
    } else {
      for (const writer of entry.authorizedWriters) {
        if (!writers.has(writer)) errors.push(`${entry.dataset} has unknown authorized writer ${writer}`);
      }
    }
    if (typeof entry.accessRule !== 'string' || !entry.accessRule.trim()) {
      errors.push(`${entry.dataset} must declare an access rule`);
    }
  }
  const runtime = model.dataOwnership.find((entry) => entry.owner === 'runtime');
  if (!runtime?.authorizedWriters.includes('worker')) {
    errors.push('runtime execution data must explicitly authorize the constrained worker protocol');
  }
  for (const entry of model.dataOwnership.filter((item) => item.owner !== 'runtime')) {
    if (entry.authorizedWriters?.includes('worker')) {
      errors.push(`worker may not write non-runtime dataset ${entry.dataset}`);
    }
  }
}

function validateForbiddenDependencies(model, errors) {
  const rules = model.forbiddenDependencies;
  if (!Array.isArray(rules)) {
    errors.push('forbiddenDependencies must be an array');
    return;
  }
  const ids = rules.map((rule) => rule.id);
  if (new Set(ids).size !== ids.length) errors.push('forbiddenDependencies contains duplicate IDs');
  if (JSON.stringify(ids) !== JSON.stringify(expectedForbiddenDependencies)) {
    errors.push(`forbiddenDependencies must contain ${expectedForbiddenDependencies.join(', ')}`);
  }
  for (const rule of rules) {
    if (typeof rule.rule !== 'string' || !rule.rule.trim()) errors.push(`${rule.id} has an empty rule`);
  }
}

function validateDocuments(model, errors) {
  const documents = model.documents;
  if (!documents || typeof documents !== 'object') {
    errors.push('documents map is required');
    return;
  }
  for (const [name, file] of Object.entries(documents)) {
    const resolved = path.resolve(repositoryRoot, file);
    if (!resolved.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(resolved)) {
      errors.push(`architecture document does not exist (${name}): ${String(file)}`);
    }
  }
  const diagramPath = path.resolve(repositoryRoot, documents.diagramViews ?? '');
  if (existsSync(diagramPath)) {
    const diagram = readFileSync(diagramPath, 'utf8');
    const mermaidCount = (diagram.match(/```mermaid/gu) ?? []).length;
    if (mermaidCount < 3) errors.push(`diagram document must contain at least 3 Mermaid views; found ${mermaidCount}`);
    for (const item of [
      ...(model.people ?? []),
      ...(model.externalSystems ?? []),
      ...(model.containers ?? []),
    ]) {
      if (!diagram.includes(item.name)) errors.push(`diagram document is missing architecture element ${item.id}`);
    }
    for (const module of model.controlPlaneModules ?? []) {
      if (!diagram.includes(module.id)) errors.push(`diagram document is missing module ${module.id}`);
    }
    for (const rule of model.forbiddenDependencies ?? []) {
      if (!diagram.includes(rule.id)) errors.push(`diagram document is missing forbidden dependency ${rule.id}`);
    }
    for (const token of ['唯一事实源', 'scaffolded', 'local-compose-available', 'planned', 'OutboxPort']) {
      if (!diagram.includes(token)) errors.push(`diagram document is missing boundary token: ${token}`);
    }
  }
  const indexPath = path.resolve(repositoryRoot, documents.architectureIndex ?? '');
  if (existsSync(indexPath)) {
    const index = readFileSync(indexPath, 'utf8');
    for (const token of ['c4-architecture-views.md', 'model.json', 'architecture:verify']) {
      if (!index.includes(token)) errors.push(`architecture index is missing token: ${token}`);
    }
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const model = readJson(options.model);
  const errors = [];
  if (model.schemaVersion !== 1) errors.push('schemaVersion must be 1');
  if (!/^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/u.test(model.modelVersion ?? '')) {
    errors.push('modelVersion must be stable SemVer');
  }
  if (model.task !== 'G2-01') errors.push('architecture model must remain linked to G2-01');
  if (model.system?.id !== 'workflow-platform' || !model.system?.name || !model.system?.purpose) {
    errors.push('system under design must be workflow-platform with name and purpose');
  }

  const contextIds = new Set([model.system?.id]);
  validateIdCollection(model.people, 'people', contextIds, errors);
  validateIdCollection(model.externalSystems, 'externalSystems', contextIds, errors);
  validateRelationships(model.contextRelationships, contextIds, 'contextRelationships', errors, true);
  for (const person of model.people ?? []) {
    if (!model.contextRelationships?.some((item) => item.from === person.id && item.to === model.system.id)) {
      errors.push(`person lacks system-context relationship: ${person.id}`);
    }
  }

  const containerIds = new Set([
    ...(model.people ?? []).map((item) => item.id),
    ...(model.externalSystems ?? []).map((item) => item.id),
  ]);
  validateIdCollection(model.containers, 'containers', containerIds, errors);
  for (const container of model.containers ?? []) {
    if (!allowedDeploymentStatuses.has(container.deploymentStatus)) {
      errors.push(`${container.id} has invalid deploymentStatus ${String(container.deploymentStatus)}`);
    }
    if (typeof container.authoritativeState !== 'boolean') {
      errors.push(`${container.id} must declare authoritativeState`);
    }
    if (typeof container.trustBoundary !== 'string' || !container.trustBoundary.trim()) {
      errors.push(`${container.id} must declare a trustBoundary`);
    }
  }
  const authoritative = (model.containers ?? []).filter((container) => container.authoritativeState);
  if (authoritative.length !== 1 || authoritative[0].id !== 'postgres') {
    errors.push(
      `PostgreSQL must be the only authoritative state store; found [${authoritative.map((item) => item.id)}]`,
    );
  }
  validateRelationships(
    model.containerRelationships,
    containerIds,
    'containerRelationships',
    errors,
    false,
  );
  for (const [from, to, protocol] of [
    ['web-console', 'control-plane', 'OpenAPI'],
    ['keycloak', 'control-plane', 'OIDC'],
    ['control-plane', 'postgres', 'JDBC'],
    ['control-plane', 'kafka', 'Kafka'],
    ['control-plane', 'redis', 'Redis'],
    ['control-plane', 'minio', 'S3'],
    ['kafka', 'worker', 'Kafka'],
    ['worker', 'postgres', 'JDBC'],
    ['worker', 'minio', 'S3'],
    ['worker', 'external-business', 'HTTPS'],
    ['control-plane', 'otel-collector', 'OTLP'],
    ['worker', 'otel-collector', 'OTLP'],
  ]) {
    requireRelationship(model.containerRelationships ?? [], from, to, protocol, errors);
  }

  validateModules(model, errors);
  validateIdCollection(model.technicalComponents, 'technicalComponents', new Set(), errors);
  validateDataOwnership(model, errors);
  validateForbiddenDependencies(model, errors);
  if (!options.skipDocuments) validateDocuments(model, errors);

  if (errors.length > 0) {
    throw new Error(`Architecture model verification failed:\n- ${errors.join('\n- ')}`);
  }
  console.log(
    `Architecture verification PASS: people=${model.people.length}, externalSystems=${model.externalSystems.length}, containers=${model.containers.length}, ` +
      `containerRelationships=${model.containerRelationships.length}, modules=${model.controlPlaneModules.length}, datasets=${model.dataOwnership.length}, ` +
      `forbiddenDependencies=${model.forbiddenDependencies.length}, authoritativeStore=postgres.`,
  );
}

main();
