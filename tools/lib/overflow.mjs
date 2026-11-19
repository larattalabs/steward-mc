/**
 * (CHECK-079, Architect 0.79.0 T0) Every give-back enters the stock, past capacity if need be: nothing drops at the crate and a stockpile's
 * refundOverflow never grows again (the counts from before 0.79.0 are kept, so only a growth between two reads counts). The refund and undo checks
 * still sum refundOverflow into what came back, and fail loudly on any growth over the same pair of ledgers.
 */

/** What refundOverflow grew by from ledger `before` to ledger `after`, item by item ({} when it didn't). A shrink is not a growth. */
export function overflowGrowth(before, after) {
  const a = before?.refundOverflow ?? {}, b = after?.refundOverflow ?? {};
  const out = {};
  for (const [k, n] of Object.entries(b)) {
    const d = (n ?? 0) - (a[k] ?? 0);
    if (d > 0) out[k] = d;
  }
  return out;
}

/** The growth in words for a failure ('' when refundOverflow did not grow). */
export function overflowGrew(before, after) {
  const g = overflowGrowth(before, after);
  const keys = Object.keys(g);
  return keys.length ? `refundOverflow grew (every give-back should enter the stock since Architect 0.79.0): ${keys.map((k) => `${k} +${g[k]}`).join(', ')}` : '';
}
