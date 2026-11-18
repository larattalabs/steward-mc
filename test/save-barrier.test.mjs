// slice SAVEB: a dev.command's own verdict checked, and the save barrier (tools/lib/save-barrier.mjs), here against a fake bridge.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkCommand, runCommand, hasRequest, saveBarrier, savedOnDisk, CommandNotRun } from '../tools/lib/save-barrier.mjs';

/** A fake DevClient: {@code handlers[type](payload, n)} answers each request (n: that type's call count); every request is kept in {@code sent}. */
function fakeDev(handlers) {
  const sent = [];
  const n = {};
  return {
    sent,
    async request(type, payload = {}) {
      sent.push({ type, payload });
      n[type] = (n[type] ?? 0) + 1;
      const h = handlers[type];
      if (!h) return { ok: false, error: `unknown type ${type}` };
      return h(payload, n[type]);
    },
  };
}
const help = (...types) => () => ({ ok: true, commands: Object.fromEntries(types.map((t) => [t, '...'])), screens: {} });
const clock = () => { let t = 0; return { now: () => t, sleep: async (ms) => { t += ms; } }; };

// ---- checkCommand

test('a command that ran passes, its answer returned', () => {
  const r = { ok: true, cmd: '/tp @p 0 64 0', messages: ['Teleported'], success: true, result: 1 };
  assert.equal(checkCommand(r, { cmd: r.cmd }), r);
});

test('an unknown command (no result) throws with its messages', () => {
  const r = { ok: true, cmd: '/save-all flush', messages: ['Unknown or incomplete command. See below for error', 'save-all flush<--[HERE]'], success: false, result: null };
  assert.throws(() => checkCommand(r, { cmd: r.cmd }), (e) => e instanceof CommandNotRun && /save-all flush: did not run/.test(e.message) && /Unknown or incomplete/.test(e.message) && e.response === r);
});

test('a command that ran and failed (a number in result) passes: flows read its refusal', () => {
  const r = { ok: true, cmd: '/kill @e[type=minecraft:pig]', messages: ['No entity was found'], success: false, result: 0 };
  assert.equal(checkCommand(r, { cmd: r.cmd }), r);
});

test('a bridge refusal (ok false) throws', () => {
  assert.throws(() => checkCommand({ ok: false, error: 'refused in a Hardcore world' }, { cmd: '/time set day' }), (e) => e instanceof CommandNotRun && /refused by the bridge: refused in a Hardcore world/.test(e.message));
  assert.throws(() => checkCommand(undefined, { cmd: '/x' }), CommandNotRun);
});

test('allowFail lets an unrun command and a refusal through', () => {
  const r = { ok: true, messages: ['Unknown or incomplete command'], success: false, result: null };
  assert.equal(checkCommand(r, { cmd: '/nope', allowFail: true }), r);
  const bad = { ok: false, error: 'x' };
  assert.equal(checkCommand(bad, { allowFail: true }), bad);
});

test('runCommand sends dev.command and checks the answer', async () => {
  const dev = fakeDev({ 'dev.command': ({ cmd }) => (cmd === '/save-all' ? { ok: true, cmd, messages: ['Unknown or incomplete command'], success: false, result: null } : { ok: true, cmd, messages: [], success: true, result: 1 }) });
  assert.equal((await runCommand(dev, '/time set day')).result, 1);
  await assert.rejects(runCommand(dev, '/save-all'), CommandNotRun);
  assert.equal((await runCommand(dev, '/save-all', { allowFail: true })).result, null);
  assert.deepEqual(dev.sent.map((s) => s.payload.cmd), ['/time set day', '/save-all', '/save-all']);
});

// ---- hasRequest

test('hasRequest reads dev.help once per client', async () => {
  const dev = fakeDev({ 'dev.help': help('dev.ping', 'dev.stockpile.save') });
  assert.equal(await hasRequest(dev, 'dev.stockpile.save'), true);
  assert.equal(await hasRequest(dev, 'dev.nothing'), false);
  assert.equal(dev.sent.filter((s) => s.type === 'dev.help').length, 1);
});

