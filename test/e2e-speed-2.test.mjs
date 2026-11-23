// (E2E-SPEED-2, Noah 11-22: "1+2+3: all speed items", then items 5 and 6) The two-client nightly, three-client batch runs, the fast set, the
// RAM disk's start floor and the settled cleanup, and composed-sculpt-survival-crash nightly-only until Architect's PLR.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { FAST_FLOWS, fastNames, fastPick, packFlows, groupFlows, projectWall, mergeFlowSeconds, tierSkips, LANE_OVERHEAD_S, blocksWithLibs, touchedByFiles } from '../tools/lib/e2e-flows.mjs';
import { NIGHTLY_PORTS, NIGHTLY_SIDECAR_PORTS, NIGHTLY_CLIENTS, stepArgs, nightFits, STOP_GRACE_MS, LIMIT_MIN } from '../tools/lib/nightly-plan.mjs';
import { waitTmpFloor, tmpFloor } from '../tools/lib/disk.mjs';
import { own, cleanupOwnedSettled } from '../tools/lib/tmp-owner.mjs';

const SRC = fs.readFileSync(new URL('../tools/e2e.mjs', import.meta.url), 'utf8');
const BASE = JSON.parse(fs.readFileSync(new URL('../tools/flow-times-baseline.json', import.meta.url), 'utf8'));
/** The stub flows as e2e.mjs names them: name, tags, needs and the tier options. */
const STUB = (() => {
  const body = SRC.slice(SRC.indexOf("await flow('stub'"), SRC.indexOf("await flow('migrated-steward'"));
  const out = [];
  for (const m of body.matchAll(/await flow\('([^']+)', '([^']*)', \(\) => [^\n]*?(?:, \[([^\]]*)\])?(?:,\s*\{ nightlyOnly: '[^']*' \})?\);/g)) out.push({ name: m[1], tags: m[2] });
  for (const f of out) {
    const at = body.indexOf(`await flow('${f.name}'`);
    const stmt = body.slice(at, body.indexOf(');\n', at) + 2);
    f.needs = [...(/\]\s*,\s*\{|, \[([^\]]+)\]\)/.exec(stmt)?.[1] ?? '').matchAll(/'([^']+)'/g)].map((x) => x[1]);
    if (/nightlyOnly:/.test(stmt)) f.nightlyOnly = 'x';
    if (/\bfull:/.test(stmt)) f.full = 'x';
  }
  return out;
})();

// ------------------------------------------------------------------ item 6

test('item 6, restored at the 0.91.0 pin (PLR): both sculpt crash flows run in batch and slice runs again', () => {
  const night = STUB.filter((f) => f.nightlyOnly).map((f) => f.name);
  assert.ok(!night.includes('composed-sculpt-survival-crash'), 'composed-sculpt-survival-crash: back in batch runs');
  assert.ok(!night.includes('composed-sculpt-crash-carve'));
  assert.ok(STUB.some((f) => f.name === 'composed-sculpt-survival-crash') && STUB.some((f) => f.name === 'composed-sculpt-crash-carve'));
  const why = new Map(STUB.map((f) => [f.name, '']));
  assert.ok(!tierSkips(STUB, why).some((s) => s.name === 'composed-sculpt-survival-crash' || s.name === 'composed-sculpt-crash-carve'));
});

// ------------------------------------------------------------------ item 3: the fast set

test('item 3: every fast flow is a stub flow, none nightly-only, full tier or a sculpt crash flow; every risk area covered', () => {
  assert.ok(STUB.length > 60, `parsed ${STUB.length} stub flows`);
  for (const n of fastNames()) {
    const f = STUB.find((x) => x.name === n);
    assert.ok(f, `${n} is not a stub flow`);
    assert.ok(!f.nightlyOnly && !f.full, `${n} is nightly-only or full tier`);
    assert.ok(!/sculpt.*crash/.test(n), n);
  }
  assert.deepEqual(Object.keys(FAST_FLOWS).sort(), ['a composed build with conservation', 'a crash and restart', 'districts', 'module billing', 'survival billing and refund', 'undo and revert'].sort());
  for (const [area, names] of Object.entries(FAST_FLOWS)) assert.ok(names.length, area);
});

test('item 3: fastPick takes the fast set by exact name, plus the touched flows by word, with what they need', () => {
  const why = fastPick(STUB);
  assert.deepEqual([...why.keys()].sort(), fastNames().sort());
  // `survival` alone would pick dozens: the fast set is by exact name
  assert.ok(!why.has('survival-district'));
  const touched = fastPick(STUB, ['adopt-ns']);
  assert.equal(touched.get('adopt-ns'), '');
  assert.equal(touched.get('adopt'), 'adopt-ns', 'adopt-ns needs adopt');
});

test('item 3: the fast set fits about 25 min of wall time on 3 clients, by the baseline', () => {
  const secs = mergeFlowSeconds([BASE]);
  const why = fastPick(STUB);
  // the composed flows share a snapshot writer, so they pack as one group (worst case: every fast flow of a family together)
  const composed = [...why.keys()].filter((n) => n.startsWith('composed-'));
  const groups = groupFlows(STUB, new Set(why.keys()), composed.slice(1).map((n) => [composed[0], n]));
  const lanes = packFlows(groups, secs, 3);
  const { wall } = projectWall(lanes, { sanity: LANE_OVERHEAD_S, stagger: 120 });
  assert.ok(wall <= 25 * 60, `projected ${Math.round(wall / 60)} min`);
});

// ------------------------------------------------------------------ item 2: three clients

test('item 2: three lanes balance by time, and the projection takes the stagger and the per-lane overhead', () => {
  const lanes = packFlows([['a'], ['b'], ['c'], ['d'], ['e']], { a: 300, b: 200, c: 200, d: 100, e: 100 }, 3);
  assert.deepEqual(lanes.map((l) => l.seconds), [300, 300, 300]);
  assert.deepEqual(projectWall(lanes, { sanity: 240, stagger: 120 }), { wall: 780, lanes: [540, 660, 780] });
  // the whole stub baseline: 3 lanes beat 2 by well over 15 min
  const secs = mergeFlowSeconds([BASE]);
  const groups = groupFlows(STUB, null, [['gate-mech', 'gate-mech-lots'], ['gate-mech', 'gate-mech-swing'], ['gate-mech', 'drawbridge']]);
  const two = projectWall(packFlows(groups, secs, 2), { sanity: 240, stagger: 120 }).wall;
  const three = projectWall(packFlows(groups, secs, 3), { sanity: 240, stagger: 120 }).wall;
  assert.ok(two - three > 15 * 60, `2 lanes ${Math.round(two / 60)} min, 3 lanes ${Math.round(three / 60)} min`);
});

// ------------------------------------------------------------------ item 1: the two-client nightly

test('item 1: the nightly runs stub on 2 clients, 8590/8591 and 8600/8601, and checks all four ports', () => {
  assert.equal(NIGHTLY_CLIENTS, 2);
  assert.deepEqual(NIGHTLY_SIDECAR_PORTS, [8590, 8600]);
  assert.deepEqual(NIGHTLY_PORTS, [8590, 8591, 8600, 8601]);
  assert.deepEqual(stepArgs('stub'), ['stub', '--clients', '2', '--ports', '8590,8600', '--stagger', '120']);
  assert.deepEqual(stepArgs('free'), ['free']);
  const nightly = fs.readFileSync(new URL('../tools/nightly.mjs', import.meta.url), 'utf8');
  assert.match(nightly, /const PORTS = NIGHTLY_PORTS;/);
  assert.match(nightly, /\.\.\.stepArgs\(mode\)/);
  // the night's SIGKILL waits past a lead's own stop bound (90 s for its children, then their JVMs and launchers)
  assert.ok(STOP_GRACE_MS > 115_000);
  assert.match(nightly, /process\.kill\(-child\.pid, 'SIGKILL'\); \} catch \{ \/\* gone \*\/ \} \}, STOP_GRACE_MS\)/);
});

