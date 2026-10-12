// The strange-asks gap map (docs/PLAN.md "Strange asks"): which asks are built today, built as a nearest version, or wait, and which capabilities
// would unlock the most. Pure: reads the two fixture files.
import fs from 'node:fs';

const RANK = { now: 0, nearest: 1, waits: 2 };

export function load(dir = new URL('../../fixtures/strange-asks/', import.meta.url)) {
  const caps = JSON.parse(fs.readFileSync(new URL('capabilities.json', dir))).capabilities;
  const asks = JSON.parse(fs.readFileSync(new URL('asks.json', dir))).asks;
  return { caps, asks };
}

/** An ask's status: the worst of its needs. */
export function statusOf(ask, caps) {
  return ask.needs.map((n) => caps[n].status).reduce((a, b) => (RANK[b] > RANK[a] ? b : a), 'now');
}

/** Every capability not built yet, with the asks that need it and the asks it alone would bring to 'now' (their only missing need). */
export function gaps(caps, asks) {
  const out = [];
  for (const [id, c] of Object.entries(caps)) {
    if (c.status === 'now') continue;
    const need = asks.filter((a) => a.needs.includes(id));
    const alone = need.filter((a) => a.needs.every((n) => n === id || caps[n].status === 'now'));
    out.push({ id, name: c.name, status: c.status, comes: c.comes, needed: need.length, alone: alone.length, asks: need.map((a) => a.id) });
  }
  return out.sort((a, b) => b.needed - a.needed || b.alone - a.alone || a.id.localeCompare(b.id));
}

export function report({ caps, asks }) {
  const by = { now: [], nearest: [], waits: [] };
  for (const a of asks) by[statusOf(a, caps)].push(a.id);
  const lines = [`# Strange asks: the gap map`, '', `${asks.length} asks: ${by.now.length} built today, ${by.nearest.length} built as a nearest version, ${by.waits.length} wait.`, ''];
  lines.push('| Capability | Status | Asks needing it | Unlocked by it alone | Comes with |', '|---|---|---|---|---|');
  for (const g of gaps(caps, asks)) lines.push(`| ${g.name} | ${g.status} | ${g.needed} | ${g.alone} | ${g.comes ?? ''} |`);
  lines.push('', '## By category', '');
  const cats = [...new Set(asks.map((a) => a.category))];
  for (const c of cats) {
    const in_ = asks.filter((a) => a.category === c);
    lines.push(`- **${c}:** ${in_.map((a) => `${a.id} (${statusOf(a, caps)})`).join(', ')}`);
  }
  return { text: lines.join('\n') + '\n', counts: { now: by.now.length, nearest: by.nearest.length, waits: by.waits.length } };
}
