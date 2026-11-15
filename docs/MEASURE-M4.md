# M4: `storage_hall@1`, designed and measured

Slice M4 of phase 5 (`docs/MODULES.md` §3.2 as amended by §9, §2.2's envelope, §6, §8's M4 row; `docs/PROBE-M0.md` ST1-ST6; Architect's FK,
CONTRACT "FK contract" and "Slice FK as built"). The module is `modules/storage_hall/1/`. This file fixes the design and the measurement's
bars **before the first candidate is measured**: git history shows it committed before any `artifacts/m4/<step>.json` that judges a bar.
Results never move a bar. A bar found wrongly stated is recorded as a FAIL and a design change, never quietly relaxed.

**Order (the brief).** The plain hall (`slots = 0`) is built, measured and certified first, so the slice ships either way. The sorting
branches (`slots` 4-16) wait for Noah's choice of the filler item (open: option 1 `minecraft:petrified_oak_slab`, a module-provided item that
needs an Architect ask first; option 2 a billed plain item survival can make). Until then the envelope's `slots` branch is `[0]` only, and
the program refuses any other value.

**The filler is decided** (Noah, PLAN Decisions 2026-11-14, relayed by the Steward manager): `minecraft:petrified_oak_slab`, **module-provided**
(option 1). It exists in 26.3 (block, item and loot table in the jar; no recipe). Architect's support is slice FK-b (strict, after J1, likely
v0.70.0): the fill writes the slabs unbilled (not on the quote, the bill, `outstanding` or `nextNeeded`), removal and undo take them back with
no refund, conservation counts them apart, an allow-list holds only this item, and a fill container is stripped of them before it drops (a
repair re-filling them is asked). `storage_hall@2` (sorting) certifies only on an Architect with FK-b; the plain hall certifies on 0.67.0 now.

**Versions (amended after the design review, finding 11).** A measured version is frozen (MODULES 2.1). The plain hall is `storage_hall@1`
(`slots` `[0]`) and stays as certified; the sorting hall is **`storage_hall@2`**, a new program and envelope (its own plain branches
included, measured again with it). Only the newest version is offered, so @2 replaces @1 once certified. A plain hall does not meet M5's
gate.

## 1. The module

### 1.1 Params (kit types: building programs take only int, bool and enum)

| Param | Type | Values | Measured branches now |
|---|---|---|---|
| `slots` | int 0..16 | 0, 4, 8, 12, 16 | `[0]` |
| `bulk` | int 0..8 | double chests of bulk storage | `[0..8]`, all nine |
| `item1` .. `item16` (the sorting branches only) | enum | `none` and the filter domain (1.4) | not yet in the program |

`items` (MODULES §3.2) becomes 16 enum params, because a given program's params are int, bool or enum only (kit `lib/params.mjs`
`validateParams`). The enum's options *are* the filter domain, so the kit itself refuses an item outside it. Slot `n` is `item<n>`; the
first `slots` must name distinct items, the rest `none`.

### 1.2 Geometry (template = the lot's frame: front south, x across, z from the back, `groundY 1`)

Row 0 is a foundation layer under the whole lot (frame y -1), row 1 the floor (ground level), rows 2-5 the walls (4 high), row 6 the roof
as the roof role's **bottom slab** over the whole hall (no mob spawns on a bottom slab, and none inside: it is lit). Walls on the `wall`
role, corner posts on `frame`, the floor on `floor`, the foundation on `foundation`, the front row on `path`, a door from the bible's wood
at the front wall's middle `gate = floor(W / 2)`, lamps from `light` set into the walls at row 4 (they light the inside and the front row).
*Amended before measuring:* no glass panes (one less role for the bound to re-express). The program records each cell's role as it writes
it (`bp.roleCells`), so the survival bound's role parts are counted by provenance, never by a block two roles share (review finding 7).

**The plain hall (`slots = 0`):** `W = 11`, `D = 7`, `H = 7`.
- z 0 back wall; z 1 the bulk row; z 2-4 the aisle; z 5 the front wall with the door; z 6 the front row (path).
- **Bulk:** `bulk` double chests along the back wall, facing south, in four columns of two cells (x 1-2, 3-4, 5-6, 7-8), the lower row
  (row 2) filled first, then the upper row (row 3, on the lower chest: a chest opens under a non-conductor). Each half is written with its
  `type` (left/right) so it is a double chest as placed.
