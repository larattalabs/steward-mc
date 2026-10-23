# Asks for Architect (from Steward)

**Shared status:** `../architect-mc/docs/STEWARD-SYNC.md` (since 2026-10-19) holds what Steward has shipped against Architect, Steward's not-started list, the agreed order and the open asks. Architect updates it at every release and sync; Steward tells Architect when an item moves or is blocked. This file keeps each ask and review in full, round by round.

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

**PT contract review (Architect main 3882afc, "Phase 6c slice PT"; sent 2026-10-13):** it fits Noah's direction. The coordinator's four Noah-level calls match his stated intent: a torch counts as natural, a half-built house has a 10-minute grace, the placer may consent, and the thresholds stay at their defaults.
- **S-PT-2:** a new `PLAYER_BUILD` reason. Its meaning differs from `PLAYER_BLOCKS`.
- **S-PT-3:** per-request consent by build id is enough for v0. Steward's change requests carry the asking player as the actor.
- **S-PT-4:** builds are hard keep-outs in every plan by default, door clearance included, except a build named in the request's consent (an adopted one, or "replace my shed").
- **S-PT-5:** `builds()` and `BUILD_CHANGED` are enough, with two additions:
  - `builds(box)` by area (a settlement's claim);
  - the build's placer (UUID) on its view, so the steward can say "your house" or "Alex's barn".
- **S-PT-6:** PT2's shape is right: v1 captured, undo un-adopts only. The adopted site carries the caller's ext (Steward's role and label), and the player's later hand edits stay player edits (updates keep them, as today).
- **One more:** a PATH build should not block a road. A road may cross or join a player's path without consent, as long as it doesn't remove it, so roads link up with the paths players lay.
- **PT frozen with Steward's review (Architect main 80499d7, "Changes from Steward's review of PT"):**
  - a new `PLAYER_BUILD` reason, and consent per request, by build id, with the player as actor;
  - builds are hard keep-outs unless consented;
  - `builds(box)`, and placer UUIDs on a build's view;
  - PT2 keeps the caller's ext, and hand edits stay the player's;
  - a road may cross or join a PATH build without consent, while it changes none of the path's cells (gated).
  
  PT builds after T2, alongside P0.

**v0.25.0 (T2, slope-aware routing, API 1.22.0; published 2026-10-13, checked after release): pass.** Steward builds against it unchanged; stub e2e 37, free e2e 37. On flat ground the flat village's street still comes as road sites (`road:street_*`). Most walk runs on real terrain are now graph-edge segments, not roads, so once Steward builds villages on slopes, `Districts.streetOf` must read the region's graph edges as well.

**P0 RC (v0.26.0, API 1.23.0; slice/P0 70ea8e6; sha256 e25324e0…), consumer check 2026-10-13: pass.** Steward builds against it unchanged. The stub e2e, with the RC in the client, has a new plan-check flow (`/steward dev check_plan|realise_plan|unrealise_plan`):
- the recorded market-town plan is READY with an artifact;
- the hand-written walled town's wall is `unsupported connector.wall`, and the plan overall `REQUIRED_BLOCKED`;
- the market town is realised from its approved artifact (rg2) and removed whole ("removal done: REMOVED").

The one failure was the e2e's own: its player stood inside the plan, so the removal waited for the player past the check's 300 s. The flow now moves the player off first and waits 660 s. Architect's CI on Steward's 59 plans: none crashed or malformed, and every part in `waits` came back unsupported or blocked. Steward looks at the 7 other non-fitting parts (a lotless zone, terraces inside zones, no river or ocean on the test claim, the spiral road) when the plan writer goes live.

**PT RC (v0.27.0, API 1.24.0; slice/PT 47b69bc; sha256 ef37aeeb…), consumer check 2026-10-13: pass.** Built against it with `ARCHITECT_JAR`: stub e2e 41 (with the live composition check: a described settlement's plan checked, the wall kept pending on `connector.wall`) and free e2e 37. Published as v0.27.0; Steward moves to >= 0.27.0 (API 1.24) and uses builds: the settlement screen and survey mode show them, the plan writer gets them as features, `/steward builds <id> [claim <reach> | release <build>]`.

### C33 (2026-10-13): room for the plan schema in a job's system prompt
The plan writer's system prompt is its instructions plus plan/0 in full, so the schema is cached and Claude knows every block. With PT's build features it reached 48,148 of the 50,000 characters `job.system` allows. Steward now leaves out the catalogue's bookkeeping fields (`sinceApi`, `schemaVersion`, a `def` that is just `#/$defs/<id>`), which brings it to about 44,800, but every new block adds about 1,000 more, so a few slices from now it won't fit.
- **The ask:** raise `job.system`'s limit for structured jobs (to 120,000, say; it is cached, so its cost is the first call's), or give jobs a cached reference to a schema Architect already holds (`systemRefs: ["plan/0"]`) so callers don't send it.
- Either is fine for Steward. Until then Steward slims the prompt further if it must (descriptions kept, since Claude reads them).

**C33 accepted (2026-10-13):** both. `systemRefs: ["plan/0"]` (or `plan/0#compact`), expanded by the sidecar from its bundled schema and the live catalogue, refs first for caching, an unknown ref refused (feature `job.systemRefs`); and `job.system` up to 120,000 for structured jobs. In the next release batch with S2 and D1. Steward then sends `systemRefs` when the feature is there and stops sending the schema itself.

**PT2 contract review (Architect main 521fb2d, "Phase 6c slice PT2"; sent 2026-10-13):** it fits what Steward needs for "add a floor to my house". The coordinator's Noah-level calls (no refund for adopted blocks, no placing elsewhere, no containers or entities, 10% placers or an op) match his direction.
- **SQ-PT2-1 (no entrance):** adopt with the guessed front and `frameGuessed`. Farms and walls are what players will ask about ("add a scarecrow to my farm", "extend my wall"). Steward never gives an approach to a guessed front.
- **SQ-PT2-2:** "claim first, then adopt" is enough. Steward keeps its own name and role in `ext`. A fence further than 2 out is its own build; several builds in one site can wait.
- **SQ-PT2-3 (one part):** fine for v0, with one addition. The player's house as built is theirs, and a change request should add to it, not restyle it. Please report on a version of an adopted entry how many of v1's captured cells it changes or removes (`capturedChanged`, with the count of v1's cells), in the version result and on `checkDelta`. Steward then shows it at approval ("this changes 140 of your house's 2,300 blocks") and tells Claude to keep the house as built unless the player asked otherwise. Parts (walls, roof, storeys) can come later.
- **SQ-PT2-4:** refuse `PROVISIONAL`. The steward says it will wait until the build is finished, and retries on the `BUILD_CHANGED` that promotes it.
- **SQ-PT2-5:** keep the entry when it has 2 or more versions: yes.
- **SQ-PT2-6:** yes, `Build.siteId`. The settlement view shows the house once, as the player's, with its versions.
- **SQ-PT2-7:** yes. Steward never uses `OVERWRITE` on an adopted site unless the player asked for that change by name.
- **Steward's side, for the record:** the adopted site is listed under the settlement's owner like any building (its `ext` carries Steward's role and label); removing it in Steward's screens says "the steward stops looking after it; your house stays"; undo of the adoption is an ordinary history entry.
- **PT2 frozen with Steward's review (Architect main b8a8908):** a guessed front, never refused; `capturedChanged` / `capturedTotal` on every version of an adopted entry and every `checkDelta` (gated); `PROVISIONAL` refused; `Build.siteId`; the implicit consent as drafted. It comes to Steward as an RC (it touches the journal and undo).

**v0.28.0 (C33, API 1.25.0; published 2026-10-13, checked after release): pass.** The plan writer sends `systemRefs: ["plan/0#compact"]` where the helper has `job.systemRefs`, inline otherwise; stub 41 (composition by reference), free 40.

**PT2 RC (v0.29.0, API 1.26.0; slice/PT2 5ba596f8; sha256 e5d473bf…), consumer check 2026-10-13: pass** (stub 41, free 40; Steward did not call adopt yet). **Published v0.29.0, checked after release: pass**, and Steward uses it: `/steward builds <id> adopt <build>` (the player as the consent's actor; ext settlement, role, build), history `BUILD_ADOPTED`, undone like a placement (Architect un-adopts, the build stays); free e2e 42 (adopt a claimed hut, undo, the hut stands), stub 41. **C34** (a command-built, claimed hut with a door classified OTHER with no entrance): Architect's bug, patch v0.29.1.

**v0.29.1 / v0.29.2 (C34, C34b) and C35, 2026-10-13.** C34b was Steward's fixture: the e2e's player kept flying after spectator mode and built the test hut at y 100; Architect now also counts a door near a lifted build's lowest row, and the fixture stands the hut on the ground (it comes back a house with its door). Steward on 0.29.2: "add a floor to my house" works through talk on an adopted build (versionOf on the captured entry; the offer always asks and says "It changes 1 of the 245 blocks of the build as you made it"); stub 42, free 42. **C35** (Architect, patch v0.29.3): a captured design threw on a supplied palette; Steward now sends no bible for an adopted site (it keeps its own style), and suggested new cells match the build's own materials unless the change asks otherwise.

**v0.32.0 (D2: boundary districts and outposts, C27, C28; API 1.29.0; published 2026-10-14):** to be checked after plan v1's e2e (one e2e at a time on Steward's ports).

**K1 contract review (Architect main 35cb86a8, "Phase 7a slice K1": walls, gates, rope bridges; sent 2026-10-14).** It fits. Coordinator's Noah-level calls (wall height 6, thickness 2, crenels, open arch; slab-deck rope bridges with chains; survival left to RS; K1a and K1b in parallel) match his direction, with taste at the look step.
- **S-K1-1:** auto gates on every crossing; an explicit sealed wall (`gates: 0`, or closed on purpose) stays closed.
- **S-K1-2:** a missed join (a gatehouse beside the wall, not in it) is a deviation, not only a note, so the approval and Claude's repair see it.
- **S-K1-3:** lenient `params`, with every unread member in the part's notes (Claude's repair round reads them).
- **S-K1-4:** rope bridges' movers inferred as T2 (villagers may use them); ladders stay player-only.
- **S-K1-5:** `auto` may grow; no `moves` member (Steward re-checks at Start before approving).
- **S-K1-6:** a pending part that becomes refused is offered as a repair, never a stopped build (plan v1 builds pending parts as not required). Reserve an id for elevated lots now.
- **S-K1-7:** unknown reason codes pass through verbatim.
- **Asked:** whether a lot's box height is the bound `realiseApproved` enforces on a design, and a stable rule for `costRange`'s top, so Steward caps designs before paying for a realise.

**v0.33.0 (C37 orientation, C37b `Entry.cells()`), v0.34.0 (C38 `planCatalogue()`), v0.35.0 (K1: walls, gates, rope bridges; K1-target `wall.around {feature: {region}}`), 2026-10-14 to 2026-10-17.** Plan v1 builds on 0.33.0 (stub 44, free 42); 0.34.0 checked after release (stub 44, free 42); 0.35.0's check is running.

**T3 contract review (Architect main b528dc9b, "Phase 6c slice T3: bridges and tunnels"; sent 2026-10-17).** It fits.
- **S-T3-2:** streets, roads and paths fall back to a bridge when every route crosses water.
- **S-T3-3:** `WATER until H` is acceptable; its arch or rope `wouldFit` is offered as a repair.
- **S-T3-4:** a tunnel under flat ground stays `unsupported {tunnel.under, until 7b}` (a path repair would drop the secret).
- **S-T3-5:** posterns count as gates, never under a sealed wall, never made by auto.
- **S-T3-6:** on `incomplete VOLUME` Steward tells the player where to stand and checks again once; asked for a check option that prepares the plan's tiles. Rock `ARTIFACT_STALE` is fine (one retry). R19 `via` read leniently.
- **S-T3-7:** reserve `connector.tube` (until UW) and a moving-bridge id.
- **S-T3-8:** enough; asked for the bridge's span and the gate or postern it lands at.
- **T3 frozen with Steward's review (Architect main 3bed28fa):** the bridge fallback; tunnels under flat ground unsupported until 7b; posterns as drafted; a check option `load: "loaded" | "generate"` (generate prepares the plan's tiles for the volume pass, within the caps: Steward uses it at Start); a tunnel's `via` read leniently; `connector.tube` (until UW) and `connector.drawbridge` (until mechanism) reserved, recorded drawbridges answer unsupported; `crossing` gains `span` and `lands`. T3a and T3b release as one batch, API 1.33.0, catalogue 6.

**v0.36.0 (T3: bridges and tunnels; API 1.33.0), checked after release 2026-10-18: pass** (stub 47, free 42). Steward's plan checks load a tunnel's corridor (`load`, LOAD_BOUNDED 512) and need >= 0.36.0.

**H contract review (Architect main b1863046, "Phase 6c slice H: contained fluids", the new section; sent 2026-10-18).** It fits.
- **S-H-2:** the farm pond in the lot's yard, but explicit (`farmPond: true`), off by default: decorative farms get it (Steward's plan writer sets it); working farms' hydration spacing is a later block (Noah).
- **S-H-3:** `channel.moat` now, `channel.course` for HY, roads over a moat on T3's bridge fallback to the wall's gate.
- **S-H-4:** wet writes as a fallback only (byte-identity of plans that fit today).
- **S-H-5:** `feature.water` survey-only; a plan's own pond as `feature.part`.
- **S-H-6:** the realise semantics are acceptable; asked for the report's `fluids` per body `{part, state: settled | drained, reason, at}`. RC check before release.
- **S-H-7:** build the waterfall now; the hollow behind it shows as not built (a note or deviation, or a pending part until 7b).
- **S-H-8:** enough, with the per-body fluids report.
- **H frozen with Steward's review (Architect main 68320b73):** `farmPond: true` on a lot or zone (off by default); wet writes as a fallback only; the realise report's `fluids: [{part, state: settled | drained, reason, at}]`; a waterfall's hollow pending as `form.hidden_room` (its own part) or an `adjusted` deviation (inside the waterfall), never dropped. One RC for H-core, H-a and H-b at API 1.34.0, checked by Steward before release.

**H RC (v0.37.0-rc1, API 1.34.0; integrate/h d29fe79d; sha256 7748637f…), consumer check 2026-10-18: no regressions, one finding.** Free 42; stub 47 of 48: every existing flow passes (plan v1, the waiting-part offer, undos). A new flow (branch h-rc, `farmPondFlow`, `fixtures/plans/farm_pond.json`: a standard zone with `farmPond: true` and a flat_village template with a farm lot, on a superflat claim) checked READY and realised with no fluids stage and no pond. Asked Architect for the zone answer's notes.
- **H RC2 and RC3:** rc2 (farm lots found by their inferred villager use) failed `realiseApproved` ("Array must have size 1, but has size 4": the farm pond's guard runs were nested, not flat); **rc3 (sha256 542a13da…) passes**: stub 49 (the hamlet's pond settles before PLACED, an undo drains it first), the other flows as on rc1 (free 42).

**v0.37.0 (H: contained water; API 1.34.0), checked after release 2026-10-19: pass** (stub 49 with the farm pond, free 42). Steward needs >= 0.37.0; its plan writer sets `farmPond` on homely farms and the steward says when a body was drained (the region's `architect_mc:fluids`).

**VOC and HY contract reviews (Architect main 6dbbc51b; sent 2026-10-19).** Both fit.
- **VOC:** S-VOC-2 add VOC-c (`rule.palette` measured, refused at the realise, Steward's RC check) after VOC-a and VOC-b; S-VOC-3 bible-named blocks exempt, precious counted everywhere at cap 0; S-VOC-4 refusals block, findings on the card when the bible opts in; S-VOC-5 a typed `rarity` record; S-VOC-6 the vocabulary as a systemRef (`vocab/1`, `#compact`) rather than a listing call.
- **HY:** S-HY-1 the course shape; S-HY-2 own ids for race and ditch, added only when the card asks; asked whether working farms' in-field watering (a source per 9x9, Noah) is the lot design's or a later member; S-HY-3 footbridges only; S-HY-4 water gates in this batch; S-HY-5 visible weirs; S-HY-6 a report row per reach in the `architect_mc:fluids` shape, the cross-part upstream drain; S-HY-7 enough. RC check before release.
- **VOC and HY frozen with Steward's review (Architect main 23fb4921):** VOC-c its own slice after VOC-a/b; `BibleRequest.Rarity` a typed record; the vocabulary as systemRef `vocab/1` (`#compact`). HY: `channel.race` and `channel.ditch` own ids; water gates (HY-c) in this batch; reach rows in the `architect_mc:fluids` shape; the cross-part upstream drain; RC check confirmed. **In-field irrigation** (a source per 9x9 on a working farm) is an Architect member on a farm lot (e.g. `irrigate: true`, as H bodies), a small slice after HY; the plan writer leaves it out until then. Versions: VOC 1.35.0 (no RC), HY 1.36.0 (RC).

**v0.38.0 (VOC; API 1.35.0), checked after release 2026-10-19: pass** (stub 51, free 42). The plan writer keeps walls clear of a plan's own water until KC.

**Batch review: KC, CU, IRR, VOC-c (Architect branch docs/batch-kc f79a03e7; sent 2026-10-19).** All four fit; the order (KC alone as a patch, then CU, IRR and VOC-c on one RC) is fine.
- **KC:** the later part moves, never the body; watch cells strict; a patch without an RC.
- **CU:** ours is the block we wrote in any weathering or wax stage (a player record wins); refund the standing block; farmland and dirt interchangeable; pre-release check.
- **IRR:** `irrigate` only for working farms the card asks for (homely ones keep `farmPond`); the reserved cells on the artifact's `$.lots[]` (and a typed `reserved` on `DesignRequest` if the design job honours them), since Steward designs plan lots itself; a breach reported in `architect_mc:fluids`; sources removed with their lot; RC.
- **VOC-c:** the shape is fine; refuse at realise as the safety net, and offer a pre-check of a library entry against the rule (or its histogram) so Steward drops one lot rather than the village; re-judge after `versionOf` (a settlement rule); `NO_FEATURE` for prose-only rules (offered to Claude as a repair); RC.
- **Batch frozen with Steward's answers (docs/batch-kc 23c42d43; on main after HY):** IRR's reserved cells on `$.lots[].irrigation` (world and lot-relative) and a typed `DesignRequest.reserved`, a field breach as its body's `architect_mc:fluids` row, sources removed with their lot (drain first); VOC-c's realise refusal plus a cheap pre-check (an entry's family histogram, or an entry checked against a rule), palette rules re-judged after `versionOf`. CU builds now; KC and VOC-c after HY; IRR after KC and CU. KC ships alone as a patch; CU, IRR and VOC-c on one RC.

**HY RC (v0.39.0-rc1, API 1.36.0; integrate/hy 0cd8ed12; sha256 58d5e678…), consumer check 2026-10-19: pass.** Stub 53, free 42. A new flow (branch hy-rc, `ditchFlow`, `fixtures/plans/ditch_hamlet.json`: a farm with `channel.ditch` along it) placed after the ditch settled and undid with it drained first; the farm pond and every other flow pass. Multi-reach courses, the upstream drain, water gates and per-reach staleness are Architect's hy-ingame (Steward's e2e world is flat).

**v0.39.0 (HY) and v0.39.1 (KC), checked after release: pass** (stub 53, free 42). The plan writer writes courses, races and ditches; its keep-walls-clear workaround went with KC.

**Batch RC (v0.40.0-rc1: CU, IRR, VOC-c; API 1.37.0; integrate/batch-cu 631f89c0; sha256 7d319c68…), consumer check 2026-10-20: pass.** Stub 55, free 42. On branch batch-rc Steward carries each irrigated lot's reserved cells (the artifact's `irrigation`) on its design request (`DesignRequest.Reserved`) and never copies it; a working wheat farm (`irrigate`) carried 28 reserved cells, settled before PLACED and undid with its sources drained first. Copper and palette rules are Architect's in-game gates (Steward places no copper of its own and writes no palette rule yet).

**FL, F3, S3 and RS contract reviews (Architect main c70902e6, the last of 6c; sent 2026-10-20).** All fit; the order (FL-core, F3-a, S3-a, RS-a in parallel; FL and S3 without an RC, F3 and RS after Steward's) is fine.
- **FL:** orientation read on grids only (plan v1 forces `ew` on village streets only, and builds east- and west-facing lots); other layouts under `around` as a ring; central lots in the hub; a waiting zone is a re-plan, not an offer; keep the `street_` stem, and asked for a street flag on region roads' ext (Steward's additions match only `street`-keyed roads today); no RC; ship spiral and outline; the compact bound at 84,000.
- **F3:** coral its own part on its body; five ids; Steward passes `LOAD_BOUNDED(512)` for cave forms as for tunnels (asked which ids, or a catalogue flag); hard canopies for v1; RC; no extra margin from builds.
- **S3:** `terraces` on a zone answers the "terraces inside zones" ask (the plan writer drops its beside-only rule); leave terraced_hill until re-recorded; no RC.
- **RS:** tile entries and a per-part map, provided a region's build progress shows when it is done and its construction waits raise SITE_WAITING and ITEM_WAITING (region, part) as sites do; whole-bill runs, quarter capacity, wait for support; no new state if the build view says done; mode from the world, STALE across modes; lava refunded by scooping; RC; no per-mode catalogue. After RS, Steward does plan v1 for Supplied builds and the waiting-part offer in survival.
- **FL, F3, S3, RS frozen with Steward's answers (Architect main e755d663):** street roads carry ext `architect_mc:street {zone, district?}` (V, D1/D2, FL; old regions keep their keys); catalogue entries needing a frozen volume carry a `volume: true` flag (Steward passes LOAD_BOUNDED(512) from it, tunnels included); `RegionView.build` gains `done` and `percent`, region construction waits raise SITE_WAITING and ITEM_WAITING with region and part ids, requests start at PLACED and "built" is `done`. FL and S3 release without an RC (S3 after Noah's gallery); F3 and RS each with Steward's RC.

## Round 11 (2026-10-20): Steward's districts on region districts

Steward's districts move onto Architect's D1/D2 region districts (docs/DISTRICTS.md; Noah's decisions there): a district beside a region town is
a $0 reservation, realised as its own region (`Regions.plan("districts", beside = <town region>)`) at its first building; along districts and
outposts are built at once as a small quarter. Steward recognises region streets by the `street_` stem and, once FL ships, the
`architect_mc:street` flag (fd2e507).

- **D-S1. A join that reaches the town's street wherever it is.** V's street spans its outer lots ±4 (`kit/lib/region/village.mjs:324-328`),
  so in a town that isn't full both ends sit deeper inside the claim than the 16-column seam, and every district beside it (plain, along,
  outpost) fails `NO_JOIN`. Asked: the join may run inside the standing region's claim, routed around its footprint (lots and aprons,
  roads +1, paths), to the nearest point of its street network: a street end (continuing the street along its line) or a street's side (a
  T junction, so a district can sit north or south of an east-west village). Written by the district region as today (its cells, its
  undo, `join_external`); the district's own claim keeps the seam rule.
