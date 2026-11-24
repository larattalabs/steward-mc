# T4R-RETRY: busy calls ask again by themselves (WIP, stopped 2026-11-23 for the night)

Strict tier. Branch `t4r-retry`, cut from batch-100 (main plus the Architect 0.91.0 pin). The work is in commit 4b11e1d0.

## What's built
- **`BusyRetries`** is the one rule for a call Architect refused as busy.
  - It is keyed per operation and released by the blocker's own REMOVAL_FINISHED (`Busy.finishedBy`).
  - It is also released by a bounded timer: 60 s behind a removal, 5 s behind another change, at most 10 times.
  - A release runs once (single flight) and only in the session that made it.
  - Past the bound it is said once. It keeps only the event, and only for a wait behind a removal when `keepForEvent` is set.
  - It is dispatched from `RemovalEnds.init`.
- **Rows 1, 5, 7 and 12:**
  - **Row 1, the site-by-site undo:** waits and carries on by itself. The retry checks the entry is still the newest and unretired, and that the settlement isn't busy. It saves its own intent before Architect is asked, and the player's own undo drops it.
  - **Row 5, the cancel's take-up:** stays CANCELLING and is asked again.
  - **Row 7, the unrecorded road:** taken up again, and said in the road's words, never its id.
  - **Row 12, the realise:** waits and realises again under the same key and the cleared quote. The build is never cancelled.
- **`RemovalWaits`:** a group's or village's ticket wait is said once, bounded, and never after the call's answer.
- **`RevisionEnds`:** a growth that finished by itself is logged and said once, by the revision event or the sweep (one path).
  - Its refund is summed over the revision's stages: the event's own stage from the event, the others from their snapshots.
  - `Undo.growth` says its refund only after an append it made.
- **P2s:**
  - the road's words;
  - the update preview's busy words are tested through `Updates.refresh`, which now reads Architect through `ArchitectAccess`;
  - the T4 words judge is out of scope (the manager moved it to t4r-pin's fix round).

## What ran
- **Java units:** 1029/1029 at 4b11e1d0. **npm:** 332/332. `./gradlew build` passes.
- **Red-again** (`logs/redagain.log`): 23 mutants, all red. The first pass had 3 survivors, which got stronger rows: the in-flight guard, a ticket read after the answer, and words only after this append.
  - No row covers the player's undo dropping a pending retry (`lastProject` needs a server).
  - No row covers `Undo.growth`'s refund guard (its refund needs Minecraft items).
- **e2e** `stub --clients 2 --ports 9126,9128 --only undo,revert,update,district,survival`: stopped part way by the manager. Before the stop, 347 ok and 2 FAIL:
  - `supplied growth restart mid-undo reconcile: no reconcile line`. Probably this slice: the growth reconcile's log line changed (it is now RevisionEnds' "finished by itself"), or the event logs it first. Check the judge in `tools/e2e.mjs` and rerun `--only supplied-growth`.
  - `run: DevBridge connection closed`, from the SIGTERM.
- **Early GPT review** (`logs/gpt.log`): **NOT SAFE**, 1 P1 and 2 P2. Each was checked in code and holds.
  - **P1:** `cancelVillage` forgot the wait's count on every automatic retry, so the bound was never reached.
  - **P2:** the placement key was per settlement and not cleaned up when the build ended. A later build could inherit the count.
  - **P2:** a retry of a project that was already partly undone reused the captured entry's sites. `removedSince` can't match a tagless entry, so a site could be logged removed twice.

## Left (in this order)
1. Apply the prepared fix round, which is not applied yet:
   - `logs/patch/fix1.py`: the cancel keeps its count on its own releases; the place key is per build and forgotten when the build ends; the retry uses the fresh head's sites.
   - `logs/patch/fix1tests.py`: a cancel kept busy past the bound, the per-build count, and a partly undone project.
   - Then run the scoped units and red-again for the three, then the final GPT pass (rule 4).
2. Merge t4r-pin's fix round when the manager names the commit. Then run the full build, npm, and the e2e (`--only` the failing flows first, at most 3 full runs).
3. gitleaks, push, report. Self-retry has no in-game row: `dev.removal.pause` could hold a removal for one.
