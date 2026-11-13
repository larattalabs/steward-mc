// tools/lib/style-bounds.mjs: the crop farm's bound per Style and its role parts, recorded only when the lot's quote is the template's bill
// (docs/MEASURE-M2.md "For M3"). Runs on the real 9x9 wheat quotes of the 0.64.1 bounds step, with the kit Architect v0.65.0 ships.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { styleBounds } from '../tools/lib/style-bounds.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAIN = path.resolve(execFileSync('git', ['-C', ROOT, 'rev-parse', '--git-common-dir']).toString().trim(), '..');
const ARCH = process.env.ARCHITECT_CHECKOUT ?? path.resolve(MAIN, '..', 'architect-mc');
const quotes = () => JSON.parse(fs.readFileSync(path.join(ROOT, 'test', 'fixtures', 'style-bounds-quotes.json'), 'utf8')).quotes;
const source = fs.readFileSync(path.join(ROOT, 'modules', 'crop_farm', '1', 'program.mjs'), 'utf8');

let kit = null;
function pinnedKit() {
  if (kit) return kit;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'steward-kit-'));
  execFileSync('tar', ['-x', '-C', dir], { input: execFileSync('git', ['-C', ARCH, 'archive', 'v0.65.0', 'kit'], { maxBuffer: 1 << 30 }) });
  kit = path.join(dir, 'kit');
  return kit;
}
const haveArch = fs.existsSync(path.join(ARCH, '.git'));
const lot = (q) => q.bomParts[Object.keys(q.bomParts).find((k) => k.startsWith('lot:'))];

test('each Style records its bill and the share each role writes, counted and matching the quote', { skip: !haveArch && 'no architect-mc checkout' }, async () => {
  const r = await styleBounds({ quotes: quotes(), kit: pinnedKit(), source });
  assert.deepEqual(r.problems, []);
  assert.equal(r.bounds.length, 3);
  const rustic = r.bounds.find((b) => b.style === 'rustic');
  const part = (role) => rustic.roleParts.find((p) => p.role === role);
  assert.deepEqual(part('path'), { role: 'path', block: 'minecraft:dirt_path', item: 'minecraft:dirt', count: 12 });
  assert.equal(part('foundation').count, 41);
  assert.equal(part('light').count, 7, 'the lot\'s lamps: the street\'s two lanterns are not a role\'s');
  assert.equal(part('wood.fence').item, 'minecraft:spruce_fence');
  assert.equal(r.bounds.find((b) => b.style === 'desert').roleParts.find((p) => p.role === 'wood.fence').item, 'minecraft:jungle_fence');
});

test('a quote whose lot is not the template\'s bill is refused (the split would be a guess)', { skip: !haveArch && 'no architect-mc checkout' }, async () => {
  for (const [item, by] of [['minecraft:dirt', -12], ['minecraft:dirt', 1], ['minecraft:cobblestone', 1]]) {
    const q = quotes();
    const l = lot(q.find((x) => x.bible === 'rustic'));
    l[item] += by;
    const r = await styleBounds({ quotes: q, kit: pinnedKit(), source });
    assert.ok(r.problems.some((m) => /the lot's quote is not the template's bill/.test(m)), `${item} ${by}: ${r.problems}`);
  }
  const q = quotes();
  lot(q[0])['minecraft:torch'] = 1;
  assert.match((await styleBounds({ quotes: q, kit: pinnedKit(), source })).problems[0], /minecraft:torch template 0, quoted 1/);
});
