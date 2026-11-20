import test from 'node:test';
import assert from 'node:assert/strict';

import { pickFlows } from '../tools/lib/e2e-flows.mjs';

// the free run's flows as tools/e2e.mjs names them (districts builds on the settlement proposals describes)
const FREE = [
  { name: 'stone-recipe', tags: 'screens' },
  { name: 'place', tags: 'update revert undo screens' },
  { name: 'proposals', tags: 'farm' },
  { name: 'districts', tags: 'protect road layout', needs: ['proposals'] },
  { name: 'player-builds', tags: 'protect' },
  { name: 'migrated-steward', tags: 'save migration' },
];

test('no --only runs every flow, none pulled in', () => {
  const why = pickFlows(FREE, null);
  assert.deepEqual([...why.keys()], FREE.map((f) => f.name));
  assert.ok([...why.values()].every((v) => v === ''));
});

test('--only districts pulls in proposals, which it needs', () => {
  const why = pickFlows(FREE, ['districts']);
  assert.deepEqual(Object.fromEntries(why), { districts: '', proposals: 'districts' });
});

test('a tag word that picks districts pulls in proposals too', () => {
  assert.deepEqual(Object.fromEntries(pickFlows(FREE, ['road'])), { districts: '', proposals: 'districts' });
  // protect picks districts and player-builds; proposals comes with districts
  assert.deepEqual(new Set(pickFlows(FREE, ['protect']).keys()), new Set(['districts', 'player-builds', 'proposals']));
});

test('a needed flow picked on its own says so, and pulls nothing back', () => {
  assert.deepEqual(Object.fromEntries(pickFlows(FREE, ['farm'])), { proposals: '' });
});

test('needs are followed through a chain', () => {
  const list = [{ name: 'a', tags: '' }, { name: 'b', tags: '', needs: ['a'] }, { name: 'c', tags: '', needs: ['b'] }];
  assert.deepEqual(Object.fromEntries(pickFlows(list, ['c'])), { c: '', b: 'c', a: 'b' });
});

test('a word matching nothing runs no flows (the sanity set runs outside them)', () => {
  assert.equal(pickFlows(FREE, ['sanity']).size, 0);
});

// ------------------------------------------------------------------ slice EF: packing, needs, skip keys

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { groupFlows, packFlows, codeBlocks, closureOf, fixtureRefs, hashPaths, hashLib, libFiles, skipDecision, applySkips, isStrict } from '../tools/lib/e2e-flows.mjs';

const STUB = [
  { name: 'stub', tags: 'paid card plan wall revision undo' },
  { name: 'growth', tags: 'growth region' },
  { name: 'adopt', tags: 'adopt growth region undo' },
  { name: 'adopt-ns', tags: 'adopt north-south', needs: ['adopt'] },
  { name: 'gate-mech', tags: 'gate wall' },
  { name: 'gate-mech-lots', tags: 'gate wall' },
  { name: 'plan-check', tags: 'plan' },
];

test('a flow and what it needs make one group, in registry order', () => {
  assert.deepEqual(groupFlows(STUB), [['stub'], ['growth'], ['adopt', 'adopt-ns'], ['gate-mech'], ['gate-mech-lots'], ['plan-check']]);
});

test('flows sharing a block that writes fixed names share a group; names limits it to the flows that run', () => {
  const g = groupFlows(STUB, new Set(['growth', 'gate-mech', 'gate-mech-lots', 'plan-check']), [['gate-mech-lots', 'gate-mech'], ['stub', 'growth']]);
  assert.deepEqual(g, [['growth'], ['gate-mech', 'gate-mech-lots'], ['plan-check']]);
});

test('packing puts the longest groups first, each on the least loaded client', () => {
  const secs = { stub: 187, growth: 149, adopt: 100, 'adopt-ns': 90, 'gate-mech': 60, 'gate-mech-lots': 50, 'plan-check': 31 };
  const lanes = packFlows(groupFlows(STUB), secs, 2);
  // adopt + adopt-ns (190), then stub (187) on the other, growth (149) on stub's (the lighter), then the gates and plan-check on adopt's
  assert.deepEqual(lanes.map((l) => l.flows), [['adopt', 'adopt-ns', 'gate-mech', 'gate-mech-lots', 'plan-check'], ['stub', 'growth']]);
  assert.deepEqual(lanes.map((l) => l.seconds), [331, 336]);
});

