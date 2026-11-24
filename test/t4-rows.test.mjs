// tools/lib/t4-rows.mjs: the T4 kill-point alias rows' gate and judges (T4S-PRE). On 0.83.0 (no removal_finished) every row is skipped, never red.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { t4Gate, T4_FEATURE, KILL_ALIASES, FKC0_HOOK, afterDecision, heldWords, reUndoAnswer, custodyAtHalt, custodyClear, fkc0Abort } from '../tools/lib/t4-rows.mjs';

const total = (m) => Object.values(m).reduce((a, b) => a + b, 0);

test('the rows run only when Architect advertises removal_finished', () => {
  assert.equal(T4_FEATURE, 'removal_finished');
  assert.equal(t4Gate({ features: ['regions', 'removal_finished'] }).run, true);
  // 0.83.0: skipped with a note, not red
  const off = t4Gate({ features: ['regions', 'removalStop', 'sitesLoadHealthy'] });
  assert.equal(off.run, false);
  assert.match(off.why, /no removal_finished/);
  // an older Steward or no answer: unknown, not run
  assert.equal(t4Gate(null).run, false);
  assert.equal(t4Gate({ givenPrograms: true }).run, false);
  assert.equal(t4Gate({ features: 'removal_finished' }).run, false, 'a string is not the list');
});

test('every alias Steward names is listed, and none maps to a PREPARED point', () => {
  assert.deepEqual(Object.keys(KILL_ALIASES).sort(), ['CS1', 'FKC1', 'FKC2', 'RVU1', 'RVU2']);
  assert.match(KILL_ALIASES.RVU1, /decided/);
  assert.doesNotMatch(KILL_ALIASES.RVU1, /PREPARED/);
  assert.match(KILL_ALIASES.RVU2, /after D/);
  assert.equal(FKC0_HOOK, 'dev.removal.failAfterCustody');
});

test('RVU1/RVU2 past the decision finish the removal; a revision read PLACED again means the point was before it', () => {
  assert.equal(afterDecision(['UNDOING', 'UNDOING', 'UNDONE']).ok, true);
  assert.equal(afterDecision(['undone']).ok, true);
  assert.equal(afterDecision(['UNDOING', 'PLACED', 'UNDONE']).ok, false);
  assert.equal(afterDecision(['UNDOING']).ok, false, 'never ended');
  assert.equal(afterDecision([]).ok, false);
});

const chat = (t) => `[15:29:56] [Render thread/INFO] (Minecraft) [System] [CHAT] ${t}`;

const cancelled = [/Cancelled /];
test('after the decision the steward never says a stop or Architect\'s text', () => {
  const want = [/Undo under way|Undone/];
  assert.equal(heldWords(chat('Undo under way: it finishes by itself when chunk 3, -4 loads. Nothing more to do.'), want).ok, true);
  assert.equal(heldWords(chat('Undone: the town\'s change is taken up'), want).ok, true);
  for (const bad of ['Undo stopped: x', 'could not be taken up just now', 'Run /steward undo s again once it is clear.', 'held(decided) tx-7: chunk 3,-4'])
    assert.equal(heldWords(`${chat('Undone: x')}\n${chat(bad)}`, want).ok, false, bad);
});

test('(T4R-PIN fix round, P2) the words judge needs the operation\'s own line: unrelated chat never passes it', () => {
  // the batch-100 review: an unrelated chat line passed, and logSince(0) supplied one
  assert.equal(heldWords(chat('Saved the game'), cancelled).ok, false);
  assert.equal(heldWords(chat('Saved the game')).ok, false, 'no expected line given is no proof');
  assert.equal(heldWords(chat('Saved the game'), []).ok, false);
  assert.match(heldWords(chat('Saved the game'), cancelled).why, /never said/);
  assert.equal(heldWords(`${chat('Saved the game')}\n${chat('Cancelled Supply Growth.')}`, cancelled).ok, true);
  // every expected line is needed
  assert.equal(heldWords(chat('Cancelled Supply Growth.'), [/Cancelled /, /Undone: /]).ok, false);
  assert.equal(heldWords(`${chat('Undone: the town\'s change is taken up')}\n${chat('Cancelled S.')}`, [/Cancelled /, /Undone: /]).ok, true);
});

test('the words are what was said in chat, never Architect\'s or Steward\'s own log lines', () => {
  // (T4R-PIN, the 367e40ce run) Architect's INFO line names its txn; nothing was said
  const log = ['[15:29:56] [Server thread/INFO] (architect) Txn event: tx-9 removal_finished (replayed) {"kind":"group"}',
    '[15:29:57] [Server thread/INFO] (steward_mc) set_1: the undo of group g5 is decided and held: held(decided) tx-9', chat('Undone: the town\'s change is taken up')].join('\n');
  const want = [/Undone: /];
  assert.equal(heldWords(log, want).ok, true);
  assert.equal(heldWords(`${log}\n${chat('Undo stopped: x')}`, want).ok, false);
  // nothing said at all is a FAIL (zero results is never a pass); a Steward log line naming the line is not chat
  assert.equal(heldWords('', want).ok, false);
  assert.equal(heldWords(log.split('\n').slice(0, 2).join('\n'), want).ok, false);
  assert.equal(heldWords('[15:29:57] [Server thread/INFO] (steward_mc) Undone: x', want).ok, false);
});

