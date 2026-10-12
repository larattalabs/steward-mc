# Composition: Claude plans, Architect builds and checks

Joint design for Steward and Architect. Drafted by Steward on 2026-10-12, from Noah's direction of the same day:

> Support all of it: planned, ideas and not-planned-at-all. Don't be deterministic about it where we can avoid it, and don't hardcode ways to handle each situation unless we need to. Claude should be able to get creative and work things out.

**Status:** v2, with Architect's answers folded in. They are in `architect-mc/docs/COMPOSITION-ANSWERS.md` (main bfb9895), and that file's "Revisions" section, reviewed by GPT-6.1, wins over its earlier text. Both teams adopt it. Architect drafts slice P0 (the plan check) against this version. Nothing is built yet. Noah's two questions are in section 9.

## 1. The principle

| Who | Does what |
|---|---|
| **Claude** | Decides what to make. It interprets the ask, invents the parts, and composes them: zones, buildings, paths, walls, forms, rules, how the place changes over time, and how its parts relate. |
| **Architect** | Gives general building blocks, and checks whatever is composed from them: does it fit the land, does it overlap anything, what does it cost, can it be undone, can it be built from materials. When it can't build something, it says why, in a form Claude can act on. |
| **Steward** | Runs the loop: the player's words, then Claude's plan, then Architect's checks, then Claude's repair, then the player's approval, then the build. It keeps the plan, the history and the settlement's own mind. |

A new kind of ask should need a new combination, not new code. Code is added only for a **building block** the vocabulary lacks, or for a **check** that makes something safe. A program for one kind of ask ("ring city", "walled town") is never added.

Today's fixed programs (Steward's street layout, Architect's `flat_village`) stay as **shortcuts**: cheap, proven compositions that Claude can reach for when an ask is plain. They are also how a plain ask stays cheap.

## 2. The loop

1. **Words.** The player's words, with the land round the stone (`Land`, built), the settlement as it stands, and the history.
2. **Plan.** Claude writes a composition (section 3) through Steward's plan writer. It describes places with Architect's locators, and adds a relative sketch only where shape matters. It never gives world coordinates.
3. **Check.** `Regions.checkPlan(plan)` (Architect slice P0) is a $0 dry run of the whole plan against one survey snapshot, in a virtual world. It is asynchronous, cancellable, capped and cached. Each part gets one answer:
   - `fits`, with the resolved geometry and the cost;
   - `adjusted`, with how far it moved;
   - `refused`: it can't fit, so it says why and what would fit;
   - `unsupported`: no such building block;
   - `blocked`: it depends on a part that didn't fit;
   - `malformed`: a schema error in a known part;
   - `incomplete`: the check ran out of budget or time. This is never treated as `fits`.

   With every answer, Architect returns its **capability catalogue**: the building blocks and locators it knows. It sends a light preview first, and images on request.
4. **Repair.** Claude revises the plan from the answers. The loop is bounded by **total work and spend**: composing, bibles, building design and any program authoring together, not rounds alone. It stops on repeated failures or oscillation.
5. **Honest approval.** What can't be done is said in Claude's words. A part the plan marks **required** that comes back `unsupported` or `refused` **blocks approval**, until the player approves a concrete reduced revision. Any part dropped or substituted is shown in what the player approves. `CardLimits` stays only as a fallback.
6. **Approval binds a resolved artifact.** What is approved is the checked plan as Architect resolved it: its geometry, IR, blobs, blueprint versions, fits, links and frozen inputs. Buildings not designed yet are marked **provisional**, with a cost range. If the terrain or protected ground changes before the build, the artifact is checked again.
7. **Replay.** Resuming keeps the artifact's identity. Architect commits that **a shipped op's semantics never change**: new behaviour is a new op kind, held by its frozen goldens. Checking an approved intent again on a newer kit makes a new revision.
8. **Life.** Growth, festivals, repairs and conflicts are new plans, or revisions of one, made the same way.

## 3. The plan: two layers

