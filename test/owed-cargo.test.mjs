// (T4RD-S, S-1) module-measure's bar 7b judges: on T4R-D the placer's blocks are owed to it, never on the ground.
import test from 'node:test';
import assert from 'node:assert/strict';
import { placerOwed, judgeCargo, owedTotal, judgeRestart, grew, PLAYER_BLOCKS } from '../tools/lib/owed-cargo.mjs';

const PLACER = 'uuid-farmer';
const want = { 'minecraft:wheat_seeds': 4, 'minecraft:cobblestone': 2, 'minecraft:torch': 2 };
const parts = [
  { key: 'pb:1', recipient: PLACER, reason: PLAYER_BLOCKS, items: { 'minecraft:wheat_seeds': 3, 'minecraft:cobblestone': 1, 'minecraft:torch': 1 } },
  { key: 'pb:2', recipient: PLACER, reason: PLAYER_BLOCKS, items: { 'minecraft:wheat_seeds': 1, 'minecraft:cobblestone': 1, 'minecraft:torch': 1 } },
  // never the placer's: the town's refund owed, another player's blocks
  { key: 'gr:1', recipient: 'steward_mc:settlement/m2', reason: 'group_refund', items: { 'minecraft:oak_planks': 9 } },
  { key: 'pb:3', recipient: 'uuid-other', reason: PLAYER_BLOCKS, items: { 'minecraft:torch': 5 } },
];

test('the placer is owed only its own player_blocks parts', () => {
  assert.deepEqual(placerOwed({ owedParts: parts }, PLACER), want);
  assert.deepEqual(placerOwed({}, PLACER), {});
});

test('T4R-D: owed to the placer, nothing on the ground', () => {
  assert.equal(judgeCargo({ t4: true, undo: { owedParts: parts }, placer: PLACER, ground: { f1: {}, f2: {} }, want }).ok, true);
  // a block on the ground fails, even when the owed sum is right
  const loose = judgeCargo({ t4: true, undo: { owedParts: parts }, placer: PLACER, ground: { f1: { 'minecraft:torch': 1 } }, want });
  assert.equal(loose.ok, false);
  assert.match(loose.why, /on the ground at f1/);
  // owed short, or an answer with no owedParts at all (zero results), or no placer id: FAIL
  assert.equal(judgeCargo({ t4: true, undo: { owedParts: parts.slice(1) }, placer: PLACER, ground: {}, want }).ok, false);
  assert.equal(judgeCargo({ t4: true, undo: { removed: true }, placer: PLACER, ground: {}, want }).ok, false);
  // no owedParts at all is a FAIL even when nothing was expected (an Architect that doesn't report them proves nothing)
  assert.equal(judgeCargo({ t4: true, undo: { removed: true }, placer: PLACER, ground: {}, want: {} }).ok, false);
  assert.equal(judgeCargo({ t4: true, undo: { owedParts: [] }, placer: PLACER, ground: {}, want }).ok, false);
  assert.equal(judgeCargo({ t4: true, undo: { owedParts: parts }, placer: '', ground: {}, want }).ok, false);
});

test('without owedParts in the answer: the placer\'s owed store across the undo (S-1\'s alternative)', () => {
  const before = { 'minecraft:torch': 1 };
  const after = { 'minecraft:torch': 3, 'minecraft:cobblestone': 2, 'minecraft:wheat_seeds': 4 };
  assert.deepEqual(grew(before, after), want);
  assert.equal(judgeCargo({ t4: true, undo: { removed: true }, placer: PLACER, ground: {}, want, owedDelta: grew(before, after) }).ok, true);
  assert.equal(judgeCargo({ t4: true, undo: { removed: true }, placer: PLACER, ground: {}, want, owedDelta: grew(before, before) }).ok, false, 'nothing owed');
  assert.equal(judgeCargo({ t4: true, undo: { removed: true }, placer: PLACER, ground: { f1: { 'minecraft:torch': 1 } }, want, owedDelta: want }).ok, false);
  // owedParts, when there, win over the store
  assert.equal(judgeCargo({ t4: true, undo: { owedParts: [] }, placer: PLACER, ground: {}, want, owedDelta: want }).ok, false);
});

test('before T4R-D: each farm drops its own blocks (the bar as it was)', () => {
  const wantPerFarm = { f1: { 'minecraft:torch': 1 }, f2: { 'minecraft:torch': 1 } };
  assert.equal(judgeCargo({ t4: false, ground: { f1: { 'minecraft:torch': 1 }, f2: { 'minecraft:torch': 1 } }, wantPerFarm }).ok, true);
  assert.equal(judgeCargo({ t4: false, ground: { f1: { 'minecraft:torch': 1 }, f2: {} }, wantPerFarm }).ok, false);
});

test('after a restart: nothing given twice', () => {
  const before = owedTotal({ owed: [{ items: { 'minecraft:torch': 2 } }, { items: { 'minecraft:cobblestone': 2 } }] });
  assert.deepEqual(before, { 'minecraft:torch': 2, 'minecraft:cobblestone': 2 });
  assert.equal(judgeRestart({ t4: true, before, after: { ...before }, stray: {} }).ok, true);
  assert.equal(judgeRestart({ t4: true, before, after: { 'minecraft:torch': 4, 'minecraft:cobblestone': 2 }, stray: {} }).ok, false, 'owed twice');
  assert.equal(judgeRestart({ t4: true, before, after: before, stray: { 'minecraft:torch': 1 } }).ok, false, 'dropped too');
  assert.equal(judgeRestart({ t4: true, before: {}, after: {}, stray: {} }).ok, false, 'nothing owed: no proof');
  assert.equal(judgeRestart({ t4: false, before: {}, after: {}, stray: {} }).ok, true);
});
