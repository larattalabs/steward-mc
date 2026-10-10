# Asks for Architect (from Steward)

**Status 2026-10-05: all accepted** (architect-mc commit a040e39, "After phase 3: asks from Steward"). Architect's order: A9 (in progress), A8, A1+A2, A3, A7, A4, A6, A5. A8 contract draft comes to Steward for review before it is built.


Steward (sibling mod, see PLAN.md) is generative-first and depends on Architect for generation and placement. These
are the Architect-side changes it needs. They land in `architect-mc` first, through the Architect session, and each
should be useful on its own for Architect's players. Order is by what Steward phase 1 needs.

| # | Ask | Why | Notes |
|---|---|---|---|
| A1 | **Style bible**: a first-class artifact (JSON + prose) generated once from a prompt and fed to every design job | Many separately generated buildings must read as one place | Versioned; separate artifact at `architect/bibles/<id>.json`, referenced by library entries; cacheable prompt prefix |
| A2 | **Hierarchical / parallel jobs**: a parent job spawns N building jobs, shared bible, per-job model tier (Opus landmark, Sonnet ordinary), aggregate progress and cost | Wall time near one design for 8-20 buildings | Rate-limit aware; partial results usable |
| A3 | **Massing pass**: a cheap coarse volume design shown as a ghost before the detail pass; "approve or redirect" | Fixes "wanted something else" cheaply | Detail pass takes the approved massing as input |
| A4 | **Critique loop**: render, Claude reviews its own iso/top/front images (and neighbours'), revise, bounded rounds | Quality without a human per building | Same pattern as AgentCraft's design-critic |
| A5 | **Macro kit + macro checker**: carve, platform, pillar, bridge, stair, cavern, noise, ring, terrace, lot, road, utility; checker for reachability, headroom, support, fluids, dark spawn, size cap | Rift, sky city, crater, castle are layout programs | Terrain operators: natural blocks only, snapshotted, block count shown |
| A6 | **Delta apply**: diff the new build of a source against what is placed; ghost shows added/removed/changed; apply only the delta | Evolving a settlement without a full rebuild | Builds on `Reconcile` |
| A7 | **Batch placement API** for the mod: queue many placements, spread over ticks near the player, chunk-load aware, one undo group | City scale | Public Java API surface that Steward calls |
| A8 | **Public API / extension points**: library, jobs, ghost and sites callable from another mod; a stable sidecar protocol version | Sibling mod must not copy Architect | Version-negotiated; documented in CONTRACT |
| A9 | Phase 3 (survival sites, crate, ledger) as already planned | Supplied/Hardcore difficulty for Steward | No change, just the order |

Not asked of Architect: the concept card, site survey, steward entity, proactive triggers, permission/difficulty,
inbox/HUD/hub, modules, villagers, animals. These stay in Steward.

Coordination: Architect owns its repo. Steward does not edit it. Proposed changes go to the Architect session as a message;
it decides ordering and records them in `architect-mc/docs/PLAN.md`.

## Round 2 (2026-10-05): requested changes to the accepted plan

Sent to the Architect session after its reply. Priority 1-5 change what Steward can ship; 6-11 are lower.

| # | Request | Detail |
|---|---|---|
| R1 | **Split A5; take the infrastructure early (A5a)** | Nested sites (child inside parent, removed child-first), chunked/sparse snapshots (changed cells per chunk section), terrain operators through the snapshot path, roads and bridges as sites. Order: right after A7. Macro kit + checker (A5b) stays later. Minimum with A7: a **site group** (parent id, one undo group over separate lots) so phase-1 villages are not unrelated lots. |
| R2 | **A8 `job.run` as a full job API** | Streamed progress, cancel, resume after restart, hard budget stop enforced in the sidecar, cache-read token counts in cost output, and **mod-provided tools** (callbacks over the WebSocket) so the agent can query world state mid-job. |
| R3 | **Named parts and a shared component library** | Design sources built from named components with stable ids (wing, tower, porch) so patches and diffs stay local; a per-bible component library (windows, lantern posts, roof trims) generated once and reused. Better for A6 delta apply and for coherence than bible text alone. |
| R4 | **Open building types** | Claude can declare a checker profile from a menu of rules (door opens, roof closed, floors reachable, lit, nothing floating) instead of needing a preset type. |
| R5 | **Open metadata + ports + ownership** | `ext` namespace on library entries and sidecars; named connector ports on blueprints (item output, water inlet, bed count); an owner tag on sites (settlement id) so Architect's UI does not remove a steward-owned site by accident. |
| R6 | Shared stockpile/ledger | Phase 3 crate and ledger serve multiple sites and are queryable by API. |
| R7 | Placement events + site registry API | placed/removed/failed with the blocked reason; persistent queue that waits for chunks and survives relogs. |
| R8 | Eval harness | A prompt set scored by checker + critique loop, run when prompts, the bible format or the model change. Needed for the Sonnet-vs-Opus tier decision. |
| R9 | Rate limits with parallel jobs | Usage-limit hold/resume works on a job group. |
| R10 | Library collections | Group entries by settlement and set. |
| R11 | Reusable dev tooling | DevBridge and devcli usable by Steward's gates, separate ports. |

Offer to Architect: the Steward session writes the A5b spec (macro kit primitives, macro checker rules, site-group and nested-site needs) as a document while Architect builds A8 through A7, so A5b does not start from zero.

## Round 3 (2026-10-09): after Steward's phase 1 gate run

Measured: ordinary details $2.5-4.6 with the report critique, a landmark $3.72, massings about $0.19, a bible $1.16-1.55; $31 for 8 buildings. Already queued for 6c from
earlier rounds: `minLotSize`, group events on real transitions only, `cost.byKind`, `groundHeight`, durable finished batches with a caught-up BATCH_DONE. Already sent
after the gate run: a road that places the part it can (or names the bad span), the 40-character style limit checked on the Java side, `extendGroup` warning when the
new budget would pause again at once.

| # | Ask | Why |
|---|---|---|
| C1 | **Copies in a design group**: an item with `count`, or "variant of item X", made as free variants (`makeVariant` / bible re-skin) inside the group, each its own item for placement, stages and undo | A program's "x4" is four full designs today; about a third of a run's cost |
| C2 | **Small items cheap**: per item, no report critique and an optional massing skip (or a group rule by size) | Sheds, racks and stalls cost as much as a hall |
| C3 | **Plain villages from 7a**: let 7a's lots, connectors, paths and props work for a flat village claim, not only regions, so Steward's one-street `VillageLayout` retires | Paths to doors, props, landscaping, solved once for both mods |
| C4 | **Stub helper for Steward's $0 end-to-end gate**: stub bibles, groups (massing-first, approvals, redirects) and massings with well-formed fake results, usable from a Steward dev client | Steward's flow checks are all hand-run and paid today |
| C5 | **`Designs.estimate` calibrated** with the measured costs above (massing-first, report critique) | Steward shows Architect's estimate in the card screen, `BudgetPolicy` is the fallback |
| C6 | **`fitToLot` for massings** (or a massing's predicted origin and rotation for a lot) | Steward's approval ghosts are an approximation of where the building will stand |

**Architect's answer (2026-10-09): all six taken, no conflicts.** C1, C2, C4, C5 and C6 land in **6c slice 0 "cost and API polish"**: the first slice after 6b
ships (v0.12.0, API 1.9.0). It is sidecar and API work only, with a short gate. It also carries the queued items: minLotSize, group event seq, cost.byKind, groundHeight,
durable batches with BATCH_DONE catch-up, partial roads, typed bounded-field refusals and the extendGroup warning. C1 stays in Architect; Steward does not build it
on its side. **C3 lands in 7a**: its graph, lots, connectors, paths and props work on a flat village claim (a region with no terrain operations), and VillageLayout
retires then. From 6c on, Architect ships slices of a few hours with short contracts (about 300 lines) and freezes the scope per slice. The slice-0 contract comes
to Steward right after 6b ships.