test('item 1: the baseline stub fits the night on 2 lanes; on one lane it did not', () => {
  const secs = mergeFlowSeconds([BASE]);
  const groups = groupFlows(STUB, null, []);
  const two = packFlows(groups, secs, 2).map((l) => l.seconds);
  const fit = nightFits({ lanes: two, extra: 5 * 60 });
  assert.ok(fit.fits, `${fit.minutes} of ${fit.limit} min`);
  const one = nightFits({ lanes: packFlows(groups, secs, 1).map((l) => l.seconds), extra: 5 * 60 });
  assert.ok(!one.fits, `one lane: ${one.minutes} min`);
  assert.equal(LIMIT_MIN, 165);
});

// ------------------------------------------------------------------ item 4: the RAM disk's start floor

const GIB = 1024 ** 3;
/** A stubbed df: free bytes per call from `seq` (the last repeats), a clock that sleeps by advancing. */
function rig(seq) {
  let t = 0, i = 0;
  return {
    opts: { statfs: () => ({ bavail: seq[Math.min(i++, seq.length - 1)] / 4096, bsize: 4096 }), exists: () => true, now: () => t, sleep: async (ms) => { t += ms; } },
    t: () => t,
  };
}

test('item 4: at or above the floor the client starts at once', async () => {
  const r = rig([7 * GIB]);
  const got = await waitTmpFloor('/Volumes/MCTmp/tmp/steward/x', { env: {}, ...r.opts });
  assert.equal(got.ok, true);
  assert.equal(got.floorGiB, 6);
  assert.equal(r.t(), 0);
});

