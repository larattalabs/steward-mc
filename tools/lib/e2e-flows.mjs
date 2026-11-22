// The e2e check's flow choice (tools/e2e.mjs), kept apart so it has unit tests (test/e2e-flows.test.mjs).

/**
 * The flows an --only run picks (all of them with no words): a word of it in the flow's name or tags, then what those need, and what that needs.
 * `list` is the flows in run order, each { name, tags, needs }; each map value is why the flow runs: '' when picked, else the flow that needs it.
 */
export function pickFlows(list, words) {
  const why = new Map();
  if (!words) for (const f of list) why.set(f.name, '');
  else for (const f of list) if (words.some((w) => `${f.name} ${f.tags}`.toLowerCase().includes(w))) why.set(f.name, '');
  const pull = (f) => {
    for (const n of f.needs ?? []) {
      if (!why.has(n)) why.set(n, f.name);
      pull(list.find((x) => x.name === n));
    }
  };
  for (const f of list) if (why.has(f.name)) pull(f);
  return why;
}

// ------------------------------------------------------------------ slice EF: two clients side by side, and skipping unchanged flows

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

/** A strict flow (billing, refunds, undo, restarts) is skipped only on an exact key match (brief EF). */
export const STRICT_WORDS = ['bill', 'refund', 'undo', 'restart', 'survival'];
export const isStrict = (f) => STRICT_WORDS.some((w) => `${f.name} ${f.tags ?? ''}`.toLowerCase().includes(w));

/**
 * Groups of flows that run on one client, each in registry order: a flow and what it `needs` (it builds on their world), and flows that share a
 * code block writing under artifacts/e2e (`together`, pairs of names: the same snapshot file names). Groups come out in registry order of their
 * first flow. `names` limits it to the flows that run.
 */
export function groupFlows(list, names = null, together = []) {
  const run = list.filter((f) => !names || names.has(f.name));
  const up = new Map(run.map((f) => [f.name, f.name]));
  const find = (n) => (up.get(n) === n ? n : (up.set(n, find(up.get(n))), up.get(n)));
  const join = (a, b) => { if (up.has(a) && up.has(b)) up.set(find(a), find(b)); };
  for (const f of run) for (const n of f.needs ?? []) join(f.name, n);
  for (const [a, b] of together) join(a, b);
  const groups = new Map();
  for (const f of run) {
    const r = find(f.name);
    if (!groups.has(r)) groups.set(r, []);
    groups.get(r).push(f.name);
  }
  return [...groups.values()];
}

/**
 * Packs groups onto `clients` lanes, longest first (each group's seconds from `seconds`, {flow: s}), each onto the lane with the least seconds so far
 * (the lower lane on a tie). A group holding a flow with no recorded time goes after every timed one, in registry order. Returns
 * [{ flows: [names in run order], seconds, untimed }], one per lane (an empty lane has no flows).
 */
export function packFlows(groups, seconds, clients) {
  const lanes = Array.from({ length: Math.max(1, clients) }, () => ({ flows: [], seconds: 0, untimed: 0 }));
  const timeOf = (g) => (g.every((n) => Number.isFinite(seconds?.[n])) ? g.reduce((s, n) => s + seconds[n], 0) : null);
  const timed = groups.map((g, i) => ({ g, i, t: timeOf(g) })).filter((x) => x.t != null).sort((a, b) => b.t - a.t || a.i - b.i);
  const untimed = groups.filter((g) => timeOf(g) == null);
  const least = () => lanes.reduce((best, l, i) => (l.seconds < lanes[best].seconds || (l.seconds === lanes[best].seconds && l.flows.length < lanes[best].flows.length) ? i : best), 0);
  for (const { g, t } of timed) { const l = lanes[least()]; l.flows.push(...g); l.seconds += t; }
  for (const g of untimed) {
    const l = lanes[least()];
    l.flows.push(...g);
    l.untimed += g.length;
    l.seconds += g.reduce((s, n) => s + (seconds?.[n] ?? 0), 0);
  }
  return lanes;
}

/** Source without comments (line comments after whitespace or at a line's start, block comments): what the keys hash and the call graph reads. */
export const stripComments = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[ \t])\/\/.*$/gm, '$1');

/**
 * The top-level code blocks of a module, {name: text}: each `function`, `async function`, `const`/`let`/`var` at column 0 runs to the next line at
 * column 0 that starts another statement (or a comment). Unnamed statements are left out.
 */
export function codeBlocks(src) {
  const blocks = {};
  let name = null, buf = [];
  const end = () => { if (name) blocks[name] = (blocks[name] ?? '') + stripComments(buf.join('\n')); name = null; buf = []; };
  for (const line of src.split('\n')) {
    if (/^[A-Za-z_$/@]/.test(line)) {
      end();
      const m = /^(?:export\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)|^(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)/.exec(line);
      if (m) name = m[1] ?? m[2];
    }
    if (name) buf.push(line);
  }
  end();
  return blocks;
}

/** The names of the blocks `text` reaches, by the identifiers it mentions, then theirs (sorted). */
export function closureOf(blocks, text) {
  const seen = new Set();
  const todo = [stripComments(text)];
  while (todo.length) {
    for (const id of todo.pop().match(/[A-Za-z_$][\w$]*/g) ?? []) {
      if (seen.has(id) || !Object.hasOwn(blocks, id)) continue;
      seen.add(id);
      todo.push(blocks[id]);
    }
  }
  return [...seen].sort();
}

/**
 * The fixtures a flow's code reads: `path.join(ROOT, 'fixtures', 'a', 'b.json')` gives 'fixtures/a/b.json'; a name made at run time
 * (`path.join(ROOT, 'fixtures', 'talk', f)`) gives its whole directory, 'fixtures/talk/'. Sorted, no repeats.
 */
