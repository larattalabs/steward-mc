# Steward: plan

Working name **Steward** (mod id `steward_mc`, rename is open). A Fabric mod for Minecraft 26.3. You found a
settlement, and a Claude-driven NPC steward designs it from your prompt, builds it, runs it, and keeps evolving it
as you progress: a village, castle, sky city, a rift settlement, a "hellish evil lair", a "repurposed meteor crater
mining facility", anything you can describe.

It is a **sibling mod to Architect** (`larattalabs/architect-mc`). Architect is the building generator and survival
construction engine. Steward is the director on top: concept, site, layout, a standing NPC, proactive upgrades,
functional modules, villagers, animals, an inbox. Steward depends on Architect and does not copy it.

Status: **plan only**, written 2026-10-05, revised the same day after Architect accepted asks A1-A9 (see "Architect constraints"). Nothing is built.

## Principles

1. **Generative first.** Claude does the design work from the player's prompt. Presets exist for inspiration,
   as examples and vocabulary for the generator, as a free fallback when a budget or usage limit is hit, and for
   verified functional modules. A settlement is never "pick a theme, place prefabs".
2. **Claude writes programs, code checks them.** Claude writes layout and building programs (the kit, as in
   Architect). Once written they are deterministic, checkable (reachability, support, light, fluids) and re-runnable.
   Claude owns "where" through code and iterates against the checker, not by placing blocks one at a time.
3. **Everything is undoable and logged.** Snapshots, the Architect "never touches player blocks" rule, a change log,
   and permission levels up to full autonomy. Full autonomy is only safe because of this.
4. **Cost and time are a product constraint.** A novel Opus design is about 4-6 minutes and about $1-1.50 (Architect,
   measured twice). Model tiering, parallel jobs, massing-first, a spend meter and a budget cap are phase-1 features.
5. **Vanilla blocks in templates.** Keeps Architect's rule. Any mod block (an "assisted" tier) is a deliberate,
   opt-in exception decided per module (open question 2).
6. **Singleplayer only**, same sidecar architecture as Architect and AgentCraft (local Node sidecar, Claude Agent
   SDK, WebSocket to the mod). Auth: API key is the supported path; the opt-in claude-login flag is personal use only
   (Agent SDK terms). Per Architect's decision log.
7. **Normal-first.** The steward only acts inside a claimed settlement. Nothing runs in a world that has no
   Founding Stone.

## Decisions

| Date | Decision |
|---|---|
| 2026-10-05 | Sibling mod to Architect, own repo (`steward-mc`, local until the owner says to publish). Depends on Architect; Architect changes are welcome and are coordinated with the Architect session. |
| 2026-10-05 | Generative-first (principle 1). Templates/prefabs are inspiration and fallback. |
| 2026-10-05 | The steward is a **server-side persistent entity** (right-clickable, saved with the world, survives relogs), unlike AgentCraft's client-only agents. It is the persona: it walks to the worksite and acts. The mod places the blocks. |
| 2026-10-05 | A prompt is split into a **concept card**: site/form, style/mood, purpose, story (optional), constraints. One prompt box plus optional per-field chips. Claude parses the free text into the card and shows its interpretation; the player approves the card, not the raw prompt. |
| 2026-10-05 | Site/form and style are versioned separately, so a re-skin does not redo the layout. |
| 2026-10-05 | **One sidecar.** Steward is a protocol client of Architect's sidecar (one Node helper, one SDK install, one auth). Steward owns the prompt, schema and card UX; the concept-card call goes through Architect's generic `job.run {schema, prompt, model}` (A8). |
| 2026-10-05 | Architect accepted A1-A9 (architect-mc commit a040e39). Its order is binding for Steward's phases: A9 phase 3, then A8, A1+A2, A3, A7, A4, A6, A5 (see "Architect constraints"). |
| 2026-10-05 | Functional modules (farms, sorters, trading hall) come from a **verified catalog**, with later a simulation-verified path for Claude-designed ones. Claude composes and parametrises; it does not hand-wire redstone unverified. |

## Concept card

The player types one prompt, optionally fills chips. Claude returns a card the player edits and approves. Each field
has regenerate and lock.

