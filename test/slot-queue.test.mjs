// slice SQ: Steward's clients take their slots from Architect's queue through its CLI (tools/lib/slot-queue.mjs), here against a fake CLI.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { acquire, release, heldTickets, runLanes, slotCli, waitLine, waitRecord, SlotWait, inheritedSlot, reserveAcquireAt, ACQUIRE_GAP_MS } from '../tools/lib/slot-queue.mjs';

const LIB = pathToFileURL(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'tools', 'lib', 'slot-queue.mjs')).href;

/** A fake Architect checkout whose tools/slot.mjs logs every call and answers acquires as FAKE_SLOT_PLAN says (ok,timeout,...). */
function fakeArchitect() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'slotq-'));
  fs.mkdirSync(path.join(dir, 'tools'));
  const logFile = path.join(dir, 'calls.log');
  fs.writeFileSync(path.join(dir, 'tools', 'slot.mjs'), `
import fs from 'node:fs';
const [cmd, ...argv] = process.argv.slice(2);
const opt = (f) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : undefined; };
fs.appendFileSync(${JSON.stringify(logFile)}, JSON.stringify({ cmd, argv }) + '\\n');
const n = fs.readFileSync(${JSON.stringify(logFile)}, 'utf8').split('\\n').filter((l) => l && JSON.parse(l).cmd === 'acquire').length;
if (cmd === 'acquire') {
  const plan = (process.env.FAKE_SLOT_PLAN ?? 'ok').split(',');
  const what = plan[Math.min(n, plan.length) - 1];
  if (what === 'timeout') { console.error('slot steward: waiting (slots full)'); console.error('slot: waited 0 min for a slot (load 9.5 / max1 72; turbo queued (pid 1); holders: none)'); process.exit(2); }
  if (what === 'usage') { console.error('slot: acquire needs --label'); process.exit(1); }
  if (what === 'fixed') { console.log(JSON.stringify({ slot: n, ticket: 't-1-' + opt('--pid'), waitedSeconds: 0 })); process.exit(0); }
  if (what === 'ms') { console.log(JSON.stringify({ slot: n, ticket: 't-' + Date.now() + '-' + opt('--pid'), waitedSeconds: 0 })); process.exit(0); }
  console.log(JSON.stringify({ slot: n, ticket: 't-' + (1000 + n) + '-' + process.pid, waitedSeconds: 0 }));
} else if (cmd === 'release') {
  console.log(JSON.stringify({ ticket: opt('--ticket'), released: [1], queued: false }));
} else if (cmd === 'status') {
  console.log(JSON.stringify({ limit: 6, free: 0, holders: [{ slot: 1 }], queue: [], load: { load1: 9.5, max1: 72 }, turbo: { active: true, running: [], reserving: [], queued: [{ pid: 1 }] } }));
}
`);
  const calls = () => (fs.existsSync(logFile) ? fs.readFileSync(logFile, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)) : []);
  return { dir, calls };
}

const quiet = () => {};
const arg = (c, f) => c.argv[c.argv.indexOf(f) + 1];

test('the CLI is $ARCHITECT_DIR/tools/slot.mjs, else ARCHITECT_CHECKOUT, else ../architect-mc', () => {
  assert.equal(slotCli({ env: { ARCHITECT_DIR: '/a', ARCHITECT_CHECKOUT: '/b' }, root: '/x/steward' }), '/a/tools/slot.mjs');
  assert.equal(slotCli({ env: { ARCHITECT_CHECKOUT: '/b' }, root: '/x/steward' }), '/b/tools/slot.mjs');
  assert.equal(slotCli({ env: {}, root: '/x/steward' }), '/x/architect-mc/tools/slot.mjs');
});

