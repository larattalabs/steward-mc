# T4R-PIN: Steward ready for the Architect pin carrying T4R-D (as-built, 2026-11-22)

Strict tier. Branch `t4r-pin` (cut from main bc60f501): `t4s-pre` merged, then T4RD-S (LEFTOVERS rows T4S-PRE and T4RD-S), against Architect
`slice/T4R`. **It builds only with `ARCHITECT_JAR`** (it uses `Busy`, `OwedPart`, `RemovalFinished`, `RemovalStopCause.WAITING_BEHIND`) until
`architect_version` moves to the release. The compile jar is slice/T4R 68d2b065 (`-PallowNoSidecar jar`, in `_work/architect-mc-t4r-steward-rc`).
The coordinator held in-game runs and the GPT review for the post-fix head of Architect's current round (owedParts on grouped futures and revision
aggregation, a region's Busy or a group finish event, a group's own extraction, DevStart gating).

## What was built
- **Merge** (0b15da97): conflicts in RefusalWords, SettlementRunner and e2e.mjs resolved toward main plus T4S-PRE; the fake's T4S-PRE site-group
  map renamed `siteGroups` (main's `groups` holds design groups).
- **One busy rule** (`SiteBusy`): `ArchitectRefused.busy()` first, else the detail token. HELD_DECIDED is decided. REMOVAL_IN_PROGRESS and
  WAITING_BEHIND wait for the blocker. OPERATION_IN_PROGRESS, PLAYER_BLOCKS_RETURNING, `held(other)` and any unknown kind are retried soon
  (`Undo.asking`, 5 x 2 s), as Architect's javadoc says.
- **Words by kind** (`RemovalStops`): rows 1-5 and 7 (chain, group and village undo, growth take-up, cancel, unrecorded road), and rows 8-12
  through `RefusalWords` (either shape). The blocker is named only in Steward's words: the old <lot>, your old buildings, <town>'s old buildings,
  else "a build nearby". Never an Architect id, and never "being taken down" for something that isn't a removal. WAITING_BEHIND gets stop words
  and a tail.
- **Updates**: a SITE_BUSY answer, as a failed future or a delta refusal (the async path drops its detail), is never put in `FAILED` and never
  logged. It waits, asked in the inbox and said once per version. Any REMOVAL_FINISHED releases a wait behind a removal; a timer releases
  either kind, and a removal's end also releases an untyped busy (its cause unknown). At most 5 timed tries per version per session, so Full
  autonomy can't loop; the player's own ask starts the count and the words again. **Revert**: a busy refusal is a NOTE naming no
  site change, never a FAILED REVERTED.
- **Owed** (`OwedParts`): said only for parts whose recipient is the settlement's owner *and* whose reason is `crate_settle`, `crate_putback` or
  `group_refund`, an allowlist that keeps out `player_blocks`, `cargo`, unclaimed and later reasons. Used by `Undo.growth`, `takeUpEmpty`
  (refund line and `notPlacedNote`), `RemovalEnds`, and the sweep.
- **The adapter** (`RemovalEnds.init`): REMOVAL_FINISHED is subscribed once at mod init (so Architect's replay after a restart is never fired
  before Steward listens), used only while a world runs with the feature, behind a LinkageError guard. `RemovalEnd` carries
  stage, revisionId and owedParts. `Undo.endedBy` is the one event-to-entry match, so an event says its refund only for the entry it took down.
  The sweep reads `Sites.removalResult` only for what it logs without the event.
- **Bar 7b (S-1)**: on `removal_finished`, the placer's blocks are judged by `owedParts` (player_blocks, `dev.pt.uuid`) with the ground 0, and
  after the restart the placer's `dev.stockpile.owed` must be unchanged. Older Architects keep the dropped judge. MEASURE-M2 is updated.

## Design checklist
- **A1 (one record):** Steward writes no new store. "Was it removed" is Architect's answer or event plus Steward's PROJECT_REMOVED entry, logged
  once on a fresh read (`notYetLogged`, `retired`). "What is owed" is Architect's owed store and the immutable `removalResult`/`owedParts`
  snapshot; Steward only reads them for words. A busy update or revert has no record at all (Updates) or only a NOTE (Revert).
- **A2 (persist before mutate):** every new path changes no world, stockpile or ledger state. A busy refusal began nothing on Architect's side,
  and Steward's only writes are notes (`closeEntry`, the revert NOTE), saved through `Settlements.log`'s durable save. Updates' busy map is
  memory that decides only whether to ask again.
- **A3 (second crash):** the sweep and the event log each entry once on a fresh read. A crash between the log and its words loses the words,
  never repeats them (words follow a successful log). A crash after a busy refusal leaves the closed note, so no later removal is taken for it.
- **A4 (key per operation):** the owed parts' keys are Architect's (per attempt). Steward's busy-update key is `siteId@version`, so a newer
  version is a new operation. The event match is per (kind, id) against the logged entry, idempotent on saved state.
- **A5 (holds after restart):** Architect's admission. Steward never reads absence as gone after a busy refusal (the group test removes a site
  and still logs nothing).
