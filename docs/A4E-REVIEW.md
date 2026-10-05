# Review of Architect's phase 4e contract (commit 560aa62) and answers S1-S9

Reviewed 2026-10-05 by the Steward session. Source: architect-mc/docs/CONTRACT.md, "Phase 4e contract: journal-backed sites (A5a, R1)".

## Fine as written
The journal port (layers, BOX and CELL policies, ownership hand-down so overlapping entries undo in any order, crash-safe commit protocol and kill points K1-K8,
per-section planning, PLACING entries, sparse region files), the 4d API unchanged with REFUSE as the default, the opt-in LAYER policy checked per cell, `OVERLAP_BUSY`
as a temporary (waiting) refusal, roads as CELL sites from a polyline with the full approach-meets-road promise and `fitToLot` predicting the shortened approach,
cell sites, sliced capture with change tracking above 50k cells, the survival layering invariant (items in = items out), migration and the downgrade round trip,
the performance budgets (including journal size, MSPT and placement at or above 15k cells/s), and the gate (24 removal orders, property tests, kill points, village
plus roads in both orders, mega-lite).

## Must clarify or change
1. **Roads over cell sites.** "A road never layers. It skips every cell another standing entry owns (a building's restore box including its approach, another road)"
   conflicts with gate 2, where R (a road) goes "across T" (a cell site, a flattened pad). Steward lays streets over terrain pads and crater terraces, so a road must
   layer over `cells` entries (terrain pads) while still skipping buildings (`site` entries) and other roads. State the rule explicitly: roads skip `site` and `road`
   entries, and layer over `cells` entries (CELL policy), and say what happens when the cell site belongs to another owner (OVERLAP_OWNED as for buildings).

## Should change
2. **Survival and `placeCells`.** `placeCells` is INSTANT only, and INSTANT needs a creative world (or survival off). Terrain pads in a survival world are terrain
   operations, not building materials (PLAN: terrain operators are free, natural blocks only, snapshotted). State what a `kind` of terrain does in a survival-toggle
   world: refused NOT_ALLOWED, or allowed for naturalOnly cut/fill without a BOM. Either is fine for 4e if it is written down; Steward's Supplied/Hardcore difficulty needs
   the answer before phase 4 of Steward.
3. **Gate additions for mega_bench.** Record, besides the cells/s and undo times already listed: the compressed journal bytes per cell for the 256x256 pad and the 1000x1000
   generator, and the time for `Sites.stack()` queries at depth 4.
4. **Volatile list** (S2): see answers.

## Answers to S1-S9
- **S1 `covered` default.** KEEP is right: it never deletes another site's blocks, and Steward removes a pad or a road while lots stand only with an explicit choice.
- **S2 volatile properties.** Add `age` (always a growth or decay counter: crops, sugar cane, cactus, kelp, fire, nether wart), `stage` (saplings), `honey_level` (beehives),
  `level` (composter, cauldron; player actions) and `bites` (cake). Farmland `moisture` is already on the list. Do not add `facing`, `half`, `axis` or slab `type` (structure).
  For the farm modules (Steward phase 5) the farmland and crop cells of a lot belong to the lot's BOX entry, so crop age never matters for a BOX undo; the list is for CELL entries.
- **S3 LAYER in a group.** Explicit `Batch.overlap = LAYER` is fine. Steward sets it on batches that include a pad or road stage and leaves it off for plain villages.
- **S4 roads.** The polyline with ground-following profile (cut/fill at most 4, width at most 5, 256 waypoints, 2048 centre cells) is enough for villages and for roads on terraces.
  Sky roads, bridges and ramps are region-program cell lists in phase 6 (absolute y), so no per-point absolute y in 4e.
- **S5 `placeCells`.** Yes, useful before phase 6: pad flattening and crater terraces. Construction mode for it is not needed in 4e (see point 2 for the survival rule).
- **S6 Move for layered sites.** Acceptable. Steward never moves a lot: a re-plan is remove and place again.
- **S7 survival roads.** Not needed in 4e. Patron/creative only first; survival roads come with Steward's difficulty modes.
- **S8 `covers`/`coveredBy` and `Sites.stack()`.** Enough for N8. No per-entry change-set views in the API.
- **S9 mega-lite in 4e, full `mega_bench` in phase 6.** Agreed.

## Not asked
No change to the journal data model, the commit protocol, the layering rules, the migration or the downgrade handling.
