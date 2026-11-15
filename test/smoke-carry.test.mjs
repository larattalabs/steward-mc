// tools/lib/smoke-carry.mjs: speed rule 4's carry (module-catalog.mjs record --smoke), on crop_farm@1's real full certificate.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { canonSha, moduleShaOf, smokeCarry } from '../tools/lib/smoke-carry.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DIR = path.join(ROOT, 'modules', 'crop_farm', '1');
const clone = (o) => JSON.parse(JSON.stringify(o));
const live = JSON.parse(fs.readFileSync(path.join(DIR, 'measured.json'), 'utf8'));
const liveMod = JSON.parse(fs.readFileSync(path.join(DIR, 'module.json'), 'utf8'));
const NOW = { programSha: liveMod.programSha, moduleJsonSha: 'b'.repeat(64) };

/** The full certificate: the live one if it is full, else the one it carries, made whole again. */
function fullCert() {
  if (live.basis !== 'smoke') return clone(live);
  return { ...clone(live.carried), bounds: clone(live.bounds), rates: clone(live.rates) };
}
const FULL = fullCert();
const FROM = FULL.checks.offline.architect;
const NEXT = '9.0.0';

/** The inputs of a passing carry of `prev` to Architect `arch` with kit pins `kit`. */
function inputs({ prev = FULL, arch = NEXT, kit = '9.9.0' } = {}) {
  const mod = { ...clone(liveMod), pins: { kit, checker: kit } };
  const versions = { architect: arch, minecraft: '26.3' };
  const ev = (step, extra = {}) => ({ step, ok: true, at: 't', head: 'h', seconds: 1, ...NOW, ...versions, checks: [{ ok: true, what: 'x' }], ...extra });
  const field = ev('field-potatoes', { runs: 5, data: { crop: 'potatoes', farms: [{ id: 'f1', size: '9x18' }], judged: { f1: { mean: 0.6, n: 160 } } } });
  return {
    prev: clone(prev), mod, now: NOW, gameModuleSha: moduleShaOf(mod), versions,
    offline: { ok: true, data: { architect: arch, kit, checker: kit } }, offEntry: { branches: clone(FULL.checks.offline.branches) },
    ingame: { ok: true }, inEntry: {}, checks: { offline: {}, ingame: {} }, survival: ev('survival'), fields: [field],
    requote: { evidence: ev('bounds'), bounds: clone(FULL.bounds).reverse(), problems: [] },
  };
}

test('the canonical moduleSha is the game\'s: the fully measured module.json (its pins put back) hashes to the certificate\'s moduleSha', () => {
  assert.equal(moduleShaOf({ ...liveMod, pins: { kit: FULL.kit, checker: FULL.checker } }), FULL.moduleSha);
  assert.equal(moduleShaOf(liveMod), live.moduleSha, 'the committed certificate is for the committed module.json');
});

test('a plain pin bump with every smoke step passing carries the full measurement', () => {
  const { why, m } = smokeCarry(inputs());
  assert.deepEqual(why, []);
  assert.equal(m.basis, 'smoke');
  assert.equal(m.from, FROM);
  assert.deepEqual(m.architect, [NEXT]);
  assert.equal(m.kit, '9.9.0');
  assert.equal(m.result, 'pass');
  assert.deepEqual(m.bounds, FULL.bounds, 'the bounds offered are the measured ones');
  assert.deepEqual(m.rates, FULL.rates);
  assert.equal(m.carried.boundsSha, canonSha(FULL.bounds));
  assert.equal(m.carried.ratesSha, canonSha(FULL.rates));
  assert.equal(m.carried.moduleSha, FULL.moduleSha);
  assert.deepEqual(m.carried.checks.measure, FULL.checks.measure, 'the full measurement is kept');
  assert.equal(m.carried.bounds, undefined, 'carried holds no second copy of the bounds');
  const r = m.checks.smoke.fields[0].rates[0];
  assert.deepEqual(r.params, { crop: 'potatoes', depth: 18, width: 9 });
  assert.equal(typeof r.changePct, 'number');
  const s = m.checks.smoke;
  for (const x of [s.survival, s.bounds, s.fields[0]]) assert.deepEqual([x.programSha, x.architect, x.minecraft, x.passed === x.of], [NOW.programSha, NEXT, '26.3', true]);
  assert.equal(s.bounds.equal, FULL.bounds.length);
});