test('a flow with no recorded time goes after every timed one; needs stay after what they need', () => {
  const lanes = packFlows(groupFlows(STUB), { stub: 10, growth: 500, adopt: 5 }, 2);
  assert.deepEqual(lanes[0].flows, ['growth']);
  assert.deepEqual(lanes[1].flows.slice(0, 1), ['stub']);
  const all = lanes.flatMap((l) => l.flows);
  assert.equal(all.length, STUB.length);
  for (const l of lanes) if (l.flows.includes('adopt-ns')) assert.ok(l.flows.indexOf('adopt') < l.flows.indexOf('adopt-ns'));
  // adopt's group has an untimed member (adopt-ns): the whole group is untimed
  assert.equal(lanes[0].untimed + lanes[1].untimed, 5);
});

test('one client takes every flow; packing never loses or repeats a flow', () => {
  const [one] = packFlows(groupFlows(STUB), {}, 1);
  assert.deepEqual(one.flows, STUB.map((f) => f.name));
  const lanes = packFlows(groupFlows(STUB), { stub: 1 }, 4);
  assert.deepEqual(lanes.flatMap((l) => l.flows).sort(), STUB.map((f) => f.name).sort());
});

const SRC = `import x from 'y';
const ROOT = '/r';
// a comment naming helperB, which must not count
const cmd = (s) => s;
/** doc */
async function helperA() {
  return cmd('a') + fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'walled_town.json'));
}
function helperB() { return 1; }
async function flowOne() {
  await helperA(); // helperB in a trailing comment
  const f = (n) => path.join(ROOT, 'fixtures', 'talk', n);
}
async function flowTwo() { return helperB(); }
try {
  await flowOne();
} finally {}
`;

test('code blocks: top-level names, comments left out, statements unnamed', () => {
  const b = codeBlocks(SRC);
  assert.deepEqual(Object.keys(b).sort(), ['ROOT', 'cmd', 'flowOne', 'flowTwo', 'helperA', 'helperB']);
  assert.ok(!b.cmd.includes('helperB'));
  assert.ok(!b.flowOne.includes('trailing'));
});

test("a flow's closure follows its calls, not its comments", () => {
  const b = codeBlocks(SRC);
  assert.deepEqual(closureOf(b, '() => flowOne()'), ['ROOT', 'cmd', 'flowOne', 'helperA']);
  assert.deepEqual(closureOf(b, '() => flowTwo()'), ['flowTwo', 'helperB']);
});

test('fixture refs: a literal file, and a directory for a name made at run time', () => {
  const b = codeBlocks(SRC);
  const text = closureOf(b, 'flowOne()').map((n) => b[n]).join('\n');
  assert.deepEqual(fixtureRefs(text), ['fixtures/plans/walled_town.json', 'fixtures/talk/']);
  assert.deepEqual(fixtureRefs("path.join(ROOT, 'fixtures', f)"), ['fixtures/']);
});

test('hashPaths changes with a file in a hashed directory, and counts a missing path', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ef-'));
  fs.mkdirSync(path.join(dir, 'fixtures', 'talk'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'fixtures', 'talk', 'a.json'), '1');
  const a = hashPaths(dir, ['fixtures/talk/']);
  assert.equal(hashPaths(dir, ['fixtures/talk/']), a);
  fs.writeFileSync(path.join(dir, 'fixtures', 'talk', 'b.json'), '2');
  assert.notEqual(hashPaths(dir, ['fixtures/talk/']), a);
  assert.notEqual(hashPaths(dir, ['fixtures/gone.json']), hashPaths(dir, []));
  fs.rmSync(dir, { recursive: true, force: true });
});

const KEY = { block: 'b1', fixtures: 'f1', src: 's1', lib: 'l1', jar: 'j1', pin: 'architect 0.67.0; lab-ui 0.4.1' };
const LAST = { at: '2026-11-15T01:00:00Z', ...KEY };

