// (E2E-DISK-5) E2E-DISK-4's kept-dir bounds and fallback cleanup with real owners: the unit tests judge a dead owner through stubs (`starts`, `alive`);
// here every owner is a real process (a live child, a child that has exited, a driver SIGKILLed) and the sweep runs with its defaults (ps, kill 0) and
// keptBounds() from the environment, on a temp root of its own (never /Volumes/MCTmp's live dirs).
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { sweepStale, markOwner, readOwner, dirBytes, keptBounds, OWNER_FILE } from '../tools/lib/tmp-owner.mjs';
import { ramSidecarData } from '../tools/lib/ram-tmp.mjs';

const LIB = pathToFileURL(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'tools', 'lib')).href;
const made = [];
const tmp = (p) => { const d = fs.mkdtempSync(path.join(os.tmpdir(), p)); made.push(d); return d; };
after(() => { for (const d of made) fs.rmSync(d, { recursive: true, force: true }); });

/** A node child that prints its pid and Node timeOrigin (what a driver writes in its marker); `stay` keeps it running. */
function child(stay) {
  const c = spawn(process.execPath, ['-e', `console.log(process.pid + ' ' + Math.round(performance.timeOrigin));${stay ? ' setInterval(() => {}, 1000);' : ''}`],
    { stdio: ['ignore', 'pipe', 'inherit'] });
  let out = '';
  const owner = new Promise((resolve) => c.stdout.on('data', (d) => {
    out += d;
    const m = /^(\d+) (\d+)\n/.exec(out);
    if (m) resolve({ pid: Number(m[1]), startMs: Number(m[2]) });
  }));
  const exited = new Promise((resolve) => c.on('exit', resolve));
  return { c, owner, exited };
}
/** A real owner that has exited: its pid no longer runs (or runs as another process, started later). */
async function deadOwner() {
  const k = child(false);
  const o = await k.owner;
  await k.exited;
  return o;
}

const HOUR = 3_600_000;
function backdate(dir, ms) {
  const t = new Date(Date.now() - ms);
  for (const n of fs.readdirSync(dir)) fs.utimesSync(path.join(dir, n), t, t);
  fs.utimesSync(dir, t, t);
}
function dir(root, name, { kb = 0, owner = null, kept = false, ageMs = 0 } = {}) {
  const p = path.join(root, name);
  fs.mkdirSync(p);
  if (kb) fs.writeFileSync(path.join(p, 'data.bin'), Buffer.alloc(kb * 1024, 1));
  if (owner) markOwner(p, kept ? { kept: true } : {}, owner);
  if (ageMs) backdate(p, ageMs);
  return p;
}

test('(E2E-DISK-5) with real owners and the default ps/kill checks, the sweep takes dead owners\' dirs by its bounds and never a live one', async () => {
  const root = tmp('tmpreal-ram-');
  const live = child(true);
  try {
    const L = await live.owner;
    const D = await deadOwner();
    const plain = dir(root, 'sidecar-dead', { kb: 8, owner: D });
    const held = dir(root, 'sidecar-live', { kb: 8, owner: L });
    const keptOld = dir(root, 'sidecar-keptold', { kb: 8, owner: D, kept: true, ageMs: 25 * HOUR });
    const keptYoung = dir(root, 'sidecar-keptyoung', { kb: 8, owner: D, kept: true, ageMs: 1 * HOUR });
    const keptLiveOld = dir(root, 'sidecar-keptliveold', { kb: 8, owner: L, kept: true, ageMs: 48 * HOUR });
    const emptyFallback = dir(root, 'sidecar-1a2b3c-Ab12Cd', { ageMs: 11 * 60_000 });
    const youngFallback = dir(root, 'sidecar-1a2b3c-Ef34Gh', { ageMs: 2 * 60_000 });
    const fullFallback = dir(root, 'sidecar-1a2b3c-Ij56Kl', { kb: 1, ageMs: 11 * 60_000 });
    const unmarked = dir(root, 'sidecar-devdata', { kb: 1, ageMs: 48 * HOUR });
    const b = keptBounds({});
    const r = sweepStale({ root, ...b });
    assert.deepEqual(r.removed.sort(), ['sidecar-1a2b3c-Ab12Cd', 'sidecar-dead', 'sidecar-keptold']);
    for (const p of [plain, keptOld, emptyFallback]) assert.equal(fs.existsSync(p), false, p);
    for (const p of [held, keptYoung, keptLiveOld, youngFallback, fullFallback, unmarked]) assert.ok(fs.existsSync(p), p);
    assert.match(r.swept.find((s) => s.name === 'sidecar-keptold').why, /untouched 25 h \(bound 24 h\)/);
    assert.equal(r.overCap, false);
    assert.equal(r.failed.length, 0);
  } finally {
    live.c.kill('SIGKILL');
  }
});

