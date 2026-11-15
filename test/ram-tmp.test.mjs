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
