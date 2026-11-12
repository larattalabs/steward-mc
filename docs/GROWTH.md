# Growth through Architect (slice A: region towns; slice B: adopting street towns)

Status: slice A design, 2026-11-11, for review before code. Agreed with Architect in docs/ARCHITECT-ASKS.md ("Batch 6d contract review", frozen
2026-10-20): a growth's history entry names its revision, its undo is `undoRevision` to the position below (never `Regions.remove`), grown lots go
through the four-front adapter, and additions in region towns move from Steward's own street pieces to GR.

## Today

`SettlementRunner.startAddition` (accepting a proposal, or a quarter) → `afterSurvey`. An addition never takes a region path (`VPlan.applies` and
`appliesSupplied` need `!addition`), so it plans with `VillageLayout.plan(..., streetZ)` against the live street (`Districts.streetOf`) and queues
the building plus `street#n` road pieces as a batch (`fitAndQueue`, `BatchPlanner.build`). That is so even for a town that is an Architect region
(V, plan v1): the addition stands beside the region as loose sites in their own batch group. No mod code calls `planRevision` or `undoRevision`;
only the e2e check does, through dev calls (`survivalGrowth`, `districtGrowth`).

## Slice A: an addition to a region town grows the region

Revised after the GPT design review (2026-11-11: request changes; 5 blockers, 4 should-fixes, all taken). A revision is identified by its **rid**,
never by its position (positions are reused after an undo, and `undoRevision` takes the whole suffix above its target).

**When.** `afterSurvey`, an addition with no district, where `TownRegion.of(settlement)` finds the standing town region and Architect has
`planRevision`:
- the region is busy (`RegionView.busy() != null`), or a revision of it is not ended: the build ends, "the town is being changed; ask again once it
  has settled". It never falls through to street pieces (review 6).
- the region builds from materials (`RegionView.build() != null`): today's path, unchanged (survival growth is slice A2).
- else the growth below. A town that is not a region (a street town) keeps today's path until slice B.