test('item 4: below the floor it waits, re-checking, and starts once space comes back', async () => {
  const r = rig([2 * GIB, 3 * GIB, 6 * GIB]);
  const got = await waitTmpFloor('/x', { env: {}, ...r.opts });
  assert.equal(got.ok, true);
  assert.equal(r.t(), 30_000);
});

test('item 4: past its bound (15 min) it FAILS, naming the free space and the floor', async () => {
  const r = rig([1.5 * GIB]);
  const got = await waitTmpFloor('/x', { env: {}, ...r.opts });
  assert.equal(got.ok, false);
  assert.ok(r.t() >= 15 * 60_000 && r.t() <= 15 * 60_000 + 15_000, `waited ${r.t()} ms`);
  assert.match(got.message, /1\.5 GiB free, below the 6 GiB floor after 15 min/);
});

test('item 4: an unreadable free space FAILS (never a silent pass); the env sets the floor, capped wait', async () => {
  const bad = await waitTmpFloor('/x', { env: {}, statfs: () => { throw new Error('EIO'); }, exists: () => true });
  assert.equal(bad.ok, false);
  assert.match(bad.message, /could not read/);
  assert.deepEqual(tmpFloor({ E2E_TMP_FLOOR_GIB: '10', E2E_TMP_WAIT_MIN: '60' }), { gib: 10, waitMin: 15, warnings: [] });
  assert.equal(tmpFloor({ E2E_TMP_FLOOR_GIB: 'lots' }).gib, 6);
  const r = rig([1]);
  assert.equal((await waitTmpFloor('/x', { env: { E2E_TMP_FLOOR_GIB: '0' }, ...r.opts })).ok, true);
});

test('item 4: e2e waits for the floor after its slot and before its first launch only, failing as a check', () => {
  assert.match(SRC, /await preflight\(\);\n(\s+\S.*\n){2}\s+await takeSlot\(\);\n\s+await ramFloor\(\);\n\s+launch\(\);/);
  assert.equal(SRC.match(/await ramFloor\(\)/g).length, 1, 'only before the first launch');
  assert.match(SRC, /if \(sidecarDir\?\.mode !== 'ram'\) return;/);
  assert.match(SRC, /fail\(e\.check \?\? 'run', e\.message\)/);
});

