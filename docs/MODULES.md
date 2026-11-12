# Functional modules (phase 5)

Status: design v2.1, 2026-11-12, for review before code. $0: no code and no paid runs. It builds after survival growth A2.
- Noah asked on 2026-11-12 for the design to start now.
- PLAN.md "Phase 5" sets the scope:
  - a verified catalog: a crop farm, and a storage hall with sorting;
  - the "You have seeds" offer;
  - the spine and connectors;
  - the gate: each module's output measured in the dev client, and two modules composed into a settlement.
- Architect asks: ARCHITECT-ASKS.md, "Phase 5: functional modules (S-M5)". Noah's questions: section 9.
- v2 folds in GPT's design review and its fix check (section 10).

## 1. What a module is

A **module** is a building whose job is to do something, such as growing crops or sorting items. Most buildings only need to look right. A
module also has to **work**, and keep working on 26.3. That changes three things:

| | An ordinary building | A module |
|---|---|---|
| Who decides the blocks | Claude designs them: bible, massing, detail; $2.5-4.6. | A **verified program** in Steward's catalog. Claude only chooses the module, its parameters and its place (decision 2026-10-05: "Claude composes and parametrises; it does not hand-wire redstone unverified"). |
| How it is checked | Architect's checker and the critique. | The checker, and a **measurement**: its output counted in the dev client, for each version in the catalog. |
| What it costs to design | A design. | **$0**: one run of the program with the parameters and the settlement's bible roles (principle 1: presets for verified modules). |

**A module is a functional core and a styled shell, from one program.**
- **The core.** The program writes the farmland and crops, or the hoppers, comparators and chests. It writes them in their **settled state**,
  as Architect's GM writes a gate (`gmCircuit` returns the rest state).
- **The shell.** The program dresses the core from the bible's roles: fence, path, lamp, roof and wall. This works like Architect's
  palette-driven kit components (`kit/lib/smalls.mjs`).
- **What is fixed and what is a role.** The core's functional blocks are fixed and never come from a role (a hopper stays a hopper). The roles
  that touch the core are constrained (section 2.2). Every other block is a free role.
- So a module reads as the settlement's own without Claude designing it, and a re-skin costs nothing.

**Where the program runs (S-M5-1, recommended).**
- Steward owns the catalog. PLAN "Boundary with Architect" lists the modules catalog as Steward's.
- Steward ships each module as data: the kit program's source, a parameter schema and the tested parameter envelope.
- Architect runs it as a **given-program design**: a design request that supplies its program instead of having Claude write one. Architect
  runs the kit, the checker and the renders, and installs a library entry with that source, keyed, at $0. A keyed variant with other parameters,
  another bible or reserved cells works as it does for any entry.
- This is one general building block (COMPOSITION section 1), not code for each module.
- **Alternative:** Architect hosts the module programs as kit components written to Steward's spec. That starts faster, but every new module
  and every fix becomes an Architect release.

**A module's identity** is `<id>@<version>` (for example `crop_farm@1`) and the sha of its program source.
- A version is frozen once it is measured. A new layout, circuit or envelope is a new version, measured again.
- A built module carries `steward_mc:module {id, version, programSha, params}` in its lot's ext, so the screens, growth, land check and undo
  know what it is.

**A module never changes by an ordinary path.**
- A module lot refuses free-text change requests and `versionOf`: a redesign would be unmeasured.
- It is never a copy's source, and never follows one.
- A bible re-skin is allowed within the palette constraints (2.2).
- A change of size or version is a new module (section 4.5).

## 2. Verified: measured in the dev client

"Verified" means **measured**, not reviewed. Each catalog version has a **measurement**: a scripted scenario in a dev client (DevBridge,
$0) with **bars fixed before the first candidate is measured**.
- **The results** are committed with the module in `modules/<id>/<version>/measured.json`: the numbers, the bars, the Minecraft, Architect and
  program versions, the seeds and the number of runs.
- **The check** is re-run by the gate-verifier.

### 2.1 Rules

- **No measurement, no offer.** A module is offered only:
  - on the Minecraft version it was measured on;
  - on an Architect version whose RC ran its `module` e2e flow (section 7).

  A version that fails its measurement leaves the catalog. Nothing falls back silently to an unmeasured version.
