// Throwaway test data on the RAM disk (Noah, 2026-11-14: "yes, set up the RAM disk"): /Volumes/MCTmp is a 24 GB RAM disk made at login by the
// LaunchAgent com.laratta.mctmp-ramdisk, so the e2e's and the nightly's temp files cost no SSD writes. Its contents go at a reboot, which is fine
// for temp files. When the volume is missing or not writable, or STEWARD_RAM_TMP=0, TMPDIR stays as it was.
import fs from 'node:fs';
import path from 'node:path';

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
