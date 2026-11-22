// Slice TMPSWEEP: an e2e run's own sidecar dir goes on every way out of the driver, survives its client relaunches, and the start-of-run sweep
// prunes only marked dirs whose owner is gone.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { sweepStale, markOwner, readOwner, removeOwned, own, ownedDirs, isHeld, ownerGone, processStart, dirBytes, keptBounds, OWNER_FILE, LINK_FILE, PROCESS_START_MS } from '../tools/lib/tmp-owner.mjs';
import { ramSidecarData, repairSidecarData } from '../tools/lib/ram-tmp.mjs';

const LIB = pathToFileURL(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'tools', 'lib')).href;
const tmp = (p) => fs.mkdtempSync(path.join(os.tmpdir(), p));

/**
 * A driver like e2e.mjs: it makes its sidecar dir through ramSidecarData (own, fresh) on a fake RAM root, writes data into it, prints `ready <dir>`, then
 * leaves the way `how` says. `relaunch` quits and relaunches its "client" twice (in-process, like restartClient) and prints `kept` when the data survived.
 * `slotlike` adds a SIGINT listener that exits, as slot-queue's does.
 */
function driver(how, { game, root, env = {} }) {
  const src = `
import fs from 'node:fs';
import path from 'node:path';
import { ramSidecarData } from '${LIB}/ram-tmp.mjs';
const how = process.argv[2];
if (how === 'slotlike') process.on('SIGINT', () => process.exit(130));
const sd = ramSidecarData(${JSON.stringify(game)}, process.env, ${JSON.stringify(root)}, { fresh: true, own: true });
fs.mkdirSync(path.join(sd.dir, 'regions', 'plans'), { recursive: true });
fs.writeFileSync(path.join(sd.dir, 'regions', 'plans', 'p.json'), '{}');
console.log('ready ' + sd.dir);
const quit = async () => {}, launch = () => {};
if (how === 'relaunch') {
  for (let i = 0; i < 2; i++) { await quit(); launch(); }
  console.log(fs.existsSync(path.join(sd.dir, 'regions', 'plans', 'p.json')) ? 'kept' : 'lost');
} else if (how === 'throw') throw new Error('boom');
else if (how === 'reject') await Promise.reject(new Error('boom'));
else if (how === 'exit3') process.exit(3);
else if (how === 'signal' || how === 'slotlike') setInterval(() => {}, 1000);
`;
  const file = path.join(tmp('tmpown-drv-'), 'driver.mjs');
  fs.writeFileSync(file, src);
  const child = spawn(process.execPath, [file, how], { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, ...env } });
  let out = '';
  const ready = new Promise((resolve) => child.stdout.on('data', (d) => { out += d; if (/ready /.test(out)) resolve(); }));
  const done = new Promise((resolve) => child.on('close', (code, signal) => resolve({ code, signal })));
  return { child, ready, done, out: () => out, dir: () => /ready (.+)/.exec(out)?.[1] };
}

/** The run's data is gone: the link still leads to the RAM dir, now empty and unmarked (a later dev client stays on the RAM disk). */
function assertEmptied(dir, game, how) {
  const link = path.join(game, 'architect', 'sidecar-data');
  assert.ok(fs.lstatSync(link).isSymbolicLink(), `${how}: link gone`);
  assert.equal(fs.realpathSync(link), fs.realpathSync(dir));
  assert.deepEqual(fs.readdirSync(dir), [], `${how}: data left`);
}

for (const how of ['end', 'throw', 'reject', 'exit3']) {
  test(`the run's sidecar data goes when the driver ends (${how}); the link stays, to an empty dir`, async () => {
    const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
    const d = driver(how, { game, root });
    await d.ready;
    const r = await d.done;
    if (how === 'exit3') assert.equal(r.code, 3);
    assert.ok(d.dir().startsWith(path.join(root, 'steward', 'sidecar-')));
    assertEmptied(d.dir(), game, how);
  });
}

