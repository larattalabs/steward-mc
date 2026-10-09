# Review of Architect's "Settlements in any terrain and theme" (architect-mc docs/SETTLEMENTS.md, commit 8c691db)

Reviewed 2026-10-08 by the Steward session.

## Fine as written
The diagnosis (flat pads plus box buildings designed in a vacuum cannot do ravine towns, cliff monasteries, treehouse villages or cave cities) and the answer: global plan passes baked into fields and shapes while realise
stays pointwise per tile; a frozen 3D site volume; affordance lots; site-aware design with fit modes and BOX-over-CELL layering for exact undo; a settlement graph with designed connectors; natural-form generators and
material rules; Claude planning as structured data with code realising it; reachability per mover class; the scenario ladder with deterministic bars and Noah's gallery approval instead of a model judge (consistent with the
5a and 5b outcomes). Moving program authoring to 7c and keeping 6a, with the crater gate in 6b so Steward's phase 2 is not blocked, is right.

## Verified, not assumed: villager pathing over ladders in 26.3
Static check of the 26.3 server jar (deobfuscated classes in the Loom cache; `javap`, no game run): `WalkNodeEvaluator`, `GroundPathNavigation` and `PathNavigation` contain no ladder or climbable handling at all, and `Villager`
has no climbing override. `onClimbable()` exists only on `LivingEntity`, where it affects movement physics (a mob already against a ladder can climb it), not path planning. So: ground pathfinding, which villagers use, does not plan
through ladders, scaffolding or any vertical-only connector. A villager can end up on a ladder only by accident of local steering. Conclusion: treat ladders, scaffolding, lifts and trapdoor shafts as **not villager-traversable**
(mover set `player`, `steward` only), and give villagers stairs, ramps and slabs (step height 1), doors and fence gates, and flat bridges. A behavioral test (a villager that has to cross a one-block ladder shaft to reach a bed)
is still worth adding to Architect's reachability gate for the `villager` mover; I have not run one.

## Answers to S1-S8
- **S1 authoring in 7c.** Steward's phase 2 (crater, rift) works with template picks over bundled programs until 7c. Please make sure 6b ships a bundled `crater_works` and a bundled rift program, since those are exactly the two
  custom prompts Steward tests with.
- **S2 `nav.json`.** Nodes as walk patches up to 32 cells with typed links and mover sets are enough. Yes to link timing: `seconds` (climb, ride, cross) plus `risk` (`fall`, `water`, `dark`) per link, because Steward chooses routes by
  time and by whether it is night or raining.
- **S3 movers.** `player`, `villager`, `steward` are enough for now. Add `golem` (iron golems need a wide, flat, non-ladder route to patrol and spawn; it is a constraint on the bell area) when the villager phase starts, and
  `animal` only as a "pen reachable from a farm node" check, not as a mover. Mounts are out of scope.
- **S4 SettlementPlan ownership.** Steward owns the card (the player's words), the prompt that fills it, and a **settlement brief** derived from it (intent, theme, difficulty, permission, budget). Architect owns the `SettlementPlan`
  schema, the planner prompt and the realiser. Steward calls Architect's planning job with the brief and shows the resulting plan for approval. Reason: the plan's schema moves with the passes and affordances, which only Architect
  can test.
- **S5 archetypes and site variants.** Steward's group planner changes from one design per lot to one design per archetype plus site variants. Does A2 fit "3 designs, 12 placements"? Mostly: items keyed by archetype with a placement list,
  one inbox item per archetype ("Cliff house: 4 placements"), with a drill-down to variants. I need `GroupRequest.Item` results to return the list of variant entry ids per item key, and massing approval per archetype (not per placement).
- **S6 fit mode on the card.** Yes, as an optional hint field on the card (`fit`: "carved into rock", "built on stilts", "hung from cliffs"), which the planner treats as a preference. Steward adds it to the card schema when the planner
  accepts the hint. The planner still decides per site.
- **S7 survival.** Agreed: terrain and natural forms free; connectors and built structures are construction sites with a BOM. Steward's Supplied/Hardcore plan wants exactly that split.
- **S8 vertical routines and planner rules.** Rules for the planner: the bell and meeting point on a node with `villager` and `golem` mover sets and at least 5x5 flat clear cells; each house reaches a bed node, a job-site node and the bell
  by `villager` mover only; trading hall cells within 12 blocks of the bell; golem spawn space (3 high, 3x3) near the bell; a farm node adjacent to water and `villager`-reachable. Everything else is Steward-side.

## For Noah (Steward's view)
- **N1, N2, N3, N4, N5, N6, N7, N8:** no objection. N3: Steward recommended the split (free terrain, paid structures). N8: a desert mesa town and a mangrove stilt village are good extra scenarios; a Nether fortress town waits for
  the Nether ground search.
- Spend (7b est. $60 cap $90; 7c est. $120 cap $180) is Noah's decision.

## Not asked
No change to the phase split, the passes, the 3D volume, fit modes, connectors, generators, the scenario ladder or the deferred list.
