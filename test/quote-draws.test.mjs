import test from 'node:test';
import assert from 'node:assert/strict';
import { drawsBetween, quoteOff, partsTotal, mayStand, judgeQuote } from '../tools/lib/quote-draws.mjs';

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

// (B092-FIX) Architect's own inexact quote of the composed sculpt town (its notes as logged in the batch e2e)
const NOTES = ['tiles: 661 paid cell(s) of natural terrain may already stand in the land (river (658 moss_block); town (1 dandelion, 2 poppy)): a realise skips a cell that already holds its block, so the region\'s cells are an upper bound: consumed is at most this quote, never more',
  'lot shop_4 (gen_mossy_and_crooked_shop): the plan\'s tiles change 7 cell(s) where its entrance approach is planned'];
const PARTS = { 'tiles:river': { 'minecraft:clay': 2359, 'minecraft:moss_block': 658, 'minecraft:mud': 1224 },
  'tiles:town': { 'minecraft:dirt': 3, 'minecraft:poppy': 2, 'minecraft:dandelion': 1 }, 'lot:shop_4': { 'minecraft:dirt': 4, 'minecraft:oak_planks': 90 } };
const LINING = ['minecraft:clay', 'minecraft:mud', 'minecraft:moss_block'];

test('the pieces summed, and the cells that may already stand read from the notes', () => {
  assert.deepEqual(partsTotal(PARTS), { 'minecraft:clay': 2359, 'minecraft:moss_block': 658, 'minecraft:mud': 1224, 'minecraft:dirt': 7, 'minecraft:poppy': 2, 'minecraft:dandelion': 1, 'minecraft:oak_planks': 90 });
  assert.deepEqual(mayStand(NOTES), { 'minecraft:moss_block': 658, 'minecraft:dandelion': 1, 'minecraft:poppy': 2 });
  assert.deepEqual(mayStand(['nothing of the kind']), {});
  assert.deepEqual(mayStand(null), {});
});

test('an inexact quote: drawn as quoted, or natural cells skipped, passes; short lots are reported only', () => {
  const quote = { bom: partsTotal(PARTS), exact: false, notes: NOTES };
  assert.deepEqual(judgeQuote(quote, partsTotal(PARTS), LINING), { fails: [], short: [] });
  const r = judgeQuote(quote, { ...partsTotal(PARTS), 'minecraft:moss_block': 600, 'minecraft:poppy': 0, 'minecraft:oak_planks': 88 }, LINING);
  assert.deepEqual(r.fails, []);
  assert.deepEqual(r.short, ['minecraft:moss_block 600/658', 'minecraft:oak_planks 88/90', 'minecraft:poppy 0/2']);
});

test('an inexact quote still fails an extra item, a draw past the quote and a short lining', () => {
  const quote = { bom: partsTotal(PARTS), exact: false, notes: NOTES };
  const all = partsTotal(PARTS);
  assert.deepEqual(judgeQuote(quote, { ...all, 'minecraft:stone': 1 }, LINING).fails, ['minecraft:stone drew 1, never quoted']);
  assert.deepEqual(judgeQuote(quote, { ...all, 'minecraft:dirt': 8 }, LINING).fails, ['minecraft:dirt drew 8/quoted 7']);
  assert.deepEqual(judgeQuote(quote, { ...all, 'minecraft:clay': 2359 + 29 }, LINING).fails, ['minecraft:clay drew 2388/quoted 2359'], 'a doubled part of the lining');
  assert.deepEqual(judgeQuote(quote, { ...all, 'minecraft:mud': 1200 }, LINING).fails, ['minecraft:mud drew 1200/quoted 1224 (at most 0 may already stand)']);
});

test('an exact quote: every item exactly as quoted', () => {
  const quote = { bom: { 'minecraft:clay': 10 }, exact: true, notes: [] };
  assert.deepEqual(judgeQuote(quote, { 'minecraft:clay': 10 }), { fails: [], short: [] });
  assert.deepEqual(judgeQuote(quote, { 'minecraft:clay': 9 }).fails, ['minecraft:clay drew 9/quoted 10']);
  assert.deepEqual(judgeQuote(quote, { 'minecraft:clay': 10, 'minecraft:dirt': 1 }).fails, ['minecraft:dirt drew 1/quoted 0']);
});
