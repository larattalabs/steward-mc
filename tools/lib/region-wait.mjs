// (E2E-SPEED) reading dev.region.state while waiting on a region (tools/e2e.mjs regionUntil), kept apart for its unit tests.

/**
 * The state a dev.region.state answer stands for. A removed region leaves Architect's live table and is answered {ok: true, missing: true}
 * (RegionsImpl.devState): that reads as REMOVED. Before E2E-SPEED it read as nothing, so every wait for REMOVED ran to its bound (gate-mech:
 * 600 s each) and then passed on `undefined`. A failed call or no answer also reads as REMOVED when that is what is waited for (as before).
 */
export function regionStateOf(v, states) {
  if (v?.view?.state) return v.view.state;
  if (v?.state) return v.state;
  if (v?.missing === true) return 'REMOVED';
  if (states.includes('REMOVED') && (!v || v.ok === false || v.error)) return 'REMOVED';
  return undefined;
}

/**
 * Polls `read()` every `everyMs` until its state is one of `states`, at most `ms`. Returns the answer with `state`; past the bound
 * `{ ..., state: <last state or 'TIMEOUT'>, timedOut: true }`, so a caller that accepted an unknown state no longer passes on a timeout (C14).
 */
export async function regionUntilBy(read, states, ms, { everyMs = 2000, sleep = (t) => new Promise((r) => setTimeout(r, t)), now = Date.now } = {}) {
  let v = null, st;
  for (const end = now() + ms; now() < end;) {
    v = await read().catch(() => null);
    st = regionStateOf(v, states);
    if (states.includes(st)) return { ...(v ?? {}), state: st };
    await sleep(everyMs);
  }
  return { ...(v ?? {}), state: st ?? 'TIMEOUT', timedOut: true };
}
