# A5b spec: macro kit and macro checker

Written by the Steward session for the Architect session (ask A5b, phase 6 in architect-mc/docs/PLAN.md). Architect reviews
this and turns it into a CONTRACT section when it builds it; nothing here binds Architect until then. Draft 2026-10-05,
revised the same day after Architect's answers (journal-backed sites, one shape-math implementation, see section 7).

Goal: let Claude write a **region program** for a whole site (a rift, a sky city, a crater facility, a walled castle, a
terraced hillside village), check it without the game, show it as a ghost, and place it through the snapshot path, with
its buildings as separate child designs.

Scope: A5b is the **kit and checker**. The engine pieces it relies on come first (A5a nested sites, sparse snapshots,
terrain operators through the snapshot path, roads/bridges as sites; A7 batch placement and site groups). Where this
spec needs something from them it says so under "Needs from A5a / A7".

## 1. Where it sits in the kit

Today a design is a `Blueprint` (one template, one box, `kit/lib/kit.mjs`) with `build()` returning it. A5b adds a second
program type beside it:

```
kit/designs/<id>.mjs   -> default export build(params) -> Blueprint        (unchanged)
kit/regions/<id>.mjs   -> default export region(ctx, params) -> Region      (new)
```

A `Region` is a **description**, not a block array. It holds: terrain ops, lots, roads, bridges, utilities, anchors and
the claim. Realising it produces (a) terrain writes, evaluated per chunk section, and (b) N child building jobs, one per
lot, each an ordinary Blueprint design (96x64x96 cap per child, unchanged). A region is never one giant template, so the
design-job cap stays as it is and the region itself has no cap (only the claim and a block budget).

Two phases:
1. **Plan** (sidecar, no game): the program runs with `ctx.survey`, a coarse sample sent by the mod (heightmap grid,
   biome, sea level, water, a hash). It returns the Region description (JSON). The checker and the previews work on a
   virtual world built from the survey plus the ops.
2. **Realise** (sidecar evaluates, mod writes): per chunk section, the sidecar takes a **fresh survey of those sections**,
   evaluates the ops (the shape math lives only in the JS kit) and returns **cell lists**; the mod writes them through the
   change-set journal. The ghost uses the same cell lists. If the fresh survey has drifted from the plan survey beyond a tolerance
   (sample hash and a few probe columns), realisation stops and asks for a replan. Ops expressed relative to the surface
   (`y: { surface: -3 }`) are evaluated against the fresh heights; absolute Y is allowed (sky cities) and the checker warns near
   the build limits.

## 2. Region API (sketch)

```js
import { Region, shapes as S, roles } from '../lib/region.mjs';

export const id = 'crater_works';
export const params = { radius: { type: 'int', min: 40, max: 160, default: 90 }, depth: { type: 'int', min: 12, max: 48, default: 28 } };

export default function region(ctx, { radius = 90, depth = 28 } = {}) {
  const r = new Region({ id, claim: ctx.claim, bible: ctx.bible });   // material roles come from the bible, not raw blocks
  const c = ctx.survey.pickCenter({ flat: true, near: ctx.claim.center });
  r.part('bowl').carve(S.bowl(c, radius, depth), { to: roles.rock, lining: roles.scorched, naturalOnly: true });
  r.part('rim').add(S.ring(c, radius, radius + 6, { rise: 6 }), roles.rubble);
  r.part('ramp').stair(S.path([ rimPoint, floorPoint ], { width: 5, spiral: true }), { carve: true, railing: roles.rail });
  r.part('floor').terrace(c, [0.2, 0.45, 0.7], { levels: 3, riser: 2 });          // work levels
  const lots = [
    r.lot('foreman_office', { at: terraceCell(1), size: [18, 14], max: [24, 20, 24], front: 'toward:ramp', brief: 'Overseer office, windows over the pit' }),
    r.lot('ore_hall',       { at: terraceCell(2), size: [32, 24], max: [40, 30, 36], front: 'toward:ramp', brief: 'Sorting hall, crates, conveyors' }),
  ];
  r.road(S.path([ lots[0].entrance, lots[1].entrance ]), { width: 3, light: 'lantern_post' });
  r.bridge(S.path([ a, b ]), { width: 4, rail: true, support: { every: 12, style: 'arch' } });
  r.anchor('entrance', rimPoint); r.anchor('spawn', outsideRim);
  return r;
}
```

