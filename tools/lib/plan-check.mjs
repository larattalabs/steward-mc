// Checks a plan writer's answer against its own schema and Architect's plan/0 (schema/plan-0.json, from architect-mc main d776b6c). Pure.
import fs from 'node:fs';
import Ajv from 'ajv/dist/2020.js';

const planSchema = JSON.parse(fs.readFileSync(new URL('../../schema/plan-0.json', import.meta.url)));
const validatePlan = new Ajv({ allErrors: true, strict: false }).compile(planSchema);
const validateAnswer = new Ajv({ allErrors: true, strict: true }).compile(JSON.parse(fs.readFileSync(new URL('../../schema/plan-writer.schema.json', import.meta.url))));

/** The answer's problems, as lines ("plan/zones/0/program must ..."); empty when it is a valid plan/0 answer. */
export function checkAnswer(answer) {
  const errors = [];
  if (!validateAnswer(answer)) errors.push(...validateAnswer.errors.map((e) => `answer${e.instancePath} ${e.message}`));
  if (answer?.plan && !validatePlan(answer.plan)) errors.push(...validatePlan.errors.slice(0, 12).map((e) => `plan${e.instancePath} ${e.message}`));
  return errors;
}

/** The plan's parts as {family, kind, id, required} (zones, connectors, forms, rules, and any other array of parts). */
export function partsOf(plan) {
  const out = [];
  for (const family of ['zones', 'connectors', 'forms', 'rules', 'dependencies']) {
    for (const p of plan?.[family] ?? []) out.push({ family, kind: p.kind ?? (family === 'zones' ? 'standard' : '?'), id: p.id, required: p.required });
  }
  return out;
}

/** The catalogue's states by block id (x-catalogue), e.g. {"connector.wall": "reserved"}. */
export const catalogue = Object.fromEntries((planSchema['x-catalogue']?.entries ?? planSchema['x-catalogue'] ?? []).map((e) => [e.id, e.state]));
