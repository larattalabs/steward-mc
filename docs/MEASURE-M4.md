# M4: `storage_hall@1`, designed and measured

Slice M4 of phase 5 (`docs/MODULES.md` §3.2 as amended by §9, §2.2's envelope, §6, §8's M4 row; `docs/PROBE-M0.md` ST1-ST6; Architect's FK,
CONTRACT "FK contract" and "Slice FK as built"). The module is `modules/storage_hall/1/`. This file fixes the design and the measurement's
bars **before the first candidate is measured**: git history shows it committed before any `artifacts/m4/<step>.json` that judges a bar.
Results never move a bar. A bar found wrongly stated is recorded as a FAIL and a design change, never quietly relaxed.

**Order (the brief).** The plain hall (`slots = 0`) is built, measured and certified first, so the slice ships either way. The sorting
branches (`slots` 4-16) waited for Noah's choice of the filler item (decided 2026-11-14, below: option 1); they are storage_hall@2 (M4b).
In @1 the envelope's `slots` branch is `[0]` only, and the program refuses any other value.

**Current (2026-11-16):** storage_hall@1 is certified on Architect 0.73.0 by a smoke carry (§9b), the version main pins.

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

## 8. Gate-verifier (strict, 2026-11-14, at 265de7c)

PASS on all four items: the certificate backed by its evidence on these files and versions; its own `survival` re-run 26/26 (bills equal
the quotes, the cancel partial at 6 of 362 items and refunded exactly, the mined chest not refunded, a restart giving nothing twice); the
billing and undo diff (the hall pre-check can only refuse; the dev hooks are development-only; no save format changed); e2e PASS 18.
Its notes, recorded as leftovers: survival checks consumed against the quote, the bound against the quote is the bounds step's; the cancel
is only just partial; the mined chest's drop is the player's by the PT break path, not checked by owner; a crash during refund crediting is
the sorting hall's bar (4.2).

## 9. Smoke carry to Architect 0.70.0 (2026-11-14, speed rule 4)

`storage_hall@1` is carried from its full measurement on 0.67.0 to 0.70.0 (kit/checker 0.65.0), `basis: smoke`, `from: 0.67.0`, on branch
`storage-hall-069` (crop-farm-069 + pin-069 + pin-070). Kit 0.62.0 -> 0.65.0 leaves the hall's paths alone (kit.mjs, blocks, check, params,
survival vocab unchanged; QL only adds `Quote.terrain`). Fresh: offline 151/151, ingame 150/150, survival 26/26, bounds 58/58 (the 27
re-quoted bounds equal the carried ones), the hall round 218/218; e2e `module-hall` PASS 18. `record --smoke` works per module now: the
hall's evidence is artifacts/m4 and its round is the deterministic hall step (the catalog's smoke check takes it).

## 9o. Architect 0.91.0 (2026-11-22, T4R-PIN): storage_hall@1 carried, @2 not

The kit/checker pins moved to 0.79.0; the published jar's sha256 is `5eea4921…`.
- **@1 carried:** hall 218/218, bounds 58/58, survival 26/26.
- **@2 not carried:** hall 218/218 and bounds 382/382, but sort-survival 15/19, the same 4 rows on 2 runs:
  - B2: the player's 64 cobblestone inserted mid-build is gone from the hall at the preload (input, line, F and D all empty), so the fill
    runs UNLOCKED instead of waiting `fill_blocked`;
  - 8: filled once; taken;
  - 8 refund: credited is 33 stone short. The 33 stone left in F 0 is not refunded as the structure's own.
  T4R-D's cargo extraction and owed paths changed what @2's billing check measures (rule 11b). `record --smoke` refuses it, and its pins stay
  kit 0.78.0, so its 0.89.0 certificate stays self-consistent and the game offers it on 0.89.0 only. A re-measure or an Architect fix is
  Noah's call. The evidence is `artifacts/m4b/sort-survival.json` in `_work/steward-t4r-pin-hall`.

## 9n. Smoke carry to Architect 0.89.0 (2026-11-20, rc-089)

