// The nightly's e2e plan (tools/nightly.mjs), kept apart so it has unit tests (test/nightly-plan.test.mjs). E2E-SPEED-2 (Noah, 11-22: "1+2+3: all
// speed items"): the stub runs on 2 clients. It timed out every night from 11-17 on one client (about 190 min of flows in a 165 min night), so
// the --full and nightly-only flows weren't really running.

/** The stub's clients and their sidecar ports (each DevBridge port is one above): c1 the e2e pair 8590/8591, c2 8600/8601, kept for the nightly. */
export const NIGHTLY_CLIENTS = 2;
export const NIGHTLY_SIDECAR_PORTS = [8590, 8600];
/** Every port the nightly's clients listen on: the night is skipped when any of them is taken. */
export const NIGHTLY_PORTS = NIGHTLY_SIDECAR_PORTS.flatMap((p) => [p, p + 1]);
/** The whole night's hard limit. */
export const LIMIT_MIN = 2 * 60 + 45;
/** After SIGTERM at the limit, how long the step's process group has before SIGKILL: past a --clients lead's own stop bound (90 s for its
 * children, then their JVMs and launchers, about 115 s), so a timed-out night still quits its clients and empties their RAM-disk dirs. */
export const STOP_GRACE_MS = 180_000;

/** The e2e argv after `tools/e2e.mjs` for a step: free on one client (the e2e pair), stub on NIGHTLY_CLIENTS with the 120 s stagger. */
export function stepArgs(mode) {
  if (mode !== 'stub') return [mode];
  return [mode, '--clients', String(NIGHTLY_CLIENTS), '--ports', NIGHTLY_SIDECAR_PORTS.join(','), '--stagger', '120'];
}

/**
 * Whether the night fits its limit, in minutes: the build and the free step (`before`), then the stub's lanes (`lanes`: each lane's packed flow
 * seconds, from `e2e.mjs stub --plan`), each starting `stagger` s after the one before, with `overhead` s of its own (launch, sanity set, restart)
 * and `extra` s for what the baseline doesn't time (the nightly-only crash-carve flow's stall bound, module-survival's report-only round).
 * -> {fits, minutes, limit, margin}.
 */
export function nightFits({ lanes, before = 15 * 60, overhead = 240, stagger = 120, extra = 0, limitMin = LIMIT_MIN }) {
  const ends = lanes.map((s, i) => i * stagger + overhead + s);
  const stubS = Math.max(0, ...ends) + extra;
  const minutes = Math.round((before + stubS) / 60);
  return { fits: minutes <= limitMin, minutes, limit: limitMin, margin: limitMin - minutes };
}
