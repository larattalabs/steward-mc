import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { creditsOf, creditedSome, savedLedger, refundOnce, waterLeft, artifactSnapshot, artifactChanges, hallCap, capAnswer } from '../tools/lib/sculpt-halts.mjs';

const OWNER = 'steward_mc:settlement/set_3';
const line = (k, items) => `[12:00:00] [Server thread/INFO] (architect) Stockpile ${OWNER}: refund ${k} returned {${Object.entries(items).map(([i, n]) => `${i}=${n}`).join(', ')}}`;
const again = (k) => `[12:00:00] [Server thread/INFO] (architect) Stockpile ${OWNER}: refund ${k} already credited; nothing credited again`;
const clay = { 'minecraft:clay': 10 };
const paidBy = { 'site:A@1': clay, 'site:B@2': clay, 'site:C@3': clay };

test('the refund lines: items, "already" and an empty refund (no credit)', () => {
  const cs = creditsOf([line('site:A@1', clay), again('site:B@2'), line('site:C@3', {})].join('\n'), OWNER);
  assert.deepEqual(cs.map((c) => [c.key, c.again, c.items]), [['site:A@1', false, clay], ['site:B@2', true, {}], ['site:C@3', false, {}]]);
  assert.deepEqual(cs.map(creditedSome), [true, false, false], 'an empty "returned {}" is not a credit (GPT P2)');
});

test('the GPT counterexample: A saved and credited again, B logged but lost and never credited: both fail', () => {
  const saved = { credited: new Set(['site:A@1']), paid: { 'site:B@2': clay, 'site:C@3': clay } };
  const before = creditsOf([line('site:A@1', clay), line('site:B@2', clay)].join('\n'), OWNER);
  const after = creditsOf([line('site:A@1', clay), line('site:C@3', clay)].join('\n'), OWNER);
  const r = refundOnce({ paidBy, saved, before, after });
  assert.ok(r.off.some((x) => /^site:A@1 saved credited at the halt and credited again/.test(x)), r.off.join('; '));
  assert.ok(r.off.some((x) => /^site:B@2 owed at the halt and credited 0 time/.test(x)), r.off.join('; '));
  assert.deepEqual(r.durable, ['site:A@1']);
});

test('a refund halt done right: the saved credit not made again, the lost one and the owed one made once each', () => {
  const saved = { credited: new Set(['site:A@1']), paid: { 'site:B@2': clay, 'site:C@3': clay } };
  const before = creditsOf([line('site:A@1', clay), line('site:B@2', clay)].join('\n'), OWNER);
  const after = creditsOf([again('site:A@1'), line('site:B@2', clay), line('site:C@3', clay)].join('\n'), OWNER);
  assert.deepEqual(refundOnce({ paidBy, saved, before, after }), { off: [], durable: ['site:A@1'], owed: ['site:B@2', 'site:C@3'] });
  // nothing saved since the barrier: every credit made after the restart
  const none = { credited: new Set(), paid: { ...paidBy } };
  const all = creditsOf(Object.keys(paidBy).map((k) => line(k, clay)).join('\n'), OWNER);
  assert.deepEqual(refundOnce({ paidBy, saved: none, before, after: all }).off, []);
});

test('a saved ledger that is not the halt\'s (a paid key neither credited nor still paid) fails; so does a wrong amount or an unpaid credit', () => {
  const saved = { credited: new Set(), paid: { 'site:B@2': clay, 'site:C@3': clay } };
  const all = creditsOf(Object.keys(paidBy).map((k) => line(k, clay)).join('\n'), OWNER);
  assert.ok(refundOnce({ paidBy, saved, before: [], after: all }).off.some((x) => /^site:A@1 neither credited nor paid/.test(x)));
  const full = { credited: new Set(), paid: { ...paidBy } };
  const short = creditsOf([line('site:A@1', { 'minecraft:clay': 9 }), line('site:B@2', clay), line('site:C@3', clay), line('site:Z@9', clay)].join('\n'), OWNER);
  const off = refundOnce({ paidBy, saved: full, before: [], after: short }).off;
  assert.ok(off.some((x) => /^site:A@1 credited .* after the restart, its record/.test(x)), off.join('; '));
  assert.ok(off.some((x) => /^site:Z@9 credited .* with no paid record/.test(x)), off.join('; '));
});

