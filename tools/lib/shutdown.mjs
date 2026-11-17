// One owner of an e2e driver's SIGINT/SIGTERM/SIGHUP (batch-074 GPT review P2): the order on a signal is
//   1. stop: every registered stop step runs (a --clients lead forwards the signal to its child drivers and waits for them; a single driver quits
//      its client and kills the client JVM and its launcher by PID), each bounded, ending in SIGKILL of its own children;
//   2. release: the slots go back to Architect's queue (slot-queue), only once nothing of this run is still running;
//   3. cleanup: the run's owned temp dirs go (tmp-owner: the sidecar data, no longer written by a live JVM);
//   4. exit 128 + the signal's number.
// Before, slot-queue released and exited at once and tmp-owner removed the sidecar data at once, while the clients kept running outside the queue's
// capacity. A second signal while shutting down is ignored (a terminal's Ctrl-C reaches the whole process group, so a lane's child gets SIGINT
// directly and again forwarded); the bounds guarantee the exit. The 'exit' hooks of slot-queue and tmp-owner stay, for the other ways out.
// Never by pattern: only the pids this process started or recorded.
import { execFileSync } from 'node:child_process';

export const SIGNALS = { SIGINT: 2, SIGTERM: 15, SIGHUP: 1 };
const PHASES = ['release', 'cleanup'];

const stops = new Set();
const finals = { release: [], cleanup: [] };
let installed = false;
let shuttingDown = null;

/** Registers an async stop step (sig) => Promise; -> a function that unregisters it. */
export function onStop(fn) {
  stops.add(fn);
  return () => stops.delete(fn);
}

/** Registers a synchronous step of a phase ('release' runs before 'cleanup'), run once on a signal after every stop step. */
export function onFinal(phase, fn) {
  if (!PHASES.includes(phase)) throw new Error(`shutdown: no phase ${phase}`);
  if (!finals[phase].includes(fn)) finals[phase].push(fn);
}

/** Whether the coordinator owns the signals (tmp-owner and slot-queue then leave a signal to it). */
export const isInstalled = () => installed;

/** Whether a signal's shutdown has begun. */
export const shuttingDownNow = () => shuttingDown !== null;

/**
 * The shutdown for `sig`: stop steps (all at once, each bounded by itself), then release, then cleanup, then exit(128 + n). A step that throws is
 * logged and the order goes on. Once per process: a later call returns the first one's promise.
 */
export function shutdown(sig, { exit = (c) => process.exit(c), log = (m) => console.error(m) } = {}) {
  if (shuttingDown) return shuttingDown;
  shuttingDown = (async () => {
    log(`shutdown: ${sig}: stopping this run's clients first, then its slots, then its temp dirs`);
    await Promise.all([...stops].map(async (fn) => {
      try { await fn(sig); } catch (e) { log(`shutdown: a stop step failed: ${e?.message ?? e}`); }
    }));
    for (const phase of PHASES) {
      for (const fn of finals[phase]) {
        try { fn(); } catch (e) { log(`shutdown: ${phase} failed: ${e?.message ?? e}`); }
      }
    }
    exit(128 + (SIGNALS[sig] ?? 0));
  })();
  return shuttingDown;
}

/** Takes the signals (idempotent). */
export function install() {
  if (installed) return;
  installed = true;
  for (const sig of Object.keys(SIGNALS)) {
    process.on(sig, () => {
      if (shuttingDown) { console.error(`shutdown: ${sig} again: already shutting down (bounded), ignored`); return; }
      shutdown(sig);
    });
  }
}

/** Resets the coordinator (tests only). */
export function _reset() {
  stops.clear();
  finals.release.length = 0;
  finals.cleanup.length = 0;
  shuttingDown = null;
}

// ------------------------------------------------------------------ stopping processes by PID

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Whether pid runs (kill 0; EPERM counts as running). */
export function pidRuns(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
}

/** Waits up to ms for done() to hold, polling every step ms; -> whether it held. */
export async function waitFor(done, ms, step = 100) {
  const t0 = Date.now();
  while (!done()) {
    if (Date.now() - t0 >= ms) return false;
    await sleep(step);
  }
  return true;
}

/**
 * Stops one process this run started, by its pid: `sig` to it, up to graceMs for it to exit, then SIGKILL and up to killMs more.
 * `exited()` says it is gone (a ChildProcess's exit seen; default: the pid no longer runs). `same()` re-checks before each kill that the pid is
 * still that process (default: yes; a recorded JVM pid passes its command line check). -> 'gone' | 'exited' | 'killed' | 'stuck'.
 */
export async function stopPid(pid, { sig = 'SIGTERM', graceMs = 15_000, killMs = 5_000, exited = () => !pidRuns(pid), same = () => true, kill = process.kill.bind(process), log = () => {} } = {}) {
  if (exited()) return 'gone';
  if (!same()) return 'gone';
  try { kill(pid, sig); } catch {}
  if (await waitFor(exited, graceMs)) return 'exited';
  if (!same()) return 'exited';
  log(`shutdown: pid ${pid} still runs ${Math.round(graceMs / 1000)} s after ${sig}: SIGKILL`);
  try { kill(pid, 'SIGKILL'); } catch {}
  return (await waitFor(exited, killMs)) ? 'killed' : 'stuck';
}

/** A ChildProcess's exit as a check (exitCode or signalCode set). */
export const childExited = (child) => () => child.exitCode !== null || child.signalCode !== null;

/** The pid listening on a local TCP port (the client JVM's DevBridge), or null; by port, never by a command pattern. */
export function listenerPid(port) {
  try {
    const out = execFileSync('lsof', ['-nP', '-t', `-iTCP:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    const pids = out.split('\n').map((l) => Number(l.trim())).filter((n) => Number.isInteger(n) && n > 0);
    return pids.length === 1 ? pids[0] : null;
  } catch {
    return null;
  }
}

/** A pid's command line, or null when it doesn't run. */
export function commandOf(pid) {
  try {
    return execFileSync('ps', ['-o', 'command=', '-p', String(pid)], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim() || null;
  } catch {
    return null;
  }
}
