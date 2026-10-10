import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import Ajv from 'ajv/dist/2020.js';

const schema = JSON.parse(fs.readFileSync(new URL('../schema/concept-card.schema.json', import.meta.url)));
const ajv = new Ajv({ allErrors: true, strict: true });
const validate = ajv.compile(schema);
const dir = new URL('../fixtures/', import.meta.url);

for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.json'))) {
  test(`fixture ${f} validates`, () => {
    const { prompt, card } = JSON.parse(fs.readFileSync(new URL(f, dir)));
    assert.ok(typeof prompt === 'string' && prompt.length > 0);
    assert.ok(validate(card), JSON.stringify(validate.errors));
  });
}

test('rejects unknown fields and bad enums', () => {
  const { card } = JSON.parse(fs.readFileSync(new URL('crater_works.json', dir)));
  assert.equal(validate({ ...card, extra: 1 }), false);
  assert.equal(validate({ ...card, site: { ...card.site, terrain: 'bulldoze' } }), false);
  assert.equal(validate({ ...card, constraints: { near: 'moon', density: 'med' } }), false);
});

test('the prompt names every template the fixtures use', () => {
  const prompt = fs.readFileSync(new URL('../prompts/concept-card.md', import.meta.url), 'utf8');
  for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.json'))) {
    const { card } = JSON.parse(fs.readFileSync(new URL(f, dir)));
    for (const k of ['site', 'style', 'purpose']) if (card[k].template) assert.ok(prompt.includes('`' + card[k].template + '`'), `${card[k].template} missing from prompt`);
  }
});

test('real Sonnet outputs validate against the schema', () => {
  const real = new URL('../fixtures/real/', import.meta.url);
  const files = fs.readdirSync(real).filter((n) => n.endsWith('.json'));
  assert.ok(files.length >= 2);
  for (const f of files) {
    const { card } = JSON.parse(fs.readFileSync(new URL(f, real)));
    assert.ok(validate(card), f + ' ' + JSON.stringify(validate.errors));
  }
});

test('the prompt offers exactly the Architect presets LotBrief knows', () => {
  const java = fs.readFileSync(new URL('../mod/src/main/java/dev/larattalabs/steward/gateway/LotBrief.java', import.meta.url), 'utf8');
  const set = java.match(/ARCHITECT_TYPES = Set\.of\(([^)]*)\)/)[1].match(/"([a-z_]+)"/g).map((s) => s.slice(1, -1)).filter((t) => t !== 'custom');
  const prompt = fs.readFileSync(new URL('../prompts/concept-card.md', import.meta.url), 'utf8');
  const programLine = prompt.slice(prompt.indexOf('- **program**'), prompt.indexOf('- **story**'));
  const placements = schema.properties.program.items.properties.placement.enum;
  const fields = ['role', 'type', 'count', 'footprint', 'landmark', 'notes', 'placement'];
  const offered = [...programLine.matchAll(/`([a-z_]+)`/g)].map((m) => m[1]).filter((t) => set.includes(t) || !t.includes('_') && !fields.includes(t) && !placements.includes(t));
  assert.deepEqual([...new Set(offered)].sort(), [...set].sort());
});

test('every fixture program type is a valid Architect type slug', () => {
  for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.json'))) {
    const { card } = JSON.parse(fs.readFileSync(new URL(f, dir)));
    for (const b of card.program ?? []) assert.match(b.type, /^[a-z][a-z0-9_]{0,39}$/, f);
  }
});