```json
{
  "id": "set_ab12",
  "site":   { "text": "repurposed giant meteor crater", "template": null, "terrain": "sculpt|find|flat", "size": "M|L|XL|{x,z}" },
  "style":  { "text": "hellish evil lair", "template": null },
  "purpose":{ "text": "mining facility", "template": null },
  "story":  { "text": "was struck, then abandoned, then mined out", "optional": true },
  "constraints": { "near": "spawn|here|search", "density": "low|med|high", "budgetUsd": 20, "difficulty": "patron|supplied|hardcore|economy" },
  "avoid": [], "references": [],
  "interpretation": "what Claude took each field to mean, and any contradiction it resolved"
}
```

Templates (rift, sky city, castle, ring wall, cliff face; medieval, elven, steampunk; trading hub, fortress, farm
town) only fill a field. A typed custom entry becomes a new macro program (site) or a new style bible (style).

Versions: `siteVersion`, `styleVersion`, `purposeVersion` are tracked separately in the plan store.

## Generation pipeline

```
prompt -> concept card (approve) -> site survey -> style bible + site plan (macro program) -> massing ghost (approve)
      -> per-building briefs -> parallel building designs (checker, render, critique, revise) -> place -> evolve
```

1. **Concept card.** One cheap call that parses the prompt and the chips.
2. **Site survey (mod, read-only data).** Heightmap, biome, water, existing ravines/valleys/cliffs near the claim. Claude may
   pick a spot where terrain already fits ("this valley already works as a crater") or propose sculpting.
3. **Style bible (Claude, once).** Silhouette, palette, roof language, proportions, motifs, what materials mean at each
   tier, lighting mood. It goes into every later job (and caches well). Stored by Architect as a separate artifact
   (`<gameDir>/architect/bibles/<id>.json`) that library entries reference, not inside one entry. Because buildings share a bible,
   a **re-skin can be variants with no Claude call** (about 0.5 s each); Claude is only needed for structural changes.
4. **Site plan = a macro program** in the kit: terrain operators plus a lot/road/utility layout. Claude writes it, code runs it.
5. **Massing pass.** A cheap coarse pass (volumes, no detail) shown as a ghost. The player approves or redirects. Fixes
   "I wanted something different" before the expensive pass.
6. **Per-building briefs.** One per lot, from purpose and layout. Landmarks use Opus, ordinary buildings Sonnet.
7. **Parallel building designs.** Each job sees the style bible and renders of its neighbours (iso/top/front PNGs), runs the
   checker, looks at its own renders, revises. Wall time stays near one design's, within rate limits.
8. **Place.** Through Architect's placement (creative: instant; survival: construction sites), incrementally over ticks near
   the player, with chunk-load handling.
9. **Evolve.** Settlement = a versioned program plus parameters. A change ("add a library wing, make it creepier", or a steward
   proposal) is Claude patching the source with the current state in context. The mod diffs old vs new and shows the delta on
   the ghost (added / removed / changed). Approve, then only the delta is applied.

### Macro kit (new, lives in Architect's kit)
Primitives: `carve`, `platform`, `pillar`, `bridge`, `stair`, `cavern`, `noise`, `ring`, `terrace`, `lot`, `road`, `utility`.
Terrain operators touch natural blocks only, stay inside the claim, show a block count on the ghost, and are snapshotted.
A **macro checker** verifies: every district reachable on foot from the entrance, headroom, nothing unsupported unless the
theme allows it, no unsafe fluids, no dark spawn areas, within the claim and the size cap.

## Perception (what the steward knows)

Read-only tools for the agent, assembled mod-side:
- **Progression tier**, computed in code (wood, stone, iron, diamond, nether, end, elytra...) from advancements, dimension,
  gear and the stockpile. Claude receives facts, never guesses.
- Inventory summary (with the "seeds in your inventory" kind of triggers), stockpile contents, nearby animals and villagers
  (with professions and trades), biome/terrain survey, settlement state and history.
- **Proactive triggers (event-driven, not a timer):** advancement, dimension change, sleeping, seeds/saplings in inventory,
  animals gathering nearby, a stockpile threshold, "since you were away". A trigger starts a short cheap planning call that
  returns proposals; heavy design only starts after approval or per permission level.

## Permission levels and difficulty

| Permission | Behaviour |
|---|---|
| Observer | Asks about everything. |
| Proposals | Approve each project. |
| Autonomous | Approve only demolitions and new districts. |
| Full | No approval. Change log and undo always. |

