import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const artifactFields = ['api', 'events', 'tables', 'tests', 'docs'];
const allowedScopes = new Set(['mvp', 'phase2', 'out-of-scope']);
const allowedPriorities = new Set(['P0', 'P1', 'P2']);
const allowedArtifactStatuses = new Set([
  'planned',
  'implemented',
  'not-applicable',
  'out-of-scope',
]);
const taskRanges = new Map([
  ['G2', 11],
  ['G3', 10],
  ['G4', 17],
  ['G5', 13],
  ['G6', 12],
  ['G7', 8],
  ['G8', 11],
  ['G9', 9],
  ['G10', 11],
  ['G11', 10],
  ['G12', 11],
]);
const requiredControlTasks = new Set(
  [...taskRanges.entries()].flatMap(([group, count]) =>
    Array.from({ length: count }, (_, index) => `${group}-${String(index + 1).padStart(2, '0')}`),
  ),
);

function parseArguments(argv) {
  const options = {
    requirements: path.join(repositoryRoot, 'config', 'traceability', 'requirements.json'),
    markdown: path.join(repositoryRoot, 'docs', 'planning', '03-需求追踪矩阵.md'),
    skipMarkdown: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--skip-markdown') {
      options.skipMarkdown = true;
    } else if (argument === '--requirements' || argument === '--markdown') {
      const value = argv[index + 1];
      if (!value) throw new Error(`${argument} requires a path`);
      options[argument.slice(2)] = path.resolve(repositoryRoot, value);
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
    throw new Error(`Unable to read requirements JSON ${file}: ${error.message}`);
  }
}

function taskGroup(taskId) {
  return taskId.match(/^(G\d+)-/u)?.[1];
}

function validateImplementedReference(item, errors, requirementId, field) {
  const fileReference = item.match(/\bfile:([^\s,;\]]+)/u)?.[1];
  if (!fileReference) {
    errors.push(`${requirementId}.${field}: implemented artifact must include file:<repository-path>`);
    return;
  }
  const resolved = path.resolve(repositoryRoot, fileReference);
  if (!resolved.startsWith(`${repositoryRoot}${path.sep}`) || !existsSync(resolved)) {
    errors.push(`${requirementId}.${field}: implemented file does not exist: ${fileReference}`);
  }
}

function validateArtifactList(requirement, field, errors) {
  const items = requirement[field];
  if (!Array.isArray(items) || items.length === 0) {
    errors.push(`${requirement.id}.${field}: artifact mapping must not be empty`);
    return;
  }
  for (const item of items) {
    if (typeof item !== 'string' || !item.trim()) {
      errors.push(`${requirement.id}.${field}: artifact entries must be non-empty strings`);
      continue;
    }
    const match = item.match(/^([a-z-]+):\s+\S/u);
    const status = match?.[1];
    if (!status || !allowedArtifactStatuses.has(status)) {
      errors.push(`${requirement.id}.${field}: invalid or missing artifact status in "${item}"`);
      continue;
    }
    if (requirement.scope === 'out-of-scope' && status !== 'out-of-scope') {
      errors.push(`${requirement.id}.${field}: out-of-scope requirement must use out-of-scope artifacts`);
    }
    if (requirement.scope !== 'out-of-scope' && status === 'out-of-scope') {
      errors.push(`${requirement.id}.${field}: active scope may not use out-of-scope artifacts`);
    }
    if (requirement.scope === 'phase2' && status === 'implemented') {
      errors.push(`${requirement.id}.${field}: phase2 artifact may not be marked implemented in the MVP baseline`);
    }
    if (status === 'implemented') {
      validateImplementedReference(item, errors, requirement.id, field);
    }
  }
}

