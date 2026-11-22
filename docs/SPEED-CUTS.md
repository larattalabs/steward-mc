# Speed cuts

Noah, 2026-11-21, via the parent: "based on sweeps, let things be dropped- even if coverage, assertions, etc get weaker- we want more speed
desperately". Every cut that weakens coverage goes here, with the time it saves and how to bring it back. Speed fixes that weaken nothing are
listed first, for context. The measurements are in `mod/DEV.md`, "Faster runs". Slice E2E-SPEED made the first sweep.

These cuts leave three things as they were: the hard rules (saves migrated, `SaveCompatTest`), the strict-tier unit tests, and the
gate-verifier step (AGENTS.md speed rule 10).

## Speed fixes (nothing weakened)

**1. Lanes packed by time.**
- Problem: a batch worktree is new and has no `artifacts/e2e/flow-times.json`, so `--clients 2` filled its lanes blind, in registry order. In
  b093, c1 ran 7073 s and c2 4388 s, so c2 sat idle for about 45 min.
- Fix: `tools/flow-times-baseline.json` is committed and read first; a worktree's own times override it.
- Check: `test/e2e-speed.test.mjs` fails when a stub flow has no baseline time.
- Refresh it as in `mod/DEV.md`, "Faster runs".

**2. Region removals end when the region is gone.**
- Problem: `dev.region.state` answers a removed region with `{ok: true, missing: true}`, and `regionUntil` read that as no state. Every wait
  for REMOVED therefore ran to its bound, then passed on `undefined`, which is a C14 hole.
- Cost per batch, about 75 min of flow time:
  - the four gate-mech flows: 600 s each;
  - `wall-lots`: 600 s;
  - `revision`: 300 s;
  - 120 s waits in `wall-offer`, `beside` and `district-growth`.
- Fix: a missing region now reads as REMOVED, and a wait that reaches its bound comes back as `timedOut` with state TIMEOUT, which fails
  (`tools/lib/region-wait.mjs`).

**3. The game log across midnight.**
- Problem: the game rolls `latest.log` over at 00:00. A log wait read from a byte offset past the new file's end, so it saw nothing and
  stalled to its bound. That cascaded: run 2's supplied-growth, and batch 094's supplied growth cancel.
- Fix: `logSince` now reads bytes and reads a rolled-over file whole (`readSince`).

## Cuts (in effect)

| What | Min saved | No longer covered in batch and slice runs | Back with |
|---|---|---|---|
| `module-survival`'s waiting round (S-M5-10, a carrot lot fed in halves, then undone) | about 5 | Report only: its row can't fail, so a batch only lost time to it. Its line says `skip ... report only`. | `--full` (forwarded to the lane clients), or the nightly |
| `composed-sculpt-survival-crash` nightly-only (E2E-SPEED-2; Noah 11-22: "Nightly-only until PLR") | about 2.3 (140 s of flow time) | The lining-halt crash scenario in batch and slice runs: a composed sculpt town halted uncleanly part way through the river's paid lining, restarted, finished and undone. It hits Architect's orphaned `placing` site (B092L-1) like `-crash-carve`. The nightly still runs it. | Drop its `nightlyOnly` in `tools/e2e.mjs` when the Steward pin reaches the Architect release carrying PLR (B092L-1); `--only composed-sculpt-survival-crash` runs it meanwhile |
| `--fast` for light-only batches (E2E-SPEED-2; Noah 11-22: "1+2+3: all speed items") | about 45-50 per light batch (a 3-client full stub projects ~59 min; the fast set ~11 min) | A light-only batch (no strict slice) runs the sanity set, one flow per risk area (`FAST_FLOWS` in `tools/lib/e2e-flows.mjs`: `survival`, `composed-modules-survival`, `module-hall-survival`, `revision`, `discard`, `district-region`, `composed-hall-fill-restart`) and the flows its slices touched (`--only` words, and by file since the merge base: their e2e code or fixtures; harness code every flow runs counts as covered), not the other ~65 stub flows. A strict batch never uses it; its summary says `tier: 'fast'`. | Run the batch without `--fast`; the nightly runs everything |

**The `--full` tier.** A flow given `{ full: '<why>' }` in `tools/e2e.mjs` runs only:

- with `--full`;
- in the nightly (`E2E_NIGHTLY=1`);
- when `--only` names it exactly.

Otherwise the run prints `skip <flow>: full tier (...)`. No flow is in this tier now.

- At first, `wall-lots`, `wall-offer`, `beside` and `district-region` were moved to it, to save about 26 min of flow time.
- With fix 2 in, they cost about 7 min of flow time together (about 3.5 min of wall time), so they are back in batch runs.

**The nightly.** The nightly stub timed out from 11-17 to 11-22: one client couldn't fit about 190 min of flows into its window. Since
E2E-SPEED-2 it runs stub on 2 clients (proposal 1, done), projected at about 112 min of its 165 min night (`node tools/nightly.mjs --dry`
prints the fit), so what is behind `--full` and `nightlyOnly` is covered nightly again.

## Proposals (not cuts; the manager's and Noah's call)

Proposals 1, 2 and 4 were approved by Noah on 11-22 and built in slice E2E-SPEED-2 (`mod/DEV.md`, "Faster runs").

1. **Done (E2E-SPEED-2): a 2-client nightly stub,** on 8590/8591 and 8600/8601. `tools/nightly.mjs` would run `--clients 2 --ports <a>,<b> --stagger 120`, using the baseline. With fix 2, the
   full stub should fit well inside the window.
   - The second port pair is the manager's to pick: 8592-8601 are slice pairs.
   - The nightly would take 2 slots.
2. **Done (E2E-SPEED-2): three clients** (`--clients 3 --ports a,b,c`). On the current baseline a full stub projects about 59 min on 3
   lanes against 82 min on 2. `--plan --clients 3` on the flow set before fix 2 packed about 3260 s per lane, against 5680 s for 2: about 40 min less
   per batch. With fix 2 the gap shrinks to roughly 20 min. It needs a third port pair and a third slot.
3. **The creative gate-mech flows** (`gate-mech`, `gate-mech-lots`, `gate-mech-swing`, `drawbridge`) are strict: they carry `undo`, and the
   gate-verifier picks flows by word.
   - What they cost: with fix 2, about 1 to 2 min each.
   - What their survival twins already cover: the same mechanisms, billed and refunded.
   - What only these flows cover: the hash diff of the whole claim after the undo, and `drawbridge`'s 16-use lever test.
   - So: not worth cutting now.
4. **Done (E2E-SPEED-2) as `--fast`: a fast set for light-only batches,** for example `--only sanity` plus the touched flows' words. That changes speed rules 9 and 10, so it
   is the manager's call.

## Stub and free duplicates (reported, not cut)

Batches run only stub; free runs only in the nightly (about 10 min). `stone-recipe`, `inbox`, `shared-hud`, `tour-talk` and `migrated-steward`
run in both. Together they take about 40 s per run, so they aren't worth cutting.

## Module tools (reported, not changed)

`tools/module-measure.mjs` and `tools/module-measure-hall.mjs` have no REMOVED waits. Their long sleeps are observation windows inside what
the certificates measure:

- the hall's 30 s after the preload (does the line take the input?);
- its 15 s before clearing the overflow;
- the 6-8 s settles after a halt or restart.

Shortening them would change what is recorded, and the tools run rarely, so they stay as they are.
