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
 * the world is an item still, protected ('keep-world'): the run's record says it was kept, so a lead never takes the lane for empty. (E2E-DISK-2)
 * `protect` names any other fixed-name world (an explicit ARCHITECT_AUTOWORLD_NAME); an unprotected world goes only while its owner marker
 * ({@link markWorld}) still names `owner`, read just before the delete: no owner, no marker or another run's, and it is not touched.
 */
export function runItems({ world, saves, keepWorld = false, protect = null, owner = null, readFile, slotWait = false, tempInputs = [], tmp }) {
  const guard = protect ?? (keepWorld ? 'keep-world' : null);
  return [
    // (batch 091 final review P1, switched off per Noah 11-21) a driver that never got its slot never launched, so the world under its game dir
    // may be another driver's, made during the wait: it is not an item at all. Its temp blueprints (mkdtemp, unique) still go.
    ...(slotWait ? [] : [{ path: world, root: saves, what: 'the e2e world', ...(guard ? { protect: guard } : {}), verify: () => ownsRunDir(world, owner, readFile ? { readFile } : {}) }]),
    ...tempInputs.map((p) => ({ path: p, root: tmp, what: 'a temp blueprint', always: true })),
  ];
}

// ------------------------------------------------------------------ E2E-DISK-2: per-run names, so every delete is provably this run's

/**
 * This run's tag (E2E-DISK-2): base36 time, pid and a random part, [a-z0-9_] only (an Architect blueprint id's characters). It names the run's world (`Steward E2E <tag>`), a --clients
 * lead's lane dirs (`mod/run-c<k>-<tag>`), its library stub entries and its owner markers, so no other run ever computes the same path.
 */
export function runTag({ now = Date.now(), pid = process.pid, rand = Math.random } = {}) {
  return `${now.toString(36)}_${pid.toString(36)}${Math.floor(rand() * 36 ** 4).toString(36).padStart(4, '0')}`;
}

/** The fixed e2e world name: --keep-world's, and every run's before E2E-DISK-2 (so the name of the leftovers a run never touches). */
export const E2E_WORLD_NAME = 'Steward E2E';

/**
 * This driver's world (E2E-DISK-2): `Steward E2E <tag>`, new each run (perRun) and its to delete once it passed. With --keep-world, the fixed name
 * (or ARCHITECT_AUTOWORLD_NAME), protected 'keep-world'; an explicit ARCHITECT_AUTOWORLD_NAME without it, that world, protected too (a world
 * someone named may hold anything). -> {name, path, perRun, protect}.
 */
export function e2eWorld({ saves, tag, keepWorld = false, env = process.env }) {
  const explicit = String(env.ARCHITECT_AUTOWORLD_NAME ?? '').trim() || null;
  const name = keepWorld ? explicit ?? E2E_WORLD_NAME : explicit ?? `${E2E_WORLD_NAME} ${tag}`;
  const protect = keepWorld ? 'keep-world' : explicit ? 'a named world (ARCHITECT_AUTOWORLD_NAME): never deleted' : null;
  return { name, path: path.join(saves, name), perRun: !keepWorld && !explicit, protect };
}

/**
 * Before the first launch (E2E-DISK-2): nothing is moved or deleted. A per-run world must not be there yet (else it is not this run's: refused);
 * a fixed-name world opens as it is. `earlier` names the other e2e worlds under saves (old `Steward E2E`, `(last)`, other runs' kept worlds),
 * left as they are. -> {ok, why?, earlier}.
 */
export function worldAtStart(world, { exists = fs.existsSync, list = (d) => fs.readdirSync(d) } = {}) {
  let earlier = [];
  try { earlier = list(path.dirname(world.path)).filter((n) => n.startsWith(E2E_WORLD_NAME) && n !== world.name); } catch { /* no saves yet */ }
  if (world.perRun && exists(world.path)) return { ok: false, why: `${world.path} is already there: not this run's world (refused)`, earlier };
  return { ok: true, earlier };
}

/**
 * After the client made the world (E2E-DISK-2): this run's owner marker, written exclusively ('wx') into it. Null when written, else why not
 * (then the world is never this run's to delete). Fixed-name worlds aren't marked (they are protected). Never throws.
 */
export function markWorld(world, owner, { exists = fs.existsSync, writeFile = fs.writeFileSync } = {}) {
  if (!world.perRun) return null;
  if (!exists(world.path)) return `${world.path} is not there: the client made no world of this name`;
  try { writeFile(path.join(world.path, OWNER_FILE), JSON.stringify(owner), { flag: 'wx' }); } catch (e) { return `owner marker not written (${e?.code ?? e?.message})`; }
  return null;
}

/**
 * (E2E-DISK-2) The names among `files` ({name: bytes}) whose bytes in dir differ or are missing: the fixed `e2e_stub` library entry is written only
 * there (each through a temp file and a rename), never removed, so a run on the same game dir never sees it gone.
 */
export function filesToWrite(dir, files, readFile = fs.readFileSync) {
  return Object.keys(files).filter((n) => {
    try { return !Buffer.from(readFile(path.join(dir, n))).equals(Buffer.from(files[n])); } catch { return true; }
  });
}

/** A --clients lead's lane dir name for client k (E2E-DISK-2): its own per run, so no other run can reuse it. */
export const laneDirName = (k, tag) => `run-c${k}-${tag}`;

/**
 * (batch 091 final review P1, Noah 11-21: switched off until E2E-DISK-2) A --clients lead claims and deletes its lanes' dirs. On again with
 * E2E-DISK-2: each lane dir is this run's own by name (`run-c<k>-<tag>`, {@link laneDirName}), made exclusively at its use, and its marker is
 * read just before the delete, so no other run can reuse it between the claim and the delete. Old plain `run-c<k>` dirs are never items.
 */
export const DELETE_LANE_DIRS = true;

/** The owner marker a --clients lead writes into a lane dir it made (batch 091 review P1), and a driver into its world (E2E-DISK-2). */
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
 * A --clients lead's own part: the lane dirs `<mod>/run-c<k>-<tag>` it claimed this run ({@link claimRunDir}), as cleanup items. Each is held (kept)
 * unless every lane that ran on it exited 0, wrote a summary with no failure and kept nothing; a dir no lane ran on is held too, and every one under
 * the lead's --keep-world (batch 091 review P2). Each is checked again, just before its delete, to hold this run's owner marker (`owner`).
 * Never mod/run, never a dir this run didn't claim (it isn't in `created`), never anything not named run-c<k>-<this run's tag> right under mod
 * (E2E-DISK-2: an old plain run-c<k>, or another run's, is never an item; no tag, no items).
 */
export function laneRunDirs({ modDir, created, lanes, owner = null, tag = null, readFile, keepWorld = false }) {
  const items = [];
  if (!tag || !/^[a-z0-9_]+$/.test(tag)) return items;
  for (const dir of created) {
    const m = /^run-c(\d+)-(.+)$/.exec(path.basename(dir));
    if (!m || m[2] !== tag || path.dirname(path.resolve(dir)) !== path.resolve(modDir)) continue;
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
