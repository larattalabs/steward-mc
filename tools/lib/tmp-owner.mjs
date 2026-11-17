// Owned temp dirs (slice TMPSWEEP): a dir an e2e run makes on the RAM disk (the sidecar's data, 2-6 GB) is owner-marked and removed when the run's
// driver process is done for good, and a sweep at each run's start removes the marked dirs whose owner is gone (a SIGKILL, a crash, a reboot of
// the driver). By 11-16 sixteen unremoved sidecar dirs had filled the 24 GB RAM disk (ENOSPC). mod/DEV.md "Temp dirs".
//
// The marker is Architect's (slice TS, ../architect-mc/tools/lib/arch-tmp.mjs and tmp-sweep.mjs): a regular file `.arch-owner` holding
// {pid, startMs, createdAt}, startMs being the owner's Node timeOrigin; an owner is live when its pid runs with the same start time (a reused pid
// doesn't count). The format and the liveness rule are matched here, not imported: Architect's modules point TMPDIR at its own RAM dir on import,
// which would move this process's (and its clients') temp files out of <RAM>/steward.
//
// Cleanup is tied to the driver process ending, never to a client quitting: a run's client relaunches (restartClient, the restart flows) happen
// inside the same process, so the dir and the sidecar data in it survive them. It runs on a normal end, process.exit (the slot timeout's exit 3),
// an uncaught throw or rejection ('exit'), and SIGINT/SIGTERM/SIGHUP (the shutdown coordinator's last phase, after the client is stopped). A dir is removed only by its exact path, and only while its marker still
// names this process (another run may have taken the same path since). STEWARD_KEEP_TMP=1 keeps them (debugging); the sweep takes them later.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { onFinal, install as installShutdown } from './shutdown.mjs';

export const OWNER_FILE = '.arch-owner';
export const PROCESS_START_MS = Math.round(performance.timeOrigin);
/** Architect's window (tmp-sweep.mjs): the live process started at most 1 s after the marker's time and at most 60 s before it. */
export const START_EARLY_MS = 1_000;
export const START_LATE_MS = 60_000;

export const ownerRecord = (pid = process.pid, startMs = PROCESS_START_MS, extra = {}) => ({ pid, startMs, createdAt: new Date().toISOString(), ...extra });

/** Writes the owner marker into dir (this process by default). */
export function markOwner(dir, extra = {}, { pid = process.pid, startMs = PROCESS_START_MS } = {}) {
  fs.writeFileSync(path.join(dir, OWNER_FILE), `${JSON.stringify(ownerRecord(pid, startMs, extra))}\n`);
}

/** The owner marker's text as it is on disk, or null (no marker, a symlink, unreadable). */
export function rawOwner(dir) {
  const f = path.join(dir, OWNER_FILE);
  try {
    return fs.lstatSync(f).isFile() ? fs.readFileSync(f, 'utf8') : null;
  } catch {
    return null;
  }
}

/** A marker's text parsed, or null (not JSON, or without a pid). */
export function parseOwner(raw) {
  if (raw == null) return null;
  try {
    const o = JSON.parse(raw);
    return o && typeof o === 'object' && Number.isInteger(o.pid) && o.pid > 0 ? o : null;
  } catch {
    return null;
  }
}

/** The owner marker of dir, or null (a marker that is a symlink, unreadable or without a pid doesn't count). */
export const readOwner = (dir) => parseOwner(rawOwner(dir));

/** Whether dir's marker names this process. */
export const ownedByMe = (dir, { pid = process.pid, startMs = PROCESS_START_MS } = {}) => {
  const o = readOwner(dir);
  return !!o && o.pid === pid && o.startMs === startMs;
};

/** Every live process's start time (epoch ms) by pid, from one `ps` call; empty on failure. */
export function processStarts() {
  const out = new Map();
  try {
    const txt = execFileSync('ps', ['-A', '-o', 'pid=,lstart='], { encoding: 'utf8', env: { ...process.env, LC_ALL: 'C' }, maxBuffer: 64 << 20 });
    for (const line of txt.split('\n')) {
      const m = line.trim().match(/^(\d+)\s+(.+)$/);
      if (!m) continue;
      const t = Date.parse(m[2]);
      if (Number.isFinite(t)) out.set(Number(m[1]), t);
    }
  } catch {
    // no ps: an empty map
  }
  return out;
}

