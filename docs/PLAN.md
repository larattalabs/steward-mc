# Steward: plan

Working name **Steward** (mod id `steward_mc`, rename is open). A Fabric mod for Minecraft 26.3. You found a
settlement, and a Claude-driven NPC steward designs it from your prompt, builds it, runs it, and keeps evolving it
as you progress: a village, castle, sky city, a rift settlement, a "hellish evil lair", a "repurposed meteor crater
mining facility", anything you can describe.

It is a **sibling mod to Architect** (`larattalabs/architect-mc`). Architect is the building generator and survival
construction engine. Steward is the director on top: concept, site, layout, a standing NPC, proactive upgrades,
functional modules, villagers, animals, an inbox. Steward depends on Architect and does not copy it.

Status (2026-10-09): written 2026-10-05; **phase 1 is done** (gate passed, Noah; the whole flow runs in a dev client through in-game screens). Update available
and the free e2e check are built. Independent reviews found budget, cancel and ownership bugs, fixed in a hardening slice (see "Reviews (2026-10-09)"). Phase 3 is next.

## Principles

1. **Generative first.** Claude does the design work from the player's prompt. Presets exist for inspiration,
   as examples and vocabulary for the generator, as a free fallback when a budget or usage limit is hit, and for
   verified functional modules. A settlement is never "pick a theme, place prefabs".
2. **Claude writes programs, code checks them.** Claude writes layout and building programs (the kit, as in
   Architect). Once written they are deterministic, checkable (reachability, support, light, fluids) and re-runnable.
   Claude owns "where" through code and iterates against the checker, not by placing blocks one at a time.
3. **Everything is undoable and logged.** Snapshots, the Architect "never touches player blocks" rule, a change log,
   and permission levels up to full autonomy. Full autonomy is only safe because of this.
4. **Cost and time are a product constraint.** Measured in Steward's own runs (2026-10-09):
   - a bible costs $1.2-1.6;
   - a massing about $0.19;
   - a landmark $3.7;
   - an ordinary building $2.5-4.6;
   - 8 buildings came to $31, and Greywater's 3 unique buildings to $13.76 and about 70 minutes.

   **Targets** (provisional, "Reviews (2026-10-09)"): a useful starter settlement under $5, and the first usable result within 15 minutes. Architect measures where
   cost and time go (C7) and bounds small buildings (C8). See `BudgetPolicy` and the soft budget below. Model tiering, parallel jobs, massing-first, a spend meter
   and a hard budget cap are built.
5. **Vanilla blocks in templates.** Keeps Architect's rule. Any mod block (an "assisted" tier) is a deliberate,
   opt-in exception decided per module (open question 2).