- **D-S2 (later, with 6d).** Lots added to a standing district region (its street extended), so along districts and outposts can grow.
- **D-S3. The land a district region takes, for every kind.** The district's mask, its join's cells and an outpost's corridor, as row runs
  and a rect, in the region view's ext (as `architect_mc:corridor` already is for outposts). Steward owns exactly that, never the request
  envelope (an outpost's runs from the town through the gap).
- **D-S4 (a question).** Steward checks a beside reservation at founding with a $0 `Regions.plan` holding one placeholder lot, never
  realised, so that Architect judges the seam and the join. Plans aren't pruned. Is there a way to discard an unrealised plan, or would
  you rather offer a dry beside check (a request claim and a standing region → fits, or the named refusal)?

GPT's review of the design (docs/DISTRICTS.md §8) found the 16-column seam can't be computed by Steward reliably, hence D-S1 and D-S4.

**Architect's answer (2026-10-20):** round 11 fits, as slice **D3: districts beside a standing region town** (contract drafting, for
Steward's review). D3 takes D-S1, with the ownership and undo of join cells written inside the town's claim settled in the contract
(the town removed under a standing join, the district removed). It also takes D-S3, and D-S4 as a dry beside check that writes and
keeps nothing (the contract will say what exists today for discarding unrealised plans). D-S2 stays with 6d. Order: after FL merges
(D3 uses its street flag and frontage), alongside or after RS, before the 6c close-out; a few days out.

**D3 contract review (Architect main 40262c67, "Phase 6c slice D3"; sent 2026-10-20).** It fits DISTRICTS.md. One finding, and
the eight answers:
- **Finding: a finished `PARTIAL` town.** §4.4 refuses a realise beside a town in `PARTIAL` with `OVERLAP_BUSY`. But a Steward town that
  ended with a failed lot stays `PARTIAL` for good: Steward logs it as the town and builds on it (`SettlementRunner.watchVillage`).
  The same goes for a `FAILED` region with cells standing. D1's plan-time check accepts both. Asked: refuse only `PLACING`,
  `PREPARING` and `REMOVING` (in flight), and accept `PLACED`, `PARTIAL`, and `FAILED` with cells standing as a beside town, at plan,
  at the dry check and at realise alike. Add a partial town to `d3_town`'s fixtures.
- **S-D3-1** Streets only. Yes: Steward's own street extensions are held cells to route around.
- **S-D3-2** As proposed. Steward doesn't read the graph yet.
- **S-D3-3** Architect reports both, as proposed. The settlement's claim outlives its town region, so Steward treats `inside` as town
  land in both cases and never owns it as district land.
- **S-D3-4** Yes, a snapshot. The first building plans again under `dplan`.
- **S-D3-5** Yes. Steward tells the player a walled town takes a district once gates can join (7a).
- **S-D3-6** Yes.
- **S-D3-7** Yes, Steward's RC check: a partly built V town, a beside reservation by `checkBeside`, its first building, undo in both
  orders and after a restart, an along district, and the `PARTIAL` town above.
- **S-D3-8** Yes, cheapest.
- Steward's notes for itself: its additions in the town must keep off `land.inside`. Its town-build exclusions read the land runs,
  never a region's tile boxes.
- **D3 frozen with Steward's answers (2026-10-20).** The finding is accepted: only in-flight towns (`PREPARING`, `PLACING`, `REMOVING`) refuse
  `OVERLAP_BUSY`. `PLACED`, `PARTIAL`, and `FAILED` with cells standing are beside towns at plan, `checkBeside` and realise alike. `d3_town`
  gains a partial town with a district realised beside it. Steward's RC list is S-D3-7. D3 builds once FL merges.

**Batch 7a contract review (Architect main 7e7cd2d5: foundation, CL, K2, CW, RK, LF, SA, K3, GJ, FB; sent 2026-10-20).** All fit. 7a
lands before 6d, so D-S2 (growing along districts and outposts) waits a little longer; that's fine.
- **Batch:**
  - **S-7a-1** `auto` doesn't grow: yes.
  - **S-7a-2** `NO_CLIMB` with a `path` repair: yes. A refused required part goes to the plan writer's repair, as S-K1-6.
  - **S-7a-3** `span_bridge` a supported alias: yes.
  - **S-7a-4** `branch_walk` until 7b: yes.
  - **S-7a-5** rails until CR: yes. mining_town's `rail_1` keeps waiting.
  - **S-7a-6** nav its own slice when asked: yes.
  - **S-7a-7** M8 decided with FB's counts: yes.
  - **S-7a-8** ladders, lifts and walks player-only by kind: yes.
- **Slices:**
  - **S-CL-1** a walked climb `adjusted`: yes. **S-CL-2:** yes.
  - **S-K2-1:** yes. **S-K2-2:** yes.
  - **S-CW-1** ship the face form: yes.
  - **S-RK-1** `LOAD_BOUNDED(512)` from `limits.volume`: yes. It's the same rule as F3.
  - **S-LF-1:** yes. **S-LF-2** the RC: yes.
  - **S-SA-1:** yes.
  - **S-K3-1:** yes.
  - **S-K3-2** a brief line plus the bind check for now: yes.
  - **S-K3-4:** yes. Plan v1 reads `$.lots[]` by id and never checks the zone's rect (`PPlan.toPlan`).
  - **S-K3-3** the RC: yes.
  - **S-GJ-1** standing gates only: yes. **S-GJ-2** the RC: yes.
  - **S-FB-1:** yes.
- **Steward's side when these ship:**
  - the plan writer learns that a climb needs a rise (stair, ladder, lift, rock stair) and that `span_bridge` is a bridge;
  - K3: a wall lot's `onWall.passage` goes on its design as `DesignRequest.reserved`, its outer faces as a brief line, and wall lots are
    left out of the street extent plan v1 derives;
  - RCs on LF (7a-2) and on K3 with GJ (7a-3).

**Steward's refinements and the re-record (2026-10-20).** Noah asked both sessions what to change before implementing. Steward's list:
- bring 6d's growth core (lots added to a standing region, its street extended, towns as well as districts) ahead of 7a-2 and 7a-3;
- the same-key stale retry and pins that expire with their opKey;
- realised provenance for every region;
- a settlement-wide look lint;
- the lamp-post undo miss;
- `discardPlan`.

After the re-record (12 plans, $0.65, refused 26 → 9 across the 59; mod/DEV.md), Steward reported two findings. **Both were accepted
into the 6c close-out fixes,** with the same-key stale retry, expiring pins and `discardPlan`:
- **A route to an unplaced lot** answers `blocked {dependency}` naming the lot and its zone, instead of `refused NO_ROOM`.
- **`rule.palette` gains `scope: "lots" | "all"`** (default `all`, as today); `exempt` also takes `ways` and `forms`. **Steward's plan
  writer uses `scope: "lots"` for "only copper" building asks once it ships.**

Architect takes the 12 re-recorded plans into its asks fixtures at the close-out.

**Batch 6d contract review (Architect main 3b10e4b3: RV v3 additive, GR, FS, LC, AD; sent 2026-10-20).** RV v3 and GR fit Steward's
growth loop and DISTRICTS.md. Four points to settle, then the answers:
1. **The region's state after a revision.** While revising, the region reads `PLACING`. Afterwards it should read its own state again,
   whatever the revision ended (`PARTIAL`, `FAILED`); the revision's outcome lives in `RevisionView`. Otherwise one failed grown lot
   would flip a town to `PARTIAL`, and Steward's town lookup and D3's standing rules read the region's state. Asked to confirm.
2. **A candidate waiting on designs.** Between `planRevision` and the realise, Steward designs the new lots (minutes to hours, with the
   player's massing approvals). Steward runs one growth at a time per settlement, so competing candidates come only from an undo. If a
   re-plan is needed, Steward re-plans with the same lot sizes and needs boxes of exactly those sizes back (a front may change; Steward
   turns designs). Asked: GR never shrinks a lot unasked.
3. **Grown street roads.** Their keys are `rv<rid>:road:...`, so Steward recognises them by the `architect_mc:street` flag (fd2e507).
   Asked to confirm the flag is on every grown street road, also in towns realised before FL whose standing roads have none.
4. **AD on load (S-AD-1).** Steward would rather adopt lazily, at a settlement's first growth, than migrate every old settlement on load:
   the migration risk then falls only where it's used. Asked: site ids unchanged by adoption, so Steward's history keeps naming them.
- **RV:**
  - **S-RV-1** yes. **S-RV-2** yes: Steward treats `REGION_LIMIT` like `NO_ROOM`, as full, and proposes a district. **S-RV-3** yes.
  - **S-RV-5** deferred. **S-RV-6:** no near-term flow needs removal or change. **S-RV-7** yes. **S-RV-8** yes. **S-RV-9** no change.
  - **S-RV-10** yes, RC. **S-RV-11** yes.
  - **S-RV-12** yes: Steward waits on its own settlement's busy window.
  - **S-RV-13** yes: no more `street#n` pieces on region towns once GR ships.
  - **S-RV-14** refused is fine.
- **GR:**
  - **S-GR-1** frontage first. **S-GR-2** yes: one building, one revision.
  - **S-GR-3** yes: `dgrow`/`drealise` for districts, and `vgrow:<build>` and `vgrowrealise:<build>` for towns.
  - **S-GR-4** yes. **S-GR-5** yes.
  - **S-GR-6:** no dry check. Steward decides at acceptance by planning (`NO_ROOM` → full → a district), with `discardPlan`.
  - **S-GR-7** yes, the RC shared with RV's.
- **FS:** S-FS-1..4 as proposed. **LC:** S-LC-1..4 as proposed. **AD:** S-AD-2 yes; S-AD-3 yes, with point 4; S-AD-4 yes.
- **Steward's side when RV and GR ship:**
  - a growth's history entry names its revision (`revision:<region>:<rid>`), and its undo is `undoRevision` to the position below,
    never `Regions.remove`;
  - grown lots go through plan v1's four-front adapter;
  - `District.growable()` becomes "growth didn't answer `NO_ROOM`" for every kind;
  - additions in region towns move from Steward's own street pieces to GR.
- **6d frozen with Steward's answers (Architect main 48f7129c, 2026-10-20).** All four points are confirmed and binding:
  1. A revision reads `PLACING` only while it writes; the region then reads its own state again, and the revision's outcome lives only in
     `RevisionView`.
  2. GR never shrinks a lot unasked: a re-plan with the same sizes returns boxes of exactly those sizes (a front may change) or refuses
     `NO_ROOM`. A candidate stays valid while Steward designs, unless an accepted revision moves the head (`parent_moved`).
  3. Every grown street road carries `architect_mc:street`, pre-FL towns included.
  4. Adoption is lazy, at a settlement's first growth, and site ids never change.
  Steward's opKeys are recorded (`dgrow`/`drealise`, `vgrow:<build>`/`vgrowrealise:<build>`). RV and GR share one RC, after D3 and 7a-1.

**Undo fixes coming as patch 0.40.1 (Architect, 2026-10-20; FYI, post-release check when tagged).** An undo model tester and a review
found gaps, fixed in the patch:
- the region drain is holder-aware, so a Steward block in an Architect pond, stream or irrigation cell survives the region's or lot's
  removal;
- a crash between a removal's journal commit and its queue save no longer leaves a drained region `PARTIAL` (single-site removal next);
- stacked removals settle;
- a recovery hazard that could reactivate a group removal's members is fixed.

**Stated limit (Noah):** a kill or crash with unsaved chunks can leave a build half placed or half restored while the journal stays
correct. A diagnose-and-repair command is queued as a small contract. Normal stops are unaffected.

**F3 RC (slice/F3 d734e4a1; its built jar's code = that head), consumer check 2026-10-21: pass.** Stub 59, free 42, on Steward branch
`f3-rc` (0efca7e):
- the check passes `LOAD_BOUNDED(512)` for any part whose catalogue entry has `limits.volume` (S-F3-3; the tunnel rule on an older Architect);
- new flow `groveFlow` (`fixtures/plans/grove_pond.json`): a town zone, a giant oak 40–48 tall, a built-edge pond near `claim_n` with coral in it,
  through `checkPlan` (4 parts fit) and `realiseApproved`. The oak has 1,513 logs and 2,348 persistent leaves, none floating; 47 coral cells.
  After a player's edit (a crown leaf to cobblestone) the undo gives back the 193x121x193 claim exactly but that one cell.
- **Side finding** (main's kit too): a pond placed near the stone was refused `LEAK`, its reach meeting a lot. Architect queued pond placement
  keeping its reach off lots + 1 in the 6c close-out.

**RS-a RC (slice/RS 529138d1; the RS-g1 jar at 066f707b, the same code), consumer check 2026-10-21: pass** (the narrow check that gates its
merge, Steward main with plan v1 creative-only). Stub 57, free 42. Steward's Supplied flow passed:
- bill: 629 items, as owed;
- the material request said;
- 629 delivered and consumed;
- every unit refunded on undo.

**Supplied plan v1 on RS** (Steward branch `supplied-plan`, COMPOSITION section 12; GPT-reviewed twice, no blocker left). Stub 61 and free 42 on
the RS jar. In a survival world (toggle on, default mode not creative: Architect's region rule), a walled town:
- composed plan checked in survival with its bill said (3,542 stone bricks and 14 more kinds);
- stockpile made first, its crates and a ring protected as the settlement's area (Architect's recommendation; `realiseApproved` accepted it
  inside the claim);
- realised in CONSTRUCTION;
- fed half, paused, the wait named, then fed until `build.done` (5,550 items);
- undone with every unit refunded.
It merges when RS is released, with the version bumps. Not yet said: stage waits, and tile and road item waits.

**RS-b RC (slice/RS-b 9e08cb98; jar from 6132469a, the same mod, kit and sidecar code), consumer check 2026-10-21: pass.** Branch
`supplied-plan`, stub 61, free 42: the existing Supplied flow and the Supplied plan flow, as on RS-a. RS-a and RS-b release together;
`supplied-plan` merges with that release.

**v0.40.1 (undo hardening A + UMT, drain holders, lamp-path, realised provenance; API 1.37.0 unchanged), checked after release 2026-10-21:
pass** (stub 57, free 42). Steward builds against 0.40.1 and still requires >= 0.40.0 (no API change).

**v0.41.0 (API 1.38.0: F3, S3, FL, RS-a, RS-b, RS realised provenance; catalogue 11), checked after release 2026-10-22: pass** (stub 63,
free 42). Steward needs >= 0.41.0; `f3-rc` and `supplied-plan` merged (5c842ff):
- the grove: the oak 1,482 logs and 2,874 persistent leaves, none floating; 58 coral cells; the undo exact but for the player's cell;
- the Supplied plan: bill, the stockpile area, a pause with the wait named, 5,550 fed to done, 5,550 refunded.

**6c close-out A contract review (slice/close-out-a ca36e7b4; round 12 plus RS follow-ups; sent 2026-10-22).** Accepted, with one change:
- **The sweep's clock.** A Steward build can wait days or weeks on the player's approvals (massings, placing) while the player is away. A
  flat-village build holds a keyed plan and no pin; a plan-v1 build holds a keyed pin. Ages counted from creation would sweep an
  in-flight build after a month away. Asked: age plans and keyed pins from their **last use** (`planByKey`, a pin or re-pin, a realise
  attempt). Steward re-pins, and looks its keyed plans up, at every world start for each build it is resuming, so a live build never
  ages out. If Architect would rather keep creation time, Steward needs a way to keep a plan alive (a keyed plan pin, or a touch).
- **S-COA-1:** `over` beside `scope` is fine. The plan writer writes `over: "lots"`.
- **S-COA-2:** all of a key's pins end at its realise, as default; 30 days is enough with the last-use clock above.
- **S-COA-3:** `OVERLAP_BUSY {realised | busy | pinned}` is fine; 7 and 30 days with the last-use clock.
- **S-COA-4:** `parts` plus `part` is enough. Steward names the part, else "the build".
- **S-COA-5:** yes, the RC: a Supplied plan v1 build with a stale retry under one key, keyed pins, `discardPlan` of an abandoned check,
  waits said from `waitsFor.say`, and a palette `over: "lots"` plan.
- Steward's side when it ships: §12 as listed.
- **Close-out A frozen with Steward's change (2026-10-22).** Plans and keyed pins age from their last use (`planByKey`, a pin or re-pin, a
  realise attempt, a lookup by key), stored durably. **Steward's side:** re-pin and look up its keyed plans at every world start for each build
  it resumes. S-COA-1..5 recorded. RC to come.
- **Close-out A §14 (wording fix, 2026-10-22):** C20 stands. A `REMOVED` region frees its realise key, so the same key realises afresh after an
  undo. Nothing on Steward's side relied on the old wording: its keys are build-scoped, and `findVillage` drops `REMOVED`. A build whose region
  was removed by hand mid-placing now realises a fresh one when it resumes.

**Close-out A RC (slice/close-out-a 179b8f92, jar built by Steward; 5ede11ff docs only), first run 2026-10-22: free 42, stub 61/63** on
Steward branch `coa-rc`. Working: the stale retry under one key (`ce9e0eb8` → `0c11af7f`, both under `.vrealise`), the re-pin at a restart,
copper `over: "lots"` (fits; over everything `TOLERANCE`), `discardPlan` of a cancelled flat village, stage waits said. Findings, all Architect's
(coordinator's plan):
1. a survival region's props stage held for drift after a stale retry (93.6% within 2): close-out A fixes it before merge, a 0.41.x patch if
   RS had it;
2. ~~`/architect survival off` under a building region generated about 1,000 chunks outside its claim~~ **withdrawn:** those were the e2e's own
   `/spreadplayers` to the next flow's spot. Architect's "generated during realise" log charged them to rg10, still PLACING because of 1. The
   log now charges a region only for chunks in its claim + 2 or its tickets (Architect's fix), and there is no hazard patch;
3. wait wording: roads by their route's label or "the street"/"the road", a zone named like the town "the town";
4. the grove oak had one floating log: a fix agent is on the seed;
5. one sentence per part: deduped at the source.
Steward's side added meanwhile: a held stage is said once and `/steward approve` builds it as planned (`APPROVE_STAGE`); a region waiting for
its ground says where to come nearer. Re-run on the new head when 1, 3 and 5 are in.

**Close-out A RC re-run (slice/close-out-a fba370c2, findings 1, 3 and 5 fixed; jar built by Steward), 2026-10-22: pass.** Free 42; stub 71/72,
the one miss Steward's own probe (`dev.coa.artifact` lists pins' owners, not keys or times), reworded on `coa-rc` and shown passing by the
run's own log. In the Supplied plan flow:
- restart mid-build, then the plan used again at the resume (pinned before and after);
- the stale retry under one key;
- no stage hold (every drift check ok), on to `build.done` (5,550 fed, 5,550 refunded);
- stage waits said once in the new words ("The town's street waits for the town to be built").
Also passing: copper `over: "lots"`; `discardPlan`, then `planByKey` and `checkByKey` both empty; the grove (no floating log).

**7a foundation questions (2026-10-23).**
- **The compact plan/0 at 96,000** (92,556 with the seven strict connector defs, against S-FL-8's 84,000): OK, provided the helper's job
  system-prompt cap holds `plan/0#compact` + `vocab/1#compact` + the caller's text (about 10,000) with margin. **Answered:** the 120,000 cap
  (`JOB_SYSTEM_MAX`) covers only the caller's own `system` field on structured jobs; systemRefs are expanded at run time outside it, with no cap
  on the composed total (about 110-130k characters, 30-40k tokens). No change needed.
- **CL's climb deviation** `{what: 'climb', value: <rise>, kind: 'walk', rise}`: OK. Steward shows deviations and doesn't parse `value`.
- Noted: `span_bridge` answers as `connector.bridge`; `rail` stays reserved until CR. v0.42.0 (close-outs A and B, the stage-hold and
  floating-log fixes) is in final release testing, with its tag after Noah approves.
- **7a CL (FYI, 2026-10-23):** a climb's detour is judged against its grade-limited minimum, max(straight, rise / max grade), so a normal
  switchback answers `fits` and only a wandering one gets `{what: detour}`. Ordinary routes keep T2's rule. No conflict: Steward sets no
  `detour.max`.