- **The probe comes first** (slice M0, as Architect's `gm-probe`).
  - **The vanilla rules.** Every vanilla rule a module relies on is checked on the running 26.3 server, with numbers. If a rule fails, phase 5
    stops and reports to Noah:
    - farmland hydration reach and time;
    - crop growth by random tick, light and hydration, with each crop's own maximum age (beetroot's is 3);
    - trampling, and which mobs it needs;
    - a hopper's transfer rate;
    - a comparator's signal from a hopper's fill;
    - a hopper locked by a torch;
    - a full chest at the end of a chain.
  - **The assembled sorter tile.** The probe also chooses and measures the actual tileable sorter circuit (section 3.2) as one assembled
    piece: 20 runs of the full sorting mix, two neighbouring slots saturated, and recovery after they are cleared. The primitives alone don't
    prove the circuit.
- **Random ticks are noisy.** Growth is measured over at least 5 runs at the default `randomTickSpeed` of 3, and accepted on a band around
  the probe's figures.
  - `/tick sprint` speeds the runs up, once the probe has shown that sprinting doesn't change the result.
  - Each module also has an **absolute usefulness bar**: a farm that is technically working but yields too little to be worth offering fails.
- **The dev hooks are Steward's**, in its DevBridge and in dev builds only:
  - insert stacks into a container;
  - count the items in a box's containers;
  - harvest a box's mature crops as a fake player and count the drops;
  - replant;
  - set a gamerule.

  None of these needs an Architect ask.

### 2.2 The certification envelope

What a measurement covers, and so what may be offered:
- **Every discrete layout branch**, not only the corners of the range: every field size in the set (3.1), every slot count (3.2), including the
  plain hall's 0.
- **All four facings**, and placement across a chunk border.
- **Pinned versions**: the program sha, the checker version and the kit version.
- **Palette constraints.**
  - The lamp role must give light 14 or more. Otherwise the program uses its fallback lantern, which is part of the measured envelope and
    shown on the offer ("lit by plain lanterns: your style's lamp is too dim"). This is the only substitution.
  - Roles that touch the core may not be redstone-conductive in the sorter's circuit plane, and may not be gravity blocks.
  - The program checks these and refuses a bible that breaks them. Apart from the lamp, it never substitutes.
- **For the sorter, the item classes** that 3.2 allows.

The checker runs over every branch, each re-skinned by three bibles (one per palette family the strange-ask corpus uses most). M1 does this
on fixture modules. M2 and M4 do it on their modules, and each measures one re-skin in full.

## 3. The first catalog

### 3.1 `crop_farm@1`: a watered, lit field

**What it is.**
- **The field.** Farmland in rows, planted with the crop the player has: wheat, carrots, potatoes or beetroot.
- **Water.** Architect's farm irrigation (IRR: `irrigate: true` on a farm lot) puts one contained source in each 9x9 of the field, and drains
  them first on undo.
- **Light.** Lamps from the bible's lamp role, spaced so every crop cell has light 9 or more at night.
- **Fence.** The field is fenced, with a gate on the lot's front row.
- **The output port.** A composter and a chest by the gate are the field's `item_out` port, where the player puts the harvest.
- **Who harvests.** The player does in v1 (section 9, Q1). There is no redstone and nothing that can jam.

