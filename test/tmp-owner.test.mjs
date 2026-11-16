// Slice TMPSWEEP: an e2e run's own sidecar dir goes on every way out of the driver, survives its client relaunches, and the start-of-run sweep
// prunes only marked dirs whose owner is gone.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { sweepStale, markOwner, readOwner, removeOwned, own, ownedDirs, OWNER_FILE, PROCESS_START_MS } from '../tools/lib/tmp-owner.mjs';
import { ramSidecarData } from '../tools/lib/ram-tmp.mjs';

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
    if (how === 'slotlike') assert.equal(r.code, 130);
    else assert.equal(r.signal, sig);
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
  const r = sweepStale({ root, starts, now });
  assert.deepEqual(r.removed.sort(), ['sidecar-dead', 'sidecar-reused']);
  assert.deepEqual(r.kept, ['sidecar-live']);
  assert.equal(fs.existsSync(dead), false);
  assert.equal(fs.existsSync(reused), false);
  for (const p of [live, unmarked, other]) assert.ok(fs.existsSync(p), p);
  assert.ok(fs.lstatSync(path.join(root, 'sidecar-link')).isSymbolicLink());
  // an age floor keeps a recently touched dead-owner dir
  const young = mk('sidecar-young', { pid: 4242, startMs: now - 10_000 });
  assert.deepEqual(sweepStale({ root, starts, now, hours: 1 }).removed, []);
  assert.ok(fs.existsSync(young));
  // a missing root is nothing to do
  assert.deepEqual(sweepStale({ root: path.join(root, 'nope'), starts }).removed, []);
});