| Difficulty | Behaviour |
|---|---|
| Patron | Free, instant builds (Architect creative placement). Only where the world allows it: in a survival world Patron is unavailable unless the world toggle is off or the caller has op. A per-settlement setting never bypasses the world's survival toggle. |
| Supplied | Builds from a stockpile; the steward requests materials (Architect survival sites). |
| Hardcore | You gather everything. |
| Economy | You spend resources to unlock modules and tiers (automated farming and so on). |

Difficulty maps onto Architect's per-world survival toggle (`<world>/architect-world.json`, changed with permission 2). The Architect API accepts a placement mode only within what the world allows.

## Villagers and animals

- Villager modes (configurable): start with some, bring them near, or breed them in the housing. Housing, bell, workstation
  lots, population cap, golem defence.
- **Trading hall:** villager cells with job-site blocks. The integrated server can read `Villager.getOffers()`, so finding
  high-value trades is a reroll loop (place/break the job block). Authentic mode does it slowly through the steward;
  assisted mode instantly.
- **Animals:** a cluster of passive mobs near the player triggers an offer: an animal wing (pens per species, breeding,
  harvest, hopper to chests, smelting). Moving animals needs approval.

## Functional modules

Verified catalog, each with a footprint, connector ports (output hoppers into a central **spine**) and parameters:
crop farm, sugar cane, melon/pumpkin, animal pen with breeding and harvest, storage hall with item sorters, smelter array,
villager trading hall. Claude composes and parametrises them and adapts footprints and style; the wiring is verified.
Each module is tested in the dev client before it ships (redstone and farms are version-sensitive in 26.3).

Later: Claude-designed modules pass a **simulation gate**: run the singleplayer server N ticks in a scratch area and count
the output before the design is accepted.

## Interface

- **Founding Stone** item: placing it opens the concept card form, claims a radius, and spawns the steward.
- **Concept card form**: one prompt box, optional chips per field, the interpretation, regenerate/lock per field, spend estimate.
- **Inbox + HUD + hub** from AgentCraft: decisions with options, a HUD line while anything needs you, "since you were away",
  toasts, a change log with undo. Remote use: an in-game overlay lets you decide without walking to the steward.
- **Steward conversation**: right-click for free text ("add a library wing, make it creepier").

## Boundary with Architect

| Lives in Architect (sidecar/kit/mod) | Lives in Steward |
|---|---|
| Design job runner, kit, checker profiles, renderer, library, variants, placement, snapshots, survival sites | Concept card, site survey, settlement plan store, steward entity, proactive triggers, permission and difficulty, inbox/HUD/hub, modules catalog, villagers, animals |
| **New in Architect, used by Steward:** public API + protocol version + `job.run` (A8), style bible (A1), parallel jobs (A2), massing (A3), batch placement (A7), critique loop (A4), delta apply (A6), macro kit/checker/nested sites/chunked snapshots (A5) | A protocol client and Java API user of those. No second sidecar. |

Architect changes are requested through the Architect session and land there first. See `docs/ARCHITECT-ASKS.md`.

## Architect constraints (from Architect's reply, 2026-10-05)

