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
- the region builds from materials (`RegionView.build() != null`): today's path, unchanged (survival growth is slice A2, below).
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
Not covered by e2e: parent_moved and restarts mid-realise (unit tests and the decision function only). **Covered since slice GE (2026-11-13):** the
`growth` flow's `growth replan` (a dev revision realised at the massings: parent_moved, re-planned under vgrow3, the client restarted as that plan
is made, the realise picked up after it) and `growth orphan` (the build dropped across a restart once its revision stands: the sweep logs it and
takes it up; in a creative town).

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

## Slice A2: a town built from materials grows from materials

Status: design v2, 2026-11-11, revised after the GPT design review (request changes: 3 blockers, 5 should-fixes, 2 nits; all but one
should-fix taken, see "Review" below). It lifts slice A's "built from materials: today's path" (`RegionView.build() != null`):
such a town still laid Steward's own street pieces beside the region. Everything slice A says (rid not position, lot ids, keys, the runner's
paths, history, saves) holds; this section says only what changes. Architect's side, as built (CONTRACT RV §8, GR, XR; `Revisions.realise`
and `undo0`):
- a revision is planned in the **world's** mode (`RegionSurvival.on`: the toggle on, the default game type not creative) and realised only
  in the **region's** own mode (`rec.survival()`, fixed at its realise): a mismatch refuses the realise, `NOT_ALLOWED` ("plan it again"),
  after Steward has paid for the designs. The realise's `mode` is not read for a revision;
- the realise refuses `NOT_ALLOWED survival_unfinished` while any target of the region (its own or an earlier revision's) is not built;
- a survival revision ends PLACED once its targets are put down (RS: "requests start at PLACED and built is done"); its targets then draw
  from the owner's stockpile, and `RegionView.build()` (the whole region's: `built`, `done`, `percent`, `outstanding`) covers them;
- `undoRevision` refuses `NOT_ALLOWED survival_building`, nothing written, while a suffix target is not built (§8's "unbuilt targets are
  cancelled first" is not what shipped); once built, the suffix's paid blocks are refunded once per key (`site:<id>@<placedAt>`,
  `road:<id>@<placedAt>`) into the stockpile, the land back free;
- nothing cancels a revision's unbuilt targets: `Sites.pause` stops one drawing, but a paused target never builds, so it could never be
  undone.

**When** (`afterSurvey`, slice A's addition branch, the town found and not busy, no revision in flight). One rule for both kinds of town:
a growth is asked only when the **town's mode equals the world's mode now** (town `build() != null` iff `regionSurvival`). Else the build
ends before anything is spent: "<the town> was built <from materials | at once> and this world builds <at once | from materials> now:
Architect grows a town only the way it was built." (This also closes slice A's gap: a town built at once in a world turned survival since
planned in survival and was refused at the realise, after its designs.)
For a town built from materials, also:
- **not fully built** (`!build().built()`): "<the town> is still being built from the stockpile (<percent>%): it can grow once it's built."
  Nothing spent (Architect would refuse `survival_unfinished` at the realise);
