# AGENTS.md history

What AGENTS.md said before the speed rules were consolidated (2026-11-16, Noah via the parent session: "Yes, all three"). AGENTS.md now holds
only the current rules. This file keeps the dated text they came from, so older docs, briefs and commits that cite it still resolve. Nothing
here is current where it differs from AGENTS.md.

## What the consolidation removed or replaced

- **"Batched merges: 2-4 ready slices"** (11-13). Replaced by batches of 4-6 (11-15), now speed rule 9.
- **Light tier: "unit tests plus one targeted in-game flow"** (11-13), and Process's "each step ships with unit tests and an e2e check".
  Replaced by deferred verification (11-15): a light slice has no in-game run of its own (rule 2).
- **Strict slices "keep the gate-verifier"** per slice (11-13). The verifier now runs once per batch merge or release (rule 10).
- **"The GPT review runs once per batch, not once per slice"** (11-15). Overridden for strict slices by the per-slice early review (11-16), rule 3.
- **"Nightlies stay skipped until Noah says otherwise"** (11-15, repeated 11-16 overnight). Nightlies now run by default and are skipped
  night by night with skip files (rule 23).
- **"Architect pins skip versions: bump the pin every other release"** (11-15). Not in the consolidated set; pins now follow the manager's
  call, with module re-certification under rule 11.
- **Capacity said twice** (the 11-13 list and the 11-15 "gate capacity"), with a dangling "rule 12 below". Now one rule, 12.
- **DEV MODE's "skip the gate-verifier for branches that touch only tests or wording"** (11-04). Covered by the risk tiers (rule 1).
- **"High-stakes changes get a GPT review"** as a separate line overlapping the strict-tier rules. Kept only for what the tiers don't cover
  (spending and cancellation), in Process.

## Process, as of 2026-11-16 (before consolidation)

- **Speed rules** (Noah, 2026-11-13: "yes, apply all of those"; they apply to running slices too):
  - **Risk tiers.**
    - **Strict:** saves and persistence, billing, refunds and stock, undo and revert, crash recovery and reconcile, and anything that can
      mint or lose items. These keep the gate-verifier and, where the rules ask for one, the GPT review.
    - **Light:** everything else (UI, wording, tooling, docs, fixtures, $0 prompts). Unit tests plus one targeted in-game flow, with no
      verifier and no GPT review. New screens still get a design-critic look.
    - The manager picks the tier in the brief when it's unclear.
  - **Failing steps only.** After a failure, fix it and rerun only the failing flows (`--only`). The full gate runs once at the end. Three
    full runs per slice at most; a fourth is the manager's call.
  - **Batched merges.** The manager merges 2-4 ready slices together and runs one merge suite on the combined result. If it fails, bisect by
    slice.
  - **Module re-certification.** A measured module is re-measured at an Architect pin only when its own code changes, or when the Architect
    build, billing or realise paths it uses change. A plain version bump gets a smoke check: one field round plus the billing check.
  - **Capacity.** Up to 6 in-game runs at once machine-wide, with no two starting within 2 minutes of each other (rule 12 below). Unit
    suites run under `nice -n 10`. Docs, kit-only and unit-only work take no slot.
  - **Short as-builts.** A slice's as-built and its report are 10-15 lines: what changed, what ran and passed, and the leftovers.
  - **Scoped unit suites** (Noah, 2026-11-13: "yes, send that to both managers/add to agent/claude docs"):
    - A slice runs only the Gradle and npm tests for the code it touched, plus their direct dependents.
    - The full `./gradlew build` and `npm test` run once, at the batch merge.
    - A slice that touches shared core (persistence, billing, the gateway) runs the full suite.
  - **Timing benches report only** (same decision): a perf or timing assertion becomes a report step that records the number and the load.
    Past its bound, it flags the manager instead of failing the slice.
  - **Load gate** (Noah, 2026-11-13: "lets ensure that is 15 min max from now on, with high at least 72 cap"): a load-aware start waits at most
    15 minutes, and the load threshold is 72 or more. The e2e client's defaults are `E2E_LOAD_MAX` 72 and `E2E_LOAD_WAIT_MIN` 15.
  - **Deferred verification** (Noah, 2026-11-15: "Yes, apply all"; it overrides the rules above where they conflict, and applies to running
    slices too):
    - **Light tier:** no in-game run per slice. Compile, the scoped unit tests and gitleaks, then the slice joins the next batch, whose in-game
      run covers it.
    - **Strict tier:** only the in-game scenarios for the risk the slice touches (item loss, billing, refund, undo or crash). No full gate
      per slice; the batch merge runs it.
    - **Batches** of 4-6 ready slices, with one full merge suite on the combined result. If it fails, bisect by slice.
    - **The strict gate-verifier and the GPT review** run once per Architect release or Steward batch merge, over all the strict diffs it
      holds, not once per slice.
    - **Architect pins skip versions:** bump the pin every other release. At other releases, run a smoke check only (one field round plus
      billing), unless a module's own code or Architect's realise or billing paths changed.
    - Unchanged: the targeted strict scenario on every strict slice, scoped unit tests, gitleaks, at most 3 full runs, $0 without Noah's
      approval, and slices never merge or push main. Nightlies stay skipped until Noah says otherwise.
  - **Gate capacity, standing approvals, parallel slices** (Noah, 2026-11-15: picked "More gate slots + ports", "Standing approvals" and
    "More parallel slices"; not "GPT review early"):
    - **Gate capacity:** up to 6 in-game runs at once machine-wide, 2 minutes between starts. The load-gate bounds are unchanged. Steward
      may also use ports 9100-9149 (in pairs), besides its own.
    - **Standing approvals:** a paid step under $1 inside a budget Noah already approved goes ahead, with a one-line note to the parent
      giving its cost. A product question with a confident recommendation goes ahead on it, with a one-line note (question, choice, why);
      Noah can overrule. Still asked first: anything irreversible, anything changing what a player loses or pays, a new public surface,
      new budgets or raised caps, and any step over $1.
    - **Parallel slices:** the manager keeps up to 6-8 slices in progress at once, as far as files and ports allow, and pre-briefs the next
      ones so a free slot never sits empty.
    - Unchanged: the strict tier, the per-batch GPT review and gate-verifier, $0 unless approved, and slices never merge or push main.
  - **GPT review per strict slice** (Noah, 2026-11-16: picked "Review per strict slice"; not "Auto-drop failed slices"):
    - Once a strict slice is built and its targeted strict scenarios pass, it gets its own GPT review in the background (the
      second-opinion skill, over its diff only) while other work carries on.
    - It fixes its P1 findings before it joins a batch.
    - The per-batch GPT review stays, so this adds a check and removes none. It overrides "not once per slice" above for strict
      slices. Light slices still get no GPT review.
