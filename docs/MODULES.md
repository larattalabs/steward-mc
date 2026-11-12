# Functional modules (phase 5)

Status: design v1, 2026-11-12, for review before code. $0: no code, no paid runs. It builds after survival growth A2. Noah asked on
2026-11-12 for the design to start now. PLAN.md "Phase 5" sets the scope: a verified catalog with a crop farm and a storage hall with sorting,
the "You have seeds" offer, and the spine and connectors. Its gate is each module's measured output in the dev client, and two modules
composed into a settlement. Architect asks are in ARCHITECT-ASKS.md, "Phase 5: functional modules (S-M5)". Noah's questions are in section 9.

## 1. What a module is

A **module** is a building whose job is to do something: grow crops, sort items. Most buildings only need to look right. A module also has to
**work**, and keep working on 26.3. That changes three things:

| | An ordinary building | A module |
|---|---|---|
| Who decides the blocks | Claude designs them (bible, massing, detail; $2.5-4.6) | A **verified program** in Steward's catalog. Claude only chooses the module, its parameters and its place (2026-10-05 decision: "Claude composes and parametrises; it does not hand-wire redstone unverified") |
| How it is checked | Architect's checker, the critique | The checker **and** a **measurement**: its output counted in the dev client, per catalog version |
| What it costs to design | a design | **$0**: a run of the program with the parameters and the settlement's bible roles (principle 1: presets for verified modules) |

**One module = a functional core plus a styled shell, from one program.** The program writes the core (farmland and crops, hoppers,
comparators, chests) in its **settled state**, as Architect's GM writes a gate (`gmCircuit` returns the rest state; template writes skip side
effects, and template block entities are empty: CONTRACT "Survival", "Block entities"). It dresses the core from the bible's roles: fence,
path, lamp, roof and wall blocks, like Architect's palette-driven kit components (`kit/lib/smalls.mjs`). The core's functional blocks are fixed
(a hopper is a hopper); every block the core does not need is a role. So a module reads as the settlement's own without Claude designing it,
and a re-skin is free.

