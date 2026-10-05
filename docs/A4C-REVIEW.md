# Review of Architect's phase 4c contract (commit eccdd1e)

Reviewed 2026-10-05 by the Steward session. Source: architect-mc/docs/CONTRACT.md, "Phase 4c contract: massing pass (A3) and composite preview".

## Fine as written
Massings as cheap Sonnet designs in the bible's flat roles with named parts that carry over to the detail pass; the binding detail pass with a conformance
check that starts as a warning; `massing.redirect` as a new version; `massingFirst` groups going through `awaiting_approval` with per-item approve/redirect;
estimates that include the massing pass; the composite preview with layer styles (GHOST, MASSING, ADDED, REMOVED, CHANGED), `onlyCells`, several keys at once
(exactly what Steward's settlement-wide view and the 5b delta ghosts need); the gate, including a looked-at composite screenshot.

## Must change
1. **Who owns the approval UI.** The draft puts Approve / Redirect bars in Architect's Design tab and Set dialog. For a Steward-owned group the player should
   decide in Steward's inbox, not be asked twice. Add `GroupRequest.approvalUi: "architect" | "owner"` (default architect). With "owner", Architect shows no
   approval bar for that group, only emits `GROUP_AWAITING_APPROVAL` (with the owner), and accepts `approveGroup` from the owner.
2. **Massing records need owner, ext and a lifecycle.** Massings live in `massings/` and "don't appear in the library list", so Steward cannot find or clean them.
   Add a `Massing` record (id, version, itemKey/ext, owner, group, bible pin, parts, size, cost) with `Designs.listMassings(owner)`, `massing(id)` and
   `deleteMassing(id)`, ext round trip like designs (the lot key survives a sidecar restart), and say when massings are garbage-collected (e.g. with their group).
3. **The cap binds the detail pass too.** Conformance allows the detail size within +-2 of the massing. A lot has a hard `maxSize` from the layout. State that the
   request's `maxSize` always wins: detail size <= min(massing + 2, maxSize), and the massing job itself must stay inside `maxSize`.

## Should change
4. **Group-level context.** Every item brief should include a shared `GroupRequest.context` text/JSON (concept card summary, settlement site and purpose, and the
   neighbours' lot rectangles and street side) so massings coordinate silhouettes. Per-item `notes` work but repeat the same text N times.
5. **Cost and rounds.** Massings and redirects count in the group's cost aggregate and soft/hard budget. Cap redirect rounds per item (default 3, configurable)
   and report `rounds` on the item so the UI can say "redirect 2 of 3".
6. **Composite preview limits.** Say what happens at scale: a 20-massing settlement is many layers. State a cell limit per key and a fallback (box outlines for far
   or oversize layers), and that composites are cleared on world leave. Document the client-only nature (Steward sends the layers from server logic over its own
   packet).
7. **Auto-approve is Steward's to call.** No change needed: at AUTONOMOUS or FULL permission Steward calls `approveGroup` as soon as it sees the event.

## Not asked
No change to the massing kit API, the profile, redirect semantics, the event names or the Java 1.3.0 surface beyond the additions above.