### Primitives

All shapes are composable signed-distance fields (`union`, `subtract`, `smooth`) so any cell can be evaluated
independently and streamed by chunk section. There is one implementation (the kit's JS); no Java copy.

| Primitive | Does | Notes |
|---|---|---|
| `carve(shape, opts)` | removes natural blocks inside the shape, optional `lining` | never removes player blocks, block entities, or anything outside the claim |
| `add(shape, material, opts)` | adds blocks (platform, rim, island, mass) | may write into air and natural blocks only; `underside: 'flat' \| 'taper' \| 'pillars' \| 'rock'` for floating masses |
| `platform(poly, y, opts)` | flat slab with edge and underside style | sky districts, terraces |
| `pillar(at, opts)` | support column to ground or bedrock | auto-added under `add` with `underside: 'pillars'` |
| `bridge(path, opts)` | deck, rails, supports every N, optional arches/towers | placed as a site (A5a) |
| `stair(path, opts)` | carved or built stairs/ramps/spirals, landing every N | rise <= 1 per step, 2 headroom |
| `cavern(shape, opts)` | noise-edged hollow with lighting and floor | seeded, deterministic |
| `noise(field)` | seeded mask or height field used by other ops | same seed, same result, for variants and delta |
| `ring(centre, r0, r1, opts)` | circular wall/rim with towers and gates | castles, crater rims |
| `terrace(centre, fractions, opts)` | stepped flat levels with risers | hillside and pit work levels |
| `lot(id, opts)` | a named building pad: footprint, floor level, front direction, brief, child size cap | produces a **child design job** and a flat pad with foundation fill |
| `road(path, opts)` | path with width, surface by biome, lantern posts | placed as a site (A5a); AgentCraft roads are the model |
| `utility(path, opts)` | reserved corridor for a spine (water, item, power) | no blocks at A5b; modules use it later (Steward phase 5) |
| `anchor(name, at)` | `entrance`, `spawn`, `cam_*` as in the building kit | required: `entrance`, `spawn` |
| `part(id)` | names a group of ops with a **stable id** | A6 diffs and patches by part; renames are breaking |

Materials come from **roles** (`rock`, `surface`, `subsurface`, `rubble`, `scorched`, `rail`, `structure`, `accent`, ...)
resolved from the style bible (A1). A re-skin changes the bible, not the program.

Determinism: a region is a pure function of `(params, survey, bible roles, seed)`. Variants (A1/A2) re-run it without Claude.

## 3. Checker: macro rules

The checker runs on a **virtual world**: a sparse map of chunk sections built from the survey plus the ops (plus the
child lots' declared footprints, not their interiors). It needs no game. Large regions are checked at **coarse resolution**
(walk graph over surface cells, flood-fill over sampled columns) with a full-resolution pass only around lots, bridges,
stairs and carved edges. Previews: shaded top-down, a section cutaway through the main axis, and a low-detail isometric.

Severity follows Architect's rule: **every new rule starts as a warning** and is promoted after it passes hand-written examples
and a couple of real generations (record the promotion in PLAN.md). The "proposed final" column is what I would promote to.
A separate class, **engine invariants**, are enforced at write time by the mod regardless of the checker: nothing outside the
claim, natural blocks only, no block entities removed, everything through the snapshot path, block budget. The checker reports
them too, but the engine does not trust the checker for them.

| # | Rule | Start | Proposed final | Check |
|---|---|---|---|---|
| M1 | **Claim containment** | error (invariant) | error | every write, lot and path inside the claim |
| M2 | **Reachability** | warning | error | a walk graph (standable cell, step <= 1, headroom 2, fall <= 3, ladders/stairs/bridges) reaches every lot entrance and every named district from `entrance`; unreachable lots listed |
| M3 | **Support / floating** | warning | error | every non-air, non-attachable block connects to ground through face adjacency; `add(..., underside)` masses are connected to their supports; gravity blocks supported; a region may declare `floating: [part ids]` for sky districts (then M3 only requires them to connect to each other and to a declared anchor) |
| M4 | **Fluid containment** | warning | error | static flood fill from every fluid source touched by a `carve`, and from any fluid left exposed, into walkable and lot cells; flags breaches, new unintended lakes, lava within 3 of a walkway without a barrier |
| M5 | **Spawn safety** | warning | warning | count of cells with block light 0, solid below, 2 headroom on walkable and lot surfaces and inside carved voids; ratio and absolute thresholds; carved caverns must declare lighting |
| M6 | **Path clearance** | warning | error | roads, stairs and bridges keep the declared width and 2 headroom along their length |
| M7 | **Slope and rise** | warning | warning | road grade and stair rise within limits (rise <= 1 per step, landing every N) |
| M8 | **Edge protection** | warning | warning | walkable edges with a drop > 3 have rails or walls unless the theme opts out (`edges: 'open'`) |
| M9 | **Lot pads** | warning | error | each lot has a flat pad within tolerance, foundation fill resolves, no overlap with margin, `max` inside the child design cap |
| M10 | **Bridge supports** | warning | warning | span between supports <= limit; deck is not the only load path for gravity blocks; does not dam a water flow |
| M11 | **Terrain-op sanity** | warning | warning | removed and added block counts within the budget shown on the ghost; no op reaches below the world bottom margin; carve does not breach into unexpected caves (reported, not forbidden) |
| M12 | **Budget** | warning | warning | block count, estimated placement ticks, and (survival) the bill of materials estimate versus the player's stockpile |
| M13 | **Palette validity** | error (inherited) | error | roles resolve to vanilla blocks in `blocks.mjs` |
| M14 | **Parts** | warning | error | part ids unique and stable; every op belongs to a part |

Open building types (R4): a region program may declare the rule menu it wants (`rules: ['reachability','fluids','spawn']`);
M1, M13 and M14 always apply.

## 4. Needs from A5a / A7 (journal-backed sites and site groups)

Architect chose to back sites with its **change-set journal** (a port of AgentCraft's WorldJournal: per-position cell stacks
ordered by layer, BOX and CELL policies, ownership hand-down so overlapping entries undo in any order, crash safety). Nested
sites are therefore not needed: overlap is legal and undo is ordered. This section states what a region needs from it.

**Site shape.** A realised region is a **site group** (A7) with an owner tag (R5, the settlement id) containing ordered change-sets:

1. `terrain`: the terrain ops. **CELL policy** (restore only while the world still holds what we wrote; the player's later blocks
   are kept and reported).
2. `road` and `bridge` sites. **CELL policy.**
3. one `lot` site per lot: the child building, ordinary placement, **BOX policy** (exact restore, as today).

**Requirements:**

- **N1 Ordered layers.** Lots sit above the terrain change-set. Removing a lot restores the terrain layer's state exactly; removing
  the terrain with lots still present is allowed and does not resurrect or delete the lots' blocks (ownership hand-down). Offer a
  cascade, never silently.
- **N2 Not "player blocks".** Blocks written by a group's change-sets are in the journal, so they never count as player blocks.
  Occupancy and TerrainFit treat them as replaceable inside a child's lot. The player's own blocks inside a lot still refuse placement.
- **N3 Pad reservation.** The terrain change-set flattens each lot to its pad and records the lot rectangle. A child building fits its
  pad; moving a child is allowed within its pad.
- **N4 Order and partial failure.** A group places terrain, then roads/bridges, then lots, through the batch queue (persistent,
  waits for chunks, "waits until clear"). One child failing does not undo the group; the group reports `partial`.
- **N5 Undo group, conflict-aware.** One undo for the whole group in reverse order. "Still ours" ignores a short allowlist of
  volatile properties (doors open, levers powered, furnaces lit, leaf distance); a real change since is reported and kept, never
  overwritten. A container the player filled still refuses removal, as today.
- **N6 Guard cells are part of the change-set.** The row under a foundation, held leaves and similar cells are recorded so Remove
  stays exact.
- **N7 Delta (A6).** A patch re-runs the program, diffs by part id, and writes the new cells as a new change-set layer whose "before"
  includes the earlier layers. Remove stays exact.
- **N8 Registry.** The site registry (A8/R7) exposes the group, its change-sets, owner and state (`planned`, `placing`, `placed`,
  `partial`, `failed`, `removing`) with events.
- **N9 Cell-list streaming.** The realise step hands the mod cell lists per chunk section (positions, block state, optional block
  entity data). The mod needs no knowledge of the shape math.

## 5. Interaction with the other asks

- **A1/A2:** the region job is a group parent. It generates the plan, then spawns child building jobs for the lots, with the shared
  bible and the lot `brief` as input, in parallel.
- **A3 massing:** a region massing pass is the ops plus the lot footprints as boxes. No interiors, no child detail. The approved
  massing fixes the plan; the detail pass fills the lots.
- **A4 critique:** the reviewer looks at the top-down, section and isometric previews plus the checker output.
- **R3 named parts:** parts are the unit of patching. The component library (lantern posts, rail styles, bridge trims) is shared with
  the building kit.

## 6. Test plan and gate

Hand-written fixtures first, one per site family, each with the expected checker report (which rules fire and why):
`crater_works` (carve, terrace, stair, lots), `sky_isle` (floating add with pillars/taper, bridges), `rift_city` (carve, cavern, bridges,
fluid containment), `walled_hill` (ring, terrace, gate, roads), plus the `mega_bench` scale fixture (section 6a). Add deliberately broken variants (a lot with no path, a carve that opens
a lake, a floating spur) and check that M2, M4 and M3 catch them.

**Gate (proposal):** from a fresh dev world, a region generated by Claude from "a crater mining facility" passes the checker with no M1,
M2, M3 or M4 findings, realises through the batch queue, is placed with its child buildings, and one undo returns the whole area
cell for cell (Architect's exactness bar), including a deliberate player block placed in a lot beforehand, which must be reported and kept.

## 6a. Scale: benchmark fixture and staged builds

Size goals are targets to measure, not promises. Rough classes, by confidence: up to about 300x300 (sky city, crater, castle):
realistic; about 500-1000 across: plausible after measuring; beyond that: experimental (staged builds across sessions).

**Benchmark fixture `mega_bench`.** A synthetic region (1000x1000 claim; a mix of one big carve, a ring wall, terraces, bridges and ~200
lots with stub children) with no Claude call, run in a dev world. Measure and record in PLAN.md:
- cells per second through the journal, at several per-tick time budgets (including lighting and neighbour-update cost);
- undo time for the whole group and for one lot;
- peak memory and journal size on disk;
- sidecar JS evaluation time per chunk section and cell-list size, and how it is chunked over the WebSocket;
- checker time at coarse resolution and the full-resolution passes;
- behaviour with unloaded chunks (queue waits, resumes as the player moves, survives relog).
The fixture is also the regression test for any later change to the batch queue or the journal.

**Staged build mode.** A region is built **district by district over time**, visibly, instead of in one run:
- the plan declares stages (`stage: 'ramp' | 'floor' | 'lots-1' ...`) as ordered groups of parts; each stage is its own change-set group;
- stages place when their chunks are loaded and the player is near (the persistent queue), or are scheduled by the steward over days;
- the group state exposes per-stage progress (`planned`, `placing`, `placed`, `partial`), and the HUD/inbox show "stage 3 of 9";
- a stage can be approved, reordered or skipped before it runs, and undone on its own;
- a half-built region is always valid: earlier stages never depend on later ones, and the checker runs on every prefix of stages
  (M2 reachability from `entrance` holds after each stage, not only at the end);
- repetition: districts built from a few designed module types use shared cell lists (the same module placed at many lots), so cost and
  evaluation time scale with the number of module types, not the number of lots.

**Measured placement throughput** (Architect 4d gate, 2026-10-05; instant ticked placement, a 12-lot village of about 26k cells, player present, loaded chunks):
1 ms per-tick budget about 10-12k cells/s (2.2-2.6 s wall); 4 ms (the default) about 19-20k cells/s (1.3-1.45 s; max MSPT 34 ms, no tick over 50 ms);
10 ms about 34-35k cells/s (0.8 s). Ballpark for `mega_bench` planning: a million-cell site is about 50 s at the default budget, 10 million about 8 minutes,
before lighting and chunk loading effects, which this run did not stress (see the unmeasured items in section 8).

**Measured with Architect's journal (phase 4e gate, 2026-10-06).** Journal size: a 256x256 flattened pad 0.04 bytes/cell, a synthetic 760k-cell village-scale layout
(40 lots, 8 roads) 0.39 bytes/cell. `Sites.stack()` at depth 4: p50 1.1-1.2 microseconds, p99 1.5-2.8. A 12-lot village plus roads at the 4 ms budget: 15-16.6k cells/s,
max MSPT 13 ms (the journal costs about a fifth of 4d's 20k cells/s). Mega-lite (760k cells) stays under 50 ms per tick at every budget (max 27-46 ms); its group undo took
10.6 s. Recorded, not gated: a 1000x1000 run placed 11.6M cells in 34 minutes at 4 ms (about 5.7k cells/s with unloaded chunks and worldgen), 11 of 610 lots timed out
NOT_LOADED under LOAD_BOUNDED(64), and ticks reached 237 ms while the server generated unexplored terrain. So the megastructure answer is: storage is solved,
**terrain generation and chunk loading** now dominate, which the staged-build mode (build near the player, over time) is the answer to. Architect will chase both in phase 6.

**Measured by Architect's phase 6a gate (v0.11.0, 2026-10-09), mega_bench A: 1000x1000, 9.56M cells, prepared terrain.** Realise 54.6k cells/s; MSPT max 28 ms (p99 13 ms); 0 chunks generated during
realise; prepare 2910 chunks in 147 s; journal 0.20 bytes/cell; heap about 0.7 GB above the game; group undo 135-141 s (max tick 29 ms); an exact-restore check over 258M cells. Staged run (LOADED_ONLY, scripted walk): 0 failed
items, resume 1 s after a relog and 2 s after a sidecar kill; chunks loaded per stage ground 47,449, ways 5,710, lots 5,344 / 6,935 / 7,534 / 1,068; engine time ground 68.7 s, other stages 0.15-2.5 s (the harness's per-stage
wall time is a 30 s dwell per waypoint, not engine speed). So a megastructure at this scale is now a few minutes of engine time plus the player's walk, and prepare is a one-off 2.5 minutes. The earlier lot timeouts were
shared chunk tickets being dropped (now reference-counted). Journal format 2: a 0.10.x client refuses a world 0.11.0 wrote.

## 7. Resolved with Architect (2026-10-05)

1. **Survey:** Architect owns the format and the mod-side sampler, generic and exposed by the API. Heights at 1-block resolution
   (4-block past about 256x256), ocean-floor heights, biome per 4x4 and a water mask. Steward consumes it.
2. **Evaluation:** one implementation. The shape math is in the JS kit; the sidecar evaluates per chunk section against a fresh
   survey and returns cell lists; the mod writes through the journal. No Java copy (it would drift).
3. **Y:** both; surface-relative is the default, absolute is allowed (sky cities) and the checker warns near the build limits.
4. **Landmark lots:** may be region programs. With sparse journal change-sets, 96x64x96 caps only the single-template format.
5. **Rule promotion:** the usual process, warnings first, promoted on real generations, recorded in architect-mc PLAN.md.

## 8. Remaining open points
- Which "volatile properties" the CELL-policy comparison ignores (Architect's list; Steward may need more, such as crop age).
- Cell-list size for a large section and how it is chunked over the sidecar WebSocket.
- Throughput above is for a village on loaded chunks; terrain operators, unloaded chunks and survival construction sites are not yet measured.
- Whether realise-time evaluation per section is fast enough in JS at crater scale; measure in `crater_works` first, then `mega_bench` (6a).
- Per-stage checking: confirm the checker can run incrementally on stage prefixes without re-walking the whole region.
