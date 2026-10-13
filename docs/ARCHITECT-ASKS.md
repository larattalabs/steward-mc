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

## Round 5 (2026-10-09): phase 3 needs

| # | Ask | Why |
|---|---|---|
| C13 | **A design that becomes the next version of an existing entry**: a design request (as `remix`, but installed in place) whose result is the next version of the given entry. Its context is the placed site's current state, including the player's kept edits. | Change requests ("make the tavern creepier", 3c) need the update-available path, which works between versions of one entry. As far as Steward can see, a remix makes a new entry. |
| C14 | **Revert**: confirm `applyDelta` to an older `toVersion` is supported, and that a version is kept while any site stands at it (`pinned`) or the settlement's change log refers to it | "Revert this building" (3c) and change history before autonomy |
| C15 | **A shared UI library** with AgentCraft (world UI, screen kit, sprites), reversing "not now" | Steward adds in-world interfaces after AgentCraft's (nameplates, speech bubbles, building labels, a board): a fourth copy otherwise |
| C16 | **Streets in survival construction batches** | Supplied (phase 4) needs the street built from materials; survival batches skip it today |
| C17 | **Protected areas**: write bounds Architect enforces. A caller marks areas (or Architect detects player-changed cells) that no placement, delta, road or terrain operation of that owner touches. | "Natural blocks only" is a block type, not provenance: a player's dirt paths and landscaping count as natural. Needed before districts and terrain work. |

**Architect's answer (2026-10-09)**, recorded in architect-mc PLAN.md "Steward round 5". The order stays 0a → 0b → 0c → V → terrain; 6b is close.

- **C13 → 0b:** `DesignRequest.versionOf(entryId[, siteId])`. It designs a change request with the entry and, when a site is given, the site's current state and kept edits as context. The result is installed as the next version, then checkDelta, applyDelta and outdated work as usual. Nothing for callers exists before that: `entry.installVersion by:'design'` is internal, and a remix makes a new entry.
- **C14, available now:**
  - `Sites.revert(siteId, toVersion, actor)` (1.7.0), and applyDelta to an older version.
    - Instant mode undoes last-in-first-out where it can, else writes a forward delta.
    - Survival always writes a paid forward delta.
  - A version is pinned while any site stands at it, and never garbage-collected.
  - 0a adds caller pins: `Library.pinVersion/unpinVersion(entryId, version, owner)`.
- **C16 → 0c:** roads and paths in survival construction batches, with a bill of materials.
- **C17 → 0c:** protected areas the caller marks, enforced as write bounds on that owner's placements, deltas, roads and terrain operations, with a typed refusal. Detecting player-changed cells automatically comes with the 6c terrain slices.
- **Shutdown:** 0a fails pending futures with a typed `WORLD_STOPPED` reason. Until then, Steward ending its session at SERVER_STOPPING is right.
- **C15, the shared UI library:** a new public repository and who owns it are Noah's call. Architect takes it to Noah and coordinates with AgentCraft Main.

**Status (2026-10-10):** Architect published v0.12.0 (API 1.9.0, phase 6b: region previews, check and design, nudge, volume survey, IR format 2, `PlaceRequest.pathStyle`, `Reason.PLAYER_BLOCKS`). Next: v0.12.1 (lab-ui switch, no API change), then slice 0a with the stub sidecar first. Steward stays on 0.11.2: what 3f uses (`placeRoad`, `checkRoad`) dates from API 1.5.0. Steward's protected areas (C17's Steward side) are built: its layouts and roads keep off them, and they become Architect write bounds when 0c ships.

## Slice 0a contract review (2026-10-10)

Architect's 0a contract (architect-mc `docs/CONTRACT.md`, "Phase 6c slice 0a") is frozen with Steward's answers:
- **S-0a-1:** a scripted card in `ext["architect:simAnswer"]`, validated against the schema, is enough for `e2e.mjs stub`. Once v0.12.2 is tagged, `run-e2e-client.sh` switches to `ARCHITECT_SIDECAR_BACKEND=sim` with `simCosts: "measured"`.
- **S-0a-2:** for approval ghosts, the same rotation and an origin within ±2 are good enough.
- **S-0a-3:** the first group that pins a bible version carries its cost; the bible line names the job ids it counts, so Steward does not count that cost twice.
- **S-0a-4:** a key re-used with a different body fails with `OP_KEY_CONFLICT`.
- **S-0a-5:** Greywater's 70 minutes were wall clock, including the approvals, two design waves and placement. The time estimate is recorded beside it, not judged against it.

v0.12.2, the sim stub, is built first and Architect says when it is tagged. The rest of 0a comes as v0.13.0, API 1.10.0.

**Consumer check of the 0b versionOf RC (2026-10-10):**
- **Jar:** `architect_mc-0.14.0-rc-versionOf-695299f.jar`.
- **Result:** Steward's stub e2e passes 22 checks. A change request goes through `versionOf`, then an update to v2, then a revert to v1.
- **How Steward calls it:** by reflection, so one Steward jar runs on 0.12.2 with change requests off, and on 0b.

**v0.12.3 (2026-10-10):** the sim fits each request's maxSize, so S lots go through approval to done. The stub e2e uses S buildings again. Architect's note on C6 `fitMassingToLot`: a 180°-rotated detail may land up to 3 off, not 2, and the approach length and groundY are not checked. That is fine for approval ghosts; Steward does not rely on ±2 exactly.

