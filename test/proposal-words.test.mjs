import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import Ajv from 'ajv/dist/2020.js';

const schema = JSON.parse(fs.readFileSync(new URL('../schema/proposal-words.schema.json', import.meta.url)));
const validate = new Ajv({ allErrors: true, strict: true }).compile(schema);
const dir = new URL('../fixtures/proposal-words/', import.meta.url);

for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.json'))) {
  test(`proposal wording fixture ${f} validates`, () => {
    const { answer } = JSON.parse(fs.readFileSync(new URL(f, dir)));
    assert.ok(validate(answer), JSON.stringify(validate.errors));
  });
}

test('rejects extra fields, missing fields and long titles', () => {
  assert.equal(validate({ title: 'x', why: 'y', role: 'z', type: 'castle' }), false, 'the type is the rules\' to decide');
  assert.equal(validate({ title: 'x', why: 'y' }), false);
  assert.equal(validate({ title: 'x'.repeat(61), why: 'y', role: 'z' }), false);
});