function validateRequirement(requirement, index, seenIds, coveredTasks, coveredGates, errors) {
  if (!requirement || typeof requirement !== 'object' || Array.isArray(requirement)) {
    errors.push(`requirements[${index}]: entry must be an object`);
    return;
  }
  const id = requirement.id;
  if (typeof id !== 'string' || !/^REQ-[A-Z0-9]+(?:-[A-Z0-9]+)*-\d{3}$/u.test(id)) {
    errors.push(`requirements[${index}]: invalid Requirement ID ${String(id)}`);
  } else if (seenIds.has(id)) {
    errors.push(`duplicate Requirement ID: ${id}`);
  } else {
    seenIds.add(id);
  }
  const safeId = typeof id === 'string' ? id : `requirements[${index}]`;

  if (typeof requirement.title !== 'string' || !requirement.title.trim()) {
    errors.push(`${safeId}: title is required`);
  }
  if (!Array.isArray(requirement.source) || requirement.source.length === 0) {
    errors.push(`${safeId}: source must contain at least one outline/audit reference`);
  } else {
    for (const source of requirement.source) {
      if (typeof source !== 'string' || !/^(OUTLINE|AUDIT|CONTROL)\s/u.test(source)) {
        errors.push(`${safeId}: invalid source reference ${String(source)}`);
      }
    }
  }
  if (!allowedScopes.has(requirement.scope)) {
    errors.push(`${safeId}: invalid scope ${String(requirement.scope)}`);
  }
  if (!allowedPriorities.has(requirement.priority)) {
    errors.push(`${safeId}: invalid priority ${String(requirement.priority)}`);
  }

  const tasks = requirement.controlTasks;
  if (!Array.isArray(tasks)) {
    errors.push(`${safeId}: controlTasks must be an array`);
  } else {
    const localTasks = new Set();
    for (const task of tasks) {
      if (!requiredControlTasks.has(task)) {
        errors.push(`${safeId}: unknown control task ${String(task)}`);
        continue;
      }
      if (localTasks.has(task)) errors.push(`${safeId}: duplicate control task ${task}`);
      localTasks.add(task);
      coveredTasks.add(task);
      const group = taskGroup(task);
      if (requirement.scope === 'mvp' && group === 'G12') {
        errors.push(`${safeId}: MVP requirements may not map G12 tasks`);
      }
      if (requirement.scope === 'phase2' && group !== 'G12') {
        errors.push(`${safeId}: phase2 requirements may only map G12 tasks`);
      }
    }
    if (requirement.scope === 'mvp' && tasks.length === 0) {
      errors.push(`${safeId}: MVP requirement must map at least one control task`);
    }
    if (requirement.scope === 'phase2' && tasks.length === 0) {
      errors.push(`${safeId}: phase2 requirement must map at least one G12 task`);
    }
    if (requirement.scope === 'out-of-scope' && tasks.length !== 0) {
      errors.push(`${safeId}: out-of-scope requirement must not claim implementation tasks`);
    }
  }

  for (const field of artifactFields) validateArtifactList(requirement, field, errors);
  const artifactStatuses = artifactFields.flatMap((field) =>
    Array.isArray(requirement[field])
      ? requirement[field]
          .map((item) => (typeof item === 'string' ? item.match(/^([a-z-]+):/u)?.[1] : undefined))
          .filter(Boolean)
      : [],
  );
  const implementedCount = artifactStatuses.filter((status) => status === 'implemented').length;
  const plannedCount = artifactStatuses.filter((status) => status === 'planned').length;
  if (requirement.scope === 'mvp') {
    if (!['planned', 'partial', 'implemented'].includes(requirement.implementationStatus)) {
      errors.push(`${safeId}: MVP implementationStatus must be planned, partial, or implemented`);
    } else if (requirement.implementationStatus === 'planned' && implementedCount > 0) {
      errors.push(`${safeId}: planned requirement may not contain implemented artifacts`);
    } else if (
      requirement.implementationStatus === 'partial' &&
      (implementedCount === 0 || plannedCount === 0)
    ) {
      errors.push(`${safeId}: partial requirement must contain both implemented and planned artifacts`);
    } else if (requirement.implementationStatus === 'implemented' && plannedCount > 0) {
      errors.push(`${safeId}: implemented requirement may not contain planned artifacts`);
    }
  } else {
    const expectedStatus = requirement.scope === 'phase2' ? 'phase2' : 'out-of-scope';
    if (requirement.implementationStatus !== expectedStatus) {
      errors.push(
        `${safeId}: implementationStatus must be ${expectedStatus} for scope ${String(requirement.scope)}`,
      );
    }
  }

  if (!Array.isArray(requirement.acceptanceGates) || requirement.acceptanceGates.length === 0) {
    errors.push(`${safeId}: acceptanceGates must not be empty`);
  } else {
    for (const gate of requirement.acceptanceGates) {
      if (typeof gate !== 'string' || !/^(G(?:2|3|4|5|6|7|8|9|10|11|12)|MVP-(?:0[1-9]|10)|SCOPE-GUARD)$/u.test(gate)) {
        errors.push(`${safeId}: invalid acceptance gate ${String(gate)}`);
      } else {
        coveredGates.add(gate);
      }
    }
  }
}

