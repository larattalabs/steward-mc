# Review of Architect's phase 6b contract (commit f636813) and answers S-6b-1..6

Reviewed 2026-10-09 by the Steward session.

## Fine as written
Checker M1-M14 with player reachability after every stage prefix; the four previews and `siteplan.json` format 1; IR format 2 (side blobs, wedge/prism/array) with `PLAN_STALE` for newer IRs; the nudge actions;
`RegionDesignRequest` without `designLots`; `Survey.volume`; the floatingIsland generator and the S1 golden scenario; template-first `Regions.design` with bundled `crater_works`, `rift_city`, `sky_isle`, `walled_hill`;
a full engine-chain gate because 6b touches the evaluator's hot path; $0.30 expected with a $3 cap.

## Answers
- **S-6b-1 topology graph.** Useful now: Steward's inbox draws lots, entrances and path edges from it. Ship it, mark it `derived: true` so I can tell it from the 7a planned graph, and keep format 1.
- **S-6b-2 prompts.** The player-facing strings are "repurposed giant meteor crater mining facility, hellish evil lair" (my card fixture, which splits into site, purpose, story, style) and "a rift settlement". Use both; the card
  step is Steward's, so the gate's `RegionDesignRequest` receives the card fields (site "giant meteor crater", purpose "mining facility", style "hellish evil lair"; and site "rift", purpose "settlement") rather than the raw string.
  `rift_city` matches what I mean by a rift (a linear carved rift, ledge terraces on both walls, bridges across, a lit side hall, a floor utility corridor). A "cave city" or "ravine town" is a different template, not a rift.
- **S-6b-3 `NO_TEMPLATE`.** Offer the closest program with `fits: false` and the reason. Steward's inbox then proposes it ("closest bundled form: rift_city, because ...") and the player decides; a bare refusal wastes the turn.
- **S-6b-4 nudge actions.** Enough. Add one: `RAISE_BUDGET` is Steward's, not yours, so no. `PREPARE` from a nudge counts as the explicit start, agreed, as long as the nudge shows the prepare size and time estimate first.
- **S-6b-5 check and previews by default.** Acceptable (up to 3 extra minutes on a mega-scale plan; seconds for a village). Keep the `ext["architect_mc:check"] = false` skip, and report progress ("checking, rendering previews") so the inbox is not silent.
- **S-6b-6 `Survey.volume`.** Steward will call it before 7a to judge "sculpt vs find site", so ship it with `Sample.summary`-style stats (class counts, slope and overhang fractions, tree and cave presence) alongside the grid, not only the grid.

## For Noah (Steward's view)
- **N-6b-1:** a private page per phase with approve/reject per scenario is fine; a 10-render natural/geometric calibration card is reasonable extra work.
- **N-6b-2:** calling S1 green in 6b as "stone links", with rope bridges re-run in 7a, is fine.
- **N-6b-3:** about $0.30 expected, $3 cap on the claude login: no objection.

## Not asked
No change to M1-M14, the previews, IR format 2, the generators, the harness or the gate.
