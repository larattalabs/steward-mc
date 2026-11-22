import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { useRamTmp } from '../tools/lib/ram-tmp.mjs';

test('TMPDIR moves to the RAM disk when it exists', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ramtmp-'));
  const env = { TMPDIR: '/old/' };
  assert.equal(useRamTmp(env, root), path.join(root, 'steward'));
  assert.equal(env.TMPDIR, path.join(root, 'steward') + '/');
  assert.ok(fs.statSync(path.join(root, 'steward')).isDirectory());
});

test('TMPDIR stays when the volume is missing or turned off', () => {
  const env = { TMPDIR: '/old/' };
  assert.equal(useRamTmp(env, '/nonexistent/MCTmp/tmp'), null);
  assert.equal(env.TMPDIR, '/old/');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ramtmp-'));
  const off = { TMPDIR: '/old/', STEWARD_RAM_TMP: '0' };
  assert.equal(useRamTmp(off, root), null);
  assert.equal(off.TMPDIR, '/old/');
});

import { ramSidecarData } from '../tools/lib/ram-tmp.mjs';

const tmpRoot = () => fs.mkdtempSync(path.join(os.tmpdir(), 'ramsd-'));

test('sidecar-data becomes a link to the RAM disk; an old directory is kept, not deleted', () => {
  const game = tmpRoot(), root = tmpRoot();
  const old = path.join(game, 'architect', 'sidecar-data');
  fs.mkdirSync(old, { recursive: true });
  fs.writeFileSync(path.join(old, 'state.json'), '{}');
  const r = ramSidecarData(game, {}, root);
  assert.equal(r.mode, 'ram');
  assert.ok(fs.lstatSync(old).isSymbolicLink());
  assert.equal(fs.realpathSync(old), fs.realpathSync(r.dir));
  assert.ok(fs.existsSync(path.join(r.moved, 'state.json')));
  // again: the same link, nothing moved
  const again = ramSidecarData(game, {}, root);
  assert.equal(again.dir, r.dir);
  assert.equal(again.moved, undefined);
});

test('without the RAM disk a dangling link becomes a directory; a real one is left', () => {
  const game = tmpRoot();
  const link = path.join(game, 'architect', 'sidecar-data');
  fs.mkdirSync(path.dirname(link), { recursive: true });
  fs.symlinkSync('/nonexistent/MCTmp/tmp/steward/sidecar-x', link);
  assert.equal(ramSidecarData(game, {}, '/nonexistent/MCTmp/tmp').mode, 'disk');
  assert.ok(fs.lstatSync(link).isDirectory());
  fs.writeFileSync(path.join(link, 'keep'), '1');
  ramSidecarData(game, { STEWARD_RAM_TMP: '0' }, tmpRoot());
  assert.ok(fs.existsSync(path.join(link, 'keep')));
});

test('a fresh e2e run starts the RAM-disk sidecar data empty', () => {
  const game = fs.mkdtempSync(path.join(os.tmpdir(), 'ramsd-')), root = fs.mkdtempSync(path.join(os.tmpdir(), 'ramsd-'));
  const r = ramSidecarData(game, {}, root);
  fs.writeFileSync(path.join(r.dir, 'designs.json'), 'x');
  ramSidecarData(game, {}, root);
  assert.ok(fs.existsSync(path.join(r.dir, 'designs.json')), 'kept without fresh');
  // (E2E-DISK-3) unmarked data (a dev client's) is never emptied: a fresh run starts empty in a dir of its own beside it
  const f = ramSidecarData(game, {}, root, { fresh: true });
  assert.ok(fs.existsSync(path.join(r.dir, 'designs.json')), 'unmarked data left as it is');
  assert.notEqual(f.dir, r.dir);
  assert.deepEqual(fs.readdirSync(f.dir), [], 'the fresh dir is empty');
  assert.ok(fs.lstatSync(path.join(game, 'architect', 'sidecar-data')).isSymbolicLink());
  assert.equal(fs.realpathSync(path.join(game, 'architect', 'sidecar-data')), fs.realpathSync(f.dir));
});

import { repairSidecarData } from '../tools/lib/ram-tmp.mjs';

// slice LINK-SIDECAR: the module tools' repair of a sidecar-data link left dangling by a reboot
const sidecarLink = (game) => { const l = path.join(game, 'architect', 'sidecar-data'); fs.mkdirSync(path.dirname(l), { recursive: true }); return l; };

test('a dangling sidecar-data link is replaced by an empty directory when the RAM disk is down', () => {
  const game = tmpRoot(), link = sidecarLink(game);
  fs.symlinkSync('/nonexistent/MCTmp/tmp/steward/sidecar-x', link);
  assert.equal(repairSidecarData(game, {}, '/nonexistent/MCTmp/tmp'), 'replaced');
  assert.ok(!fs.lstatSync(link).isSymbolicLink());
  assert.ok(fs.lstatSync(link).isDirectory());
  assert.deepEqual(fs.readdirSync(link), []);
});