// ------------------------------------------------------------------ item 5: the settled cleanup

test('item 5: a removal a straggler spoils is tried again, bounded, then said', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'settle-'));
  own(dir);
  const seq = ['failed', 'failed', 'removed'];
  let sleeps = 0;
  const got = await cleanupOwnedSettled({ remove: () => seq.shift(), sleep: async () => { sleeps++; }, tries: 4 });
  assert.deepEqual(got, [[dir, 'removed']]);
  assert.equal(sleeps, 2);
  own(dir);
  const stuck = await cleanupOwnedSettled({ remove: () => 'failed', sleep: async () => { sleeps++; }, tries: 4 });
  assert.deepEqual(stuck, [[dir, 'failed']]);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('item 3: flows touched by file: their own code or fixtures changed; harness code every flow runs touches none by itself', () => {
  const base = blocksWithLibs("import { h } from './lib/x.mjs';\nfunction aFlow() { return h() + helper(); }\nfunction bFlow() { return h(); }\nfunction cFlow() { return h(); }\nfunction helper() { return 1; }\n", () => 'export const h = 1;');
  const head = blocksWithLibs("import { h } from './lib/x.mjs';\nfunction aFlow() { return h() + helper(); }\nfunction bFlow() { return h(); }\nfunction cFlow() { return h(); }\nfunction helper() { return 2; }\n", () => 'export const h = 2;');
  const list = [{ name: 'a' }, { name: 'b' }, { name: 'c' }];
  const closures = { a: { names: ['aFlow', 'h', 'helper'], fixtures: ['fixtures/talk/'] }, b: { names: ['bFlow', 'h'], fixtures: ['fixtures/one.json'] }, c: { names: ['cFlow', 'h'], fixtures: [] } };
  const r = touchedByFiles({ list, closures, base, head, changed: ['fixtures/one.json', 'mod/src/main/X.java'] });
  // helper (a's own) and b's fixture; h is the harness (all three reach it), Java maps to no flow
  assert.deepEqual([...r.touched.keys()], ['a', 'b']);
  assert.match(r.touched.get('a'), /code helper/);
  assert.match(r.touched.get('b'), /fixtures fixtures\/one\.json/);
  assert.deepEqual(r.harness, ['h']);
  // a fixture directory covers what is under it
  assert.deepEqual([...touchedByFiles({ list, closures, base: head, head, changed: ['fixtures/talk/x.json'] }).touched.keys()], ['a']);
});

test('item 3: e2e adds the touched flows to --fast, with what they need, before the tier skips', () => {
  assert.match(SRC, /for \(const \[n, w\] of fastTouched\(\)\)/);
  assert.ok(SRC.indexOf('fastTouched()) {') < SRC.indexOf('for (const sk of tierSkips(flows, why'), 'touched flows still pass the tier skips');
});

// ------------------------------------------------------------------ item 5: the wiring

test('item 5: the lead empties each child\'s sidecar dir after it ends, the nightly after each step, the driver with evidence and retries', () => {
  assert.match(SRC, /const reap = reapSidecar\(at\.gameDir, \{ pid: child\.pid/);
  assert.match(SRC, /Date\.parse\(x\?\.at \?\? ''\) >= t0/, 'only this child\'s summary names its kept world');
  assert.match(SRC, /copyEvidence\(sidecarDir\.dir, path\.join\(WORLD, 'steward-sidecar-evidence'\)\)/);
  assert.match(SRC, /of await cleanupOwnedSettled\(\)\)/);
  const nightly = fs.readFileSync(new URL('../tools/nightly.mjs', import.meta.url), 'utf8');
  assert.match(nightly, /releaseSlot\(slot\.ticket, \{ log \}\);\n\s+\}\n(\s+\/\/.*\n)*\s+reapWorktree\(\);/);
  assert.match(nightly, /const r = reapSidecar\(path\.join\(mod, n\)\);/);
});