test('hasRequest is false when dev.help fails', async () => {
  const dev = { async request() { throw new Error('closed'); } };
  assert.equal(await hasRequest(dev, 'dev.stockpile.save'), false);
});

// ---- saveBarrier

test('with dev.stockpile.save on the bridge, the barrier calls it and nothing else', async () => {
  const dev = fakeDev({ 'dev.help': help('dev.stockpile.save', 'dev.stockpile.ps', 'dev.stockpile.saved'), 'dev.stockpile.save': () => ({ ok: true, ms: 12 }) });
  const r = await saveBarrier(dev);
  assert.equal(r.how, 'save');
  assert.equal(r.ms, 12);
  assert.deepEqual(dev.sent.map((s) => s.type), ['dev.help', 'dev.stockpile.save']);
});

test('Architect v0.75.0\'s dev.stockpile.save answer {written, path, bytes, saves} passes through the barrier', async () => {
  const ans = { ok: true, written: true, path: '/run/saves/Steward E2E/data/architect_stockpile.dat', bytes: 2048, saves: 3 };
  const dev = fakeDev({ 'dev.help': help('dev.stockpile.save'), 'dev.stockpile.save': () => ans });
  const r = await saveBarrier(dev);
  assert.deepEqual(r, { how: 'save', ...ans });
});

test('from Architect v0.75.0 a command that never ran answers ok:false (a DevError), which is refused unless allowFail', () => {
  const r = { ok: false, error: 'dev.command /save-all: Unknown or incomplete command' };
  assert.throws(() => checkCommand(r, { cmd: '/save-all' }), (e) => e instanceof CommandNotRun && /refused by the bridge: .*Unknown/.test(e.message));
  assert.equal(checkCommand(r, { cmd: '/save-all', allowFail: true }), r);
});

test('a failed dev.stockpile.save throws', async () => {
  const dev = fakeDev({ 'dev.help': help('dev.stockpile.save'), 'dev.stockpile.save': () => ({ ok: false, error: 'not in a world' }) });
  await assert.rejects(saveBarrier(dev), /dev.stockpile.save failed: not in a world/);
});

test('without it, the periodic save is turned on and waited for until the ledger is saved', async () => {
  const c = clock();
  const dev = fakeDev({
    'dev.help': help('dev.stockpile.ps', 'dev.stockpile.saved'),
    'dev.stockpile.ps': () => ({ ok: true }),
    'dev.stockpile.saved': (_, n) => ({ ok: true, unsaved: n < 4, periodicSaves: n < 4 ? 2 : 3 }),
  });
  const r = await saveBarrier(dev, { ...c, pollMs: 2000 });
  assert.equal(r.how, 'periodic');
  assert.equal(r.saved.periodicSaves, 3);
  assert.equal(r.waitedMs, 6000);
  assert.deepEqual(dev.sent[1], { type: 'dev.stockpile.ps', payload: { noPeriodic: false } });
  assert.equal(dev.sent.filter((s) => s.type === 'dev.stockpile.saved').length, 4);
});

test('an already saved ledger passes at once (no periodic save runs for a clean ledger, so none is waited for)', async () => {
  const c = clock();
  const dev = fakeDev({ 'dev.help': help(), 'dev.stockpile.ps': () => ({ ok: true }), 'dev.stockpile.saved': () => ({ ok: true, unsaved: false, periodicSaves: 0 }) });
  const r = await saveBarrier(dev, c);
  assert.equal(r.how, 'periodic');
  assert.equal(r.waitedMs, 0);
});

test('match gates the barrier: saved but not yet the live state waits on', async () => {
  const c = clock();
  const dev = fakeDev({
    'dev.help': help(),
    'dev.stockpile.ps': () => ({ ok: true }),
    'dev.stockpile.saved': (_, n) => ({ ok: true, unsaved: false, file: { delivered: { 'minecraft:oak_log': n < 3 ? 0 : 64 } } }),
  });
  const r = await saveBarrier(dev, { ...c, match: (s) => s.file.delivered['minecraft:oak_log'] === 64 });
  assert.equal(r.saved.file.delivered['minecraft:oak_log'], 64);
  assert.equal(dev.sent.filter((s) => s.type === 'dev.stockpile.saved').length, 3);
});

