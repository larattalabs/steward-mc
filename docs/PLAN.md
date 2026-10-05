# Steward: plan

Working name **Steward** (mod id `steward_mc`, rename is open). A Fabric mod for Minecraft 26.3. You found a
settlement, and a Claude-driven NPC steward designs it from your prompt, builds it, runs it, and keeps evolving it
as you progress: a village, castle, sky city, a rift settlement, a "hellish evil lair", a "repurposed meteor crater
mining facility", anything you can describe.

It is a **sibling mod to Architect** (`larattalabs/architect-mc`). Architect is the building generator and survival
construction engine. Steward is the director on top: concept, site, layout, a standing NPC, proactive upgrades,
functional modules, villagers, animals, an inbox. Steward depends on Architect and does not copy it.

Status: **plan only**, written 2026-10-05. Nothing is built.

## Principles

1. **Generative first.** Claude does the design work from the player's prompt. Presets exist for inspiration,
   as examples and vocabulary for the generator, as a free fallback when a budget or usage limit is hit, and for
   verified functional modules. A settlement is never "pick a theme, place prefabs".
2. **Claude writes programs, code checks them.** Claude writes layout and building programs (the kit, as in
   Architect). Once written they are deterministic, checkable (reachability, support, light, fluids) and re-runnable.
   Claude owns "where" through code and iterates against the checker, not by placing blocks one at a time.
3. **Everything is undoable and logged.** Snapshots, the Architect "never touches player blocks" rule, a change log,
   and permission levels up to full autonomy. Full autonomy is only safe because of this.
4. **Cost and time are a product constraint.** A novel Opus design is about 4 minutes and about $1 (Architect,
   measured). Model tiering, parallel jobs, massing-first, a spend meter and a budget cap are phase-1 features.
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
   tier, lighting mood. It goes into every later job (and caches well).
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
| Patron | Free, instant builds (Architect creative placement). |
| Supplied | Builds from a stockpile; the steward requests materials (Architect survival sites). |
| Hardcore | You gather everything. |
| Economy | You spend resources to unlock modules and tiers (automated farming and so on). |

Difficulty maps onto Architect's per-world survival toggle (phase 3 there).

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
| **New in Architect, used by Steward:** style bible, hierarchical (parallel) jobs, massing pass, critique loop, macro kit + macro checker, delta diff/apply, API for batch placement over ticks | A thin client of those APIs |

Architect changes are requested through the Architect session and land there first. See `docs/ARCHITECT-ASKS.md`.

## Phases

Each phase ends at a gate checked in a dev client (DevBridge), never in a real world, using an independent gate-verifier.

### Phase 0: Architect substrate (Architect repo)
Finish Architect phase 3 (survival sites, crate, ledger) and carried-forward issues. In parallel the Architect session
starts the generation primitives Steward needs (see ARCHITECT-ASKS): style bible, hierarchical jobs, massing pass, critique
loop, macro kit and checker, delta apply, batch placement API.
- **Gate:** Architect phase 3 gate passes. One style bible drives 3 buildings that read as one set; a massing ghost converts to
  a detailed build; a delta edit applies without a full rebuild.

### Phase 1: Settlement core, generative (proposals only)
Founding Stone, claim, concept card form, plan store, site survey, style bible and site plan for **one village-scale prompt**,
massing ghost, parallel building designs, place, log and undo. Server-side steward entity (persona only). Spend meter and budget cap.
Permission: Proposals. Difficulty: Patron.
- **Gate:** from a fresh dev world, a custom prompt ("a fishing village built on stilts over a swamp, mossy and crooked") yields
  a card, a massing ghost, 8+ buildings that pass checks and read as one place, placed through the ghost; Remove restores terrain
  exactly; the spend meter matches the real cost.

### Phase 2: Macro sites and custom forms
Macro kit in use: rift, sky city, crater, castle, ring wall. Sculpt vs find site, terrain operators, macro checker.
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
- **Griefing existing builds.** Claim boundary, natural-blocks-only terrain operators, Architect `Occupancy`.
- **Functional builds** are version-fragile. Verified modules, dev-client measurement, simulation gate later.
- **Auth/ToS.** API key first; claude-login is a personal opt-in only.
- **Hardcore safety.** Everything undoable; no fatal surprises (fluids, lava, cave-ins) enforced by the checkers.

## Open questions

1. Name (Steward is a working name). Check Modrinth/CurseForge for conflicts before publishing.
2. Vanilla-only modules, or a vanilla-authentic plus assisted split.
3. Where the concept-card parser lives: Steward's own thin sidecar call, or Architect's sidecar.
4. Track AgentCraft fixes to copied code, or treat the copies as independent (same question as Architect).
