# Composition: Claude plans, Architect builds and checks

Joint design for Steward and Architect. Drafted by Steward on 2026-10-12, from Noah's direction of the same day:

> Support all of it: planned, ideas and not-planned-at-all. Don't be deterministic about it where we can avoid it, and don't hardcode ways to handle each situation unless we need to. Claude should be able to get creative and work things out.

**Status:** a draft for Architect's review. Nothing in it is built yet. Steward's PLAN and Architect's PLAN refer to it once Architect has answered.

## 1. The principle

| Who | Does what |
|---|---|
| **Claude** | Decides what to make. It interprets the ask, invents the parts, and composes them: zones, buildings, paths, walls, forms, rules, how the place changes over time, and how its parts relate. |
| **Architect** | Gives general building blocks, and checks whatever is composed from them: does it fit the land, does it overlap anything, what does it cost, can it be undone, can it be built from materials. When it can't build something, it says why, in a form Claude can act on. |
| **Steward** | Runs the loop: the player's words, then Claude's plan, then Architect's checks, then Claude's repair, then the player's approval, then the build. It keeps the plan, the history and the settlement's own mind. |

A new kind of ask should need a new combination, not new code. Code is added only for a **building block** the vocabulary lacks, or for a **check** that makes something safe. A program for one kind of ask ("ring city", "walled town") is never added.

Today's fixed programs (Steward's street layout, Architect's `flat_village`) stay as **shortcuts**: cheap, proven compositions that Claude can reach for when an ask is plain. They are also how a plain ask stays cheap.

## 2. The loop

1. **Words.** The player describes the place in their own words. With the words go the land round the stone, the settlement as it stands, and the history.
2. **Plan.** Claude writes a composition (section 3). It describes places by meaning ("along the biome edge", "a ring round the lake", "the hilltop") or, where shape matters, as a rough sketch in relative units. It does not give world coordinates.
3. **Check.** Architect fits the plan to the land and checks it as a dry run, with nothing placed. For every part the check answers one of:
   - **fits**, with the resolved geometry and the cost;
   - **adjusted**, saying how it moved or shrank;
   - **refused**, with a typed reason and what would make it fit;
   - **unsupported**, naming the building block that doesn't exist yet.
