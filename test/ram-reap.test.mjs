// (E2E-SPEED-2 item 5) A run's own sidecar data on the RAM disk is emptied once its driver is gone, by whoever outlives it (a --clients lead after
// its child, the nightly after a step), and only after proving it is that run's: never another worktree's dir (REVIEW-PATTERNS D15/D16).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { reapSidecar, copyEvidence } from '../tools/lib/ram-tmp.mjs';

const DEAD = 999_999; // a pid no process has
const mk = () => fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'reap-')));

/** A game dir whose sidecar-data links to <root>/steward/<name>, marked by `owner` (null: no marker), holding 1 MB of data. */
function setup({ name = 'sidecar-abc123', owner = { pid: DEAD, startMs: 1 }, gameFor = null } = {}) {
  const root = mk(), base = mk();
  const game = path.join(base, 'mod', 'run');
  const dir = path.join(root, 'steward', name);
  fs.mkdirSync(path.join(dir, 'jobs'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'jobs', 'big.bin'), Buffer.alloc(1 << 20));
  if (owner) fs.writeFileSync(path.join(dir, '.arch-owner'), JSON.stringify({ ...owner, createdAt: 'x', tool: 'steward-e2e', gameDir: gameFor ?? game }));
  fs.mkdirSync(path.join(game, 'architect'), { recursive: true });
  fs.symlinkSync(dir, path.join(game, 'architect', 'sidecar-data'));
  return { root, game, dir };
}
const opts = (root, extra = {}) => ({ root, jvmOn: () => null, gone: () => true, ...extra });
const emptied = (dir) => fs.existsSync(dir) && fs.readdirSync(dir).length === 0;
const intact = (dir) => fs.existsSync(path.join(dir, 'jobs', 'big.bin'));

test('its own dead run\'s dir is emptied; the link still points at it', () => {
  const { root, game, dir } = setup();
  const r = reapSidecar(game, opts(root));
  assert.equal(r.outcome, 'emptied', r.why);
  assert.ok(emptied(dir));
  assert.ok(fs.statSync(path.join(game, 'architect', 'sidecar-data')).isDirectory());
});

test('the pid must match when it is known', () => {
  const { root, game, dir } = setup();
  assert.equal(reapSidecar(game, opts(root, { pid: 4242 })).outcome, 'not-mine');
  assert.ok(intact(dir));
  assert.equal(reapSidecar(game, opts(root, { pid: DEAD })).outcome, 'emptied');
});

test('a foreign dir is never removed: another worktree\'s game dir in the marker', () => {
  const { root, game, dir } = setup({ gameFor: '/elsewhere/steward-other/mod/run' });
  const r = reapSidecar(game, opts(root));
  assert.equal(r.outcome, 'not-mine');
  assert.ok(intact(dir));
});

test('no marker, a kept marker, a live owner or a live client JVM: left as it is', () => {
  for (const [s, extra, want] of [
    [{ owner: null }, {}, 'not-mine'],
    [{ owner: { pid: DEAD, startMs: 1, kept: true } }, {}, 'kept'],
    [{}, { gone: () => false }, 'live'],
    [{}, { jvmOn: () => 777 }, 'live'],
  ]) {
    const { root, game, dir } = setup(s);
    assert.equal(reapSidecar(game, opts(root, extra)).outcome, want);
    assert.ok(intact(dir), want);
  }
});

test('STEWARD_KEEP_TMP=1 keeps everything', () => {
  const { root, game, dir } = setup();
  assert.equal(reapSidecar(game, opts(root, { env: { STEWARD_KEEP_TMP: '1' } })).outcome, 'kept');
  assert.ok(intact(dir));
});

test('a target outside <root>/steward, nested under it, or not named sidecar-*: never touched', () => {
  const root = mk(), base = mk(), out = mk();
  const cases = [
    path.join(out, 'sidecar-abc'), // outside the root
    path.join(root, 'steward', 'sidecar-abc', 'inner'), // nested
    path.join(root, 'steward', 'sidecar-abc', 'sidecar-def'), // nested, named like a sidecar dir
    path.join(root, 'steward', 'node-compile-cache'), // not a sidecar dir
  ];
  for (const [i, dir] of cases.entries()) {
    const game = path.join(base, `g${i}`, 'mod', 'run');
    fs.mkdirSync(path.join(dir, 'jobs'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'jobs', 'big.bin'), 'x');
    fs.writeFileSync(path.join(dir, '.arch-owner'), JSON.stringify({ pid: DEAD, startMs: 1, gameDir: game }));
    fs.mkdirSync(path.join(game, 'architect'), { recursive: true });
    fs.symlinkSync(dir, path.join(game, 'architect', 'sidecar-data'));
    assert.equal(reapSidecar(game, opts(root)).outcome, 'refused', dir);
    assert.ok(intact(dir), dir);
  }
});

