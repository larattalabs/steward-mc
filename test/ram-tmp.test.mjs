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
  ramSidecarData(game, {}, root, { fresh: true });
  assert.ok(!fs.existsSync(path.join(r.dir, 'designs.json')), 'emptied when fresh');
  assert.ok(fs.lstatSync(path.join(game, 'architect', 'sidecar-data')).isSymbolicLink());
});