test('a smoke of a smoke keeps the first full measurement and its from', () => {
  const first = smokeCarry(inputs()).m;
  const { why, m } = smokeCarry(inputs({ prev: first, arch: '9.1.0', kit: '9.9.1' }));
  assert.deepEqual(why, []);
  assert.equal(m.from, FROM);
  assert.deepEqual(m.carried, first.carried);
  assert.deepEqual(m.architect, ['9.1.0']);
});

const refused = (a, re) => {
  const { why, m } = smokeCarry(a);
  assert.equal(m, null);
  assert.ok(why.some((w) => re.test(w)), why.join('; '));
};

test('refused: the program changed', () => {
  const a = inputs();
  a.now = { ...a.now, programSha: 'c'.repeat(64) };
  refused(a, /program changed/);
  const b = inputs();
  b.mod.programSha = 'c'.repeat(64);
  b.gameModuleSha = moduleShaOf(b.mod);
  refused(b, /program changed/);
});

test('refused: module.json changed in more than its pins', () => {
  const a = inputs();
  a.mod.envelope.branches.width = [9, 18];
  a.gameModuleSha = moduleShaOf(a.mod);
  refused(a, /more than its pins/);
  const b = inputs();
  b.mod.palette.lamp.minLight = 13;
  b.gameModuleSha = moduleShaOf(b.mod);
  refused(b, /more than its pins/);
});

test('refused: this tool and the game disagree on the moduleSha', () => {
  const a = inputs();
  a.gameModuleSha = 'd'.repeat(64);
  refused(a, /disagrees with the game/);
});

test('refused: a smoke step failed or is missing', () => {
  let a = inputs(); a.survival.ok = false; refused(a, /billing check \(survival\) did not pass/);
  a = inputs(); a.survival = null; refused(a, /no billing check/);
  a = inputs(); a.survival.architect = FROM; refused(a, /billing check ran on other files or versions/);
  a = inputs(); a.fields[0].ok = false; refused(a, /field round field-potatoes did not pass/);
  a = inputs(); a.fields = []; refused(a, /no field round/);
  a = inputs(); a.fields[0].programSha = 'e'.repeat(64); refused(a, /no field round/);
  a = inputs(); a.fields[0].runs = 1; refused(a, /1 runs/);
  a = inputs(); a.offline.ok = false; refused(a, /offline step did not pass/);
  a = inputs(); a.ingame.ok = false; refused(a, /ingame step did not pass/);
  // the evidence is the named step, with every check passing
  a = inputs(); a.survival = { ...a.fields[0] }; refused(a, /is step 'field-potatoes', not survival/);
  a = inputs(); a.survival.checks.push({ ok: false, what: 'y' }); refused(a, /survival\) has a failed or no check/);
  a = inputs(); a.fields[0].checks = []; refused(a, /field-potatoes has a failed or no check/);
  a = inputs(); a.fields[0].data.crop = 'wheat'; refused(a, /not field-wheat/);
  a = inputs(); a.fields[0].data.farms = []; refused(a, /judged no farm/);
  a = inputs(); a.requote = null; refused(a, /no bounds re-quote/);
  a = inputs(); a.requote.evidence.ok = false; refused(a, /bounds re-quote did not pass/);
  a = inputs(); a.requote.evidence.architect = FROM; refused(a, /bounds re-quote ran on other/);
  a = inputs(); a.requote.problems = ['x']; refused(a, /bounds re-quote: x/);
});