test('a symlinked target (the link\'s target is itself a link) is refused, its real dir untouched', () => {
  const { root, game, dir } = setup();
  const alias = path.join(root, 'steward', 'sidecar-alias1');
  fs.symlinkSync(dir, alias);
  const link = path.join(game, 'architect', 'sidecar-data');
  fs.unlinkSync(link);
  fs.symlinkSync(alias, link);
  assert.equal(reapSidecar(game, opts(root)).outcome, 'refused');
  assert.ok(intact(dir));
});

test('a real sidecar-data directory (on disk) or no link: nothing to do', () => {
  const base = mk(), root = mk();
  const game = path.join(base, 'mod', 'run');
  assert.equal(reapSidecar(game, opts(root)).outcome, 'none');
  fs.mkdirSync(path.join(game, 'architect', 'sidecar-data'), { recursive: true });
  fs.writeFileSync(path.join(game, 'architect', 'sidecar-data', 'x'), 'x');
  assert.equal(reapSidecar(game, opts(root)).outcome, 'none');
  assert.ok(fs.existsSync(path.join(game, 'architect', 'sidecar-data', 'x')));
});

test('evidence: small logs and JSON copied (regular files only, capped), never tokens, node_modules or links', () => {
  const src = mk(), dest = path.join(mk(), 'evidence');
  fs.mkdirSync(path.join(src, 'logs'), { recursive: true });
  fs.mkdirSync(path.join(src, 'jobs', 'j1'), { recursive: true });
  fs.mkdirSync(path.join(src, 'programs', 'node_modules', 'x'), { recursive: true });
  fs.writeFileSync(path.join(src, 'logs', 'sidecar.log'), 'log');
  fs.writeFileSync(path.join(src, 'jobs', 'j1', 'job.json'), '{}');
  fs.writeFileSync(path.join(src, 'jobs', 'j1', 'huge.json'), Buffer.alloc(3 << 20));
  fs.writeFileSync(path.join(src, 'pins.json'), '{}');
  fs.writeFileSync(path.join(src, 'client.token'), 'secret');
  fs.writeFileSync(path.join(src, 'secrets.json'), '{"k":1}');
  fs.writeFileSync(path.join(src, 'programs', 'node_modules', 'x', 'package.json'), '{}');
  fs.writeFileSync(path.join(src, 'blob.bin'), 'b');
  fs.symlinkSync('/etc/hosts', path.join(src, 'logs', 'link.log'));
  const r = copyEvidence(src, dest);
  const got = [];
  const walk = (d, rel = '') => { for (const n of fs.readdirSync(d)) { const p = path.join(d, n); if (fs.lstatSync(p).isDirectory()) walk(p, path.join(rel, n)); else got.push(path.join(rel, n)); } };
  walk(dest);
  assert.deepEqual(got.sort(), ['jobs/j1/job.json', 'logs/sidecar.log', 'pins.json']);
  assert.equal(r.files, 3);
});

test('a failed run\'s evidence goes next to its kept world before the dir is emptied', () => {
  const { root, game, dir } = setup();
  fs.mkdirSync(path.join(dir, 'logs'));
  fs.writeFileSync(path.join(dir, 'logs', 'sidecar.log'), 'why it failed');
  const world = path.join(game, 'saves', 'Steward E2E t1');
  fs.mkdirSync(world, { recursive: true });
  const r = reapSidecar(game, opts(root, { evidenceTo: path.join(world, 'steward-sidecar-evidence') }));
  assert.equal(r.outcome, 'emptied');
  assert.equal(fs.readFileSync(path.join(world, 'steward-sidecar-evidence', 'logs', 'sidecar.log'), 'utf8'), 'why it failed');
  assert.ok(emptied(dir));
});
