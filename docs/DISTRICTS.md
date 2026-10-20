# Steward's districts on Architect's region districts (drafted 2026-10-20)

Noah, 2026-10-20: "yes, proceed with the district move". This is the design; section 6 has Noah's answers. Steward's side is built
on Architect's slice D3 (D-S1, D-S3, D-S4 as a dry beside check; after FL, a few days out; section 7).

## 1. Today

A Steward district is a square claim (radius 48 or 64) on one side of the town's 128-square, at most one per side (N, E, S, W).
Founding it costs $0. Steward lays one road (an Architect road site, `steward_mc:infra`) from the town street's end into the square.
The district has no street until the first building proposal routed to it is accepted. That build surveys the square, lays the
district's own east-west street with Steward's `VillageLayout`, and places its lots and street as a batch. A second road, the link,
joins the street to the founding road. Later buildings extend the street. Undoing the founding road gives the district up once nothing
stands in it. It is saved in `Settlement.lands.districts` as `District(id, name, direction, claim, at, roads, linkUndone)`, format 3.

Limits:
- Only squares, beside the town, along an east-west street.
- Nothing follows the land (a shore, a river, a ridge, a biome edge).
- Nothing stands apart (a mine or a camp).
- Two road sites and a batch per district, joined by Steward's own geometry rather than routed.

## 2. What Architect offers (D1, D2: API 1.27-1.28, in 0.40.0)

- **`Regions.plan` with `beside = <a standing region>`**, program `districts` (bundled, `catalogue = false`, $0, deterministic).
  `params.spec.districts` takes one entry per district:
  - a plain district (`{id, size, lots, prefer, levelBand}`): a survey candidate in the claim, its own V street;
  - a following district (`{follow: {shore|ridge|biome_edge: {between}}, size: [length, depth], side}`): a band along the boundary, its
    street on the boundary's line;
  - an outpost (`{outpost: {gap}}`): apart from the town, reached by its road, its corridor reported (`architect_mc:corridor`).
- The plan routes **one join** from the district's street end to the town's nearest street end in the seam. The district's region
  writes it, so `Regions.remove(district)` removes the district and its join and leaves the town as it was.
- **The seam.** The new claim may overlap the town's by at most 16 columns, never over the town's lots or roads. The join needs a
  town street end whose past-end cell lies in the new claim; otherwise the plan fails `NO_JOIN`.
- A region needs **at least one lot when it is planned**, and Architect can't add to a standing region yet (6d deltas).
- Lots are designed and realised as the V town's are (`Regions.realise` with the lots' blueprints), so a district's buildings go
  through the same design group, approvals and spend as a town build.
- Street roads keep the `street_` stem (`road:<district>_street_w`). Once FL ships, they also carry `architect_mc:street {zone, district?}`.
  Steward recognises both (fd2e507).

## 3. Where each kind of district applies

