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

**Refined with Noah (2026-10-09), sent to Architect:** quality first (PLAN.md "Copies, adaptations and originals").
- C1 copies have safeguards. Landmarks and single buildings are never copied. There is a cap of 2-3 placements per design. Each copy shows variation (palette, an
  exposed parameter, mirror or rotation). Every variant goes through the checker. A failed fit falls back to an original. Each copy is linked to its source
  (`variantOf`).
- C2 is narrowed to skipping the report critique on small items only. Massings stay for every building.
- The 7b rule is adapt first: `fitToSite` of an existing design, with an original as the fallback. Lot types only predict cost. Steward owns the policy: the copy
  caps and the player's "all original" choice.
- 6d: a new version of a source design is offered for its copies and adaptations as one update, applied per lot, keeping each lot's adaptation. C1's
  representation carries this from the start.
- 7c: promoted shapes may be offered across settlements, by the player's choice only, re-checked, refitted and re-skinned.
- C5 estimates by kind (original / adapted / copy).

**Accepted by Architect (2026-10-09):**
- Every variant already passes the full kit check: the sidecar's VariantRunner runs `checkDesign` and refuses to install a variant that fails.
- Slice 0 adds the fall-back to an original when a copy fails fit or the checker on its lot.
- C1 copies carry `variantOf` and the source version from the start.
- C2 is narrowed: only the report critique is skipped, massings stay.
- C5 estimates by kind.
- Adapt first (7b), updates follow the source (6d) and card-only reuse (7c) are recorded for their phases.
- 6b's release waits on a performance regression fix (megaA ticks of 93-103 ms against 6a's 28 ms), so slice 0 comes a little later.

## Round 4 (2026-10-09): from the GPT-6.1 reviews

Independent reviews of Steward's code and plan (`docs/GPT-REVIEWS-2026-10-09.md`). Noah adopted their points on cost and wait, Architect's critical path, and
change history before autonomy. These are the Architect side of them.

| # | Ask | Why |
|---|---|---|
| C7 | **Where cost and time go, per stage**: bible, massing, detail, repair rounds, critique, queueing, usage holds, for each group (on `Group` and in the job log), and a benchmark of a settlement of unique buildings next to a repeat-heavy one | Copies help repeat-heavy villages only. Greywater Hamlet (3 unique buildings) cost $13.76 and took about 70 minutes. Steward sets provisional targets: a useful starter settlement under $5, the first usable result within 15 minutes. It needs to know which stage to cut. |
| C8 | **Bounded effort for small buildings**: a lower repair-round and token cap for S footprints (sheds, racks, stalls), and reusable kit components they can be built from | A $4 drying rack is the cost problem in one building |
| C9 | **Caller operation ids**: an idempotency key on `bibles().request`, `designs().requestGroup` and `sites().queue` (the same key returns the same job, group or batch), and a lookup by key | Steward now saves a build before it asks Architect for anything. A crash between the request and the ack still leaves the build saying "interrupted" while the paid job runs on. With a key, Steward finds and adopts it instead. |
| C10 | **A narrow flat-settlement slice early**: lots, a path from each door to the street, one shared space (a square or well) and a few props, for a flat claim, before the full terrain, hydrology and connector catalogue | Village quality waits behind a large amount of terrain work. This slice alone makes Steward's settlements read as places. |
| C11 | **6c slice 0 split** into parts that ship on their own: consumer support (C4 stub, C5 estimates, C6 massing fit, durable batches), reuse (C1, C2), placement polish | Its scope grew well past "cost and API polish"; Steward's $0 e2e gate waits only on the stub |
| C12 | **VillageLayout retires behind a joint gate** with 7a/6d: an existing Steward settlement migrates onto Architect's lots and still updates (6d) and undoes | Retiring it on 7a alone could strand settlements built before |

Not asks: shared UI extraction, schematic interchange and shape promotion wait until an observed problem needs them.

**Architect's answer (2026-10-09): all of C7-C12 taken**, recorded in architect-mc PLAN.md "Steward round 4". Order after 6b ships:
- **0a consumer support:**
  - the C4 stub (first: Steward's $0 gate waits on it), C5, C6;
  - durable batches;
  - C9 idempotency keys with lookup by key;
  - the C7 per-stage cost and time breakdown;
  - group event seq, cost.byKind;
  - `Bibles.cancel` returning a future.
- **0b reuse and bounded effort:**
  - C1 with safeguards, C2, C8;
  - the C7 benchmark of unique against repeat-heavy settlements, against Steward's targets (under $5, 15 minutes).
- **0c placement polish:** minLotSize, partial roads, groundHeight, typed field refusals, the extendGroup warning.
- **V, flat village (C10):** lots, door-to-street paths, a shared square or well, props.
- Then the 6c terrain slices, then 7a. C12's joint 7a/6d retirement gate is recorded.

Answers to Steward's questions:
- **`cancelGroup`** never throws synchronously. For an unknown group, or one already done, failed or cancelled, the future fails ("no group", "already <status>"). It also fails when the helper is not running. Treat a failed future plus a final `group(id)` as "already over". Steward does this.
- **`bibles().cancel`** is fire-and-forget in 1.8/1.9, so a refusal is silent. On success the job is saved as cancelled and BIBLE_DONE fires once, caught up after a restart. A job that had already finished sends no new event. Reading `job(id)` after cancelling is right; Steward does. 0a makes cancel return a future.
