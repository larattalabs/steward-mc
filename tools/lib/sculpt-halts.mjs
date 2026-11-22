/**
 * (B092-LEFT-2, B092-LEFT's final GPT review) The composed-sculpt halt and stale checks as pure predicates, so each can be tested against the
 * review's counterexamples: the refund halt judged against the ledger saved at the halt (Architect's stockpiles.json), the water halt by water
 * work placed only after the restart, and the stale stop by every file of Architect's artifact store.
 */
import fs from 'node:fs';
import path from 'node:path';

const positive = (m) => Object.values(m ?? {}).some((n) => n > 0);
const sameItems = (a, b) => JSON.stringify(Object.entries(a ?? {}).filter(([, v]) => v).sort()) === JSON.stringify(Object.entries(b ?? {}).filter(([, v]) => v).sort());

/** The stockpile's refund lines in {@code text}: {key, again (an "already credited"), items}. */
export function creditsOf(text, owner) {
  const re = new RegExp(`Stockpile ${owner.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}: refund (\\S+) (returned \\{([^}]*)\\}|already credited)`, 'g');
  return [...String(text ?? '').matchAll(re)].map((m) => ({ key: m[1], again: m[3] === undefined && /already/.test(m[2]),
    items: Object.fromEntries([...(m[3] ?? '').matchAll(/([a-z0-9_.:]+)=(\d+)/g)].map(([, k, n]) => [k, Number(n)])) }));
}

/** Whether a credit gave anything back (an empty "returned {}" is no credit). */
export const creditedSome = (c) => !c.again && positive(c.items);

/**
 * The ledger of {@code owner}'s stockpile in Architect's saved stockpiles file ({@code <world>/architect/stockpiles.json}); throws when the
 * file can't be read or holds no such stockpile.
 */
export function savedLedger(file, owner) {
  const j = JSON.parse(fs.readFileSync(file, 'utf8'));
  const p = (j?.stockpiles ?? []).find((x) => x?.owner === owner);
  if (!p?.ledger) throw new Error(`no stockpile of ${owner} in ${path.basename(file)}`);
  return { credited: new Set(p.ledger.credited ?? []), paid: p.ledger.paid ?? {}, returned: p.ledger.returned ?? {}, refundOverflow: p.ledger.refundOverflow ?? {} };
}

/**
 * The refund halt, per paid site, against the ledger saved at the halt ({@code saved}: savedLedger's answer read after the client's process
 * exited, before the next one started). {@code paidBy}: each paid site's record before the undo; {@code before}: the credits logged before the
 * halt; {@code after}: those logged after the restart.
 * - Every paid key is in exactly one saved state: credited (its record spent) or still paid its whole record (else the file isn't this one).
 * - A key credited in the saved ledger was credited exactly its record before the halt, and nothing again after the restart ("already" only).
 * - A key not credited there is credited exactly its record once after the restart (a credit logged before the halt was lost with it).
 * Returns {off: [problems], durable: [keys credited in the saved ledger], owed: [keys not]}.
 */
export function refundOnce({ paidBy, saved, before, after }) {
  const off = [], durable = [], owed = [];
  for (const [k, want] of Object.entries(paidBy)) {
    const inCredited = saved.credited.has(k);
    const rec = saved.paid?.[k];
    if (inCredited && rec && positive(rec)) off.push(`${k} both credited and still paid ${JSON.stringify(rec)} in the saved ledger`);
    else if (!inCredited && !sameItems(rec, want)) off.push(`${k} neither credited nor paid its record in the saved ledger (paid ${JSON.stringify(rec ?? null)}, record ${JSON.stringify(want)})`);
    const pre = before.filter((c) => c.key === k && creditedSome(c));
    const post = after.filter((c) => c.key === k && !c.again);
    const postSome = post.filter((c) => positive(c.items));
    if (inCredited) {
      durable.push(k);
      if (pre.length !== 1) off.push(`${k} credited in the saved ledger with ${pre.length} credit(s) logged before the halt`);
      else if (!sameItems(pre[0].items, want)) off.push(`${k} credited ${JSON.stringify(pre[0].items)} before the halt, its record ${JSON.stringify(want)}`);
      if (post.length) off.push(`${k} saved credited at the halt and credited again after the restart ${JSON.stringify(post.map((c) => c.items))}`);
    } else {
      owed.push(k);
      if (after.some((c) => c.key === k && c.again)) off.push(`${k} not credited in the saved ledger, yet "already credited" after the restart`);
      if (postSome.length !== 1 || post.length !== 1) off.push(`${k} owed at the halt and credited ${post.length} time(s) after the restart (${postSome.length} with items)`);
      else if (!sameItems(postSome[0].items, want)) off.push(`${k} credited ${JSON.stringify(postSome[0].items)} after the restart, its record ${JSON.stringify(want)}`);
    }
  }
  for (const c of [...before, ...after]) if (!paidBy[c.key] && creditedSome(c)) off.push(`${c.key} credited ${JSON.stringify(c.items)} with no paid record`);
  return { off, durable, owed };
}

