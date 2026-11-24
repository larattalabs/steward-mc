// (T4S-PRE) The e2e's and the hall tool's rows for Architect T4's removal transactions (T4 contract, "Changes from Steward's review" 1, 2, 4), kept
// apart so their gating and judging have unit tests (test/t4-rows.test.mjs). Every row here runs only when Architect advertises `removal_finished`
// (T4c); on an Architect without it (0.83.0) each is skipped with a note, never red.

/** Architect's feature for T4c's removal transactions and RemovalFinished. */
export const T4_FEATURE = 'removal_finished';

/**
 * Whether a T4 row runs on this Architect: `env` is Steward's `/steward dev module {op:'env'}` answer (its `features`, slice T4S-PRE). Unknown (no
 * answer, an older Steward with no `features`): not run. -> { run, why }.
 */
export function t4Gate(env, feature = T4_FEATURE) {
  const f = env?.features;
  if (!Array.isArray(f)) return { run: false, why: `Architect's features unknown (no env answer): the ${feature} rows wait` };
  if (!f.includes(feature)) return { run: false, why: `this Architect has no ${feature} (T4c): the row waits for the pin that carries it` };
  return { run: true, why: '' };
}

/**
 * T4 change 1: the kill points Steward names and what each must map to on a txn removal. Each alias logs `kill point <ALIAS> reached` under its
 * own name. RVU1 is after the decision (never PREPARED: a restart there must finish the removal, not abort it).
 */
export const KILL_ALIASES = {
  RVU1: 'TX:removal.group:decided (after D: the journal undo committed and the decision recorded)',
  RVU2: "after the region's drain, still after D",
  FKC1: "after custody.take's B effects and their barrier (PREPARED_DONE)",
  FKC2: "after a member's W DONE, its custody credit not booked",
  CS1: "inside the barrier publishing a member's L credit, after the chunks, before the ledger",
};

/** The FKC0 hook (T4 change 3: a TEST hook aborting a group or stage removal right after its custody B effects). */
export const FKC0_HOOK = 'dev.removal.failAfterCustody';

/**
 * RVU1/RVU2 on a txn removal (T4 change 1): the revision's states read after the restart, oldest first. A point after the decision never reads
 * PLACED again (an aborted removal would put the growth back), and the removal ends UNDONE. -> { ok, why }.
 */
export function afterDecision(states) {
  const seen = (states ?? []).filter(Boolean).map((s) => String(s).toUpperCase());
  if (seen.includes('PLACED')) return { ok: false, why: `the revision read PLACED after the restart (${seen.join(' > ')}): the kill point was before the decision` };
  if (seen[seen.length - 1] !== 'UNDONE') return { ok: false, why: `never UNDONE (${seen.join(' > ') || 'no read'})` };
  return { ok: true, why: seen.join(' > ') };
}

/**
 * What a held, decided undo said, or a stop it must never say after a restart past the decision. -> { ok, why }.
 * (T4R-PIN fix round, batch-100 P2) `text` is the operation's own window (the log from the relaunch after its kill point, never `logSince(0)`), and
 * `expect` the Steward lines that operation must say there (each a RegExp over the chat lines): a window without them is no proof and FAILs, so an
 * unrelated chat line (`Saved the game`) never passes it.
 */
