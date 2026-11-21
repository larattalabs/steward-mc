import test from 'node:test';
import assert from 'node:assert/strict';
import { drawsBetween, quoteOff } from '../tools/lib/quote-draws.mjs';

const bom = { 'minecraft:clay': 2393, 'minecraft:mud': 1190, 'minecraft:moss_block': 658, 'minecraft:stone_bricks': 8 };

test('the draws equal to the quote: no mismatch', () => {
  assert.deepEqual(quoteOff(bom, { ...bom }), []);
  assert.deepEqual(quoteOff({ ...bom, 'minecraft:dirt': 0 }, bom), [], 'a 0 in the quote is no quote');
});

test('an extra draw of an item the quote never named fails (the review case: dirt, stone)', () => {
  assert.deepEqual(quoteOff(bom, { ...bom, 'minecraft:dirt': 1 }), ['minecraft:dirt drew 1/quoted 0']);
  assert.deepEqual(quoteOff(bom, { ...bom, 'minecraft:stone': 64 }), ['minecraft:stone drew 64/quoted 0']);
});

test('an over-draw, a short draw and a quoted item never drawn each fail', () => {
  assert.deepEqual(quoteOff(bom, { ...bom, 'minecraft:clay': 2394 }), ['minecraft:clay drew 2394/quoted 2393'], 'a duplicate debit of one unit');
  assert.deepEqual(quoteOff(bom, { ...bom, 'minecraft:mud': 1189 }), ['minecraft:mud drew 1189/quoted 1190']);
  const { 'minecraft:moss_block': _, ...noMoss } = bom;
  assert.deepEqual(quoteOff(bom, noMoss), ['minecraft:moss_block drew 0/quoted 658']);
});

test('the draws between two ledgers: consumed after minus before, new items from 0', () => {
  const before = { consumed: { 'minecraft:clay': 10, 'minecraft:dirt': 5 } };
  const after = { consumed: { 'minecraft:clay': 2403, 'minecraft:dirt': 5, 'minecraft:mud': 1190 } };
  assert.deepEqual(drawsBetween(before, after), { 'minecraft:clay': 2393, 'minecraft:mud': 1190 });
  assert.deepEqual(drawsBetween(null, { consumed: { 'minecraft:mud': 2 } }), { 'minecraft:mud': 2 });
  // an extra draw recorded consistently in the ledger still shows against the quote
  assert.deepEqual(quoteOff({ 'minecraft:clay': 2393, 'minecraft:mud': 1190 }, drawsBetween(before, { consumed: { ...after.consumed, 'minecraft:dirt': 6 } })),
    ['minecraft:dirt drew 1/quoted 0']);
});