export function fixtureRefs(text) {
  const out = new Set();
  for (const m of text.matchAll(/path\.join\(\s*ROOT\s*,\s*'fixtures'((?:\s*,\s*'[^']*')*)(\s*,\s*[^)\s'][^)]*)?\)/g)) {
    const parts = ['fixtures', ...[...m[1].matchAll(/'([^']*)'/g)].map((x) => x[1])];
    out.add(m[2] ? parts.join('/') + '/' : parts.join('/'));
  }
  return [...out].sort();
}

/** sha256 of files and directories under `root` (each relative path and its bytes; a directory recursively, sorted; a missing path counts as missing). */
export function hashPaths(root, rels) {
  const h = crypto.createHash('sha256');
  const add = (rel) => {
    const p = path.join(root, rel);
    let st;
    try { st = fs.statSync(p); } catch { h.update(`missing ${rel}\n`); return; }
    if (st.isDirectory()) for (const e of fs.readdirSync(p).sort()) add(path.join(rel, e));
    else h.update(`file ${rel}\n`).update(fs.readFileSync(p)).update('\n');
  };
  for (const r of [...rels].sort()) add(r);
  return h.digest('hex').slice(0, 16);
}

export const hashText = (s) => crypto.createHash('sha256').update(s).digest('hex').slice(0, 16);

/**
 * (SAVEB leftover) The harness's own library: every tools/lib/*.mjs under `root`, hashed as one part. A flow's block holds only the lib modules it
 * names directly; this catches the rest (a lib that one of those imports, or the shared helpers every flow runs through).
 */
export function libFiles(root) {
  let names = [];
  try { names = fs.readdirSync(path.join(root, 'tools', 'lib')).filter((n) => n.endsWith('.mjs')).sort(); } catch {}
  return names.map((n) => `tools/lib/${n}`);
}
export const hashLib = (root) => hashPaths(root, libFiles(root));

/**
 * Whether a flow can be skipped: `now` and `last` are {block, fixtures, src, lib, jar, pin} (last from passes.json, its last PASS, with `at`). A
 * strict flow needs every part equal (an exact key: block, fixtures, lib, jar, pin); a light flow its block, its fixtures, the harness lib and the mod's
 * sources. A PASS recorded before `lib` was keyed has none, so it runs once. A part that
 * could not be worked out (null) never matches. Returns { skip, why }.
 */
export function skipDecision(f, now, last) {
  if (!last) return { skip: false, why: 'no PASS recorded' };
  const parts = isStrict(f) ? ['block', 'fixtures', 'lib', 'jar', 'pin'] : ['block', 'fixtures', 'lib', 'src'];
  const changed = parts.filter((p) => now[p] == null || now[p] !== last[p]);
  if (changed.length) return { skip: false, why: `${changed.map((p) => (now[p] == null ? `no ${p}` : `${p} changed`)).join(', ')} since its PASS at ${last.at}` };
  return { skip: true, why: `${isStrict(f) ? 'strict, exact key' : 'light: its code, fixtures, the harness lib and the mod sources'} unchanged since its PASS at ${last.at}` };
}

/**
 * The run list once unchanged flows are skipped: `why` is pickFlows' map, `skips` {flow: true} the flows that could be skipped. A skipped flow that
 * a running flow needs runs anyway. Returns { run: Map (name → why it runs), skipped: [names] }.
 */
export function applySkips(list, why, skips) {
  const run = new Map([...why].filter(([n]) => !skips[n]));
  const pull = (f) => {
    for (const n of f.needs ?? []) {
      if (!run.has(n)) run.set(n, f.name);
      pull(list.find((x) => x.name === n));
    }
  };
  for (const f of list) if (run.has(f.name)) pull(f);
  return { run, skipped: [...why.keys()].filter((n) => !run.has(n)) };
}

// ------------------------------------------------------------------ slice E2E-SPEED: packing from a committed baseline, the --full tier

/**
 * Each flow's seconds from timing sources, earliest first, a later source overriding an earlier one per flow. A source is a summary
 * ({ flows: [{ name, seconds }] }), flow-times.json ({ name: { seconds } }) or the committed baseline ({ seconds: { name: s } }); null skips.
 */
export function mergeFlowSeconds(sources) {
  const out = {};
  for (const j of sources) {
    if (!j || typeof j !== 'object') continue;
    if (Array.isArray(j.flows)) { for (const x of j.flows) if (Number.isFinite(x?.seconds)) out[x.name] = x.seconds; continue; }
    const m = j.seconds && typeof j.seconds === 'object' && !Array.isArray(j.seconds) ? j.seconds : j;
    for (const [k, v] of Object.entries(m)) {
      const s = Number.isFinite(v) ? v : v?.seconds;
      if (Number.isFinite(s)) out[k] = s;
    }
  }
  return out;
}

/**
 * The flows a run leaves out by tier (E2E-SPEED, Noah 11-21: speed over coverage): a `nightlyOnly` flow runs only in the nightly, a `full` one in
 * the nightly or with --full; either runs when --only names it exactly. `why` is pickFlows' map; returns [{ name, why }] of the flows to drop from it.
 */
export function tierSkips(list, why, { nightly = false, full = false, only = null } = {}) {
  const out = [];
  for (const f of list) {
    if (!why.has(f.name) || (only ?? []).includes(f.name)) continue;
    if (f.nightlyOnly && !nightly) out.push({ name: f.name, why: `nightly-only (${f.nightlyOnly}); name it in --only to run it` });
    else if (f.full && !nightly && !full) out.push({ name: f.name, why: `full tier (${f.full}); --full or --only ${f.name} runs it` });
  }
  return out;
}