for (const [how, sig] of [['signal', 'SIGINT'], ['signal', 'SIGTERM'], ['slotlike', 'SIGINT']]) {
  test(`the run's sidecar data goes on ${sig}${how === 'slotlike' ? ' with an exiting listener (slot-queue)' : ''}`, async () => {
    const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
    const d = driver(how, { game, root });
    await d.ready;
    assert.ok(fs.existsSync(path.join(d.dir(), OWNER_FILE)));
    d.child.kill(sig);
    const r = await d.done;
    // the shutdown coordinator exits 128 + the signal's number once its phases ran
    assert.equal(r.code, sig === 'SIGINT' ? 130 : 143);
    assertEmptied(d.dir(), game, sig);
  });
}

test('a client relaunch keeps the dir and its data; it goes only when the driver ends', async () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const d = driver('relaunch', { game, root });
  await d.ready;
  await d.done;
  assert.match(d.out(), /\bkept\b/);
  assertEmptied(d.dir(), game, 'relaunch');
});

test('STEWARD_KEEP_TMP=1 keeps the dir, still marked for a later sweep', async () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const d = driver('end', { game, root, env: { STEWARD_KEEP_TMP: '1' } });
  await d.ready;
  await d.done;
  assert.ok(readOwner(d.dir()));
});

test('on disk (STEWARD_RAM_TMP=0) the dir the run made goes; an existing one of someone else is left', async () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const link = path.join(game, 'architect', 'sidecar-data');
  const d = driver('end', { game, root, env: { STEWARD_RAM_TMP: '0' } });
  await d.ready;
  assert.equal(d.dir(), link);
  await d.done;
  assert.equal(fs.existsSync(link), false);
  // a dev client's data, not marked: kept
  fs.mkdirSync(link, { recursive: true });
  fs.writeFileSync(path.join(link, 'state.json'), '{}');
  const e = driver('end', { game, root, env: { STEWARD_RAM_TMP: '0' } });
  await e.ready;
  await e.done;
  assert.ok(fs.existsSync(path.join(link, 'state.json')));
});

test('on disk, an earlier run\'s dir whose owner is gone is taken again (emptied) and removed', () => {
  const game = tmp('tmpown-game-');
  const link = path.join(game, 'architect', 'sidecar-data');
  fs.mkdirSync(link, { recursive: true });
  fs.writeFileSync(path.join(link, 'old.json'), '{}');
  markOwner(link, {}, { pid: 999_999, startMs: 1 });
  const r = ramSidecarData(game, { STEWARD_RAM_TMP: '0' }, '/nonexistent', { fresh: true, own: true, starts: new Map([[1, 0]]) });
  assert.equal(r.owned, true);
  assert.equal(fs.existsSync(path.join(link, 'old.json')), false);
  assert.ok(ownedDirs().includes(link));
  assert.equal(removeOwned(link), 'removed');
  assert.equal(fs.existsSync(link), false);
});

test('a dir another process has marked since is never removed (not by glob, only by exact path while it is ours)', () => {
  const root = tmp('tmpown-ram-');
  const dir = path.join(root, 'sidecar-abc');
  fs.mkdirSync(dir);
  own(dir);
  assert.equal(readOwner(dir).startMs, PROCESS_START_MS);
  markOwner(dir, {}, { pid: process.pid + 1, startMs: 5 });
  const sibling = path.join(root, 'sidecar-abd');
  fs.mkdirSync(sibling);
  assert.equal(removeOwned(dir), 'not-mine');
  assert.ok(fs.existsSync(dir));
  assert.ok(fs.existsSync(sibling));
});