test('refused: the bill moved (a re-quoted bound differs from the carried one, or one is missing or repeats)', () => {
  let a = inputs(); a.requote.bounds[5].bom['minecraft:dirt'] += 1; refused(a, /the bill moved: 1 of 108/);
  a = inputs(); a.requote.bounds.pop(); refused(a, /the bill moved/);
  a = inputs(); a.requote.bounds[1] = clone(a.requote.bounds[0]); refused(a, /the bill moved/);
});

test('refused: the build moved (a block count differs from the measured one)', () => {
  const a = inputs();
  a.offEntry.branches[3].blocks += 1;
  refused(a, /the build moved: 1 of/);
  const b = inputs();
  b.offEntry.branches = b.offEntry.branches.map(() => clone(b.offEntry.branches[0]));
  refused(b, /the build moved/);
  const c = inputs();
  c.offEntry.branches[0].ok = false;
  refused(c, /the build moved/);
});

test('refused: nothing to carry, a failed or tampered certificate, the measured version itself', () => {
  let a = inputs(); a.prev = null; refused(a, /no measured.json/);
  a = inputs(); a.prev.result = 'fail'; refused(a, /result 'fail'/);
  a = inputs(); delete a.prev.checks.measure; refused(a, /no measurement/);
  a = inputs({ arch: FROM }); refused(a, /fully measured version itself/);
  const s = smokeCarry(inputs()).m;
  s.bounds[0].bill = { 'minecraft:dirt': 1 };
  a = inputs({ prev: s, arch: '9.1.0' }); refused(a, /not the carried measurement's/);
  a = inputs(); a.prev.basis = 'guess'; refused(a, /unknown basis/);
});

// (M4) storage_hall@1: its round is the deterministic hall step (module-measure-hall.mjs hall), not a crop field round; it has no rates
test('storage_hall@1 carries on its hall round, from the version it was fully measured on', () => {
  const HDIR = path.join(ROOT, 'modules', 'storage_hall', '1');
  const hm = JSON.parse(fs.readFileSync(path.join(HDIR, 'measured.json'), 'utf8'));
  const hmod = JSON.parse(fs.readFileSync(path.join(HDIR, 'module.json'), 'utf8'));
  const prev = hm.basis === 'smoke' ? { ...clone(hm.carried), bounds: clone(hm.bounds), rates: clone(hm.rates) } : clone(hm);
  const now = { programSha: hmod.programSha, moduleJsonSha: 'c'.repeat(64) };
  const mod = { ...clone(hmod), pins: { kit: '9.9.0', checker: '9.9.0' } };
  const versions = { architect: NEXT, minecraft: '26.3' };
  const ev = (step) => ({ step, ok: true, at: 't', head: 'h', seconds: 1, ...now, ...versions, checks: [{ ok: true, what: 'x' }] });
  const a = {
    prev, mod, now, gameModuleSha: moduleShaOf(mod), versions,
    offline: { ok: true, data: { architect: NEXT, kit: '9.9.0', checker: '9.9.0' } }, offEntry: { branches: clone(prev.checks.offline.branches) },
    ingame: { ok: true }, inEntry: {}, checks: { offline: {}, ingame: {} }, survival: ev('survival'), fields: [ev('hall')],
    requote: { evidence: ev('bounds'), bounds: clone(prev.bounds), problems: [] },
  };
  const { why, m } = smokeCarry(a);
  assert.deepEqual(why, []);
  assert.equal(m.from, '0.67.0');
  assert.deepEqual(m.rates, []);
  assert.equal(m.checks.smoke.fields[0].step, 'hall');
  const b = clone(a); b.fields[0].ok = false;
  assert.match(smokeCarry(b).why.join('; '), /the hall round did not pass/);
  const c = clone(a); c.fields = [];
  assert.match(smokeCarry(c).why.join('; '), /no field round/);
});