**Consumer check of the 0a RC (2026-10-10):**
- **Jar:** `architect_mc-0.13.0.jar`, sha256 54b1b538…
- **Result:** Steward builds against it and its unit tests pass. The stub e2e passes; the change request is noted in the history, since 0a has no versionOf. The free e2e passes 32 of 33; the miss is Steward's own intermittent board check.
- **Told Architect:** pass, tag it.

**Consumer check of the 0b RC (2026-10-10):**
- **Jar:** `architect_mc-0.14.0.jar`, sha256 270c3a09…
- **Result:** the stub e2e passes 25 checks: recovery by key, change requests through versionOf, all S lots. The free e2e passes 33.
- **Told Architect:** pass, tag it.
- **Benchmark (Architect, $18.69 of $35):** a 3-unique starter cost $9.14, a copies-plus-smalls starter $8.28. The first usable result came at 7.5 and 14.7 minutes. Under $5 is not reached: detail design is about 80% of the cost.
- **Defaults proposed for Steward:** copies on at copyCap 3, and S items as SMALL. They are adopted with v0.14.0.

**Consumer check of the 0c RC (2026-10-11):**
- **Jar:** `architect_mc-0.15.0.jar`, sha256 e15848c6…
- **Result:** Steward builds against it and its unit tests pass. The stub e2e passes 26 checks and the free e2e 33.
- **Told Architect:** pass, tag it.
- **Free paths:** no objection to dirt, coarse dirt, podzol, mycelium and rooted dirt becoming free path ground in V.
- **Next for Steward:** with 0c, protected areas go to `Sites.protect` as write bounds, and Steward keeps its own layout and road checks.

**Consumer check of the slice V RC (2026-10-11):**
- **Jar:** `architect_mc-0.16.0.jar`, sha256 cf6c20f4…
- **Result:** Steward builds against it and its unit tests pass. The stub e2e passes 26 checks; a first attempt failed on a pig standing on a lot, and the stub run now clears farm animals before each build. The free e2e passes 34.
- **Told Architect:** pass. The tag waits for Noah's gallery approval.
- **V's known limits:** the most important for Steward is the dark ground around houses (11.8% spawnable). Steward asked for it first in the next slice.
- **Next for Steward:** new Patron flat settlements move from VillageLayout to `Regions.plan("flat_village")` once v0.16.0 is out.

**v0.16.0 (2026-10-11):** published with Noah's gallery approval. Steward moved to it (free e2e 34, stub e2e 26 on the release). Adopting `Regions.plan("flat_village")` is a feature of its own: design first (PLAN "V adoption"), after a $0 spike of what a realised V village leaves (sites, street, keys, undo).

## Round 6 (2026-10-11): V adoption and the next batch

| Ask | What | Why |
|---|---|---|
| C19 | A lot's ext (Steward's `steward_mc:lot`, `steward_mc:role`, `steward_mc:build`) carried onto the lot's site at realise. | A realised V lot's site has only `architect_mc:lot`; Steward's panels, updates and history name buildings from its tags. |
| C20 | `opKey` on `RegionPlanRequest` and `RealiseRequest`. | A realise interrupted by a restart, found by key as groups and batches are (C9). |
| C21 | Bible-styled lighting (7a §8.3) early: the bible's `light` role chooses the fixture (lantern posts, soul lanterns, end rods, hanging lanterns, wall lamps). | Noah: lighting options that follow the settlement's theme and style. |

**Slice W review (sent 2026-10-11):**
- **S-W-1:** frozen water counts as water. Steward reads `Sample.frozen` as water too.
- **S-W-2:** up to 4 lamps per lot is fine. Each lot's lamp positions are asked for on the lot's ext.
- **Noah's calls:** V's and W's gallery shas both approved; fence-post lamps by default.

**Architect's answer (2026-10-11):**
- **C21:** it is in W, which is already building. The bible's `light` role picks the fixture from six: a lantern post (the default), a soul-lantern post, a torch post, an end rod, a sea-lantern bollard, and a hanging lantern on a post and arm. Coverage is computed from each fixture's own light level. The gallery shows S7 under rustic, infernal and coastal or sky bibles for Noah. Wall lamps stay in 7b, on the design side.
- **C19 and C20:** drafted as slice X (wave 1, $0, depends on V only), CONTRACT.md "Phase 6c slice X" at 7ec7194.
  - A plan's `lots` entry carries `ext`, which reaches `LotSpec.ext` and then the site. A trailing `lotExt` goes on `RealiseRequest`. The limit is 4 KB a lot.
  - A trailing `opKey` goes on plan and realise, with `planByKey` and `realiseByKey`.

**Slice X review (sent 2026-10-11):**
- **S-X-1:** the plan's ext is enough. Steward plans once per build, so its lot tags (`steward_mc:settlement`, `lot`, `role`, `build`, `styleVersion`) are all known at plan time. Steward won't use `lotExt` on realise. Keep it only if it costs nothing.
- **S-X-2:** 4 KB is plenty. Steward's lot ext is about 250 bytes.
- **When X ships:** Steward tags lots in the plan and drops the `architect_mc:lot` fallbacks and the plan-side role lookup. It plans and realises with `steward.<build>.vplan` and `.vrealise` keys, and on a restart finds the region by key instead of by owner and build tag.

