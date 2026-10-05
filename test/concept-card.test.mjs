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
  for (const f of fs.readdirSync(dir)) {
    const { card } = JSON.parse(fs.readFileSync(new URL(f, dir)));
    for (const k of ['site', 'style', 'purpose']) if (card[k].template) assert.ok(prompt.includes('`' + card[k].template + '`'), `${card[k].template} missing from prompt`);
  }
});