**Geometry** (this matches IRR's own frame, CONTRACT "Phase 6c slice IRR as built": field = u 1..W-2, v 1..D-3).
- The lot is the field plus a one-cell border (the fence) and a front row of two (gate, chest, composter, path): **W = F + 2, D = F + 3**.
- The field side F is one of **9, 18 or 27**. Those are whole multiples of IRR's 9x9 segments, so every source sits where the program left a
  gap.
- Fields need not be square: any pair of these sides.
- The program takes the lot's irrigation cells (`irrigation.lotCells`, IRR) as reserved, and leaves them and the cell above each open.

**Measurement** (per crop and per size):
1. **Hydration.** Every farmland cell reaches `moisture=7` within IRR's as-built bound of 240 s.
2. **Light.** Every crop cell has block light 9 or more at midnight.
3. **Output rate.** Harvestable crops per in-game day:
   - setup: plant, wait T, harvest and count, replant, repeat for 3 cycles, at least 5 runs;
   - T is the probe's time to a mature majority for that crop;
   - recorded as gross drops and as net of replanting stock;
   - bar: within the probe's band for a watered, lit field, and above the absolute usefulness bar;
   - control: the probe's unwatered field is clearly slower.

   The offer quotes this **net rate**. It never quotes a single mature harvest as a rate.
4. **Exclusion.** Mobs are let loose outside the closed fence for 10 in-game minutes. None gets in, and the farmland is unchanged. A player
   gets in and out through the gate.
5. **Restarts.** A restart and a chunk unload mid-growth keep every crop's age, and moisture is back within the bound.
6. **Working the field.** Harvest, then replant, then save and reload, then land check (LC), then undo, all under S-M5-4's rules. A player's
   torch left on the field is still the player's after undo.
7. **Survival.** The field is placed from a stockpile:
   - the whole growth's bill equals what was consumed (section 6);
   - refunds on undo follow 6's table.

**Limits.**
- From IRR: a field side under 3 is asked nothing.
- A freezing biome freezes the sources, so the module isn't offered there.
- An irrigated lot is never refilled (S-RV-14).

### 3.2 `storage_hall@1`: chests that sort

**What it is.**
- An **input chest** at the hall's door, the `item_in` port. One hopper line runs from it under a row of filter slots.
- **Each slot.** A filter hopper holds its item, and a comparator reads it. The slot passes only that item down into its chest. The concrete
  tileable circuit is the one the probe measured (2.1).
- An **overflow chest** at the end of the line takes everything not filtered.
- A bank of plain double chests for bulk storage.
- A sign and an item frame show each slot's item.
- The walls, roof, floor and lamps are styled from the bible's roles.

**Parameters.**
- `slots`: 0, 4, 8, 12 or 16. A hall with 0 slots is plain: chests and the input chest, no redstone.
- `items`: one filter item per slot, chosen at the offer from what the settlement has most of (the stockpile and the player's carried items,
  by count). The player can change them before accepting.
- `bulk`: 0 to 8 double chests.

**The filter item domain** (enforced by the program):
- **Allowed:** items that stack to 64, are plain (no components, names or enchantments) and are distinct, with no duplicate filters.
- **Never a filter:** the filler item, a fixed item recorded with the module. Unstackable items, items that stack to 16, and items that carry
  components always go to overflow.
- The offer names the item classes the hall won't sort.

**Throughput** comes from the single input line: one hopper chain. Slot count doesn't add capacity. The offer says "about N items a second"
from the measured rate.

**Filter items are the hard part** (S-M5-3).
- Each filter hopper must hold an exact count of its item plus fillers.
- Architect writes template and construction-target containers empty, so without a change the hall would sort nothing.
- **Asked:** a given-program entry may declare filled containers, with a transactional lifecycle:
  - **Locked while built.** The filter hoppers are built locked: the circuit's lock is powered until activation.
  - **Filled once.** They are filled once, after every cell of the core stands, as one keyed and journaled step.
  - **Then unlocked.**
  - **Refunds.** A cancel before the fill refunds no filter items, because none were written. Filter items in place are refunded as
    construction. Anything else in a container is cargo (section 6).
- **Fallback if refused:** a hall with `slots = 0` only.

**Measurement** (deterministic, so the bars are exact):
1. **Sorting.**
   - The test mix: each slot's item times 3 stacks, 5 plain kinds the hall doesn't filter, an unstackable item, a 16-stack item, an item with
     components, and the filler item itself.
   - Bar: every filter item ends in its slot, everything else in overflow, and the input chest is empty. **100%**, items in = items out.
2. **Throughput.** Items a second, end to end. Bar: within 10% of the probe's single-line rate.
3. **Saturation.** Two neighbouring slots are filled to the top, then fed more of their items.
   - No wrong item lands in any chest, nothing is lost, and every other slot still sorts 100%.
   - Once the two slots are emptied, all of them sort 100% again.
4. **The filters hold.** After 10,000 items, each filter hopper still holds exactly its filter count.
5. **Restarts.** A restart and a chunk unload mid-sort lose and duplicate nothing, and sorting continues.
6. **Survival.**
   - The whole growth's bill (hoppers, comparators, chests, redstone, the filter items and fillers) equals what was consumed.
   - **Crash cases around activation:**
     - a cancel before the fill: no filter items are written, and none are refunded;
     - a crash between the fill and the unlock: the fill is not repeated;
     - a filter hopper holding one extra item of its own kind: undo refused as cargo;
     - crashes during capture, during removal and during refund crediting: refunded exactly once, never minted.

**Tier.**
- Hoppers need iron and comparators need nether quartz.
- In survival the sorting hall is offered from the Nether tier on. Progress already reads the tier.
- Before that, the plain hall (`slots = 0`) is offered: one of the measured branches.

### 3.3 Next, not in the first catalog

- Sugar cane and melon/pumpkin farms: observer and piston harvesters, the first modules with an automatic `item_out`.
- An animal pen and a smelter array.
- The trading hall: phase 6, with villagers.

Each needs its own probe items and measurement.

## 4. Offering and placing

### 4.1 The offers

The steward offers modules through phase 3e's proposal rules and guardrails: rejections remembered, cooldowns, at most three open, one per
20 minutes, worded by Claude. Two new rules:

| Rule (key) | Sign | Offer |
|---|---|---|
| `module.crop_farm` | the player carries 8 or more seeds of a crop the catalog grows (today's `farm` sign) | "You carry wheat seeds. I can lay a watered, lit field by the town: about N wheat a day once it's going." N is the measured net rate for that size. |
| `module.storage_hall` | the stockpile or the chests in the claim hold 12 or more kinds of items, or the player's inventory is full twice within 20 minutes | "Your stores are getting mixed. A sorting hall would put your cobblestone, iron, wheat and wood in their own chests." |

**Eligibility.**
- **Before the offer.** It is decided before anything is offered (section 4.3): the town's path, features, biome, tier and the Minecraft
  version. An ineligible module is never offered.
- **At Accept.** It is checked again, because the town may have changed. If the module is ineligible by then, the steward says so. A
  decorative substitute is a **separate offer with its own price** ("a working field can't go here because …; a decorative farm would cost
  about $3"), never a silent switch from a free module to a paid design.

**Coalescing and declines** (`ProposalRules.propose`: today a rule is skipped when its key was declined, its key is open, or a building of its
type stands):
- **Type checks by module.** For module rules the type check reads **module identity**. A standing module of the same id suppresses the
  offer. A decorative `farm` building does not, so a town with a decorative farm can still get the working one, **once**.
- **Declines.**
  - Declining `module.crop_farm` also retires `farm` for that settlement: the player has said no to farms.
  - Declining the old decorative `farm` doesn't retire the module offer.
- **Saves.** No save migration: keys are strings, and the module's ext tags the built lot.

**Seeds and the stockpile.**
- **Patron.** The field is free, and it is planted at placement.
- **Supplied.** Construction draws only from the stockpile. The offer sizes the field from the stockpile plus what the player carries, and
  says so: "put 160 wheat seeds in the stockpile".
- **Material requests.** They name the seeds as any material. The steward never takes from the player's inventory.

**Asked in talk.** "Build me a farm" or "I need somewhere to sort my stuff" goes through the talk job. The job can now name a module (`module:
crop_farm` and its parameters) in the proposal it puts in the inbox. Its schema takes the module ids from the live catalog.

**Autonomous and Full** may build a module offer on their own, within the weekly allowance. A module design costs $0. In survival, Autonomous
asks first when the **whole growth's bill** takes more than half of the stockpile's count of any item (Q4).

### 4.2 Placing: a module is an addition on growth

| The town | Path | Module lot |
|---|---|---|
| a region town (V, plan v1), instant | growth A: a `planRevision` grow | grow lot `{id, type, size [W, D], ext, irrigate (farm)}` |
| a region town built from materials | growth A2 | the same lot, billed from the stockpile |
| a street town (east-west street), instant, not Supplied | growth B: adopted at its first growth, then A | the same lot |

**The order inside the build:**
1. `planRevision` with the module lot at its exact size.
   - If `NO_ROOM`, Steward tries the next smaller size of the discrete set **as a new plan**, and says so.
   - It never accepts a box other than the one asked for.
2. From the revision plan, read the lot's box, frame and `irrigation` (S-M5-2).
3. **The variant.** The keyed variant of the module's catalog entry with the parameters, the bible and the irrigation cells as reserved.
   - Its key is per lot: `steward.<build>.module.<lotId>`, so two module lots never share one.
4. **The binding.** Saved with the build (`moduleBindings`, BuildStore format 5) before the realise:
   - `lotId -> {entryId, version, programSha, params, bibleVersion, geometrySha}`;
   - `geometrySha` covers the lot's box, frame and irrigation cells.
5. **The bill.** The whole operation's bill: the revision's parts (lanes, lamps, irrigation shells), every bound lot entry, and the filter
   fill (S-M5-3). It comes from a read-only quote of the revision plan with the final `lotEntries` (S-M5-7). `planRevision` returns no bill, and
   Architect's plan BOM leaves out unbound lots.
   - The quote is compared with the bound approved at Accept (4.4), and Q4's threshold applies to it.
   - Over the bound, the build pauses and asks again before anything is consumed.
6. **Realise.** The module entries go straight into `lotEntries` beside the designed ones, outside the design group. DS's site variants are
   bound the same way. A module lot whose entry is missing refuses the realise; it is never left as a pad.

**A stale plan** (`parent_moved` during design, growth A's re-plan under `vgrow3`).
- If the re-planned lot's `geometrySha` changed, the variant is **made again** for the new geometry, under a new key
  `steward.<build>.module.<lotId>.<n>`, and the binding is replaced.
- Designed lots keep their designs by id, as now.

**Recovery.** After a restart, the binding and the key find the variant that was made (`variantByKey`, S-M5-1). Once a binding is saved, a
variant is never made twice.

### 4.3 Where a module isn't placed (yet)

- **Districts:** district growth (`dgrow`, D-S2) isn't built.
- **North-south streets, Supplied street towns** (not adopted, S-AD-5) and the **legacy street path**: there is no region to irrigate. Lot
  designs never own fluids (IRR).
- **Freezing biomes** (the farm), an Architect without the S-M5 features, and an unmeasured Minecraft version.

The steward doesn't offer the module in these cases. Where the old `farm` rule fires, its decorative offer is made as today, priced.

### 4.4 What the player approves

The proposal shows:
- the catalog's render of the module, re-skinned to the settlement's bible;
- the measured rate;
- in survival, an **upper bound on the whole bill**, per item: the catalog's measured BOM for that size plus a bound on the growth's own work
  (lanes, lamps, irrigation shells), recorded from the measurement runs.

Accept approves that bound. Step 5 of 4.2 holds the build to it.

### 4.5 Growth and revisions over a standing module

- **A module is a lot like any other** to growth. GR never writes under a standing lot box +1, and a later addition can sit beside it.
- **No in-place upgrades** in phase 5 (Q5). Refilling an irrigated lot is refused (S-RV-14), and a redesign would be unmeasured. A second
  field is a second module lot.
- **Land check.** A worked field must not read as "changed" every day. Crop age and moisture are volatile already. Harvested cells,
  replanted crops and a module's standing score need S-M5-4.

### 4.6 Composing modules into a settlement (plan v1)

When a card asks for working things ("a farming village that feeds itself", "a trading post with a sorting warehouse"), the plan writer can
name modules as lots of a composed plan:
- **The lot.** A module lot has a `type`, `ext steward_mc:module` with its parameters, the exact `size` from 3.1's geometry, and `irrigate` for
  a farm.
- **The plan writer's catalogue copy** gains the live module list: ids, sizes, what each needs.
- **Binding.** Steward binds each module lot's variant at the Start re-check, from the artifact's `$.lots[]` (box, frame, `irrigation`), with
  the same bindings and the same bill bound as 4.2.
- **The neighbours.** The ordinary buildings around the modules are designed as today.

**The two-module gate** is met here: a plan v1 settlement whose plan holds a crop farm and a storage hall, built, and each measured in place
(section 7). The hall's input chest stands by the farm's gate on the plan's path. Item transport between them is the spine (section 5).

## 5. The spine: item connections between modules

**What exists.** PLAN's sketch has output hoppers feeding a central spine. Architect today has:
- **Ports.** Blueprints declare `item_out` and `item_in` ports (CONTRACT "Ports").
- **The link pass.** 7b's link pass (LP) joins **walk** ports between lots at realise. It doesn't move items, and a revision's realise has no
  links stage (S-7b-3).

So an item spine needs a new building block, **`connector.items`** (S-M5-5). Its semantics:
- **The ends.** Each port has a direction and height. A connector runs from an `item_out` container to an `item_in` container.
- **Extraction.** A hopper under or beside the source container pulls from it. The source is never emptied by anything else.
- **Transport.** A hopper chain up to 16 cells long. Water on ice beyond that only once its own probe passes, because item entities in water
  are a different risk: unloading, despawn timers and pickup.
- **A full sink.** When the destination is full, items back up in the chain. Nothing drops or is lost.
- **Unload and restart.** Nothing is lost or duplicated across a chunk unload or a restart.
- **Removal.** Removing either module's lot first refuses while a connector depends on it, or takes the connector with it, Architect's
  choice. The connector's own hoppers holding items count as cargo (section 6).
- **Growth.** It works in a revision's realise.

**Phase 5's default (Q2)** is to **defer the spine**. This changes PLAN's phase 5 scope, so it is put to Noah as a scope question, not
assumed.
- A player-filled farm chest *could* feed the hall through a connector. The default doesn't, because a hopper chain between two
  player-fed chests saves only a few steps.
- **The alternative** is a bounded slice M6 in phase 5: hopper chains only (16 cells or fewer), from the farm's chest to the hall's input,
  measured with section 3.2's bars.

## 6. Survival: billing and undo

### Billing

A module is billed like any building, from the settlement's stockpile (phase 4), through growth A2 for region towns. **The bill is the whole
revision's bill**, not only the variant's (4.2 step 5):
- **Crops** cost their seed. Architect's survival COSTS map crop blocks to seed items.
- **Farmland** costs dirt (CU treats farmland and dirt as one). **Water** is free (N-H-1).
- **Hoppers, comparators, chests and redstone** are ordinary items.
- **Filter items and fillers** come under S-M5-3.
- **Masked cells.** Template cells masked out by irrigation are not billed. They are Architect's `droppedBlocks`, and the shells are billed in
  the zone's `cost.bom`.

**Material requests** name the module's part ("the sorting hall waits for 3 hoppers").

### Undo

A module lot goes like any growth: `undoRevision` under growth A, or with the town. Irrigation is drained first (IRR). The module-specific
rules:

| What the module holds | Today (Architect) | Phase 5 |
|---|---|---|
| our crops, at any age | `age` is volatile, so they are ours | refunded as Architect refunds standing blocks |
| a harvested cell (air on our farmland); farmland trampled to dirt | mined, so not refunded; farmland and dirt interchangeable | nothing refunded for the crop |
| a crop the player replanted on a field cell | the player's block (a PT record) | **S-M5-4:** it goes with the lot, not a removal blocker; its planting item (one seed) is returned **once** as cargo, never the original construction seed |
| any other player block on the field (a torch, a sign) | the player's | unchanged: kept, or a blocker, as PT says |
| a chest, hopper or composter holding items (cargo) | "a container the player filled is not ours": refuses Remove | **refused and said** (Q3): "the sorting hall still holds 1,240 items in 9 chests; empty it first", with the containers named. Nothing is lost or duplicated. Optional S-M5-6: the cargo moves into the stockpile |
| filter hoppers (S-M5-3) | their contents change as items pass | ours while they hold exactly their declared contents. **Anything more, even more of the filter's own item, is cargo and blocks removal** like any cargo (Q3). Cleared, the contents are captured in the same tick the hopper is removed (no transfer in between, no vanilla drop) and refunded as construction through the journal's durable replay |

**Standing and reconcile.** A worked field is not "partly built" or damaged (S-M5-4):
- Architect's standing score compares template blocks, so its field cells are judged by the field rule, not block by block.
- LC, removal blockers, refunds and the standing score each follow the field rule.

**Crash and restart.**
- A module build is a growth (A, A2, B), with their keys, recovery and save formats.
- BuildStore format 5 (`moduleBindings`) comes with a `SaveCompatTest` fixture.

## 7. How it is tested

- **Unit tests:**
  - the proposal rules: signs, keys, identity-based coalescing, declines both ways, eligibility at the offer and at Accept;
  - parameter choice: seeds, stockpile and room to the field size; the stockpile to the hall's items, with the item domain filtered;
  - the catalog's gating: versions, features, tier, biome, palette constraints;
  - the lot spec and geometry (W = F + 2, D = F + 3);
  - bindings and their `geometrySha`, re-binding after a stale plan, and the bill bound.
- **Module measurement** (`tools/module-measure.mjs`, in its own dev client on the e2e ports):
  - section 3's items over the whole certification envelope (2.2);
  - results go into `measured.json`, and the gate-verifier re-runs them.
- **e2e (stub), the `module` flow:**
  - a V town accepts a crop farm (a growth with an irrigated module lot): hydration, then harvest, replant, LC and undo;
  - a storage hall sorts a short mix (30 s);
  - undo of the hall is refused while it holds cargo, then it is emptied and undone;
  - two module lots in one build, with a restart between the variant and the realise.

  The flow is short enough for the sanity set of an Architect RC that touches regions, fluids or growth, and it runs nightly.
- **Survival e2e:**
  - the farm from a stockpile, with partial feeding: the bill comes in, the materials are consumed, and an undo mid-build refunds them;
  - an operation whose quote is over the approved bound, with a fully stocked stockpile: the build pauses and nothing is consumed;
  - the sorter's crash cases (3.2 item 6).

## 8. Slices and gates

**Process.** Each slice is one Opus session in its own worktree (AGENTS.md). A module's gate is its measurement, re-run by the gate-verifier.

**The gap map.** The row "working modules" (`fixtures/strange-asks`) moves from *waits* to *nearest version* at M3, and to *built* at M5.

| Slice | What | Needs | Gate |
|---|---|---|---|
| **M0 probe** | Section 2.1's vanilla rules and the assembled sorter tile, measured on the running 26.3 server; `tools/module-probe.mjs`; Steward's dev hooks; the usefulness bars fixed | nothing | every rule measured, numbers committed; the sorter tile's 20 runs pass; any failure stops the phase and goes to Noah |
| **M1 catalog** | The catalog format (program, schema, envelope, `measured.json`), gating, palette constraints, bindings (BuildStore 5) | S-M5-1 | a module entry installed at $0 by key; every branch re-skinned by three bibles, checker clean; unit tests |
| **M2 crop farm** | `crop_farm@1` and its measurement | M0, M1, S-M5-2, S-M5-4 | 3.1's measurement passes over its envelope (items 1-7); gate-verifier |
| **M3 offer and place** | `module.crop_farm`, identity coalescing, eligibility, the priced decorative substitute, talk naming a module, the bill bound, growth A, B and A2 placement, re-binding after a stale plan | M2, growth A2 merged, S-M5-2 and S-M5-7 shipped | e2e `module` (farm) and the survival e2e; GPT review (spending, undo, persistence) |
| **M4 storage hall** | `storage_hall@1` and its measurement, `module.storage_hall`, the tier gate, undo refused while it holds cargo | M0, M1, S-M5-3 (the plain hall, `slots = 0`, may ship before it as an interim) | 3.2's measurement passes over its envelope, the crash cases included; e2e `module` (hall); GPT review |
| **M5 composed** | The plan writer names modules; Steward binds module lots in plan v1 | M3, M4 with sorting | **phase 5 gate:** a composed settlement with a crop farm and a storage hall **with at least 4 sorting slots**, built, each measured in place (sorting included); gate-verifier; Noah's look. A plain hall does not meet it |
| **M6 spine** (if Q2 says so) | `connector.items`, hopper chains only, from the farm's chest to the hall's input | S-M5-5 | items carried, measured with 3.2's bars; removal in both orders |

**Order.**
- M0 and M1 can run alongside A2.
- M2 comes after M1 and S-M5-2/4.
- M3 comes after A2 merges. M4 can run alongside M3.
- M5 comes after both.

**Cost.** $0 throughout: module designs are $0 and the e2e is stub. M5's plan-writer change needs a small paid re-record of a few composed
plans, with Noah's OK on the spend.

## 9. Questions for Noah, each with a recommended default

1. **Harvesting in v1.**
   - **Default:** the player harvests a watered, lit field. Automatic harvest comes with the sugar cane and melon farms (observers and pistons)
     and with villager farmers (phase 6).
   - **Alternative:** a water-flush harvester on a lever. It uproots unripe crops too, and needs replanting.
2. **The spine in phase 5.** This is a scope change to PLAN's phase 5.
   - **Default:** defer it to the first automatic module. Modules that belong together are placed side by side.
   - **Alternative:** slice M6 in phase 5, hopper chains only, from the farm's chest to the hall. It needs Architect's S-M5-5.
3. **Undoing a storage hall that holds items.**
   - **Default:** refuse and say so ("empty it first", with the containers named).
   - **Alternative:** the cargo moves into the settlement's stockpile. This needs Architect's S-M5-6.
4. **Autonomous and Full building modules on their own.**
   - **Default:** yes, like any proposal. In survival, Autonomous asks first when the whole bill takes more than half of the stockpile's
     count of any item.
   - **Alternative:** modules always ask.
5. **Upgrades on a standing farm.**
   - **Default:** none in phase 5. A second field is a second module, and in-place upgrades wait for Architect to refill an irrigated lot
     (S-RV-14).
   - **Alternative:** ask Architect to lift S-RV-14 now.
6. **Vanilla only.**
   - **Default:** vanilla blocks only, as for every template (principle 5, open question 2). Every module in this catalog is vanilla.

## 10. Review record

**GPT design review, round 1** (gpt-6.1-sol, at 25f8c82): request changes, with 5 blockers, 5 should-fixes and 1 nit. All are taken.
- **B1. Irrigated growth isn't built.** `grow.mjs` doesn't forward `irrigate`, and `revise.mjs` adds a fluids stage only for ponds. Taken:
  S-M5-2 now asks for the whole lifecycle, with its gate.
- **B2. Variant binding and recovery.** Taken:
  - bindings per lot, with `geometrySha`;
  - per-lot keys, and a keyed variant (S-M5-1);
  - the variant made again after a stale plan;
  - direct `lotEntries`, and a refusal when an entry is missing (4.2).
- **B3. Filled containers need a transaction.** Taken:
  - S-M5-3's lifecycle: built locked, filled once, then unlocked;
  - cargo kept apart from construction refunds;
  - capture on removal in the same tick;
  - crash tests (3.2).
- **B4. The variant's BOM isn't the bill.** Taken: the whole revision's bill, against a bound approved at Accept (4.2, 4.4). Seeds come
  through the stockpile (4.1).
- **B5. Field rules come after the farm ships.** Taken: S-M5-4 is a prerequisite of M2. It now covers standing and reconcile, the planting
  item returned once, and a foreign block that stays the player's.
- **S6. The sorter's item domain and circuit.** Taken:
  - the probe measures the assembled tile;
  - the item domain is restricted, with a fixed filler and no duplicates;
  - saturation and recovery are tested;
  - throughput comes from the single input line.
- **S7. The farm's claims.** Taken:
  - crop-specific maturity (beetroot's is 3);
  - the net rate, measured at the quoted elapsed time;
  - bars fixed before measuring, and a usefulness bar;
  - hydration within IRR's 240 s;
  - an exclusion test instead of the trampling test.
- **S8. The certification envelope.** Taken (2.2):
  - every layout branch, all four facings, chunk borders;
  - pinned versions, palette constraints;
  - change requests, copies and follows refused for modules.
- **S9. Coalescing by type, and a paid fallback.** Taken: coalescing by module identity, declines both ways, eligibility at the offer and
  at Accept, the decorative substitute as its own priced offer.
- **S10. The spine.** Taken: Q2 is put as a scope change. S-M5-5 now has its transport semantics, and M6 is bounded.
- **N11. Geometry.** Taken: W = F + 2 and D = F + 3 from IRR's frame, the discrete sizes, and a smaller size only as an explicit new plan.

**GPT fix check, round 2** (at 48aedba): 9 of the 11 findings resolved. B3 and B4 were only partly resolved, and the check raised 2 new
blockers, 2 should-fixes and 1 nit. All are taken. This was the last round.
- **R2-B1. The bill has no source.** `RegionPlan` carries no bill, and the plan BOM leaves out unbound lots. Taken: new ask S-M5-7, a
  read-only quote of a revision plan with its final `lotEntries`, plus a gate for an over-bound bill with nothing consumed (4.2 step 5, 7).
- **R2-B2. Excess filter items bypass the cargo refusal.** Taken:
  - any content past the declared fill is cargo and blocks removal, even of the filter's own kind;
  - captured contents are credited through durable replay;
  - crash cases added for capture, removal and crediting (6, 3.2, S-M5-3).
- **R2-S3. A plain hall can meet the gate.** Taken: the phase gate needs a sorting hall with at least 4 slots; the plain hall ships only as
  an interim.
- **R2-S4. M1's gate depends on M2 and M4.** Taken: M1 checks fixture modules; the full re-skin measurements move to M2 and M4.
- **R2-N5. The lamp fallback contradicts the no-substitution rule.** Taken: the lantern is the one disclosed exception, inside the measured
  envelope.