Both halls carried to 0.89.0 (kit/checker pins moved to 0.78.0, programs unchanged, moduleShas with the old pins put back unchanged), on the
published jar (sha256 `8a1b9f8f…`). 0.89.0 adds RTE3/RTE4 (district street ownership; a boundary growth's street records its district) and the
kit 0.78.0 strings; nothing a hall's build or bill uses moves. Fresh: @1 hall 218/218, bounds 58/58, survival 26/26; @2 hall 218/218, bounds
382/382, sort-survival 19/19; offline 295/295, ingame 292/292; ports 9128/9129, $0. The hall steps ran first: catalog `ingame` doesn't wait
for a fresh run dir's helper install (npm ci), so it read givenPrograms false twice on a new worktree; the hall's own 5-minute wait covers it. Fixed (CATALOG-WAIT, 2026-11-20):
catalog `ingame` and the hall steps share `tools/lib/helper-wait.mjs`, which waits up to 5 minutes for the helper's link to sync and
givenPrograms to arrive; a helper that is up (link synced) but gives no programs still fails, with its own message (`test/helper-wait.test.mjs`).

## 9m. Smoke carry to Architect 0.87.0 (2026-11-20, rc-087)

Both halls carried to 0.87.0 (kit/checker pins moved to 0.76.0, programs unchanged, moduleShas with the old pins put back unchanged), on the
published jar (sha256 `b89c6672…`). 0.87.0 adds RTE2 (district land ownership per district) and LYW3 (mega_bench's ring wall) and the kit
0.76.0 strings; nothing a hall's build or bill uses moves. Fresh: @1 hall 218/218, bounds 58/58, survival 26/26; @2 hall 218/218, bounds
382/382, sort-survival 19/19; offline 295/295, ingame 292/292; ports 9128/9129, $0.

## 9l. Smoke carry to Architect 0.86.0 (2026-11-19, mod-086)

Both halls carried to 0.86.0 (kit/checker pins moved to 0.75.0, programs unchanged, moduleShas with the old pins put back unchanged), on the
published jar (sha256 `1d73a68a…`). 0.86.0 adds RTE (a district route's pieces name their graph edge; districts land and `Regions.layout`
read them) and the kit 0.75.0 strings; nothing a hall's build or bill uses moves. Fresh: @1 hall 218/218, bounds 58/58, survival 26/26;
@2 hall 218/218, bounds 382/382, sort-survival 19/19; offline 295/295, ingame 292/292; ports 9128/9129, $0.

## 9k. Smoke carry to Architect 0.85.0 (2026-11-19, rc-084)

Both halls carried to 0.85.0 (kit/checker 0.74.0 unchanged, moduleShas unchanged), on the published jar (sha256 `d6573eca…`). 0.85.0 adds
SOFF (stockpile, crate and job operations held only under /save-off; unchanged with saving on); nothing a hall's build or bill uses moves
with saving on. Fresh: @1 hall 218/218, bounds 58/58, survival 26/26; @2 hall 218/218, bounds 382/382, sort-survival 19/19; offline
295/295, ingame 292/292; ports 9128/9129, $0.

## 9j. Smoke carry to Architect 0.84.0 (2026-11-19, rc-084)

Both halls carried to 0.84.0 (kit/checker pins moved to 0.74.0, programs unchanged, moduleShas with the old pins put back unchanged), on the
published jar (sha256 `e343ec08…`). 0.84.0 adds LYW2's ring walls (an IR member the evaluator ignores, `Regions.layout` read-side), PR3's
key labels and the kit 0.74.0 strings; nothing a hall's build or bill uses moves. Fresh: @1 hall 218/218, bounds 58/58, survival 26/26;
@2 hall 218/218, bounds 382/382, sort-survival 19/19; offline 295/295, ingame 292/292; ports 9128/9129, $0.

## 9i. Smoke carry to Architect 0.83.0 (2026-11-19, pin-083)

Both halls carried to 0.83.0 (kit/checker 0.73.0 unchanged, moduleShas unchanged), on the published jar (sha256 `9d2ff95b…`). 0.83.0 adds
RVC (a revision's typed stop cause), LYW (walls in `Regions.layout`, read-side) and PR2's client fixes; nothing a hall's build or bill uses
moves. Fresh: @1 hall 218/218, bounds 58/58, survival 26/26 (strict: nothing given twice); @2 hall 218/218, bounds 382/382, sort-survival 19/19
(B2 took the S-M5-17 known-gap path and the fill waited fill_blocked, as on 0.82.0); offline 295/295, ingame 292/292; ports 9116/9117, $0.

## 9h. Smoke carry to Architect 0.82.0 (2026-11-18, pin-081)

Both halls carried to 0.82.0 (kit/checker 0.73.0 unchanged, moduleShas unchanged), on the published jar (sha256 `c5cf0291…`). 0.82.0 is
Architect's player-ready pass (lab-ui 0.6.0 screens, the client's Preview on ghost, ghost-palette tints); nothing a hall's build or bill uses
moves. Fresh: @1 hall 218/218, bounds 58/58, survival 26/26 (strict: nothing given twice); @2 hall 218/218, bounds 382/382, sort-survival 19/19
(B2 took the S-M5-17 known-gap path and the fill waited fill_blocked, as on 0.81.0); offline 295/295, ingame 292/292; ports 9116/9117, $0.

## 9g. Smoke carry to Architect 0.81.0 (2026-11-18, pin-081)

Both halls carried to 0.81.0 (kit/checker 0.73.0, moduleShas with the old pins put back unchanged), on the published jar (sha256
`74f2ab44…`); 0.80.0 skipped. Kit 0.72.0 -> 0.73.0 is the opt-in 8c water model only; neither hall has a fluid or terrain op. 0.80.0's DFG
changes which crates adoption defers, 8b adds status views and RemovalStopCause; no hall bill moves, as the re-quote shows. Fresh: @1 hall
218/218, bounds 58/58, survival 26/26 (strict: nothing given twice); @2 hall 218/218, bounds 382/382, sort-survival 19/19 (B2 took the
S-M5-17 known-gap path and the fill waited fill_blocked, as on 0.79.0); offline 295/295, ingame 292/292; ports 9116/9117, $0.

## 9f. Smoke carry to Architect 0.79.0 (2026-11-18, pin-079)

Both halls carried to 0.79.0 (kit/checker 0.72.0, moduleShas with the old pins put back unchanged), on the published jar (sha256
`98e18a42…`). Kit 0.71.0 -> 0.72.0 adds staged terrain (CLASS_STAGED, billing nothing) and the compiled op map for a fluid op with decor;
neither hall has a terrain or fluid op, so no hall bill moves, as the re-quote shows. Fresh: @1 hall 218/218, bounds 58/58, survival 26/26;
@2 hall 218/218, bounds 382/382, sort-survival 19/19 (B2 took the S-M5-17 known-gap path and the fill waited fill_blocked, as on 0.78.0);
offline 295/295, ingame 292/292; ports 9116/9117, $0. The DR2-DOUBLE exception is gone (Architect 0.79.0 leaves no empty pendingCredit
record): @1's "after a restart: nothing given twice" passed comparing every ledger member and stock item exactly.

## 9e. Smoke carry to Architect 0.78.0 (2026-11-17, pin-076)

Both halls carried to 0.78.0 (kit/checker 0.71.0, moduleShas unchanged), on the published jar (sha256 `11580aea…`). Fresh: @1 hall 218/218,
bounds 58/58, survival 26/26 on its rerun; @2 hall 218/218, bounds 382/382, sort-survival 19/19; offline 295/295, ingame 292/292; ports
9116/9117, $0. The first @1 survival failed "after a restart: nothing given twice" (25/26): 0.78.0's start drops a leaked empty pendingCredit
record (`site:s10@...: {items: {}}`, the cancelled lot), logging "credited now" though nothing is credited (DR2-DOUBLE). The world files on both
sides of the restart (_work/dr2-double-evidence/rerun) differ only in that record; delivered, consumed, returned, paid, credited and the stock
are equal: nothing minted. The check now allows exactly that (tools/lib/pending-credit.mjs, test/pending-credit.test.mjs); a record with items,
or any other change, still fails. Architect fixes the leak for v0.79.0 (fix/double-credit): drop the exception at that pin.

## 9d. Smoke carry to Architect 0.77.0 (2026-11-17, pin-076 retargeted)

storage_hall@1 carried from 0.67.0 (`basis: smoke`) to 0.77.0, kit/checker 0.71.0, moduleSha `c9503214…`; storage_hall@2 from 0.72.0, moduleSha
`55d66403…`. On the published 0.77.0 jar (sha256 `73e01fe4…`). The kit's hall paths change only version strings (the rest of the kit diff is
PL2's ladder.port, behind its own kind). OVF (API 1.66.0) puts a keyed refund all into the stock instead of overflowing; the refund checks count
returned plus refundOverflow, so they hold. Fresh: @1 hall 218/218, survival 26/26, bounds 58/58; @2 hall 218/218, bounds 382/382,
sort-survival 19/19 (re-quoted bounds equal the carried ones); offline 295/295, ingame 292/292; ports 9116/9117, $0.

## 9c. Smoke carry to Architect 0.74.0 (2026-11-16, pin-074)

Carried from 0.67.0 (`basis: smoke`) to 0.74.0, kit/checker 0.69.0, moduleSha `cf66394f…`. The kit's hall paths change only version strings and
survival billing is unchanged. Fresh: offline 151/151, ingame 150/150, survival 26/26, bounds 58/58 (re-quoted bounds equal to the carried
ones), the hall round 218/218; ports 9112/9113, $0.

## 9b. Smoke carry to Architect 0.73.0 (2026-11-16, pin-073)

Carried from 0.67.0 (`basis: smoke`) to 0.73.0, kit/checker 0.68.0, moduleSha `c467ecf6…`. The kit's hall paths change only version strings; 0.73.0
changes survival billing (BC-c/BC-d per-revision caps; LC reverted, so removal's late cargo is as in 0.72.0). Fresh: offline 151/151, ingame
150/150, survival 26/26, bounds 58/58 (all 27 re-quoted bounds equal to the carried ones), the hall round 218/218; ports 9106/9107, $0.

## 10. The leftovers (slice M4-L, 2026-11-15)

The hall's program and its certificate are unchanged (no re-measure). Each of §7-8's leftovers:
- **"chests" counted a player's barrel: fixed.** `HallCargo` counts as the hall's chests only a plain chest on one of its declared cells
  (`StorageHallLot.layout(...).containers()`, turned into the site's box by the vanilla template rotation, unit-tested against
  `StructureTemplate.transform`). Any other filled container in the lot is said apart ("..., and 12 items in 1 other container that is not the
  hall's") and still refuses the undo at once, alone too ("the storage hall's lot still holds ..."): Architect's removal would wait on it.
  Unknown params or a box not the template's size count every container as before. e2e `module-hall`: the declared cells equal the 5 chests
  standing; a barrel with the chest, and a barrel alone, each refused and said.
- **The cancel was barely partial: now at a real partial point, in the e2e.** `module-hall-survival` accepts the hall, feeds half of what
  its sites owe once (crates stocked before the accept are not booked to it) with no feeder, waits until the draws stop (30 s) while it still owes, undoes through Steward (Architect's RU), and checks at least a
  quarter of the quote drawn, the same back, no overflow, nothing more 20 s on, one removal. The hall is then asked for again in talk (the
  rules' 20 min cooldown; its key is `asked_<time>_storage_hall`) for the full round. Run 3: drew 171 of the quote's 344, 171 back, once. The measurement's bar 7 is left as measured (changing it would need a re-measure).
- **The mined drop's owner: not checked, recorded.** A vanilla block drop carries no owner; the measurement checks the refund leaves out what
  the other player mined and that it dropped in the world. Attributing the drop to the breaker is Architect PT's, an ask if it is wanted.
- **The full-inventory and claim-chest signs: deferred.** "Full twice in 20 minutes" needs per-player tracking state, and "chests in the
  claim" a claim-wide container scan on the server thread; both change when the offer fires (§2's v1 deviation stands).
- **A cold client restart with a replay, and a live bound-against-quote check per branch in an unmeasured Style: out of scope.** Both are
  measurement bars, run only with a re-measure, which this slice doesn't do (the program is unchanged).
- **Ran** (stub, ports 8598/8599): run 1 `--only module-hall`, `module-hall` all pass (declared cells, barrel with the chest, barrel alone,
  undo), the cancel round failed (nothing drawn: fed before the accept); run 2 the cancel passed, the talk re-ask looked for the wrong key;
  run 3 `--only module-hall-survival` PASS 14.

## 11. Smoke carry to Architect 0.72.0 (2026-11-15)

Carried from 0.67.0 (`basis: smoke`) to 0.72.0, kit/checker 0.67.0, on branch `storage-hall-072` (batch-072). 0.72.0 changes the billing
path (QL-b/QL-c: Quotes, FullBom, QuoteLand) and adds FK-b, which @1 doesn't use; the hall's kit paths are unchanged. Fresh: offline 151/151,
ingame 150/150, survival 26/26 (each growth consumed = its quote, the cancel and the refunds exact), bounds 58/58 with **all 27 re-quoted
bounds equal to the carried ones** (so the carry recorded), the hall round 218/218.

## 12. `storage_hall@2`, the sorting hall: design (slice M4b, 2026-11-15; for the GPT design review before building)

A new version (§ "Versions"): its own program and envelope, plain branch included. Once certified it is the newest, so it replaces @1.
Built on Architect 0.72.0 with FK (filled containers) and FK-b (module-provided fill items). Noah's filler: `minecraft:petrified_oak_slab`,
module-provided. Its requirements are §5's list for @2; each is answered below (§5's number in brackets).

### 12.1 Params and the filter domain [5]

- `slots` int, branches **0, 4, 8, 12, 16**; `bulk` int 0..8, branches 0..8 (plain: §1.2's 11 x 7 hall, unchanged from @1).
- `item1` .. `item16`: enums. Their options are `none` plus the filter domain's **short keys**, since the kit refuses `minecraft:` in an
  option. A key `k` means item `minecraft:k`.
  - **The domain** (`FILTERABLE`, in the program and in Java, pinned equal by a test): 48 plain, 64-stacking items a survival settlement
    stores in bulk:
    - stone and earth: cobblestone, cobbled_deepslate, stone, deepslate, dirt, sand, red_sand, gravel, andesite, diorite, granite, tuff,
      calcite, netherrack, blackstone, basalt, end_stone, clay_ball, flint;
    - wood: oak_log, spruce_log, birch_log, jungle_log, acacia_log, dark_oak_log, mangrove_log, cherry_log;
    - ores and gems: coal, charcoal, iron_ingot, gold_ingot, copper_ingot, raw_iron, raw_gold, raw_copper, redstone, lapis_lazuli, quartz,
      diamond, emerald;
    - farm and mobs: wheat, wheat_seeds, beetroot_seeds, sugar_cane, bone, string, gunpowder, rotten_flesh.
  - Never in the domain: the filler, anything that stacks to 1 or 16, anything a player can't rename into the domain (renamed stacks are
    component stacks and go to overflow).
- **Dependent rule** (the program throws `storage_hall@2: ...`; Steward checks it first, `StorageHallLot.items`):
  - `item1`..`item<slots>` are domain keys and all distinct;
  - every later one is `none`.
- **The gate** (`ModuleGate.params`): module.json's envelope gains `domains: {"item1": "filter", ...}`. A domain param takes any of its enum
  options, under the module's dependent rule. It is never a branch, so the branch product stays `slots x bulk`.
- **Certification of the item class:** the measurement runs each sorting branch with its own item set. Across the measurement every one of
  the 48 domain items is a filter at least once and a non-filter in some mix. The branch result then holds for any distinct domain choice:
  every domain item is a plain 64-stack, and the tile's behaviour depends only on that (M0 CS: the comparator reads the fill fraction).

### 12.2 Geometry (sorting branches; §1.2's plan with its fixes) [1, 6, 13]

- `W = max(s + 3, 11)`, `D = 13`, `H = 7`; rows as @1 (0 foundation, 1 floor, 2-5 walls, 6 the roof slab). Line z 6, b = row 2.
- **The tile** is M0's, exactly. Slot u at x = 1 + u: line hopper (x, 4, 6) facing east; F (x, 3, 6) facing north; D (x, 2, 6) facing
  south into the slot chest (x, 2, 7) `single`; comparator (x, 3, 5); dust (x, 3, 4), (x, 3, 3) and (x, 2, 2); repeater (x, 1, 3) facing
  north; the wall torch (x, 1, 5) on (x, 1, 4); stone at (x, 2, 5), (x, 2, 4), (x, 2, 3), (x, 1, 2), (x, 0, 3) and (x, 1, 4). D is the only
  hopper under F, and it is the controlled one: there is no uncontrolled extraction below F [13].
- **Fixed core around the mechanism** [6]: every cell of x 0..s+1, z 1..6, rows 0..5 that the tile doesn't use is fixed `minecraft:stone`
  or air, never a role. That covers:
  - the circuit zone;
  - the west wall's segment, which holds the lever;
  - the partition east of the last slot;
  - the floor and foundation under the line and the circuit;
  - the cell under each D: fixed stone, so a floor role can't power D.

  So no role block touches dust, a torch, a comparator, a repeater, a hopper or the lever. The back wall (z 0) is a role behind z 1's air.
- **The overflow chest** (s + 1, 4, 6), `single`: air above it (s + 1, 5, 6), so it opens [1]. The input chest (1, 5, 6) has the roof slab
  above it, a non-conductor. The slot chests (row 2) and bulk chests (rows 2-3) have air above.
- **The lock:** a lever at (1, 4, 5) on the fixed-stone west wall cell (0, 4, 5), `face=wall, facing=east`. FK writes it `powered=true`
  while built and `false` at the unlock.
- **Bulk** at z 10, facing north, either side of the door; the aisle is z 8-9; the front wall is z 11 with the door at `gate`; the front
  row is z 12.
- **Labels** [12]: a sign above each slot chest at (x, 3, 7) would need sign text, which the kit doesn't write, and item frames are
  entities. So the slot map is read in game: `/steward hall <settlement>` lists each slot's item, the input and the overflow, from the lot's
  params (its `steward_mc:module` ext). The offer and the inbox name them too. *A question for Noah:* is a command and the inbox enough, or
  does he want a look-at HUD label (a UI slice)?
- **Lamps** in the side walls at row 4 (x 0 at z 8 and z W-1, z 2 and 8; the circuit side is the fixed-stone wall, so its lamp is at z 8),
  and in the front wall beside the door.

### 12.3 The fill and the lock (FK, FK-b)

- Each F: slot 0 holds **41 of its filter item, billed**; slots 1-4 hold **1 `minecraft:petrified_oak_slab` each, `provided: true`**
  (M0's 41 + 4; CS: signal 2 at rest, 3 at the 42nd item).
- Lock: the lever above, `{powered: 'true'}`.
- Bill: the fill's `fill:<lot>` piece is 41 of each filter item. The slabs are unbilled and never refunded (FK-b).
- **The bound** (offer and Accept): the measured structure bound for (slots, bulk, Style), re-expressed in the town's blocks as @1, plus
  the fill part, computed exactly: 41 of each chosen item. `ModuleBound` for storage_hall looks up the structure part by `{slots, bulk}` and
  adds the fill, so an edited item choice gets its exact bound. The measurement checks quote = structure bound + fill on every branch.

### 12.4 Offer, items and placement

- **Sign:** as @1 (12+ kinds). The tier gate: the sorting branches are offered from the Nether tier on (comparators need quartz); before
  that, the plain branch.
- **Params at the offer:** `slots` is the largest of 4/8/12/16 that the settlement's distinct domain items fill, counting the stockpile
  and the inventory by count, with at least 4 (else plain). The items are those kinds, most held first, ties by key. `bulk` as @1.
- **Words** name each slot's item, "about N items a second" (the measured rate, § bars), and what it won't sort: "tools, armour, potions,
  renamed or enchanted items, and anything that stacks to 16 go to overflow; never put petrified oak slabs in it" [10].
- **The player changes the items** before accepting: `/steward hall <settlement> items <slot>=<key> ...` on the waiting offer.
  - It checks the dependent rule and recomputes the bound, and the offer's words and bound are rewritten.
  - In talk, a named item list rides on the module's params as @1's.
- **Placement and undo:** M3's growth paths. The binding, quote and realise are as @1, with the fill in the quote. Undo with cargo follows
  MODULES §6's table:
  - **Steward's pre-check** (`HallCargo.before`, M4-L's declared cells) counts as ours: an F holding a sub-multiset of its declaration (≤ 41
    of its item, ≤ 4 slabs), and empty line, D, slot, overflow and bulk containers. Anything else is cargo:
    - an F holding more than declared, even more of its own item;
    - any item in the line, a D, the input or the overflow;
    - a slot or bulk chest holding anything.

    Cargo refuses the undo at once, said with the count.
  - Architect's own blocker rule then judges the same at removal (FK's sub-multiset rule), and its custody takes the F contents in one tick.
- **Repair** (FK-b §6): undo the hall's growth and grow it again. The steward says so when a filter hopper is broken: the lock and fill run
  once per key.

### 12.5 The bars (sorting; fixed now) [2, 3, 4, 8, 9]

1. **Sorting 100%.** Each sorting branch x 4 facings, rustic in full, fortress and desert for sorting and light; 20 runs per branch:
   - the mix is each slot's item x 3 stacks, 5 plain domain kinds the hall doesn't filter, 3 unstackables, a 16-stack and a renamed stack of
     a filter item;
   - it is fed in **acknowledged batches of at most 27 stacks** into the input chest, against an independent expected multiset [8];
   - every filter item ends in its slot, the rest in overflow, the input and the line end empty, items in = out, no item entity.
2. **Throughput:** items a second from the first item leaving the input chest to the last arriving, recorded (a report, speed rule).
   Bar: within 10% of M0's HR (2.241-2.739).
3. **Saturation:** two neighbouring slots' chests full, then a mix:
   - no wrong item in any chest; conserved; the other slots 100%; each saturated F reads 3 or less;
   - emptied, all 100% again, every F back to exactly 41 + 4;
   - a full overflow backs the line up, and recovers once emptied [8].
4. **The filters hold:** after 10,000 items, each F holds exactly 41 + 4.
5. **Restart and unload mid-sort:**
   - a restart with items on the line;
   - an unload of every chunk the hall touches (asserted), with one hall crossing a chunk border **through its circuit** [9];
   - nothing lost or duplicated, sorting continues, the contents exact by component (the renamed stack stays renamed).
6. **Shape, access, light** as @1 [9], plus a fake player opens and empties every chest (input, overflow, each slot chest, each bulk half,
   upper and lower) [1].
7. **Cargo, in isolation** [4], with the line stopped (the lever on) so F stays the blocker. One item alone, then the undo refused and the
   item still there, in each of:
   - the input, the overflow, a slot chest, either bulk half, a line hopper, a D;
   - an F holding one more of its own item;
   - an F holding one item of another kind.

   Then cargo arriving after the pre-check (an item inserted between Steward's check and Architect's removal through a dev hook): Architect
   refuses, nothing lost.
8. **Survival, bill and refunds** [3]:
   - quote = consumed = structure + 41 per filter item; no slab in the quote or the bill;
   - undo of the emptied hall: refund = consumed, minus what the player took, each item apart:
     - F untouched;
     - 8 filter items taken from one F (refund 41 - 8 of it);
     - one F emptied of its item (refund 0 of it);
     - a mined bulk chest half (refund less that chest);
   - the player's inventory, stock, custody and drops are counted per item; no slab ever in the player's inventory or the world (FK-b);
   - a restart after, and a retried undo: nothing twice.
9. **Crash cases** [2], each with items in = items out against the saved state:
   - FK1-FK4 at the fill and FKC1/FKC2 at removal (`dev.fk.halt`), on the hall's own growth path;
   - a removal failing after the custody take (a cargo item put in during the removal's ticks): a restart, then a retry;
   - a cancel before the fill: no filter items written or refunded.
10. **The bound:** every branch's quote = its structure bound (per Style) + 41 per item, for two item sets per branch.
11. **The filler** [10]: a petrified slab put in the input chest is not on the hall's promise. The offer says so; it lands in slot 1's F
    (M0 ST5). The bar is that the next undo refuses it as cargo (F holds more than declared). Recovery is the repair (undo, grow again).
    Recorded, as M0 found.

### 12.6 For the review

- Is the domain-param certification (each domain item filtered at least once, by item class) sound, or must the envelope hold item sets?
- Is the pre-check's ownership rule right for D and the line? Through-traffic in a running hall makes them hold items; the undo then
  refuses until the line is stopped and drains. The steward says "pull the lever by the input chest to stop the hall and wait until it is
  empty", but the lever is behind the line, out of reach. Should the lever be reachable (in the aisle's wall), or should the steward stop it?
- Anything §5's findings ask that this misses.

### 12.7 Design review (GPT-6.1 Sol, at ae3063c, Architect v0.72.0 de231d14) and what changes

3 blockers, 5 should-fixes, 1 nit. The tile, the item-class certification and the bound are confirmed sound. The review's answers to
12.6 are taken: item classes certify the domain; D and the line stay cargo; the steward runs the lever.
- **[B1] Late cargo at removal: an Architect ask, S-M5-12.** Architect's removal scans blockers once and restores ticks later; an item put
  into an undeclared container in between is erased. Steward's pre-check narrows the window, never closes it. Whether @2 certifies before
  S-M5-12 is the manager's and Noah's call.
- **[B2] Ingress before the lock:** bars added (below).
  - The input preloaded at partial construction and while starving: nothing moves.
  - An F contaminated before the fill: `fill_blocked`, no charge, the cargo untouched; emptied, exactly one fill.
- **[B3] Crash:** CS1 is added at removal. A deterministic failed removal after the custody take waits for S-M5-13. Until then the bar is
  FKC1/FKC2/CS1 plus a restart and an unchanged second retry. Each case is judged on the saved files and the physical refunds, per item,
  with no custody, `heldProvided` or pending refund left.
- **[S4] F isolation:** while the extra-in-F case is judged, D is held by a test lock (a dev-placed redstone block beside D, removed
  after). The bar asserts F itself holds the excess and every other container is empty.
- **[S5] Slabs in F:** the recovery is break the F (FK-b strips the slabs), take the dropped billed goods, then undo and grow again. The
  bar: no slab dropped or refunded; the missing hopper and its filter items not refunded; one correct, billed replacement fill.
- **[S6] Ownership:**
  - an F is ours only once its fill committed (FILLED/UNLOCKED);
  - it holds plain stacks only (any component patch is cargo);
  - in total at most 41 of its item and at most 4 slabs;
  - the input, the line, every D and every chest have no allowance.

  M4-L's declared cells become typed cells (input, overflow, line, F, D, slot, bulk), unit-tested against the program's rotated layout.
  HallCargo classifies and says; it never takes or refunds.
- **[S7] Geometry:** the air mask is explicit (the program's `keepAir`: the cell over the low dust, the low dust's point, the cell over the
  overflow), and no shell write may reach it (a program check). Partial chunk unloads are added: a hall crossing a border along its line, and
  one crossing through a tile's control arm, with one side unloaded while the other ticks, then reloaded. Plus the adverse-palette probes.
- **[S8] Labels:** a question for Noah (a look-at HUD label, or the command and inbox).
- **[N9]**
  - Lamps as full triples, in the program: (0, 4, 8), (W-1, 4, 2), (W-1, 4, 8), (gate±2, 4, 11).
  - A lower bulk chest has a chest above, which still opens.
  - "Refund 0 of it" means zero fill contribution: the structure's own stone and redstone are still refunded. The bound and refund tests
    include stone, dirt and redstone as filter items, so structure and fill share an item.

### 12.8 Noah's decision (2026-11-15, PLAN Decisions, main 92b185f): "Certify now, name the gap" (option A)

`storage_hall@2` certifies before Architect's LC (S-M5-12/13) once its bars pass. The offer names the late-cargo gap in one line,
`ModuleOffer.LATE_CARGO_WORDS` behind `LATE_CARGO_GAP`: "While a building is being taken down, don't put items into its chests: they could be
lost." It comes off in one edit at the pin that carries LC3 (LC was reverted at Architect v0.73.0; LC2 became LC3, the seal design). The pre-check still refuses an undo while anything holds cargo. FKC0 (bar 9)
runs at that pin and is recorded as deferred until then. The slot labels for v1 are `/steward hall` with the offer and inbox (rule 13);
a HUD label is a follow-up.
- **Judge clarified after the first run (no bar moved):**
  - A slot is its chest and its output hopper D. The tile unlocks D only long enough to pull the item that tipped F to 42, so the last item
    of each kind through a run waits in D for the next one, as M0's tile did: M0's judge counted D in the slot.
  - A run is done when the input and line are empty and each D holds at most one of its own item.
  - The steward's words for a refused undo say those hoppers hold the last sorted items, and the player takes them out too.
  - A filled F's settled D state is `enabled=false` in the template, so the land check reads a built hall as unchanged.
  - Architect's land check counts a container written empty that now holds items as changed, so each filled F is one changed cell: bar 6
    reads `changed` equal to the slot count, with every F holding exactly 41 + 4 slabs (S-M5-18 asks Architect to count a fill as its own).
- **M4b-074 done at the 0.91.0 pin (2026-11-22, T4R-PIN, 78d8fc22):** FL first ships in v0.91.0. B2's leak branch expects the
  insert in no F, line or D, all 64 cobblestone in the overflow or sort chests, the fill UNLOCKED, nothing past the quote, and `dev.fk.state`
  `held` > 0. Its take-back clears only the overflow, the chests and the input, never F 0. Bar 6 expects `changed 0`. `dev.fk.container`
  answers `state` (FL), and `dev.fk.state` answers `held`. The non-leak dirt branch is unchanged: on 0.91.0 both runs took the leak branch.
- **Follow-up M4b-074 (at the pin that carries Architect LC3 with FL 50f47d9b; v0.74.0 carries neither):** bar 6 goes back to expecting `changed 0` (S-M5-18), and
  sort-survival's mid-build insert expects no item in F (S-M5-17, the hopper refusal); `dev.fk.container` answers `state`, `dev.fk.state` `held`.

### 12.9 Results (2026-11-16, Minecraft 26.3, Architect 0.72.0, kit/checker 0.67.0): certified

`storage_hall@2` is certified (`modules/storage_hall/2/measured.json`, moduleSha 315a60271b22, program aa216ff12306), from the evidence
in `artifacts/m4b` (hall, bounds, sort, sort-survival, sort-cargo and sort-crash, each at one run; the hall is deterministic). It passed:

| Step | Bars | Checks |
|---|---|---|
| hall (`--version 2`) | the plain branch, @1's bars 1-6 | 218/218 |
| bounds | the structure bound per slot count, bulk and Style, and the fill (41 per item) for two item sets | 382/382 |
| sort | 12.5 bars 1-6 over 20 halls: every rustic branch in all four facings (20 runs a branch, 10,000 items a hall), fortress and desert for 4 and 16; every chest opened by a player; the asserted unload; counted drains; chunk crossings | 258/259 (run 5; its one coverage check completed by sort-plain) |
| sort-survival | bar 8 and 12.7 B2: the bill, the mid-build insert, fill_blocked (the contaminant intact and taken back whole), refunds after taking | 19/19 |
| sort-cargo | bars 7 and 11: cargo alone in each container class, the pre-check and the undo, the fed-slab recovery | 26/26 |
| sort-crash | bar 9: FK1-FK4 at the fill (every item since the pre-crash baseline, the fill's items in = out), FKC1, FKC2 and CS1 at removal (no pending refund, an unchanged second undo and a second restart), a cancel before the fill; FKC0 deferred | 68/68 |
| sort-plain | 12.1: the item the sort run never used as a non-filter kind (cherry_log), through a rustic hall in 3 runs | 12/12 |

The rates (items a second, rustic, by slot count): 4 slots 2.342, 8 slots 2.424, 12 slots 2.461, 16 slots 2.397. The catalog's own steps
passed too: offline 295/295, ingame 292/292.

- **Known gaps (as-built, MODULES.md):** S-M5-12, late cargo during an undo, is named in the offer until LC3. S-M5-17, a mid-build insert
  down the line, makes the fill wait `fill_blocked` with nothing charged or lost, until Architect FL. It needs no offer line.
- **S-M5-17's readings at the preload:** the lever and line hopper 0 both stood when the player loaded the input. The harness's `cells` op
  reads only the block id and light, so their states weren't captured. Architect FL's reproduction gave them: the lever `powered=true`
  (written with no neighbour update) and line hopper 0 `enabled=true`.
- **The judge, measured once more:**
  - Bar 6's `changed` is the slot count (S-M5-18, 12.8).
  - sort-crash sets each case up as Architect's own FK gate does: the hall's blocks fed with the fill's items held back until every cell
    stands, the site paused, the fill not begun, the fill's items delivered. Its baseline reaches disk before the point is armed.
  - The fill items there are flint, bone, string and gunpowder, which the structure never uses. A kill point halts the whole JVM, so the
    harness waits for the client to exit, names the point from its own log line ("reached", or FK3's and CS1's "kill point P:"), and
    relaunches into the same world. An exit it can't name fails that case.
- **A harness finding, not Architect's:** `/save-all` is a dedicated-server command. On the integrated client it answers "Unknown or
  incomplete command", and `dev.command` still answers `ok: true` with `success: false` (S-M5-19, Architect's answer). Every `/save-all`
  in `module-measure-hall.mjs` is a no-op. sort-crash's baselines reach disk by Architect's periodic save instead: `dev.stockpile.ps
  {noPeriodic: false}`, waited for until the saved ledger is the live one, then `noPeriodic` on and the point armed. The other steps pass
  because `dev.world.leave` saves the world. Working save barriers today: the periodic save waited for (over 10 s), `dev.world.leave`,
  and `dev.stockpile.halt {mode: "autosave"}` (a save, then a halt). The tooling fix is LEFTOVERS row SAVEB (the manager's).
- **style-bounds** bills a wall-mounted block as its standing item (`redstone_wall_torch` as `redstone_torch`), as Architect's survival
  bill does. The kit's `vocab/survival.json` has no entry for the wall form.

### 12.10 The diff review (GPT-6.1 Sol, speed rule 15) and what came of it

The review (log `_work/m4b-gpt-review.log`) ranked 8 P1 and 3 P2. A fix check and a second fix check followed (`_work/m4b-gpt-fixcheck*.log`).
- **Fixed, measured again:**
  - P2-9: `/steward hall <id> items` is the host's (`Actions.refusal`).
  - P2-10: the same command keeps the offer's district, and bills an adopted town from materials (`path().materials()`).
  - P2-11: a smoke carry keeps `fill` and reads sort-survival as @2's billing step, in the tool and the loader (`ModuleCatalog.billingStep`).
  - P1-1: sort-crash feeds only the fill's items after a restart, and judges every item since the pre-crash baseline and the fill's items
    in = out.
  - P1-2: sort-crash checks no pending refund, then an unchanged second undo (Architect's own refusal) and a second restart.
  - P1-5: sort-survival requires `fill_blocked` and the contaminant taken back whole.
- **Open, the manager's call (not measured as committed):**
  - Bar 1, each sorting branch x 4 facings: the facings are a union across halls; 12 slots ran only west and 16 only south.
  - Bar 5, the mid-sort unload: it waits for the unload but doesn't assert it, and the partial unload doesn't assert the forced side.
  - Bar 6, a fake player opening and emptying every chest: not done; the check reads the blocks above the chests.
  - Bar 9, a cancel before the fill: not run.
  - Hardening: the drain between overflow rounds doesn't count what it removes, the mixes leave 33 domain items never used as a non-filter
    kind, and the restart/unload batches feed only the first 27 stacks.
  - Beyond the bars: survival accounting for the repair path (bar 11 only records it).

### 12.11 The 5th sort run (2026-11-16, the manager's call over the cap) and the open items closed

The review's open items (12.10) were measured as the bars commit them, in one full sort run, through Architect's slot queue (`tools/slot.mjs`):
- **Bar 1:** each rustic branch (4, 8, 12, 16 slots) placed in all four facings: a growth is planned until its lot faces the wanted way,
  then realised (plans are free until realised). 20 runs a branch, every run 100%, and at least 10,000 items through each rustic hall
  for bar 4.
- **Bar 5:** the full unload is asserted (every probe's chunk unloaded, 600 game ticks). The partial unload asserts the forced side loaded
  for 600 ticks and records the other side: in vanilla a forced chunk's neighbours stay loaded and block-ticking, so one side of a hall
  crossing a chunk border never stops ticking under a single forceload. The whole mix is fed across each interruption.
- **Bar 6:** the dev client's own player opens each chest through the block's own use and empties it through the menu: the input, the overflow,
  every slot chest and every bulk half (Fabric's FakePlayer opens no menu). Its inventory is set aside and given back.
- **Bar 9:** a cancel before the fill (sort-crash): the hall's blocks built with the fill's items out of stock, then undone. Nothing of the
  fill was written, charged or refunded, and the structure came back exactly (706 items).
- **Hardening:** each drain counts what it takes and settles before its limit. Each hall's five plain kinds are planned so every domain item is a
  non-filter kind somewhere, asserted before the run.
- **The one miss and its completion:** the 5th run's own offsets left `cherry_log` never a non-filter kind (258/259). The planned
  assignment above now prevents that. `sort-plain` carried it through a rustic hall in 3 runs of 100% (12/12), and `record` accepts
  the sort step's coverage check only when `sort-plain` carried every item it names (`completes` in `tools/module-catalog.mjs`).
- A short probe (`SORT_QUICK=1`, two halls) ran before the full run. It caught the FakePlayer menu and the partial-unload side; it is
  not evidence.
- **The fix-check of these harness changes** (GPT-6.1 Sol, `_work/m4b-gpt-fixcheck3.log`, the manager's scope):
  - **Fixed in code, not re-measured:** the cancel case now also requires queued work, a non-empty structure refund and four empty F
    hoppers before the undo. The 5th run's evidence already shows real work (701/701 cells, 706 = 706 back). `playerOpen` closes any menu
    first and gives the inventory back in a `finally`. `sort-plain` is required only when it completes a coverage failure.
  - **Open: they need another sort run, the manager's call:**
    - The full unload probes only the F hoppers' chunks, not every chunk a hall touches.
    - For 8 to 16 slots, the renamed stack (and some filters) enters only after the interruption.
    - Chest access doesn't stand the player within reach or check the menu's `stillValid`.
  - **Scope:** a single forceload can't bring one side of a border-crossing hall below block-ticking, so 12.7 S7's partial boundary
    isn't measurable this way. The partial run records what it is.

### 12.12 Smoke carry to Architect 0.74.0 (2026-11-16, batch-074)

Carried from 0.72.0 (`basis: smoke`, the full measurement 12.9/12.11 kept in `carried`) to 0.74.0, kit/checker 0.69.0, moduleSha
`2c977fbb…`; the program is unchanged and module.json differs only in its pins. v0.74.0 carries neither LC3 nor FL, so S-M5-12, S-M5-17 and
M4b-074 stand. Fresh: offline 295/295 and ingame 292/292 (the whole live catalog), the hall round 218/218, bounds 382/382 (re-quoted bounds
equal to the carried ones), sort-survival 19/19 (its billing check); ports 9112/9113, slots from Architect's queue (SQ), $0.