function validateMarkdown(file, requirements, errors) {
  if (!existsSync(file)) {
    errors.push(`traceability Markdown does not exist: ${path.relative(repositoryRoot, file)}`);
    return;
  }
  const markdown = readFileSync(file, 'utf8');
  const rowIds = [...markdown.matchAll(/^\| `([^`]+)` \|/gmu)].map((match) => match[1]);
  const expectedIds = requirements.map((requirement) => requirement.id);
  const rowSet = new Set(rowIds);
  for (const id of expectedIds) {
    if (!rowSet.has(id)) errors.push(`traceability Markdown is missing row ${id}`);
  }
  for (const id of rowIds) {
    if (!expectedIds.includes(id)) errors.push(`traceability Markdown contains unknown row ${id}`);
  }
  if (rowIds.length !== expectedIds.length || rowSet.size !== rowIds.length) {
    errors.push(
      `traceability Markdown row count/uniqueness mismatch: rows=${rowIds.length}, unique=${rowSet.size}, expected=${expectedIds.length}`,
    );
  }
  for (const requirement of requirements) {
    const row = markdown.split(/\r?\n/u).find((line) => line.startsWith(`| \`${requirement.id}\` |`));
    if (row && !row.includes(requirement.title)) {
      errors.push(`traceability Markdown title mismatch for ${requirement.id}`);
    }
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const document = readJson(options.requirements);
  const errors = [];
  if (document.schemaVersion !== 1) errors.push('schemaVersion must be 1');
  if (!document.baseline || typeof document.baseline !== 'object') {
    errors.push('baseline metadata is required');
  }
  const requirements = document.requirements;
  if (!Array.isArray(requirements) || requirements.length === 0) {
    errors.push('requirements must be a non-empty array');
  } else {
    const seenIds = new Set();
    const coveredTasks = new Set();
    const coveredGates = new Set();
    requirements.forEach((requirement, index) =>
      validateRequirement(requirement, index, seenIds, coveredTasks, coveredGates, errors),
    );
    const missingTasks = [...requiredControlTasks].filter((task) => !coveredTasks.has(task));
    if (missingTasks.length > 0) {
      errors.push(`missing control task coverage: ${missingTasks.join(', ')}`);
    }
    const requiredMvpGates = document.baseline?.requiredMvpAcceptanceGates;
    if (!Array.isArray(requiredMvpGates) || requiredMvpGates.length !== 10) {
      errors.push('baseline.requiredMvpAcceptanceGates must contain the ten audit gates');
    } else {
      const missingGates = requiredMvpGates.filter((gate) => !coveredGates.has(gate));
      if (missingGates.length > 0) {
        errors.push(`missing MVP acceptance gate coverage: ${missingGates.join(', ')}`);
      }
    }
    const scopeCounts = new Map(
      [...allowedScopes].map((scope) => [
        scope,
        requirements.filter((requirement) => requirement.scope === scope).length,
      ]),
    );
    for (const [scope, count] of scopeCounts) {
      if (count === 0) errors.push(`traceability baseline must contain at least one ${scope} requirement`);
    }
    if (!options.skipMarkdown) validateMarkdown(options.markdown, requirements, errors);

    if (errors.length === 0) {
      console.log(
        `Traceability verification PASS: requirements=${requirements.length} ` +
          `(mvp=${scopeCounts.get('mvp')}, phase2=${scopeCounts.get('phase2')}, out-of-scope=${scopeCounts.get('out-of-scope')}), ` +
          `controlTasks=${coveredTasks.size}/${requiredControlTasks.size}, mvpGates=10/10, artifactFields=${artifactFields.length}.`,
      );
      return;
    }
  }
  throw new Error(`Traceability verification failed:\n- ${errors.join('\n- ')}`);
}

main();