export function heldWords(text, expect = []) {
  // (T4R-PIN) only what was said to the player (the client log's chat lines): Architect's and Steward's own log lines name txns (`Txn event:
  // tx-9 ...`) and are never said
  const said = String(text ?? '').split('\n').filter((l) => l.includes('[CHAT]'));
  // (GPT P2-7) nothing said at all is no proof: the client's chat wasn't captured
  if (!said.length) return { ok: false, why: 'no chat lines captured after the decision: nothing to judge' };
  const t = said.join('\n');
  const stop = /Undo stopped|could not be taken up|again once it is clear|held\(|tx-\d+/.exec(t);
  if (stop) return { ok: false, why: `a stop or Architect's text after the decision: "${stop[0]}"` };
  const want = (Array.isArray(expect) ? expect : [expect]).filter((r) => r instanceof RegExp);
  if (!want.length) return { ok: false, why: 'no expected Steward line given: nothing proves the window holds this operation' };
  const missing = want.filter((r) => !r.test(t));
  if (missing.length) return { ok: false, why: `the operation's own line was never said (${missing.map(String).join(', ')})` };
  return { ok: true, why: `said ${want.map((r) => `"${r.exec(t)[0].slice(0, 80)}"`).join(', ')}; no stop` };
}

/**
 * The hall tool's unchanged re-undo after the removal ended (T4 change 4): NOT_ALLOWED (as today), or, on a txn removal while Architect's result cache
 * holds it, the original answer replayed (removed, `replayed` true). Either way nothing more may come back (the caller's sums). -> { ok, how }.
 */
export function reUndoAnswer(retry, txn) {
  const text = String(retry?.refused ?? retry?.message ?? retry?.error ?? '');
  if (retry?.removed !== true && /NOT_ALLOWED/.test(text)) return { ok: true, how: 'NOT_ALLOWED' };
  if (txn && retry?.removed === true && retry?.replayed === true) return { ok: true, how: 'replayed' };
  return { ok: false, how: retry?.removed === true ? `removed again${txn ? ' (not marked replayed)' : ''}` : `answered ${text.slice(0, 120) || 'nothing'}` };
}

/**
 * The FKC1 "custody on disk at the halt" check (T4 change 2). -> { check, ok, note }; `check` is always true (T4R-PIN fix round, batch-100 P2: a missing
 * proof FAILs, never passes unchecked). Evidence is read from disk after the JVM halt: the saved ledger's `custody` (Architect 0.91.0 keeps it there under
 * T4 too: 164 items at the FKC1 halt in the 0.91.0 measurement). An Architect whose dev view states durability per entry (`custody: [{key, items,
 * onDisk}]`, the T4c shape Architect stated 11-19; not in 0.91.0) also counts when every entry is onDisk. Neither: FAIL.
 */
export function custodyAtHalt(savedLedger, txn, total, devCustody = null) {
  const n = total(Object.values(savedLedger?.custody ?? {}).reduce((a, m) => {
    for (const [k, v] of Object.entries(m ?? {})) if (typeof v === 'number') a[k] = (a[k] ?? 0) + v;
    return a;
  }, {}));
  if (n > 0) return { check: true, ok: true, note: `${n} in the saved ledger's custody` };
  const view = Array.isArray(devCustody) ? devCustody : [];
  if (txn && view.length && view.every((e) => e?.onDisk === true)) return { check: true, ok: true, note: `${view.length} custody entr${view.length === 1 ? 'y' : 'ies'} onDisk in the dev view` };
  const why = txn && view.length ? `the dev view's custody not all onDisk (${view.filter((e) => e?.onDisk !== true).map((e) => e?.key ?? '?').join(', ')})` : 'no durable custody evidence';
  return { check: true, ok: false, note: `nothing in the saved ledger's custody at the halt; ${why}` };
}

/**
 * (T4R-PIN fix round, batch-100 P2) FKC0's abort, judged on Architect's answer and the world: the undo answered (no transport error, no timeout),
 * not removed, its blockers naming the failAfterCustody hook; the hall's containers read before arming and after the abort, both read (a null read
 * FAILs), and the same, item for item (the custody given back). -> { ok, why }.
 */
export function fkc0Abort(u, before, after, total) {
  if (!u || u.error) return { ok: false, why: `the undo got no answer (${String(u?.error ?? 'none').slice(0, 120)}): a timeout or transport failure is not the abort` };
  if (u.removed !== false) return { ok: false, why: `answered removed ${u.removed}: not the aborted removal` };
  const blockers = (u.blockers ?? []).join('; ');
  if (!/failAfterCustody/.test(blockers)) return { ok: false, why: `not the hook's abort (blockers: ${blockers.slice(0, 160) || 'none'})` };
  if (before == null || after == null) return { ok: false, why: `the containers were not read (${before == null ? 'before' : 'after'}): nothing proves them restored` };
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  const off = [...keys].filter((k) => (before[k] ?? 0) !== (after[k] ?? 0));
  if (off.length) return { ok: false, why: `the containers changed: ${off.slice(0, 4).map((k) => `${k} ${before[k] ?? 0} > ${after[k] ?? 0}`).join(', ')}` };
  return { ok: true, why: `aborted by the hook; the containers as before (${total(before)} items)` };
}

/**
 * The custody dev view after a removal retired (T4 change 2): `custody`, `heldProvided` and `pendingCredit` all empty; while a txn is open its custody
 * shows with `txn` beside it. -> { ok, left } (`left`: what still shows).
 */
export function custodyClear(ledger) {
  const left = {};
  for (const k of ['custody', 'heldProvided', 'pendingCredit']) if (Object.keys(ledger?.[k] ?? {}).length) left[k] = ledger[k];
  return { ok: !Object.keys(left).length, left };
}