| Town | District |
|---|---|
| A region (Architect's flat village, or an approved plan), creative | **Region districts** (this design) |
| Steward's own layout (old saves, a north-south fallback), or no town yet | Square districts, as today |
| Survival (`Supplied`) | Square districts until RS ships (a region in survival needs RS's construction mode) |

Square districts keep working and keep loading. Nothing migrates a square district into a region.

## 4. Proposed model (revised after the GPT review, section 8)

**Kinds.** `District.kind`: `square` (today's, the default for every saved district), `beside`, `along`, `outpost`. `District.state`:
`reserved` (a beside district before its first building), `building` (its region being planned or realised), `standing`.

**Three geometries, never confused.**
- The **request claim**: what Steward passes to `Regions.plan`. A search envelope, never owned. For an outpost it runs from the town's
  street through the gap to the candidates, as Architect's own gate builds it.
- The **land**: what the settlement owns, as row runs plus their bounding rect.
  - A reserved beside district owns its reservation (the side's square beyond the town's claim, as today's squares), and keeps it once
    built, so its street has room to grow.
  - An along district or outpost owns what Architect reports its region took: the district's mask, its join's cells outside the
    town's claim and an outpost's corridor (ask D-S3). Until then, nothing of the request claim is owned beyond the region's own claim.
- The **join**: Architect's, written by the district region. Its cells inside the town's claim are town land already.

Every consumer reads the land: `districtAt`, `claims()`, `claimFor`, the overlap test in `SettlementStore.put`, the district's survey
and layout box, `markSites`' exclusions for town builds, and batch street bounds. The bounding square is kept only so that an older
file reader sees a claim. It never authorises a write outside the land. Within one settlement, a district may overlap the town's claim
only in the seam Architect validated, and never another district's land.

**Saved: format 4.** An older Steward re-saving a file through Gson would drop the new members, and could refuse rectangles whose
bounding squares overlap. So the format goes to 4. The v3 to v4 migration keeps every district as a `square`. An older Steward refuses
a v4 file rather than corrupting it (checked: `SettlementStore` refuses a newer format), and the build store, which embeds settlements,
moves with it. The new members are `kind`, `state`, `parent` (the town's region id at founding), `region`, `land` (runs and a rect),
`follow` (the boundary, families and side) and `opKey` (the plan and realise keys' stem).

**The seam is Architect's to judge.** Steward never computes a seam. A beside reservation is checked at founding by Architect's
dry beside check (D3, D-S4: writes and keeps nothing), over the side's request claim beside the town region. The
founding saves only when that plan succeeds; otherwise the player hears Architect's named reason (`NO_JOIN`, `OVERLAP`,
`PROTECTED`). The real first building plans again with its own lot. Steward's own street extensions in a V town aren't in the town
region's graph, so the join can never use them (D-S1 asks for the street network as Architect wrote it).

**Founding and building, one transaction at a time.**
1. **Founding** saves the district (`reserved` or `building`, kind, parent, intent, the opKey stem) and a `DISTRICT_FOUNDED` entry, before
   any paid work.
2. **The build** runs in the build runner as a district build. Its checkpoint names the district. Its plan and realise opKeys derive
   from the district's stem (`dplan`, `drealise`), so a restart finds the plan and the region (as `findVillage` does for V).
3. **On realise,** one `Settlements.replace` writes the district's region, its land, its state `standing` and the `PROJECT_PLACED` entry
   (group `region:<id>`) together. The checkpoint is dropped only after that save.
4. **A partial or failed region** is logged like V's (`PARTIAL`, `FAILED` with its remaining sites), so an undo can take it.
5. **A region left by a stop before the save** is taken up at the next start (VillageSweep's rule), and the district goes back to
   `reserved`, or is removed if it was a quarter.

**Undo.**
- A reservation is undoable by itself. `DISTRICT_FOUNDED` gains an explicit land target (the district id), and `lastUndoable()` counts
  it while that district is still `reserved`. The undo removes the reservation at $0 and retires the entry. A reserved district's
  founding entry stops being undoable once it is built: its build's entry is newer, and once the district has a region, undoing the
  build undoes the district.
- A district is given up only when its own region is confirmed `REMOVED`, no build in it is pending, and no Steward site stands in its
  land (its additions). Ownership holds while a removal is blocked or interrupted.
- **The town undone first:** Architect marks the district's join `dangling`. The district stays, with "its road to the town is gone" on the
  Claim tab.

**Which region is the town.** One shared lookup, `TownRegion.of(s)`: the newest standing region of a `PROJECT_PLACED` entry that is
neither a district's (named by a district's `region`) nor a part's (PendingOffers' `part:` tag). `PendingOffers.townRegion` uses it
too; today it takes the oldest standing region, which after the town is undone could be a district. A district founded while the town
is gone is refused: "there's no town region to build beside".

**Talk and proposals.**
- A district proposal persists its payload: kind, side, boundary (`shore`, `ridge`, `biome_edge` with its two families), lot types
  and the estimate. That way it survives the inbox and a restart.
- A beside reservation is free. An along or outpost quarter is priced like a build (its lots' design estimate), refused while
  autonomy is on, and rechecks the world's construction mode at acceptance (survival: square districts only).
- Talk's district intent gains the kind:
  - "along the river", "by the shore", "on the ridge", "where the desert meets the jungle" → `along`;
  - "a camp apart", "an outpost", "out by the mine" → `outpost`;
  - a side or "here" → `beside`.
- **Growth is by kind, persisted.** `District.growable()` is true for `square`, and for `beside` while its street runs east-west.
  `ProposalRules.where` never routes a building into a district that can't grow (an along district, an outpost, or a beside
  district whose street runs otherwise). This replaces relying on the in-memory "full" mark.
- `Proposals.proposeDistrictFromLand` proposes `along shore` where the land line says water, and `beside` otherwise.
- **The Claim tab's Found chips:**
  - in a region town: Beside (east or west; north and south are square districts, Q3), Along (offered only where the land line has
    a shore, river, ridge or biome edge) and Outpost;
  - in other towns, the four sides, as today.

**Lots.** A district region's lots go through the approved-plan adapter (PPlan's: four fronts, `maxHeight`, each lot's own size),
not V's, which turns every front south and drops the height cap.

## 5. Checks

Beyond the list below (from the review):
- small towns, an expanded claim and a town grown by additions (the join refused or reaching, named);
- another settlement in an outpost's gap and beside a bent corridor;
- reserve, undo, undo again, before and after a reopen;
- the town region removed first, a restart, then a new district and a waiting-part offer (neither adopts the district as the town);
- an interrupted creation (a stop between realise and save), a failed save, a partial region;
- a building proposal never routed into a quarter;
- a curved boundary, east- and west-facing lots, a north or south outpost;
- an older Steward's refusal of a v4 file (a fixture read by the v3 reader's rule).

- **Unit:**
  - the reservation's geometry (seam, sides, refusals);
  - the plan params per kind;
  - `districtAt`, `claims` and the overlap test with `box`;
  - SaveCompatTest with a new **v3 fixture with lands** (a square district with founding and link roads and `linkUndone`) and one with a
    `beside` and an `along` district.
- **e2e stub ($0, Architect's sim):**
  - a V town, a `beside` reservation, its first building (the region, its join walkable, the district's street), a second building
    extending that street, undo of each;
  - an `along` shore district in the e2e world's water, or a stub fixture site;
  - an outpost with its corridor;
  - a square district in a non-region town, as today.
- **e2e free:** the reservation, the survey picture, refusals (protected area, another settlement, no street end).
- **Reviews:** a GPT review of this design, then of the code (persistence, undo), with one fix-verification pass. Both e2e checks pass
  before the push.

## 6. Noah's answers (2026-10-20)

- **Q1. Founding a beside district.** A $0 reservation, built at its first accepted building, then grown along its street.
- **Q2. Along and outposts.** Built at once as a small quarter (2-4 buildings, priced like a build) until Architect can add to a standing
  region.
- **Q3. North and south of a region town.** Square districts there for now. Architect is asked to join a street's side (D-S1).

### 6.1 Found while checking the design: the join can't reach most towns' streets

V's street spans only its outer lots: the lots ±4, clamped to the claim margin (`architect-mc kit/lib/region/village.mjs:324-328`). In
a town that isn't full, both ends sit well inside the claim, deeper than the 16-column seam the join reaches. Every kind of district
needs that join (beside, along and outposts alike), so all of them fail `NO_JOIN` beside most real flat villages. That is the D-S1 ask,
widened. Steward's side waits for it, rather than shipping districts that refuse in most towns or laying its own road to a region.

## 7. Architect asks (round 11, sent 2026-10-20)

- **D-S3. The land a district region takes, for every kind.** The district's mask, its join's cells and an outpost's corridor, as row runs
  and a rect, in the region view's ext (as `architect_mc:corridor` is for outposts today). Steward owns exactly that, never the request
  envelope.

- **D-S1. A join that reaches the town's street wherever it is.** A district's join may run inside the standing region's claim, routed
  around its footprint (lots and their aprons, roads +1, paths), to the nearest point of the town's street network. That point is
  either a street end, where the join continues the street along its line, or a street's side, a T junction (a district north or south
  of an east-west village). The district region writes the join as today (its cells, its undo, `join_external`). The district's own
  claim keeps the 16-column seam rule. Same for along districts and outposts.
- **D-S2 (later, 6d).** Lots added to a standing district region (its street extended), so along districts and outposts can grow.
  **Scheduled (2026-10-20, Noah):** 6d's GR, right after D3 and 7a-1. It grows towns (V and FL streets) and districts, frontage first,
  then one street extension. Once GR ships, along districts and outposts become growable, and Steward's additions in a region town
  (today its own street pieces and batch sites) move onto GR.

## 8. GPT review of this design (2026-10-20, gpt-6.1-sol, max)

Ten findings, each checked against the code and folded into section 4. Blockers:
1. **The 8-column seam was wrong.** The claim must take the past-end cell and miss the street (`village.mjs:320-328`,
   `BesideCheck.java:105`), and most towns' ends are beyond 16 columns. Now Architect judges the seam: a $0 placeholder plan at
   founding. Plus D-S1.
2. **Format 3 was unsafe for downgrade.** An older Steward drops the unknown members on save. Now format 4, with the build store.
3. **The request envelope was conflated with land.** Now three geometries, and D-S3.

Should-fixes:
4. Every geometry consumer reads the land.
5. One transaction per step, with recovery.
6. A reservation's undo target.
7. Give-up only with the region confirmed removed and nothing standing.
8. One town-region lookup. `PendingOffers.townRegion`'s oldest-standing rule was confirmed at `PendingOffers.java:113`.
9. Persisted proposal payloads, pricing and growth by kind.
10. PPlan's lot adapter for district lots.