- **The input chest** (`item_in` port): a single chest just inside the door (x = gate + 1, z 4, row 2), `type=single`, with no chest beside
  it, so it never merges. With no line it is a plain chest the player fills.
- **Lamps:** in the side walls at (0, 4, 2) and (W-1, 4, 2) and in the front wall at (gate ± 2, 4, 5), each standing on the wall cell below.
- No redstone, no hopper, nothing that can jam.

**The sorting hall (`slots = s` of 4-16; designed now, built after the filler):** `W = max(s + 3, 11)`, `D = 13`, `H = 7`.
- z 0 back wall; z 1 an air gap (so no role block touches the circuit); z 2-5 the circuit; z 6 the line; z 7 the slot chests; z 8-9 the
  aisle; z 10 the bulk row (facing north, both sides of the door); z 11 the front wall; z 12 the front row.
- **The tile is M0's measured tile, exactly** (PROBE-M0 "ST", `tools/module-probe.mjs buildHall`), with its line along +x and its slot
  chest to the south. Slot `u` (0-based) is column x = 1 + u. With b = row 2 (the D hopper's row) and L = z 6:
  - line hopper (x, b+2, L) facing east; the last points into the **overflow chest** at (s + 1, b+2, L);
  - filter hopper F (x, b+1, L) facing north (into the comparator), so it never pushes;
  - output hopper D (x, b, L) facing south into the **slot chest** (x, b, L+1), `type=single`;
  - comparator (x, b+1, L-1) facing south, on stone (x, b, L-1); dust (x, b+1, L-2), (x, b+1, L-3) on stone; dust (x, b, L-4) on stone
    (x, b-1, L-4); repeater (x, b-1, L-3) facing north, delay 1, on stone (x, b-2, L-3); stone (x, b-1, L-2); a redstone wall torch at
    (x, b-1, L-1) on (x, b-1, L-2), facing south, which strongly powers (x, b, L-1) and so locks D.
  - The circuit's solid blocks are fixed `minecraft:stone` (core, never a role), as measured. No hopper sits under F (M0 HL4).
- **The input chest** sits on the first line hopper, (1, b+3, L), `type=single`: the line's head, reached from the aisle.
- **The lock** (FK, Steward's review A): a lever at (1, b+2, L-1), `face=wall, facing=east`, on the west wall's inside face (0, b+2, L-1).
  That cell is free in the tile (row b+2 of the comparator's column holds nothing) and it is the first line hopper's north neighbour, so a
  powered lever locks that hopper (a hopper reads any neighbour's signal; a lever signals every side) and nothing leaves the input chest.
  The lever strongly powers only its wall cell, whose neighbours are wall cells and the lever. FK writes it `powered=true` while built,
  fills, then writes it `powered=false` with neighbour updates. It stands behind the line, out of the player's reach.
- **The partition:** the cells of z 6 not used by the tile (x = s + 1 rows b, b+1, b+3; x ≥ s + 2, rows b..b+3) and every circuit-zone cell
  at x > s are the wall role, so the player can't reach the circuit. They don't touch dust or a mechanism of the circuit except sideways
  (dust here runs north-south; a block beside it is not pointed into).
- **Bulk** at z 10, facing north, pairs left and right of the door, lower row then upper row (8 double chests at W = 11).
- **Signs and item frames.** MODULES §3.2 asks for a sign and an item frame per slot. An item frame is an entity and templates place none;
  the kit writes no sign text. Neither is built in v1 (the offer and the inbox list each slot's item instead). Recorded as a deviation.
- **The fill** (`bp.fillContainers`): each F holds **41 of its filter item in slot 0 and one filler in each of slots 1-4** (M0's measured
  fill, CS: signal 2 at rest, 3 at the 42nd item). Locks: the lever above, `{powered: 'true'}`.

### 1.3 Palette rules (MODULES 2.2, the program and `module.json` `palette`)

- **Lamp:** the `light` role at block light 14 or more, else the plain lantern, said on the offer.
- **No gravity** on `floor` (chests stand on it), `foundation`, `wall` and `frame`. *Amended:* not on `roof`: the kit refuses a roof
  block with no slab variant, and no gravity block has one, so the rule could never fire (the offline probe showed it).
- **Non-conductive:** none: no role block is in the circuit's plane (the air gap at z 1, the partition only sideways).

### 1.4 The filter domain (sorting branches)

- **Allowed:** a fixed list in the program, `FILTERABLE`: plain items that stack to 64 and that a survival settlement stores in bulk
  (building blocks, ores and ingots, crops and food items, mob drops, wood). Each is distinct; no duplicates across slots.
- **Never a filter:** the filler; anything that stacks to 1 or 16; any item with components (renamed, enchanted, potions, written books).
  These always go to overflow. The offer names these classes.
- **Duplicates** are refused by the program (`palette:`-style throw `storage_hall@1: item3 repeats item1`), and by Steward before asking.

## 2. Offer and place (M3's path)

- **`ModuleKind.StorageHall`**: rule `module.storage_hall`, role "storage hall", lot type `storage_hall`, no irrigation, any biome,
  decorative rule retired by a decline: none (there is no decorative storage rule today).
- **The sign** (MODULES 4.1): the stockpile and the player's inventory together hold 12 or more kinds of items. "Full twice in 20 minutes"
  and "chests in the claim" are not read in v1 (recorded deviation: they need tracking and a container scan).
- **Params at the offer.** `slots = 0` until the sorting branches are certified; then the tier gate (Nether tier: hoppers need iron,
  comparators quartz) decides between 0 and the largest of 4-16 the settlement's distinct domain items fill. `bulk`: the stacks the
  stockpile and the player hold, at 54 a double chest, rounded up, at least 2 and at most 8. In talk, the params named are kept when
  measured. The player can change the items before accepting (sorting branches; the inbox's module order editor, a later step of M4).
- **The words:** "Your stores are getting mixed. I can raise a storage hall by the town (11 by 7): N double chests and an input chest by
  the door." Sorting adds: the slot items, "about N items a second" (the measured rate), and "it won't sort tools, armour, potions,
  renamed or enchanted items, or anything that stacks to 16: those go to overflow". Survival adds the bound, as the farm's.
- **Placement** is the farm's: growth A, A2 or B, the binding, the keyed variant, the quote held to the bound, the realise.
- **Undo:** a hall holding items refuses Remove, and the steward names the containers and the count (Q3: "the storage hall still holds 1,240
  items in 9 chests; empty it first"). The words come from Architect's removal blockers (the cargo cells), counted by Steward.

## 3. Open points for the design review

- **Q1. The lock.** Is one lever on the first line hopper enough? Before the fill each F is empty, so its comparator reads 0, the torch is
  lit and every D is locked; the only path for an item is the line, stopped at its head. After the fill the lever is released.
- **Q2.** Is a 1-cell air gap at z 1 enough isolation, and is the wall-role partition beside the last slot's circuit safe?
- **Q3.** The bulk chests' upper row on the lower: any vanilla rule that blocks opening (`ChestBlock.isChestBlockedAt`: a block above
  that is a redstone conductor; a cat sitting on it).
- **Q4.** Is the plain hall's measurement (below) the right bar for a hall with no mechanism?

## 4. The bars

Evidence: `node tools/module-measure.mjs <step> --module storage_hall` (its own client, ports **8598/8599**), `artifacts/m4/<step>.json`.
Conventions as M2's (frozen time where it matters, `random_tick_speed 3`, mob spawning off except where a step summons mobs).

### 4.1 The plain hall (`slots = 0`), now

1. **Shape (look).** Every hall placed in the measurement: every template cell stands as written (Architect's standing 100%); every bulk
   pair reads as one double chest (both halves' `type` paired, one 54-slot container); the input chest reads `type=single`; the door, the
   lamps and the roof are as written. All four facings and a chunk-border crossing in both x and z are covered (4.3).
2. **Storage conserves.** Into the input chest and every bulk chest of every hall: a fixed mix (64-stacks of 8 kinds, 3 unstackables, a
   16-stack, a component stack) is inserted by the dev hook and read back. Bar: every container holds exactly what was put in, slot by slot.
3. **Restarts and unloads.** With the containers holding the mix: a restart (the client quits and starts), then the hall's chunks unloaded
   (the player teleported 6,000 blocks away; *amended:* the unload is asserted, every hall's input-chest chunk reading as unloaded, review
   finding 9; then 600 ticks) and loaded again. Bar: every container exactly as before, no item entity in the hall's box, standing still 100%.
4. **Light.** At midnight, every air cell at row 2 inside the hall and on its front row has block light 1 or more (mob safety, M2's bar 2).
5. **Undo with cargo** (MODULES 6, Q3). A hall holding items: `undoRevision` is refused, with the containers named; nothing in the world
   changes (the containers' contents, the standing score). The steward's words name the count and the containers. *Amended (review
   finding 4):* then cargo **in isolation**, one container class at a time: a single item alone in the input chest, in either half of a
   lower bulk pair, and in either half of an upper pair. Each alone refuses the undo and is still there after.
6. **Undo emptied.** The containers emptied by the dev hook: the undo removes the hall; no item entity appears.
7. **Survival.** The plain hall placed from a stockpile (growth A2, every `bulk` branch at least once over the run, rustic):
   - the quote held to the measured bound, and **consumed == the quote**, item by item (the chests, planks, the door, the lamps, the roles);
   - a cancel mid-build refunds exactly what was consumed, never more;
   - *amended (review finding 3):* a construction block the player mined first (one bulk chest half, broken by a fake player through PT):
     it drops as the player's, and the undo refunds consumed less that chest, never the chest again;
   - an undo of the built, emptied hall refunds exactly what was consumed;
   - after a restart following the undos, nothing is given twice (the ledger, the stock, nothing on the ground);
   - the bound recorded per params and measured Style (`bounds`, as M2's, with `roleParts`).
8. **The checker** over every branch x Style (rustic, fortress, desert): `tools/module-catalog.mjs offline` and `ingame` clean.

### 4.2 The sorting hall (after the filler; fixed now, unchanged by its results)

MODULES 3.2's measurement 1-6, on the real module, every slot branch x 4 facings, rustic in full, fortress and desert for 1 and 4:
1. **Sorting 100%:** M0's mix less the filler (each slot's item x 3 stacks, 5 plain kinds it doesn't filter, an unstackable, a 16-stack, a
   component stack); every filter item in its slot, the rest in overflow, the input and the line empty, items in = out, no item entity.
   20 runs per branch.
2. **Throughput:** items a second end to end, within 10% of M0's HR rate (2.490/s): bar 2.241-2.739.
3. **Saturation:** two neighbouring slots' chests filled to the top, then a mix: no wrong item in any chest, nothing lost, the other slots
   100%, each saturated F reads 3 or less; emptied, all 100% again, every F back to exactly 41 + 4.
4. **The filters hold:** after 10,000 items, each F holds exactly 41 of its item and its 4 fillers.
5. **Restarts and unloads mid-sort:** nothing lost or duplicated, sorting continues.
6. **Survival and the crash cases** (FK's kill points `dev.fk.halt` FK1-FK4, FKC1, FKC2): the whole growth's bill (the fill included) ==
   consumed; a cancel before the fill writes and refunds no filter items; a crash between the fill and the unlock never fills again; a
   filter hopper holding one extra of its own item refuses undo as cargo; crashes during capture, removal and refund crediting refund once.
   Bar each: items in = items out against the saved state.

### 4.3 The envelope (§2.2)

- **Branches:** `slots` 0 x `bulk` 0-8 now (9). Later 4, 8, 12, 16 x the bulk values the sorting W holds.
- **Facings:** all four; a V town laid east-west fronts its lots north and south, north-south fronts them east and west (as M2).
- **Chunk borders:** *amended before measuring:* at least one hall crosses a chunk border in x and one in z. The plain hall is one fixed
  frame for every bulk branch, so a crossing tests the containers' chunk handling, not a branch's geometry. (The sorting version places
  crossings through its circuit, review finding 9.)
- **Styles:** rustic in full; fortress and desert for 1 (shape) and 4 (light).

## 5. Design review (GPT-6.1 Sol, at 5151078, 2026-11-14)

"Plain-first is sound as an interim release." 5 blockers, 7 should-fixes, 1 nit, checked against the code and the 26.3 jar's rules it cites.
**For the plain hall (taken now):** finding 3 (a mined construction block's refund: bar 7), 4 (cargo in isolation per container class:
bar 5), 7 (the bound's role parts by provenance: `bp.roleCells`, `StyleBound`'s hall roles pinned to the kit by a unit test), 9 (the unload
asserted: bar 3; crossings: 4.3), 11 (versions: @1 frozen, sorting is @2). Findings 3's restart-and-retry after refunds: bar 7's restart.
**For `storage_hall@2` (accepted as its requirements, built after the filler):**
- 1: nothing conductive over the overflow chest (the partition leaves the cell above it clear); every chest opened and emptied by a fake player.
- 2: FK's recorded leftovers (a stalled removal's custody written back and wiped on retry; a single-site Remove credited without a save) are
  exercised on the hall's own `undoRevision` path with a removal failure after capture, a restart and a retry, and fixed or excluded in
  Architect (an ask) before @2 certifies.
- 3: refunds after the player took filter items (part and all of an F), a missing container, and a mined block, each item counted apart
  (inventory, stock, custody, pending refunds, drops), with a restart and retry.
- 4: cargo alone in each container class (input, overflow, slot chest, bulk halves, line hopper, F, D), cargo arriving after the preflight,
  and the extra-in-F case with the line stopped, so F itself is the blocker.
- 5: the item params: 16 enums of short keys (the kit refuses `minecraft:` in an option) mapped to item ids, with dependent validation
  (distinct, `none` only after the slots) in `ModuleGate`; the filter items certified as an item class apart from the layout branches; the
  bound looked up for the accepted items.
- 6: every role touching a mechanism is a fixed measured core block or held to a support and redstone rule (a redstone block floor under D
  would lock it); adverse-palette checks; a preloaded input stays put through construction and starvation; the exact F slots before the
  release; `fill_blocked` recovery charging nothing over cargo.
- 8: the larger mixes are fed in acknowledged batches against an independent expected multiset; throughput timed around that; a full
  overflow backs up and recovers.
- 10: the filler's paths into ordinary inventories (refunds, a broken filter) named, the input restriction on the offer, and the
  recovery measured; with a survival-obtainable filler, §9's disclosed risk and Remove/repair.
- 12: placed slot labels the player can read in the hall (from the lot's params, the input and the overflow too), and the offer's real
  dimensions.
- 13: "no hopper under F" means no uncontrolled extraction below F (D, the controlled one, is there); the bars named, not numbered.

## 6. Results (2026-11-14, Minecraft 26.3, Architect 0.67.0, kit/checker 0.62.0)

`storage_hall@1` is certified (`measured.json`, `result: pass`, program `9e6f1f15…`). Evidence in `artifacts/m4/` (local).
- **hall** 218/218: 17 halls in 4 V towns (every bulk 0-8 in rustic, bulk 0 and 8 in fortress and desert, extras for borders), all four
  facings, 11 halls crossing a chunk border in x and more in z; shape (standing 1, double chests paired, the input single); storage slot by
  slot; light at midnight (darkest 7); a restart; an asserted unload (34/34 chunks, 610 game ticks); every growth's undo refused for cargo,
  and again with one item alone in each container class; the words; every emptied undo removed with no drops.
- **survival** 26/26: three growths (bulk 0-8) billed exactly their quote (1,201, 1,150, 1,237 items); a cancel mid-build refunded exactly
  what it had consumed; a chest the player mined dropped as theirs and was not refunded; every undo refunded consumed exactly; a restart gave
  nothing twice.
- **bounds** 58/58: the quote per bulk and Style, 27 per-Style bounds with role parts by provenance.
- **offline** 151/151 (every branch x Style clean; the dim lamp, gravity and full-block probes), **ingame** 150/150.
- **Run notes:** runs 1-3 and the first final runs failed only on the harness (the helper not yet up, the lots without Steward's ext, a player
  standing in a site, the refusal's text in `refused`, the cancel fed before its targets owed anything); no bar moved.

## 7. Diff review (GPT-6.1 Sol, of the slice's diff from 1f90b31 before the fixes, then a fix check)

2 blockers, 5 should-fixes, 1 nit; the fix check found no new blocker and two should-fixes, both taken.
- **Taken:** the cargo words on Architect's thrown refusal (then, from e2e: Architect's removal *waits* on cargo, so a growth undo whose hall
  holds items is refused by Steward at once, `HallCargo.before`); `fullBlock` for wall and foundation (the lamps, the door); wall-form roof
  slabs; the words scoped to the halls of the refused region whose containers the refusal names, a double chest once, "at least" when
  unloaded, coordinates parsed safely; the unload over every chunk and 600 game ticks.
- **Left over (recorded):** finding 5, a cold client restart with a replay of the original operations (the restart here is a world leave and
  reopen in one client, as M2's); finding 7, a live bound-against-quote check for every branch in an unmeasured Style (each branch's quote
  is measured equal to consumption, and the re-expression is unit-tested against the kit; the e2e checks one offered branch in another
  Style); "chests" also counts a barrel or furnace a player adds.
