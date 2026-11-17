// Steward's in-game runs take their slots from Architect's machine-wide queue (slice SQ; Noah, 2026-11-15: "Steward joins the queue").
// The queue is Architect's (docs/GATES.md "Outside callers (Steward)"), reached only through its CLI, never edited or copied here:
//   node $ARCHITECT_DIR/tools/slot.mjs acquire --who steward --worktree <checkout> --label <flow or step> --pid <driver pid> [--max-wait-min N]
//   node $ARCHITECT_DIR/tools/slot.mjs release --ticket <t>        node $ARCHITECT_DIR/tools/slot.mjs status --json
// It gives at most 6 in-game runs machine-wide, the load gate (max1 72, settle 120 s), FIFO by ticket and turbo exclusivity; one slot per client.
// The holder pid is the driver (this node process), so a slot frees when the driver dies even when no release runs (a SIGKILL).
//
// ARCHITECT_DIR names the Architect checkout whose CLI to use (default: ARCHITECT_CHECKOUT, else ../architect-mc beside this checkout). A missing
// CLI is an error that names the fix, never a silent skip. E2E_SLOTS=off takes no slot (unit tests, odd cases) and says so loudly.
// E2E_SLOT_TICKET: a slot an owner (a --clients lead, the nightly) already holds for this process's client: used as is, never acquired or released here.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { onFinal, install as installShutdown } from './shutdown.mjs';

const round1 = (x) => Math.round(x * 10) / 10;

/** The slot CLI's path: $ARCHITECT_DIR, else $ARCHITECT_CHECKOUT, else <root>/../architect-mc, then tools/slot.mjs. */
export function slotCli({ env = process.env, root } = {}) {
  const arch = env.ARCHITECT_DIR || env.ARCHITECT_CHECKOUT || path.resolve(root ?? process.cwd(), '..', 'architect-mc');
  return path.join(path.resolve(arch), 'tools', 'slot.mjs');
}

export const slotsOff = (env = process.env) => String(env.E2E_SLOTS ?? '').toLowerCase() === 'off';

/** A slot the owner already holds for this process (E2E_SLOT_TICKET, E2E_SLOT), or null. */
export function inheritedSlot(env = process.env) {
  return env.E2E_SLOT_TICKET ? { ticket: env.E2E_SLOT_TICKET, slot: env.E2E_SLOT ? Number(env.E2E_SLOT) : null, inherited: true } : null;
}

/** The CLI, or an error naming the fix. */
export function requireCli(opts = {}) {
  const cli = opts.cli ?? slotCli(opts);
  if (!fs.existsSync(cli)) {
    throw Object.assign(new Error(`Architect's slot CLI is not at ${cli}: set ARCHITECT_DIR to an Architect checkout at v0.74.0 or later (it has tools/slot.mjs; `
      + `the default is ../architect-mc, which must be on that release), or E2E_SLOTS=off to take no slot (unit tests and odd cases only)`), { code: 'SLOT_NO_CLI' });
  }
  return cli;
}

/** What a slot timeout records (summary.json's `slot`): not a FAIL, a wait. */
export function waitRecord({ minutes, status = null, reason = '' } = {}) {
  const t = status?.turbo;
  const turbo = !t ? 'unknown' : t.running?.length ? 'running' : t.reserving?.length ? 'reserving' : t.queued?.length ? 'queued' : 'none';
  return { timedOut: true, waitedMin: round1(minutes ?? 0), load: status?.load?.load1 ?? null, turbo, holders: status?.holders?.length ?? null, reason };
}

/** "waited N min for a slot (load L, turbo T)". */
export const waitLine = (r) => `waited ${r.waitedMin} min for a slot (load ${r.load ?? '?'}, turbo ${r.turbo})`;

/** A slot timeout: err.code 'SLOT_TIMEOUT', err.record (waitRecord). */
export class SlotWait extends Error {
  constructor(record) {
    super(waitLine(record));
    this.code = 'SLOT_TIMEOUT';
    this.record = record;
  }
}

// ------------------------------------------------------------------ the tickets this process holds, released on every way out

const held = new Map(); // ticket -> cli
let hooked = false;

