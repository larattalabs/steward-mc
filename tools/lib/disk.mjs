// Disk use of e2e runs (slice E2E-DISK, 2026-11-21): Minecraft/_work grew from 89 GB to about 977 GB, Steward's share about 684 GB, mostly e2e
// worlds left in hundreds of slice and batch worktrees (200-470 MB each, a second one kept as "<name> (last)"). Two parts, both pure with their
// filesystem calls injected (test/disk.test.mjs):
//   - the cleanup after a run: what the run made is deleted when it passed, kept (and named, with how to delete it) when it didn't;
//   - the free-space guard: e2e.mjs and nightly.mjs refuse to start below E2E_MIN_FREE_GIB (default 150) free on the worktree's volume, exit 5.
// mod/DEV.md "Disk: cleanup after a run, the free-space guard".
import fs from 'node:fs';
import path from 'node:path';

export const DEFAULT_MIN_FREE_GIB = 150;
/** e2e.mjs and nightly.mjs exit with this when they refuse for disk space (2 is usage, 3 a slot that didn't come; 4 is the slot CLI's own). */
export const EXIT_LOW_DISK = 5;
const GIB = 1024 ** 3;

// ------------------------------------------------------------------ the guard

/** The threshold in GiB from E2E_MIN_FREE_GIB: unset or empty -> 150; a number >= 0 -> it (0 turns the guard off); anything else -> 150, said. */
export function minFreeGiB(env = process.env) {
  const raw = env.E2E_MIN_FREE_GIB;
  if (raw == null || String(raw).trim() === '') return { gib: DEFAULT_MIN_FREE_GIB };
  const n = Number(raw);
  if (Number.isFinite(n) && n >= 0) return { gib: n };
  return { gib: DEFAULT_MIN_FREE_GIB, warning: `E2E_MIN_FREE_GIB=${JSON.stringify(raw)} is not a number >= 0: using ${DEFAULT_MIN_FREE_GIB}` };
}

/** The nearest existing dir at or above p (a worktree not made yet is judged on its parent's volume). */
export function existingAncestor(p, exists = fs.existsSync) {
  let at = path.resolve(p);
  while (!exists(at)) {
    const up = path.dirname(at);
    if (up === at) break;
    at = up;
  }
  return at;
}

/** Bytes free to this user on the volume holding p (statfs: bavail * bsize, what `df -k` shows as Available). Never walks the disk. */
export function freeBytes(p, { statfs = fs.statfsSync, exists = fs.existsSync } = {}) {
  const s = statfs(existingAncestor(p, exists));
  return Number(s.bavail) * Number(s.bsize);
}

/**
 * Whether a run may start on the volume holding dir. -> {ok, dir, freeGiB, minGiB, message}. A statfs error fails open (ok, with a warning): the
 * guard is there to stop a full disk, not to block runs on a reader that broke.
 */
export function checkFreeSpace(dir, { env = process.env, statfs = fs.statfsSync, exists = fs.existsSync } = {}) {
  const { gib: minGiB, warning } = minFreeGiB(env);
  const warnings = warning ? [warning] : [];
  if (minGiB === 0) return { ok: true, dir, freeGiB: null, minGiB, warnings, message: 'free-space guard off (E2E_MIN_FREE_GIB=0)' };
  let free;
  try {
    free = freeBytes(dir, { statfs, exists });
    if (!Number.isFinite(free)) throw new Error('statfs gave no number');
  } catch (e) {
    warnings.push(`free-space guard: could not read free space for ${dir} (${e.message}); starting anyway`);
    return { ok: true, dir, freeGiB: null, minGiB, warnings, message: warnings.at(-1) };
  }
  const freeGiB = Math.round((free / GIB) * 10) / 10;
  if (free >= minGiB * GIB) return { ok: true, dir, freeGiB, minGiB, warnings, message: `free space ${freeGiB} GiB (at least ${minGiB} needed)` };
  return {
    ok: false, dir, freeGiB, minGiB, warnings,
    message: `refusing to start: ${freeGiB} GiB free on the volume holding ${dir}, below ${minGiB} GiB. Free space first (old worktrees under ` +
      `Minecraft/_work, e2e worlds kept after failed runs), or set E2E_MIN_FREE_GIB=<GiB> (0 turns the guard off). Exit ${EXIT_LOW_DISK}.`,
  };
}

