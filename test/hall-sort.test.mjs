// batch-074 GPT review P3: the sorting flow's judge fails a mix that stays in the input chest or the line, or lands in the wrong place.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { judgeSort } from '../tools/lib/hall-sort.mjs';

// a 2-slot hall (cobblestone, coal): input, overflow, and per slot its line, filter, output hopper and chest
const cells = [
  { at: [0, 0, 0], kind: 'INPUT', slot: 0 }, { at: [9, 0, 0], kind: 'OVERFLOW', slot: 0 },
  { at: [1, 2, 0], kind: 'LINE', slot: 1 }, { at: [1, 1, 0], kind: 'FILTER', slot: 1 }, { at: [1, 0, 0], kind: 'OUTPUT', slot: 1 }, { at: [1, 0, 1], kind: 'SLOT', slot: 1 },
  { at: [2, 2, 0], kind: 'LINE', slot: 2 }, { at: [2, 1, 0], kind: 'FILTER', slot: 2 }, { at: [2, 0, 0], kind: 'OUTPUT', slot: 2 }, { at: [2, 0, 1], kind: 'SLOT', slot: 2 },
];
const items = ['minecraft:cobblestone', 'minecraft:coal'];
const fed = { 'minecraft:cobblestone': 64, 'minecraft:coal': 64, 'minecraft:dirt': 64 };
const S = 'minecraft:petrified_oak_slab';
/** A containers response: every cell, with the stacks given by cell index. */
const resp = (stacks = {}) => {
  const containers = cells.map((c, i) => ({ at: c.at, slots: (stacks[i] ?? []).map(([k, n], j) => [j, k, n]) }));
  const totals = {};
  for (const c of containers) for (const [, k, n] of c.slots) totals[k] = (totals[k] ?? 0) + n;
  return { containers, totals };
};
const filled = { 3: [['minecraft:cobblestone', 41], [S, 4]], 7: [['minecraft:coal', 41], [S, 4]] };
const before = resp(filled);

test('a sorted mix passes: slot chest plus its output hopper, the rest in the overflow, the filters as filled', () => {
  const after = resp({ ...filled, 5: [['minecraft:cobblestone', 63]], 4: [['minecraft:cobblestone', 1]], 9: [['minecraft:coal', 64]], 1: [['minecraft:dirt', 64]] });
  const r = judgeSort({ cells, before, after, fed, items });
  assert.deepEqual(r.why, []);
  assert.ok(r.ok && r.drained);
});

test('nothing sorted (all 192 still in the input chest) fails, though the hall total is conserved', () => {
  const after = resp({ ...filled, 0: [['minecraft:cobblestone', 64], ['minecraft:coal', 64], ['minecraft:dirt', 64]] });
  const r = judgeSort({ cells, before, after, fed, items });
  assert.equal(r.ok, false);
  assert.equal(r.drained, false);
  assert.match(r.why.join('; '), /the input chest still holds/);
  assert.match(r.why.join('; '), /slot 1 \(minecraft:cobblestone\) got \{\}/);
});

test('a backlog in the line, an item in the wrong slot, a moved filter: each fails', () => {
  const line = judgeSort({ cells, before, fed, items, after: resp({ ...filled, 2: [['minecraft:cobblestone', 5]], 5: [['minecraft:cobblestone', 59]], 9: [['minecraft:coal', 64]], 1: [['minecraft:dirt', 64]] }) });
  assert.equal(line.drained, false);
  assert.match(line.why.join('; '), /the line still holds/);
  const wrong = judgeSort({ cells, before, fed, items, after: resp({ ...filled, 5: [['minecraft:cobblestone', 64], ['minecraft:dirt', 1]], 9: [['minecraft:coal', 64]], 1: [['minecraft:dirt', 63]] }) });
  assert.equal(wrong.ok, false);
  assert.match(wrong.why.join('; '), /slot 1 .* got .*dirt/);
  const moved = judgeSort({ cells, before, fed, items, after: resp({ 3: [['minecraft:cobblestone', 40], [S, 4]], 7: filled[7], 5: [['minecraft:cobblestone', 65]], 9: [['minecraft:coal', 64]], 1: [['minecraft:dirt', 64]] }) });
  assert.match(moved.why.join('; '), /filter 1 moved/);
  // no declared cells (an older client): a failure, said
  assert.equal(judgeSort({ cells: null, before, after: before, fed, items }).ok, false);
});
