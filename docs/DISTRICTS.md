# Steward's districts on Architect's region districts (drafted 2026-10-20)

Noah, 2026-10-20: "yes, proceed with the district move". This is the design; section 6 has Noah's answers. Steward's side is built
once Architect answers the join ask (section 7, D-S1).

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

## 4. Proposed model

**Kinds.** `District.kind`: `square` (today's, the default for every saved district), `beside`, `along`, `outpost`.

**Saved (additive, format 3 unchanged).** New nullable members, so an old file reads unchanged and an older Steward reading a new file
still sees a claim:
- `kind`;
- `region`: the district region's id, once it stands;
- `box`: its land as a rectangle (x0, z0, x1, z1);
- `follow`: for `along`, the boundary (`shore`, `ridge`, `biome_edge` with its two families) and `side`;
- `corridor`: for `outpost`, the road's corridor rect, counted as the settlement's land and freed with the outpost.

`claim` stays the bounding square for code that reads a `Claim`. `districtAt`, `claims()` and `SettlementStore.put`'s overlap test
read `box` when it is set.

**Founding (recommended: section 6, Q1).**
- **Beside.** $0, as today: a reservation of the side's square, overlapping the town's claim by an 8-column seam where a town street
  ends. No road is laid. The district appears in survey mode, dashed until it is built. Its first accepted building plans
  `districts` beside the town region over the reservation (one plain district: that building's lot, `prefer` from the district's
  words). Steward realises it as the district's region, with its street, its join and the building. Later buildings extend its street
  as additions do in a V town, while that street runs east-west (`streetOf`). Otherwise the district is full, and the next building
  proposes another district.
- **Along and outpost.** Architect can't add to them, and a following street isn't a line Steward's additions can extend. So they are
  founded and built at once, as a small quarter: 2 to 4 buildings from the card's program, chosen by the steward (or named by the
  player), estimated and approved like a build. The land is the realised region's box, plus the corridor for an outpost.

**The town's side.** `beside` needs the town's region id. That is the `region:<id>` group of the town's own `PROJECT_PLACED` entry
(`VPlan.REGION_PREFIX`). It is never a part's region (a waiting-part offer) or another district's.

**Undo.**
- The district's build is recorded like a V build: a `DISTRICT_FOUNDED` entry, then the build's `PROJECT_PLACED` entry with the
  group `region:<id>`. The undo takes the region (`Undo.region`).
- `Districts.afterUndo` gives a region district up when its region is gone, and keeps a reserved (unbuilt) `beside` district until the
  player gives it up from the Claim tab.
- Undoing the founding entry of an unbuilt reservation removes it, at $0.
- Undoing the town while a district stands: Architect marks the district's join `dangling`. Steward keeps the district and shows "its
  road to the town is gone".

**Talk and proposals.**
- Talk's district intent gains a kind:
  - "along the river", "by the shore", "on the ridge", "where the desert meets the jungle" → `along`;
  - "a camp apart", "an outpost", "out by the mine" → `outpost`;
  - a side or "here" → `beside`.
- `Proposals.proposeDistrictFromLand` keeps choosing from the land, but proposes `along shore` where the land line says water
  (DistrictSite already scores water).
- The Claim tab's Found chips:
  - in a region town, Beside (N/E/S/W), Along (shore, river, ridge, biome edge, offered only where the land line has one) and Outpost;
  - in other towns, the four sides, as today.

**The street end.** The join needs a town street end in the seam, so a `beside` reservation sits where the town's street ends: east
or west of a flat village. A north or south `beside` needs Architect to join a street's side, not only its end (ask D-S1). Until
then, north and south are offered only as square districts in a region town, or not at all (section 6, Q3).

## 5. Checks

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

- **D-S1. A join that reaches the town's street wherever it is.** A district's join may run inside the standing region's claim, routed
  around its footprint (lots and their aprons, roads +1, paths), to the nearest point of the town's street network. That point is
  either a street end, where the join continues the street along its line, or a street's side, a T junction (a district north or south
  of an east-west village). The district region writes the join as today (its cells, its undo, `join_external`). The district's own
  claim keeps the 16-column seam rule. Same for along districts and outposts.
- **D-S2 (later, 6d).** Lots added to a standing district region (its street extended), so along districts and outposts can grow.