**C22 (sent 2026-10-11), from the GPT check of Steward's V fixes:**
- **(a) Retrying a removal that failed.** A region or group removal moves its members into pending records before restoring their blocks. When a restore fails and the removal is run again, the retry drops members missing from the active maps without checking the pending records (`Groups.java` ~601). It can then answer `removed=true` with the land not restored. The retry should finish the pending restorations, or refuse until it can.
- **(b) A removal Steward didn't see finish.** If a region removal completes but Steward loses the answer or fails to save it, a later `Regions.remove` of that region refuses because the region is unknown. Steward then can't tell "already removed" from "never existed". Steward asks for one of two things: the removal answering `removed=true` again for a region it already removed, or `Regions.get` keeping a REMOVED record.
- **Until then:** Steward trusts `removed=true` only from the first removal. A region undo that stops leaves its history entry as it was, so it can be run again.
- **Architect's answer (2026-10-11):** C22 goes into slice X (contract on main at a621b2e).
  - (a) A retried removal finishes its own pending restorations, or refuses naming the failing member. It answers removed=true only when every restore has committed.
  - (b) Removing an already-removed region or group answers removed=true again and writes nothing. `Regions.get` keeps a REMOVED tombstone with its finish time, which survives a reload and is kept as long as opKey records are.
  - The gate injects a restore failure mid-removal and checks that a retry restores the world exactly, or refuses.
  - **When X ships:** Steward's region undo reads the tombstone and retires an entry whose region is already REMOVED.

**Slice X RC (0.17.0, API 1.14.0; slice/X e2443ec; sha256 5559bfa7…), consumer check 2026-10-11: pass.** Steward builds against it unchanged. The stub e2e on 8590/8591, with the RC in the client, passed 30 checks, including the village: planned, approved, 5 buildings placed on region rg1 (7 sites), its street standing as a road, and undone whole.

**C23 (sent 2026-10-11), from the last GPT check of Steward's V path on 0.17.0:** a region removal that resumes after a restart can leave the region REMOVING for good. `Groups` reloads the group removal without its completion future (`Groups.java` ~896), and the region's state is cleared only in that future's callback (`RegionsImpl.java` ~1825). Steward asks that the resumed removal end the region as REMOVED, or report it as stopped, the same as one that never restarted. Until then, Steward doesn't treat a REMOVING region as keeping its settlement busy (Noah, 2026-10-11).
- **Architect's answer (2026-10-11):** C23 is confirmed: `Groups.load` rebuilt removals without their completion future. The fix ships as patch v0.17.1, with no API change.
  - A resumed removal ends the region as an uninterrupted one does: REMOVED with removedAt, or stopped on failure.
  - It is driven from the removal's own saved state, so it survives repeated restarts.
  - New gate items: a restart mid-removal then finishing, a restart then an injected failure then a retry, two restarts, and the group-only case.
  - **When 0.17.1 ships:** Steward passes the RC through its consumer check, pins 0.17.1, raises its minimum Architect version to it, and puts the REMOVING busy check back.

**Architect's open questions for Steward, answered 2026-10-11 (CONTRACT "Open questions for Steward"; S-W-1 and S-W-2 were answered with the W review):**
- **S-T1-1:** 120 s worst case is acceptable, shown as "laying out the village". Steward's claims are at most 257 across (survey radius 127), and districts at most 129. Steward sets no timeout below 120 s on a plan.
- **S-MP-1:** only through bibles and bundled programs for now. The card has no ground-cover field. Revisit with phase 2's custom forms.
- **S-T2-1:** infer movers from the end nodes' lot kinds. The card doesn't name them, and Steward may override per edge later.
- **S-T2-2 (Noah):** ladders on for player-only edges (lookouts, watchtowers). Villager edges never use them.
- **S-B-1:** one region per stroke, as drafted. A Steward history entry holds one region, so one undo per stroke matches.
- **S-B-2:** not needed in 6c. Per-settlement C17 areas are enough; keep owner `*` deferred.
- **S-S2-1:** a fair stand-in. A hillside card typically asks for a landmark, a hall, a craft building or two, a store and dwellings, which matches. A farm would make it closer still.
- **S-T3-1:** stone bridges and plain tunnels are acceptable until 7a. Please report which edges crossed water or rock (in the plan or the region), so Steward's card and history can say so.
- **S-H-1 (Noah):** yes, a small contained pond beside a decorative farm lot when the claim has none. It stays on the lot, never outside the claim or in a protected area, and is removed with the region. Working farms are different and are built for yield: water spaced so every farmland block is hydrated (a source per 9x9, channels on that spacing), designed into the farm rather than added as a pond. That's for Steward's functional-module farms and later automated ones (7b / modules).
- **S-D-1:** survey candidates are enough. Steward's districts are its own (3f) until the joint gate. Phase 2 waits for 7c anyway, so there's nothing to wait for on Steward's side.
- **S-D-2:** this matches: Steward proposes and approves, Architect lays out. Steward needs the join's site under the district's region, so that undoing the district removes its join.
- **S-VOC-1:** defaults are enough now. A card's style text can ask for a rare palette ("sculk temple"); when one does, Steward will pass budgets to the bible request. Please keep the budget field on the bible request.
- **S-RS-1:** useful. Steward will move Supplied villages to `flat_village` with shovel paths when it starts phase 4 (Supplied on construction sites), not before.
- **S-PT-1:** not needed in 6c. C17 areas are enough until 6d's replanning, since updates already keep the player's edits.
- **Architect's answer (2026-10-11):** all 14 answers are recorded on Architect main at 555c6bd, under "Changes from Steward's review of the batch", and every slice is now frozen.
  - **From Steward's answers:** S2 gets a farm lot, and each plan or region edge reports `crossing: bridge|tunnel` and what it crossed. The join's site sits under the district's region, and the bible request keeps its budget field. PT is dropped from 6c, and working-farm hydration is carried to 7b.
  - **Building now:** F1 (ice spikes, rock spires) and MP (masks and patterns); neither depends on W.
  - **Next:** T1 once W lands, then T2, then S2. Architect sends an RC as each one is ready.