/** One process's start time (epoch ms), from `ps -p`; null when it doesn't run, undefined when ps itself failed (unknown). */
export function processStart(pid) {
  let txt;
  try {
    txt = execFileSync('ps', ['-o', 'lstart=', '-p', String(pid)], { encoding: 'utf8', env: { ...process.env, LC_ALL: 'C' }, stdio: ['ignore', 'pipe', 'ignore'] });
  } catch (e) {
    // ps exits 1 when no such process runs (and prints nothing); anything else is unknown
    return e.status === 1 && !String(e.stdout ?? '').trim() ? null : undefined;
  }
  const t = Date.parse(txt.trim());
  return Number.isFinite(t) ? t : undefined;
}

const pidAlive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
};

/** Whether owner {pid, startMs} is a live process, the same one (Architect's rule; without ps, a pid alive by kill(0) counts as held). */
export function isHeld(owner, starts, alive = pidAlive) {
  if (!owner || !Number.isInteger(owner.pid) || owner.pid <= 0) return false;
  const t = starts.get(owner.pid);
  if (t === undefined) return starts.size ? false : alive(owner.pid);
  if (!Number.isFinite(owner.startMs)) return true;
  return t <= owner.startMs + START_EARLY_MS && owner.startMs <= t + START_LATE_MS;
}

/**
 * Whether owner {pid, startMs} is gone now, judged on its own pid (the sweep's recheck just before a removal): no process with that pid, or one
 * that started outside the marker's window (a reused pid). A pid alive whose start time can't be read counts as held (not gone).
 */
export function ownerGone(owner, { alive = pidAlive, startOf = processStart } = {}) {
  if (!owner || !Number.isInteger(owner.pid) || owner.pid <= 0) return true;
  if (!alive(owner.pid)) return true;
  const t = startOf(owner.pid);
  // (fix-check P1) ps says no such process, yet kill(0) found it: held while it still runs (a ps failure never authorises a removal)
  if (t === null) return !alive(owner.pid);
  if (t === undefined) return false;
  return !isHeld(owner, new Map([[owner.pid, t]]), alive);
}

// ------------------------------------------------------------------ the dirs this process owns, removed on every way out

const owned = new Map(); // dir -> { link }
let hooked = false;
const keep = (env = process.env) => env.STEWARD_KEEP_TMP === '1';

/**
 * Removes one owned dir by its exact path, if its marker still names this process. With `link` (the game dir's sidecar-data symlink to it, RAM mode)
 * the dir is made again empty and unmarked, and the link left: a dev client on the same game dir then keeps writing to the RAM disk, not to a real
 * on-disk dir the next run would rename to sidecar-data.ssd and keep. Never throws. -> 'removed'|'kept'|'not-mine'|'gone'|'failed'.
 */
export function removeOwned(dir, { link = null, env = process.env } = {}) {
  owned.delete(dir);
  try {
    let st = null;
    try { st = fs.lstatSync(dir); } catch {}
    if (!st) return 'gone';
    if (!st.isDirectory() || !ownedByMe(dir)) return 'not-mine';
    if (keep(env)) return 'kept';
    fs.rmSync(dir, { recursive: true, force: true, maxRetries: 3, retryDelay: 50 });
    if (fs.existsSync(dir)) {
      // a straggler (the sidecar, the client's grandchild) wrote into it meanwhile: marked again, so the next run's sweep takes it
      try { markOwner(dir); } catch {}
      return 'failed';
    }
    if (link) relink(link, dir);
    return 'removed';
  } catch {
    try { if (fs.existsSync(dir)) markOwner(dir); } catch {}
    return 'failed';
  }
}

/** The link still points at exactly dir: dir is made again, empty (no marker: nothing of this run's is left in it). */
function relink(link, dir) {
  try {
    if (fs.lstatSync(link).isSymbolicLink() && fs.readlinkSync(link) === dir) fs.mkdirSync(dir, { recursive: true });
  } catch {
    // no link (anymore): nothing to keep valid
  }
}

