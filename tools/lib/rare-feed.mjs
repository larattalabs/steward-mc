// (E2E-NAMED) when the survival mechanism flows' feeder gives up holding back the rare items (tools/e2e.mjs survivalMechFlow), kept apart for its unit tests.

/**
 * The feeder holds the gate's rare items (comparator, observer, ...; the drawbridge's redstone and repeaters) until the steward names them. A mechanism
 * asks for its bill only once its host building stands, and the lots are built one after another: in batch 096 the stockpile owed nothing but the rare
 * items from about 12:00:45 while the gatehouse, the last lot, was placed at 12:03:27 and built at 12:05:03. The old fallback (3 min after only the rare
 * items were owed) fed them before the mechanism ever waited, so it took its bill at once and nothing was named.
 *
 * Now the clock is the build's progress: the fallback fires after `quietMs` with only the rare items owed and nothing consumed from the stockpile (the
 * mechanism really waiting, or the build stuck), or `capMs` after only the rare items were first owed whatever the progress (C14: a bound either way).
 *
 * `st` is the feeder's state, updated in place ({} to start). Each poll passes `now`, whether only rare items are owed (`onlyRare`), the stockpile's
 * consumed units and whether the steward has named the items. Returns null while it holds, else why it feeds: 'named', 'quiet' or 'cap'.
 */
export function rareFeedDue(st, { now, onlyRare, consumed, named }, { quietMs = 180_000, capMs = 900_000 } = {}) {
  if (named) return 'named';
  if (!onlyRare) return null;
  st.onlyRareSince ??= now;
  if (st.quietSince == null || consumed > (st.consumed ?? -1)) {
    st.quietSince = now;
    st.consumed = consumed;
  }
  if (now - st.quietSince > quietMs) return 'quiet';
  if (now - st.onlyRareSince > capMs) return 'cap';
  return null;
}