test('the sweep removes only marked sidecar dirs whose owner is gone', () => {
  const root = tmp('tmpown-ram-');
  const mk = (name, owner) => {
    const p = path.join(root, name);
    fs.mkdirSync(p);
    fs.writeFileSync(path.join(p, 'data'), 'x');
    if (owner) markOwner(p, {}, owner);
    return p;
  };
  const now = Date.now();
  const dead = mk('sidecar-dead', { pid: 4242, startMs: now - 10_000 });
  const reused = mk('sidecar-reused', { pid: 4343, startMs: now - 3_600_000 }); // pid 4343 runs now, but started later: another process
  const live = mk('sidecar-live', { pid: 4444, startMs: now - 5_000 });
  const unmarked = mk('sidecar-unmarked', null);
  const other = mk('arch-thing-AbCdEf', { pid: 4242, startMs: now - 10_000 }); // not Steward's name
  fs.symlinkSync(dead, path.join(root, 'sidecar-link'));
  const starts = new Map([[4444, now - 5_000], [4343, now - 60_000], [1, 0]]);
  // the recheck just before a removal judged by the same table (the real one is the next test's)
  const gone = (o) => !isHeld(o, starts);
  const r = sweepStale({ root, starts, now, gone });
  assert.deepEqual(r.removed.sort(), ['sidecar-dead', 'sidecar-reused']);
  assert.deepEqual(r.kept, ['sidecar-live']);
  assert.equal(fs.existsSync(dead), false);
  assert.equal(fs.existsSync(reused), false);
  for (const p of [live, unmarked, other]) assert.ok(fs.existsSync(p), p);
  assert.ok(fs.lstatSync(path.join(root, 'sidecar-link')).isSymbolicLink());
  // an age floor keeps a recently touched dead-owner dir
  const young = mk('sidecar-young', { pid: 4242, startMs: now - 10_000 });
  assert.deepEqual(sweepStale({ root, starts, now, hours: 1, gone }).removed, []);
  assert.ok(fs.existsSync(young));
  // a missing root is nothing to do
  assert.deepEqual(sweepStale({ root: path.join(root, 'nope'), starts }).removed, []);
});

/** A live process that isn't this one: a sleeping node child, with its pid and start time as ps has it. */
async function liveOther() {
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
  await new Promise((r) => child.on('spawn', r));
  await new Promise((r) => setTimeout(r, 50));
  return { child, pid: child.pid, startMs: processStart(child.pid) };
}

test('(batch-074 P1) a marker rewritten by a live process between the snapshot and the delete is kept, with its data', async () => {
  const root = tmp('tmpown-ram-');
  const p = path.join(root, 'sidecar-raced');
  fs.mkdirSync(p);
  fs.writeFileSync(path.join(p, 'data'), 'x');
  markOwner(p, {}, { pid: 4242, startMs: Date.now() - 10_000 });
  const other = await liveOther();
  try {
    assert.ok(Number.isFinite(other.startMs), 'ps reads the live child');
    // the snapshot predates the new driver (it lists neither pid): the old marker reads as gone; then the live driver marks the dir
    const starts = new Map([[1, 0]]);
    const r = sweepStale({ root, starts, between: (dir) => markOwner(dir, {}, { pid: other.pid, startMs: other.startMs }) });
    assert.deepEqual(r.removed, []);
    assert.deepEqual(r.kept, ['sidecar-raced']);
    assert.ok(fs.existsSync(path.join(p, 'data')));
    assert.equal(readOwner(p).pid, other.pid);
  } finally {
    other.child.kill('SIGKILL');
  }
});

test('(batch-074 P1) an owner the snapshot missed but alive at the recheck keeps its dir; once it is dead the dir goes', async () => {
  const root = tmp('tmpown-ram-');
  const p = path.join(root, 'sidecar-late');
  fs.mkdirSync(p);
  fs.writeFileSync(path.join(p, 'data'), 'x');
  const other = await liveOther();
  markOwner(p, {}, { pid: other.pid, startMs: other.startMs });
  const starts = new Map([[1, 0]]); // a snapshot taken before it started
  assert.equal(ownerGone(readOwner(p)), false);
  assert.deepEqual(sweepStale({ root, starts }).kept, ['sidecar-late']);
  assert.ok(fs.existsSync(path.join(p, 'data')));
  other.child.kill('SIGKILL');
  await new Promise((r) => other.child.on('close', r));
  assert.equal(ownerGone(readOwner(p)), true);
  assert.deepEqual(sweepStale({ root, starts }).removed, ['sidecar-late']);
  assert.equal(fs.existsSync(p), false);
  // a pid alive whose start can't be read is held; a reused pid (started long after the marker's time) is gone
  assert.equal(ownerGone({ pid: 7, startMs: 1 }, { alive: () => true, startOf: () => undefined }), false);
  assert.equal(ownerGone({ pid: 7, startMs: 1 }, { alive: () => true, startOf: () => 3_600_000 }), true);
  assert.equal(ownerGone({ pid: 7, startMs: 1 }, { alive: () => false }), true);
  // (fix-check P1) ps reporting no such process while kill(0) still finds it alive: held, and the sweep keeps the dir
  assert.equal(ownerGone({ pid: 7, startMs: 1 }, { alive: () => true, startOf: () => null }), false);
  const q = path.join(root, 'sidecar-psfail');
  fs.mkdirSync(q);
  markOwner(q, {}, { pid: 7, startMs: 1 });
  const r = sweepStale({ root, starts, alive: () => false, gone: (o) => ownerGone(o, { alive: () => true, startOf: () => null }) });
  assert.deepEqual(r.kept, ['sidecar-psfail']);
  assert.ok(fs.existsSync(q));
});