test('(E2E-DISK-5) past STEWARD_KEEP_TMP_CAP_GIB the dead owners\' kept dirs go oldest first; a live owner\'s counts but stays', async () => {
  const root = tmp('tmpreal-ram-');
  const live = child(true);
  try {
    const L = await live.owner;
    const D = await deadOwner();
    const big = dir(root, 'sidecar-livebig', { kb: 512, owner: L, kept: true, ageMs: 30 * HOUR });
    const a = dir(root, 'sidecar-k1', { kb: 128, owner: D, kept: true, ageMs: 3 * HOUR });
    const b = dir(root, 'sidecar-k2', { kb: 128, owner: D, kept: true, ageMs: 2 * HOUR });
    const c = dir(root, 'sidecar-k3', { kb: 128, owner: D, kept: true, ageMs: 1 * HOUR });
    const capBytes = dirBytes(big) + dirBytes(c) + 1024;
    const bounds = keptBounds({ STEWARD_KEEP_TMP_CAP_GIB: String(capBytes / 1024 ** 3) });
    assert.equal(bounds.warning, undefined);
    const r = sweepStale({ root, ...bounds });
    assert.deepEqual(r.removed, ['sidecar-k1', 'sidecar-k2']);
    for (const s of r.swept) assert.match(s.why, /the oldest while kept dirs total .* GiB \(cap/);
    assert.equal(fs.existsSync(a), false);
    assert.equal(fs.existsSync(b), false);
    assert.ok(fs.existsSync(c));
    assert.ok(fs.existsSync(path.join(big, 'data.bin')));
    assert.equal(r.overCap, false);
    // a cap the live dir alone passes: nothing of the live owner's is taken, and it is said
    const r2 = sweepStale({ root, ...keptBounds({ STEWARD_KEEP_TMP_CAP_GIB: String((dirBytes(big) - 1) / 1024 ** 3) }) });
    assert.deepEqual(r2.removed, ['sidecar-k3']);
    assert.ok(fs.existsSync(path.join(big, 'data.bin')));
    assert.equal(r2.overCap, true);
  } finally {
    live.c.kill('SIGKILL');
  }
});

/** A driver as e2e.mjs makes its sidecar dir (ramSidecarData fresh, own) on root; prints `ready <dir>`, then ends or waits as `how` says. */
function driver(how, { game, root, env = {} }) {
  const file = path.join(tmp('tmpreal-drv-'), 'driver.mjs');
  fs.writeFileSync(file, `
import fs from 'node:fs';
import path from 'node:path';
import { ramSidecarData } from '${LIB}/ram-tmp.mjs';
const sd = ramSidecarData(${JSON.stringify(game)}, process.env, ${JSON.stringify(root)}, { fresh: true, own: true });
fs.writeFileSync(path.join(sd.dir, 'plans.json'), '{}');
console.log('ready ' + sd.dir);
if (process.argv[2] === 'wait') setInterval(() => {}, 1000);
`);
  const c = spawn(process.execPath, [file, how], { stdio: ['ignore', 'pipe', 'inherit'], env: { ...process.env, ...env } });
  let out = '';
  const ready = new Promise((resolve) => c.stdout.on('data', (d) => { out += d; const m = /ready (.+)\n/.exec(out); if (m) resolve(m[1]); }));
  const done = new Promise((resolve) => c.on('close', (code, signal) => resolve({ code, signal })));
  return { c, ready, done };
}

test('(E2E-DISK-5) a real driver on a game dir holding dev data runs in a fallback, removes it at its end and points the link back', async () => {
  const game = tmp('tmpreal-game-'), root = tmp('tmpreal-ram-');
  const link = path.join(game, 'architect', 'sidecar-data');
  const dev = ramSidecarData(game, {}, root);
  fs.writeFileSync(path.join(dev.dir, 'designs.json'), 'dev');
  const d = driver('end', { game, root });
  const fb = await d.ready;
  assert.notEqual(fb, dev.dir);
  assert.equal((await d.done).code, 0);
  assert.equal(fs.existsSync(fb), false, 'the fallback is gone');
  assert.equal(fs.readlinkSync(link), dev.dir, 'the link is back on the game dir\'s dir');
  assert.equal(fs.readFileSync(path.join(link, 'designs.json'), 'utf8'), 'dev');
  assert.deepEqual(fs.readdirSync(path.join(root, 'steward')), [path.basename(dev.dir)], 'nothing piles up');
});

test('(E2E-DISK-5) a SIGKILLed STEWARD_KEEP_TMP driver leaves a kept dir: the real sweep keeps it while young, takes it past the age bound', async () => {
  const game = tmp('tmpreal-game-'), root = tmp('tmpreal-ram-');
  const d = driver('wait', { game, root, env: { STEWARD_KEEP_TMP: '1' } });
  const kd = await d.ready;
  const o = readOwner(kd);
  assert.equal(o.kept, true);
  assert.equal(o.pid, d.c.pid);
  // while it runs, even past every bound, it is held
  backdate(kd, 30 * HOUR);
  assert.deepEqual(sweepStale({ root: path.join(root, 'steward'), ...keptBounds({}) }).removed, []);
  d.c.kill('SIGKILL');
  await d.done;
  fs.utimesSync(kd, new Date(), new Date());
  fs.utimesSync(path.join(kd, OWNER_FILE), new Date(), new Date());
  const young = sweepStale({ root: path.join(root, 'steward'), ...keptBounds({}) });
  assert.deepEqual(young.removed, [], 'its owner is gone, but it is young');
  assert.ok(fs.existsSync(path.join(kd, 'plans.json')));
  backdate(kd, 25 * HOUR);
  const old = sweepStale({ root: path.join(root, 'steward'), ...keptBounds({ STEWARD_KEEP_TMP_HOURS: '24' }) });
  assert.deepEqual(old.removed, [path.basename(kd)]);
  assert.equal(fs.existsSync(kd), false);
});
