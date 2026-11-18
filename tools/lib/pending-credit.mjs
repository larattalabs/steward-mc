// (DR2-DOUBLE) the one ledger change a restart may make on Architect 0.78.0, for module-measure-hall's "nothing given twice" check.
/**
 * (DR2-DOUBLE, Architect 0.78.0; drop at the v0.79.0 pin, whose fix/double-credit stops the leak) {@code before} with the pendingCredit records
 * whose items are empty and which {@code after} no longer has taken out (the key too when nothing is left and {@code after} has none): {ledger,
 * dropped}. A record with items, or one {@code after} still has, stays, so it is compared as before. Pure; the ledger is not changed.
 */
export function emptyPendingGone(before, after) {
  const pc = before?.pendingCredit;
  if (!pc || typeof pc !== 'object') return { ledger: before, dropped: [] };
  const kept = {}, dropped = [];
  for (const [k, v] of Object.entries(pc)) {
    // empty: items {} and nothing besides items and its timestamp (a record carrying any other member, a paid share say, is compared as before)
    const empty = v && typeof v === 'object' && v.items && typeof v.items === 'object' && Object.keys(v.items).length === 0
      && Object.keys(v).every((m) => m === 'items' || m === 'at');
    if (empty && !(after?.pendingCredit && k in after.pendingCredit)) dropped.push(k);
    else kept[k] = v;
  }
  if (!dropped.length) return { ledger: before, dropped };
  const ledger = { ...before };
  if (Object.keys(kept).length || (after && 'pendingCredit' in after)) ledger.pendingCredit = kept;
  else delete ledger.pendingCredit;
  return { ledger, dropped };
}
