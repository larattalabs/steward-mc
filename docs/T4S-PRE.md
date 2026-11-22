# T4S-PRE: Steward's side of Architect T4, pre-built on 0.83.0 (as-built, 2026-11-19)

Strict tier. This is branch `t4s-pre` and it does not merge until T4c ships and the pin moves. The spec is the T4 contract (parent's notes,
`architect-T4-contract-draft.md`, "Changes from Steward's review"). On 0.83.0 nothing changes: no `held(..)` answers arrive, and the intent notes,
the T4 words and the tool rows are gated on `features()` containing `removal_finished`.

**Rule 5, the review's line numbers checked at main f0e1858.** The review cited Undo 158/177, Runner 4680, Growths 378 and Undo 291. At f0e1858 they are:
- Undo: the group's gone-from-absence :157, the chain's :184, the region's closedNote :305.
- SettlementRunner: `orElse(true)` :4745.
- Growths: GONE :346, the refused line :387.
- Districts: `takeUp` :661.

## What was built
- `SiteBusy.of(err)` (with `SettlementRunner.siteBusy`) reads ArchitectRefused SITE_BUSY `detail()`. It accepts the bare token or the T1 form, takes the
  place from the detail or the message, and gives "chunk x, z" or `records unreadable`. Nothing else is ever said.
- On held(decided), Undo's group, chain and region paths, Growths.undo, cancelVillage, cancelGrowth, takeUpEmpty and Districts.takeUp all do the same:
  - say "Undo under way: …";
  - log no FAILED or PARTIAL entry and write no closedNote;
  - release UNDOING;
  - leave a held note (regions `undo:region:<id>`, entries `undo:entry:<at>[@site]`).
  A cancel stays CANCELLING (`cancelHeld`) until `removalFinished` or the restart's resync.
- On held(other), `Undo.asking` asks again up to 5 times, 2 s apart, in the same session.
- The group and chain paths get an intent note, written only with the feature, and a sweep: `Undo.finish`, `interruptedEntries` and the pure
  `finished`. It logs once on a healthy absence or on the event's positive answer. A part-way chain is logged as PARTIAL and its note closed.
- The `loadHealthy()` guards cover the group and chain absence reads, cancelVillage (`takenUp`, pure) and Growths GONE.
- With the feature, a group stopped by blockers is NOT_STARTED ("nothing was taken down").
- The `RemovalEnds` seam is a `RemovalEnd(kind, id, removed, refund, owed)` record plus `handle`. It is idempotent on saved state and says the refund,
  the owed and the over-capacity lines after the refund lands. The TODO(T4c) adapter is in `RemovalEnds.init`.
- New words: `Growths.refundLine(refund, owed)` lists owed apart and never says "nothing came back" when something is owed; `notPlacedNote`;
  `RemovalStops.growthWaiting(cause, byItself)`; and RefusalWords' records line.
- Tools:
  - `/steward dev module {op:env}` now reports `features`.
  - `tools/lib/t4-rows.mjs` holds the gate and the judges.
  - The e2e RVU1/RVU2 rows check "after the decision" (never PLACED again) and the words.
  - The hall tool's FKC1 on-disk check, the custody view, the re-undo (NOT_ALLOWED or replayed) and FKC0 (a missing hook fails under T4) are all gated.

## TODO(T4c), at the pin
1. The adapter: `RemovalEnds.init` registers `SiteEvents.REMOVAL_FINISHED` and resolves `RemoveResult.owed()` keys to items via `Sites.owed(owner)`.
2. Owed from `RemoveResult.owed()`:
   - `Undo.growth`'s refund line;
   - `takeUpEmpty` (`notPlacedNote`'s owed flag, marked in SettlementRunner);
   - the e2e conservation sums (OVF-S3; needs `dev.stockpile.owed`).
3. `replayed()`: don't say "Refunded" again for a replayed answer (`Growths.reportsRefund`).
4. Ask Architect:
   - the RemovalFinished `kind`/`id` for an `undoRevision` (unstated). Until then the growth sweep logs it, but its refund isn't said;
   - whether a decided, held site stays listed until retirement (the entry sweep reads absence on a healthy load);
   - txn.json's open-entry shape, for FKC1 custody on disk.
5. Run, with the T4c jar:
   - `node tools/e2e.mjs stub --only supplied-growth` (RVU1/RVU2 T4 rows);
   - hall tool step 9 (FKC1/FKC2/CS1, the re-undo, FKC0);
   - an e2e row that holds a group undo on an unloaded chunk (not written: it needs a T4 hold hook).
   Then the GPT review of this diff.

## Leftovers (TXN-S, not this slice)
- Updates' SITE_BUSY handling (no FAILED, rescan).
- `Stockpiles.addCrate` busy words.
- MaterialRequests `txn_held`.
- The cancel lines' "It ends on its own".