6. **Singleplayer only** (LAN guests may watch, only the host directs the steward; enforced since 2026-10-09), same sidecar architecture as Architect and AgentCraft (local Node sidecar, Claude Agent
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
| 2026-10-09 | **Phase order revised** (Noah): finish phase 1 (street, claim size and Expand), then phase 3's core, then phase 4, then phase 2 once Architect 7c lands (phase 2 is blocked; 3 and 4 are not). Cross-cutting first: copies as free variants, a $0 end-to-end gate, Architect's estimate in the UI. See "Review and order (2026-10-09)". |
| 2026-10-09 | **Copies never cost quality** (Noah): designs are shared only with safeguards, adapted to their lot first, original as the fallback; the player can choose "all original". See "Copies, adaptations and originals". |
| 2026-10-09 | **Hardening before phase 3** (Noah): the review findings are fixed first. Adopted from the plan review: cost and wait targets, a shorter Architect critical path, change history before autonomy. **Declined:** narrowing phase 3; it keeps its full scope. See "Reviews (2026-10-09)". |
| 2026-10-09 | **Settlement and building interfaces** (Noah): a settlement screen, a panel for each building, and in-world interfaces after AgentCraft (the steward's nameplate and speech bubbles, building labels on look-at, survey mode, a settlement board, a ledger). Phase 3 core. See "Interface". |
| 2026-10-09 | **Claims grow** (Noah): the card's size sets the first claim, the player can expand it, and a settlement grows by **districts** (adjacent claims the steward proposes). See "Claims and growth". |

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

## Copies, adaptations and originals

Each building of a settlement is one of three kinds. Sharing a design is how cost and time come down (a program's "x4" is four full designs today). It must never
make a build buggy, broken or visibly cheap.

| Kind | What it is | Cost | Massing approval |
|---|---|---|---|
| **Original** | designed for its own lot (against its 3D site once Architect 7b ships) | full | per lot |
| **Adapted** | an existing design fitted to another lot by Architect's `fitToSite` (site variant, keep cells): same body, its base generated to the spot (stilt lengths, stepped foundation, a stair to the ground) | small (code, no Claude) | once per design |
| **Copy** | a free variant on a pad lot (`makeVariant` / bible re-skin) | about zero | once per design |

Rules:
1. **Adapt first, original as the fallback.** For each lot, an existing design of its role is fitted first; an original is made when the fit or the checker fails,
   or the lot is a landmark. **Architect's fit decides**, not a lot-type table: 7a's lot types (pad, cliff face, ravine span, canopy, cave interior, ledge, suspended)
   only predict the cost. As fit modes improve, more lots get cheap adaptations without Steward changing.
2. **Never forced.** A copy or adaptation that fails fit or the checker on its lot falls back to an original for that lot. Every variant is checked like a design.
3. **No sameness.** Landmarks and buildings that appear once are never shared. At most 2-3 placements per design, then a new design (x6 becomes 2 designs x 3
   placements). Each copy changes the palette and at least one exposed parameter (length, roof, porch side) where the design has them, plus mirror or rotation.
4. **The player decides.** The card screen shows the mix and its cost before anything starts ("14 buildings: 4 original, 3 designs adapted to 7 lots, 3 shared")
   and offers **"all original"** at the higher price.
5. **Copies follow their source.** A copy records its source design (`variantOf`). When the source gets a new version (a free-text change, a re-design), its copies
   and adaptations are offered as one update and applied per lot, keeping each lot's adaptation (phase 3; Architect 6d).
6. **Reuse across settlements, by choice.** A proven design from another settlement (Architect 7c's promoted shapes) may be offered on a new card ("reuse the cottage
   from Greywater Hamlet"), never applied automatically, and re-checked, refitted and re-skinned to the new bible.
7. **Small buildings** skip the report critique (it only reports, it never changes the design). They keep their massing: the cheap shape step the player approves
   and the detail pass binds to.

Until Architect 7a/7b, Steward has pad lots only, so copies (Architect 6c slice 0, C1) are the first kind to arrive; adaptations and site-aware originals come with 7b.
Estimates by kind come from Architect's calibrated `Designs.estimate` (C5).

## Claims and growth

A claim is the land a settlement may touch (everything the steward writes stays inside it). It is a square around the Founding Stone. The stone claims 129x129
(`Settlements.DEFAULT_RADIUS` 64), and describing then grows it to the card's size. The model takes any radius from 8 to 2048 and refuses claims that overlap
another settlement. It grows in three ways; 1 and 2 are built (2026-10-09), 3 is phase 3:

1. **Size from the card** (phase 1 follow-up). The card's `site.size` (S, M, L, XL; the schema also allows explicit `{x, z}`) sets the claim when the settlement is
   described: S 97, M 129, L 193, XL 257 blocks across (radius 48, 64, 96, 128), or the explicit size. If the larger square would overlap a neighbour, the
   claim takes the largest size that fits and the card screen says so. A hamlet stays small; a crater lair gets room. The card screen shows the claim's size.
2. **Expand** (phase 1 follow-up). A button on the card screen and the inbox grows the claim one step (to the next size, around the stone), refused with the reason
   when it would overlap another settlement or pass 4XL. Past the card's XL come **2XL 513, 3XL 1025 and 4XL 2049** blocks across (Noah: at least 1000x1000): room
   for districts and regions. A one-street village is still surveyed and laid out within 257x257 of the stone (`ClaimRules.VILLAGE_RADIUS`); the rest of a big claim is
   for districts (phase 3) and region programs (phase 2, Architect's prepare step loads terrain at that scale). A big claim also keeps other settlements away: a 3XL
   claim reserves a kilometre square. Free in Patron; in Supplied, Hardcore and Economy it costs resources (an "expansion" unlock, phase 4).
   A re-survey follows, so the next layout uses the new land. Shrinking is not offered while sites stand outside the smaller square.
3. **Districts** (phase 3, with evolution). Past one claim, a settlement grows by **districts**: adjacent claims with their own purpose ("a harbour district east
   of town", "the mine quarter"), each laid out and built like a settlement, sharing the settlement's style bible and steward, linked by roads. The steward
   proposes a district when the town runs out of room or a trigger fits (a resource found, a tier reached, animals nearby); the player can also ask in free text.
   Permission levels apply as written above: Autonomous still asks before a new district. Districts are also how the single-street limit is lifted: one street
   holds 12-16 buildings, a district adds its own street or region program.

Large forms are not claims grown by hand: a crater, rift or sky city is a region program (phase 2, Architect 6b-7c) whose claim comes from the program's
footprint (Architect has realised 1000x1000 regions), checked for overlap like any claim.

Gates: (1) a card of each size claims the matching square and refuses or shrinks on overlap, unit-tested and seen in a client; (2) Expand grows, re-surveys and the
next layout uses the new land, refusals named; (3) is part of phase 3's gate (a proposed district is approved, laid out next to the town, linked by a road, undone).

## Interface

Decided with Noah, 2026-10-09. There are three layers: screens for detail and actions, the HUD for "something needs you", and **in-world interfaces** so the settlement explains
itself where it stands, in the way of AgentCraft's in-world UI. Everything in-world is drawn by the client from state the server sends. Nothing is placed as blocks except the
board and ledger the player chooses to place.

**Built (phase 1):**
- The Founding Stone.
- The describe screen.
- The card screen.
- The inbox: the decisions of a running build, and updates.
- A HUD line and the `Y` key.
- Massing and update-preview ghosts.
- The steward as a static mannequin that opens describe, the inbox or the card.

After a build there is no screen for the settlement or for one building, and in the world there are no labels and no claim border.

**Screens:**
- **Settlement screen**, opened from the steward, the board, the ledger or the inbox. Tabs:
  - **Buildings:** a list and a top-down map. Each building shows its role, state, version and source (original, adapted or copy) and has a badge when it has an update.
  - **History:** the change log, each operation with undo or revert.
  - **Claim:** size, Expand, and later districts.
  - **Settings:** permission level, difficulty, budget and "all original".

  The inbox stays for decisions only.
- **Building panel**, opened from the list, the map, or by looking at a building in the world and pressing the inspect key. Right-click is not used, since it would clash with doors and chests.
  - **Shows:** role and notes, the style bible, version and history, the source design and the buildings that share it, the player's edits that updates keep, and cost.
  - **Actions:**
    - ask for a change in your own words (scoped to this building);
    - preview a pending update as a ghost;
    - revert to an earlier version;
    - "pin this version" and "make this one independent" (copies);
    - remove;
    - highlight.

  Each action arrives with the feature behind it. A read-only panel comes first.
- **Concept card form**, still to do: per-field chips, regenerate or lock per field, and Architect's estimate.

**In-world (after AgentCraft's world UI, ported like the screen kit):**
- **The steward:**
  - a nameplate with a status dot and an activity line ("designing 3 buildings", "waiting for you");
  - a pulsing "!" when a decision waits;
  - **speech bubbles** for what it says, instead of chat lines (chat keeps a short log);
  - particles for its state.

  When it walks (phase 3) it goes to the building it is working on, so where it stands says what it is doing.
- **Building labels:** look at a building and a label appears at its entrance with its role, its state or version, and a badge for an update or a pending change. The building gets a light outline, and the inspect key opens the panel. Labels show only on look-at, or for all buildings in **survey mode** (a key, or while the settlement screen is open), so the settlement is not cluttered. AgentCraft's plate declutter (no overlaps, the targeted one in full) carries over.
- **Claim and lots:**
  - In survey mode, the claim border and the lots are drawn on the ground, with district edges once districts exist.
  - Massing ghosts get a label for each lot (role and size) and approve or redirect by look-at.
  - The update ghost is labelled.
- **Settlement board:** a wall-panel block like AgentCraft's village board. It shows one card per building with its stage, the spend meter and the decisions that wait. Right-click opens the settlement screen. The steward offers it as the first prop (the player places it, or a build includes it).
- **Steward's ledger:** an item that opens the settlement screen and the inbox from anywhere. This is the "remote overlay" for when you are far from the steward.
- **HUD:** the inbox line and toasts ("Smithy updated", "3 massings ready"). In survey mode, a small legend.

**Steward conversation:** right-click the steward for free text ("add a library wing, make it creepier"). The reply comes in its speech bubble, and any proposal goes to the inbox.

**Shared UI code:** this would be the fourth copy of the world UI (AgentCraft, Architect's ghosts, Steward's screens and now its world UI). The "shared UI extraction"
deferred after the review now has an observed need. The plan is to port AgentCraft's `WorldUi`, nameplate, bubble and declutter into Steward, and to raise a small shared
library with AgentCraft and Architect before the copies drift further.

**Order:** in phase 3's steps (see "Review and order"):
- **3a:** the settlement screen and a read-only building panel.
- **3b:** the steward's nameplate, "!" and bubbles.
- **3c:** the panel's change and revert actions.
- **3d:** building labels, survey mode, labelled massings, the board and the ledger.
- **After Architect 0b:** the copy controls.

## Boundary with Architect

| Lives in Architect (sidecar/kit/mod) | Lives in Steward |
|---|---|
| Design job runner, kit, checker profiles, renderer, library, variants, placement, snapshots, survival sites | Concept card, site survey, settlement plan store, steward entity, proactive triggers, permission and difficulty, inbox/HUD/hub, modules catalog, villagers, animals |
| **New in Architect, used by Steward:** public API + protocol version + `job.run` (A8), style bible (A1), parallel jobs (A2), massing (A3), batch placement (A7), critique loop (A4), delta apply (A6), macro kit/checker/nested sites/chunked snapshots (A5) | A protocol client and Java API user of those. No second sidecar. |

Architect changes are requested through the Architect session and land there first. See `docs/ARCHITECT-ASKS.md`.

## Architect constraints (from Architect's reply, 2026-10-05)

Binding for Steward's plans until Architect changes them:
- (Delivered in Architect 0.8.0 / API 1.5.0, 2026-10-06: journal-backed sites, opt-in LAYER, roads as sites that layer over cell sites and skip sites and roads, `placeCells`, `stack()`, covers/coveredBy.) Sites never overlap by default. Architect backs sites with a **change-set journal** (ported from AgentCraft's WorldJournal, phase 4e), which makes overlap legal and undo ordered, conflict-aware and exact; nested sites are dropped. Until then no covering terrain site.
- DesignRequest `maxSize` is x/z 7..96, y 6..64: that caps the single-template format only. Larger sites are **region programs** whose shape math runs once, in the sidecar's JS kit, producing cell lists per chunk section that the mod writes through the journal (see A5B-SPEC.md).
- Survival is per world; placement modes are limited to what the world allows (see Difficulty).
- Occupancy refuses placement near the player; placements are asynchronous.
- Library entries are per building; style bibles are separate artifacts under `<gameDir>/architect/bibles/`.
- Remove is exact over the snapshot box + 7; delta apply extends the snapshot before writing new cells.
- **Placement rules (4d, frozen 2026-10-05):** overlap compares restore box against restore box; sides and back add nothing (a 0-block side gap is legal), only the
  front grows, by up to the approach length + 8 (`Sites.overlapMargin(blueprintId)`, `LotFit.predictedRestoreBox`). `Sites.fitToLot` sets the front back by the approach
  length so the approach stays inside the lot (`approachIntoStreet: true` lets it run out); `lot.minY` is the ground height. Queued items persist the actor UUID and the
  resolved mode; actorless INSTANT is only for a creative world or survival off (Patron); survival turned on later fails queued INSTANT items with NOT_ALLOWED.
  `cancelBatch` rolls the in-flight item back exactly. Steward's `VillageLayout` lot rectangles are therefore lot boxes the building and its approach must fit in
  (`LOT_TOO_SMALL` is a typed refusal), and the 3-block gap in `Rules.defaults()` can shrink once 1.4.0 is on hand.
- One sidecar; Steward is a protocol client. Architect will send the A8 API contract draft for review before building it.

## Survival rule for regions (decided 2026-10-08, relayed by the Architect session from Noah)
Natural cut and fill and grown natural forms are **free** (no BOM, no drops); connectors and buildings are **construction sites with a BOM**. So Supplied and Hardcore can build regions: the terrain work costs
nothing, the player pays for structures. Architect's five-phase settlement plan (6b, 6c, 7a-7c; caps 7b $90, 7c $180) is adopted; see `docs/A7-SETTLEMENTS-REVIEW.md` for Steward's answers.

## Scale answer (Architect 6a, 2026-10-09)
Megastructures work: a 1000x1000 region of 9.56M cells realised at 54.6k cells/s with no tick over 28 ms and no chunk generated during realise (terrain is pre-generated by an explicit 147 s prepare step). Per-stage drift
is checked (a changed stage returns to PLANNED with "land changed since planning"), any stage order is safe, and waits are unlimited by default. Steward's staged-build UI shows the wait reason from `RegionView.waiting`.

## Review and order (2026-10-09)

Where things stand after phase 1's gate run, what is weak, and the order agreed with Noah. Architect asks from this review are round 3 in
`docs/ARCHITECT-ASKS.md`.

**Built and run for real (phase 1):** Founding Stone claim, steward NPC, describe screen, concept card with a building program and the card screen, survey and the
one-street layout, the style bible, massings shown as ghosts, parallel detail designs with report critiques, the budget pause and raise, staged placement, exact
undo, the inbox with its HUD line and key, builds that survive a restart, the spend meter.

**Known gaps (built in part):**

| Gap | State |
|---|---|
| Street | Fixed: placed in the rerun (2026-10-09, Greywater Hamlet, same slope that refused it before). |
| Phase 1 gate | **Passed** (Noah, 2026-10-09). |
| Steward NPC | A static mannequin: no walking, no skin of its own, no conversation; the right-click is not tested by hand. |
| Card screen | No per-field chips or regenerate/lock per field. |
| Update flow | Wired and run (2026-10-09): "update available" in the inbox; see DEV.md. |
| Survival worlds | Supplied and Hardcore never tried. |
| Claims | Size from the card and Expand built (2026-10-09); districts are phase 3 (see "Claims and growth"). |
| Gates | No $0 end-to-end gate; every flow check so far was hand-run. |

**Weakest points and what changes:**
1. **Cost.** $31 for 8 buildings (ordinary $2.5-4.6 each). Every copy of a program entry is designed separately ("fisher's stilt house x4" was four designs).
   **Copies become free variants:** one design per program entry; its other copies are variants without Claude (Architect `Library.makeVariant`, or the bible
   re-skin), each on its own lot. About a third of a run's cost goes. Small buildings (sheds, racks, stalls) skip the report critique and can skip massing approval.
2. **Layout quality.** One straight street, lots packed beside it, no paths to doors, no props (lanterns, wells, fences), no landscaping. Steward's `VillageLayout`
   retires once Architect lays out plain villages with its 7a lots, connectors and paths (ask C3); until then the street and lot spacing get small fixes only.
3. **The steward is barely a character.** Walking, conversation and proposals move up into phase 3's core.
4. **No $0 end-to-end gate.** A stub-helper run (claim, describe, start, approve, place, restart, undo) before every push, as Architect's `tools/gate-run.mjs`
   does. Every bug of the gate run (the style field limit, repeated pause messages, a raise that paused again, a summon nested in a command) would have shown there.
5. **Duplicated logic.** In-game estimates come from Architect's `Designs.estimate` (`BudgetPolicy` stays the fallback); the village layout moves to Architect (2).

**Order** (updated 2026-10-09, after the hardening and Architect's round 4 order):
1. Phase 1: **done** (gate passed; street, claim size and Expand built).
2. Cross-cutting: **done** on Steward's side. This covers update available, the free e2e check (11/11) and the hardening slice.
3. **Phase 3** (full scope, Noah), in steps. Each step ships on its own, with tests and an e2e check.
   - **3a. Foundations.**
     - The change log becomes operations: sites, versions before and after, the outcome, how to recover.
     - Save format versions and migrations: old-world fixtures and a backup before a migration. Districts, operations, copies and the new steward entity all change the saves.
     - The settlement screen and a read-only building panel.
   - **3b. The steward as a character.**
     - Its own mob entity replaces the mannequin, with a migration.
     - Nameplate, "!", speech bubbles.
     - Walking to the building it works on.
     - Conversation by right-click.
   - **3c. Change requests.** Free text scoped to a building or the settlement: a new version of the building's design, previewed as a delta ghost, applied, and revertible (needs Architect C13, C14).
   - **3d. In-world interface.** Building labels, survey mode, labelled massings, the settlement board, the ledger.
   - **3e. Perception and proposals.**
     - The progression tier and triggers.
     - Proposals with guardrails: a rejected proposal is remembered, with cooldowns, coalescing, and a planning allowance per settlement.
     - Permission levels act on their own only within the change history (3a) and the allowance.
   - **3f. Districts.** Several claims per settlement with stable ids, roads between them (Architect `placeRoad`), and undo.
4. **Architect slices as they land:**
   - **0a:** the stub turns on `e2e.mjs stub`, which then runs before every push. Also estimates by kind in the card screen, exact massing ghosts, and operation keys (C9: an interrupted request is adopted, not repeated).
   - **0b:** copies with safeguards, small buildings with bounded effort, and the cost benchmark against the targets.
   - **0c:** placement polish.
   - **V (flat village):** new settlements use Architect's lots, door paths, square and props. Existing ones keep `VillageLayout` until the joint 7a/6d gate (C12).
5. **Phase 4:** Supplied on Architect's survival construction sites, then Hardcore and Economy. This needs streets in construction batches, which survival skips today.
6. **Phase 2** when Architect 7c lands (big custom forms as region programs); phases 5-7 after.

Cross-mod: the UI kit exists three times (AgentCraft, Architect, Steward), and the in-world UI would be a fourth. A small shared library is now needed ("Interface");
it goes to the AgentCraft and Architect sessions before Steward ports the world UI (3b).

## Reviews (2026-10-09)

Three GPT-6.1 reviews (code, plan, boundaries): `docs/GPT-REVIEWS-2026-10-09.md`.

**Hardening slice (done, 2026-10-09):**
- The budget is a hard cap. The bible's cap fits within it, and a bible that uses it up pauses before the group. A raise extends the group to the total less the bible's cost. A raise Architect refuses goes back.
- Cancel goes through a cancelling state. The bible job, the group (also one acknowledged late) and the batch's rollback stop first, and the build keeps its slot meanwhile. What stayed placed is logged for undo.
- Decisions are remembered per massing version, so a redirected building can be decided again.
- Builds are saved before Architect is asked for anything, and nothing is spent on a build that cannot be saved.
- Callbacks from a closed world change nothing.
- A build starts in its settlement's own dimension.
- One description runs at a time per settlement.
- Only the host directs the steward (on a dedicated server, operators).
- Updates are blocked outside the claim. One that removes parts is a demolition, which Autonomous asks about. A failed update is not retried by itself.
- The layout no longer stalls on one lot that fits nowhere.
- Settlement saves never diverge from memory, and incomplete saved records are refused.
- The e2e timing and identity bugs are fixed.

**Adopted from the plan review (Noah):**
1. **Cost and wait are the product problem.** Copies only help repeat-heavy villages, and Greywater (3 unique buildings) cost $13.76 and took about 70 minutes.
   - Provisional targets: a useful starter settlement **under $5**, and the **first usable result within 15 minutes**. Larger projects stay asynchronous, with honest estimates and usable partial results.
   - Architect measures cost and time per stage (ask C7).
   - Small buildings get bounded effort and kit components (C8).
   - The one-third saving from copies is a hypothesis until it is measured under the real 2-3 placement cap and fallback rate.
2. **A shorter Architect critical path.**
   - A narrow flat-settlement slice (lots, door paths, a shared space, props) comes before the full terrain work (C10).
   - 6c slice 0 is split into parts that ship on their own (C11).
   - `VillageLayout` retires only behind a joint migration and evolution gate with 7a/6d (C12).
   - Shared UI extraction, schematic interchange and shape promotion wait for an observed need.
3. **Change history before autonomy.** Before Autonomous or Full act on their own (automatic updates, proposals carried out), these are needed:
   - The change log records **operations**: the affected sites, the versions before and after, the outcome, and how to recover.
   - Any change can be reverted (a site back to its previous version), not only a whole project removed.
   - A change that removes parts asks first; this is done for updates.
   - Failed or partial projects stay in the inbox.
   - Caller operation ids let an interrupted request be found again rather than paid for twice (C9).

   This is how phase 3's gate ("the log can undo any change") is met.

**Not adopted:** narrowing phase 3 to one loop first. Phase 3 keeps change requests, proposals, the steward walking and talking, and districts.

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

- **Gate run (2026-10-09, Opus session):** the stilt-village prompt, dev client, claude login, Proposals, Patron. Card with a 10-building program, 8 built (2 stilt houses left out
  for the count); bible approved; 8 massings shown as ghosts and approved; a restart during the detail designs (restored, re-synced, carried on); the soft budget paused at 80% and was
  raised; placement approved; 8 buildings placed in 4 stages; `/steward undo` removed the site group and the land matched the "before" screenshot. Read as one place (dark timber,
  red trim, moss, lantern chimneys, stilts on every building). Spend: $31.03 on the meter (bible $1.16, massings $1.51, details $28.35), matching Architect's group cost plus the bible;
  plus $1.58 for a first attempt the run's own bug stopped (a whole-sentence style broke the request's 40-character style field: fixed). Found and fixed: the style limit, repeated pause
  messages, a raise that re-paused at once (now needs a budget that clears the pause), the street run-out over a drop (refused TOO_STEEP; no run-out now), the cost seeds (ordinary
  buildings cost $2.5-4.6, not $0.8-2.5). **Not met yet:** the street (refused this run), a gallery check by Noah, and a card screen (the decisions are chat commands). Screenshots in
  `docs/img/stilt-village*.jpg`.

### Phase 2: Macro sites and custom forms
**Blocked on Architect A5** (macro kit + checker, nested sites child-inside-parent removed child-first, chunked snapshots and region
programs for sites beyond the size cap). Macro kit in use: rift, sky city, crater, castle, ring wall. Sculpt vs find site, terrain
operators, macro checker. Terrain operators only ever go through Architect's snapshot path, never the world directly.
- **Gate:** the rift and a custom "meteor crater mining facility" prompt each generate, pass reachability/support checks, and
  restore exactly on Remove.

### Phase 3: Perception, proactivity, evolution
Progression tier, read-only perception tools, event triggers, steward proposals, free-text change requests, delta preview and apply,
permission levels, the settlement screen and building panel, the in-world interface (the steward's nameplate and bubbles, building labels, survey mode,
the board and the ledger; see "Interface"), the steward walking and talking, districts.
- **Gate:** advancing a tier produces a sensible proposal; an approved patch applies only the delta; each permission level
  behaves as specified; the log can undo any change (operations with versions and recovery, revert, demolition asks first: "Reviews (2026-10-09)").

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

- **Polish failed its gate too (Architect 0.10.0, 2026-10-08):** the critic accepted 0 steps even for visible fixes, so polish stays behind a dev flag. Delta apply itself shipped and passed its gate
  (12-village deltas max tick 11 ms; a 590k-cell full-change delta in 10.3 s, exact revert; survival items in = out). Steward's evolution therefore applies re-designed versions with `checkDelta`/`applyDelta`
  (`UpdatePlanner`); the cheap scoping call (`fits`/`suggest`) can route free-text requests.
- **Critique loop did not earn its cost (Architect 0.9.0 phase 5a, 2026-10-07).** On 18 Sonnet briefs a blind Opus judge preferred the loop's output 7 times to 6 losses and 5
  ties (p 0.50), the critic's own mean went 5.48 to 5.76 (never reaching its 7 ship line), and the loop cost +52% and +3 min per design. It ships experimental and off by default. Steward's
  plan therefore uses **REPORT critiques only** (about $0.05-0.15 per design, inside the design's slot): scores and open issues feed the inbox and can gate Steward's own decisions
  (for example "redirect this massing" or "polish later"), with per-lot extra criteria (entrance on the front face, the card's avoid list, reads as the lot's role). Do not plan on LOOP for
  anchors or landmarks. A dedicated Opus-vs-Sonnet run for the landmark decision (about $30) is available if it matters. Bible format 2 (restraint) does de-clutter (detailNoise 0.27 vs 0.47).
- **Cost and time.** Higher than first assumed (see principle 4). Managed by tiering (Opus only for landmarks), parallel waves, massing-first, an
  estimate shown before anything runs (`Designs.estimate`, Architect 4b Java), a soft budget that pauses at 80% and asks, a hard cap at 100%, caching,
  and the free fallback. Default budgets scale with the settlement size (`BudgetPolicy.suggestedBudgetUsd`: S $20, M $35, L $55, XL $95); the old $20
  default was too low for anything but S. Re-measure in phase 1.
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
