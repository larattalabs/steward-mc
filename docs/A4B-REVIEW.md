# Review of Architect's phase 4b contract (commit b27cd32)

Reviewed 2026-10-05 by the Steward session. Source: architect-mc/docs/CONTRACT.md, "Phase 4b contract: style bibles and design groups".

## Fine as written
Bibles as artifacts separate from entries, versioned, with prose, roles and a component library; roles generalising the palette so a
re-skin is a free variant; named parts with stable ids and the part-count warning; open types with a rule-menu profile; groups with a shared
bible, per-role models, neighbour renders after the first wave, a group-wide usage hold, partial results installed as they finish; collections;
the feature names; the gate (including the no-bible control judged by two critics).

## Must change
1. **Item results must be addressable by Steward's own keys.** Steward needs "lot_3 became entry gen_x". Group items carry `ext`
   (`steward_mc:lot`), which is copied into the entry; please also make `group(id)` and `GROUP_DONE` return `items: [{ext/itemKey, designId, entryId, status, cost}]`
   persisted across restarts, and `list(owner)` for groups and bibles.
2. **Estimate before spending.** The HUD spend meter and the approval step ("about $9, about 25 minutes, 12 buildings") need
   `Designs.estimate(GroupRequest)` and `Bibles.estimate(BibleRequest)` returning cost range and wall time from Architect's measured per-model averages,
   concurrency and the usage-limit state. Without it Steward would hard-code guesses.
3. **Soft budget instead of only a hard cancel.** A hard cap that cancels queued items is right as the last line, but Steward wants a soft
   threshold: stop dispatching new items at a configured fraction (default 80%), hold the group, emit `GROUP_UPDATED` with a reason, and let the player
   raise the budget and continue. Add `extendGroup(id, budgetUsd)` and `resumeGroup(id)`, and the status `paused_budget`.
4. **Anchor-first ordering.** Neighbour renders are only given "after the first wave", but which items form the first wave decides what everything
   else matches. Add an item flag `anchor: true` (or `wave: n`): anchors design first, alone or together, and the rest get their renders. A village
   wants its landmark (tavern, hall) designed first.
5. **Roles are an open set, with a macro superset.** The 11 building roles are fine. Region programs (A5b) read `rock`, `surface`,
   `subsurface`, `rubble`, `rail`, `structure` and settlement-specific ones from the same bible. Let `roles` hold extra named roles (validated as vanilla
   blocks) and have the bible job fill a documented macro set when asked (`BibleRequest.scope: "settlement"`), so one bible serves buildings and terrain.
6. **Batch re-skin and the sheet.** Steward's "make it more ominous" is `bible.revise` followed by a re-skin of the whole collection.
   Add `Library.reskinCollection(bibleId, version)` (or a group-level variant request) with one future and a `RESKIN_DONE` event listing the new entries,
   and put the sheet path in the Java `Bible` record (`sheetPath`, `proseText`, `roles`, `version`) so the client can show the sheet for approval.

## Should change
7. A `BibleRequest.seedPreset` (a built-in bible or palette preset) so Steward's style templates (medieval, infernal, ...) start from something concrete
   and cost less.
8. A group `status` that distinguishes `held_usage` (until a time) from `paused_budget` and `cancelled`, as above.
9. State in the contract how many items one group may hold (Steward plans 8 to 20) and how the sidecar-wide `designConcurrency` interacts with several
   groups at once (a settlement may have a bible job, a group and a re-skin at the same time).
10. Gate additions: an item `ext` round trip (lot key to entry id after a sidecar restart), `estimate` within a stated tolerance of the measured cost,
    and a soft budget pause and resume.

## Not asked
No change to the bible schema fields, the component set, named-part rules, the open-type menu or the Java 1.2.0 feature names.
