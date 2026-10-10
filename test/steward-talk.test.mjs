import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import Ajv from 'ajv/dist/2020.js';

const schema = JSON.parse(fs.readFileSync(new URL('../schema/steward-talk.schema.json', import.meta.url)));
const card = JSON.parse(fs.readFileSync(new URL('../schema/concept-card.schema.json', import.meta.url)));
const validate = new Ajv({ allErrors: true, strict: true }).compile(schema);
const dir = new URL('../fixtures/talk/', import.meta.url);

for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.json'))) {
  test(`talk fixture ${f} validates`, () => {
    const { said, answer } = JSON.parse(fs.readFileSync(new URL(f, dir)));
    assert.ok(typeof said === 'string' && said.length > 0);
    assert.ok(validate(answer), JSON.stringify(validate.errors));
  });
}

test('rejects unknown intents, fields and an empty reply', () => {
  assert.equal(validate({ reply: 'x', intent: 'demolish' }), false);
  assert.equal(validate({ reply: 'x', intent: 'chat', extra: 1 }), false);
  assert.equal(validate({ reply: '', intent: 'chat' }), false);
  assert.equal(validate({ reply: 'x', intent: 'district', way: 'NE' }), false);
});

test("a talk building is a concept card program entry, one of it", () => {
  const b = schema.properties.building.properties;
  const p = card.properties.program.items.properties;
  for (const k of Object.keys(b)) assert.deepEqual(b[k], p[k], k);
  assert.ok(!('count' in b));
});

test('the prompt names every intent and the presets', () => {
  const prompt = fs.readFileSync(new URL('../prompts/steward-talk.md', import.meta.url), 'utf8');
  for (const i of schema.properties.intent.enum) assert.ok(prompt.includes('**' + i + '**'), i);
  for (const t of ['house', 'cabin', 'cottage', 'tower', 'shop', 'tavern', 'barn', 'smithy', 'chapel', 'gatehouse']) assert.ok(prompt.includes('`' + t + '`'), t);
});