// ------------------------------------------------------------------ the cleanup after a run

/**
 * Why a run's things are kept, or null when they go. Kept on any failure, so it can be inspected: a failed check, a run that threw, no checks at
 * all, a signal's shutdown. A slot wait made nothing new (the world is made by the client, which never started), so nothing is kept for it.
 */
export function keepReason({ failed = 0, checks = 0, slotWait = false, shuttingDown = false } = {}) {
  if (shuttingDown) return 'stopped by a signal';
  if (failed > 0) return `${failed} check(s) failed`;
  if (!slotWait && checks === 0) return 'no checks ran';
  return null;
}

/** Whether p is strictly inside root (resolved; root itself is not inside). */
export function isInside(p, root) {
  const rel = path.relative(path.resolve(root), path.resolve(p));
  return !!rel && !rel.startsWith('..') && !path.isAbsolute(rel);
}

/** A shell-quoted path for the delete note ("Steward E2E" has a space). */
const q = (p) => `'${String(p).replace(/'/g, `'\\''`)}'`;

/** The one-line note on how to delete what a run kept. */
export const deleteNote = (paths) => (paths.length ? `delete when done looking: rm -rf ${paths.map(q).join(' ')}` : null);

/**
 * Deletes what a run made, or keeps it. items: [{path, root, what, always?, hold?, protect?, verify?}]: each path must be strictly inside its root (else
 * it is never touched: 'outside'), is lstat'ed first (a symlink is unlinked, never followed), and goes unless the run is kept (`keep`, a reason) or
 * `hold` (a reason this one item stays, e.g. a client JVM still on the game dir). `protect` (a reason) keeps it whatever the run did, named by that
 * reason (--keep-world's world: 'keep-world'). `always` items (temp inputs) go even when the run is kept. `verify` (batch 091 review), called just
 * before the delete, answers null when the item is still this run's, else why not: then it is not touched (reported in failed).
 * -> {deleted: [path], kept: [{path, what, why}], failed: [{path, why}], note}. Never throws.
 */
export function cleanupRun(items, { keep = null, hold = null, lstat = fs.lstatSync, rm = (p) => fs.rmSync(p, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 }), unlink = fs.unlinkSync } = {}) {
  const out = { deleted: [], kept: [], failed: [] };
  for (const it of items) {
    if (!it?.path || !it.root || !isInside(it.path, it.root)) { out.failed.push({ path: it?.path ?? null, why: `outside ${it?.root ?? 'its root'}: not touched` }); continue; }
    let st = null;
    try { st = lstat(it.path); } catch { continue; } // gone already (or never made)
    const why = it.always ? null : (it.protect ?? keep ?? it.hold ?? hold);
    if (why) { out.kept.push({ path: it.path, what: it.what, why }); continue; }
    let mine;
    try { mine = it.verify ? it.verify() : null; } catch (e) { mine = `ownership not checked (${e.message})`; }
    if (mine) { out.failed.push({ path: it.path, why: `${mine}: not touched` }); continue; }
    try {
      if (st.isSymbolicLink()) unlink(it.path);
      else rm(it.path);
      out.deleted.push(it.path);
    } catch (e) {
      out.failed.push({ path: it.path, why: e.message });
    }
  }
  out.note = deleteNote(out.kept.map((k) => k.path));
  return out;
}

/**
 * A single driver's own items (not a --clients lead's): its world under saves, and its temp blueprint dirs. (batch 091 review P2) With --keep-world
 * the world is an item still, protected ('keep-world'): the run's record says it was kept, so a lead never takes the lane for empty.
 */
export function runItems({ world, saves, keepWorld = false, tempInputs = [], tmp }) {
  return [
    { path: world, root: saves, what: 'the e2e world', ...(keepWorld ? { protect: 'keep-world' } : {}) },
    ...tempInputs.map((p) => ({ path: p, root: tmp, what: 'a temp blueprint', always: true })),
  ];
}

/** The owner marker a --clients lead writes into a run-c<k> it made (batch 091 review P1). */
export const OWNER_FILE = '.steward-e2e-owner.json';

/**
 * (batch 091 review P1) Claims a lane's run dir for this run at the moment of use: mkdir without -p (it fails when the dir is there: made by another
 * run, or left from an earlier one), then the owner marker {run, pid} written exclusively. -> {claimed: true} or {claimed: false, why}. Never throws.
 */
export function claimRunDir(dir, owner, { mkdir = (p) => fs.mkdirSync(p), writeFile = fs.writeFileSync } = {}) {
  try { mkdir(dir); } catch (e) { return { claimed: false, why: e?.code === 'EEXIST' ? 'it existed before this run used it' : `not made (${e?.message})` }; }
  try { writeFile(path.join(dir, OWNER_FILE), JSON.stringify(owner), { flag: 'wx' }); } catch (e) { return { claimed: false, why: `owner marker not written (${e?.message})` }; }
  return { claimed: true };
}

/** Null when dir's owner marker names this run (its run id and pid), else why not. Never throws. */
export function ownsRunDir(dir, owner, { readFile = (p) => fs.readFileSync(p, 'utf8') } = {}) {
  if (!owner?.run) return 'no owner to check against';
  let m;
  try { m = JSON.parse(readFile(path.join(dir, OWNER_FILE))); } catch { return 'no owner marker'; }
  return m?.run === owner.run && m?.pid === owner.pid ? null : `not this run's (its marker names ${m?.run ?? '?'}, pid ${m?.pid ?? '?'})`;
}