4. **Repair.** Claude reads the answers and revises the plan. The rounds and their cost are capped (Steward's budgets).
5. **Honest card.** What still can't be done after repair is said to the player on the card, in Claude's words: what waits, why, and what is built instead. That replaces Steward guessing from words (`CardLimits` stays only as a fallback).
6. **Approval, then the build.** The approved plan is saved with the settlement, and builds, growth and changes execute it. Once a plan is approved it is deterministic: a restart, an undo or a resumed build replays the same plan.
7. **Life.** Later changes (growth, festivals, repairs, conflicts) are new plans or plan revisions, made the same way.

## 3. The plan (a draft vocabulary)

A plan is JSON that Claude writes against a schema. The shape is a first guess, for Architect to shape:

| Part | What it holds |
|---|---|
| `story` | The settlement's identity and voice. Already on the card. |
| `zones[]` | Each zone's `id`, `purpose`, `style` (a bible or a variant of one), `program` (its buildings), `where` (a description of the place, or a relation to another zone or feature), `size`, and `relations` to other zones (rival, guards, trades with, sacred to). |
| `connectors[]` | Each connector's `kind` (`street`, `path`, `wall`, `gate`, `bridge`, `tunnel`, `ladder`, `stair`, `rail`, `channel`, `rope_bridge`...), `from`/`to`/`along` (zones, features, or a sketch), `style`, and `function` (walkable, defensive, carries water...). |
| `forms[]` | Volumes that are not buildings: `raise`, `carve`, `float`, `flatten`, `terrace`, `pond`, `hollow`, `spire`, each with `where` and `size`. Architect's terrain, regions and F1 already do some of these. |
| `rules[]` | Constraints Claude invents from the words, in a checkable form, with a scope: a palette rule ("copper family only"), a shape rule ("no right-angle corners"), a variety rule ("no two houses alike" or "identical rows"), a height cap... |
| `stages[]` | A growth arc: what comes first, what each stage adds, and what starts the next one (a population, a resource, the player's go-ahead, time). |
| `schedule[]` | Timed or conditional versions: a festival dressing applied and removed, seasonal changes, decay unless visited. |
| `secrets[]` | Hidden parts: rooms, concealed entrances, tunnels, with who knows about them. Design-side. |
| `notes` | What Claude wants that no field holds yet. It is kept and shown, and a check answers it with "unsupported". This is how the vocabulary learns what to grow. |

## 4. Building blocks: what exists, what is planned, what is new

**Exists or is building:**

| Building block | Architect slice | Status |
|---|---|---|
| Survey: height, water, trees, biomes | Sample, T1 | exists (biomes at 4x4; Steward uses them) |
| Regions on a claim, realised, undone whole | V, X | exists |
| Terrain passes, relief, blended pad edges | T1 | exists (0.21.0) |
| Masks and patterns from bibles | MP | exists (0.19.0) |
| Generated natural forms (spires, ice spikes) | F1 | exists (0.19.0) |
| Construction from materials, stockpile, refunds | 0c, Y1, Y2, C29 | exists; C29 building |
| Versions, deltas and revert | 0b and earlier | exists |
| Routing: paths over terrain, ladders on player-only edges | T2 | building |
| Scatter and stamps | F2 | building |
| Brushes | B | building |
| Districts on survey candidates; districts that follow a boundary; outposts | D (C27) | next after T2 |
| Crossings: bridges and tunnels | T3 | planned |
| Fluids: ponds, channels, wells | H | planned |
| Typed connectors: walls, gates, rails... | 7a | planned |
| Big custom forms as region programs; bible format 3 (related bibles) | 7c | planned |

**New (asks to open):**

| Building block | What it unlocks |
|---|---|
| **Plan check**: a dry run of a whole composition with per-part answers (section 2, step 3) | The loop itself: Claude can repair, and the card is honest |
| **Free layouts**: zones and a path network fitted to a sketch or a relation (ring, spiral, grid, organic), not one street | Ring cities, spirals, grids, hamlets that grew |
| **Rule checks** in design checking: palette, shape and variety rules a design must pass | "Only copper", "no right angles", "no two alike" |
| **Schedules**: a version applied and removed on a calendar or a condition | Festivals, seasons, decay |
| **Relocation**: a settlement or zone taken up and placed elsewhere, its history kept | Nomad camps |
| **Other surfaces**: building on trees, giant mushrooms, cliff faces and overhangs, and on what other parts built | Treetop and cliff villages, upside-down builds |
| **Under water**: building below a fluid surface with air pockets | Domes, sunken towns |
| **Other dimensions**: survey, regions and stockpiles in the Nether and the End; settlements linked by portals | Nether outposts, End cities |
| **Ownership that moves**: `transferOwner`, already kept open | Conquest and factions (long term) |

Steward's own parts:
- the plan schema and the composing prompt;
- the repair loop and its budget;
- the honest card;
- stages (growth), relations and simulation, the long-term direction;
- functional modules, inhabitants and economy (phases 5 to 7);
- variety control and building around the player's things, as plan rules.

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
- **Each new building block** should move a row of the gap map (`node tools/strange-asks.mjs`). Each composition milestone reruns the asks through the loop. That run is paid, for a few dollars, with Noah's approval.

## 7. Order (a proposal)

1. **Plan v0 (Steward).** A schema that expresses what is built today (a street village, a flat village, square and placed districts, stages as proposals), with Claude writing it. The card shows the plan. Execution still uses today's paths. $0 to build; one paid recording of the strange asks.
2. **Plan check v0 (Architect).** A dry run for the parts that exist (regions, roads, lots, districts), with per-part answers including "unsupported". The loop and the honest card go live.
3. **Building blocks in Architect's order**, each one adding a plan part and a check: T2 routing, D districts (C27), T3 and H, 7a connectors, free layouts, rule checks, then 7c. The new asks in section 4 are placed in that order by Architect.
4. **Steward's life parts:** stages and schedules, relations, phases 5 to 7.

## 8. Questions for Architect

1. Does the "composition plus check" split fit how 7a to 7c are drafted? What should change in them?
2. Is the plan best checked as one call (a dry run of the whole composition), or part by part through the existing check calls?
3. What form should a sketch take (a relative polyline, a zone graph, both), and how loose should fitting be?
4. Which of the new building blocks (section 4) can go where in your order, and which are your design rather than an ask?
5. Rule checks: inside design checking (the bible's critique), or a separate verifier?
6. Shortcuts: should `flat_village` stay a program, or become a composition Architect ships as a named template?

## 9. Questions for Noah

1. **Creative passes cost money.** Should the steward compose creatively for every ask, or only when the ask calls for it (with a plain ask kept cheap)? Is there a per-settlement or per-ask cap for composing?
2. **How strange is too strange?** When an ask needs a building block that doesn't exist yet, should the steward build the nearest version and say so (today's way), or wait and ask?