test('acquire asks as steward for this driver and worktree; release frees that ticket, once', async () => {
  const fake = fakeArchitect();
  const env = { ...process.env, ARCHITECT_DIR: fake.dir, FAKE_SLOT_PLAN: 'ok' };
  const s = await acquire('e2e stub --only sanity', { root: '/w/steward-sq', env, log: quiet });
  assert.equal(s.slot, 1);
  assert.match(s.ticket, /^t-1001-\d+$/);
  assert.deepEqual(heldTickets(), [s.ticket]);
  assert.equal(release(s.ticket, { log: quiet }), true);
  assert.equal(release(s.ticket, { log: quiet }), false, 'a second release is a no-op');
  assert.deepEqual(heldTickets(), []);
  const c = fake.calls();
  assert.deepEqual(c.map((x) => x.cmd), ['acquire', 'release']);
  assert.equal(arg(c[0], '--who'), 'steward');
  assert.equal(arg(c[0], '--pid'), String(process.pid));
  assert.equal(arg(c[0], '--worktree'), '/w/steward-sq');
  assert.equal(arg(c[0], '--label'), 'e2e stub --only sanity');
  assert.equal(arg(c[0], '--max-wait-min'), '15');
  assert.equal(arg(c[1], '--ticket'), s.ticket);
});

test('a timeout is a SlotWait: waited N min for a slot (load L, turbo T), with a record for summary.json', async () => {
  const fake = fakeArchitect();
  const env = { ...process.env, ARCHITECT_DIR: fake.dir, FAKE_SLOT_PLAN: 'timeout', E2E_SLOT_WAIT_MIN: '0' };
  const err = await acquire('e2e free', { env, log: quiet }).then(() => null, (e) => e);
  assert.ok(err instanceof SlotWait);
  assert.equal(err.code, 'SLOT_TIMEOUT');
  assert.equal(err.record.timedOut, true);
  assert.equal(err.record.load, 9.5);
  assert.equal(err.record.turbo, 'queued');
  assert.match(err.record.reason, /waited 0 min for a slot/);
  assert.match(err.message, /^waited \d+(\.\d)? min for a slot \(load 9\.5, turbo queued\)$/);
  assert.deepEqual(heldTickets(), []);
  assert.equal(waitLine(waitRecord({ minutes: 15, status: { load: { load1: 80 }, turbo: { running: [{}] } } })), 'waited 15 min for a slot (load 80, turbo running)');
});

test('a usage error is an error, not a wait', async () => {
  const fake = fakeArchitect();
  const err = await acquire('x', { env: { ...process.env, ARCHITECT_DIR: fake.dir, FAKE_SLOT_PLAN: 'usage' }, log: quiet }).then(() => null, (e) => e);
  assert.ok(err && !(err instanceof SlotWait));
  assert.match(err.message, /exited 1: slot: acquire needs --label/);
});

test('a missing CLI names the fix; E2E_SLOTS=off takes no slot and says so', async () => {
  const none = fs.mkdtempSync(path.join(os.tmpdir(), 'slotq-none-'));
  const err = await acquire('e2e free', { env: { ARCHITECT_DIR: none }, log: quiet }).then(() => null, (e) => e);
  assert.equal(err?.code, 'SLOT_NO_CLI');
  assert.match(err.message, /ARCHITECT_DIR/);
  assert.match(err.message, /v0\.74\.0/);
  assert.match(err.message, /E2E_SLOTS=off/);
  const said = [];
  const off = await acquire('e2e free', { env: { ARCHITECT_DIR: none, E2E_SLOTS: 'off' }, log: (m) => said.push(m) });
  assert.deepEqual(off, { off: true, slot: null, ticket: null });
  assert.match(said.join('\n'), /E2E_SLOTS=off: e2e free takes NO slot/);
});

test('an owner\'s slot (E2E_SLOT_TICKET) is used as is', () => {
  assert.deepEqual(inheritedSlot({ E2E_SLOT_TICKET: 't-1-2', E2E_SLOT: '4' }), { ticket: 't-1-2', slot: 4, inherited: true });
  assert.equal(inheritedSlot({}), null);
});

/** A driver that takes a slot through the lib, then leaves the way `how` says. */
function driver(how, env) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'slotq-drv-'));
  const f = path.join(dir, 'driver.mjs');
  fs.writeFileSync(f, `
import { acquire } from ${JSON.stringify(LIB)};
const s = await acquire('drv', { log: () => {} });
console.log(s.ticket);
const how = ${JSON.stringify(how)};
if (how === 'throw') throw new Error('boom');
if (how === 'reject') Promise.reject(new Error('boom'));
if (how === 'exit') process.exit(1);
if (how === 'sigterm') { process.kill(process.pid, 'SIGTERM'); await new Promise((r) => setTimeout(r, 5000)); }
if (how === 'sigint') { process.kill(process.pid, 'SIGINT'); await new Promise((r) => setTimeout(r, 5000)); }
`);
  return spawnSync(process.execPath, [f], { env, encoding: 'utf8' });
}

