// tools/lib/t4-rows.mjs: the T4 kill-point alias rows' gate and judges (T4S-PRE). On 0.83.0 (no removal_finished) every row is skipped, never red.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { t4Gate, T4_FEATURE, KILL_ALIASES, FKC0_HOOK, afterDecision, heldWords, reUndoAnswer, custodyAtHalt, custodyClear } from '../tools/lib/t4-rows.mjs';

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

test('after the decision the steward never says a stop or Architect\'s text', () => {
  assert.equal(heldWords(chat('Undo under way: it finishes by itself when chunk 3, -4 loads. Nothing more to do.')).ok, true);
  assert.equal(heldWords(chat('Undone: the town\'s change is taken up')).ok, true);
  for (const bad of ['Undo stopped: x', 'could not be taken up just now', 'Run /steward undo s again once it is clear.', 'held(decided) tx-7: chunk 3,-4'])
    assert.equal(heldWords(chat(bad)).ok, false, bad);
});

test('the words are what was said in chat, never Architect\'s or Steward\'s own log lines', () => {
  // (T4R-PIN, the 367e40ce run) Architect's INFO line names its txn; nothing was said
  const log = ['[15:29:56] [Server thread/INFO] (architect) Txn event: tx-9 removal_finished (replayed) {"kind":"group"}',
    '[15:29:57] [Server thread/INFO] (steward_mc) set_1: the undo of group g5 is decided and held: held(decided) tx-9', chat('Undone: the town\'s change is taken up')].join('\n');
  assert.equal(heldWords(log).ok, true);
  assert.equal(heldWords(`${log}\n${chat('Undo stopped: x')}`).ok, false);
  // nothing said at all is a FAIL (zero results is never a pass)
  assert.equal(heldWords('').ok, false);
  assert.equal(heldWords(log.split('\n').slice(0, 2).join('\n')).ok, false);
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

test('FKC1 custody on disk: the saved ledger without T4; the dev view with it', () => {
  const saved = { custody: { 'site:a@1': { 'minecraft:oak_slab': 4 } } };
  assert.deepEqual(custodyAtHalt(saved, false, total).ok, true);
  assert.equal(custodyAtHalt({ custody: {} }, false, total).ok, false, 'without T4 an empty saved custody fails as today');
  const t4 = custodyAtHalt({ custody: {} }, true, total);
  assert.equal(t4.check, false);
  assert.equal(t4.ok, true);
  assert.match(t4.note, /txn entry/);
  assert.equal(custodyAtHalt(saved, true, total).check, true);
});

test('after a removal retires the custody dev view is empty', () => {
  assert.equal(custodyClear({ custody: {}, heldProvided: {}, pendingCredit: {} }).ok, true);
  assert.equal(custodyClear({}).ok, true);
  const open = custodyClear({ custody: { 'site:a@1': { 'minecraft:oak_slab': 4, txn: 'tx-7' } } });
  assert.equal(open.ok, false);
  assert.deepEqual(Object.keys(open.left), ['custody']);
});
