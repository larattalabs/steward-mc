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
// names this process (another run may have taken the same path since). STEWARD_KEEP_TMP=1 keeps them (debugging): marked `kept` from the start
// (E2E-DISK-3), so a later run on the same game dir never empties them. (E2E-DISK-4) The sweep bounds them: a kept dir whose owner is gone goes
// once untouched for 24 h, or oldest first while the kept total is past 8 GiB (STEWARD_KEEP_TMP_HOURS, STEWARD_KEEP_TMP_CAP_GIB).
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

/** Whether a marker says its dir was kept (STEWARD_KEEP_TMP=1, E2E-DISK-3): never swept, never emptied by a later run. */
export const isKept = (o) => o?.kept === true;

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
export function removeOwned(dir, { link = null, primary = owned.get(dir)?.primary ?? null, env = process.env } = {}) {
  owned.delete(dir);
  try {
    let st = null;
    try { st = fs.lstatSync(dir); } catch {}
    if (!st) return 'gone';
    if (!st.isDirectory() || !ownedByMe(dir)) return 'not-mine';
    if (keep(env)) {
      // (E2E-DISK-3) the marker says kept, so the sweep and the next run's preflight leave the data
      try { const o = readOwner(dir); if (o && !isKept(o)) fs.writeFileSync(path.join(dir, OWNER_FILE), `${JSON.stringify({ ...o, kept: true })}\n`); } catch {}
      return 'kept';
    }
    fs.rmSync(dir, { recursive: true, force: true, maxRetries: 3, retryDelay: 50 });
    if (fs.existsSync(dir)) {
      // a straggler (the sidecar, the client's grandchild) wrote into it meanwhile: marked again, so the next run's sweep takes it
      try { markOwner(dir); } catch {}
      return 'failed';
    }
    // (E2E-DISK-5) a primary that isn't a real dir (a file, a symlink): the fallback is made again empty instead, so the link never dangles
    if (link && !(primary && repoint(link, dir, primary))) relink(link, dir);
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

/**
 * (E2E-DISK-4) A fallback dir (`sidecar-<hash>-XXXXXX`, taken because the game dir's own held kept or a dev client's data) isn't made again: made
 * again empty, one per run, they piled up. The link, when it still points at exactly dir, goes back to the game dir's own dir (`primary`, where it
 * pointed before the run; made when absent), swapped in by a rename so it is never missing. -> true when the link is fine as it is (repointed, or no
 * longer this dir's); false when it still points at dir because primary isn't a real directory (E2E-DISK-5: a plain file, a symlink, dangling or
 * not), which is never touched: it isn't Steward's to replace. The caller then makes dir again (relink), so the link doesn't dangle.
 */
function repoint(link, dir, primary) {
  try {
    if (!fs.lstatSync(link).isSymbolicLink() || fs.readlinkSync(link) !== dir) return true;
    let st = null;
    try { st = fs.lstatSync(primary); } catch {}
    if (!st) {
      fs.mkdirSync(primary, { recursive: true });
      st = fs.lstatSync(primary);
    }
    if (!st.isDirectory()) return false;
    const tmp = `${link}.repoint-${process.pid}`;
    try { fs.unlinkSync(tmp); } catch {}
    fs.symlinkSync(primary, tmp);
    fs.renameSync(tmp, link);
    return true;
  } catch {
    return false;
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

/**
 * Marks dir as this process's and removes it when the process ends ({link}: the game dir's symlink to it; {primary}: for a fallback dir, the
 * game dir's own dir the link goes back to). With STEWARD_KEEP_TMP=1 the marker says
 * `kept` from the start (E2E-DISK-3): a SIGKILL skips the exit hook, and the data is still never swept. Returns dir.
 */
export function own(dir, { link = null, primary = null, extra = {}, env = process.env } = {}) {
  markOwner(dir, keep(env) ? { ...extra, kept: true } : extra);
  owned.set(dir, { link, primary });
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

/** (E2E-DISK-4) The kept-dir bounds' defaults: a dead owner's kept dir goes once untouched this long, or oldest first past this total. */
export const KEPT_HOURS = 24;
export const KEPT_CAP_GIB = 8;
const GIB = 1024 ** 3;
/** A fallback dir's name: the game dir's `sidecar-<hash>` plus mkdtemp's six characters. */
export const FALLBACK_RE = /^sidecar-[0-9a-f]+-[A-Za-z0-9]{6}$/;
/** An empty unmarked fallback is left this long (minutes) before the sweep takes it: a run marks its new one at once, a dev client fills it. */
export const EMPTY_MINUTES = 10;

/** The kept bounds from STEWARD_KEEP_TMP_HOURS and STEWARD_KEEP_TMP_CAP_GIB (numbers >= 0; 0 turns that bound off), else the defaults, said. */
export function keptBounds(env = process.env) {
  const warn = [];
  const num = (k, d) => {
    const raw = env[k];
    if (raw == null || String(raw).trim() === '') return d;
    const n = Number(raw);
    if (Number.isFinite(n) && n >= 0) return n;
    warn.push(`${k}=${JSON.stringify(raw)} is not a number >= 0: using ${d}`);
    return d;
  };
  const out = { keptHours: num('STEWARD_KEEP_TMP_HOURS', KEPT_HOURS), keptCapBytes: num('STEWARD_KEEP_TMP_CAP_GIB', KEPT_CAP_GIB) * GIB };
  if (warn.length) out.warning = warn.join('; ');
  return out;
}

/** The bytes dir takes on its volume (allocated blocks), walked with lstat: symlinks are never followed. Never throws. */
export function dirBytes(dir) {
  let total = 0;
  const walk = (p) => {
    let st;
    try { st = fs.lstatSync(p); } catch { return; }
    total += Number.isFinite(st.blocks) ? st.blocks * 512 : st.size;
    if (!st.isDirectory()) return;
    let names = [];
    try { names = fs.readdirSync(p); } catch { return; }
    for (const n of names) walk(path.join(p, n));
  };
  walk(dir);
  return total;
}

/**
 * Removes the direct children of root named `prefix*` that carry a valid owner marker whose owner is gone and that are untouched for `hours`
 * (default 0: a dead owner is decisive, the dir is that run's leftover). A dir without a marker, a symlink and a plain file are never touched (other
 * tools share TMPDIR), but for an empty fallback dir (below); a live owner's dir is always kept. Never throws.
 *
 * (E2E-DISK-4) Dirs marked `kept` (STEWARD_KEEP_TMP=1) are bounded, so repeated kept runs (2-6 GB each) can't fill the 24 GB RAM disk:
 *   - a kept dir whose owner is gone goes once untouched for `keptHours` (24; 0: no age bound);
 *   - while all kept dirs together (live owners' included: they count, but are never taken) pass `keptCapBytes` (8 GiB; 0: no cap), the dead
 *     owners' go, oldest first; `overCap` says the total is still past it after that;
 *   - a dead owner's kept fallback holding nothing but its marker goes at once.
 * An empty unmarked fallback dir (`sidecar-<hash>-XXXXXX`, FALLBACK_RE; what a run before E2E-DISK-4 left) untouched for `emptyMinutes` goes too,
 * by rmdir: never recursively, so a dir written into meanwhile stays.
 * -> { root, removed: [names], kept: [names], failed: [names], swept: [{name, why, bytes?}], keptBytes, overCap }.
 *
 * (batch-074 GPT review P1) The snapshot `starts` is taken once, before the walk: a driver that starts after it and marks a dir before the walk
 * reaches it would read as gone. So just before each removal the marker is read again and must be byte-for-byte the one judged, and its owner is
 * checked again on its own (`gone`: kill(pid, 0), then that one pid's start time against the marker's); a changed or missing marker, a live owner
 * or an owner that can't be told keeps the dir. `between(path)` (tests) runs between the judgement and that recheck. A window as short as the
 * recheck and the rmSync remains (a driver marking the very dir in that gap); renaming the dir away first would break a live owner's paths.
 */
export function sweepStale({ root, prefix = 'sidecar-', hours = 0, now = Date.now(), starts = processStarts(), alive = pidAlive, dryRun = false,
  gone = (o) => ownerGone(o, { alive }), between = null, keptHours = KEPT_HOURS, keptCapBytes = KEPT_CAP_GIB * GIB,
  emptyMinutes = EMPTY_MINUTES, sizeOf = dirBytes } = {}) {
  const res = { root, removed: [], kept: [], failed: [], swept: [], keptBytes: 0, overCap: false };
  let names = [];
  try { names = fs.readdirSync(root).sort(); } catch { return res; }
  /** The recheck, then the removal; -> true when removed (or would be, dry). */
  const take = (name, p, raw, o, why, bytes) => {
    try {
      if (!dryRun) {
        if (between) between(p);
        if (!fs.lstatSync(p).isDirectory()) { res.kept.push(name); return false; } // swapped for a symlink meanwhile: not followed
        // marked again since the judgement (another run took the name, or its run said kept), or its owner live after all: kept
        if (rawOwner(p) !== raw || !gone(o)) { res.kept.push(name); return false; }
        fs.rmSync(p, { recursive: true, force: true, maxRetries: 2, retryDelay: 50 });
      }
      res.removed.push(name);
      res.swept.push({ name, why, ...(bytes == null ? {} : { bytes }) });
      return true;
    } catch {
      res.failed.push(name);
      return false;
    }
  };
  const keptLive = [], keptDead = [];
  for (const name of names) {
    if (!name.startsWith(prefix)) continue;
    const p = path.join(root, name);
    let st;
    try { st = fs.lstatSync(p); } catch { continue; }
    if (!st.isDirectory()) continue;
    const raw = rawOwner(p);
    const o = parseOwner(raw);
    if (!o) {
      if (raw == null && FALLBACK_RE.test(name) && (now - lastTouched(p, st)) / 60_000 >= emptyMinutes) {
        let empty = false;
        try { empty = fs.readdirSync(p).length === 0; } catch {}
        if (!empty) continue;
        try {
          if (!dryRun) fs.rmdirSync(p); // ENOTEMPTY when written into meanwhile: kept
          res.removed.push(name);
          res.swept.push({ name, why: 'an empty fallback dir' });
        } catch (e) {
          if (e?.code !== 'ENOTEMPTY' && e?.code !== 'ENOENT') res.failed.push(name);
        }
      }
      continue;
    }
    if (isHeld(o, starts, alive)) {
      res.kept.push(name);
      if (isKept(o)) keptLive.push({ name, p });
      continue;
    }
    if (isKept(o)) {
      let only = false;
      try { only = FALLBACK_RE.test(name) && fs.readdirSync(p).every((n) => n === OWNER_FILE); } catch {}
      if (only) { take(name, p, raw, o, 'a kept fallback dir holding nothing, its owner gone'); continue; }
      keptDead.push({ name, p, raw, o, t: lastTouched(p, st) });
      continue;
    }
    if (hours > 0 && (now - lastTouched(p, st)) / 3_600_000 < hours) { res.kept.push(name); continue; }
    take(name, p, raw, o, 'its owner gone');
  }
  // the kept dirs whose owner is gone: by age, then oldest first while the kept total is past the cap
  const left = [];
  for (const k of keptDead) {
    const h = (now - k.t) / 3_600_000;
    if (keptHours > 0 && h >= keptHours) take(k.name, k.p, k.raw, k.o, `kept, its owner gone, untouched ${Math.floor(h)} h (bound ${keptHours} h)`);
    else left.push(k);
  }
  if (keptCapBytes > 0 && (left.length || keptLive.length)) {
    for (const k of [...keptLive, ...left]) { k.bytes = sizeOf(k.p); res.keptBytes += k.bytes; }
    left.sort((a, b) => a.t - b.t);
    const cap = (b) => `${(b / GIB).toFixed(2)} GiB`;
    while (res.keptBytes > keptCapBytes && left.length) {
      const k = left.shift();
      if (take(k.name, k.p, k.raw, k.o, `kept, its owner gone, the oldest while kept dirs total ${cap(res.keptBytes)} (cap ${cap(keptCapBytes)})`, k.bytes)) res.keptBytes -= k.bytes;
    }
    res.overCap = res.keptBytes > keptCapBytes;
  }
  for (const k of left) res.kept.push(k.name);
  return res;
}