test('a dangling sidecar-data link into the RAM disk gets its target back when the volume is up', () => {
  const game = tmpRoot(), root = tmpRoot(), link = sidecarLink(game);
  const target = path.join(root, 'steward', 'sidecar-gone');
  fs.symlinkSync(target, link);
  assert.equal(repairSidecarData(game, {}, root), 'relinked');
  assert.ok(fs.lstatSync(link).isSymbolicLink());
  assert.ok(fs.statSync(target).isDirectory());
  // a link that points outside root is replaced, even with the volume up; so is any link with STEWARD_RAM_TMP=0
  const game2 = tmpRoot(), link2 = sidecarLink(game2);
  fs.symlinkSync('/nonexistent/elsewhere/sidecar', link2);
  assert.equal(repairSidecarData(game2, {}, root), 'replaced');
  assert.ok(fs.lstatSync(link2).isDirectory());
  const game3 = tmpRoot(), link3 = sidecarLink(game3);
  fs.symlinkSync(path.join(root, 'steward', 'sidecar-off'), link3);
  assert.equal(repairSidecarData(game3, { STEWARD_RAM_TMP: '0' }, root), 'replaced');
  assert.ok(!fs.existsSync(path.join(root, 'steward', 'sidecar-off')));
});

test('a live sidecar-data link, a real directory and a missing path are left alone', () => {
  const root = tmpRoot();
  const live = tmpRoot(), liveLink = sidecarLink(live), target = tmpRoot();
  fs.writeFileSync(path.join(target, 'jobs.json'), '[]');
  fs.symlinkSync(target, liveLink);
  assert.equal(repairSidecarData(live, {}, root), null);
  assert.equal(fs.readlinkSync(liveLink), target);
  assert.ok(fs.existsSync(path.join(liveLink, 'jobs.json')));
  const real = tmpRoot(), realDir = sidecarLink(real);
  fs.mkdirSync(realDir);
  fs.writeFileSync(path.join(realDir, 'keep'), '1');
  assert.equal(repairSidecarData(real, {}, '/nonexistent/MCTmp/tmp'), null);
  assert.ok(fs.existsSync(path.join(realDir, 'keep')));
  const none = tmpRoot();
  assert.equal(repairSidecarData(none, {}, root), null);
  assert.ok(!fs.existsSync(path.join(none, 'architect')));
});

test('(E2E-DISK-5) a link to a target that is there but not a directory is replaced by an empty directory; the target is left', () => {
  const root = tmpRoot();
  fs.mkdirSync(path.join(root, 'steward'));
  // a dangling link to a dangling symlink under root (mkdir -p on it throws ENOENT)
  const g1 = tmpRoot(), l1 = sidecarLink(g1), t1 = path.join(root, 'steward', 'sidecar-dl');
  fs.symlinkSync(path.join(root, 'nowhere'), t1);
  fs.symlinkSync(t1, l1);
  assert.equal(repairSidecarData(g1, {}, root), 'replaced');
  assert.ok(fs.lstatSync(l1).isDirectory() && !fs.lstatSync(l1).isSymbolicLink());
  assert.equal(fs.readlinkSync(t1), path.join(root, 'nowhere'), 'the symlink left');
  // a link to a plain file under root (mkdir -p on it throws EEXIST)
  const g2 = tmpRoot(), l2 = sidecarLink(g2), t2 = path.join(root, 'steward', 'sidecar-file');
  fs.writeFileSync(t2, 'theirs');
  fs.symlinkSync(t2, l2);
  assert.equal(repairSidecarData(g2, {}, root), 'replaced');
  assert.ok(fs.lstatSync(l2).isDirectory());
  assert.equal(fs.readFileSync(t2, 'utf8'), 'theirs', 'the file left');
  // with the volume down, ramSidecarData repairs a link to a file the same way
  const g3 = tmpRoot(), l3 = sidecarLink(g3);
  fs.symlinkSync(t2, l3);
  assert.equal(ramSidecarData(g3, {}, '/nonexistent/MCTmp/tmp').mode, 'disk');
  assert.ok(fs.lstatSync(l3).isDirectory());
  // a link through a symlink to a real directory is live: left
  const g4 = tmpRoot(), l4 = sidecarLink(g4), real = tmpRoot(), t4 = path.join(root, 'steward', 'sidecar-chain');
  fs.symlinkSync(real, t4);
  fs.symlinkSync(t4, l4);
  assert.equal(repairSidecarData(g4, {}, root), null);
  assert.equal(fs.readlinkSync(l4), t4);
});
