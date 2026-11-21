// Waiting for Architect's sim helper to give programs (CATALOG-WAIT, docs/MEASURE-M4.md §9n).
//
// A fresh run dir's helper is installed by Architect's first-start `npm ci`, which can still be running when the world is open: until the
// helper's link is synced, the env reports givenPrograms false. This waits for it (at most 5 minutes by default) and tells the two cases
// apart: a helper that never came up times out, and a helper that is up (link synced) but gives no programs fails as soon as that is
// seen on two reads in a row (one read of slack for a snapshot still landing).
//
// `probe()` answers { up, given, detail }: up is the helper's link synced (`dev.sidecar.state`.link === 'synced'), given the env's
// givenPrograms, detail a short text for the messages. `now` and `sleep` are injectable so the tests run on a fake clock.

/**
 * Wait until the helper gives programs. Resolves with the probe's last answer; throws with a clear message otherwise.
 * @param {{ probe: () => Promise<{up: boolean, given: boolean, detail?: string}>, now?: () => number, sleep?: (ms: number) => Promise<void>,
 *   timeoutMs?: number, intervalMs?: number, log?: (m: string) => void }} o
 */
export async function waitForPrograms({ probe, now = Date.now, sleep = (ms) => new Promise((r) => setTimeout(r, ms)),
  timeoutMs = 300_000, intervalMs = 5_000, log = () => {} }) {
  const start = now();
  let upWithout = 0, last = null, waited = false;
  for (;;) {
    last = await probe();
    if (last.given) {
      if (waited) log(`the helper gives programs after ${Math.round((now() - start) / 1000)} s`);
      return last;
    }
    upWithout = last.up ? upWithout + 1 : 0;
    if (upWithout >= 2) {
      throw new Error(`the helper is up (link synced) but reports givenPrograms false: this Architect build or helper gives no programs`
        + `${last.detail ? ` (${last.detail})` : ''}`);
    }
    if (now() - start >= timeoutMs) {
      throw new Error(`the helper did not come up in ${Math.round(timeoutMs / 1000)} s (givenPrograms false`
        + `${last.detail ? `, ${last.detail}` : ''}): is Architect's first-start npm ci still running? see the client log`);
    }
    if (!waited) log(`waiting for the helper (givenPrograms false${last.detail ? `, ${last.detail}` : ''}), at most ${Math.round(timeoutMs / 1000)} s`);
    waited = true;
    await sleep(intervalMs);
  }
}