**Lot ids** (review 2). Region lot ids are unique across the region, and `ProgramPlanner` restarts `house_1` per build. A growth's lots are renamed
`g<build>_<n>` (the build id's letters and digits, lowercase, trimmed so the id is at most 48 and matches `[a-z][a-z0-9_]{0,47}`), once, before
the plan; the specs are saved with the build (`districtSpecs`, reused), so a re-plan or a restart sends the same ids.

**Plan.** `planRevision(region, RevisionRequest(program, {grow: {lots, prefer}}, key, "steward", ext))`, `program` the region's own
(`RegionPlan.programId` of its plan; `VPlan.PROGRAM` for V towns), `ext` the settlement and build tags:
- key `vgrow` with `prefer: frontage`; on `NO_ROOM`, key `vgrow2` with `prefer: extend`. `NO_ROOM`/`REGION_LIMIT` on both: `Proposals.full` and
  the build ends (nothing spent).
- The answer is saved before anything else: `vPlanId`, `growRegion`, `growRid` (`revisionOf().rid()`), `growPosition`, `growKey` (the key that
  planned it). Lots through `DPlan.toPlan` (four fronts).
- Restart before the answer was saved: `planByKey` on `vgrow3`, `vgrow2`, `vgrow` in that order, before any plan is sent again (review 3).
  `touchPlan` touches `growKey`.

**Stale.** The realise refused `PLAN_STALE`/`parent_moved` (another revision accepted while the lots were designed): planned once more with the same
ids and sizes under `vgrow3` (prefer as the plan that went stale), the new plan saved as above, its lots re-bound to their designs by id. Refused
again: the build ends saying so.

**Realise.** `realise(RealiseRequest(vPlanId, INSTANT, actor, lotEntries, ..., ext, 0, "vgrowrealise" (or "vgrowrealise3" after a stale re-plan)))`.
The answer is the town's id; the revision is the one whose `planId` equals `vPlanId` (review 3), not one found by tagged sites. `adoptGrowth` saves
`vRegionId` and `siteGroupId = "revision:<region>:<rid>"`.

**The runner's region paths** (review 1, 4, 5). One rule: **a growth never calls `Regions.remove` or `VillageSweep.takeUp`**; every take-up is
`Growths.undo(region, rid)`:
- the revision by rid; ended `UNDONE`: done (nothing to take);
- the region's head position is not the rid's position (a newer revision stands): refused, said, nothing written;
- the revision `UNDOING` (a take-up a restart cut short): the same `undoRevision(region, position - 1)` again, which resumes it;
- the revision still placing (busy): wait; the runner or undo retries on its next tick or call;
- else `undoRevision(region, position - 1, owner)`; done when the answer says removed.

| Path | A growth |
|---|---|
| `watchVillage` | the revision by rid: ended (PLACED/PARTIAL/FAILED in `RevisionView.state`) → logs its `sites()` under `revision:<region>:<rid>`; the region's state is not the outcome |
| nothing placed (no sites) | `Growths.undo`; the history gets a note, no entry |
| `cancelVillage` (CANCELLING) | before the realise is answered: waits for it (the phase stays CANCELLING, the checkpoint kept); realised: the growth's entry is logged first, then `Growths.undo`; refused or waiting: the entry stays, said, and undo takes it up later |
| `findVillage`, `settleLostRealise` | `realiseByKey(vgrowrealise / vgrowrealise3)`, then the revision whose `planId` is `vPlanId`; never "a region with the build tag" |
| `takeUpOrphan` | logs the growth's entry, then `Growths.undo` (never the region) |

A build whose growth realise is in flight is never dropped from the save: a cancel waits in CANCELLING for the answer (or the lost-answer lookup),
so a restart resumes the obligation (review 5).

**History** (review 7). The growth's `PROJECT_PLACED` entry: `siteGroup = "revision:<region>:<rid>"`, the revision's sites. `lastUndoable` keeps a
`revision:` group after a partial removal record (it does not fall back to site by site). `TownRegion.of` and `regionTownGone` read only `region:`
entries, so the founding entry still names the town.

**Undo** (`Undo.lastProject`), before the region and group branches: a `revision:` entry → `Growths.undo`; done → `PROJECT_REMOVED` with its sites;
the region gone → the entry retired. **The founding entry** (`Undo.region`) is refused while the region is busy, has a revision in flight, or has one of Steward's own logged growths
still standing (review 1), whatever its sites say. A revision Steward never logged (a dev or another mod's) goes with the town, as the RV RC agreed
(`revisionFlow`'s "town undone with a revision").

**Saves** (review 8). `BuildStore` FORMAT 3 → 4 (`growRegion`, `growRid`, `growPosition`, `growKey`; a format-3 build has none). Settlements FORMAT
5 → 6 with a no-op migration, so an older Steward refuses a file whose history may hold `revision:` entries. `SaveCompatTest` fixtures for both.

**Tests** (review 9). Unit: the lot renaming (repeated types, the id rule); `Growths.decide` as a pure function over a `RegionView` (UNDONE, newer
head, reused position with another rid, UNDOING, busy); the founding undo refused with a revision standing and no visible site; the plan-key
recovery order; `lastUndoable` keeping the tag; both save formats. e2e (stub, `growth` flow): a V town accepts a proposal of a type it already has
→ one revision under the build's ext and keys, no `street#` site, the town still found; undo of the town refused while it stands; undo of the
growth by `undoRevision` leaves the town byte-equal (`dev.region.hash`); a cancel mid-design ends with nothing; then the town undone.
Not covered by e2e: parent_moved and restarts mid-realise (unit tests and the decision function only).

**As built (2026-11-11), after the GPT implementation review** (request changes: 2 blockers, 5 should-fixes, all but one taken):
- a second cancel waits while a growth is realised or in flight (`cancel()`), so the build's save, and with it the take-up, is never dropped;
- a growth that placed nothing keeps the build until its revision is taken up (asked again each second); one that can't be (a newer change stands on
  it) ends with a note naming the revision; an orphan's empty revision that can't be taken up is noted the same way;
- `REMOVING` reads as "wait", not "gone" (Architect can stop a removal and the town stands again);
- a cancel whose realise answer was lost looks the growth up by its keys and plan after 3 minutes and takes it up;
- a stale realise answered after a cancel ends the cancel (nothing was written), and a re-plan never realises a cancelled build;
- a busy town waits before the materials check, whatever it is built from.
- **Not taken:** a restart during the first `planRevision` (before the build's state exists) drops the build, as any build's restart before its
  plan does. Nothing is spent then, and the candidate plan goes with Architect's sweep; the player accepts the proposal again.

**Not in A.** Survival towns (A2), districts' growth (`dgrow`, D-S2), street towns (slice B).

## Slice B: a street town adopted at its first growth

Status: design v2, 2026-11-11, revised after the GPT design review (request changes: 3 blockers, 5 should-fixes, 1 nit; all taken). Architect's
AD (v0.59.0, `Regions.adopt`/`unadopt`, feature `regionAdopt`) as built; Steward's answers S-AD-1 (lazy, at a settlement's first growth; no
migration on load), S-AD-5 (survival towns later), S-AD-6 (a new region id on re-adopt).

**When.** `afterSurvey`, an addition with no district, where all hold:
- `TownRegion.of` finds no region town, and Architect has `regionAdopt` and `growDistrict`;
- the world builds instantly now (not `regionSurvival`) and the settlement is not Supplied (review 7: a street town that may hold construction
  sites, or that would be grown from materials, keeps today's path);
- the town has a standing **east-west** street (`Districts.streetOf`; it skips north-south streets, and so does B: review 9).

Else today's street-piece path, unchanged.

**The inventory** (one extractor, `Adoption.inventory(settlement, live sites)`, review 5), over the owner's live sites in the settlement's dimension:
- **roads:** the pieces `Districts.streetOf(sites, s, null)` reads as the town's street (it already tells district streets apart by district
  land): `street`/`street#n` road sites on that line;
- **lots:** sites with `steward_mc:settlement` = the settlement and a `steward_mc:lot`, standing, in no region, with their box's centre in the
  town claim and in no district's land, not a wall lot (`architect:wallLot` ext), not a player's build the settlement took on (a `BUILD_ADOPTED`
  entry's site). Removed or partly undone builds give only their standing sites (the live list).
- **lot ids** (review 6): a deterministic allocator, sites in id order (`s<N>` numerically): the base is `steward_mc:lot` lowercased, every
  character outside `[a-z0-9_]` dropped, `l_` put in front when it does not start with a letter, cut to 40; a base taken already gets `_2`, `_3`,
  ... (the whole at most 48). The map `lotId -> siteId` is saved in the intent below.
- **claim** (review 3): the settlement's own **town claim** (its square, full height), so GR has the whole town's land to extend the street into.
  District regions stand on their own claims, outside it; anything else Architect finds overlapping refuses, and B falls back.

**The adoption is saved before it is asked** (review 2). It happens before the build has a pipeline state, so its record is the settlement's
history, not the build's save:
1. **Intent:** a `TOWN_ADOPTED` entry with `siteGroup = adopt:<opKey>` (opKey `steward.<build>.adopt`), its `changes` the lot map (`SiteChange(siteId,
   lotId, 0, version)`) and the road sites (`lot` null), outcome `PARTIAL` (pending). Not saved: the adoption is not asked; today's path.
2. `Regions.adopt(owner, dimension, claim, village_layout, {streetZ, xLo, xHi}, roads, lots, opKey, ext {steward_mc:settlement, steward_mc:adopt =
   opKey})`.
3. **Answered PLACED:** a second `TOWN_ADOPTED` entry, `siteGroup = region:<id>`, the same changes, outcome `DONE`. Then slice A grows it. Not saved:
   the build stops ("try again"); the world-start reconcile below writes it.
4. **Refused, definitively** (`NOT_ALLOWED`, `OVERLAP`, `PROTECTED`: nothing moved, review 7): a `TOWN_RELEASED` entry `siteGroup = adopt:<opKey>`
   ("not adopted: <reason>"), then today's street-piece path in the same build.
5. **Anything else** (busy `SITE_BUSY`, a timeout, a lost answer, the world stopping): the build stops ("the town is being changed; ask again");
   never today's path while the outcome is unknown.

**Reconcile** (at world start, before `VillageSweep`, and before any undo or growth of the settlement), for an `adopt:<key>` intent with no later
`region:` or `TOWN_RELEASED` entry for that key: a region of the owner whose ext `steward_mc:adopt` is the key, not removed → its `region:<id>`
entry; none (Architect rolled its intent back at its own world start, or never got the call) → `TOWN_RELEASED adopt:<key>` ("not adopted").

**Never removed whole** (review 1): an adopted town is never taken up by `Regions.remove` from Steward:
- `VillageSweep` skips every region whose ext has `steward_mc:adopt` (and the adopt request carries no build tag, which the sweep keys on);
- `VillageSweep.entryOf` and `TownRegion.of` read `TOWN_ADOPTED region:` entries; `Undo.region` is reached only from `PROJECT_PLACED` entries;
- a growth of it is slice A's (`undoRevision`).

**History and undo.**
- `TOWN_ADOPTED` places nothing (`placesSites()` false: its sites are the older builds'). `lastUndoable` returns the newest `TOWN_ADOPTED region:`
  entry with no later `TOWN_RELEASED` for its region, when nothing newer is undoable (growths are newer: undone first).
- **Its undo:** `Regions.unadopt(region)`, then `TOWN_RELEASED region:<id>`. Every site goes back to its old group (Architect keeps the changes made
  since), so the older builds' entries undo as today. No `PROJECT_REMOVED` is written for the old sites.
- **A lost unadopt answer** (review 4): Architect deletes the record on success, so a retry would fail. Before asking again, and in the reconcile:
  the region is gone with no tombstone, and the entry's standing sites are in no region → unadopted: `TOWN_RELEASED` written once. A `REMOVED`
  tombstone is a removal, never read as a release.
- `TownRegion.of` and `Districts.regionTownGone` read an unreleased `TOWN_ADOPTED region:` entry as the town's region. After a release, the town is
  a street town again (district founding reads its street as before).

**Saves.** Two new `Kind` values (`TOWN_ADOPTED`, `TOWN_RELEASED`). Settlements format 6 (slice A's bump, unreleased) covers them.

**Not in B.** Survival and Supplied street towns (S-AD-5); north-south streets; the plan writer growing an adopted village through its artifact
(slice A's `planRevision` grow covers additions).

**Tests** (review 8).
- **Unit:**
  - the inventory: a square district's batch lots and street left out by land, wall lots, a `BUILD_ADOPTED` site, region sites, removed sites;
  - the allocator: repeated `house_1`, an invalid or 60-character id, collisions with kept ids;
  - `lastUndoable` with `TOWN_ADOPTED`/`TOWN_RELEASED`: growths first, then the adoption, then the old builds;
  - the reconcile's decisions as a pure function: intent only, region found, region missing, tombstone vs released;
  - `TownRegion` reading an adoption; save round trips.
- **e2e (stub), on the `stub` flow's legacy street town:**
  - its library addition adopts the town (0 cells changed by the adoption) and grows it by a revision; a second addition that needs a street
    extension;
  - undo: the growths, then the adoption (unadopt: the old groups' sites back, no `PROJECT_REMOVED`), then the founding build as before;
  - an update and a revert of an adopted building between.
- **Not covered by e2e:** a restart between the intent and the answer (the reconcile's pure function is unit-tested).
