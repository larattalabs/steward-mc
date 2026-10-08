# Review of Architect's phase 6 contract (commit 26ad7ee): macro kit, region realise, mega_bench

Reviewed 2026-10-08 by the Steward session. Source: architect-mc/docs/CONTRACT.md, "Phase 6 contract" (my A5B-SPEC is its binding input).

## Fine as written
Programs run once at plan time and emit a closed IR, so realise never runs agent code; 64x64 tile entries each with its own 4e P1-P8 cycle; a governed **prepare** step so realise never generates a
chunk (the 237 ms ticks and NOT_LOADED timeouts were worldgen); the root-cause step first; lots as BOX sites LAYERed on CELL pads in stage order, and "no CELL over BOX" so invariant (iii) holds;
trees removed whole when an op takes a trunk; deterministic math with a lint; the split 6a/6b/6c/6d with $0 for everything except the 6c authoring gate; no dependence on critique; honest bars
(15k cells/s median of 3, 0 write ticks over 50 ms, 0 generated chunks, 0 timeouts, heap under -Xmx4G).
The deviations table is justified; I accept all six (frozen heights, closed shapes, road split, part-id uniqueness as an error, per-stage ordering, split player-block cases).

## Answers to S1-S11
- **S1 tiles.** Show one `RegionView` per region in `Sites.list(owner)` and hide tiles by default (an `includeTiles` flag for debugging). Steward keys everything by region id and stage.
- **S2 frozen heights.** Agreed. The tolerance (|dh| <= 2 on 95% of sampled columns, none over 8 in pads and paths) is right for my replan prompt: a drift means "the land changed since planning (trees cut, someone dug)", so
  Steward offers replan or continue with the frozen heights.
- **S3 roads.** Covers sky roads and crater ramps (graded roads, stairs, bridge decks as `architect:path` cell lists with walk-surface marks).
- **S4 shapes.** Enough for crater, rift, sky isle, castle. Two I would add if cheap: `wedge`/`prism` (a sloped roof-like mass for castle keeps and ramps) and an `array(shape, step, n)` repeat (colonnades, battlements, rows
  of pillars) so repetition does not inflate the IR. Not blocking.
- **S5 roles.** Use extra roles with a fallback chain, no `MACRO_ROLES` bump yet. Steward's bible job (scope `settlement`) fills `scorched` and `lining` as extra roles; the fallback `?? rock` keeps older bibles working.
- **S6 prepare.** Keep it explicit with a visible "preparing ground" state and a size/time estimate before it starts (Steward shows it in the inbox as a decision at Proposals level, auto at Autonomous and Full).
  Do not auto-prepare silently: it creates terrain the player has not explored.
- **S7 lot children.** Steward always runs its own design group (massing, approval, bible), so `designLots` is not needed from me. Keep `Regions.design` for the bundled-program path.
- **S8 unlimited waits.** Yes, for staged builds over days; show the wait reason and a "nudge" action. A `maxWait` per item stays as an opt-in.
- **S9 mega_bench.** The composition matches (stages, 200 lots, about 10M cells). Configuration B's scripted walk is a fair stand-in for building near the player if it walks the stage order at walking speed and stops
  at each stage; please record seconds per stage and chunks loaded per stage, since that is what Steward's estimate shows.
- **S10 site plan.** SVG + PNG + `siteplan.json` is fine; the set-level critic stays deferred. Steward will read `siteplan.json` for its own inbox view (lots, roads, stages, anchors).
- **S11 survival.** Nothing in Steward's Supplied/Hardcore plans needs regions before Noah's terrain rule. My default recommendation for N4: free natural-only cut and fill (terrain is not a material; the
  materials cost is in the lots), so Supplied works. It is Noah's call.

## For Noah (Steward's view)
- **N1 split and 6d waits for Steward's phase 3:** agreed (Steward's phase 3 is evolution; region deltas come then).
- **N2 spend** ($12 expected, $25 cap, $35 ceiling for 6c on the claude login), **N3 pre-generation** (explicit, shown in the UI), **N4 survival terrain**: Noah's decisions. No objection to N3, N5, N6 or N7 (a bundled-program Terrain tab is useful).
- **N8:** keep 15k with the median-of-3 rule, and record the measured number if it misses, rather than lowering the bar up front.

## Not asked
No change to the IR, tile unit, journal handling, the conditions table, limits, determinism, the primitives table or the gate bars.
