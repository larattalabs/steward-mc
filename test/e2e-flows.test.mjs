import test from 'node:test';
import assert from 'node:assert/strict';

import { pickFlows } from '../tools/lib/e2e-flows.mjs';

// the free run's flows as tools/e2e.mjs names them (districts builds on the settlement proposals describes)
const FREE = [
  { name: 'stone-recipe', tags: 'screens' },
  { name: 'place', tags: 'update revert undo screens' },
  { name: 'proposals', tags: 'farm' },
  { name: 'districts', tags: 'protect road layout', needs: ['proposals'] },
  { name: 'player-builds', tags: 'protect' },
  { name: 'migrated-steward', tags: 'save migration' },
];

test('no --only runs every flow, none pulled in', () => {
  const why = pickFlows(FREE, null);
  assert.deepEqual([...why.keys()], FREE.map((f) => f.name));
  assert.ok([...why.values()].every((v) => v === ''));
});

test('--only districts pulls in proposals, which it needs', () => {
  const why = pickFlows(FREE, ['districts']);
  assert.deepEqual(Object.fromEntries(why), { districts: '', proposals: 'districts' });
});

test('a tag word that picks districts pulls in proposals too', () => {
  assert.deepEqual(Object.fromEntries(pickFlows(FREE, ['road'])), { districts: '', proposals: 'districts' });
  // protect picks districts and player-builds; proposals comes with districts
  assert.deepEqual(new Set(pickFlows(FREE, ['protect']).keys()), new Set(['districts', 'player-builds', 'proposals']));
});

test('a needed flow picked on its own says so, and pulls nothing back', () => {
  assert.deepEqual(Object.fromEntries(pickFlows(FREE, ['farm'])), { proposals: '' });
});

test('needs are followed through a chain', () => {
  const list = [{ name: 'a', tags: '' }, { name: 'b', tags: '', needs: ['a'] }, { name: 'c', tags: '', needs: ['b'] }];
  assert.deepEqual(Object.fromEntries(pickFlows(list, ['c'])), { c: '', b: 'c', a: 'b' });
});

test('a word matching nothing runs no flows (the sanity set runs outside them)', () => {
  assert.equal(pickFlows(FREE, ['sanity']).size, 0);
});
