// Checks a plan writer's answer against its own schema and Architect's plan/0 (schema/plan-0.json: the pinned jar's architect_mc/schemas/plan.json byte for byte, held so by PlanWriterJobTest). Pure.
import fs from 'node:fs';
import Ajv from 'ajv/dist/2020.js';

const planSchema = JSON.parse(fs.readFileSync(new URL('../../schema/plan-0.json', import.meta.url)));
const planAjv = new Ajv({ allErrors: true, strict: false });
const validatePlan = planAjv.compile(planSchema);
// A zone's items are only partBase in the schema; its program is reached through the kit's x-dispatch, which Ajv does not follow, so a program went
// unchecked (mushroom_town's shared "green" passed, 2026-11-10). The program is dispatched here as $defs/program's x-dispatch says: on template, lots or
// districts. Only the program: the rest of zone.standard in this copy lags Architect's kit schema (terraces, wall walk/lots), which the kit accepts.
const programChecks = Object.fromEntries(['template', 'lots', 'districts'].map((on) => [on, planAjv.getSchema(`${planSchema.$id}#/$defs/program.${on}`)]));
function programErrors(program, at) {
  if (!program || typeof program !== 'object') return [];
  const on = ['template', 'lots', 'districts'].find((k) => k in program);
  if (!on) return [`${at} ${planSchema.$defs.program.oneOfMessage}`];
  const check = programChecks[on];
  return check(program) ? [] : check.errors.slice(0, 6).map((e) => `${at}${e.instancePath} ${e.message}`
    + (e.params?.allowedValues ? ` (${e.params.allowedValues.join(', ')})` : '') + (e.params?.additionalProperty ? ` (${e.params.additionalProperty})` : ''));
}
const validateAnswer = new Ajv({ allErrors: true, strict: true }).compile(JSON.parse(fs.readFileSync(new URL('../../schema/plan-writer.schema.json', import.meta.url))));

/** The answer's problems, as lines ("plan/zones/0/program must ..."); empty when it is a valid plan/0 answer. */
export function checkAnswer(answer) {
  const errors = [];
  if (!validateAnswer(answer)) errors.push(...validateAnswer.errors.map((e) => `answer${e.instancePath} ${e.message}`));
  if (answer?.plan && !validatePlan(answer.plan)) errors.push(...validatePlan.errors.slice(0, 12).map((e) => `plan${e.instancePath} ${e.message}`));
  (Array.isArray(answer?.plan?.zones) ? answer.plan.zones : []).forEach((z, i) => errors.push(...programErrors(z?.program, `plan/zones/${i}/program`)));
  return errors;
}

/** The plan's parts as {family, kind, id, required} (zones, connectors, forms, rules, and any other array of parts). */
export function partsOf(plan) {
  const out = [];
  for (const family of ['zones', 'connectors', 'forms', 'rules']) {
    for (const p of plan?.[family] ?? []) out.push({ family, kind: p.kind ?? (family === 'zones' ? 'standard' : '?'), id: p.id, required: p.required });
  }
  return out;
}

/** The catalogue's states by block id (x-catalogue), e.g. {"connector.wall": "reserved"}. */
export const catalogue = Object.fromEntries((planSchema['x-catalogue']?.entries ?? planSchema['x-catalogue'] ?? []).map((e) => [e.id, e.state]));
