// (SAVEB) A dev.command that really ran, and a save that really happened.
//
// Architect's DevBridge answers `ok: true` whenever its handler did not throw (S-M5-19), so `ok` alone says nothing about the command. dev.command's own
// verdict is `success` with `result`: an unknown or unparsable command ("Unknown or incomplete command", or a dedicated-server-only one such as /save-all on
// the integrated server) never reaches its result callback, so it answers `success: false, result: null`, its error text in `messages[]`. A command that
// ran and failed (a /kill that found nothing, a refused /steward ask) answers `success: false` with a number in `result`; flows read those refusals from
// `messages[]`, so that case is not an error here.
//
// There is no save command on the integrated server. The save barriers are Architect's: `dev.stockpile.save {}` once Architect has it (feature-detected
// through dev.help), else its periodic ledger save, turned back on and waited for until the saved state is the live one. `dev.world.leave` and `dev.quit`
// save on stop themselves, so a save before them is not needed.

/** A dev.command that did not run, or that the bridge refused; `response` is the bridge's answer. */
export class CommandNotRun extends Error {
  constructor(message, response) {
    super(message);
    this.name = 'CommandNotRun';
    this.response = response;
  }
}

/**
 * Checks a dev.command answer {@code r} for command {@code cmd}: throws {@link CommandNotRun} when the bridge refused the call (`ok: false`) or the command
 * never ran (`success: false`, `result: null`: unknown or unparsable), unless {@code allowFail}. A command that ran and failed passes. Returns {@code r}.
 */
export function checkCommand(r, { cmd = '?', allowFail = false } = {}) {
  if (allowFail) return r;
  if (!r || r.ok === false) throw new CommandNotRun(`dev.command ${cmd}: refused by the bridge: ${r?.error ?? 'no answer'}`, r);
  if (r.success === false && (r.result === null || r.result === undefined)) {
    throw new CommandNotRun(`dev.command ${cmd}: did not run (unknown or unparsable): ${(r.messages ?? []).join(' | ') || 'no message'}`, r);
  }
  return r;
}

/** Sends {@code cmd} through dev.command on {@code dev} and checks it ({@link checkCommand}); {@code opts.timeoutMs} for the request. */
export async function runCommand(dev, cmd, { allowFail = false, timeoutMs } = {}) {
  const r = await dev.request('dev.command', { cmd }, timeoutMs ? { timeoutMs } : {});
  return checkCommand(r, { cmd, allowFail });
}

const HELP = new WeakMap();

/** Whether the bridge on {@code dev} has request type {@code type} (its dev.help, read once per client). */
export async function hasRequest(dev, type) {
  if (!HELP.has(dev)) {
    HELP.set(dev, dev.request('dev.help').then((h) => Object.keys(h?.commands ?? {}), () => []));
  }
  return (await HELP.get(dev)).includes(type);
}

const nap = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Waits until the world is saved. With `dev.stockpile.save` on the bridge it is called (and must answer ok): `{how: 'save', ...answer}`. Else Architect's
 * periodic save is turned on (`dev.stockpile.ps {noPeriodic: false}`; it saves the world with the ledger at most every 200 ticks while the ledger is
 * unsaved) and `dev.stockpile.saved` is read every {@code pollMs} until the ledger is saved (`unsaved` false) and {@code match(saved)} holds (for example
 * the saved file's ledger equals the live one): `{how: 'periodic', saved, waitedMs}`. Throws when that does not happen within {@code timeoutMs}.
 *
 * The fallback is a ledger barrier, not a general save: with no ledger change pending no periodic save runs, so changed blocks alone are not saved by it
 * (leave the world for that). It needs the server ticking (not frozen), and it leaves the periodic save on; a caller that needs it off turns it off
 * after (`dev.stockpile.ps {noPeriodic: true}`).
 */
export async function saveBarrier(dev, { match = () => true, timeoutMs = 180_000, pollMs = 2000, sleep = nap, now = Date.now } = {}) {
  if (await hasRequest(dev, 'dev.stockpile.save')) {
    const r = await dev.request('dev.stockpile.save', {}, { timeoutMs: Math.max(timeoutMs, 60_000) });
    if (!r?.ok) throw new Error(`saveBarrier: dev.stockpile.save failed: ${r?.error ?? 'no answer'}`);
    return { how: 'save', ...r };
  }
  const on = await dev.request('dev.stockpile.ps', { noPeriodic: false }, { timeoutMs: 30_000 });
  if (!on?.ok) throw new Error(`saveBarrier: dev.stockpile.ps {noPeriodic: false} failed: ${on?.error ?? 'no answer'}`);
  const t0 = now();
  let saved = null;
  for (;;) {
    saved = await dev.request('dev.stockpile.saved', {}, { timeoutMs: 30_000 });
    if (saved?.ok && saved.unsaved === false && match(saved)) return { how: 'periodic', saved, waitedMs: now() - t0 };
    if (now() - t0 >= timeoutMs) break;
    await sleep(pollMs);
  }
  throw new Error(`saveBarrier: not saved after ${timeoutMs} ms (unsaved ${saved?.unsaved}, periodic saves ${saved?.periodicSaves}, `
    + `loadFailed ${saved?.loadFailed}${saved?.ok === false ? `, ${saved.error}` : ''})`);
}

/**
 * (SAVEB wiring) A saved state read from disk once it passes {@code ok}: the save barrier ({@link saveBarrier}) with {@code readSaved()} as its match,
 * so the periodic fallback waits until what is on disk passes; after `dev.stockpile.save` (which never consults match) the disk is read again until it
 * passes, every {@code pollMs} for at most {@code settleMs}. Never throws: a barrier that fails or times out returns the last read, which the caller
 * checks (a baseline step that fails its own check, not a whole run aborted). {@code readSaved} errors count as null.
 */
export async function savedOnDisk(dev, readSaved, ok, { timeoutMs = 180_000, settleMs = 20_000, pollMs = 2000, sleep = nap, now = Date.now } = {}) {
  const read = () => { try { return readSaved(); } catch { return null; } };
  const passes = () => { const v = read(); return ok(v) ? v : null; };
  try {
    const r = await saveBarrier(dev, { match: () => !!passes(), timeoutMs, pollMs, sleep, now });
    if (r.how === 'save') {
      const t0 = now();
      for (;;) {
        const v = passes();
        if (v || now() - t0 >= settleMs) break;
        await sleep(pollMs);
      }
    }
  } catch { /* the last read below is the answer; the caller's check names it */ }
  return read();
}