// ------------------------------------------------------------------ E2E-DISK-3 (P1, pre-existing): the preflight empties only what it owns, never kept data

const dataFile = (dir) => path.join(dir, 'regions', 'plans', 'p.json');

test('(E2E-DISK-3) STEWARD_KEEP_TMP=1 marks the dir kept: the sweep doesn\'t take it while young and small, and the next run on the game dir starts in a dir of its own', async () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const d = driver('end', { game, root, env: { STEWARD_KEEP_TMP: '1' } });
  await d.ready;
  await d.done;
  assert.equal(readOwner(d.dir()).kept, true);
  const r = sweepStale({ root: path.join(root, 'steward') });
  assert.deepEqual(r.removed, []);
  assert.ok(fs.existsSync(dataFile(d.dir())), 'kept data swept');
  const e = driver('end', { game, root });
  await e.ready;
  assert.notEqual(e.dir(), d.dir());
  await e.done;
  assert.ok(fs.existsSync(dataFile(d.dir())), 'kept data emptied by the next run');
  assert.equal(readOwner(d.dir()).kept, true);
});

test('(E2E-DISK-3) a SIGKILLed STEWARD_KEEP_TMP run (no exit hook) is still kept: marked at the start', async () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const d = driver('signal', { game, root, env: { STEWARD_KEEP_TMP: '1' } });
  await d.ready;
  d.child.kill('SIGKILL');
  await d.done;
  assert.deepEqual(sweepStale({ root: path.join(root, 'steward') }).removed, []);
  assert.ok(fs.existsSync(dataFile(d.dir())));
});

test('(E2E-DISK-3) the game dir\'s sidecar dir held by another live run (its slot wait) is never emptied: the second run refuses', async () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const d = driver('signal', { game, root });
  await d.ready;
  try {
    assert.throws(() => ramSidecarData(game, {}, root, { fresh: true, own: true }), /another live run/);
    assert.ok(fs.existsSync(dataFile(d.dir())), 'the live run\'s data emptied');
    assert.equal(fs.readlinkSync(path.join(game, 'architect', 'sidecar-data')), d.dir(), 'its link moved');
  } finally {
    d.child.kill('SIGTERM');
    await d.done;
  }
});

test('(E2E-DISK-3) unmarked data in the game dir\'s sidecar dir (a dev client\'s) is never emptied: the run takes a dir of its own', () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const dev = ramSidecarData(game, {}, root);
  fs.writeFileSync(path.join(dev.dir, 'designs.json'), 'x');
  const r = ramSidecarData(game, {}, root, { fresh: true, own: true });
  try {
    assert.notEqual(r.dir, dev.dir);
    assert.ok(path.basename(r.dir).startsWith('sidecar-'), 'still swept by name when its owner dies');
    assert.ok(fs.existsSync(path.join(dev.dir, 'designs.json')));
    assert.deepEqual(fs.readdirSync(r.dir), [OWNER_FILE]);
    assert.equal(fs.realpathSync(path.join(game, 'architect', 'sidecar-data')), fs.realpathSync(r.dir));
  } finally {
    removeOwned(r.dir, { link: path.join(game, 'architect', 'sidecar-data') });
  }
});