- **A6 (refunds from what was removed):** Steward says Architect's `refund` and the owner's refund-kind owed parts, both snapshotted from the
  removal. It never computes a refund.
- **B7 (legacy in flight):** a pre-T4R removal's `removalResult` is null, so nothing is said; its owedParts are empty, so the old refund line is
  unchanged. Old held notes keep T4S-PRE's sweep.
- **B8 (one rule):** one busy classifier (`SiteBusy.kindOf`), one owed rule (`OwedParts.sum`), one event match (`Undo.endedBy`). Rows 8-12 share
  `RefusalWords`, and Updates and Revert share `BusyUpdates.busyOf` at the point the outcome is chosen.
- **B9:** no repeated-edge area yet; the GPT rounds are pending.
- **B10 (old saves):** no save format change. NOTE entries are an existing kind, and `SaveCompatTest` passes.

## Tests and red-again
- T4RDTest: 14 rows (one proves a busy group undo never reads absence as gone without txn removals). T4SeamTest's held(other) row now expects the busy words. Two RemovalStopsTest rows were red against the RC before the
  WAITING_BEHIND words. npm `test/owed-cargo.test.mjs`: 4.
- Red-again (`artifacts/t4r-pin/redagain.log`, each fix reverted and red, then restored):
  - kindOf, typedToken;
  - chain, group (with and without T4) and region busy;
  - Updates' busyOf, its retry bound, the re-ask said again, the untyped release;
  - the revert NOTE;
  - the owed reason and recipient filters;
  - the sweep's once;
  - the WAITING_BEHIND tail;
  - the 7b ground, guard, placer-filter and restart judges.

## T4S-PRE's "at T4c" list
1. The adapter in `RemovalEnds.init`: done (owedParts from the event, not `Sites.owed` keys).
2. Owed from the result: done for `Undo.growth`'s refund line and `takeUpEmpty`'s `notPlacedNote`; the e2e conservation sums (OVF-S3) are TXN-S.
3. `replayed()`: N/A, slice/T4R's `RemoveResult` has no such accessor. A replayed answer can't say "Refunded" twice because the words follow
   only a log this pass wrote (`RemovalEnds.handle`, `Undo.finish`, `Growths.completed` before `takeUpEmpty` says anything).
4. Architect's answers: taken (11-19, binding). revisionId is now on `RemovalEnd`, but no growth path reads it yet (Left).
5. The runs (`stub --only supplied-growth`, hall step 9): wait for the post-fix jar. The hall's FKC1 row still has `TODO(T4c)` in
   `tools/lib/t4-rows.mjs` (point it at `dev.stockpile.ledger` `custody[].onDisk`), and the held-group e2e row needs a T4 hold hook: both Left.

## Left
- **At the real pin:**
  - bump `architect_version` (and the Fabric range) to the release;
  - build the RC run jar (with the sidecar) from the post-fix head;
  - run `--only undo,revert,update,district,survival` plus the hall tool and the crop_farm smoke carry (bar 7b);
  - run the early GPT review.
- **Self-retry, not built:** rows 1, 5, 7 and 12 retrying by themselves on the blocker's REMOVAL_FINISHED. They wait on Architect's event
  mapping (a region's Busy, or a group event); today the player asks again, and rows 7 and 8 retry by reconcile or rescan.
- **Not built:** speaking during a group's or region's `removalWait` (silent until it stops); a growth's revision event (`revisionId`)
  saying its refund.
- **TXN-S:** `Stockpiles.addCrate` and MaterialRequests words beyond `RefusalWords`, and the e2e conservation sums with owed (OVF-S3).