**The geometric layer (Architect owns it: schema, compiler and capability catalogue).** It is versioned like the IR and validated by the kit as JSON Schema with typed refusals. It freezes before Steward writes its plan writer. Parts have stable ids and revision refs.

| Part | What it holds |
|---|---|
| `zones[]` | Each zone's `id`, `program` (its buildings, or a named template such as `flat_village`), `style` (a bible ref), place (a locator), size, and tolerances. |
| `connectors[]` | 7a's settlement graph and connector catalogue (street, path, wall, gate, bridge, tunnel, ladder, stair, rail, channel... plus `auto`): `from`/`to`/`along`, style, function. |
| `forms[]` | Volumes that are not buildings: raise, carve, float, terrace, pond, spire... (terrain, regions, F1). |
| `rules[]` | A finite, versioned rule vocabulary, verified on the fitted output with evidence. **Palette**: a block family with a share, exempting terrain and support. **Shape**: at the outline or footprint level, for example "no right angles". **Variety**: measured on the built output, for example "no two alike". **Density** and height caps. A required rule that can't be verified blocks readiness. The critique judges taste only. |
| construction dependencies, hidden-room geometry | What must stand before what, and where a secret is built. |
| unknown kinds | A bounded `extension` envelope, answered `unsupported {block}`, never refused as malformed. This is how the vocabulary learns what to grow. |