**Where the program runs (recommended, S-M5-1).** Steward owns the catalog (PLAN "Boundary with Architect": "modules catalog" is
Steward's). Steward ships each module's program as data: kit program source, a parameter schema and the tested parameter ranges. Architect
runs it as a **given-program design**: a design request whose program is supplied rather than written by Claude. It runs the kit, the checker
and the renders, installs a library entry with its source, and costs $0. Then `makeVariant` (other parameters, another bible) and placement
work as for any entry. This is one general building block (COMPOSITION section 1), not code per module. **Alternative:** Architect hosts the
module programs as kit components, written to Steward's spec. That is faster to start, but every new module and every fix becomes an Architect
release.

**A module's identity.** `<id>@<version>`, for example `crop_farm@1`. A version is frozen once measured: a new layout, circuit or parameter range
is a new version, measured again. Built modules carry `steward_mc:module = crop_farm@1` and their parameters in the lot's ext, so the screens,
growth and undo know what they are.

## 2. Verified: measured in the dev client

"Verified" means **measured**, not reviewed. Each catalog version has a **measurement**: a scripted scenario in a dev client (DevBridge, $0),
run at each corner of its tested parameter range, with stated bars. Its results are committed with the module (`modules/<id>/<version>/measured.json`):
the numbers, the bars, the Minecraft version, the Architect version, the seeds and the run count. The gate-verifier re-runs it.

**The rules:**
- **No measurement, no offer.** A module is offered only on the Minecraft version it was measured on, and only on an Architect version whose
  RC ran its `module` e2e flow (section 7). A version that fails its measurement leaves the catalog. Nothing falls back silently to an
  unmeasured version.
- **A probe first** (slice M0, as Architect's `gm-probe`). Every vanilla rule a module relies on is checked on the running 26.3 server before any
  module is built on it. If a rule fails, the slice stops and reports. The rules: farmland hydration reach (4 cells, so one source per 9x9) and how
  long it takes; crop growth by random tick, by light level and by hydration; trampling; a hopper's transfer rate; a comparator's signal from a
  hopper's fill, at the thresholds the sorter uses; a hopper locked by a torch; what a full chest does to the chain. The probe asserts nothing
  from memory; it measures, and the module programs use its numbers.
- **Random ticks are noisy.** Growth is measured over several runs (at least 5), at the default `randomTickSpeed` 3 (the bars hold there).
  `/tick sprint` makes it faster, and the probe confirms that sprinting doesn't change the result. The bars are bands around the probe's own
  figures, not exact counts.
- **Dev hooks are Steward's** (its DevBridge, dev builds only): insert stacks into a container, count the items in a box's containers, harvest
  every mature crop in a box as a fake player and count the drops, set a gamerule. No Architect ask.

## 3. The first catalog

### 3.1 `crop_farm@1`: a hydrated, lit field

**What it is.** A rectangular field of farmland in rows, planted with the crop the player carries (wheat, carrots, potatoes or beetroot).
Hydrated by Architect's farm irrigation (IRR: `irrigate: true` on a farm lot, one contained source per 9x9 of field, drained first on undo).
Lit so it grows at night (lamps from the bible's lamp role, spaced so every crop cell has light 9 or more). Fenced so mobs don't trample it,
with a gate on the lot's front. A composter and a chest by the gate as the field's output port (`item_out`, where the player puts the harvest).
**The player harvests it** (v1; section 9, Q1): there is no redstone and nothing that can jam.

**Parameters.** `crop` (4 kinds); `field` (w x d), from 9x9 to 27x27 in steps that line up with IRR's 9x9 grid, so every source sits where the
program left a gap; `lamps` (`role` or `none`, when the bible has no lamp, in which case the measured bar is the daylight one). The program
takes the lot's irrigation cells (the artifact's `$.lots[].irrigation`, IRR) as reserved, and leaves them and the cell above each open.

**Measurement.**
1. **Hydration:** every farmland cell at `moisture=7` within 2 minutes of placement (IRR's own gate item 3, repeated here on the module).
2. **Light:** every crop cell at block light 9 or more at midnight (lamps), counted.
3. **Growth:** crops at age 7, as a share of the field, after 1, 2 and 3 in-game days at `randomTickSpeed` 3, at least 5 runs. Bars: within
   the probe's band for a hydrated, lit field, and the field without irrigation (the probe's control) clearly slower.
4. **Yield:** one scripted harvest of a mature field, drops counted per crop kind and size. This is the number the steward quotes in its offer. It is recorded, not a bar.
5. **Trampling:** a mob let into the closed field for 10 minutes leaves the farmland as it was.
6. **Restarts:** the field survives a restart and a chunk unload mid-growth (ages kept, moisture back within 2 minutes).
7. **Survival:** placed from a stockpile; its bill (dirt for farmland, one seed per crop cell, fences, lamps, chest, composter; water free)
   equals what was consumed; refunds on undo match (section 6).

**Limits carried from IRR.** A field side under 3 asks nothing. In a freezing biome the sources would freeze, so the module isn't offered there
(the decorative farm is). An irrigated lot is never refilled (S-RV-14): a removed farm's lot stays free land.

### 3.2 `storage_hall@1`: chests that sort

**What it is.** A hall with an **input chest** at its door, a line of hoppers under a row of filtered chest slots (the long-standing vanilla
comparator sorter: each slot's filter hopper holds its item and passes only that item down), an **overflow chest** at the end of the line for
everything not filtered, and a bank of plain double chests for bulk. A sign and an item frame over each slot show its item. It has an `item_in`
port at the input chest (where a later spine joins) and is styled from the bible's roles (walls, roof, floor, lamps).

**Parameters.** `slots` (4 to 16, in steps of 4); `items`, the filtered item for each slot, chosen when the hall is offered from what the
settlement has most of (the stockpile and the player's carried items, top kinds by count; the player can change them before accepting);
`bulk` (0 to 8 double chests).

**Filter items are the hard part.** Each filter hopper must hold an exact count of its item plus filler items. Architect strips container
contents from templates and construction targets (CONTRACT "Survival": "Block entities placed by the template are empty"). Without a change,
the hall would be built with empty filters and sort nothing. **Asked (S-M5-3):** a verified entry may declare **filled containers**: a cell's
inventory written at placement, billed in survival as ordinary items, refunded on undo. **Fallback if refused:** the steward hands the player
a filter kit and the hall shows which slot each goes in. It is error-prone and can't be measured as built, so the recommendation is to wait
for S-M5-3.

**Measurement** (deterministic, so exact bars):
1. **Sorting:** a test mix into the input chest: each slot's item times 3 stacks, 5 kinds the hall doesn't filter, and an unstackable item.
   Every filtered item ends in its slot's chest, every other item in overflow, and the input is empty. **100%**, with items in = items out.
2. **Throughput:** items moved per second, recorded (bar: within 10% of the probe's hopper rate times the line's parallelism).
3. **Full chest:** one slot's chest filled to the top, then more of its item: it backs up into its own slot (or overflow) and every other slot
   still sorts 100%. The probe says which; the bar is "no wrong item in any chest, nothing lost".
4. **Filters hold:** after 10,000 items, every filter hopper still holds its filter count (no filter item drained away).
5. **Restart and unload** mid-sort: nothing lost, nothing duplicated, sorting continues.
6. **Survival:** bill (hoppers, comparators, chests, redstone, the filter items) consumed equals delivered; undo as section 6.

**Tier.** Hoppers need iron and comparators need nether quartz. In survival the sorting hall is offered once the player's tier reaches the
Nether (Progress already reads it). Before that, the steward may offer the hall with `slots = 0`: chests, signs and the input, no redstone. That is
the same module version with a parameter, and needs no second measurement for sorting.

### 3.3 What comes next (not in phase 5's first catalog)

Sugar cane and melon/pumpkin farms (observer and piston harvesters, the first modules with an automatic `item_out`), an animal pen, a smelter
array, and the trading hall (phase 6, with villagers). Each one needs its own probe items and measurement. The spine (section 5) earns its keep
once an automatic module exists.

## 4. Offering and placing

### 4.1 The offers

The steward offers modules through the phase 3e proposal rules and their guardrails (rejections remembered, cooldowns, at most three open, one
per 20 minutes, worded by Claude). Two new rules:

| Rule (key) | Sign | Offer |
|---|---|---|
| `module.crop_farm` | the player carries 8 or more seeds of a crop the catalog grows (today's `farm` sign) | "You carry wheat seeds. I can lay a watered, lit field by the town for them: about N wheat every three days" (N from the measured yield for that size). The size comes from the seeds carried and the room (Patron: the largest field that fits, up to 27x27; Supplied: one the stockpile and the player's seeds can pay for, with the bill shown). |
| `module.storage_hall` | the stockpile, or the chests in the claim, hold 12 or more kinds of items, or the player's inventory is full twice within 20 minutes | "Your stores are getting mixed. A sorting hall would put your cobblestone, iron, wheat and wood in their own chests." Survival: sorting only from the Nether tier; before that, the plain hall. |

**The old `farm` rule** (seeds, a decorative "farm and barn" designed by Claude) stays as the **fallback** when the module can't be placed
(section 4.3). The module offer has its own key, so a player who declined the decorative farm still gets the working one offered **once**. A
decline of `module.crop_farm` also retires `farm` for that settlement: the player has said no to farms. No save migration: keys are strings.

**Asked in talk.** "Build me a farm" or "I need somewhere to sort my stuff" goes through the talk job, which can now name a module
(`module: crop_farm` with its parameters) in the proposal it puts in the inbox. Its schema gains the module ids from the live catalog.

**Autonomous and Full** may build a module offer on their own, within the weekly allowance like any proposal. A module design costs $0, so in
Patron it is free; in survival it spends the stockpile, and Autonomous asks first when the bill takes more than half of what the stockpile
holds of any item (section 9, Q4).

### 4.2 Placing: a module is an addition on growth

A module is placed as an **addition**, on the paths growth already has:

| The town | Path | Module lot |
|---|---|---|
| a region town (V, plan v1), instant | growth A: `planRevision` grow | grow lot `{id, type: farm or storage, size from the parameters, ext, irrigate: true (farm)}` |
| a region town, from materials | growth A2 | the same lot, billed from the stockpile |
| a street town (east-west street), instant, not Supplied | growth B: adopted at its first growth, then A | the same lot |

The lot's entry is the module's **variant** (`makeVariant` of the module's catalog entry with the parameters and the settlement's bible), bound in
`lotEntries` like any design. No massing, no design job, no critique. The proposal shows the catalog's render and, in survival, the bill for the size
offered (the measured BOM per size, kept with the catalog) before Accept. Once the lot is planned, the variant's own BOM is the bill; if it is
larger than what was shown, the build pauses and asks again.

**Order inside the build:**
1. `planRevision` with the module's lot.
2. Read the planned lot's box and, for a farm, its irrigation cells (asked, S-M5-2).
3. `makeVariant` for that box and those reserved cells.
4. Realise.

The variant comes after the plan because the field must leave the planned sources open. If the planned box differs from the size asked
(GR may place a smaller lot), the program is run for the box as planned, within the tested range, or the build ends saying so (never an
unmeasured size).

### 4.3 Where a module isn't placed (yet), and what happens instead

- **Districts:** district growth (`dgrow`, D-S2) isn't built. A district's farm waits for it.
- **North-south streets, Supplied street towns** (not adopted, S-AD-5), and the legacy street path: there is no region to irrigate. Lot designs
  never own fluids (IRR), so a dry farm module would fail its bar.
- **Freezing biomes** (farm), an Architect without the needed features (C38's catalogue and S-M5 features), and an unmeasured Minecraft version.

In all of these, the offer becomes the **decorative** farm (or, for the hall, an ordinary storehouse designed by Claude), and the card says why:
"a working field needs the town's water system; this one is for looks".

### 4.4 Composing modules into a settlement (plan v1)

When a card asks for working things ("a farming village that feeds itself", "a trading post with a sorting warehouse"), the plan writer can name
modules as lots of a composed plan: a lot with `type`, `ext steward_mc:module` and its parameters, and `irrigate` for a farm. The plan writer's
catalogue copy gains the live module list: ids, parameter ranges, footprints, what each needs (tier, features). Steward binds each module lot's
variant at the Start re-check, as it binds designed lots. Ordinary buildings around them are designed as today.

**The two-module gate** is met here: a plan v1 settlement whose plan holds a crop farm and a storage hall, built, both measured in place
(section 7). The hall's input chest stands by the farm's gate on the plan's path, so the player carries the harvest a few steps. Item transport
between them is the spine (section 5), which is not needed for this gate (section 9, Q2).

### 4.5 Growth and revisions over a standing module

- **A module is a lot like any other** to growth: GR keeps its box (never writes under a standing lot box +1), and a later addition can sit next
  to it.
- **Upgrading** (a bigger field, more slots) is a new variant on the same lot: removing the lot and refilling it would need RV's refill, which
  refuses irrigated lots (S-RV-14). Phase 5 doesn't upgrade in place. A second field is a second module lot. A version change offer (`crop_farm@2`)
  waits until Architect can refill an irrigated lot (section 9, Q5).
- **Land check** (LC): a harvested or replanted field should not read as "changed" every day. Crop age and farmland moisture are volatile
  already, so a ripening field still reads unchanged. A harvested cell (air over farmland) or a player's replanted crop needs S-M5-4.

## 5. The spine: item connections between modules

PLAN's sketch has output hoppers feeding a central spine. What Architect has today: blueprints already declare ports (`item_out`, `item_in`,
CONTRACT "Ports"), and 7b's link pass (LP) joins **walk** ports between lots at realise. It doesn't move items, and a revision's realise has
no links stage (S-7b-3). So an item spine needs a new building block: **`connector.items`** (S-M5-5), from an `item_out` port to an `item_in`
port. The connector is a hopper chain for short runs (up to 16) or a water channel on ice with a hopper at the end for long ones. It is routed
as T2 routes are, billed in survival, and verified by the probe (throughput, nothing lost at the joins, a restart mid-flow).

Phase 5's first two modules don't need it: the farm's output is a chest the player fills. So the spine is the **last** slice (M6), built when
the first automatic module (sugar cane) is designed, and only if Noah wants it in phase 5 (Q2). Until then, modules that belong together are
placed next to each other by the plan, with the hall's input by the farm's gate.

## 6. Survival: billing and undo

**Billing.** A module is billed like any building from Architect's BOM of its variant, from the settlement's stockpile (phase 4), through
growth A2 for region towns:
- crops cost their seed (Architect's survival COSTS already map crop blocks to seed items), farmland costs dirt (CU treats farmland and dirt as
  one), water is free (N-H-1);
- hoppers, comparators, chests, redstone are ordinary items;
- **filter items** are billed as items once S-M5-3 lets the entry declare them.

The proposal shows the full bill before Accept. The steward's material requests name the module's part ("the sorting hall waits for 3 hoppers").

**Undo.** A module lot goes like any growth: `undoRevision` (growth A), or with the town. Irrigation is drained first (IRR). The module-specific
parts:

| What the module holds | Today's rule (Architect) | Phase 5's answer |
|---|---|---|
| crops at any age | `age` is volatile: ours | refunded as Architect refunds standing blocks; a seed for a young crop? Architect's rule, recorded |
| a harvested cell (air on farmland), farmland trampled to dirt | a mined block: not refunded (the player has the item); farmland and dirt interchangeable | as today: nothing to refund for the crop |
| a crop the player replanted | a player-placed block (PT record): kept, or a removal blocker? | **asked, S-M5-4**: a crop in a module field's crop cell counts as the field's for undo and LC. Preferred: it goes with the lot like ours, with no refund (the player planted it) |
| a chest, hopper or composter holding items | "A container the player filled is not ours": refuses Remove | **refused, said** (default; Q3): the steward says "the sorting hall still holds 1,240 items in 9 chests; empty it first", names the containers, and undo waits. Nothing is lost or duplicated. Alternative (asked as an option in S-M5-6): removal moves the contents into the settlement's stockpile |
| filter hoppers (S-M5-3) | their contents change as items pass | declared filled containers are "ours" while they hold only their filter items, at any count. Refunded as their contents on undo |

**Crash and restart.** Nothing new: a module build is a growth (A, A2, B), with their keys, recovery and save formats. The variant is made by
key (`steward.<build>.module`), so a restart between the plan and the realise finds the variant it made, not a second one.

## 7. How it is tested

- **Unit:** the proposal rules (signs to offer, keys, the `farm` fallback and declines), parameter choice (seeds and room to field size; the
  stockpile to the hall's items), the catalog's gating (Minecraft and Architect versions, features, tier, biome), the lot spec for growth.
- **Module measurement** (`tools/module-measure.mjs`, its own dev client on the e2e ports): section 3's items at each corner of the parameter range;
  results into `modules/<id>/<version>/measured.json`; the gate-verifier re-runs it.
- **e2e (stub), flow `module`:** a V town accepts a crop farm offer (growth with an irrigated module lot), hydration checked; a storage hall,
  sorting a short mix (30 s); undo of both (the hall refused while it holds items, then emptied and undone). It is short enough for the sanity
  of an Architect RC that touches regions, fluids or growth, and runs nightly.
- **Survival e2e:** the farm from a stockpile, bill in = consumed, undo refunds.

## 8. Slices and gates

Each slice is one Opus session in its own worktree, per AGENTS.md. Each module's gate is its measurement, re-run by the gate-verifier. The
gap map row "working modules" (`fixtures/strange-asks`) moves from *waits* to *nearest version* at M3 and to *built* at M5.

| Slice | What | Needs | Gate |
|---|---|---|---|
| **M0 probe** | The vanilla rules of section 2 measured on the running 26.3 server; `tools/module-probe.mjs`; Steward's dev hooks | nothing | every rule measured, with numbers committed; any failure stops the phase and goes to Noah, with no silent fallback |
| **M1 catalog** | The catalog format (program, schema, ranges, measured.json), its gating, a module as a lot spec; the given-program path, or Architect's kit component | S-M5-1 | a module entry installed at $0, re-skinned by two bibles, checker clean; unit tests |
| **M2 crop farm** | `crop_farm@1` and its measurement | M0, M1, IRR, S-M5-2 | section 3.1's measurement passes at each corner; gate-verifier |
| **M3 offer and place** | `module.crop_farm`, the `farm` fallback, talk naming a module, the card's render and bill, growth A, B and A2 placement, the variant after the plan | M2, growth A2 merged | e2e `module` (farm part) and the survival e2e; a GPT review (spending, undo) |
| **M4 storage hall** | `storage_hall@1`, its measurement, `module.storage_hall`, the tier gate, undo refused while filled | M0, M1, S-M5-3, S-M5-6's answer | section 3.2's measurement passes; e2e `module` (hall part); GPT review |
| **M5 composed** | The plan writer names modules; Steward binds module lots in plan v1; S-M5-4 adopted | M3, M4, S-M5-4 | **phase 5 gate:** a composed settlement with a crop farm and a storage hall, built, each measured in place; the gate-verifier; Noah's look |
| **M6 spine** (optional, Q2) | `connector.items` in plans, with the first automatic module | S-M5-5, a sugar cane module | items carried from an `item_out` to an `item_in`, measured |

Order: M0 and M1 can run alongside A2. M2 after M1. M3 after A2 merges. M4 can run alongside M3. M5 after both. Costs: $0 throughout (designs of
modules are $0; e2e is stub). M5's plan writer change needs a small paid re-record of a few composed plans, with Noah's OK on the spend.

## 9. Questions for Noah (each with a recommended default)

1. **Farm harvesting in v1.** **Default: the player harvests** a watered, lit field; automatic harvest comes with the sugar cane and melon
   farms (observers and pistons) and with villager farmers (phase 6). Alternative: a water-flush harvester on a lever (uproots unripe crops too,
   and needs replanting).
2. **The spine in phase 5.** **Default: not needed for phase 5's gate.** Modules that belong together are placed side by side, and the item
   connector comes with the first automatic module (slice M6). Alternative: build `connector.items` in phase 5 (an Architect slice, a week or
   so on their side).
3. **Undoing a storage hall that holds items.** **Default: refuse and say so** ("empty it first", the containers named). Alternative: the items
   move into the settlement's stockpile (needs Architect's S-M5-6 option).
4. **Autonomous and Full building modules on their own.** **Default: yes, as any proposal**, but in survival Autonomous asks first when the bill
   takes more than half of the stockpile's count of any item. Alternative: modules always ask.
5. **Upgrades and new module versions on a standing farm.** **Default: not in phase 5**: a second field is a second module; in-place upgrades wait for
   Architect to refill an irrigated lot (S-RV-14). Alternative: ask Architect to lift S-RV-14 now.
6. **Vanilla only.** **Default: vanilla blocks only**, the same as every template (principle 5, open question 2). Every module in this catalog is
   vanilla.