test('the unchanged re-undo: NOT_ALLOWED as today, or the replayed answer on a txn removal', () => {
  assert.deepEqual(reUndoAnswer({ refused: 'NOT_ALLOWED: the head is already below it' }, false), { ok: true, how: 'NOT_ALLOWED' });
  assert.deepEqual(reUndoAnswer({ refused: 'NOT_ALLOWED: x' }, true), { ok: true, how: 'NOT_ALLOWED' });
  assert.deepEqual(reUndoAnswer({ removed: true, replayed: true }, true), { ok: true, how: 'replayed' });
  // without T4 a removed answer is a second removal; with T4 only a replay passes
  assert.equal(reUndoAnswer({ removed: true, replayed: true }, false).ok, false);
  assert.equal(reUndoAnswer({ removed: true }, true).ok, false);
  assert.equal(reUndoAnswer({ error: 'timeout' }, true).ok, false, 'a transport error is not the no-op');
});

test('FKC1 custody on disk: the saved ledger, with T4 or without; missing evidence is a FAIL (T4R-PIN fix round, P2)', () => {
  const saved = { custody: { 'site:a@1': { 'minecraft:oak_slab': 4 } } };
  for (const txn of [false, true]) {
    const r = custodyAtHalt(saved, txn, total);
    assert.deepEqual([r.check, r.ok], [true, true]);
    // the batch-100 review: under T4 a missing saved custody returned {check: false, ok: true}, recorded as PASS
    for (const empty of [{ custody: {} }, {}, null]) {
      const e = custodyAtHalt(empty, txn, total);
      assert.equal(e.check, true, 'never unchecked');
      assert.equal(e.ok, false, `no evidence fails (txn ${txn})`);
    }
  }
  // a dev view stating durability per entry (T4c's shape) counts only when every entry is onDisk, and only with T4
  assert.equal(custodyAtHalt({ custody: {} }, true, total, [{ key: 'k1', onDisk: true }, { key: 'k2', onDisk: true }]).ok, true);
  assert.equal(custodyAtHalt({ custody: {} }, true, total, [{ key: 'k1', onDisk: true }, { key: 'k2', onDisk: false }]).ok, false);
  assert.equal(custodyAtHalt({ custody: {} }, true, total, [{ key: 'k1' }]).ok, false, 'onDisk missing is not true');
  assert.equal(custodyAtHalt({ custody: {} }, true, total, []).ok, false, 'an empty view proves nothing');
  assert.equal(custodyAtHalt({ custody: {} }, false, total, [{ key: 'k1', onDisk: true }]).ok, false);
});

test('(T4R-PIN fix round, P2) FKC0: the expected abort and the containers restored; a timeout or a null read FAILs', () => {
  const hook = { removed: false, blockers: ['the removal of group g1 was not started: TEST: dev.removal.failAfterCustody'] };
  const box = { 'minecraft:bone': 41, 'minecraft:flint': 41 };
  assert.equal(fkc0Abort(hook, box, { ...box }, total).ok, true);
  // the batch-100 review: a rejected or timed-out call passed because removed !== true
  assert.equal(fkc0Abort({ error: 'Error: timeout after 600000 ms' }, box, box, total).ok, false);
  assert.equal(fkc0Abort(null, box, box, total).ok, false);
  assert.equal(fkc0Abort({}, box, box, total).ok, false, 'no removed field is not the abort');
  assert.equal(fkc0Abort({ removed: true }, box, box, total).ok, false);
  assert.equal(fkc0Abort({ removed: false, blockers: ['blocked by a player'] }, box, box, total).ok, false, 'another stop is not the hook\'s abort');
  assert.equal(fkc0Abort(hook, box, null, total).ok, false, 'a null read after');
  assert.equal(fkc0Abort(hook, null, box, total).ok, false, 'a null read before');
  assert.equal(fkc0Abort(hook, box, { 'minecraft:bone': 41 }, total).ok, false, 'a kind missing');
  assert.equal(fkc0Abort(hook, box, { ...box, 'minecraft:bone': 40 }, total).ok, false, 'one short');
});

test('after a removal retires the custody dev view is empty', () => {
  assert.equal(custodyClear({ custody: {}, heldProvided: {}, pendingCredit: {} }).ok, true);
  assert.equal(custodyClear({}).ok, true);
  const open = custodyClear({ custody: { 'site:a@1': { 'minecraft:oak_slab': 4, txn: 'tx-7' } } });
  assert.equal(open.ok, false);
  assert.deepEqual(Object.keys(open.left), ['custody']);
});
