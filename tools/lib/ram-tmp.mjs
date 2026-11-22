// Throwaway test data on the RAM disk (Noah, 2026-11-14: "yes, set up the RAM disk"): /Volumes/MCTmp is a 24 GB RAM disk made at login by the
// LaunchAgent com.laratta.mctmp-ramdisk, so the e2e's and the nightly's temp files cost no SSD writes. Its contents go at a reboot, which is fine
// for temp files. When the volume is missing or not writable, or STEWARD_RAM_TMP=0, TMPDIR stays as it was.
import fs from 'node:fs';
import path from 'node:path';
import { own as ownDir, readOwner, isHeld, isKept, ownedByMe, ownerGone, processStarts } from './tmp-owner.mjs';

export const RAM_TMP = '/Volumes/MCTmp/tmp';

/** Points TMPDIR (read by os.tmpdir() and passed on to child processes) at <RAM_TMP>/steward; returns that dir, or null when it stays as it was. */
export function useRamTmp(env = process.env, root = RAM_TMP) {
  if (env.STEWARD_RAM_TMP === '0') return null;
  try {
    if (!fs.statSync(root).isDirectory()) return null;
    fs.accessSync(root, fs.constants.W_OK);
    const dir = path.join(root, 'steward');
    fs.mkdirSync(dir, { recursive: true });
    env.TMPDIR = dir + '/';
    return dir;
  } catch {
    return null;
  }
}

const ramUp = (env, root) => env.STEWARD_RAM_TMP !== '0' && (() => { try { fs.accessSync(root, fs.constants.W_OK); return fs.statSync(root).isDirectory(); } catch { return false; } })();

/**
 * (slice LINK-SIDECAR) Repairs `<gameDir>/architect/sidecar-data` when it is a symlink whose target is gone: an earlier e2e linked it to the RAM
 * disk and a reboot wiped that dir, so Architect's launcher throws FileAlreadyExistsException on the dangling link and the sidecar never starts.
 * Every tool that launches a client on a game dir calls it first (ramSidecarData does for the e2e). When the volume is up and the link points
 * under root, its target is made again, empty (the data stays on the RAM disk); otherwise the link is replaced by an empty directory. A live
 * link (to a directory), a real directory or a missing path is left as it is. Returns 'relinked' | 'replaced' | null (nothing to repair).
 *
 * (E2E-DISK-5) A target that is there but isn't a directory (a plain file, or a symlink: dangling, or into a chain the launcher can't use) is
 * never touched, it isn't Steward's: the link is replaced by an empty directory instead. A link to a file counts as broken too.
 */
export function repairSidecarData(gameDir, env = process.env, root = RAM_TMP) {
  const link = path.join(gameDir, 'architect', 'sidecar-data');
  let st = null;
  try { st = fs.lstatSync(link); } catch { return null; }
  if (!st.isSymbolicLink()) return null;
  try { if (fs.statSync(link).isDirectory()) return null; } catch {}
  const target = path.resolve(path.dirname(link), fs.readlinkSync(link));
  const rel = path.relative(path.resolve(root), target);
  let there = true;
  try { fs.lstatSync(target); } catch { there = false; }
  if (!there && ramUp(env, root) && rel && !rel.startsWith('..') && !path.isAbsolute(rel)) {
    fs.mkdirSync(target, { recursive: true });
    return 'relinked';
  }
  fs.unlinkSync(link);
  fs.mkdirSync(link);
  return 'replaced';
}

/**
 * The bundled Architect sidecar's data (`<gameDir>/architect/sidecar-data`: jobs, designs, plans, its npm install) on the RAM disk. The machine
 * audit of 2026-11-14 measured about 3 GB written per worktree per 30 minutes there. When the volume is up, the directory becomes a symlink to
 * <root>/steward/sidecar-<hash of gameDir>, one per checkout, which persists between runs until a reboot. An existing on-disk directory is renamed
 * to `sidecar-data.ssd`, never deleted. When the volume is down, a symlink left dangling by a reboot is replaced by an empty directory, and a
 * real directory is left as it is. Returns {mode: 'ram'|'disk', dir, moved?, owned}.
 *
 * own (slice TMPSWEEP; an e2e run): the dir this call makes is owner-marked and removed when this process ends (tools/lib/tmp-owner.mjs), the
 * RAM-disk link with it. On the RAM disk that is the target; on disk (STEWARD_RAM_TMP=0, no volume) only a dir this call created, or one an
 * earlier run owned whose owner is gone (with fresh, emptied first), never an existing dir of anyone else's (a dev client's data).
 *
 * fresh (E2E-DISK-3): only a dir this run owns, or whose owner is provably gone, is emptied; the empty unmarked dir a run leaves is taken as it is.
 * A dir marked `kept` (STEWARD_KEEP_TMP=1) or holding unmarked data (a dev client's) is left as it is and the run takes a new dir of its own
 * (`sidecar-<hash>-XXXXXX`, made exclusively). A dir held by another live run (one in its slot wait) refuses the run: nothing is emptied or relinked.
 * (E2E-DISK-4) Such a fallback dir is removed at the run's end (not made again empty) and the link goes back to the game dir's own dir.
 */
