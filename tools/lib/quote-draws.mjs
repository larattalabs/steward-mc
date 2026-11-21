/**
 * (B092-FIX, batch 092 GPT review P2) The quote of the realised artifact against what the stockpile drew, item by item over both sides: an item drawn
 * but not quoted (an extra draw of anything, dirt or stone included), one quoted but drawn short or not at all, or one drawn more than quoted is a
 * mismatch. The quote is the structured one (Architect's quoteApproved through dev.region.quote: the bom of the final artifact and its lot entries),
 * never the abbreviated bill in chat.
 */

/** Positive counts only (a 0 or a negative is no draw and no quote). */
function positive(m) {
  return Object.fromEntries(Object.entries(m ?? {}).filter(([, v]) => Number(v) > 0).map(([k, v]) => [k, Number(v)]));
}

/** What the stockpile drew between two ledgers: consumed after minus consumed before, item by item (positive only). */
export function drawsBetween(before, after) {
  const a = before?.consumed ?? {}, b = after?.consumed ?? {};
  return positive(Object.fromEntries(Object.keys(b).map((k) => [k, (b[k] ?? 0) - (a[k] ?? 0)])));
}

/** The mismatches between a quote's bom and the draws, sorted by item ('<item> drew <n>/quoted <m>'); [] when they agree exactly. */
export function quoteOff(bom, drew) {
  const q = positive(bom), d = positive(drew);
  return [...new Set([...Object.keys(q), ...Object.keys(d)])].sort()
    .filter((k) => (d[k] ?? 0) !== (q[k] ?? 0))
    .map((k) => `${k} drew ${d[k] ?? 0}/quoted ${q[k] ?? 0}`);
}