test('(E2E-DISK-3) the dir an earlier run left (owner dead, not kept), or the empty one a run leaves, is taken and emptied as before', () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const first = ramSidecarData(game, {}, root, { fresh: true, own: true });
  removeOwned(first.dir, { link: path.join(game, 'architect', 'sidecar-data') });
  assert.deepEqual(fs.readdirSync(first.dir), [], 'emptied and unmarked at the end');
  const again = ramSidecarData(game, {}, root, { fresh: true, own: true });
  assert.equal(again.dir, first.dir, 'the empty one is taken');
  fs.writeFileSync(path.join(again.dir, 'old.json'), '{}');
  markOwner(again.dir, {}, { pid: 999_999, startMs: 1 });
  const third = ramSidecarData(game, {}, root, { fresh: true, own: true, starts: new Map([[1, 0]]) });
  try {
    assert.equal(third.dir, first.dir);
    assert.equal(fs.existsSync(path.join(third.dir, 'old.json')), false, 'a dead owner\'s data goes');
  } finally {
    removeOwned(third.dir, { link: path.join(game, 'architect', 'sidecar-data') });
  }
});

test('(E2E-DISK-3) on disk, a kept dir (STEWARD_KEEP_TMP) is never taken again or emptied', () => {
  const game = tmp('tmpown-game-');
  const link = path.join(game, 'architect', 'sidecar-data');
  fs.mkdirSync(link, { recursive: true });
  fs.writeFileSync(path.join(link, 'old.json'), '{}');
  markOwner(link, { kept: true }, { pid: 999_999, startMs: 1 });
  const r = ramSidecarData(game, { STEWARD_RAM_TMP: '0' }, '/nonexistent', { fresh: true, own: true, starts: new Map([[1, 0]]) });
  assert.equal(r.owned, false);
  assert.ok(fs.existsSync(path.join(link, 'old.json')));
});

for (const [what, env, devData] of [['a live STEWARD_KEEP_TMP=1 run', { STEWARD_KEEP_TMP: '1' }, false], ['a live run on a dir of its own (dev data in the game dir\'s)', {}, true]]) {
  test(`(E2E-DISK-3) ${what} keeps its link: a second run on the game dir refuses, nothing relinked or emptied`, async () => {
    const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
    const link = path.join(game, 'architect', 'sidecar-data');
    if (devData) fs.writeFileSync(path.join(ramSidecarData(game, {}, root).dir, 'designs.json'), 'x');
    const d = driver('signal', { game, root, env });
    await d.ready;
    try {
      const before = fs.readlinkSync(link);
      assert.equal(before, d.dir());
      assert.throws(() => ramSidecarData(game, {}, root, { fresh: true, own: true }), /another live run/);
      assert.equal(fs.readlinkSync(link), before, 'its link moved');
      assert.ok(fs.existsSync(dataFile(d.dir())), 'its data emptied');
      assert.deepEqual(fs.readdirSync(path.join(root, 'steward')).length, devData ? 2 : 1, 'no new dir made');
    } finally {
      d.child.kill('SIGTERM');
      await d.done;
    }
  });
}

// ------------------------------------------------------------------ E2E-DISK-4: kept dirs bounded (age, cap), empty fallbacks never pile up

const HOUR = 3_600_000;
/** Backdates dir, its marker and every direct child (the sweep's age is the newest of those) by `hours`. */
function backdate(dir, hours) {
  const t = new Date(Date.now() - hours * HOUR);
  for (const n of fs.readdirSync(dir)) fs.utimesSync(path.join(dir, n), t, t);
  fs.utimesSync(dir, t, t);
}
/** A kept sidecar dir with `kb` KiB of data, marked by owner, untouched for `hours`. */
function keptDir(root, name, { kb = 64, hours = 0, owner = { pid: 999_999, startMs: 1 } } = {}) {
  const p = path.join(root, name);
  fs.mkdirSync(path.join(p, 'regions'), { recursive: true });
  fs.writeFileSync(path.join(p, 'regions', 'data.bin'), Buffer.alloc(kb * 1024, 1));
  markOwner(p, { kept: true }, owner);
  if (hours) backdate(p, hours);
  return p;
}
const DEAD = new Map([[1, 0]]);