/** Removes every dir this process owns (the exit hook; e2e also calls it at the end of its finally). -> [[dir, outcome]]. */
export function cleanupOwned(opts = {}) {
  return [...owned].map(([dir, o]) => [dir, removeOwned(dir, { ...o, ...opts })]);
}

/** The dirs this process owns now (tests, logs). */
export const ownedDirs = () => [...owned.keys()];

function hook() {
  if (hooked) return;
  hooked = true;
  process.on('exit', () => { cleanupOwned(); });
  // a signal is the shutdown coordinator's (tools/lib/shutdown.mjs, batch-074 review): the run's client JVM is stopped first, then its slots go,
  // and only then its dirs (the 'cleanup' phase), never while the JVM may still write into them
  onFinal('cleanup', () => { cleanupOwned(); });
  installShutdown();
}

/** Marks dir as this process's and removes it when the process ends ({link}: the symlink to unlink with it). Returns dir. */
export function own(dir, { link = null, extra = {} } = {}) {
  markOwner(dir, extra);
  owned.set(dir, { link });
  hook();
  return dir;
}

// ------------------------------------------------------------------ the sweep

/** The newest mtime of dir and its direct children (lstat; no descent). */
function lastTouched(dir, st) {
  let t = st.mtimeMs;
  try {
    for (const n of fs.readdirSync(dir)) {
      try { t = Math.max(t, fs.lstatSync(path.join(dir, n)).mtimeMs); } catch {}
    }
  } catch {}
  return t;
}

/**
 * Removes the direct children of root named `prefix*` that carry a valid owner marker whose owner is gone and that are untouched for `hours`
 * (default 0: a dead owner is decisive, the dir is that run's leftover). A dir without a marker, a symlink and a plain file are never touched (other
 * tools share TMPDIR); a live owner's dir is kept. Never throws. -> { root, removed: [names], kept: [names], failed: [names] }.
 *
 * (batch-074 GPT review P1) The snapshot `starts` is taken once, before the walk: a driver that starts after it and marks a dir before the walk
 * reaches it would read as gone. So just before each removal the marker is read again and must be byte-for-byte the one judged, and its owner is
 * checked again on its own (`gone`: kill(pid, 0), then that one pid's start time against the marker's); a changed or missing marker, a live owner
 * or an owner that can't be told keeps the dir. `between(path)` (tests) runs between the judgement and that recheck. A window as short as the
 * recheck and the rmSync remains (a driver marking the very dir in that gap); renaming the dir away first would break a live owner's paths.
 */
export function sweepStale({ root, prefix = 'sidecar-', hours = 0, now = Date.now(), starts = processStarts(), alive = pidAlive, dryRun = false,
  gone = (o) => ownerGone(o, { alive }), between = null } = {}) {
  const res = { root, removed: [], kept: [], failed: [] };
  let names = [];
  try { names = fs.readdirSync(root).sort(); } catch { return res; }
  for (const name of names) {
    if (!name.startsWith(prefix)) continue;
    const p = path.join(root, name);
    let st;
    try { st = fs.lstatSync(p); } catch { continue; }
    if (!st.isDirectory()) continue;
    const raw = rawOwner(p);
    const o = parseOwner(raw);
    if (!o) continue;
    if (isHeld(o, starts, alive) || (hours > 0 && (now - lastTouched(p, st)) / 3_600_000 < hours)) { res.kept.push(name); continue; }
    try {
      if (!dryRun) {
        if (between) between(p);
        if (!fs.lstatSync(p).isDirectory()) { res.kept.push(name); continue; } // swapped for a symlink meanwhile: not followed
        // marked again since the judgement (another run took the name), or its owner live after all: kept
        if (rawOwner(p) !== raw || !gone(o)) { res.kept.push(name); continue; }
        fs.rmSync(p, { recursive: true, force: true, maxRetries: 2, retryDelay: 50 });
      }
      res.removed.push(name);
    } catch {
      res.failed.push(name);
    }
  }
  return res;
}
