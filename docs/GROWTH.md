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

Status: design, 2026-11-11, for review before code. Architect's AD (v0.59.0, `Regions.adopt`/`unadopt`, feature `regionAdopt`) as built; Steward's
answers S-AD-1 (lazy, at a settlement's first growth; no migration on load), S-AD-5 (survival towns later), S-AD-6 (a new region id on re-adopt).

**When.** `afterSurvey`, an addition with no district, where `TownRegion.of` finds no region town, Architect has `regionAdopt` and `growDistrict`,
and the settlement has a standing street (`Districts.streetOf`, east-west; a north-south street the same with `{streetX, zLo, zHi}`). Anything that
can't be adopted keeps today's street-piece path, unchanged, with the reason in the log (not said: the player sees the addition built as before).

**The request** (`AdoptRegionRequest`):
- `owner`, `dimension` the settlement's; `layout` `village_layout`;
- `geometry` `{streetZ, xLo, xHi}` from `Districts.streetOf` (the pieces' `steward_mc:street_*` ext, else the boxes' middle);
- `roadSiteIds`: the settlement's standing `street`/`street#n` road sites on that line (the founding builds' and additions');
- `lots`: every standing lot site of the settlement's town builds (ext `steward_mc:settlement` = the settlement, no district) mapped from a lot
  id: `steward_mc:lot` when it is unique among them and matches `[a-z][a-z0-9_]{0,47}`, else `<lot>_b<n>` (n counting the repeats, newest last);
  not a lot site (a player's adopted build, a district's, a wall lot): left out, and stays as it is;
- `claim`: the union of those sites' boxes plus 4 on each side (x/z), full height: not the settlement claim, which can hold district regions
  (Architect refuses any overlap with another region);
- `opKey` `steward.<build>.adopt`; `ext` the settlement tag.

**Outcomes.**
- `RegionView` PLACED: a history entry **`TOWN_ADOPTED`** (new kind): `siteGroup = region:<id>`, `siteIds` the adopted sites, text "Took the town on
  as one region (Architect) to grow it", saved **before** the growth is planned. Then slice A grows it (`planGrowth`) in the same build.
- `NOT_ALLOWED` (a survival construction site or a shared-crate group, a gap of more than 1 in the street, a lot off the street, not the Overworld,
  an overlap): today's path; the reason in the log.
- A lost answer or a restart: `Regions.list(owner)` filtered by the adopt key's region (`realiseByKey` has no adopt form, so the region's ext carries
  the build tag and is matched by it), else the adoption is asked again with the same key (Architect answers the same region).

**History and undo.**
- `TOWN_ADOPTED` places nothing (`placesSites()` false: its sites are the older builds' sites, and a `PROJECT_REMOVED` of them would read those builds
  as undone). `lastUndoable` returns it when it is the newest thing to undo and no later `TOWN_RELEASED` names its region.
- Its undo is `Regions.unadopt(region)` (position 0, no revision standing: growths are undone first, newest first), then a **`TOWN_RELEASED`** entry
  (new kind, siteGroup `region:<id>`); every site is back in its old group (Architect keeps changes made since), so the older builds' entries undo
  as today (their group, else site by site).
- `TownRegion.of` reads `TOWN_ADOPTED` entries not released as region towns, as it reads a V town's founding entry. `Districts.regionTownGone`
  the same.
- Undoing a growth of an adopted town is slice A's (`undoRevision`); removing a building inside it is `Sites.remove` (RV 4.6), as today.
- The adopted region is never removed whole by Steward: the founding builds are undone through unadopt and their own entries. (`Undo.region`
  is reached only by `region:` PROJECT_PLACED entries; `TOWN_ADOPTED` goes to unadopt.)

**Saves.** Two new `Kind` values. Settlements format 6 (slice A's bump, unreleased) covers them: an older Steward refuses the file. A format-5 file
has neither.

**Not in B.** Survival and Supplied street towns (S-AD-5); street towns with districts whose land would overlap the village's claim (refused, today's
path); the plan writer growing an adopted village through its artifact (`Regions.artifact` + `revision.parentArtifact`); slice A's `planRevision`
grow covers additions.

**Tests.** Unit: the request's lots (repeated `house_1`, a bad id, a district's and a player's site left out), the claim box, `lastUndoable` with
`TOWN_ADOPTED`/`TOWN_RELEASED` (growths first, then the adoption, then the old builds), `TownRegion` reading an adoption. e2e (stub, `stub` flow's
legacy street town): its first addition adopts the town (0 cells changed), grows it by a revision; undo: the growth, then the adoption (unadopt:
the old group's sites back), then the founding build as before.