## Round 7 (2026-10-11): phase 4, Supplied settlements

| Ask | What | Why |
|---|---|---|
| C24 | **A settlement stockpile.** One store per owner (a settlement) that every construction item of that owner draws from: groups, batch roads, standalone construction roads (`placeRoad` CONSTRUCTION, refused today without a shared crate), and construction deltas (updates and reverts, which place a crate of their own today). Its parts: one or more crate blocks the owner names, kept across groups and builds; the caller sets the order sites are fed (a priority, or batch order); one ledger per owner; `Sites.stock(owner)`; refunds (deconstruct, undo) delivered into it rather than dropped at a crate cell, with what didn't fit dropped. | Noah wants a settlement-wide storehouse (2026-10-11), as a colony sim has. A crate per group means a new crate for every build, update and road, and refunds lie on the ground. |
| C25 | **Waiting status in the API.** On `Stock`, or on `SiteView` for a construction site: whether it is waiting for materials, and the next items it needs, in build order (`nextNeeded: [{item, count}]`). Plus an event when a site starts or stops waiting. | The steward names what's needed next, and comes to find the player after a long wait. Today that is only on the crate screen and HUD. |
| C26 | **The BOM with foundation and approach before placing,** or an estimate of them on `Verdict`. | The card says what a Supplied build will cost before it is approved. `Verdict.bom` covers the template only. |
- **Architect's answer (2026-10-11):** C24-C26 become **slice Y, settlement stockpile**. It runs as its own survival track, in parallel with the terrain slices, and doesn't wait for T1 or T2.
  - **Timing:** the contract (drafted against the real crate, feed and refund code) comes to Steward for review within hours. The build follows review, about a day including its gate.
  - **The gate:** exact accounting. No item is duplicated or lost across feed, deconstruct, undo, refund overflow, or a restart mid-feed.
  - **Scope:** C24 as asked, with existing worlds' per-build crates migrated. C25 adds a per-site waiting status with `nextNeeded`, plus events when a site starts or stops waiting. C26 gives the full BOM before placing, or a Verdict estimate with an accuracy bar.
  - RS (region survival) will build on Y's stockpile instead of its own region crate.
  - Steward's $0 interim work doesn't conflict with Y.

**Slice Y contract review (Architect main e8264ab, "Phase 6c slice Y: settlement stockpile"; sent 2026-10-11):** it fits Steward's phase 4 design.
- **S-Y-1:** a blocked target keeps its reservation for transient blockers (a player in a cell, a support not built yet). The exception: a target whose whole frontier is in unloaded chunks doesn't reserve. Builds use LOADED_ONLY, so with the player at one end of a town, a far house first in the feed order would otherwise starve every target near them.
- **S-Y-2:** a reorder list. Steward's order is the street, then landmarks, then the build's stages; a player's "build this one first" moves one target to the front.
- **S-Y-3:** accept only what a build needs, for Y1. A general storehouse (putting materials away ahead of the next build) can come after phase 4, if players want it.
- **S-Y-4:** summing each item's `check` `bomFull`, `checkRoad` and `checkDelta` is enough. Steward checks every lot already.
- **S-Y-5:** 100 ticks is fine. Steward's own "come and find you" waits 10 minutes from `waitingSince`.
- **S-Y-6:** one dimension per stockpile. A settlement and its districts are in one dimension.
- **S-Y-7:** drop the region crate (`crateAt`, `RegionView.crate`) from RS; a region draws from the stockpile.
- **One gap:** a settlement can be abandoned (its builds undone and claim released). Its stockpile then needs to be dissolved: every remaining unit, reserved or not, handed to a player (or dropped at a crate), and the crates restored, in one call. `removeStockpileCrate` refuses while stock is over capacity, and refuses the last crate with targets building.
- **Coordinator decisions on N-Y-1 and N-Y-2:** 27 stacks a crate, and Y1 before Y2, both fine with Steward. They're relayed to Noah.
- **Architect's answer (2026-10-11, main 9be5694):** Y is frozen. Y1 and Y2 are building in parallel.
  - **S-Y-1:** a target whose frontier is all in unloaded chunks reserves nothing, with a gate item for it.
  - **`dissolveStockpile(owner, to)` is in Y1:**
    - the stock goes to a player (overflow dropped at them), or is dropped at a crate when `to` is null;
    - the crates are restored and the stockpile ends;
    - it works over capacity and while targets build, which fall back to waiting with no stockpile;
    - every unit is counted as withdrawn or dropped, with a gate item checking the sum.
  - **S-Y-7:** RS's region crate is dropped.