function hook() {
  if (hooked) return;
  hooked = true;
  // 'exit' runs on a normal end, process.exit, an uncaught throw or rejection; a SIGKILL leaves it to the queue's dead-pid pruning (the holder
  // pid is this process). A signal is the shutdown coordinator's (tools/lib/shutdown.mjs, batch-074 review): the run's clients are stopped first,
  // and only then are its slots released (its 'release' phase), then it exits 128+n
  process.on('exit', () => releaseAll());
  onFinal('release', () => releaseAll());
  installShutdown();
}

/** The tickets held now (tests, logs). */
export const heldTickets = () => [...held.keys()];

/** Frees one ticket (synchronous, so it works in an exit handler); a ticket already freed is fine. -> true when the CLI freed it. */
export function release(ticket, { log = (m) => console.log(m) } = {}) {
  if (!ticket || !held.has(ticket)) return false;
  const cli = held.get(ticket);
  held.delete(ticket);
  const r = spawnSync(process.execPath, [cli, 'release', '--ticket', ticket], { encoding: 'utf8' });
  // exit 4: it held nothing (already pruned); anything else but 0 is said, not thrown (the dead-pid pruning frees it in the end)
  if (r.status !== 0 && r.status !== 4) log(`slot: release ${ticket} exited ${r.status}: ${(r.stderr ?? '').trim()}`);
  else log(`slot: released ${ticket}`);
  return r.status === 0;
}

export function releaseAll(opts) {
  for (const t of [...held.keys()]) release(t, opts);
}

// Architect before v0.75.0 names a ticket t-<ms>-<holder pid>: two acquires by one holder in the same millisecond (a --clients lead) got the same
// ticket, and `release --ticket` could not tell them apart. So one process's acquires run one at a time (two CLIs spawned 2 ms apart still read
// the same millisecond: process start jitter), each starting at least ACQUIRE_GAP_MS after the previous one's start and its ticket's arrival, and
// a ticket this process already holds is refused, not released (releasing it would free the other one). In FIFO order the later acquire could
// not be served first anyway; its wait starts when the earlier one's ticket comes.
export const ACQUIRE_GAP_MS = 2;
let nextAcquireAt = 0;

/** The moment this process's next acquire may start (reserved now): at least ACQUIRE_GAP_MS after the previous one's start and its ticket. */
export function reserveAcquireAt(now = Date.now()) {
  const at = Math.max(now, nextAcquireAt);
  nextAcquireAt = at + ACQUIRE_GAP_MS;
  return at;
}

/** The queue now (`status --json`), or null when it can't be read. */
export function slotStatus(opts = {}) {
  try {
    const r = spawnSync(process.execPath, [requireCli(opts), 'status', '--json'], { encoding: 'utf8' });
    return r.status === 0 ? JSON.parse(r.stdout) : null;
  } catch {
    return null;
  }
}

let acquiring = Promise.resolve();

/**
 * One slot for `label`, FIFO behind the gate runs (one acquire at a time per process: see ACQUIRE_GAP_MS); blocks until it comes (the CLI's waiting goes to stderr).
 *   root      this checkout (--worktree, and where ../architect-mc is looked for)     pid  the holder (default: this process, the driver)
 *   maxWaitMin  E2E_SLOT_WAIT_MIN, default 15 (the CLI caps it at 15)
 * -> { slot, ticket, waitedSeconds, loadWait } (held until release or exit), or { off: true } under E2E_SLOTS=off.
 * Throws SlotWait on a timeout, an Error naming the fix when the CLI is missing, and an Error on any other failure.
 */
export function acquire(label, opts = {}) {
  const run = acquiring.then(() => acquireNow(label, opts));
  acquiring = run.catch(() => {});
  return run;
}