test('(E2E-DISK-4) a kept dir whose owner is gone goes once untouched for 24 h; a younger one stays', () => {
  const root = tmp('tmpown-ram-');
  const old = keptDir(root, 'sidecar-aa-old000', { hours: 25 });
  const young = keptDir(root, 'sidecar-bb', { hours: 1 });
  const r = sweepStale({ root, starts: DEAD, alive: () => false });
  assert.deepEqual(r.removed, ['sidecar-aa-old000']);
  assert.match(r.swept.find((s) => s.name === 'sidecar-aa-old000').why, /kept.*24 h/);
  assert.deepEqual(r.kept, ['sidecar-bb']);
  assert.equal(fs.existsSync(old), false);
  assert.ok(fs.existsSync(path.join(young, 'regions', 'data.bin')));
  // the bound is configurable; 0 turns the age bound off
  const older = keptDir(root, 'sidecar-cc', { hours: 30 });
  assert.deepEqual(sweepStale({ root, starts: DEAD, alive: () => false, keptHours: 0 }).removed, []);
  assert.ok(fs.existsSync(older));
});

test('(E2E-DISK-4) past the kept cap, dead-owner kept dirs go oldest first until the total is under it', () => {
  const root = tmp('tmpown-ram-');
  const a = keptDir(root, 'sidecar-a1', { kb: 256, hours: 3 });
  const b = keptDir(root, 'sidecar-a2', { kb: 256, hours: 2 });
  const c = keptDir(root, 'sidecar-a3', { kb: 256, hours: 1 });
  const one = dirBytes(c);
  assert.ok(one >= 256 * 1024, `dirBytes counts the data (${one})`);
  const dry = sweepStale({ root, starts: DEAD, alive: () => false, keptCapBytes: Math.round(one * 1.5), dryRun: true });
  assert.deepEqual(dry.removed, ['sidecar-a1', 'sidecar-a2'], 'a dry run names them');
  for (const p of [a, b, c]) assert.ok(fs.existsSync(p), 'a dry run removes nothing');
  const r = sweepStale({ root, starts: DEAD, alive: () => false, keptCapBytes: Math.round(one * 1.5) });
  assert.deepEqual(r.removed, ['sidecar-a1', 'sidecar-a2']);
  for (const s of r.swept) assert.match(s.why, /cap/);
  assert.equal(fs.existsSync(a), false);
  assert.equal(fs.existsSync(b), false);
  assert.ok(fs.existsSync(c));
  assert.deepEqual(r.kept, ['sidecar-a3']);
});

test('(E2E-DISK-4) a live owner\'s kept dir is never swept, however old or large, and counts toward the cap', async () => {
  const root = tmp('tmpown-ram-');
  const other = await liveOther();
  try {
    const live = keptDir(root, 'sidecar-live', { kb: 512, hours: 30, owner: { pid: other.pid, startMs: other.startMs } });
    const dead = keptDir(root, 'sidecar-dead', { kb: 64, hours: 1 });
    const r = sweepStale({ root, keptCapBytes: dirBytes(live) - 1 });
    assert.deepEqual(r.removed, ['sidecar-dead'], 'the live dir alone fills the cap: the dead one goes');
    assert.ok(fs.existsSync(path.join(live, 'regions', 'data.bin')));
    assert.equal(fs.existsSync(dead), false);
    assert.ok(r.overCap, 'still over the cap: said, nothing else touched');
  } finally {
    other.child.kill('SIGKILL');
  }
});

test('(E2E-DISK-4) the sweep removes empty fallback dirs (unmarked, or kept by a dead owner with nothing but the marker), nothing else', () => {
  const root = tmp('tmpown-ram-');
  const mk = (name, { data = false, hours = 1, marker = null } = {}) => {
    const p = path.join(root, name);
    fs.mkdirSync(p);
    if (data) fs.writeFileSync(path.join(p, 'designs.json'), 'x');
    if (marker) markOwner(p, marker, { pid: 999_999, startMs: 1 });
    if (hours) backdate(p, hours);
    return p;
  };
  const empty = mk('sidecar-1a2b-Ab12Cd');
  const keptEmpty = mk('sidecar-1a2b-Ef34Gh', { marker: { kept: true } });
  const full = mk('sidecar-1a2b-Ij56Kl', { data: true });
  const fresh = mk('sidecar-1a2b-Mn78Op', { hours: 0 });
  const primary = mk('sidecar-1a2b');
  const notOurs = mk('sidecar-1a2b-toolongname');
  fs.symlinkSync(full, path.join(root, 'sidecar-1a2b-Qr90St'));
  const r = sweepStale({ root, starts: DEAD, alive: () => false });
  assert.deepEqual(r.removed.sort(), ['sidecar-1a2b-Ab12Cd', 'sidecar-1a2b-Ef34Gh']);
  assert.equal(fs.existsSync(empty), false);
  assert.equal(fs.existsSync(keptEmpty), false);
  for (const p of [full, fresh, primary, notOurs]) assert.ok(fs.existsSync(p), p);
  assert.ok(fs.lstatSync(path.join(root, 'sidecar-1a2b-Qr90St')).isSymbolicLink());
});

