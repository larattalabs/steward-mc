# Functional modules (phase 5)

Status (2026-11-13): design v2.2 (2026-11-12, with Noah's decisions), being built at $0. **M0** (the probe, `docs/PROBE-M0.md`) and **M1** (the
catalog) are merged; **M2** (the crop farm) and **M3** (offer and place) are being built, M3 ahead of M2 on M1's fixture module by design, and M2
merges first (Noah, 2026-11-13). M4 waits on Architect's FK (S-M5-3), then M5. Section 9 holds the decisions that override the earlier sections.
- Noah asked on 2026-11-12 for the design to start now.
- **Scope, as Noah set it on 2026-11-12** (PLAN Decisions):
  - **Phase 5, the first catalog:** a crop farm and a storage hall with sorting, the "You have seeds" offer, and two modules composed into a
    settlement. Its gate is slice M5.
  - **Phase 5b, automation and the spine** (section 3.3), right after M5 and before phase 6: automatic farms, then the spine
    (`connector.items`), then the animal pen and the smelter array. Its gate is items from an automatic farm, carried by the spine and sorted
    in the hall, measured.
- Architect asks: ARCHITECT-ASKS.md, "Phase 5: functional modules (S-M5)". Noah's answers: section 9.
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

### 3.3 Phase 5b: automation and the spine

Noah, 2026-11-12: the rest of PLAN's original phase 5 comes right after the M5 gate, ahead of phase 6. Each module follows the same rules as
the first catalog:
- its own probe items, added to M0's probe and measured on the running server before the module is built on them (2.1);
- its own certification envelope (2.2) and measurement, with bars fixed before the first candidate is measured;
- offered and placed as in section 4, billed and undone as in section 6.

**The modules, in order:**
1. **Automatic farms** (slice M7): `sugar_cane_farm@1` and `melon_farm@1` (melons and pumpkins, one module with a `crop` parameter).
   - **How they work.** Observers watch the crop's growth cell. A piston breaks the grown block (the cane above its base, or the melon or
     pumpkin beside its stem). The drops are collected by hoppers under the field into a chest, the module's automatic `item_out`.
   - **Probe items:** an observer's pulse on growth; a piston breaking cane and a melon, with the drop it leaves; hopper pickup of dropped
     items from the field's floor; and cane or stem growth on hydrated or irrigated soil.
   - **Measurement:** output per in-game day into the chest, net, over at least 5 runs; nothing left on the ground after a cycle; no piston
     or observer left in a stuck state after a restart or a chunk unload.
   - **Water:** sugar cane needs water beside each base. It comes from IRR-style contained sources or an H channel (an ask, decided with
     the M7 design, section 9).
2. **The spine** (slice M8): `connector.items` (S-M5-5), hopper chains between modules, from an `item_out` to an `item_in` (section 5).
3. **The animal pen and the smelter array** (slice M9).
   - **The pen:** a fenced pen with a gate, a feeding trough, and later breeding and harvest into a chest.
   - **The smelter array:** input, fuel and output chests on a line of furnaces, fed by hoppers. Its input chest is an `item_in` port and
     its output chest an `item_out` port, so the spine can feed it from the hall's overflow, or from a farm.

