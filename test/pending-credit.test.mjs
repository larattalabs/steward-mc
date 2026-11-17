// (DR2-DOUBLE) module-measure-hall's "nothing given twice" exception on Architect 0.78.0: only an empty pendingCredit record may go at a
// restart. Architect's fix/double-credit (v0.79.0) stops the leak: drop tools/lib/pending-credit.mjs and this test at that pin.
import test from 'node:test';
import assert from 'node:assert/strict';
import { emptyPendingGone } from '../tools/lib/pending-credit.mjs';

const base = { delivered: { 'minecraft:gravel': 64 }, returned: { 'minecraft:gravel': 6 }, credited: { 'site:s9@1': true } };

test('an empty pendingCredit record that the restart dropped is the one change allowed', () => {
  const before = { ...base, pendingCredit: { 'site:s10@2': { items: {}, at: 7 } } };
  const r = emptyPendingGone(before, { ...base });
  assert.deepEqual(r.dropped, ['site:s10@2']);
  assert.deepEqual(r.ledger, base);
  assert.equal(JSON.stringify(r.ledger), JSON.stringify(base), 'the rest compares exactly as before');
  assert.ok(before.pendingCredit['site:s10@2'], 'the ledger read is not changed');
});

test('a pendingCredit record with items, or one still there after, is kept and so still compared', () => {
  const withItems = { ...base, pendingCredit: { 'site:s10@2': { items: { 'minecraft:gravel': 6 } } } };
  assert.deepEqual(emptyPendingGone(withItems, { ...base }), { ledger: withItems, dropped: [] });
  const still = { ...base, pendingCredit: { 'site:s10@2': { items: {} } } };
  assert.deepEqual(emptyPendingGone(still, still).dropped, []);
  const mixed = { ...base, pendingCredit: { a: { items: {} }, b: { items: { 'minecraft:dirt': 1 } } } };
  const r = emptyPendingGone(mixed, { ...base });
  assert.deepEqual(r.dropped, ['a']);
  assert.deepEqual(r.ledger.pendingCredit, { b: { items: { 'minecraft:dirt': 1 } } }, 'b still differs from after, so the check fails');
});

test('no pendingCredit: nothing changes', () => {
  assert.deepEqual(emptyPendingGone(base, base), { ledger: base, dropped: [] });
  assert.deepEqual(emptyPendingGone(undefined, base), { ledger: undefined, dropped: [] });
});