**Places (locators).** v0 has a small, deterministic vocabulary: `adjacent`, `along <feature>` (a biome edge, shore, river, ridge or street), `around <feature>`, `inside`, and `near <anchor>`. Features come from the survey. Anchors and units are defined, coordinates are quantised, and tie-breaking is stable. A relative sketch is optional. **Tolerances are explicit per part**: hard or soft, with defaults stated in the schema, and contract promises win (C27's "within 4 cells"). There is no blanket scale or deform.

**The life layer (Steward owns it).** It holds the `story`, the `stages` (a growth arc: what comes first, what each stage adds, what starts the next), the `schedule` (timed or conditional versions: a festival, seasons, decay), `relations` (between zones and settlements), and `secrets` (who knows of a hidden part; Architect owns its geometry). Steward maps each stage or scheduled version to a geometric plan, or a revision of one, and checks it. Free-text `notes` are kept and shown, but never treated as a verified requirement.

## 4. Building blocks, in Architect's order

**Exists or is building:**
- V and X (regions);
- T1 (terrain passes);
- MP (masks and patterns);
- F1 (natural forms);
- 0c, Y1, Y2 and C29 (construction, stockpile);
- 0b (versions);
- T2 (routing), building;
- F2 (scatter and stamps), building;
- B (brushes), building;
- D1 (districts on survey candidates) and D2 (C27: districts along a boundary, outposts), next after T2;
- T3 (crossings) and H (fluids), planned;
- 7a (typed connectors) and 7c (big forms, bible format 3), planned.

**New, by Architect's answer:**

| Building block | Where | Whose design |
|---|---|---|
| **Plan check v0 (P0)** | Right after T2. It covers regions, lots, roads and T2 routing; districts answer `unsupported` until D1, and brushes join after B. | Architect's; Steward reviews |
| **Free layouts (FL)** | Its own slice after D2: zones plus a path network fitted to locators and sketches | Architect's |
| **Rule checks** | After VOC: a separate deterministic verifier in the kit checker, not the critique | Architect's |
| **Schedules** | Mostly Steward (the life layer). **Scheduled revert**, applying a version and later reverting it exactly with a policy for edits made in between, is its own Architect contract. | Steward's, plus a contract |
| **Relocation** | Its own contract: crash-safe placement at the destination, removal at the source, materials | Architect's |
| **Other surfaces** | 7b (attach to a cliff, carve into it) and F3 (trees, mushrooms) | already planned |
| **Under water** | After H: an air-pocket or dome form with containment checks | Architect's |
| **Other dimensions** | A later infrastructure slice: survey, regions and stockpiles are mostly dimension-generic already; portals and linking are new | Architect's, later |
| **transferOwner** | Its own contract: it spans opKeys, protections, tombstones, stockpiles and ledgers | when Steward needs it |

**Steward's parts:**
- the plan writer (the composing prompt);
- the repair loop and its budget;
- the honest approval;
- the life layer (stages, schedule, relations, who knows a secret);
- functional modules, inhabitants and economy (phases 5 to 7);
- building around the player's things, as a plan constraint (protected ground plus a locator).

## 5. What stays deterministic

- **Safety:** the player's builds and protected ground are never written; undo is exact; saves are migrated, never broken.
- **Accounting:** in survival, no item duplicated or lost (Y).
- **Physics:** water, gravity and redstone. Claude proposes and a simulation checks (phase 7).
- **Cost:** budgets, caps and allowances. Composition costs Claude calls, so a plain ask stays cheap: Claude picks a shortcut, or the shortcut runs without a creative pass. Creative composition runs when the ask calls for it.
- **Replay:** an approved plan is data. Executing it is deterministic, and recovery is by key, as today.
- **Fairness and authority:** in multiplayer, the server decides. Agents act through rules, with Claude for intent and story.

## 6. Measure: the strange asks

`fixtures/strange-asks/` holds 59 asks, the capabilities each needs, and the real card Claude wrote for each. The measure for both mods is how many asks are satisfied by composition: checked, buildable, honest about the rest.
- **Today:** 4 asks are built, 13 as a nearest version, and 42 wait. 22 of 55 cards are honest, using Steward's word rules.
- **Each new building block** should move a row of the gap map (`node tools/strange-asks.mjs`).
- **Focused fixtures:** recorded-plan fixtures prove the compiler covers a block. They run in each slice's CI at $0.
- **Batch evaluation:** the 59-ask corpus measures composition quality, with Claude composing. It is rerun at milestones, paid, for a few dollars, with Noah's approval.

## 7. Order (agreed)

1. **Plan v0 (Steward).** The plan writer expresses today's builds (a street village, `flat_village` as a named template, districts, stages as proposals) against Architect's geometric schema, once it is frozen. The card shows the plan, and execution still uses today's paths. One paid batch evaluation of the strange asks follows.
2. **P0, plan check v0 (Architect), right after T2:** `checkPlan` with per-part answers for regions, lots, roads and routing; `unsupported` for the rest; the capability catalogue; the preview.
3. **The 6c remainder, each block adding its plan part and check:** S2, T3, H, HY, D1 and D2, FL, VOC with rule checks, S3, F3, RS.
4. **Then:** 7a (connectors), 7b (surfaces) and 7c (format 3, authoring); the contracts for relocation, scheduled revert, under water, dimensions and transferOwner; Steward's life parts and phases 5 to 7.

## 8. Architect's answers (2026-10-12)

All six questions are answered in `architect-mc/docs/COMPOSITION-ANSWERS.md` and folded in above: the two layers, one whole-plan check, locators and explicit tolerances, the order and owners of the new blocks, rule checks as a separate verifier after VOC, and `flat_village` kept as a program usable as a named template. Architect's SETTLEMENTS section 10 (7c) converges on this plan: 7c's own composer stays only for standalone use, and 7a to 7c are drafted against the shared schema.

## 9. Noah's answers (2026-10-12)

1. **When to compose, and the cap.** Claude decides: one call reads the ask and either picks a shortcut (the street village or `flat_village`) for a plain ask, or composes a full plan when the ask calls for it. Composing and repair for one plan are capped at about **$1**, inside the settlement's budget. At the cap, the steward shows its best plan with what is still missing.
2. **A required part that needs a building block that doesn't exist yet.** By default the town is built now and the missing part stays in the settlement's plan as **pending**. When Architect ships that block (the capability catalogue says so), the steward proposes adding the part ("Walls can be built now: shall I raise the town wall?"), approved like any proposal. The alternative offered is to **wait**: the settlement stays a plan until the block exists, then builds whole. Optional parts that can't be built are dropped and listed on the card.
