// (T4RD-S, S-1) module-measure's bar 7b on an Architect with T4R-D: the player's own blocks a growth's undo finds in its box (the fake placer's replants,
// cobblestone and torch) are no longer dropped as item entities. They are owed to their placer (`RemoveResult.owedParts`, reason `player_blocks`,
// recipient the placer's UUID: `dev.pt.uuid`), and the ground holds none. Pure judges, unit-tested in test/owed-cargo.test.mjs; the tool reads the state
// (the undo's answer, the ground, the placer's owed entries), never a log line.

/** Architect's owed reason for a player's own blocks found in a removal's box. */
export const PLAYER_BLOCKS = 'player_blocks';

const add = (a, b) => {
  const out = { ...a };
  for (const [k, v] of Object.entries(b ?? {})) if (Number(v) > 0) out[k] = (out[k] ?? 0) + Number(v);
  return out;
};
const nonZero = (m) => Object.fromEntries(Object.entries(m ?? {}).filter(([, v]) => Number(v) > 0));
const same = (a, b) => {
  const x = nonZero(a), y = nonZero(b);
  const keys = new Set([...Object.keys(x), ...Object.keys(y)]);
  for (const k of keys) if ((x[k] ?? 0) !== (y[k] ?? 0)) return false;
  return true;
};
const json = (o) => JSON.stringify(o ?? null);

/** The items an undo's answer (`dev.rv.undo`: `owedParts [{key, recipient, reason, items}]`) owes `placer` as its own blocks, summed. */
export function placerOwed(undo, placer) {
  let out = {};
  for (const p of undo?.owedParts ?? []) if (p && p.reason === PLAYER_BLOCKS && p.recipient === placer) out = add(out, p.items);
  return out;
}

/**
 * Bar 7b's cargo for one undo of a growth, judged by where the player's blocks went:
 * - `t4` (Architect advertises T4R-D's owed parts): owed to the placer exactly `want` (the sum over the growth's farms), and nothing on the ground at any
 *   of its farms. An undo answer with no `owedParts` at all fails (zero results is never a pass).
 * - before T4R-D: each farm's ground holds exactly its own `wantPerFarm` (dropped, never credited), as the bar always read it.
 * `ground`: per farm id, the items picked up there. -> { ok, why }.
 */
export function judgeCargo({ t4, undo, placer, ground, want, wantPerFarm, owedDelta }) {
  if (t4) {
    if (!placer) return { ok: false, why: 'the placer\'s UUID is unknown (dev.pt.uuid): the owed parts can\'t be read' };
    // the undo's own owedParts, else (S-1's alternative, A3) what the placer's owed entries grew by across this undo (dev.stockpile.owed: Architect's
    // durable owed store); neither is a FAIL, never a pass
    const parts = Array.isArray(undo?.owedParts);
    if (!parts && !owedDelta) return { ok: false, why: `the undo's answer has no owedParts (${json(Object.keys(undo ?? {}))}) and the placer's owed wasn't read` };
    const owed = parts ? placerOwed(undo, placer) : nonZero(owedDelta);
    const loose = Object.entries(ground ?? {}).filter(([, m]) => Object.keys(nonZero(m)).length > 0);
    if (loose.length) return { ok: false, why: `items on the ground at ${loose.map(([id, m]) => `${id} ${json(nonZero(m))}`).join(', ')}: a T4R-D undo drops none` };
    if (!same(owed, want)) return { ok: false, why: `owed to the placer ${json(nonZero(owed))}, want ${json(nonZero(want))}` };
    return { ok: true, why: `owed to the placer ${json(nonZero(owed))} (${parts ? 'the undo\'s owedParts' : 'the placer\'s owed store, across the undo'}), nothing on the ground` };
  }
  const off = Object.entries(wantPerFarm ?? {}).filter(([id, w]) => !same(ground?.[id], w));
  if (off.length) return { ok: false, why: off.map(([id, w]) => `${id}: on the ground ${json(nonZero(ground?.[id]))}, want ${json(nonZero(w))}`).join('; ') };
  return { ok: true, why: 'each farm\'s blocks dropped at it' };
}

/** What `after` holds more of than `before` (item maps): an owed store's growth across one step. */
export function grew(before, after) {
  const out = {};
  for (const [k, v] of Object.entries(after ?? {})) { const d = Number(v) - Number(before?.[k] ?? 0); if (d > 0) out[k] = d; }
  return out;
}

/** `dev.stockpile.owed {recipient}`'s answer (`owed [{items}]`) summed to one item map. */
export function owedTotal(answer) {
  let out = {};
  for (const e of answer?.owed ?? []) out = add(out, e?.items);
  return out;
}

/**
 * Bar 7b after a restart on T4R-D: nothing given twice. The placer's owed entries read the same as before the restart (`before`, `after`: owedTotal
 * maps) and not empty (the undo owed them something), and nothing lies on the ground. Before T4R-D: the ground only. -> { ok, why }.
 */
export function judgeRestart({ t4, before, after, stray }) {
  const loose = nonZero(stray);
  if (Object.keys(loose).length) return { ok: false, why: `items on the ground after the restart ${json(loose)}` };
  if (!t4) return { ok: true, why: 'nothing on the ground' };
  if (!Object.keys(nonZero(before)).length) return { ok: false, why: 'the placer was owed nothing before the restart: nothing to compare (zero results is never a pass)' };
  if (!same(before, after)) return { ok: false, why: `the placer's owed changed over the restart: ${json(nonZero(before))} -> ${json(nonZero(after))}` };
  return { ok: true, why: `the placer's owed unchanged ${json(nonZero(after))}` };
}