test('a save that never comes throws after the timeout, naming the state', async () => {
  const c = clock();
  const dev = fakeDev({ 'dev.help': help(), 'dev.stockpile.ps': () => ({ ok: true }), 'dev.stockpile.saved': () => ({ ok: true, unsaved: true, periodicSaves: 5, loadFailed: false }) });
  await assert.rejects(saveBarrier(dev, { ...c, timeoutMs: 10_000, pollMs: 2000 }), /not saved after 10000 ms \(unsaved true, periodic saves 5, loadFailed false\)/);
  assert.equal(dev.sent.filter((s) => s.type === 'dev.stockpile.saved').length, 6);
});

test('a refused dev.stockpile.ps throws', async () => {
  const dev = fakeDev({ 'dev.help': help() });
  await assert.rejects(saveBarrier(dev, clock()), /dev.stockpile.ps \{noPeriodic: false\} failed: unknown type dev.stockpile.ps/);
});

// ---- savedOnDisk (module-measure-hall's savedAs: a baseline on disk before a kill point is armed)

test('savedOnDisk on 0.75+: dev.stockpile.save, then the disk read until it passes; no periodic save asked', async () => {
  const c = clock();
  const dev = fakeDev({ 'dev.help': help('dev.stockpile.save', 'dev.stockpile.ps', 'dev.stockpile.saved'), 'dev.stockpile.save': () => ({ ok: true, written: true }) });
  let reads = 0;
  const disk = () => ({ delivered: { 'minecraft:oak_log': ++reads < 3 ? 0 : 64 } });
  const L = await savedOnDisk(dev, disk, (x) => x?.delivered['minecraft:oak_log'] === 64, c);
  assert.equal(L.delivered['minecraft:oak_log'], 64);
  assert.deepEqual(dev.sent.map((s) => s.type), ['dev.help', 'dev.stockpile.save']);
});

test('savedOnDisk on 0.74 (no dev.stockpile.save): the periodic save turned on, waited for until the file passes', async () => {
  const c = clock();
  const dev = fakeDev({ 'dev.help': help('dev.stockpile.ps', 'dev.stockpile.saved'), 'dev.stockpile.ps': () => ({ ok: true }), 'dev.stockpile.saved': () => ({ ok: true, unsaved: false }) });
  let n = 0;
  const L = await savedOnDisk(dev, () => ({ v: c.now() >= 6000 ? 'live' : 'old', n: ++n }), (x) => x?.v === 'live', c);
  assert.equal(L.v, 'live');
  assert.deepEqual(dev.sent[1], { type: 'dev.stockpile.ps', payload: { noPeriodic: false } });
  assert.ok(!dev.sent.some((s) => s.type === 'dev.stockpile.save'));
});

test('savedOnDisk never throws: a timeout, a failed save or a bad read returns the last read for the caller to check', async () => {
  const c = clock();
  const slow = fakeDev({ 'dev.help': help(), 'dev.stockpile.ps': () => ({ ok: true }), 'dev.stockpile.saved': () => ({ ok: true, unsaved: true }) });
  assert.deepEqual(await savedOnDisk(slow, () => ({ v: 'old' }), (x) => x?.v === 'live', { ...c, timeoutMs: 10_000 }), { v: 'old' });
  const broken = fakeDev({ 'dev.help': help('dev.stockpile.save'), 'dev.stockpile.save': () => ({ ok: false, error: 'not in a world' }) });
  assert.deepEqual(await savedOnDisk(broken, () => ({ v: 'old' }), () => true, c), { v: 'old' });
  const t = c.now();
  const unsettled = fakeDev({ 'dev.help': help('dev.stockpile.save'), 'dev.stockpile.save': () => ({ ok: true }) });
  assert.deepEqual(await savedOnDisk(unsettled, () => ({ v: 'old' }), (x) => x?.v === 'live', { ...c, settleMs: 4000 }), { v: 'old' });
  assert.equal(c.now() - t, 4000);
  assert.equal(await savedOnDisk(unsettled, () => { throw new Error('mid-write'); }, () => false, { ...c, settleMs: 0 }), null);
});
