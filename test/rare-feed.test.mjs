import test from 'node:test';
import assert from 'node:assert/strict';

import { rareFeedDue } from '../tools/lib/rare-feed.mjs';

/** Polls every 3 s from 0 to `untilS`; `consumedAt(s)` is the stockpile's consumed units at second s. Returns [second, why] of the first feed, or null. */
function run(consumedAt, untilS, { named = () => false, onlyRare = () => true } = {}) {
  const st = {};
  for (let s = 0; s <= untilS; s += 3) {
    const why = rareFeedDue(st, { now: s * 1000, onlyRare: onlyRare(s), consumed: consumedAt(s), named: named(s) });
    if (why) return [s, why];
  }
  return null;
}

test('batch 096: only the rare items owed while the host still builds for 260 s: held until the build goes quiet', () => {
  // the stockpile owed only the gate's rare items from 12:00:45; the gatehouse (the last lot) was built 260 s later, consuming all the while
  const building = (s) => Math.min(s, 260) * 20;
  const r = run(building, 900);
  assert.ok(r, 'it feeds in the end');
  assert.equal(r[1], 'quiet');
  // never before the host stands (the old 3 min fallback fed at 181 s, before the mechanism ever waited)
  assert.ok(r[0] > 260 + 180, `fed at ${r[0]} s`);
});

test('named: fed at once, whatever the clock', () => {
  assert.deepEqual(run((s) => s, 600, { named: (s) => s >= 30 }), [30, 'named']);
});

test('nothing consumed with only the rare items owed: fed after 3 min (the mechanism waits and nothing named it)', () => {
  assert.deepEqual(run(() => 500, 600), [183, 'quiet']);
});

test('a build that keeps consuming is still bounded: fed at the 15 min cap (C14)', () => {
  assert.deepEqual(run((s) => s, 2000), [903, 'cap']);
});

test('the clock starts only once nothing but the rare items is owed', () => {
  // other items owed for the first 120 s, nothing consumed throughout
  const r = run(() => 0, 900, { onlyRare: (s) => s >= 120 });
  assert.deepEqual(r, [303, 'quiet']);
});