test('strict flows are the ones with bill, refund, undo, restart or survival in their name or tags', () => {
  assert.ok(isStrict({ name: 'survival-mech', tags: 'gate' }));
  assert.ok(isStrict({ name: 'adopt', tags: 'adopt growth region undo' }));
  assert.ok(isStrict({ name: 'village-orphan', tags: 'village orphan restart sweep' }));
  assert.ok(!isStrict({ name: 'plan-check', tags: 'plan' }));
});

test('a strict flow skips only on an exact key; a new jar or pin runs it', () => {
  const f = { name: 'survival', tags: 'stockpile bill refund' };
  assert.equal(skipDecision(f, KEY, LAST).skip, true);
  assert.match(skipDecision(f, { ...KEY, jar: 'j2' }, LAST).why, /jar changed/);
  assert.equal(skipDecision(f, { ...KEY, pin: 'architect 0.68.0; lab-ui 0.4.1' }, LAST).skip, false);
  assert.match(skipDecision(f, { ...KEY, jar: null }, LAST).why, /no jar/);
  assert.equal(skipDecision(f, KEY, undefined).skip, false);
});

test('a light flow skips on its block, fixtures and the mod sources, whatever the jar or pin', () => {
  const f = { name: 'plan-check', tags: 'plan' };
  assert.equal(skipDecision(f, { ...KEY, jar: 'j2', pin: 'other' }, LAST).skip, true);
  assert.match(skipDecision(f, { ...KEY, src: 's2' }, LAST).why, /src changed/);
  assert.match(skipDecision(f, { ...KEY, block: 'b2' }, LAST).why, /block changed/);
  assert.match(skipDecision(f, { ...KEY, fixtures: 'f2' }, LAST).why, /fixtures changed/);
});

test('(SAVEB leftover) a tools/lib change runs strict and light flows again; a PASS from before lib was keyed runs once', () => {
  for (const f of [{ name: 'survival', tags: 'stockpile bill refund' }, { name: 'plan-check', tags: 'plan' }]) {
    assert.match(skipDecision(f, { ...KEY, lib: 'l2' }, LAST).why, /lib changed/);
    const { lib, ...old } = LAST;
    assert.equal(skipDecision(f, KEY, old).skip, false);
    assert.match(skipDecision(f, { ...KEY, lib: null }, LAST).why, /no lib/);
  }
});

test('hashLib covers every tools/lib/*.mjs and only those', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ef-lib-'));
  const lib = path.join(dir, 'tools', 'lib');
  fs.mkdirSync(lib, { recursive: true });
  fs.writeFileSync(path.join(lib, 'a.mjs'), 'export const a = 1;');
  fs.writeFileSync(path.join(lib, 'notes.txt'), 'x');
  assert.deepEqual(libFiles(dir), ['tools/lib/a.mjs']);
  const h = hashLib(dir);
  fs.writeFileSync(path.join(lib, 'notes.txt'), 'y');
  assert.equal(hashLib(dir), h);
  fs.writeFileSync(path.join(lib, 'a.mjs'), 'export const a = 2;');
  const h2 = hashLib(dir);
  assert.notEqual(h2, h);
  fs.writeFileSync(path.join(lib, 'b.mjs'), 'export const b = 1;');
  assert.notEqual(hashLib(dir), h2);
  fs.rmSync(dir, { recursive: true, force: true });
  // the repo's own lib: the modules e2e.mjs imports are all in it
  const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
  const real = libFiles(ROOT);
  for (const m of fs.readFileSync(path.join(ROOT, 'tools', 'e2e.mjs'), 'utf8').matchAll(/from '\.\/(lib\/[^']+)'/g)) assert.ok(real.includes(`tools/${m[1]}`), m[1]);
});

test('an unchanged flow that a running flow needs runs anyway', () => {
  const why = pickFlows(STUB, ['adopt']);
  const r = applySkips(STUB, why, { adopt: true });
  assert.deepEqual(r.skipped, []);
  assert.equal(r.run.get('adopt'), 'adopt-ns');
  const r2 = applySkips(STUB, why, { adopt: true, 'adopt-ns': true });
  assert.deepEqual(r2.skipped.sort(), ['adopt', 'adopt-ns']);
  assert.equal(r2.run.size, 0);
});
