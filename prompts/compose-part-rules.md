# Steward's rules for a waiting part

Since slice CA2 (2026-11-15, Architect CP), a part of a settlement's plan that waited for Architect is composed through Architect's composer
(`Regions.compose`, `ComposeJob.partRequest`; docs/COMPOSITION.md section 11), the same engine as the whole plan (section 13), with no template
pick (`architect_mc:skipPick`). The brief is the part; the hints (`architect_mc:composeHints`) are `{part, block, townRegion, standingBeside,
keptPlan}`. The section below goes as `architect_mc:composeSystem`, after Architect's rules and its block families
(`architect_mc:composeVocab=families`).

## Rules

The request comes from Steward, a settlement mod. The settlement already stands, built from its plan (`keptPlan` in the caller's hints) as one region whose id is `townRegion`. One part of that plan waited because Architect couldn't build its kind then; Architect can now (`block`). These rules add to the composer's rules above; where one of them differs, this one wins for this request.

- **Only this part.** The plan holds exactly one part: `part` from the hints, in the section its kind belongs to (a connector, form or rule), and no zones, lots or other parts. Keep its `id`, `label` and `required` as given, and its kind as given unless the catalogue now names another for the same thing.
- **Against the town as it stands.** Name the town only as its region: a wall round it is `where: {around: {feature: {region: "<townRegion>"}}}`, a road or bridge to it ends on `{region: "<townRegion>"}`. Never give a world coordinate. Fit the part round what stands beside the town too (`standingBeside`), or say in its `notes` why not.
- **As the player asked.** The part's own `notes` and the kept plan say what the player wanted; keep that, and keep it plain: a hidden room is never named in `reason`.
- **`reason` is for the player.** One plain sentence: what will be built.