test('(E2E-DISK-4) a run\'s own fallback dir is removed at its end, not made again empty: the link goes back to the game dir\'s dir', () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const link = path.join(game, 'architect', 'sidecar-data');
  const dev = ramSidecarData(game, {}, root);
  fs.writeFileSync(path.join(dev.dir, 'designs.json'), 'x');
  const r = ramSidecarData(game, {}, root, { fresh: true, own: true });
  assert.notEqual(r.dir, dev.dir);
  assert.equal(removeOwned(r.dir, { link }), 'removed');
  assert.equal(fs.existsSync(r.dir), false, 'the fallback is gone');
  assert.equal(fs.realpathSync(link), fs.realpathSync(dev.dir), 'the link points at the game dir\'s dir again');
  assert.ok(fs.existsSync(path.join(link, 'designs.json')), 'the dev client sees its data again');
  assert.deepEqual(fs.readdirSync(path.join(root, 'steward')), [path.basename(dev.dir)]);
  // with STEWARD_KEEP_TMP=1 the fallback stays, marked kept
  const k = ramSidecarData(game, { STEWARD_KEEP_TMP: '1' }, root, { fresh: true, own: true, starts: DEAD });
  assert.equal(removeOwned(k.dir, { link, env: { STEWARD_KEEP_TMP: '1' } }), 'kept');
  assert.ok(fs.existsSync(k.dir));
});

test('(E2E-DISK-4) the kept bounds come from STEWARD_KEEP_TMP_HOURS and STEWARD_KEEP_TMP_CAP_GIB', () => {
  assert.deepEqual(keptBounds({}), { keptHours: 24, keptCapBytes: 8 * 1024 ** 3 });
  assert.deepEqual(keptBounds({ STEWARD_KEEP_TMP_HOURS: '6', STEWARD_KEEP_TMP_CAP_GIB: '2' }), { keptHours: 6, keptCapBytes: 2 * 1024 ** 3 });
  const bad = keptBounds({ STEWARD_KEEP_TMP_HOURS: 'x' });
  assert.equal(bad.keptHours, 24);
  assert.match(bad.warning, /STEWARD_KEEP_TMP_HOURS/);
  const src = fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'tools', 'e2e.mjs'), 'utf8');
  assert.match(src, /sweepStale\(\{ root: path\.join\(RAM_TMP, 'steward'\), \.\.\.kept/, 'e2e.mjs passes the bounds to its sweep');
});

// ------------------------------------------------------------------ E2E-DISK-5: the game dir's sidecar-<hash> isn't a directory

