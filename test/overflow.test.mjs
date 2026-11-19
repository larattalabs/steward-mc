import test from 'node:test';
import assert from 'node:assert/strict';
import { overflowGrowth, overflowGrew } from '../tools/lib/overflow.mjs';

test('no growth: equal, missing or old counts kept', () => {
  assert.deepEqual(overflowGrowth({ refundOverflow: { 'minecraft:dirt': 3 } }, { refundOverflow: { 'minecraft:dirt': 3 } }), {});
  assert.deepEqual(overflowGrowth(null, null), {});
  assert.deepEqual(overflowGrowth({}, { refundOverflow: {} }), {});
  assert.equal(overflowGrew({ refundOverflow: { 'minecraft:dirt': 3 } }, { refundOverflow: { 'minecraft:dirt': 3 } }), '');
});

test('a growth is named item by item; a new item counts from 0', () => {
  const before = { refundOverflow: { 'minecraft:dirt': 3 } };
  const after = { refundOverflow: { 'minecraft:dirt': 5, 'minecraft:bucket': 1 } };
  assert.deepEqual(overflowGrowth(before, after), { 'minecraft:dirt': 2, 'minecraft:bucket': 1 });
  const words = overflowGrew(before, after);
  assert.match(words, /refundOverflow grew/);
  assert.match(words, /minecraft:dirt \+2/);
  assert.match(words, /minecraft:bucket \+1/);
});

test('a shrink is not a growth', () => {
  assert.deepEqual(overflowGrowth({ refundOverflow: { 'minecraft:dirt': 3 } }, { refundOverflow: { 'minecraft:dirt': 1 } }), {});
});
