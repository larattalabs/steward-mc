// (E2E-QUIT-GRACE, Architect 11-22) A dev.quit right after an undo or a removal can cut Architect's removal short: in batch 094 rg6's group g5 was
// "Removing group g5 (17 site(s))" at 07:08:15 and the client quit at 07:08:18, so the kept world looked like a stalled removal. tools/e2e.mjs
// quit() waits here, bounded, for the removals pending in the client's session first. This is evidence hygiene, not a check: a removal still
// pending at the bound is a warning in summary.json ("warnings"), never a FAIL, and the client quits anyway. Kept apart for its unit tests.

/** The bound (ms) quit() waits for pending removals. */
export const QUIT_GRACE_MS = 60_000;

/**
 * The site groups whose removal began in `logText` (this client session's game log) and hasn't ended there: a "Removing group gN (...)" or its
 * resume ("Removing group gN: resuming ...") with no later "Removal done|stopped of group gN" or "Removing group gN failed". A stage's undo ("Undo of
 * stage ... of group gN") is not a group removal. Street towns are bare site groups with no region, so dev.region.list alone can't see them.
 * -> [group ids], in the order they began.
 */
export function openGroups(logText) {
  const open = new Map();
  for (const m of String(logText ?? '').matchAll(/Removing group (g\d+)( failed\b)?|\bRemoval (?:done|stopped) of group (g\d+)/g)) {
    if (m[1] && !m[2]) open.set(m[1], true);
    else open.delete(m[1] ?? m[3]);
  }
  return [...open.keys()];
}

/**
 * What is mid-removal: the regions in REMOVING (from a dev.region.list answer's `regions`; null when it couldn't be read) and the open groups
 * of the log. A region's own group is named once, as the region. -> [{kind: 'region'|'group', id, group?}].
 */
export function pendingRemovals({ regions = null, logText = '' } = {}) {
  const out = [];
  const byRegion = new Set();
  for (const v of regions ?? []) {
    if (v?.state !== 'REMOVING') continue;
    const group = v.groupId ?? v.group ?? null;
    if (group) byRegion.add(group);
    out.push({ kind: 'region', id: String(v.id ?? v.region ?? '?'), ...(group ? { group } : {}) });
  }
  for (const g of openGroups(logText)) if (!byRegion.has(g)) out.push({ kind: 'group', id: g });
  return out;
}

/**
 * One read for graceBeforeQuit: the pending list, or null only when the client is gone (`clientGone()`). A region list that fails, times out or is
 * refused (the server thread busy with a large removal is when that happens) reads as no region state, and the log's open groups still count: a
 * region's removal logs its "Removing group" line too.
 */
export async function readPending({ listRegions, logText, clientGone = () => false }) {
  if (clientGone()) return null;
  let regions = null;
  try {
    const rl = await listRegions();
    if (rl && rl.ok !== false && Array.isArray(rl.regions)) regions = rl.regions;
  } catch {
    // unreachable state: the log alone decides
  }
  if (clientGone()) return null;
  return pendingRemovals({ regions, logText: typeof logText === 'function' ? logText() : logText });
}

export const nameOf = (p) => p.kind === 'region' ? `region ${p.id}${p.group ? ` (group ${p.group})` : ''}` : `group ${p.id}`;

/**
 * Waits, at most `ms`, until `read()` (-> pendingRemovals' list, or null/throws when it can't be read) says nothing is pending.
 * -> {outcome: 'none'|'done'|'timeout'|'unreadable', waitedMs, pending: [names], line}:
 *  - 'none': nothing pending at the first read (no wait at all);
 *  - 'done': what was pending ended within the bound;
 *  - 'timeout': still pending at the bound (`pending` names it; a warning, the caller quits anyway);
 *  - 'unreadable': the first read failed (the client is gone: readPending's null): no wait, a note.
 */
export async function graceBeforeQuit(read, { ms = QUIT_GRACE_MS, everyMs = 1000, sleep = (t) => new Promise((r) => setTimeout(r, t)), now = Date.now } = {}) {
  const t0 = now();
  const get = async () => { try { return await read(); } catch { return null; } };
  let p = await get();
  if (p == null) return verdict('unreadable', 0, []);
  if (!p.length) return verdict('none', 0, []);
  let last = p;
  while (now() - t0 < ms) {
    await sleep(everyMs);
    p = await get();
    // a read failing mid-wait is no answer: the last known list stands, and the bound still holds
    if (p == null) continue;
    last = p;
    if (!p.length) return verdict('done', now() - t0, []);
  }
  return verdict('timeout', now() - t0, last.map(nameOf));
}

function verdict(outcome, waitedMs, pending) {
  const s = (waitedMs / 1000).toFixed(1);
  const line = outcome === 'none' ? 'quit-grace: no removal pending'
    : outcome === 'unreadable' ? 'quit-grace: the client is gone; nothing to wait for'
    : outcome === 'done' ? `quit-grace: waited ${s} s for pending removals to finish`
    : `quit-grace: WARNING ${pending.join(', ')} still removing after ${s} s; quitting anyway (the kept world may show a cut removal)`;
  return { outcome, waitedMs, pending, line };
}