for (const [how, code] of [['normal', 0], ['throw', 1], ['reject', 1], ['exit', 1], ['sigterm', 143], ['sigint', 130]]) {
  test(`the slot is released when the driver ends: ${how}`, () => {
    const fake = fakeArchitect();
    const r = driver(how, { ...process.env, ARCHITECT_DIR: fake.dir, FAKE_SLOT_PLAN: 'ok' });
    assert.equal(r.status, code, r.stderr);
    const ticket = r.stdout.trim().split('\n')[0];
    const c = fake.calls();
    assert.deepEqual(c.map((x) => x.cmd), ['acquire', 'release']);
    assert.equal(arg(c[1], '--ticket'), ticket);
  });
}

// ------------------------------------------------------------------ runLanes: one slot per client, the fallback to one

function lanesHarness(plan) {
  const events = [];
  let n = 0;
  const get = async (lane) => {
    const what = plan[n++];
    events.push(`acquire ${lane.k}`);
    if (what === 'timeout') throw new SlotWait(waitRecord({ minutes: 15, status: { load: { load1: 50 }, turbo: { running: [] } } }));
    return { slot: n, ticket: `t-${n}` };
  };
  const start = async (lane, s, host) => {
    events.push(`start ${lane.k} on ${s.ticket}${host ? ` after ${host.k}` : ''}`);
    await new Promise((r) => setTimeout(r, lane.k === 1 ? 30 : 5));
    events.push(`end ${lane.k}`);
    return { k: lane.k };
  };
  const free = (t) => events.push(`release ${t}`);
  return { events, opts: { acquire: get, start, release: free, log: (m) => events.push(`log ${m}`) } };
}

const lanes = (n) => Array.from({ length: n }, (_, i) => ({ k: i + 1 }));

test('(fix-check P2) a lane ending during a shutdown frees nothing (the release phase does, after the stop steps)', async () => {
  const h = lanesHarness(['ok', 'ok']);
  let stopping = false;
  const start = h.opts.start;
  h.opts.start = async (lane, s, host) => { const r = await start(lane, s, host); stopping = true; return r; };
  await runLanes(lanes(2), { ...h.opts, stopping: () => stopping });
  assert.deepEqual(h.events.filter((e) => e.startsWith('release')), []);
});

test('two clients take two slots, each released when its client is done', async () => {
  const h = lanesHarness(['ok', 'ok']);
  const rows = await runLanes(lanes(2), h.opts);
  assert.deepEqual(rows.map((r) => [r.result.k, r.slot.ticket, r.host, r.waited]), [[1, 't-1', null, null], [2, 't-2', null, null]]);
  assert.deepEqual(h.events, ['acquire 1', 'start 1 on t-1', 'acquire 2', 'start 2 on t-2', 'end 2', 'release t-2', 'end 1', 'release t-1']);
});

test('the second slot not coming: its lane runs after the first, on the first\'s slot, and says so', async () => {
  const h = lanesHarness(['ok', 'timeout', 'ok']);
  const rows = await runLanes(lanes(3), h.opts);
  assert.deepEqual(rows.map((r) => [r.result.k, r.slot.ticket, r.host?.k ?? null]), [[1, 't-1', null], [2, 't-1', 1], [3, 't-1', 1]]);
  assert.equal(rows[1].waited.waitedMin, 15);
  assert.equal(rows[2].waited, null, 'lanes after a timed-out one are not asked for');
  assert.ok(h.events.some((e) => /^log clients: waited 15 min for a slot \(load 50, turbo none\): falling back to one client/.test(e)));
  const order = h.events.filter((e) => !e.startsWith('log'));
  assert.deepEqual(order, ['acquire 1', 'start 1 on t-1', 'acquire 2', 'end 1', 'start 2 on t-1 after 1', 'end 2', 'start 3 on t-1 after 1', 'end 3', 'release t-1']);
});