- **Release-candidate testing is DEV MODE** (Noah, 2026-11-04):
  - An Architect RC runs only the flows its change touches, plus the sanity set: `node tools/e2e.mjs stub --only gate,wall,survival`, aiming for 15-20 min.
  - Full stub and free runs are nightly, or before a pin when a release is broad; what they find is fixed forward.
  - After a test-only fix or a chance failure, rerun only the failed flows (each run prints `flow <name>` lines).
  - Skip the gate-verifier for branches that touch only tests or wording; keep it for survival billing, undo, removal and crash recovery.
  - No second full e2e pass after a merge to `main`.
  - A pin bump to a released Architect version whose RC already passed runs only the sanity set: `node tools/e2e.mjs stub --only sanity` (Noah, 2026-11-06).
    A release that differs from that RC still gets its changed areas run.

## Other Process lines replaced (verbatim)

- Phase 5 is in slices M0-M5, then 5b's M7-M9 (`docs/MODULES.md` §8); earlier phases are in `docs/PLAN.md`, "Phases". Each step ships on its own with unit tests and an e2e check, committed in coherent pieces.
- High-stakes or hard changes (spending, persistence, cancellation, migrations) get an independent GPT review (the second-opinion skill), then a check of the fixes.

## CLAUDE.md lines replaced (verbatim)

- The speed rules in AGENTS.md "Process" (Noah, 2026-11-13) set how much checking a change gets:
  - **Strict tier:** saves, billing, refunds, undo, and crash recovery. A gate or "is this done" check there is verified by an independent
    `gate-verifier` agent, never by the builder itself.
  - **Light tier:** unit tests plus one targeted flow.
  - New screens get a `design-critic` look at real screenshots (`artifacts/shots/`).
  - Unit suites are scoped to the code a slice touched, and run under `nice -n 10`.
  - Timing benches only report.
