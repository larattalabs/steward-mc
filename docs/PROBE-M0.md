# M0: the vanilla rules phase 5 relies on, probed on 26.3

Slice M0 of phase 5 (`docs/MODULES.md` §2.1 and §8). Every vanilla mechanic the crop farm (§3.1) and the storage hall (§3.2) depend on is
measured here, on the running Minecraft 26.3 server, before any module is built. If a rule fails, phase 5 stops and goes to Noah.

- **Bars first.** This section's bars were committed before the first measurement (git history: this file's first commit comes before
  `fixtures/modules/probe-m0.json`). Results never move a bar. A bar that turns out to be wrongly stated is recorded as a FAIL and a design
  change, never quietly relaxed.
- **How:** `node tools/module-probe.mjs` launches its own client through `tools/run-e2e-client.sh` on ports 8592/8593, in a fresh flat creative
  world, and drives it through the DevBridge with vanilla commands (`/setblock`, `/fill`, `/item`, `/data`, `/execute if predicate`) and
  Architect's read-only hooks (`dev.region.dump` for block states, `dev.items.near` for dropped items). `--only <probe,...>` runs a subset;
  `--judge` re-scores the committed results against these bars without a client.
- **Results:** `fixtures/modules/probe-m0.json` (the numbers, the bars, the Minecraft, Architect and script versions, seeds, run counts).
  The verdict table is at the end of this file.

## Conventions

- Game time is counted in game ticks (20 a second at full speed). "A day" is 24,000 ticks.
- Default rules unless a probe says otherwise: `random_tick_speed 3`, `mob_griefing true`, clear weather. Time and weather are frozen
  (`advance_time false`, `advance_weather false`) and set per probe, so night probes are really at midnight (18000).
- Mob spawning is off (`spawn_mobs false`) except where a probe summons its own mobs.
- Everything measured sits within 48 blocks of the player, inside simulation distance, so random ticks run everywhere measured.
- **Sprinting.** Waits use `/tick sprint` only after probe SE has passed. Until then they run in real time.

## The probes and their bars

### SE: sprinting doesn't change the result

- **Growth.** Wheat on 4 watered 9x9 plots (320 cells), every cell age 0. Advance 6,000 ticks in real time, record each cell's age, replant at
  age 0. Advance 6,000 ticks sprinted, record again.
  - **Bar:** the mean stages gained per cell, sprinted, is within ±20% of real time, and within 3 standard errors of it.
- **Hoppers.** One sorting run of the assembled tile (ST), in real time and then sprinted.
  - **Bar:** the per-chest counts are identical, and the throughput in items per game tick is within 2%.

### H: farmland hydration (§3.1 item 1)

- **Setup.** A 9x9 farmland plot with one water source at its centre, at the farmland's own level, like IRR's contained source. Every
  farmland cell is written dry (`moisture=0`) with wheat at age 0 on it, so it doesn't turn to dirt.
  - The plot straddles a chunk corner (x = 16k and z = 16k).
  - A ring of farmland at Chebyshev distance 5 from the source, also planted, is the reach control.
- **Measured:** the game tick at which each cell first reads `moisture=7`, sampled every 200 ticks; 5 runs.
- **H1 (time; the design's bar).** Every one of the 80 cells reads `moisture=7` within 4,800 ticks (IRR's as-built 240 s), in each of the 5
  runs.