test('the saved ledger read from stockpiles.json; an unreadable file or no such stockpile throws', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sculpt-halts-'));
  try {
    const f = path.join(dir, 'stockpiles.json');
    fs.writeFileSync(f, JSON.stringify({ format: 2, stockpiles: [{ owner: OWNER, ledger: { credited: ['site:A@1'], paid: { 'site:B@2': clay }, returned: clay } }] }));
    const l = savedLedger(f, OWNER);
    assert.ok(l.credited.has('site:A@1'));
    assert.deepEqual(l.paid, { 'site:B@2': clay });
    assert.throws(() => savedLedger(f, 'steward_mc:settlement/other'));
    fs.writeFileSync(f, '{"stockp');
    assert.throws(() => savedLedger(f, OWNER));
    assert.throws(() => savedLedger(path.join(dir, 'none.json'), OWNER));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

const st = (g, s) => `(architect) Group ${g}: stage fluids ${s}`;
const site = (c) => `(architect) Placed cell site ${c} (architect:terrain) (640 cells)`;

test('the GPT counterexample: every water job placed before the halt, the stage marked placed only after it: no water left', () => {
  const preHalt = [st('g4', 'approved -> placing'), ...Array.from({ length: 10 }, (_, i) => site(`c${73 + i}`))].join('\n');
  const after = ['(architect) Region rg4: fluids written; 10028 forced fluid tick(s), settling', st('g4', 'placing -> placed')].join('\n');
  const r = waterLeft({ preHalt, after, group: 'g4', region: 'rg4' });
  assert.equal(r.ok, false);
  assert.match(r.why, /no terrain site placed after the restart/);
});

test('water sites placed again after the restart (replays) are no proof; a new one is', () => {
  const preHalt = [st('g4', 'approved -> placing'), site('c73'), site('c74')].join('\n');
  const replay = [site('c73'), site('c74'), st('g4', 'placing -> placed')].join('\n');
  assert.equal(waterLeft({ preHalt, after: replay, group: 'g4', region: 'rg4' }).ok, false);
  const fresh = [site('c74'), site('c75'), site('c76'), st('g4', 'placing -> placed')].join('\n');
  const r = waterLeft({ preHalt, after: fresh, group: 'g4', region: 'rg4' });
  assert.equal(r.ok, true, r.why);
  assert.deepEqual(r.fresh, ['c75', 'c76']);
  // a site placed after the stage is placed (another stage's) doesn't count
  assert.equal(waterLeft({ preHalt, after: [st('g4', 'placing -> placed'), site('c90')].join('\n'), group: 'g4', region: 'rg4' }).ok, false);
});

test('the water halt fails when its stage or its water was done before the halt, or another group\'s stage is the one placed', () => {
  const after = [site('c80'), st('g4', 'placing -> placed')].join('\n');
  assert.equal(waterLeft({ preHalt: [st('g4', 'approved -> placing'), st('g4', 'placing -> placed')].join('\n'), after, group: 'g4', region: 'rg4' }).ok, false);
  assert.equal(waterLeft({ preHalt: [st('g4', 'approved -> placing'), '(architect) Region rg4: fluids written; 1 forced'].join('\n'), after, group: 'g4', region: 'rg4' }).ok, false);
  assert.equal(waterLeft({ preHalt: st('g4', 'approved -> placing'), after: [site('c80'), st('g5', 'placing -> placed')].join('\n'), group: 'g4', region: 'rg4' }).ok, false);
  assert.equal(waterLeft({ preHalt: st('g4', 'approved -> placing'), after, group: null, region: 'rg4' }).ok, false);
});

const SHA = 'a'.repeat(64), X = 'b'.repeat(64), Y = 'c'.repeat(64);

test('the GPT counterexample: another artifact pinned, unpinned and swept while the stop is handled shows', () => {
  const art0 = { [`${SHA}.json`]: '1:1:1', [`${SHA}.pins.json`]: '1:1:2', [`${X}.json`]: '1:1:3', [`${X}.pins.json`]: '1:1:4' };
  const art1 = { [`${SHA}.json`]: '1:1:1', [`${SHA}.pins.json`]: '1:2:5', [`${X}.removed.json`]: '1:3:6' };
  assert.deepEqual(artifactChanges(art0, art1, SHA), [{ name: `${X}.json`, how: 'removed' }, { name: `${X}.pins.json`, how: 'removed' }, { name: `${X}.removed.json`, how: 'added' }]);
});

test('the approved artifact let go and swept is the only change allowed; a new artifact made then swept shows', () => {
  const art0 = { [`${SHA}.json`]: '1:1:1', [`${SHA}.meta.json`]: '1:1:7', [`${SHA}.pins.json`]: '1:1:2' };
  assert.deepEqual(artifactChanges(art0, { [`${SHA}.meta.json`]: '1:1:7', [`${SHA}.removed.json`]: '1:3:8' }, SHA), []);
  assert.deepEqual(artifactChanges(art0, { ...art0, [`${SHA}.pins.json`]: '1:1:9' }, SHA), [], 'its pins file rewritten (a new inode)');
  assert.deepEqual(artifactChanges(art0, { ...art0, [`${Y}.meta.json`]: '1:4:9', [`${Y}.removed.json`]: '1:4:10' }, SHA).map((c) => c.name), [`${Y}.meta.json`, `${Y}.removed.json`]);
  assert.deepEqual(artifactChanges(art0, { ...art0, [`${SHA}.meta.json`]: '2:5:11' }, SHA), [{ name: `${SHA}.meta.json`, how: 'changed' }]);
  assert.deepEqual(artifactChanges({ ...art0, [`${SHA}.removed.json`]: '1:1:1' }, art0, SHA), [{ name: `${SHA}.removed.json`, how: 'removed' }], 'a tombstone gone: brought back');
});

test('the artifact snapshot: a folder that can\'t be read throws (never an empty store); a rewrite is a new inode', () => {
  assert.throws(() => artifactSnapshot(path.join(os.tmpdir(), 'sculpt-halts-none-' + process.pid)));
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sculpt-halts-'));
  try {
    fs.writeFileSync(path.join(dir, `${SHA}.pins.json`), '{"owners":["x"]}');
    const a = artifactSnapshot(dir);
    fs.writeFileSync(path.join(dir, 'tmp'), '{"owners":["x"]}');
    fs.renameSync(path.join(dir, 'tmp'), path.join(dir, `${SHA}.pins.json`));
    const b = artifactSnapshot(dir);
    assert.notEqual(a[`${SHA}.pins.json`], b[`${SHA}.pins.json`]);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('the hall cap: a refused region answer is never "no region maxBill" (GPT P2); a failed cap answer is a refusal', () => {
  const c = { lotCap: { maxBill: { 'minecraft:stone': 10 }, drawn: { 'minecraft:stone': 10 }, overshoot: {} } };
  assert.equal(hallCap(c, { maxBill: null, paid: {} }).ok, true);
  assert.equal(hallCap(c, { refused: 'dev.bc.cap failed: x' }).ok, false);
  assert.equal(hallCap(c, { ok: false, error: 'x' }).ok, false);
  assert.equal(hallCap(c, {}).ok, false, 'an answer without maxBill');
  assert.equal(hallCap(c, { maxBill: { 'minecraft:stone': 1 } }).ok, false);
  assert.equal(hallCap({ refused: 'x' }, { maxBill: null }).ok, false);
  assert.equal(hallCap({ lotCap: { ...c.lotCap, drawn: { 'minecraft:stone': 11 } } }, { maxBill: null }).ok, false);
  assert.ok(capAnswer({ ok: false, error: 'region must be a nonblank string' }).refused);
  assert.ok(capAnswer(null).refused);
  assert.ok(capAnswer({ maxBill: null, paid: {} }, ['maxBill', 'paid', 'drawn']).refused);
  assert.deepEqual(capAnswer({ maxBill: null, paid: {}, drawn: {} }, ['maxBill', 'paid', 'drawn']), { maxBill: null, paid: {}, drawn: {} });
});