Binding for Steward's plans until Architect changes them:
- Sites never overlap today. Architect will back sites with a **change-set journal** (ported from AgentCraft's WorldJournal, phase 4e), which makes overlap legal and undo ordered, conflict-aware and exact; nested sites are dropped. Until then no covering terrain site.
- DesignRequest `maxSize` is x/z 7..96, y 6..64: that caps the single-template format only. Larger sites are **region programs** whose shape math runs once, in the sidecar's JS kit, producing cell lists per chunk section that the mod writes through the journal (see A5B-SPEC.md).
- Survival is per world; placement modes are limited to what the world allows (see Difficulty).
- Occupancy refuses placement near the player; placements are asynchronous.
- Library entries are per building; style bibles are separate artifacts under `<gameDir>/architect/bibles/`.
- Remove is exact over the snapshot box + 7; delta apply extends the snapshot before writing new cells.
- One sidecar; Steward is a protocol client. Architect will send the A8 API contract draft for review before building it.

## Phases

Each phase ends at a gate checked in a dev client (DevBridge), never in a real world, using an independent gate-verifier.

### Phase 0: Architect substrate (Architect repo, in Architect's order)
Architect phase 3 (A9) is in progress. Then: A8 public API + protocol version + `job.run`; A1 style bible + A2 parallel jobs;
A3 massing pass; A7 batch placement; A4 critique loop; A6 delta apply; A5 macro kit + checker + nested sites + chunked snapshots.
Steward builds against each as it lands; it does not wait for all of them.
- **Gate (phase 1 subset):** A8, A1, A2, A3, A7 landed. One style bible drives 3 buildings that read as one set; a massing ghost
  converts to a detailed build; a batch of placements lands with one undo group.

### Phase 1: Settlement core, generative (proposals only)
Founding Stone, claim, concept card form, plan store, site survey, style bible and site plan for **one village-scale prompt**,
massing ghost, parallel building designs, place, log and undo. Server-side steward entity (persona only). Spend meter and budget cap.
Permission: Proposals. Difficulty: Patron (creative worlds).
Phase 1 scope limits, from Architect's contracts: the village is **separate lots, no covering terrain site** (sites never overlap
until nested sites exist), and **each building stays inside Architect's size cap** (x/z 7..96, y 6..64). Placement may be deferred
or refused (the player stands in the box, an overlap): the steward waits until clear and never assumes a request succeeded.
- **Gate:** from a fresh dev world, a custom prompt ("a fishing village built on stilts over a swamp, mossy and crooked") yields
  a card, a massing ghost, 8+ buildings that pass checks and read as one place, placed through the ghost; Remove restores terrain
  exactly (Architect's snapshot box + 7); the spend meter matches the real cost.

### Phase 2: Macro sites and custom forms
**Blocked on Architect A5** (macro kit + checker, nested sites child-inside-parent removed child-first, chunked snapshots and region
programs for sites beyond the size cap). Macro kit in use: rift, sky city, crater, castle, ring wall. Sculpt vs find site, terrain
operators, macro checker. Terrain operators only ever go through Architect's snapshot path, never the world directly.
- **Gate:** the rift and a custom "meteor crater mining facility" prompt each generate, pass reachability/support checks, and
  restore exactly on Remove.

### Phase 3: Perception, proactivity, evolution
Progression tier, read-only perception tools, event triggers, steward proposals, free-text change requests, delta preview and apply,
permission levels, inbox/HUD/hub.
- **Gate:** advancing a tier produces a sensible proposal; an approved patch applies only the delta; each permission level
  behaves as specified; the log can undo any change.

### Phase 4: Difficulty modes and economy
Supplied and Hardcore on Architect survival sites, stockpile and requests, Economy unlocks.
- **Gate:** a survival dev world where a site is fed from the stockpile and the refund matches.

### Phase 5: Functional modules
Verified catalog: crop farm, storage hall with sorting, then the rest. "You have seeds" offer. Spine/connectors.
- **Gate:** each module's measured output in the dev client; the steward composes two modules into a settlement.

### Phase 6: Villagers and animals
Housing, trading hall with offer reading, animal detection and wing.
- **Gate:** villagers housed and breeding to the cap; a target trade found; animals moved into a wing and farmed.

### Phase 7: Simulation-verified custom modules
Claude-designed modules pass the tick-simulation gate before acceptance.

## Risks

- **Cost and time.** Managed by tiering, parallelism, massing-first, budget cap, caching, and the free fallback. Re-measure in phase 1.
- **Style coherence** across independent building jobs. Style bible plus neighbour renders plus critique loop; measured in the phase 1 gate.
- **Spatial reasoning at scale.** Programs plus a checker, never raw block lists from Claude.
- **Scale and performance.** Placement is spread over ticks, near the player, with chunk-load handling.
- **Placement can fail.** Occupancy refuses placement while the player is in or next to the box; the API gets a "wait until clear" mode (A7). The steward treats every placement as asynchronous and retryable.
- **Griefing existing builds.** Claim boundary, natural-blocks-only terrain operators, Architect `Occupancy`.
- **Functional builds** are version-fragile. Verified modules, dev-client measurement, simulation gate later.
- **Auth/ToS.** API key first; claude-login is a personal opt-in only.
- **Hardcore safety.** Everything undoable; no fatal surprises (fluids, lava, cave-ins) enforced by the checkers.

## Open questions

0. ~~Where the concept-card parser lives~~ resolved: Steward owns prompt/schema/UX, the call goes through Architect's sidecar `job.run`.
1. Name (Steward is a working name). Check Modrinth/CurseForge for conflicts before publishing.
2. Vanilla-only modules, or a vanilla-authentic plus assisted split.
4. Track AgentCraft fixes to copied code, or treat the copies as independent (same question as Architect).