**Architect's release process (relayed by the Architect coordinator, 2026-10-11, as Noah's decision):**
- **Release first, check after:** most slices merge and publish without an RC check from Steward. Steward tests the published version, and what it finds becomes a patch release.
- **RC checks remain** for slices that change behaviour Steward relies on (removal, world stop, item accounting): C23 (v0.17.1), Y1 and Y2. W, F1, MP and the terrain slices don't get one.
- **Batched tags:** 2-3 slices per tag, so Steward adopts fewer, larger version jumps.
- **For Steward:** when a version is published, run the free and stub e2e on it, and send what fails as a patch ask.

**C23 RC (v0.17.1, API 1.14.0; fix/c23-resumed-removal 1783125; sha256 add4b3d9…), consumer check 2026-10-11: pass.** Steward builds against it unchanged. The stub e2e on 8590/8591 with the RC in the client passed 31 checks, including the village placed and undone whole.

**Zones and the long-term direction (heads-up sent 2026-10-11; Architect's audit at main 4c95f6a, PLAN "Keep open: zones and the long-term direction"):** nothing Architect is freezing closes these off.
- **Owners:** a free string on records, never part of an id, so a later `transferOwner` is additive. It would move opKeys, tombstones, C17 areas and Y1's stockpile and ledger. Y1's stockpile is keyed by its own id, with the owner as an attribute.
- **A rule for every slice from now on:** no id derived from an owner string, and no "owner never changes" assumption in a persisted format.
- **Candidate asks, for when Zones is designed:**
  - `Survey.biome` per column (T1's fields could carry it);
  - districts that follow a boundary (a biome edge, a river, a ridge; Architect's D);
  - zone walls with gates (7a);
  - several related bibles in one settlement;
  - damage and repair as journal-backed operations.

**v0.18.0 (W + Y2, API 1.15.0; published 2026-10-11, checked after release): pass.** Steward builds against it unchanged; stub e2e 31, free e2e 34. Nothing to patch. Steward doesn't use Y2's `Sites.waiting`/`SITE_WAITING` or `Verdict.bomFull` yet; they come with phase 4's requests.

**Architect paused (relayed by the Architect coordinator, 2026-10-11, as Noah's decision).** v0.18.0 is current. F1 + MP are nearly ready as v0.19.0, and Y1 (the stockpile), T1 and headless gates are in progress. Nothing is waiting on Steward. Phase 4 waits for Y1 until Architect resumes.

## Round 8 (2026-10-12): zones, the first asks

The Architect coordinator's sync (2026-10-12):
- **Y1** (stockpile) is ready as v0.20.0 / API 1.17.0; its RC comes to Steward within hours.
- **F1 + MP** are v0.19.0, with no Steward RC check.
- **T1** is in its gate. T2, F2, B and S2 follow T1.
- Only Y1 touches Steward's behaviour.
- Stockpile targets keep no chunk tickets.

Steward's answer on chunk tickets: fine. Steward builds with LOADED_ONLY, and a build progressing only while a player is near suits it.

| Ask | What | Why |
|---|---|---|
| C27 | **Districts that follow a boundary** (into slice D's contract before it builds). Steward gives the town's claim, the other claims and protected areas, a size, a side or anchor point, and the boundary to follow: a biome edge (between two named biome families), a river or shore, or a ridge. Architect returns the district's shape (a polygon or mask, not a square), its bounding claim for overlap checks, where its road joins the town, and its own street line. Requirements: <ul><li>stable across a reload;</li><li>a refusal names its reason (no such edge within reach, overlaps, protected);</li><li>undone as one operation with its road (a region, as V's).</li></ul> Also: a district not touching the town (an outpost), reached by a road over unclaimed land, with the road's corridor as the settlement's land. | Zones (Steward PLAN): Noah's desert/jungle settlement divides along the biome edge, a harbour district follows the shore, and a mine district sits on the ridge. Today Steward's districts are squares beside the town, chosen by side (`DistrictSite`, 2026-10-12). |
| C28 | **Biomes per column** in the survey, optional. Steward reads `Sample.biomeAt` (4x4) today, which is enough to choose a side. A finer biome edge would help C27 draw the boundary; Architect's choice whether C27 needs it. | The biome edge as a district's border. |

Held until zones is designed: zone walls with gates (7a), several related bibles in one settlement (7c).
- **Architect's answer (2026-10-12, main b8d4ab2, slice D's "Steward round 8 additions"):**
  - **C27 as specified:** a shape mask, a bounding claim, a join and a street line. It is deterministic across reloads, refusals are named, and the district and its join are one region with one undo.
  - **Outposts:** their road corridor is settlement land, undone with the outpost.
  - **C28:** left to D's builder, who adds it only if edge tracing needs it and records why not otherwise.
  - **Gate:**
    - a desert/jungle edge district within 4 cells of the edge;
    - a shore district, a ridge district and an outpost;
    - reloads identical, named refusals, an exact undo for each.
  - **Order:** D may split into D1 (survey-candidate districts, `beside`) and D2 (C27, outposts, C28; D2 needs T2's routing). D starts after T2, which follows T1.

**v0.19.0 (F1 + MP, API 1.16.0; published 2026-10-12, checked after release): pass.** Steward builds against it unchanged: stub e2e 31, free e2e 37. The free run's one failure was the test world (a cow in the proposal check's test placement); that check now clears farm animals first. Nothing to patch.

**Y1 RC (v0.20.0, API 1.17.0; slice/Y1 072eaaa; sha256 9584b4a7…), consumer check 2026-10-12: pass (regression only).** Steward builds against it unchanged. With the RC in the client, stub e2e 31 and free e2e 37 pass. Steward has no phase 4 flow yet, so the stockpile itself (feed, refunds, waiting, dissolve) is tested when phase 4 is built on the published 0.20.0, and what fails then is a patch ask.

## Round 9 (2026-10-12): phase 4 on 0.20.0, the first patch ask

| Ask | What | Why |
|---|---|---|
| C29 | **(a) A target being removed stops drawing from the stockpile.** When a removal (`remove`, `removeGroup`, `undoStage`, region removal) starts on a stockpile target, it leaves the feed at once (no more reservations or consumption). Its refund is tallied when its restore completes, not at the removal's start (`Groups.java` ~850 snapshots it first, so units consumed while the removal waits for a player are neither refunded nor counted). **(b) Pause and resume a construction target by API:** `Sites.pause(targetId, boolean)`, as the crate screen's button does. A paused target reserves nothing (Y1's rule) and keeps its state. | Steward's GPT review of phase 4: a cancelled Supplied build is taken up by an undo, and while that removal waits (a player standing in it), its sites keep consuming the settlement's materials, with a stale refund. With (b), Steward freezes a cancelled build's targets before taking them up. |
- **Architect's answer (2026-10-12):** C29 is accepted and building as its own slice.
  - **(a)** A target leaves the feed when its removal starts: no new reservations, no consumption, and its reservations are released. Its refund is tallied when the restore completes and credited once per key, across resumed removals and a restart mid-wait.
  - **(b)** `Sites.pause(targetId, boolean)`, persisted. A paused target reserves nothing and on resume takes its old place in the feed order.
  - **Gate:** a removal waiting on a player (with and without a restart), pause and resume, undoStage and region removal.
  - **Release:** a minor release after v0.21.0 (T1 + SCH), with an RC check (it touches accounting).
  - **When it ships:** Steward pauses a cancelled build's targets before taking them up.

**v0.21.0 (T1 + SCH, API 1.18.0; published 2026-10-12, checked after release): pass.** Steward builds against it unchanged; stub e2e 36 (with the survival flow), free e2e 37. Nothing to patch.

## Round 10 (2026-10-12): composition

**Composition (`docs/COMPOSITION.md` v2; Architect's answers in `architect-mc/docs/COMPOSITION-ANSWERS.md`, main bfb9895): adopted by both.** Architect drafts P0 (the plan check) next.

**Pending parts need a comparable capability catalogue (sent with Noah's answers; Architect agreed, in P0):**
- each entry has a stable block id (`connector.wall`, `locator.along`), the API version it arrived in, and its own parameter-schema version;
- ids are never reused or renamed, and retiring one is an explicit state;
- an `unsupported` answer names the exact block id, so diffing two releases' catalogues shows what is newly supported;
- a P0 gate item covers the diff.

The geometric schema's first draft comes to Steward when it is pushed; Steward's plan writer starts against it.

**P0 and plan/0 review (Architect main d6e634f: `kit/schemas/plan.json`, CONTRACT "Phase 6c slice P0"; sent 2026-10-12).** The schema fits Steward's lots and the composition loop.
- **S-P0-1:** yes, one region per plan until D1, compiled through a bundled `plan_v0`.
- **S-P0-2:** `required` defaulting to false is fine. Steward's plan writer always writes it explicitly, so nothing hangs on the default.
- **S-P0-3, anchors Steward passes:**
  - `stone` (the founding stone);
  - `player` (where the player stands when they point: "here");
  - `street_w` and `street_e` (the town street's ends, when it stands);
  - `district_<id>` (each district's centre);
  - `stockpile` (its first crate, in survival).
- **S-P0-4:** partRef plus ext is enough. Steward keeps its own index in the life layer. Please echo each part's `ext` in its answer, so Steward needn't re-read the plan.
- **S-P0-5:** yes, a provisional building keeps its approval while its fit stays in its lot box and its cost stays in its range. Past the range's top, Steward asks the player again. Please put the range in the approval artifact.
- **S-P0-6:** Architect keeps the artifact by sha, and Steward stores the sha with the settlement (saved and migrated). Steward needs the artifact kept while a settlement refers to it: retention tied to the owner, or pin/unpin, with a tombstone after removal.
- **S-P0-7:** yes: street is a road with lamps (the bible's lamps since W), path is narrow with stairs. Steward's streets are 3 wide today; a width member would be welcome.
- **S-P0-8:** yes, an unknown top-level member makes the plan malformed, with `ext` as the place for extras. Unknown kinds inside parts stay `extension` / `unsupported`, as agreed.
- **S-P0-9:** freeze after one round with plan v0 fixtures. Steward records plans for the 59 asks against plan/0 now and reports what doesn't fit. Time-boxed to that round.
- **Two gaps in plan/0:**
  - **(a) A lot's free-text label.** A lot's `role` is a slug, but the card's roles are free text ("fisher's hut"), so a lot needs `label` (text80), as zones and connectors have.
  - **(b) A style not made yet.** `bibleRef` needs an existing bible, but a plan is composed before its bible is made. Please allow a pending form, `{pending: "<tag>"}`, checked as provisional, like an undesigned building.
- **Architect's answer (2026-10-12, main d776b6c, CONTRACT "Changes from Steward's review of P0"):** all of it is taken.
  - **Lots:** `label` (text80).
  - **Styles:** `style` takes `{pending: "<slug>"}`, provisional with cost and palette ranges; the approval rebinds when the bible exists.
  - **Width:** `width` 1-5 on street, road and path (defaults 3, 3, 1).
  - **Answers:** each echoes its part's `ext`.
  - **Artifact:** it records each provisional part's cost range and fit box; above the range, `realiseApproved` refuses `ARTIFACT_STALE`.
  - **Retention:** artifacts are kept while pinned (`pinArtifact` / `unpinArtifact`) or referenced, with tombstones (`ARTIFACT_UNAPPROVED`).
  - **Anchors:** Steward's are documented; an unsupplied anchor is `malformed (UNKNOWN_REF)`.
  - **Freeze:** after Steward's one fixtures round. Additive optional members are allowed until then.

**C29 RC (v0.22.0, API 1.19.0; slice/C29 5603402; sha256 1348a18a…), consumer check 2026-10-12: pass.** Steward builds against it unchanged. With the RC in the client, the stub e2e passed 37 checks, including the survival flow: the bill 629, the stockpile asked, 629 delivered and consumed, and 629 refunded on undo. When v0.22.0 ships, Steward pauses a cancelled build's targets (`Sites.pause`) before taking them up.

**v0.22.0 (C29, API 1.19.0; published 2026-10-12, checked after release): pass on a rerun; one intermittent stall reported (C30).** Steward is on it (requires >= 0.22.0) and pauses a cancelled build's targets (`Sites.pause`). Free e2e 37. The stub e2e failed once and passed 37 on a rerun.

**C30 (sent 2026-10-12): a build's last stage sometimes never reaches "placed".** In one stub run, two batches stalled the same way.
- **Creative, INSTANT:** set_193's g193. Stage district_2 went "approved -> placing", its site s718 was "Placed ... over ticks", and the stage never logged "placing -> placed". The batch never finished. Its earlier stages each reached "placed" within a second.
- **Survival:** set_195's g195. After the street stage, the landmarks stage started placing only when survival was turned off ten minutes later. The street was waiting for 2 lanterns and 2 fences; it is unclear whether a stage now waits for the one before it to be built, or this is the same stall.
- **Evidence:** steward-mc `artifacts/e2e/client.log`, 18:19:23-18:39:28 (local evidence, not committed).
- The RC (sha256 1348a18a…) passed the same flow. The published jar has a different sha (1f10a1e2…) though only docs commits separate the two, and a rerun on the published jar passed. So it looks intermittent: a race in stage completion.
- **Architect's answer on C30 (2026-10-12): not a stall.** Both items waited on temporary blockers that Steward's own fit had already reported: a chicken in a lot (OCCUPIED), and the e2e player on a lot (PLAYER_IN_BOX).
  - **Gating, unchanged since 4d:** a stage places once the earlier stages are finished, and a construction item counts as placed when its site is put down.
  - **v0.23.1** logs an item's wait once per reason. The jar sha differs only in `builtAt` and manifest order.
  - **Steward's side:** the steward now says what holds an item up (`ITEM_WAITING`: someone on the lot, an animal in it, ground not loaded). The e2e clears animals before every build, keeps the player off the lots, and waits 720 s, past Architect's 600 s.
- **v0.23.0** (F2, API 1.20.0) and **v0.23.1** (the C30 log line) are released; Steward is on 0.23.1.

**The plan v0 round (2026-10-12, paid, $2.00 within Noah's $40 cap; `fixtures/strange-asks/plans/`, `tools/record-plans.mjs`): all 59 plans are valid plan/0.** Claude wrote them from the recorded cards, at about $0.03 a plan once the prompt was cached. 34 plans have parts that wait, all carried by the extension envelope and none malformed.
- **Most wanted reserved kinds:** `connector.rope_bridge` (11 parts), `connector.wall` (8), `rule.palette` (8), `rule.shape` (4), `connector.tunnel` (4).
- **Free layouts have no reserved name,** so the same idea came out four ways: `connector.ring_street`, `connector.grid`, a `rule.shape` "spiral_shape", and `rule.ring_layout` / `rule.layout_ring`. **Ask:** reserve FL's vocabulary before plan/0 freezes, for example `program.lots.layout`: `ring | spiral | grid | organic` (reserved until FL), so an unsupported answer names one block.
- **Kinds Claude needed that the catalogue doesn't list** (candidates to reserve):
  - forms: `crater`, `hollow_mountain`, `waterfall`, `seabed_site`, `ship_hull`, `mast` (7c, H, under water, moving);
  - zones: `district` (the reserved `{districts}` program wasn't found);
  - rules: `stacked_floors` (an arcology), `suspended` (an upside-down village).
- **Steward's side:** the organic hamlet's layout was dropped silently. The plan writer's prompt now says a layout other than one street is always written as a part and said in `waits`.
- **plan/0 frozen (Architect main 3df6aef, 2026-10-12):**
  - layouts: `program.lots.layout` takes `street | ring | spiral | grid | organic`, the others reserved until FL as `layout.*`, and rule R4b keeps a layout out of connector and rule kinds;
  - zones: `zone.district` is a reserved alias of `program.districts`;
  - eight new reserved kinds;
  - the demand ranking is recorded; the catalogue has 72 entries.
  
  Steward is on it (`schema/plan-0.json`, sent compact: Architect's jobs take at most 50,000 characters of system prompt, and a test keeps 4,000 spare). The five plans that used ad-hoc layout kinds, or none, are re-recorded ($0.23) and name `ring`, `grid` and `organic`.

**v0.24.0 (B, terrain brushes, API 1.21.0; published 2026-10-12, checked after release): pass.** Steward builds against it unchanged; stub e2e 37, free e2e 37. Nothing to patch.

**v0.24.1 (C31, a safety patch: tree felling now stops at protected areas, other owners' cells, the claim edge and non-log blocks; published 2026-10-13, checked after release): pass.** Steward builds against it unchanged; stub e2e 37, free e2e 37. Limit: Architect can't tell a player-placed log from a grown one, so a player's log outside any area can still be felled ("player-placed tracking" is a candidate in Architect's PLAN; whether to ask for it is with Noah).

| Ask | What | Why |
|---|---|---|
| C32 | **Player-placed tracking** (Noah, 2026-10-13). Record which blocks a player placed, as against generated terrain and Architect's own writes, so that no Architect operation (felling, a region, a brush, a road, a fit's clearing) removes or rewrites a player's block unless the player's owner is the caller and asks for it. Steward's earlier S-PT-1 ("C17 areas are enough until 6d") is withdrawn for this case. Questions for Architect: the cost of tracking (per chunk, a sparse set), what happens to blocks placed before tracking began, and whether a player-placed block's refusal names the block. | 0.24.1 (C31) stops felling at protected areas, but a player's log cabin outside any area can still lose its logs to a neighbouring village or brush stroke. Players won't forgive a felled cabin, and they shouldn't have to mark every build with `/steward protect`. |
- **Architect's answer (2026-10-13):** C32 is accepted as slice PT, building after T2 in parallel with P0. Its contract comes to Steward for review. Expected answers:
  1. **Cost:** a sparse per-chunk set saved with the chunk (a few bytes a placed block, the placer as a palette index), with O(1) lookups.
  2. **Earlier blocks:** unknown, treated as natural unless they are non-natural blocks, journal-owned, or in a C17 area. The gap is natural-looking blocks a player placed before PT; an optional one-time "claim my build" helper may mark a box as player-placed.
  3. **Refusals:** they name the block id and position (the first N, plus a count).
- **C32 refined by Noah (2026-10-13), sent while PT is drafted:** the settlement should interact with and integrate a player's builds, and not worry about one-off blocks or the player's small fixes to natural terrain. So PT is about **builds**, not every block:
  - **Builds.** PT groups player-placed blocks into builds: connected structures large enough to be one (a house, a barn, a wall, a farm), with Architect proposing the rule (size, enclosure, share of non-natural blocks). Each has a stable id, an outline, its entrances (doors) and a guessed kind.
    - A build is kept: no operation removes or rewrites it unless its owner's settlement asks.
    - A build is a **plan feature:** `near`, `adjacent` or `around` a build; a road's endpoint at its door; a district round it. The layout plans around it.
  - **Incidental blocks** (a torch, a few scattered blocks, a filled hole, a patch of dirt or stone that evens out the ground) count as natural. Architect may change or clear them, without refusing.
  - **Working on a build** ("add a floor to my house", "extend the barn"): Steward needs Architect to **adopt a build as a site** under the settlement's owner, kept standing, with its blocks captured as its first version. Updates, change requests, revert and undo then work on it as on any building.
  - **Edge cases:** "claim my build" marks a box as a build; "release" lets one become incidental.
  - **Steward's side:**
    - the settlement view and survey mode show a claim's builds ("your house");
    - the plan writer gets them as features;
    - the steward says it will build around them;
    - a change request can name one ("my house").
- **Architect's answer (2026-10-13):** Noah's refinement is folded into the PT draft.
  - **Builds:** stable id, outline, doors and a guessed kind, with a rule for what counts that includes hysteresis, so builds don't flicker. They are kept, and they are plan features (near, adjacent, around, a road to a door, a district round one), added to plan/0 additively.
  - **Incidental blocks** count as natural and are cleared without refusal. Claim and release are in.
  - **Adopting a build as a site** is likely slice **PT2**, right after PT.
  - **Refusals** name the build (id, kind) and the blocking positions.
  - The draft comes to Steward for review.
