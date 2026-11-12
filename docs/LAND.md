# The steward sees what changed in its town (Architect LC)

Status: design, 2026-11-11, revised after the GPT design review (request changes: 2 blockers, 5 should-fixes, all taken; see "Review"). Architect's side is `../architect-mc/docs/CONTRACT.md`, "Phase 6d slice LC" and its as-built (v0.58.0; Steward pins
0.59.0). Steward's answers S-LC-1..4 were "as proposed" (ARCHITECT-ASKS, batch 6d). Spend: $0 (a land check is a read, no Claude call).
Perception only: it writes no world, no save field and no history entry.

## What Architect gives

`Regions.landCheck(regionId, null)` reads a standing region's cells against what it wrote, loading no chunk:

- `PartLand(part, cells, changed, removed, playerPlaced, volatileChanged, kinds, sample)`, per IR part (roads by their road id, `mech:<gate>`,
  wall parts, the square's props, a revision's `rv<n>_*` parts, `unattributed`). `kinds` is keyed by the block **now** there.
- `LotLand(lot, siteId, state, standing, changed, playerPlaced)`, per lot with a site (the buildings, wall lots). Its `changed` is removed plus
  changed together; `playerPlaced` apart.
- `cellsChecked`, `unloaded` (cells in chunks not loaded: counted, not read). Over 4M cells with no part named: `REGION_LIMIT`.
- Already left out by Architect: a gate's own open or closed state, a player's block identical to ours, cells of a target still building.

## The rule

**On demand, or now and then while a player is in the claim, the steward looks over the cells its town and districts were built with and says,
in plain words, which are broken or changed now, and which hold a block a player placed. It says what it could not see. One inbox row per
settlement holds the latest look; nothing is repaired.** A land check reports present differences in the cells the region still owns: not who
broke a block, not what was built elsewhere in the claim, not a missing gate lever (0.59.0).

1. **Which regions.** The settlement's town region (`TownRegion.of`) and each district's `region()`. A region counts while it stands:
   `PLACED`, `PARTIAL` or `FAILED` (what it wrote is still there), and not `busy` (a revision or removal running). Skipped: `PLACING`,
   `REMOVING`, `REMOVED`. A survival target or lot still under construction is left out by Architect itself ("not counted yet"), so a town
   being built in survival reads no false damage. A street town not yet adopted has no region: on demand the steward says "I can only look over ground Architect laid
   out as a region; <name>'s street isn't one yet."
2. **What counts (`LandWords.real`).** `volatileChanged` never (crops, leaves, snow, fluids). A part counts when `removed + changed +
   playerPlaced > 0`, a lot when `changed + playerPlaced > 0`.