**The 5b gate** (slice M8's measurement, re-run by the gate-verifier): an automatic farm's output, carried by the spine into a storage hall
and sorted there. Measured end to end: items harvested = items in the hall's chests, each in its slot or in overflow, over at least 3 in-game
days; nothing lost at the joins; a restart and a chunk unload mid-flow included.

**Still for phase 6:** the trading hall, with villagers.

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

**When:** in **phase 5b**, slice M8, after the first automatic farm (Noah, 2026-11-12, Q2). Phase 5's first catalog has no spine. Modules
that belong together are placed side by side by the plan, with the hall's input chest by the farm's gate.

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

**Phase 5b** (section 3.3), right after M5:

| Slice | What | Needs | Gate |
|---|---|---|---|
| **M7 auto farms** | `sugar_cane_farm@1` and `melon_farm@1`: their probe items, envelopes and measurement; offers (a sugar cane or melon sign) and placement as in section 4 | M5; the water ask decided with M7 (section 9) | 3.3's measurement passes over each envelope; gate-verifier |
| **M8 spine** | `connector.items` in plans and growth, between an `item_out` and an `item_in`; billed and undone | M7, S-M5-5 | **the 5b gate:** an automatic farm's output carried by the spine and sorted in the hall, measured end to end (3.3); removal in both orders; gate-verifier; GPT review (accounting, removal) |
| **M9 pen and smelter** | the animal pen and the smelter array, each with its probe items and measurement; the smelter fed by the spine | M8 | each module's measurement passes over its envelope; gate-verifier |

**Order.**
- M0 and M1 can run alongside A2.
- M2 comes after M1 and S-M5-2/4.
- M3 comes after A2 merges. M4 can run alongside M3.
- M5 comes after both.
- Phase 5b: M7 after M5, then M8, then M9.

**Cost.** $0 throughout: module designs are $0 and the e2e is stub. M5's plan-writer change needs a small paid re-record of a few composed
plans, with Noah's OK on the spend.

### M1 as built (2026-11-12)

- **The catalog.** `modules/<id>/<version>/` holds `program.mjs` (sent exactly as stored; its sha256 is the identity), `module.json` (the params
  schema, the envelope's discrete `branches`, facings and bibles, the `palette` constraints, the kit and checker pins) and `measured.json`.
  `modules/catalog.json` lists the live entries; it ships in the jar (`steward/modules/`) and is empty until M2. `.gitattributes` keeps the
  programs' bytes (no line-ending changes).
- **Fixtures** live in `fixtures/modules/catalog/` and load only from a directory (unit tests, the dev hook); the live loader refuses one.
  `fixture_plot@1`: a fenced plot of farmland and a ripe crop (the core), path, fence and lamps from the bible (the shell), sides 5 and 9 x wheat
  and beetroot, re-skinned by **rustic, fortress and desert**. The strange-ask cards carry no palette field, so section 2.2's "palette family the
  corpus uses most" can't be read from them; these three built-in Styles span wood, stone and sand.
- **Certification.** measured.json certifies only with `result: "pass"`, the program's sha, and the `moduleSha` of module.json's certified
  fields (program sha, params schema, envelope, palette rules, pins; not the title), measured on the pinned kit and checker. A missing, failed or
  stale certificate offers nothing. `tools/module-catalog.mjs record` writes one only from passing offline and in-game runs over these exact
  files and versions. A dev measurement run (`measuring`, development environment only) may install an uncertified entry, to measure it.
- **Gating** (`ModuleGate`): a certified entry, measured.json's Minecraft version and Architect (mod) versions, Architect's `givenPrograms`, and
  fixtures only for a dev caller. A module with a catalog row the loader could not keep (a fixture in the live catalog, a duplicate) is blocked
  whole. Only a module's newest version is judged; a refused newest version is never replaced by an older one. Params must be
  one of the envelope's measured branch values (other params stay at their defaults), canonical with sorted keys.
- **Palette constraints** (`PaletteCheck`, mirrored in the program): the lamp role under light 14 becomes the fallback lantern, said; a gravity
  block on a core role, or a conductive one in a circuit plane, is refused before Architect is asked. A program that refuses a bible throws
  `palette: ...`; Architect reports a throw as detail `program`, with the message (its `palette` detail is for bibles it can't resolve).
- **Variants** are always made from the entry's own catalog install (by its key), never a caller's entry id, and with the bible resolved to
  its exact version before the palette check, so what was checked is what Architect runs, also after a restart.
- **Keys.** The catalog entry: `steward.catalog.<id>.v<version>.<sha12>` under owner `steward_mc:catalog` (Architect's keys take no `@`). A lot's
  variant: `steward.<build>.module.<lotId>`, `.<n>` after a re-plan.
- **Bindings.** `steward_mc:module {id, version, programSha, params}` is the lot ext (`ModuleRef`). BuildStore format 5 keeps `moduleBindings`
  (lotId -> id, version, program sha, params, entry id, bible, `geometrySha`, and the exact opKey and owner). A format-4 file reads with none;
  `fixtures/saves/v6/steward-builds.json` is a format-4 growth checkpoint written by main's writer (no non-empty one existed in a dev world).
  M3 makes the bindings; M1 carries them through every save.
- **The check** (`tools/module-catalog.mjs`, mod/DEV.md): offline on the kit at the pinned tag, and in game through Steward's own code
  (`/steward dev module`). Architect exposes no kit or checker version at runtime, so those pins are recorded, not gated. Facings and chunk
  borders are placement properties: M2's measurement covers them, not M1's checker run.

### M2 as built (2026-11-13)

- **`crop_farm@1`** (`modules/crop_farm/1/`, program sha `c406df05…`, certified on Minecraft 26.3, Architect 0.64.1, kit/checker 0.59.0; fully re-measured from the 0.63.0 certificate, docs/MEASURE-M2.md).
  - **Sizes and crops.** Field sides 9, 18 or 27 (W = F + 2, D = F + 3); wheat, carrots, potatoes or beetroots. Three Styles: rustic,
    fortress, desert.
  - **The field.** Farmland at frame y 0, written `moisture=7`, with crops at age 0 (a ripe crop billed at one seed would be a free
    harvest). Template row 0 is a dirt layer, groundY 1.
  - **Water and light: the well.** IRR's grid is left open, and the program computes it itself, with IRR's rule, as well as taking the
    lot's reserved cells. A fence post stands over each of a source's 4 farmland sides, since FC drops the sides from a field, so a crop
    there would read as changed. One lamp per source sits on the back post, which lights the middle of a 27-wide field. That costs 5 field
    cells per 9x9.
  - **The fence.** The fence and gate stand on the `foundation` role; the lamps are on the corner and gate posts.
  - **The port.** The composter and chest (`item_out`) stand **just inside the gate**, on the foundation role. 2 more field cells.
    - Measured against two earlier candidates. Outside, against the fence, mobs climbed them over it.
    - On a path, vanilla turned the path to dirt (a standing mismatch).
  - **Crop counts.** A 9x9 holds 74 crops, an 18x18 302, a 27x27 682. Every rate is over those cells.
  - **Palette.** `noGravity`: path, frame, foundation. The lamp's fallback is the lantern, which is also all three Styles' lamp.
- **`CropFarmLot.growLot(id, width, depth)`**: the grow lot `{type: crop_farm, size, irrigate: true, field {cells, crops: all 4}}`, from the
  program's own layout. The measurement checks the Java cells equal the program's on every growth. M3 uses it.
- **The measurement** (`docs/MEASURE-M2.md`, `tools/module-measure.mjs`, `tools/lib/crop-farm-bars.mjs`): §3.1 items 1-7 as amended by
  section 9, and Noah's 3b answer (2026-11-12, "a").
  - Usefulness is judged on G3's ripen-as-you-go basis, from the per-cell maturity times measured in the farm (each run's first planting
    followed through all 3 cycles; the judge was amended once after measuring, made stricter).
  - The band, and the rate the offer quotes, are the fixed-cycle protocol rate. Wheat's is 18 a day for a 9x9, 180 for a 27x27.
  - It ran over the whole envelope (36 branches x 5 runs, all four facings, chunk corners, three Styles) and passed. The verdicts are in
    MEASURE-M2.md.
- **Recording.** `tools/module-catalog.mjs record` certifies a real module only with its measurement's evidence: every step passed on these
  exact files and versions, with field steps of 5 runs or more. It writes `bounds` (the whole growth's quoted bill per params and
  measured Style, with each palette role's share of the lot: `[{params, style, bom, roleParts}]`, MEASURE-M2.md "For M3") and `rates` (`{params, perDay, item}`) for M3.
- **Dev hooks** (ModuleDev, development environment only): `cells`, `growLot`, `takeItems`, `mobs` and `walk` (a fake player's collision
  walk). Harvest and replant use Architect's PT hooks (`dev.pt.breaks`, `dev.pt.uses`), so a player's crops are PT-recorded.
- **No billing or undo code changed** in Steward: M2 measures Architect's.

### M3 as built (2026-11-13)

Branch `m3-offer-place`. It was built on M1's dry `fixture_plot@1` behind the dev gate while M2 measured, then switched to `crop_farm@1` once M2
merged. The module id is a parameter of every path:
- `ModuleKind.CropFarm` takes M2's `CropFarmLot.growLot` as its lot and `CropFarmLot`'s own field as its cells (a 9x9 plants 74).
- Its sizes are every width x depth of 9, 18 and 27, the most cells first.
- Carrots and potatoes are planted from the crop itself.
- It reads `measured.json`'s `bounds` and `rates`.

The e2e `module` flow offers it through the player's own gate (the live catalog). `module-survival` uses the live catalog under its real
certification gate, with a dev-only override bound that holds its bill on purpose, and checks the quote against crop_farm's measured bound.
- **The offer** (`ModuleKind`, `ModuleOffer`, `service/ModuleOffers`). Rule `module.<id>`; its sign is 8 or more seeds of a crop it grows. It
  is offered before the decorative `farm`, and only where it can grow the town:
  - the paths: a region town built at once (A); one built from materials and fully built (A2); a Patron, instant, east-west street town Architect
    can adopt (B, then A);
  - never a district, a freezing biome (a warm-only kind), a town in the wrong mode, or a style whose roles break the palette rules;
  - a watered module never goes to a town it would adopt: Architect's IG can't irrigate an adopted region (ask **S-M5-8**).

  Its params are the crop carried most and the largest size those seeds plant (a town built from materials counts its stockpile too). In
  survival the measured bound (`measured.json bounds`, the whole growth's bill) is shown and approved; with no bound, it is not
  offered. A per-Style bound (`{params, style, bom, roleParts}`) is re-expressed in the town bible's blocks first (`StyleBound`, Noah's "a" of
  2026-11-13): each Style's role counts move to the town's item for the same role (the lamp after the palette check, the wood by the kit's
  rule, a dirt path billed as dirt), and the bound is the per-item maximum over the Styles. An entry without `roleParts` (the dev source) is
  used as it is; with no readable bible, the Styles' bills as measured. The rate comes from `measured.json rates: [{params, perDay, item}]`, when there is one. A module offer is never reworded by
  ProposalWords, and is priced $0 in the inbox (in survival, "at most <bound>").
- **Coalescing and declines** (`ProposalRules`): by module identity (a standing site whose lot ext names the module, or an offer of the same id
  waiting, asked in talk included), never by building type. A standing or waiting module holds the decorative rule back. Declining
  `module.<id>` also declines `farm`; declining `farm` declines nothing more.
- **At Accept** (`Actions`, `ModuleOffers.recheck`): checked again. It must still be eligible, the same catalog version and program, the same
  params, a bound in survival, and no module of the id standing. Else it is said, the offer leaves the inbox (not declined), and the decorative
  building is offered as its own priced proposal. A `module.*` proposal without its order is refused, never built as a design.
- **Talk** (`TalkJob`, `Talk`): the schema's `module.id` takes the offerable ids (left out when there are none). The user prompt lists them with
  their params. An answer's module rides beside the building asked for. Eligible: a module proposal. Not: said, and the building is offered as
  its own priced design.
- **Placement** (`SettlementRunner`): `startModule` is an addition with one lot at its exact frame, which only takes growth paths (anything else
  ends before anything is spent).
  1. The grow lot carries the kind's lot (type, size, and `irrigate` and FC's `field` where the kind has them) and the `steward_mc:module` ext.
     NO_ROOM on both preferences tries the next smaller size as a new plan (keys `vgrow.s<W>x<D>`, `vgrow2...`), said, still held to the bound
     approved.
  2. The build skips the design pipeline (state READY_TO_PLACE at $0).
  3. **The binding** is saved before the keyed variant is asked (`steward.<build>.module.<lot>`, `.<n>` when a re-plan moved the lot:
     `ModulePlacement.holds` compares module, program, params and `geometrySha`). After a restart the key finds the variant (`byKey` first).
  4. **The quote** (from materials, `Regions.quote` with the final entries) is held to the order's bound. It holds and asks (`HoldBill`, phase
     AWAITING_PLACEMENT_APPROVAL, nothing consumed) when:
     - the bill is past the bound;
     - the steward accepted it on its own and the bill takes over half the stockpile's count of an item (Q4);
     - Architect can't quote it exactly: only notes known to be harmless pass, and the fluids' upper bound is conservative.

     A quote that leaves a part unbilled is refused. The held bill is saved with the build (`ModuleOrder.held`) and is the decision's own text in
     the inbox. Approving makes exactly that bill the bound, saved before the phase moves on.
  5. **The realise** (`ModulePlace`, never asked again) carries the module entries; a module lot without an entry refuses it.

  A stale realise (`parent_moved`) re-plans under `vgrow3`; the lot is bound again when its geometry moved.
- **Undo and cancel** are growth A/A2's. A cancel while a variant or quote is out ends at once; their late answers change nothing. The offer is
  given back only when nothing of it stands (never realised, or taken up whole), also by a build restored after a restart (the build's
  settlement copy holds the proposal as offered).
- **A module site isn't redesigned**: change requests (talk, start) and updates to a redesigned version are refused.
- **Saves:** settlements format 7 (a proposal's `module` order, read exactly: integers, bills, a `module.*` key with its order) and builds
  format 6 (`moduleOrders` lotId -> order, with its approved bound, held bill and proposal key). Older Stewards refuse both files.
- **Dev:** `/steward dev modules {dir, id, measuring, bounds}` (development environment only) offers a catalog directory's module (the e2e's
  fixture, measured on Architect 0.62.0, under a measuring gate). `/steward dev site_module <site>` reads a site's module identity.
- **Asks:** S-M5-8 (irrigated growth of an adopted town), S-M5-9 (a survival realise held to an approved bill: Steward checks the quote but
  can't cap what Architect then consumes).
- **Not covered by e2e** (unit-tested, or the decision functions only): a restart between the binding and the realise, a stale re-plan that
  moves the lot, the smaller-size fallback, Q4, an inexact quote, decline both ways, an adopted (B) town.

## 9. Noah's answers (2026-11-12)

Noah, in the Steward manager session: "Go with option 1, defaults/recommendations for the rest". Option 1 is phase 5b (section 3.3). The six
answers are the recommended defaults:
1. **Harvesting in v1:** the player harvests a watered, lit field. Automatic harvest comes with 5b's sugar cane and melon farms, and with
   villager farmers in phase 6.
2. **The spine:** not in phase 5's first catalog. It comes in **phase 5b** (slice M8), after the first automatic farm.
3. **Undoing a storage hall that holds items:** refused until it is emptied. The steward says so and names the containers. S-M5-6 isn't
   needed.
4. **Autonomous and Full** may build module offers on their own, like any proposal. In survival, Autonomous asks first when the whole bill
   takes more than half of the stockpile's count of any item.
5. **Upgrades:** no in-place upgrades. A second field is a second module.
6. **Vanilla blocks only**, as for every template.

**M0's results (Noah via parent session, 2026-11-12: "Merge M0, defaults").** The probe's findings (docs/PROBE-M0.md) change these bars; M2 and
M4 build to them, and they override sections 3.1 and 3.2 where those say otherwise:
1. **Hydration (3.1, measurement 1):** the farm writes its farmland as `moisture=7`, and the bar is "every cell still 7 after 24,000 ticks". "Every
   cell moist within 240 s" is dropped: each cell hydrates on its own random tick (about 3% still dry at 4,800 ticks).
2. **Light (3.1, measurement 2):** the bar is mob safety (block light for spawning), not growth. Open-sky crops grow at night by vanilla's rule; the
   light-9 growth rule matters only for covered fields. The lamp role's "light 14 or more" rule stands.
3. **The filler (3.2):** a filter hopper accepts its own filler, so the filler is one survival can't make (measured in M4) and is out of the test mix.
   If M4 finds none, the offer names the risk and Remove/repair restores the filter.
4. **Layout rules, recorded:** IRR's water sits level with the farmland or one above, never below; no hopper sits under a filter hopper; slot chests
   written `type=single` stay single. Section 8's Steward dev hooks move to M2 and M4.

**The three test styles (Noah via parent session, 2026-11-12: "merge m1, defaults").** Section 2.2's re-skin uses **rustic, fortress and desert**
(wood, stone and sand). The strange-ask cards carry no palette, so "the three families used most" couldn't be read from them.

**Open for later (phase 5b; not blocking phase 5):**
- **Water for sugar cane.** Each cane base needs water beside it. Is that IRR's `irrigate` with a cane layout, or a new Architect member
  (contained channels along the rows, built on H)? To be decided with M7's design, as an ask if needed.
- **Pistons and observers in "still ours".** A piston harvester's moving parts change state. GM's gate mechanisms show the pattern (a settled
  rest state, the piston's records). M7's design says whether S-M5-4's field cells cover cane and melon cells (the grown block is produce,
  the base or stem is ours), or whether it needs an ask of its own.
- **Animals in the pen.** Templates never place entities, and moving animals needs the player's approval (PLAN "Villagers and animals"). Does
  the pen wait for the player to lead animals in, or does the steward bring them? M9 decides, perhaps with phase 6.
- **The smelter's fuel and input.** These are cargo the player or the spine brings (section 6). Undo is refused while it holds items, as for
  the hall.
- **Spine routing in a town.** A hopper chain is at most 16 cells long, so connected modules must stand near each other, or the spine runs
  underground or as water on ice (S-M5-5's probe). M8 decides with Architect.

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
