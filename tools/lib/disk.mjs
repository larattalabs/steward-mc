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
 * Deletes what a run made, or keeps it. items: [{path, root, what, always?}]: each path must be strictly inside its root (else it is never touched:
 * 'outside'), is lstat'ed first (a symlink is unlinked, never followed), and goes unless the run is kept (`keep`, a reason) or `hold` (a reason
 * this one item stays, e.g. a client JVM still on the game dir). `always` items (temp inputs) go even when the run is kept.
 * -> {deleted: [path], kept: [{path, what, why}], failed: [{path, why}], note}. Never throws.
 */
export function cleanupRun(items, { keep = null, hold = null, lstat = fs.lstatSync, rm = (p) => fs.rmSync(p, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 }), unlink = fs.unlinkSync } = {}) {
  const out = { deleted: [], kept: [], failed: [] };
  for (const it of items) {
    if (!it?.path || !it.root || !isInside(it.path, it.root)) { out.failed.push({ path: it?.path ?? null, why: `outside ${it?.root ?? 'its root'}: not touched` }); continue; }
    let st = null;
    try { st = lstat(it.path); } catch { continue; } // gone already (or never made)
    const why = it.always ? null : (keep ?? hold);
    if (why) { out.kept.push({ path: it.path, what: it.what, why }); continue; }
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