async function acquireNow(label, { root = process.cwd(), env = process.env, pid = process.pid, maxWaitMin, log = (m) => console.log(m), cli } = {}) {
  if (slotsOff(env)) {
    log(`slot: E2E_SLOTS=off: ${label} takes NO slot from Architect's queue (nothing gates this client; unit tests and odd cases only)`);
    return { off: true, slot: null, ticket: null };
  }
  const c = requireCli({ cli, env, root });
  hook();
  const wait = Number(maxWaitMin ?? env.E2E_SLOT_WAIT_MIN ?? 15);
  const args = [c, 'acquire', '--who', 'steward', '--worktree', root, '--label', label, '--pid', String(pid), '--max-wait-min', String(wait)];
  const at = reserveAcquireAt();
  while (Date.now() < at) await new Promise((resolve) => setTimeout(resolve, Math.max(1, at - Date.now())));
  const t0 = Date.now();
  const r = await new Promise((resolve) => {
    const child = spawn(process.execPath, args, { stdio: ['ignore', 'pipe', 'pipe'], env });
    let out = '', err = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => {
      err += d;
      for (const line of String(d).split('\n').filter(Boolean)) log(`  ${line}`);
    });
    child.on('error', (e) => resolve({ code: -1, out, err: `${err}${e.message}` }));
    child.on('close', (code) => resolve({ code, out, err }));
  });
  const minutes = (Date.now() - t0) / 60_000;
  if (r.code === 0) {
    let got;
    try {
      got = JSON.parse(r.out.trim().split('\n').pop());
    } catch {
      throw new Error(`slot: acquire printed no JSON: ${r.out.trim()}`);
    }
    // the ticket came: the next acquire is counted from here as well
    nextAcquireAt = Math.max(nextAcquireAt, Date.now() + ACQUIRE_GAP_MS);
    if (held.has(got.ticket)) {
      throw Object.assign(new Error(`slot: acquire for ${label} got ticket ${got.ticket}, which this process already holds: two slots share one ticket id, so `
        + `neither is released here (the queue's dead-pid pruning frees them when this driver ends). Architect v0.75.0 makes ticket ids unique.`), { code: 'SLOT_DUP_TICKET' });
    }
    held.set(got.ticket, c);
    log(`slot: ${label} holds slot ${got.slot} (${got.ticket}, pid ${pid}) after ${round1(minutes)} min`);
    return { slot: got.slot, ticket: got.ticket, waitedSeconds: got.waitedSeconds ?? Math.round(minutes * 60), loadWait: got.loadWait ?? null };
  }
  const reason = r.err.trim().split('\n').filter(Boolean).pop() ?? '';
  if (r.code === 2) throw new SlotWait(waitRecord({ minutes, status: slotStatus({ cli: c }), reason }));
  throw new Error(`slot: acquire for ${label} exited ${r.code}: ${reason}`);
}

// ------------------------------------------------------------------ a --clients run: one slot per client, the fallback to one

/**
 * Runs the lanes of a --clients run, one slot each: lane 1 starts once its slot comes (a SlotWait there propagates: nothing ran); each
 * further lane asks for its own in turn and starts when it comes. A lane whose slot doesn't come within its wait (and every lane after it)
 * runs after lane 1, on lane 1's slot, its game dir and ports: the run falls back to one client and says so.
 *   acquire(lane, i) -> { slot, ticket }      start(lane, slot, host) -> Promise<result> (host: the lane whose slot it borrows, or null)
 *   release(ticket)
 * -> [{ lane, result, slot, host, waited }] in lane order.
 */
export async function runLanes(lanes, { acquire: get, start, release: free, log = (m) => console.log(m) }) {
  const first = await get(lanes[0], 0);
  const rows = lanes.map((lane) => ({ lane, result: null, slot: null, host: null, waited: null }));
  rows[0].slot = first;
  const own = [start(lanes[0], first, null).then((r) => { rows[0].result = r; })];
  const later = [];
  for (let i = 1; i < lanes.length; i++) {
    if (later.length) { later.push(i); continue; }
    try {
      const s = await get(lanes[i], i);
      rows[i].slot = s;
      own.push(start(lanes[i], s, null).then((r) => { rows[i].result = r; }).finally(() => free(s.ticket)));
    } catch (e) {
      if (e.code !== 'SLOT_TIMEOUT') throw e;
      rows[i].waited = e.record;
      later.push(i);
      log(`clients: ${e.message}: falling back to one client; lane ${i + 1}${lanes.length > i + 1 ? ` and after` : ''} run(s) after lane 1, on its slot`);
    }
  }
  // the fallback lanes, one after another on lane 1's slot once it is done
  const tail = own[0].then(async () => {
    for (const i of later) {
      rows[i].slot = first;
      rows[i].host = lanes[0];
      rows[i].result = await start(lanes[i], first, lanes[0]);
    }
  }).finally(() => free(first.ticket));
  await Promise.all([...own, tail]);
  return rows;
}
