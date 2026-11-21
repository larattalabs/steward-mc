/**
 * (B092-FIX, batch 092 GPT review P2) The quote of the realised artifact against what the stockpile drew, item by item over both sides. The quote is
 * the structured one (Architect's quoteApproved of exactly the realise sent: its artifact and its lot entries, piece by piece), never the abbreviated
 * bill in chat.
 *
 * Architect's quote of a composed town is inexact in known ways (its notes): the region's paid cells are an upper bound (a cell of natural terrain
 * that already holds its block is skipped), and lots are measured over the land before the plan's tiles write. So:
 * - an item drawn that the quote never names (an extra draw of anything, dirt or stone included) fails;
 * - an item drawn past its quoted total fails (a doubled debit, a draw the quote doesn't cover);
 * - each of `exact` (the river's lining) must be drawn as quoted, less at most the cells the notes say may already stand;
 * - any other item drawn short of its quote is reported, not failed (the quote's own upper bound).
 * When the quote says it is exact, every item must be drawn exactly as quoted.
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

/** A quote's pieces ({piece: {item: n}}) summed into its bill ({item: n}). */
export function partsTotal(bomParts) {
  const out = {};
  for (const piece of Object.values(bomParts ?? {})) for (const [k, n] of Object.entries(piece ?? {})) out[k] = (out[k] ?? 0) + Number(n);
  return positive(out);
}

/**
 * The cells the quote's notes say may already stand (Architect's "N paid cell(s) of natural terrain may already stand in the land (river (658
 * moss_block); town (1 dandelion, 2 poppy))"), by item: what a realise may skip, so draw less than quoted. {} when no note says so.
 */
export function mayStand(notes) {
  const out = {};
  for (const note of [notes ?? []].flat()) {
    const m = /may already stand in the land \((.*?)\)\):/.exec(String(note));
    if (!m) continue;
    for (const [, n, id] of m[1].matchAll(/(\d+) ([a-z0-9_.:]+)/g)) {
      const k = id.includes(':') ? id : `minecraft:${id}`;
      out[k] = (out[k] ?? 0) + Number(n);
    }
  }
  return out;
}

/** The mismatches between a quote's bom and the draws, both ways, sorted by item ('<item> drew <n>/quoted <m>'); [] when they agree exactly. */
export function quoteOff(bom, drew) {
  const q = positive(bom), d = positive(drew);
  return [...new Set([...Object.keys(q), ...Object.keys(d)])].sort()
    .filter((k) => (d[k] ?? 0) !== (q[k] ?? 0))
    .map((k) => `${k} drew ${d[k] ?? 0}/quoted ${q[k] ?? 0}`);
}

/**
 * The quote judged against the draws (see the file's head): `quote` {bom, exact, notes}, `drew` {item: n}, `exact` the items to be drawn as quoted
 * (less what may already stand). -> {fails: [words], short: [words]} (short: other items drawn under their quote, reported only).
 */
export function judgeQuote(quote, drew, exact = []) {
  const q = positive(quote?.bom), d = positive(drew), standing = mayStand(quote?.notes);
  if (quote?.exact === true) return { fails: quoteOff(q, d), short: [] };
  const fails = [], short = [];
  for (const k of [...new Set([...Object.keys(q), ...Object.keys(d)])].sort()) {
    const qn = q[k] ?? 0, dn = d[k] ?? 0;
    if (dn > 0 && qn === 0) fails.push(`${k} drew ${dn}, never quoted`);
    else if (dn > qn) fails.push(`${k} drew ${dn}/quoted ${qn}`);
    else if (exact.includes(k) && dn < qn - (standing[k] ?? 0)) fails.push(`${k} drew ${dn}/quoted ${qn} (at most ${standing[k] ?? 0} may already stand)`);
    else if (dn < qn) short.push(`${k} ${dn}/${qn}`);
  }
  return { fails, short };
}