/**
 * A --clients lead's own part: the run dirs `<mod>/run-c<k>` it claimed this run ({@link claimRunDir}), as cleanup items. Each is held (kept) unless
 * every lane that ran on it exited 0, wrote a summary with no failure and kept nothing; a dir no lane ran on is held too, and every one under the
 * lead's --keep-world (batch 091 review P2). Each is checked again, just before its delete, to hold this run's owner marker (`owner`).
 * Never mod/run, never a run-c<k> this run didn't claim (it isn't in `created`), never anything not named run-c<k> right under mod.
 */
export function laneRunDirs({ modDir, created, lanes, owner = null, readFile, keepWorld = false }) {
  const items = [];
  for (const dir of created) {
    if (!/^run-c\d+$/.test(path.basename(dir)) || path.dirname(path.resolve(dir)) !== path.resolve(modDir)) continue;
    const on = lanes.filter((l) => l?.gameDir && path.resolve(l.gameDir) === path.resolve(dir));
    const bad = on.find((l) => l.code !== 0 || !l.summary || l.summary.failed > 0 || l.summary.cleanup?.kept?.length);
    const hold = keepWorld ? '--keep-world: its world is kept' : !on.length ? 'no client ran on it' : bad ? `client c${bad.k ?? '?'} ${bad.code !== 0 ? `exited ${bad.code}` : !bad.summary ? 'wrote no summary' : bad.summary.failed > 0 ? `had ${bad.summary.failed} failure(s)` : 'kept its world'}` : null;
    items.push({ path: dir, root: modDir, what: 'a client run dir this run made', ...(hold ? { hold } : {}), verify: () => ownsRunDir(dir, owner, readFile ? { readFile } : {}) });
  }
  return items;
}

/** Adds one cleanup record's lists to another's and redoes the note. */
export function addCleanup(into, more) {
  into.deleted.push(...more.deleted);
  into.kept.push(...more.kept);
  into.failed.push(...more.failed);
  into.note = deleteNote(into.kept.map((k) => k.path));
  return into;
}

/** Merges the cleanup records of a --clients run's children (each result naming its client). */
export function mergeCleanups(rows) {
  const out = { deleted: [], kept: [], failed: [] };
  for (const { client, cleanup } of rows) {
    if (!cleanup) continue;
    out.deleted.push(...(cleanup.deleted ?? []));
    out.kept.push(...(cleanup.kept ?? []).map((k) => ({ ...k, client })));
    out.failed.push(...(cleanup.failed ?? []).map((k) => ({ ...k, client })));
  }
  out.note = deleteNote(out.kept.map((k) => k.path));
  return out;
}
