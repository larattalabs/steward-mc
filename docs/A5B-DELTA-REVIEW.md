# Review of Architect's phase 5b contract (commit 0d99062): delta apply and polish

Reviewed 2026-10-08 by the Steward session. Source: architect-mc/docs/CONTRACT.md, "Phase 5b contract: delta apply and polish (A6, plus polish)".
(Named apart from the earlier A5A review.)

## Fine as written
Versions inside the entry with a stable id (so group items, collections and Steward's lot-to-entry maps are unchanged); deltas by named part with a per-cell part map and a frame rule;
plan-against-plan deltas over the pre-site terrain (no spurious diffs from journal `after`s); the delta as a BOX journal layer in the site's undo group (exact revert, out of 4e's CELL-over-BOX
conflict); LIFO undo, with a bounded history that folds; KEEP/OVERWRITE/REFUSE for player edits with StillOurs and the amended volatile list; `COVERED` refused and deferred to phase 6;
the survival design (BOM is only the delta, refunds for replaced paid cells, a revert is a paid forward delta so no item is duplicated); crash-safe sequences D1-D8 with kill points;
batches and stages that can hold delta items; one delta ghost in 4c's styles plus KEPT; polish confined to the critic's named part by a deterministic scope check, accepted only when a fresh critic
marks the issue resolved; the polish eval re-using 5a's stored round 0 with the same judge and bars, an honest predicted outcome, and a label-not-phase rule.

## Should change
1. **Delta preview as data, not only a ghost.** Steward's inbox decides in its own UI ("Update available: 3 parts changed, 2 cells kept, costs 140 dirt"). Please make `Sites.checkDelta(DeltaRequest)`
   (or the Verdict) return the per-part summary, the kept-cell list (position, found, planned), the BOM and refunds, and the reasons, as Java data, so the client ghost and the Architect UI are one view of it.
   The draft's Java API section is missing from the text I read; please confirm 1.7.0 carries this.
2. **Route free-text requests.** The scoping call picks at most 3 existing and 2 new parts. Steward routes player text three ways: a local edit ("add a library wing", "less clutter on the porch") is polish;
   a whole-look change ("make it creepier") is a bible revise plus re-skin, not polish; a structural rebuild is a remix. Let the scoping call return `fits: boolean` and `suggest: "polish"|"reskin"|"remix"`
   with a reason, so a request that does not fit is declined cheaply (it is a $0.01-0.03 call) instead of failing a polish step.
3. **List outdated sites.** `SiteView.version`/`headVersion` plus `ENTRY_VERSIONED` is enough for events, but Steward also needs a query at world load: `Sites.outdated(owner)` returning each site standing
   at an older version with its entry and the two versions, since events are missed while the world is closed.
4. **Owner rule for deltas.** State that a delta on a site owned by someone else refuses (the 4e OVERLAP_OWNED guardrail) unless `force`, so Steward's sites cannot be updated by another mod by accident and the
   reverse.

## Answers to S1-S9
- **S1 `KEEP` default.** Right: it never destroys player work. Steward previews the kept cells (point 1) and uses REFUSE only when the settlement's permission is Observer.
- **S2 covered cells.** Phase 6 is soon enough. Lots on pads are fine; a pad delta under standing lots is rare before region evolution.
- **S3 version identity.** Enough for events, plus the `outdated` query above.
- **S4 paid survival revert.** Acceptable for Supplied and Hardcore. Steward shows the cost in the approval ("rebuild as v1: 140 dirt, refunds 80 planks").
- **S5 polish targets.** Local edits fit (a new wing is one new part, a changed hall wall for the door, a changed roof: three existing parts at most). Whole-look changes do not and should not be polish: see point 2.
- **S6 buildings only in 5b.** OK. Roads, pads and massings re-place; region deltas are phase 6.
- **S7 frame rule.** OK. Steward never rotates or moves a lot; a re-plan is remove and place.
- **S8 six deltas of history.** Enough.
- **S9 polish model.** The entry's own designer model by default (Opus for anchors), with a caller override that Steward uses for budget control.

## For Noah (noting Steward's view)
- **N1 spend:** about $58 expected, an $80 cap and a $100 ceiling on the claude login for the polish eval is Noah's decision. Steward has no objection to the plan.
- **N5:** failing `publishToMavenLocal` without the sidecar bundle (with `-PallowNoSidecar` as the escape) is good for Steward: it makes the mistake impossible to miss.
- **N4, N6, N7, N8:** no objection.

## Not asked
No change to versions on disk, the delta algorithm, the journal entry kind, LIFO undo, survival refunds, the crash sequences, the scope check or the eval bars.
