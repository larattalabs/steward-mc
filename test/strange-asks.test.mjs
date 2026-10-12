import test from 'node:test';
import assert from 'node:assert/strict';
import { load, statusOf, gaps } from '../tools/lib/strange-asks.mjs';

const { caps, asks } = load();

test('every capability says where it stands', () => {
  for (const [id, c] of Object.entries(caps)) {
    assert.ok(['now', 'nearest', 'waits'].includes(c.status), `${id}: status ${c.status}`);
    assert.ok(c.name, `${id}: no name`);
    if (c.status === 'nearest') assert.ok(c.nearest, `${id}: a nearest version says what is built`);
    if (c.status !== 'now') assert.ok(c.comes, `${id}: says what brings it`);
  }
});

test('every ask names known capabilities, once, and has an id of its own', () => {
  const ids = new Set();
  for (const a of asks) {
    assert.ok(!ids.has(a.id), `duplicate ask ${a.id}`);
    ids.add(a.id);
    assert.ok(a.ask && a.category && a.needs.length > 0, `${a.id}: incomplete`);
    assert.equal(new Set(a.needs).size, a.needs.length, `${a.id}: a need named twice`);
    for (const n of a.needs) assert.ok(caps[n], `${a.id}: unknown capability ${n}`);
  }
});

test('every capability is needed by some ask', () => {
  for (const id of Object.keys(caps)) assert.ok(asks.some((a) => a.needs.includes(id)), `${id} is in no ask`);
});

test('an ask is as far as its furthest need', () => {
  const caps2 = { a: { status: 'now' }, b: { status: 'nearest' }, c: { status: 'waits' } };
  assert.equal(statusOf({ needs: ['a'] }, caps2), 'now');
  assert.equal(statusOf({ needs: ['a', 'b'] }, caps2), 'nearest');
  assert.equal(statusOf({ needs: ['b', 'c', 'a'] }, caps2), 'waits');
  const g = gaps(caps2, [{ id: 'x', needs: ['a', 'c'] }, { id: 'y', needs: ['b', 'c'] }]);
  assert.deepEqual(g.map((e) => [e.id, e.needed, e.alone]), [['c', 2, 1], ['b', 1, 0]]);
});

test('every recorded card of a strange ask validates against the card schema', async () => {
  const fs = await import('node:fs');
  const Ajv = (await import('ajv/dist/2020.js')).default;
  const schema = JSON.parse(fs.readFileSync(new URL('../schema/concept-card.schema.json', import.meta.url)));
  const validate = new Ajv({ allErrors: true, strict: true }).compile(schema);
  const dir = new URL('../fixtures/strange-asks/cards/', import.meta.url);
  for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.json'))) {
    const { prompt, card } = JSON.parse(fs.readFileSync(new URL(f, dir)));
    assert.ok(asks.some((a) => a.ask === prompt), `${f}: its prompt is not an ask`);
    assert.ok(validate(card), `${f}: ${JSON.stringify(validate.errors)}`);
  }
});