3. **The words (`LandWords`, pure).**
   - A building: its role as Steward names it (`Updates.lotOf` on the lot's site), else the lot id: "The smithy: 3 blocks broken or changed,
     1 placed by a player." (LotLand cannot tell broken from changed.)
   - A part: roads ("the street", "the lanes", a road id), the wall (`w_*`, `wl_*`), a gate (`mech:<gate>` -> "the gatehouse's gate"), the
     square and its props, a growth's parts (`rv<n>_` dropped), `unattributed` -> "other built areas": "The square: 40 blocks gone, 6 replaced
     (now sponge), 2 placed by players." `kinds`: the two largest, worded "now <block>", never "lost <block>".
   - Two buildings of one role in a region are told apart by their lot: "The fisher's hut (cabin 2)".
   - A district's lines are under its name ("In Copper Ford, the street's west end: ...").
   - Nothing real: "I found nothing broken or changed in what we built in <name>." With a gap: "... in what I could see of <name>."
   - Honest gaps, on demand only: `unloaded > 0` -> "I couldn't see N blocks from here: come closer and ask again." `REGION_LIMIT` -> "<name> is
     too large for me to look over at once." Any other refusal: "I couldn't look over <name>: <message>."
4. **When.**
   - `/steward check <id>`: anyone (it is a look, not a decision). The lines go to chat; the steward says the headline. Asked again within
     3 s of its last look, the steward says "I just looked over <name>: ask me again in a moment." (one budget for the command and talk; the
     last look is not reused, since the player may have just broken something).
   - **Talk**, $0: a whole utterance like "what changed?", "anything broken?", "has anything changed?", "check the town", "damage report"
     (`LandWords.ASKED`, `TalkRules`' `END` style). Matched in `Talk.say` before the talk job; the check runs and the steward answers when
     it returns (the `Session` guard as `Follows`). "Change the tavern to stone" and "what changed in the inn's design" still go to the talk job.
   - **Now and then:** a settlement is looked over at most once every 20 minutes, only while a player stands in its claim (its chunks are
     loaded), and not while it builds (`SettlementRunner.busy`: a build, a district road, an undo, a removal), nor while a region is `busy`
     (a revision). The 20 minutes count from the attempt, refused or not. One `landCheck` in flight at a time across all settlements (it shares
     Architect's tick budget). An automatic look says nothing about unloaded cells or `REGION_LIMIT`.
   - **Before a look is kept**, the settlement's regions are read again: a region gone, removed or revised while it was looked over drops the
     look ("The land changed while I looked: ask me again.").
5. **The inbox row (`LAND`).** One per settlement, key `<id>#land`, the latest look's lines (at most 6, then "and N more: ask me what
   changed") and when it was looked; `needsYou` false (no "!", no toast, no board "waits"), its own "Noticed" group. "Got it" (`land_dismiss`)
   clears it.
   - **The damage signature** is per region and per part or lot site: `region|lot:<site>=n` or `region|part:<id>=n`, `n` the removed and
     changed cells, sorted. A player's own blocks and volatile change are not in it.
   - An automatic look **with full coverage** puts the row up and the steward says its headline only when the signature differs from the last
     told or dismissed one and holds some damage; a player decorating updates a row that is up but tells nothing; no damage clears the row.
   - An automatic look **with a gap** (a region refused, or unloaded cells) changes nothing: the row and what was told stay.
   - An on-demand look always replaces the row (gaps said in its lines); its signature counts as told only with full coverage.
6. **Kept in memory only.** The row and the last signature live in the session (`LandChecks`), so an old world loads unchanged and the save
   format does not move. After a restart a note can come back once.

## Gate mechanisms on 0.59.0

LCi (after v0.59.0) fixed "a player's block in a gate's lane reads unchanged". On the pinned 0.59.0 a block in a lane and a broken lever do not
show; only envelope cells (a mined piston) read `removed`. The words promise no more, and the e2e asserts nothing about gates. Recorded in
ARCHITECT-ASKS with LC's two open points: `kinds` keyed by the block now there is fine; a player block identical to ours reading unchanged is fine.

## Not in scope

Repair offers (a revision or a lot delta: a later slice, with a cost and an undo), who broke a block (PT records placements only), checking per
part after `REGION_LIMIT` (no V or castle town is near 4M cells), a button on the settlement screen (a follow-up), street towns not yet adopted.

## Tests

- **Unit** (`LandWordsTest`, `LandChecksTest`): volatile left out; part and lot thresholds; each part's name; lot named by role, repeated
  roles by their lot; `kinds` "now"; district lines; nothing changed; unloaded and `REGION_LIMIT` on demand, silent automatic; the signature
  (player blocks alone do not change it; equal totals in different buildings differ); the keep rule (new damage speaks, the same doesn't,
  dismissed stays dismissed, a gap changes nothing, repaired clears); the talk pattern (asks match; requests, design questions and compound
  asks don't). `InboxModel`: a `LAND` row needs no one, keys `#land`, sends no toast.
- **Not unit-tested** (no fake Architect in Steward's tests): the global slot and the 3 s limit, the re-read of regions after a look, the
  20-minute timer. The e2e covers the real command and talk routes (the talk answered with no talk job).
- **e2e** (`stub --only land`, plus the sanity set): a flat village; `/steward check` says nothing changed; as a player (`dev.pt.breaks`,
  `dev.pt.uses`) two cells of one building are broken and one refilled with cobblestone; the check names that building with 1 broken or changed
  and 1 placed by a player, every other building and part unchanged; the inbox shows the `LAND` row (a screenshot for the design critic);
  "what changed?" said to the steward answers the same; "Got it" clears the row.

## Review

**GPT-6.1, 2026-11-11, design: request changes.** Taken:
- Blocker 1: a look with a gap is unknown, not zero: an automatic one changes nothing, an on-demand one is not counted as told; the words say
  "I found nothing broken or changed in what we built / what I could see", and the rule says what a land check cannot see.
- Blocker 2: the signature is per region and per part or lot, not a sum (an equal-total test).
- Should-fixes: a 3 s limit on repeated asks (a 10 s reuse of the last look was tried first: the e2e showed it answering "nothing changed" right after a
  player broke a block, so it was dropped), the automatic timer counted from the attempt (1); the guards listed and the
  regions re-read before a look is kept (2); the row's lines capped at 6 and its time shown (3); repeated roles told apart by their lot (4);
  orchestration tests where the code is pure, the rest named as e2e-covered or not tested (5). Nits: the row's time; "other built areas".

