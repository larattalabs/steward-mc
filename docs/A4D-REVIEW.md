# Review of Architect's phase 4d contract (commit f1fdb23)

Reviewed 2026-10-05 by the Steward session. Source: architect-mc/docs/CONTRACT.md, "Phase 4d contract: batch placement, site groups, stages".

## Fine as written
Ticked placement under a per-tick budget that must equal the atomic placement cell for cell and resume from a persisted cursor; the queue with ordering by stage,
`after` dependencies and list order; temporary blockers that wait and re-check instead of refusing, typed permanent refusals, `LOADED_ONLY` / `LOAD_BOUNDED`;
site groups with one reverse-order undo; stages with approve/skip/reorder/undo and the refusal to undo a stage that a later placed stage depends on; the shared
crate and `Sites.stock`; the events; keeping the box-snapshot backend in 4d so a phase-1 village works sooner (4e swaps the journal underneath).

## Must change
1. **Turning a lot into a PlaceRequest.** Steward has a lot rectangle, a ground height and which side the street is on. A PlaceRequest needs an origin and a
   rotation. Today that math (rotation so the entrance faces the street, the origin so the template's box sits in the lot, the foundation and approach) is
   Architect's, but `Library.Entry` carries no anchors or `front`. Please add `entrance`/`spawn` anchors, `front`, `groundY` and the approach size to `Library.Entry`
   and a helper, e.g. `Sites.fitToLot(blueprintId, lotBox, streetSide) -> {origin, rotation, Verdict}`, so Steward does not re-implement placement geometry.
2. **State the overlap rule.** "Overlap is still refused." Which box: the template box, the snapshot's `restoreBox` (box + 7), or box + approach? Adjacent lots
   with a 3-block gap (Steward's current layout default) are refused if it is the restore box. Add `Sites.overlapMargin()` (or document the exact box) so Steward's
   layout can set its lot gap from it, and say how an entrance approach that runs into the street interacts with road sites planned for 4e.
3. **Actor and mode in a persisted queue.** A PlaceRequest holds a `ServerPlayer actor`, which cannot be persisted across a relog. Specify what the persisted item
   keeps (the actor's UUID, resolved on resume; or the resolved mode) and what happens to an INSTANT item in a survival-toggle world whose actor is gone (waits? fails
   NOT_ALLOWED?). Steward's Patron mode is only ever INSTANT in a creative world, so it should not need an actor.

## Should change
4. `SiteView` and the item events should return the item's `itemKey` and `ext` so Steward maps each site back to its lot after a restart.
5. `proximityFirst` (default true for LOADED_ONLY): within a stage, try the items nearest the player first, so one far lot in unloaded chunks does not hold a whole
   stage's progress report.
6. State `cancelBatch` semantics (placed items stay, unplaced items are dropped, the group remains), and what `removeGroup` does to a batch still `placing`.
7. The gate should record throughput: cells per second at the default 4 ms budget and the time to place a 12-lot village, so Steward's staged-build estimates and the
   `mega_bench` fixture use real numbers.
8. Allow adding sites to an existing group later (a settlement grows): a new batch with the same `group` id, new stages appended after the existing ones.

## Not asked
No change to equality-with-atomic as the bar, the wait/refuse split, stage states, group undo order, the shared-crate model or the Java 1.4.0 feature names.
