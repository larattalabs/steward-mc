import test from 'node:test';
import assert from 'node:assert/strict';

import { mergeFlowSeconds, tierSkips, pickFlows, packFlows } from '../tools/lib/e2e-flows.mjs';

test('baseline first, then the local summary and flow-times override it per flow', () => {
  const baseline = { mode: 'stub', seconds: { a: 100, b: 200, c: 300 } };
  const summary = { flows: [{ name: 'a', seconds: 10 }] };
  const times = { b: { seconds: 20, at: 'x' }, d: { seconds: 40 } };
  assert.deepEqual(mergeFlowSeconds([baseline, summary, times, null]), { a: 10, b: 20, c: 300, d: 40 });
  // a later source without a flow leaves the earlier seconds
  assert.deepEqual(mergeFlowSeconds([baseline, {}]), { a: 100, b: 200, c: 300 });
  // non-numbers are ignored
  assert.deepEqual(mergeFlowSeconds([{ seconds: { a: 'x' } }, { b: { seconds: null } }]), {});
});

test('the committed baseline times every flow tools/e2e.mjs names, so a fresh worktree packs by time', async () => {
  const fs = await import('node:fs');
  const src = fs.readFileSync(new URL('../tools/e2e.mjs', import.meta.url), 'utf8');
  const stub = src.slice(src.indexOf("await flow('stub'"), src.indexOf("await flow('migrated-steward'"));
  const names = [...stub.matchAll(/await flow\('([^']+)'/g)].map((m) => m[1]);
  const base = JSON.parse(fs.readFileSync(new URL('../tools/flow-times-baseline.json', import.meta.url), 'utf8'));
  const missing = names.filter((n) => !Number.isFinite(base.seconds[n]) && n !== 'composed-sculpt-crash-carve');
  assert.deepEqual(missing, [], 'refresh tools/flow-times-baseline.json (mod/DEV.md "Faster runs")');
});

test('packing with times balances lanes; untimed falls back to registry order', () => {
  const groups = [['a'], ['b'], ['c'], ['d']];
  const timed = packFlows(groups, mergeFlowSeconds([{ seconds: { a: 100, b: 10, c: 10, d: 80 } }]), 2);
  assert.deepEqual(timed.map((l) => l.seconds).sort((x, y) => x - y), [100, 100]);
  const blind = packFlows(groups, {}, 2);
  assert.deepEqual(blind.map((l) => l.untimed), [2, 2]);
});

const LIST = [
  { name: 'a', tags: 'plan' },
  { name: 'b', tags: 'wall', full: 'slow' },
  { name: 'c', tags: 'water', nightlyOnly: 'flaky' },
  { name: 'd', tags: 'wall undo' },
];

test('a full-tier flow is skipped by default, kept with --full, in the nightly, or named exactly', () => {
  const why = pickFlows(LIST, null);
  assert.deepEqual(tierSkips(LIST, why).map((s) => s.name), ['b', 'c']);
  assert.deepEqual(tierSkips(LIST, why, { full: true }).map((s) => s.name), ['c']);
  assert.deepEqual(tierSkips(LIST, why, { nightly: true }), []);
  const byWord = pickFlows(LIST, ['wall']);
  assert.deepEqual(tierSkips(LIST, byWord, { only: ['wall'] }).map((s) => s.name), ['b']);
  assert.deepEqual(tierSkips(LIST, pickFlows(LIST, ['b']), { only: ['b'] }), []);
  assert.match(tierSkips(LIST, why)[0].why, /--full or --only b/);
});

import { regionStateOf, regionUntilBy } from '../tools/lib/region-wait.mjs';

test('a region missing from the state reader reads as REMOVED at once', () => {
  assert.equal(regionStateOf({ ok: true, missing: true }, ['REMOVED']), 'REMOVED');
  assert.equal(regionStateOf({ ok: true, view: { state: 'REMOVING' } }, ['REMOVED']), 'REMOVING');
  assert.equal(regionStateOf({ ok: false, error: 'x' }, ['REMOVED']), 'REMOVED');
  assert.equal(regionStateOf({ ok: false, error: 'x' }, ['PLACED']), undefined);
  assert.equal(regionStateOf({ ok: true }, ['REMOVED']), undefined);
});

test('the wait ends on the first matching answer and fails loudly past its bound', async () => {
  let t = 0;
  const clock = { now: () => t, sleep: async (ms) => { t += ms; } };
  const seq = [{ ok: true, view: { state: 'REMOVING' } }, { ok: true, missing: true }];
  let i = 0;
  const done = await regionUntilBy(async () => seq[Math.min(i++, 1)], ['REMOVED'], 600_000, clock);
  assert.equal(done.state, 'REMOVED');
  assert.equal(t, 2000);
  t = 0;
  const stuck = await regionUntilBy(async () => ({ ok: true, view: { state: 'REMOVING' } }), ['REMOVED'], 10_000, clock);
  assert.deepEqual([stuck.state, stuck.timedOut, stuck.view.state], ['REMOVING', true, 'REMOVING']);
  t = 0;
  const blank = await regionUntilBy(async () => ({ ok: true }), ['REMOVED'], 10_000, clock);
  assert.deepEqual([blank.state, blank.timedOut], ['TIMEOUT', true]);
});
