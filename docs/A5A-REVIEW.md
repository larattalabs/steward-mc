# Review of Architect's phase 5a contract (commit 497ad9c) and answers S1-S9

Reviewed 2026-10-06 by the Steward session. Source: architect-mc/docs/CONTRACT.md, "Phase 5a contract: critique loop and eval harness (A4 + R8)".

## Fine as written
A fresh-query Sonnet critic per round (it cannot defend its earlier verdict) with fixed views, neighbour renders, a blueprint summary and layered ASCII slices; scores tied to named parts;
**the sidecar decides "ship"** (mean >= 7, no score below 5, no P0) and records the model's disagreement; revision turns in the same session with "remove before adding" for clutter;
the best round installs and a failed revision never fails the design; the caps (loop spend <= 1.0x round 0, inside the design and group budgets, no new revision in a `paused_budget` group);
off by default for API callers; a blind Opus pairwise judge, each pair judged twice with the order swapped, with a different model from the critic; the paired design (round 0 vs final from one run);
sim tier in CI, auth and spend guards (login only, never an API key); the sign-test bar with an honest power statement; the clutter work (restraint in bible format 2, sheet critique, hygiene); the migration tests.

## Should change
1. **Estimates must show critique separately.** `Designs.estimate` with a critique spec should return the loop's cost and time as their own lines (basis text is not enough), so Steward's approval step can show
   "$24 with polish, $14 without" and the soft budget can leave critique out first.
2. **Group wall time.** An item keeps its design slot for its whole loop. With concurrency 3 and 12 items a loop adds roughly 4 waves x up to 8 min. State the group estimate's time with critique, and consider letting a
   revision turn re-enter the queue so a waiting item can start while a revised one is in its critic call (it is a short call; the slot is mostly idle).
3. **Surface open issues in a form the inbox can act on.** `Critique.openIssues` (P0/P1/P2 with part and view) at install is what Steward shows ("tavern: 1 open P1, entrance hard to read from the street").
   A follow-up "polish" (critique plus revision of an installed entry) is deferred in 5a; Steward wants it soon, because delta-apply (A6) and "make it less cluttered" are the same operation. Please keep the
   report-mode verdict on the entry (`<entry>/critique.json`) in a shape a later polish can start from.

## Answers to S1-S9
- **S1 default.** Keep `off` for API callers. Steward opts in per item: `loop` for the anchor and other landmarks, `report` (about $0.1) for the rest so the inbox has scores without the revision cost, and `loop` for the rest only
  when the budget has room (soft budget decides). Never `loop` by default for a whole group: it can double the settlement's cost.
- **S2 verdicts as data.** Enough: `Critique` on designs, group items and entries, plus `DESIGN_CRITIQUED` per round. Steward keys everything by the item key and `ext`, which already round-trip.
- **S3 `extraCriteria`.** Useful. Per-lot facts ("the entrance faces the street on the south side", "reads as a mine", "no dark stone") score better as an explicit criterion than buried in group context. Steward will use them for the
  card's `avoid` list and the street side. Three per item is enough.
- **S4 site-plan view.** Not in 5a; phase 6 with the A5b section previews. The `set` score from neighbour renders covers phase 1. A set-level critic (composite of the lots along the street, with their positions) belongs in phase 6;
  Steward can supply the lot rectangles.
- **S5 massing critique with auto-approve.** Yes, in 5a if it is cheap, off by default. Steward opts in only at Autonomous and Full permission, where nobody looks at the massing before detail starts.
- **S6 settlement-flavoured briefs.** Sent: `docs/eval-briefs-v2-candidates.json` (6 briefs from the concept-card fixtures: crater ore hall and foreman's office, stilt fisher house and net shed, sky chapel, volcano barn),
  in the note format a real group item carries. They are individual designs; a settlement-level brief needs the phase-6 set critic.
- **S7 owned copies.** Agreed. Architect owns `kit/PLAYBOOK.md`, `slices.mjs` and the attach and facing rules; changes flow back by message. Keep the header note of origin.
- **S8 `job.images`.** Useful beyond the critic: a concept card from a reference screenshot (the card has a `references` field), a style bible seeded from an image, and a hillshade or top-down of a survey for site selection.
- **S9 model subset.** Directional is enough for now. What Steward needs to decide is Opus for landmarks versus Sonnet for the rest; if the 4-brief subset is ambiguous, a dedicated run (about $30) afterwards is worth it.

## For Noah (from the draft's N-questions, noting Steward's view)
- **N2 gate cap on the claude login ($120, about a day of wall time with limit holds):** this one is Noah's; Steward has no objection to the plan.
- **N3, N5, N6, N7, N8:** no objection (7.0 ship, Sonnet critic and Opus judge, textured renders and HeadlessMC deferred, no force-delete).
- **N4:** committing small eval summaries with brief texts is fine for Steward's side; the settlement briefs above contain no personal data.

## Not asked
No change to the critic's inputs, the verdict schema, the stopping rules, the eval layout, the bar G1-G4 or the Java 1.6.0 surface beyond the above.