test('the first slot not coming: nothing starts, the wait propagates', async () => {
  const h = lanesHarness(['timeout']);
  const err = await runLanes(lanes(2), h.opts).then(() => null, (e) => e);
  assert.equal(err?.code, 'SLOT_TIMEOUT');
  assert.deepEqual(h.events, ['acquire 1']);
});

test('runLanes over the CLI: the second acquire times out, one client runs both lanes, its slot released once', async () => {
  const fake = fakeArchitect();
  const env = { ...process.env, ARCHITECT_DIR: fake.dir, FAKE_SLOT_PLAN: 'ok,timeout', E2E_SLOT_WAIT_MIN: '0' };
  const started = [];
  const rows = await runLanes(lanes(2), {
    acquire: (lane) => acquire(`e2e stub c${lane.k}`, { env, log: quiet }),
    start: async (lane, s, host) => { started.push([lane.k, s.slot, host?.k ?? null]); return {}; },
    release: (t) => release(t, { log: quiet }),
    log: quiet,
  });
  assert.deepEqual(started, [[1, 1, null], [2, 1, 1]]);
  assert.equal(rows[1].waited.turbo, 'queued');
  const c = fake.calls().filter((x) => x.cmd !== 'status');
  assert.deepEqual(c.map((x) => x.cmd), ['acquire', 'acquire', 'release']);
  assert.equal(arg(c[2], '--ticket'), rows[0].slot.ticket);
  assert.deepEqual(heldTickets(), []);
});

test('one process\'s acquires are reserved at least 2 ms apart, even when asked for in the same millisecond', () => {
  const now = Date.now();
  const a = reserveAcquireAt(now), b = reserveAcquireAt(now), c = reserveAcquireAt(now);
  assert.ok(b - a >= ACQUIRE_GAP_MS && c - b >= ACQUIRE_GAP_MS, `${a} ${b} ${c}`);
  assert.ok(ACQUIRE_GAP_MS >= 2);
});

test('two acquires by one holder at once (a --clients lead) get different tickets (t-<ms>-<pid>)', async () => {
  const fake = fakeArchitect();
  const env = { ...process.env, ARCHITECT_DIR: fake.dir, FAKE_SLOT_PLAN: 'ms,ms' };
  const [a, b] = await Promise.all([acquire('c1', { env, log: quiet, pid: 777 }), acquire('c2', { env, log: quiet, pid: 777 })]);
  assert.notEqual(a.ticket, b.ticket);
  for (const t of [a.ticket, b.ticket]) release(t, { log: quiet });
});

test('a ticket this process already holds is refused, and the held one is not released', async () => {
  const fake = fakeArchitect();
  const env = { ...process.env, ARCHITECT_DIR: fake.dir, FAKE_SLOT_PLAN: 'fixed,fixed' };
  const a = await acquire('c1', { env, log: quiet, pid: 778 });
  const err = await acquire('c2', { env, log: quiet, pid: 778 }).then(() => null, (e) => e);
  assert.equal(err?.code, 'SLOT_DUP_TICKET');
  assert.match(err.message, /v0\.75\.0/);
  assert.ok(heldTickets().includes(a.ticket));
  assert.deepEqual(fake.calls().map((x) => x.cmd), ['acquire', 'acquire']);
  release(a.ticket, { log: quiet });
});

test('(E2E-SPEED-2) three clients take three slots in order, each released when its client is done', async () => {
  const h = lanesHarness(['ok', 'ok', 'ok']);
  const rows = await runLanes(lanes(3), h.opts);
  assert.deepEqual(rows.map((r) => [r.result.k, r.slot.ticket, r.host]), [[1, 't-1', null], [2, 't-2', null], [3, 't-3', null]]);
  assert.deepEqual(h.events.filter((e) => e.startsWith('acquire')), ['acquire 1', 'acquire 2', 'acquire 3']);
  assert.deepEqual(h.events.filter((e) => e.startsWith('release')).sort(), ['release t-1', 'release t-2', 'release t-3']);
});
