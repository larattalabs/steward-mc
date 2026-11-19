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

/** What a held, decided undo said, or a stop it must never say after a restart past the decision. -> { ok, why }. */
export function heldWords(text) {
  const t = String(text ?? '');
  const stop = /Undo stopped|could not be taken up|again once it is clear|held\(|tx-\d+/.exec(t);
  if (stop) return { ok: false, why: `a stop or Architect's text after the decision: "${stop[0]}"` };
  return { ok: true, why: /Undo under way/.test(t) ? 'said as under way' : 'no stop said' };
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
 * The FKC1 "custody on disk at the halt" check (T4 change 2). Without T4 it reads the saved stockpile ledger's `custody`. With T4 custody lives in the txn
 * entry: the saved ledger may hold none, so the check is left to the dev view. TODO(T4c), shape stated by Architect 11-19: `dev.stockpile.ledger`
 * gains `custody: [{key, items, txnId, phase, savedGen, onDisk}]` and a top-level `confirmedGen` (onDisk = savedGen <= confirmedGen); assert every
 * entry's onDisk at the FKC1 halt. -> { check, ok, note }.
 */
export function custodyAtHalt(savedLedger, txn, total) {
  const n = total(Object.values(savedLedger?.custody ?? {}).reduce((a, m) => {
    for (const [k, v] of Object.entries(m ?? {})) if (typeof v === 'number') a[k] = (a[k] ?? 0) + v;
    return a;
  }, {}));
  if (!txn) return { check: true, ok: n > 0, note: `${n} in the saved ledger's custody` };
  if (n > 0) return { check: true, ok: true, note: `${n} in the saved ledger's custody` };
  return { check: false, ok: true, note: "custody is in the txn entry under T4 (change 2), not the saved ledger: checked through the dev view" };
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
