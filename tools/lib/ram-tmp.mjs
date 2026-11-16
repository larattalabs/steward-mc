// Throwaway test data on the RAM disk (Noah, 2026-11-14: "yes, set up the RAM disk"): /Volumes/MCTmp is a 24 GB RAM disk made at login by the
// LaunchAgent com.laratta.mctmp-ramdisk, so the e2e's and the nightly's temp files cost no SSD writes. Its contents go at a reboot, which is fine
// for temp files. When the volume is missing or not writable, or STEWARD_RAM_TMP=0, TMPDIR stays as it was.
import fs from 'node:fs';
import path from 'node:path';
import { own as ownDir, readOwner, isHeld, processStarts } from './tmp-owner.mjs';

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
 */
export function ramSidecarData(gameDir, env = process.env, root = RAM_TMP, { fresh = false, own = false, starts } = {}) {
  const link = path.join(gameDir, 'architect', 'sidecar-data');
  fs.mkdirSync(path.dirname(link), { recursive: true });
  let st = null;
  try { st = fs.lstatSync(link); } catch {}
  const up = env.STEWARD_RAM_TMP !== '0' && (() => { try { fs.accessSync(root, fs.constants.W_OK); return fs.statSync(root).isDirectory(); } catch { return false; } })();
  if (!up) {
    let made = false;
    if (st?.isSymbolicLink() && !fs.existsSync(link)) { fs.unlinkSync(link); fs.mkdirSync(link); made = true; }
    else if (!st) { fs.mkdirSync(link); made = true; }
    else if (own && st.isDirectory()) {
      // an earlier run's own dir, left by a hard kill: it is Steward's to take again
      const o = readOwner(link);
      if (o && !isHeld(o, starts ?? processStarts())) {
        if (fresh) { fs.rmSync(link, { recursive: true, force: true }); fs.mkdirSync(link); }
        made = true;
      }
    }
    if (own && made) ownDir(link, { extra: { tool: 'steward-e2e', gameDir: path.resolve(gameDir) } });
    return { mode: 'disk', dir: link, owned: own && made };
  }
  const hash = (s) => { let h = 2166136261; for (const c of s) h = Math.imul(h ^ c.codePointAt(0), 16777619) >>> 0; return h.toString(16); };
  const target = path.join(root, 'steward', `sidecar-${hash(path.resolve(gameDir))}`);
  // fresh (an e2e run, whose world is new): start from an empty directory. The sidecar's designs, bibles and jobs pile up run after run
  // (6 GB in one checkout by 11-15), and a 24 GB RAM disk shared by every worktree filled up (ENOSPC) mid-run
  if (fresh) fs.rmSync(target, { recursive: true, force: true });
  fs.mkdirSync(target, { recursive: true });
  if (own) ownDir(target, { link, extra: { tool: 'steward-e2e', gameDir: path.resolve(gameDir) } });
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