export function ramSidecarData(gameDir, env = process.env, root = RAM_TMP, { fresh = false, own = false, starts } = {}) {
  const link = path.join(gameDir, 'architect', 'sidecar-data');
  fs.mkdirSync(path.dirname(link), { recursive: true });
  let st = null;
  try { st = fs.lstatSync(link); } catch {}
  const up = ramUp(env, root);
  if (!up) {
    let made = false;
    if (st?.isSymbolicLink()) made = repairSidecarData(gameDir, env, root) === 'replaced';
    else if (!st) { fs.mkdirSync(link); made = true; }
    else if (own && st.isDirectory()) {
      // an earlier run's own dir, left by a hard kill: it is Steward's to take again
      const o = readOwner(link);
      if (o && !isKept(o) && !isHeld(o, starts ?? processStarts())) {
        if (fresh) { fs.rmSync(link, { recursive: true, force: true }); fs.mkdirSync(link); }
        made = true;
      }
    }
    if (own && made) ownDir(link, { env, extra: { tool: 'steward-e2e', gameDir: path.resolve(gameDir) } });
    return { mode: 'disk', dir: link, owned: own && made };
  }
  const hash = (s) => { let h = 2166136261; for (const c of s) h = Math.imul(h ^ c.codePointAt(0), 16777619) >>> 0; return h.toString(16); };
  const primary = path.join(root, 'steward', `sidecar-${hash(path.resolve(gameDir))}`);
  let target = primary;
  // fresh (an e2e run, whose world is new): start from an empty directory. The sidecar's designs, bibles and jobs pile up run after run
  // (6 GB in one checkout by 11-15), and a 24 GB RAM disk shared by every worktree filled up (ENOSPC) mid-run
  if (fresh) {
    // (E2E-DISK-3) the link's current target first: a live run's dir (a kept one, or one beside the game dir's) is never relinked away from it
    if (st?.isSymbolicLink()) refuseLive(path.resolve(path.dirname(link), fs.readlinkSync(link)), gameDir, starts);
    target = freshTarget(target, gameDir, starts);
  }
  fs.mkdirSync(target, { recursive: true });
  // (E2E-DISK-4) a fallback dir is removed at the run's end, the link going back to the game dir's own dir
  if (own) ownDir(target, { link, primary: target === primary ? null : primary, env, extra: { tool: 'steward-e2e', gameDir: path.resolve(gameDir) } });
  let moved = null;
  if (st?.isSymbolicLink()) {
    if (fs.readlinkSync(link) === target) return { mode: 'ram', dir: target, owned: own };
    fs.unlinkSync(link);
  } else if (st) {
    moved = link + '.ssd';
    if (fs.existsSync(moved)) moved = `${link}.ssd-${Date.now()}`;
    fs.renameSync(link, moved);
  }
  fs.symlinkSync(target, link);
  return { mode: 'ram', dir: target, moved, owned: own };
}

/**
 * (E2E-DISK-3) The dir a fresh run starts in, empty: the game dir's own `target` when it is absent, empty and unmarked, this process's, or left by a
 * run whose owner is provably gone (then emptied); else a new `<target>-XXXXXX` (mkdtemp, exclusive) beside it, leaving kept or unmarked data as it
 * is. Throws when another live run holds target: emptying or relinking it would pull its data from under it.
 */
function freshTarget(target, gameDir, starts) {
  let st = null;
  try { st = fs.lstatSync(target); } catch {}
  if (!st) return target;
  if (st.isDirectory()) {
    const o = refuseLive(target, gameDir, starts);
    if (o && !isKept(o)) {
      fs.rmSync(target, { recursive: true, force: true });
      return target;
    }
    if (!o && fs.readdirSync(target).length === 0) return target;
  }
  // kept data (STEWARD_KEEP_TMP), a dev client's data, or not a dir: left as it is
  return fs.mkdtempSync(`${target}-`);
}

/**
 * (E2E-DISK-3) Throws when dir (a sidecar dir: the game dir's, or the link's current target) is marked by another run that is live, or can't be told
 * gone, kept or not: emptying it or relinking away from it would pull its data from under it (say during its slot wait). -> its marker, or null.
 */
function refuseLive(dir, gameDir, starts) {
  let st = null;
  try { st = fs.lstatSync(dir); } catch {}
  if (!st?.isDirectory()) return null;
  const o = readOwner(dir);
  if (o && !ownedByMe(dir) && (isHeld(o, starts ?? processStarts()) || !ownerGone(o))) {
    throw new Error(`${dir} (the sidecar dir of ${path.resolve(gameDir)}) is held by another live run (pid ${o.pid}): refused, nothing emptied or relinked; let it finish first`);
  }
  return o;
}