/**
 * The water halt: the fluids stage of group {@code group} (e.g. "g4") of region {@code region} (e.g. "rg4") had water left to write at the halt.
 * {@code preHalt}: the log from the stage's start to the halt; {@code after}: the log after the restart. Water was left when the stage didn't
 * finish nor its water get written before the halt, and after the restart, before the stage is placed, at least one terrain site is placed
 * whose id was never placed before the halt (a site placed again is a job replayed, not proof of work left). Returns {ok, why, fresh, replayed}.
 */
export function waterLeft({ preHalt, after, group, region }) {
  const done = new RegExp(`Group ${group}: stage fluids placing -> placed`);
  const written = region ? new RegExp(`Region ${region}: fluids written`) : null;
  const site = /Placed cell site (c\d+) \(architect:terrain\)/g;
  if (!group) return { ok: false, why: 'no fluids group named' };
  if (!region) return { ok: false, why: 'no region named' };
  if (done.test(preHalt)) return { ok: false, why: `group ${group}'s fluids stage was placed before the halt` };
  if (written.test(preHalt)) return { ok: false, why: `region ${region}'s fluids were written before the halt` };
  const end = String(after).search(done);
  if (end < 0) return { ok: false, why: `group ${group}'s fluids stage never placed after the restart` };
  const pre = new Set([...String(preHalt).matchAll(site)].map((m) => m[1]));
  const post = [...String(after).slice(0, end).matchAll(site)].map((m) => m[1]);
  const fresh = post.filter((id) => !pre.has(id));
  const replayed = post.filter((id) => pre.has(id));
  if (!fresh.length) return { ok: false, why: `no terrain site placed after the restart that wasn't placed before the halt (${pre.size} before, ${post.length} after, all replays)`, fresh, replayed };
  return { ok: true, why: `${pre.size} terrain site(s) placed before the halt, ${fresh.length} new after the restart (${fresh.slice(0, 4).join(', ')}), ${replayed.length} replayed`, fresh, replayed };
}

/**
 * A snapshot of Architect's artifact store: every file with its size, mtime (ns) and inode (a write is a temp file then a rename: a new inode).
 * Throws when the folder can't be read; a file gone between the listing and its stat is left out (a sweep's deletion, seen as gone).
 */
export function artifactSnapshot(dir) {
  const out = {};
  for (const n of fs.readdirSync(dir)) {
    try {
      const s = fs.statSync(path.join(dir, n), { bigint: true });
      out[n] = `${s.size}:${s.mtimeNs}:${s.ino}`;
    } catch (e) {
      if (e?.code !== 'ENOENT') throw e;
    }
  }
  return out;
}

/**
 * The changes between two artifact-store snapshots that aren't the approved artifact {@code sha} let go: its pins file rewritten or removed,
 * its body removed (swept) and its tombstone written. Any other file added, removed or changed (another artifact made, pinned, unpinned or
 * swept, a meta written) is listed. Returns [{name, how}].
 */
export function artifactChanges(art0, art1, sha) {
  const allowed = (n, how) => n === `${sha}.pins.json` || (n === `${sha}.json` && how === 'removed') || (n === `${sha}.removed.json` && how !== 'removed');
  const out = [];
  for (const n of new Set([...Object.keys(art0), ...Object.keys(art1)])) {
    const how = !(n in art0) ? 'added' : !(n in art1) ? 'removed' : art0[n] !== art1[n] ? 'changed' : null;
    if (how && !allowed(n, how)) out.push({ name: n, how });
  }
  return out.sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * The hall's lot cap: {@code c} the dev.bc.cap answer for its lot, {@code region0} the region's own; both must be answers (a refusal is never
 * "no region maxBill"), the lot capped with no draw past it and no overshoot, and the region without a maxBill. Returns {ok, over}.
 */
export function hallCap(c, region0) {
  if (!c || c.ok === false || c.refused || !region0 || region0.ok === false || region0.refused) return { ok: false, over: [] };
  const over = Object.entries(c.lotCap?.drawn ?? {}).filter(([k, v]) => v > (c.lotCap.maxBill?.[k] ?? 0));
  const ok = !!c.lotCap && Object.keys(c.lotCap.maxBill ?? {}).length > 0 && !over.length && !Object.keys(c.lotCap.overshoot ?? {}).length
    && 'maxBill' in region0 && !Object.keys(region0.maxBill ?? {}).length;
  return { ok, over };
}

/** A dev.bc.cap answer as a refusal ({refused}) when the call failed ({ok: false}, none) or lacks {@code fields}; else the answer. */
export function capAnswer(r, fields = []) {
  if (!r || r.ok === false || r.refused) return { refused: r?.refused ?? `dev.bc.cap failed: ${r?.error ?? 'no answer'}` };
  const missing = fields.filter((k) => !(k in r) || (k !== 'maxBill' && (r[k] === null || typeof r[k] !== 'object')));
  return missing.length ? { refused: `dev.bc.cap answer without ${missing.join(', ')}: ${JSON.stringify(r).slice(0, 200)}` } : r;
}