- **its stockpile**: `Stockpiles.ensure` (the town's targets draw from it); none can be made: ended as `planComposed` ends it;
- then slice A's `planGrowth`, unchanged (keys `vgrow`/`vgrow2`/`vgrow3`, the lots renamed, the answer saved first).

**Realise.** The same keyed `realise` (`vgrowrealise`/`vgrowrealise3`), with `Mode.CONSTRUCTION` when the town builds from materials
(read from the town at the realise; Architect ignores it for a revision, but the request then says what it does). A realise refused for
the mode or `survival_unfinished` (the world's mode changed while the designs ran) ends the build in plain words ("the world's mode changed
while it was designed: start it again"), as slice A's other refusals end it.

**Watch.** `watchGrowth` unchanged up to the revision's end (by rid). Ended PLACED or PARTIAL with sites: logged under
`revision:<region>:<rid>` and the build ends (`BatchDone`), as slice A. Then, when the town builds from materials: said that its
construction sites stand and are built as the stockpile is fed, with what the stockpile still owes (`build().outstanding()`, the town's), and
`MaterialRequests.watchBuild(settlement, region)`, which says once when the town is fully built again. The material waits (bubble, inbox,
the long-wait summons) are today's: the targets are the owner's, fed from the settlement's stockpile.

**Undo** (`Growths.decide` gains one input, `building`: the town builds from materials and **the revision's own sites** are not all built,
`Growths.unbuilt`: a site of `RevisionView.sites()` whose `SiteView.state` is BUILDING or PLACING, as Architect's undo preflight reads its
suffix). Never the town's whole build: an update of an older building from materials makes the town unfinished again without touching the
revision (review 1). Order: GONE, WAIT (removing), UNKNOWN, GONE (undone/stale), UNDO (resuming its own UNDOING take-up), WAIT (busy), WAIT
(placing), NEWER, then **BUILDING**, then UNDO. BUILDING asks nothing:
- `Undo.growth`: "The addition is still being built from the stockpile (<n> of its <m> construction sites still to build): it can be undone
  once it's built. Fill the stockpile to finish it." The entry stays; `/steward undo` again later. A `survival_building` refusal that slips
  past (a race) is said in the same words, never Architect's text.
- once built: `undoRevision` as slice A; Architect refunds each paid block once into the stockpile (`Undo.reportingOverflow` says what did
  not fit); `PROJECT_REMOVED` logged once, **also across a crash** (review should-fix 1): before it is logged, `Undo.retired` checks that no
  later removal names all the entry's sites already, and a cancel replayed after its removal was logged ends without logging again. The
  entry retired, a second undo moves on to the next entry (the town), never the growth again.

**The runner's paths for a growth built from materials:**

| Path | Change |
|---|---|
| `watchGrowth` | as slice A; after the entry and `BatchDone`, "The addition's construction sites stand and are built from the stockpile as it's fed (the stockpile still owes ...)" (the town's outstanding bill: Architect sums it per town) and `watchBuild` |
| `cancel()` once realised | **refused** (review 2): "The addition is being placed from materials and can't be stopped part way: it's built as the stockpile feeds it, and /steward undo takes it up once it's built." The build goes on as if not cancelled: logged when its revision ends, watched |
| `cancelGrowth`, a cancel that came while the realise was in flight | waits in CANCELLING as slice A (the obligation is saved with the build), said once in the words above: a revision can hold its later items until earlier targets are built, so this may need feeding |
| `cancelGrowth`, revision ended, its targets built (fed in time) | as slice A: logged, then taken up exactly, refunded once |
| `cancelGrowth`, revision ended, its targets building | logged first (as A); `Growths.undo` answers BUILDING; the cancel ends as slice A's refused take-up (`BatchDone`): "The addition's construction sites stand and are built as the stockpile feeds them; it can't be taken up until it's built. /steward undo takes it up then." `watchBuild` |
| `takeUpEmpty` (ended with no sites) | unchanged: a revision with no sites has no targets, so it is never BUILDING |
| `takeUpOrphanGrowth` (realised after its build ended) | ended: logged first (as A), `Undo.growth` (BUILDING leaves the entry, said); **still placing** (review 3): an orphan note (a `NOTE` with the growth's tag) is saved, and the sweep below logs or takes it up once it has ended, never logged with half its sites |
| the growth sweep (`VillageSweep.growths`, new; at world start, every 10 s from MaterialRequests' tick, and before an addition chooses its path) | a town revision that has ended, that no history entry names, and that is the settlement's: an open orphan note names it, or any standing site of it carries this settlement's tag and a build id (`b<time>`) no live build holds. With sites: logged under its tag, watched if built from materials, then `Undo.growth` (as an orphan village is taken up). With none (only a noted orphan): taken up by `Growths.undo` (an empty revision still holds the town's history: an adoption can't be let go above it), then the note closed. Durable: the notes and the history are the state |
| `Undo.lastProject` | **head first** (fix round 2): a growth logged late (the sweep's) can be the newest entry while a newer revision stands above it; the standing entry of the town's head revision is undone first (`Undo.headFirst`), so undo never loops on a refusal |
| `realiseVillage` | **re-checked at the realise** (review should-fixes 2, 3): the mode gate both ways, a town built from materials still fully built (an update since: "being built from the stockpile again", its own words), and its stockpile standing (gone: the build ends; making crates now could land on the planned lots) |
| `realised`, refused | `survival_unfinished` and the mode refusals each in their own plain words |
| `findVillage`, `settleLostRealise`, the cancel's lost-answer lookup | unchanged (keys and plan id) |
| world start | `MaterialRequests.seedBuilds` also reads `revision:` entries (their region, `Growths.parse`), so a town still building an addition after a restart is watched and said done once |
| `readBuilds` | a town that stood PARTIAL before its growth reads PARTIAL again after it (Architect), so `built` is enough there; `done` needs PLACED (review should-fix 5) |

At the survey, the stockpile's crates are protected (`Stockpiles.protectCrates`, as `planComposed`), so the growth keeps off them.

Pausing the targets on a cancel was rejected: a paused target never builds, so the growth could never be undone. Asked of Architect
(ARCHITECT-ASKS "A2"): `undoRevision` of a survival revision with unbuilt targets cancels them first, refunding what they consumed (§8 as
written), so a cancel needs no feeding.

**Saves.** No format change: the build's mode is read from the town (a region's mode never changes), the growth fields are slice A's.

**Fix check, round 1** (GPT): every finding resolved but B3, partly: an older orphan logged late above a newer growth blocked undo, and the
sweep found an orphan only by its first site and skipped one that ended with no sites. Round 2: undo head first, orphan notes, any standing
site, empty orphans taken up (above), unit-tested.

**Fix check, round 2** (GPT): the blocker resolved, every other item still resolved; 3 should-fixes, no blocker, fixed without a third
round (the slice's limit is two): an empty orphan's note stays open while a newer change stands above it (only Architect having no such
revision closes it); a note whose save failed is saved again by the sweep (this session: a restart before that save, with the revision then
ending empty, still loses it, as any build's restart before its first save does); the sweep's in-flight set is cleared when the world stops.

**Review** (GPT, 2026-11-11, request changes). Taken: 1 BUILDING from the revision's own sites; 2 a cancel once realised refused (the
build goes on), one in flight waits as A; 3 an orphan still placing left to a durable sweep; should-fixes 1 (removal logged once), 2 (the
stockpile checked at the realise, crates protected at the survey), 3 (the mode re-checked at the realise, both ways; `survival_unfinished`
in its own words), 5 (PARTIAL towns' completion); both nits. **Not taken:** should-fix 4, an unanswered realise of a build that has not
ended (no callback and no world stop) is not looked up by its keys: it is every village build's (slice A and V), not A2's, and Architect's
futures complete; recorded as a follow-up.

**Tests.**
- **Unit:** `Growths.decide` with `building` (BUILDING after NEWER; UNDOING resumes even while building; busy waits first; not building:
  UNDO); `Growths.unbuilt` (only the revision's sites; a gone site is built); the mode gate (town x world x built); the plain wording;
  `Undo.retired`; `Undo.headFirst` (an older orphan logged after a newer growth); the orphan note's open/closed reading; `seedBuilds`'s
  region of a `revision:` entry.
- **e2e (stub), flow `supplied-growth`**, through Steward only (no dev revision calls): a Supplied town from an unwalled composed plan
  (`no_wood`; the walled one has no room: `NO_ROOM [street]`), fed until built; the ground snapshotted; a library asked in talk and accepted;
  "The town grows" planned and realised as a revision under the build's keys (half its needs fed every 30 s while the revision is placed),
  the entry `revision:`, what the stockpile owes said; while its targets wait, `/steward undo` refused in plain words, nothing written (`dev.region.hash`),
  the entry kept; fed until the town is built again (the steward says so); the revision's targets' bills = what the stockpile paid;
  `/steward undo` takes it up (`PROJECT_REMOVED` once, revision UNDONE, the ground as before the growth but the crates), and what it consumed
  comes back, exactly once (nothing more 20 s on); then the town undone with the rest of its refund.
- **Not covered by e2e:** a cancel (refused once realised, or waiting from in flight), an orphan and the sweep, a restart while building,
  a mode change between survey and realise, a crash between the removal's entry and the build's save (unit tests and the decision function
  only).
- **e2e since slice GE (2026-11-13), in `supplied-growth`:** a growth cancelled at its massings (before the realise) and one whose world turned
  to building at once at its massings (refused at the realise in plain words), each with no revision and 0 cells changed; a restart while the
  growth's targets wait for items (watched again, the steward says once when it is built). The orphan sweep is `growth orphan` (creative). Still
  unit-only: a cancel once realised or from in flight, and undo while waiting, which change with Architect's RU (v0.66.0).

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
  town claim and in no district's land, not a player's build the settlement took on (a `BUILD_ADOPTED` entry's site). Wall lots are left out
  by "in no region" (they are a plan v1 town's region lots). Removed or partly undone builds give only their standing sites (the live list).
- **lot ids** (review 6): a deterministic allocator, sites in id order (`s<N>` numerically): the base is `steward_mc:lot` lowercased, every
  character outside `[a-z0-9_]` dropped, `l_` put in front when it does not start with a letter, cut to 40; a base taken already gets `_2`, `_3`,
  ... (the whole at most 48). The map `lotId -> siteId` is saved in the intent below.
- **claim** (review 3): the settlement's own **town claim** (its square, full height), so GR has the whole town's land to extend the street into.
  A district region's claim can reach into it at its seam, and a pending part's region can stand on it: Architect refuses any overlap
  (`OVERLAP`), and B falls back.

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

**Pairing** (design review 2): one build of a settlement runs at a time, so at most one intent is open. An intent `adopt:<key>` is closed by the
first later `TOWN_ADOPTED region:` entry (adopted) or `TOWN_RELEASED adopt:<key>` entry (not adopted); a `TOWN_ADOPTED region:<id>` entry is closed
by the first later `TOWN_RELEASED region:<id>`. Re-adopting after a release is a new build, a new key, a new intent: the region id may differ
(S-AD-6), and the pairing never reads a region's ext once it is gone.

**Reconcile** (at world start, before `VillageSweep`, and before any undo or growth of the settlement), for an open `adopt:<key>` intent: a region of the owner whose ext `steward_mc:adopt` is the key, not removed → its `region:<id>`
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
- **The town removed outside Steward's undo** (Architect's `Regions.remove`, a `REMOVED` tombstone): the open `TOWN_ADOPTED region:<id>` is retired
  by `TOWN_RELEASED region:<id>` ("the town was removed") and a `PROJECT_REMOVED` of its sites that are gone, so the older builds' entries read as
  undone and undo moves on (as a removed region's entry is retired today).
- **The region and every building gone, no tombstone** (slice GH, 2026-11-13; an un-adopt whose answer was lost, then each building removed, or
  any other way): retired the same way, `PROJECT_REMOVED` of the gone sites ("gone from the world") then `TOWN_RELEASED region:<id>` ("the town's
  region and its buildings are gone"), never "let go". Only when Architect lists any site at all: a site file Architect could not read lists none,
  every site would only look gone, and the adoption is left open (warned) for a later start. Some sites standing, all in no region: a release.
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
  - the pairing: adopt, release, re-adopt (a new key), saved and read back;
  - `TownRegion` reading an adoption; save round trips.
- **e2e (stub), on the `stub` flow's legacy street town:**
  - its library addition adopts the town (0 cells changed by the adoption) and grows it by a revision; a second addition that needs a street
    extension;
  - undo: the growths, then the adoption (unadopt: the old groups' sites back, no `PROJECT_REMOVED`), then the founding build as before;
  - an update and a revert of an adopted building between.
- **Not covered by e2e:** a restart between the intent and the answer (the reconcile's pure function is unit-tested). Since slice GE, `adopt restart`
  covers the not-adopted side (an unanswered intent written into the save, closed at the world start).

**As built (2026-11-11).** `service/Adoption.java` (the inventory as a pure function, the allocator, the history entries, the pairing, the reconcile,
the release), `SettlementRunner.adoptTown`, the `TOWN_ADOPTED`/`TOWN_RELEASED` kinds, `lastUndoable`, `TownRegion`, `VillageSweep` (the reconcile
of every settlement before the sweep; adopted towns skipped), `Undo` (the reconcile first, the release branch).
- **The GPT implementation review** requested changes: 1 blocker and 3 should-fixes, all taken. The fix pass approved 1708516.
  - The reconcile runs before an addition chooses between growth, adoption and Steward's street.
  - Only an adopt call in flight holds the reconcile off.
  - A removed town's removal record is saved before its release.
  - The release's fast path checks that the adoption is closed.
  - Live versions are recorded, and the inventory is unit-tested.
- **The dev layout `legacy`** (the e2e) also holds a street town off adoption, so the `stub` flow keeps the street-town path for its districts.
  The new `adopt` flow covers the adoption.
- **Not covered by e2e:** a second addition that needs a street extension; an update or revert of an adopted building; restarts across the
  adoption. The reconcile's decisions and the pairing are unit-tested.
- **e2e since slice GE (2026-11-13), in `adopt`:** `adopt restart` (an unanswered adoption closed at the world start, then adopted under a new key),
  `adopt update revert` (a founding building updated and reverted inside the region, the ground as before) and `adopt street extended` (the
  frontage filled with dev pads, then an addition whose plan extends the street); the growths undone head first.