- **H2 (reach).** At 4,800 ticks, the distance-5 ring reads `moisture=0` on every cell, in every run (vanilla's reach is 4).
- **H3 (a moist field stays moist).** The same plot written moist (`moisture=7`), 24,000 ticks: every cell reads 7 at every sample. A moist
  plot with no water dries to below 7 within 24,000 ticks (control).
- **H4 (recorded, never judged).** Water one block below the farmland's level, then one block above it: the cells hydrated after 4,800
  ticks.

### G: crop growth, light and maturity (§3.1 items 2 and 3, §2.1)

- **The crops** and their maximum age: wheat 7, carrots 7, potatoes 7, beetroot 3.
  - **G0 (maturity).** Each crop's block state carries `age` with exactly that maximum: `/setblock` of age max+1 is refused, and a crop at
    its maximum gains nothing over 24,000 ticks.
- **The fields.** For each crop, a watered 9x9 plot (moist farmland, centre source, 80 crops) and an unwatered 9x9 plot (dry farmland, no
  water, 81 crops). All are under open sky at noon; 5 runs, each with fresh plots at age 0.
- **Measured:** each cell's age every 1,000 ticks, until 90% of the watered plot is mature or 150,000 ticks.
  - **T** is the first sample at which at least half of a plot is mature: the time to a mature majority.
  - The yield: at T, the mature crops on the watered plot are broken with `/setblock ... destroy` (no tool, no fortune, as a player's hand).
    Their drops are counted from the ground.
- **G1 (watered growth).** The 5 runs' T for each crop is recorded with its band (min, median, max). **Bar:** every run reaches T within
  150,000 ticks.
- **G2 (the unwatered control).** **Bar:** for each crop, the unwatered plot's median T is at least 1.5x the watered plot's median T.
  Plots that never reach a majority count as 150,000.
- **G3 (the absolute usefulness bar, set now).** For each crop, the net harvest rate of a 9x9 watered field (80 crops) must be at least
  **24 items a day**:
  - net harvest = the drops of the crop's harvested item (wheat, carrot, potato, beetroot) from the mature cells at T, minus one item per
    replanted cell where the item itself is the planting item (carrots, potatoes);
  - rate = net harvest / (median T / 24,000).

  A field below this isn't worth offering at its size.
- **G4 (block light; the design's lamp bar).** Inside a sealed stone room at midnight, with no sky light, one lantern. Crops on moist
  farmland sit at measured block light 9, then 8, in two runs of 24,000 ticks.
  - The light level is read with a `location_check` light predicate. That reading is checked first against a lantern's falloff of 15 minus
    the taxicab distance.
  - **Bar:** the light-8 crops gain 0 stages. The light-9 crops gain stages at a rate within the watered band (G1).
- **G5 (recorded, never judged).** An open-sky plot at midnight with no block light: whether it grows. If it does, lamps are needed only to
  stop mobs spawning, not for growth. §3.1's light item then changes meaning, but it stays as a mob-safety bar.
- **G6 (recorded).** The block light each candidate lamp gives, for the palette rule "lamp role gives light 14 or more" (§2.2). Read one
  cell away and recorded as the source level: lantern, torch, soul lantern, sea lantern, glowstone, shroomlight, end rod, lit redstone lamp,
  a lit candle (1 to 4), lit copper lantern if present.

### TR: trampling (§2.1)

- **Setup.** Moist farmland with wheat on it. An entity is dropped from 2 blocks above, 10 times for each case.
- **Measured:** how many drops turn the farmland to dirt.
- **TR1.** A cow: **bar 10/10** with `mob_griefing true`, and **0/10** with `mob_griefing false`.
- **TR2.** A chicken (too small to trample): **bar 0/10**.
- **TR3.** A zombie and a villager, with `mob_griefing true`: **bar 10/10 each**. These are the mobs a fence must keep out.
- **TR4.** The player, in survival, dropped 2 blocks: **bar 10/10**. The player walking across the field (repeated `/tp` steps along the
  ground, never airborne): **0 cells** changed.

### HR: hopper transfer rate (§3.2 item 2)

- **Setup.** A chest holding 320 cobblestone feeds a chain of 8 hoppers into an empty chest.
- **Measured:** the game ticks from the first item arriving in the sink to the 320th; 3 runs.
- **Bar:** 8.0 ticks per item ±5%, which is 2.5 items a second at 20 ticks a second.

### HL: a hopper locked by redstone (§2.1, the tile's lock)

- **HL1 (a torch beside it).** A hopper with a lit redstone torch beside it, holding 64 items, over an empty hopper that pulls from it. A
  locked hopper moves nothing.
  - **Bar:** 0 items move in 400 ticks, in either direction (it neither pushes nor is pulled through).
  - Then the torch is removed: the first item moves within 20 ticks.
- **HL2 (a strongly powered block beside it).** The same hopper with a solid block beside it, strongly powered by a torch under that block,
  as in the tile. **Bar:** as HL1.
- **HL3 (a locked hopper still receives).** A locked hopper under an unlocked hopper that points into it. **Bar:** it receives items, and
  passes none on.

### CS: a comparator's signal from a hopper's fill (§2.1, the tile's threshold)

- **Setup.** A comparator reads a hopper holding n of a 64-stacking item in slot 0 and 1 renamed filler in each of slots 1 to 4. The signal is
  read from the comparator's `OutputSignal`.
- **Bar (exact):**
  - every n in 0..64 gives `floor(1 + 14 × (n + 4) / 320)`;
  - in particular 41 gives **2**, 42 gives **3**, and 64 gives **3** (never 4: the overflow protection);
  - an empty hopper gives 0.
- **Also recorded:** a 16-stacking item in slot 0 (n = 1..16), to show why the design keeps 16-stack items out of the filter domain.

### FC: a full chest at the end of a chain (§2.1)

- **Setup.** A chain of 4 hoppers ends in a chest filled to the top (27 stacks of 64 stone). 320 cobblestone are fed in, and the chain runs
  for 2,400 ticks.
- **Bar:** no item entity appears, and the items in the source, the chain and the sink equal those fed in, exactly. The chain backs up and
  stays full.

### ST: the assembled sorter tile (§3.2, §2.1)

**The candidate.** The Minecraft Wiki's "basic hopper item sorter", 1-wide, tileable, with overflow protection. Per slot (the line runs
along +x, the slot's back is -z, y from the lower hopper):

| Cell (z, y) | Block |
|---|---|
| (0, 2) | line hopper, pointing along the line (+x); the last one points into the overflow chest |
| (0, 1) | filter hopper F, pointing back (-z) into the comparator, so it never pushes |
| (0, 0) | output hopper D, pointing forward (+z) into the slot's chest at (1, 0) |
| (-1, 1) | comparator reading F, output to -z; on a solid block at (-1, 0) |
| (-2, 1), (-3, 1) | redstone dust, on solid blocks at (-2, 0) and (-3, 0) |
| (-4, 0) | redstone dust (one step down), on a solid block at (-4, -1) |
| (-3, -1) | repeater, input from -z, output +z, on a solid block at (-3, -2) |
| (-2, -1) | solid block |
| (-1, -1) | redstone wall torch on (-2, -1), facing +z: it strongly powers (-1, 0), which locks D |

- **F's fill:** 41 of its filter item in slot 0, and 1 renamed filler in each of slots 1 to 4. The filler is a stick named "Steward
  filler".
- **The signal:** 2 at rest. The dust at (-4, 0) carries signal minus 2, which is 0, so the torch stays lit and D stays locked. A 42nd
  filter item makes the signal 3, so the dust is 1, the repeater fires, the torch goes out, and D pulls one item into the chest.
- **Slot chests** are single chests written with `type=single`. Neighbouring single chests in a row would otherwise merge, so ST checks
  that they stay single.
- **Input and overflow.** An input chest sits above the first line hopper, and the overflow chest at the line's end.

**The halls under test.** Four 4-slot halls, one per facing (the tile rotated 0, 90, 180 and 270 degrees). The east-facing hall straddles a
chunk border (x = 16k between its slots 2 and 3). The filter items are cobblestone, iron ingot, wheat and oak planks.

**The mix (one run):**
- each slot's item × 3 stacks;
- 5 plain kinds the hall doesn't filter: dirt, sand, gravel, andesite, coal, 64 each;
- an unstackable item (3 iron swords);
- a 16-stacking item (16 ender pearls);
- an item with components (64 cobblestone named "Not filtered": a filtered kind, but a different item stack);
- the filler kind unnamed (64 plain sticks).

That is 1,235 items per hall per run. The mix is loaded into the input chest, and the run ends when every hall's input chest and line are
empty, or after 20,000 ticks.

- **ST1 (sorting; §3.2 item 1).** 20 runs; in each run, every hall:
  - every filter item is in its slot's chest;
  - everything else is in overflow;
  - the input chest and the line are empty;
  - items in = items out (chests + F + D), and no item entity.

  **Bar: 100%.** The chests are emptied between runs; F and D are left as they are.
- **ST2 (throughput; §3.2 item 2).** Items a second end to end: the mix's count over the ticks from the first item leaving the input chest
  to the last arriving, × 20. **Bar:** within 10% of HR's single-line rate. Recorded per hall and run.
- **ST3 (the filters hold; §3.2 item 4).** After the 20 runs (more than 10,000 items through each hall), each F holds exactly 41 of its item
  and its 4 fillers. **Bar: exact.**
- **ST4 (saturation and recovery; §3.2 item 3).**
  - Slots 1 and 2 of each hall have their chests filled to the top with their own items. Then one mix is fed.
  - **Bar:**
    - no wrong item lands in any chest;
    - items are conserved: input + line + F + D + chests = fed, and no item entity;
    - slots 3 and 4 still sort 100%;
    - each saturated F reads 3 or less (never 4).
  - Then the two chests are emptied and another mix is fed. **Bar:** all four slots sort 100%, and every F is back to exactly 41.
- **ST5 (the filler item on the line; recorded).** 64 sticks named "Steward filler", the filler itself, are fed.
  - Recorded: where they end up, and whether any F's filter count changes.
  - §3.2 says the filler goes to overflow. ST5 shows whether this tile can promise that, or whether the design must keep the filler out of
    the mix and the item domain (a FAIL against §3.2's bar, with the change named).
- **ST6 (chests stay single).** After all runs, every slot chest still reads `type=single` and holds only its slot's item.

### Not in M0

These are M2 and M4's measurements, on the real modules:
- restarts and chunk unloads mid-growth or mid-sort (§3.1 item 5, §3.2 item 5);
- mob exclusion by the fence (§3.1 item 4);
- the restart-and-crash cases;
- survival billing;
- the four facings of the farm.

§8 names Steward's own dev hooks (insert stacks, count a box's containers, harvest as a fake player, replant, set a gamerule) under M0. M0
does without them: vanilla `/item`, `/data` and `/setblock ... destroy` cover the probe. They are built with M2 and M4, which need them on
real module lots.

## Verdict

(Filled in from `fixtures/modules/probe-m0.json` after the runs.)
