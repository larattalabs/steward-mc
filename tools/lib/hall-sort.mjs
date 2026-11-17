// The e2e's sorting-hall check (batch-074 GPT review P3): a short mix fed into a sorting hall's input chest must leave the input and the line
// drained, each slot's chest and output hopper holding exactly its own item fed, the overflow exactly the rest, and every filter hopper as it was
// (its 41 + 4 fill). Before, the flow checked only that the hall's total was conserved, which holds when nothing sorts. The judge is the
// measurement's (tools/module-measure-hall.mjs sortRun: "a slot is its chest and its D"), on the cells the hall declares (dev hallCargo `cells`).
const keyOf = (at) => at.join(',');

/** One container's stacks, key -> count, from a dev `containers` response; null when the cell holds no container (or wasn't loaded). */
export function heldAt(resp, at) {
  const c = (resp?.containers ?? []).find((x) => keyOf(x.at) === keyOf(at));
  if (!c) return null;
  const out = {};
  for (const [, k, n] of c.slots ?? []) out[k] = (out[k] ?? 0) + n;
  return out;
}

const minus = (a, b) => {
  const out = {};
  for (const k of new Set([...Object.keys(a ?? {}), ...Object.keys(b ?? {})])) {
    const d = (a?.[k] ?? 0) - (b?.[k] ?? 0);
    if (d) out[k] = d;
  }
  return out;
};
const plus = (a, b) => minus(a, minus({}, b));
const same = (a, b) => Object.keys(minus(a, b)).length === 0;
const only = (m, keys) => Object.fromEntries(Object.entries(m ?? {}).filter(([k]) => keys.includes(k)));
const json = (o) => JSON.stringify(o);

/**
 * Judges the mix. cells: the hall's declared cells [{at, kind, slot}] (kind INPUT, OVERFLOW, LINE, FILTER, OUTPUT, SLOT, BULK; slot 1-based);
 * before/after: dev `containers` responses over the hall's box; fed: {key: count} put into the input; items: the slots' filter keys in slot order.
 * -> { ok, why: [...], drained } (drained: the input and the line hold none of what was fed: the poll's settle test).
 */
export function judgeSort({ cells, before, after, fed, items }) {
  const why = [];
  const keys = Object.keys(fed);
  const of = (kind) => (cells ?? []).filter((c) => c.kind === kind);
  const at = (resp, c) => heldAt(resp, c.at);
  const input = of('INPUT'), overflow = of('OVERFLOW'), line = of('LINE');
  if (input.length !== 1 || overflow.length !== 1 || of('SLOT').length !== items.length) {
    return { ok: false, drained: false, why: [`the hall's cells aren't a sorting hall of ${items.length} slots (input ${input.length}, overflow ${overflow.length}, slots ${of('SLOT').length})`] };
  }
  const missing = (cells ?? []).filter((c) => at(after, c) === null).map((c) => `${c.kind}${c.slot ? ` ${c.slot}` : ''} ${keyOf(c.at)}`);
  if (missing.length) why.push(`no container at ${missing.slice(0, 4).join('; ')}`);
  const inLeft = only(at(after, input[0]), keys);
  const lineLeft = line.reduce((m, c) => plus(m, only(at(after, c), keys)), {});
  const drained = !Object.keys(inLeft).length && !Object.keys(lineLeft).length;
  if (Object.keys(inLeft).length) why.push(`the input chest still holds ${json(inLeft)}`);
  if (Object.keys(lineLeft).length) why.push(`the line still holds ${json(lineLeft)}`);
  let rest = { ...fed };
  items.forEach((item, u) => {
    const slot = u + 1;
    const chest = of('SLOT').find((c) => c.slot === slot), out = of('OUTPUT').find((c) => c.slot === slot);
    const got = plus(minus(at(after, chest), at(before, chest)), out ? minus(at(after, out), at(before, out)) : {});
    const want = fed[item] ? { [item]: fed[item] } : {};
    if (!same(got, want)) why.push(`slot ${slot} (${item}) got ${json(got)}, want ${json(want)}`);
    rest = minus(rest, want);
  });
  const gotOver = minus(at(after, overflow[0]), at(before, overflow[0]));
  if (!same(gotOver, rest)) why.push(`overflow got ${json(gotOver)}, want ${json(rest)}`);
  for (const f of of('FILTER')) if (!same(at(after, f), at(before, f))) why.push(`filter ${f.slot} moved: ${json(at(before, f))} -> ${json(at(after, f))}`);
  return { ok: why.length === 0, drained, why };
}