for (const kind of ['a plain file', 'a dangling symlink', 'a symlink to a dir']) {
  test(`(E2E-DISK-5) sidecar-<hash> is ${kind}: the run's fallback is removed, the link never dangles, and the non-dir is left as it is`, () => {
    const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-'), elsewhere = tmp('tmpown-else-');
    const link = path.join(game, 'architect', 'sidecar-data');
    const primary = ramSidecarData(game, {}, root).dir; // the game dir's sidecar-<hash>
    fs.rmdirSync(primary);
    if (kind === 'a plain file') fs.writeFileSync(primary, 'not a dir');
    else if (kind === 'a dangling symlink') fs.symlinkSync(path.join(elsewhere, 'gone'), primary);
    else { fs.writeFileSync(path.join(elsewhere, 'theirs'), '1'); fs.symlinkSync(elsewhere, primary); }
    const before = kind === 'a plain file' ? fs.readFileSync(primary, 'utf8') : fs.readlinkSync(primary);
    const r = ramSidecarData(game, {}, root, { fresh: true, own: true });
    assert.notEqual(r.dir, primary, 'a fallback of its own');
    fs.writeFileSync(path.join(r.dir, 'designs.json'), 'x');
    assert.equal(removeOwned(r.dir, { link }), 'removed');
    assert.ok(fs.existsSync(link), 'the link leads to a directory');
    assert.ok(fs.statSync(link).isDirectory());
    assert.equal(fs.readlinkSync(link), r.dir, 'it stays on the fallback, made again');
    // (E2E-TIDY-3) no marker and none of the run's data: only the link's record, so the sweep leaves it while the link points at it
    assert.deepEqual(fs.readdirSync(r.dir), [LINK_FILE], 'the run\'s data is gone, no marker left');
    assert.equal(kind === 'a plain file' ? fs.readFileSync(primary, 'utf8') : fs.readlinkSync(primary), before, 'the non-dir untouched');
    if (kind === 'a symlink to a dir') assert.deepEqual(fs.readdirSync(elsewhere), ['theirs']);
    // the next fresh run takes a fallback again and leaves the made-again one to the empty-fallback sweep
    const n = ramSidecarData(game, {}, root, { fresh: true, own: true });
    assert.notEqual(n.dir, primary);
    assert.equal(removeOwned(n.dir, { link }), 'removed');
    assert.ok(fs.statSync(link).isDirectory());
  });
}

// ------------------------------------------------------------------ E2E-TIDY-3: the sweep never deletes a live link's target

test('(E2E-TIDY-3) a fallback made again empty for a live link (sidecar-<hash> not a dir) is never swept; once the link has moved on it goes', () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const link = path.join(game, 'architect', 'sidecar-data');
  const steward = path.join(root, 'steward');
  const primary = ramSidecarData(game, {}, root).dir;
  fs.rmdirSync(primary);
  fs.writeFileSync(primary, 'not a dir');
  const r = ramSidecarData(game, {}, root, { fresh: true, own: true });
  assert.equal(removeOwned(r.dir, { link }), 'removed');
  assert.equal(fs.readlinkSync(link), r.dir);
  // past the empty-fallback bound (10 min): a plain runClient on the game dir still needs it
  backdate(r.dir, 1);
  const s1 = sweepStale({ root: steward, starts: DEAD, alive: () => false });
  assert.ok(!s1.removed.includes(path.basename(r.dir)), `swept: ${JSON.stringify(s1.swept)}`);
  assert.ok(fs.statSync(link).isDirectory(), 'the link leads to a directory');
  // the link points elsewhere now (a later run's fallback): the made-again dir is nobody's, and goes
  const other = fs.mkdtempSync(`${primary}-`);
  fs.unlinkSync(link);
  fs.symlinkSync(other, link);
  backdate(r.dir, 1);
  const s2 = sweepStale({ root: steward, starts: DEAD, alive: () => false });
  assert.ok(s2.removed.includes(path.basename(r.dir)), `kept: ${JSON.stringify(s2)}`);
  assert.equal(fs.existsSync(r.dir), false);
  assert.ok(fs.existsSync(other));
});

test('(E2E-TIDY-3) a fallback target repairSidecarData makes again (a reboot wiped it) is never swept while the link points at it', () => {
  const game = tmp('tmpown-game-'), root = tmp('tmpown-ram-');
  const link = path.join(game, 'architect', 'sidecar-data');
  const steward = path.join(root, 'steward');
  fs.mkdirSync(steward, { recursive: true });
  fs.mkdirSync(path.dirname(link), { recursive: true });
  const fb = path.join(steward, 'sidecar-1a2b-Ab12Cd');
  fs.symlinkSync(fb, link); // dangling: the RAM disk was wiped
  assert.equal(repairSidecarData(game, {}, root), 'relinked');
  backdate(fb, 1);
  sweepStale({ root: steward, starts: DEAD, alive: () => false });
  assert.ok(fs.existsSync(fb), 'the link\'s live target stays');
  assert.ok(fs.statSync(link).isDirectory());
  // the link gone (the game dir removed): the dir goes
  fs.unlinkSync(link);
  backdate(fb, 1);
  const s = sweepStale({ root: steward, starts: DEAD, alive: () => false });
  assert.deepEqual(s.removed, ['sidecar-1a2b-Ab12Cd']);
});
