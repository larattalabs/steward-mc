# M2: `crop_farm@1` measured

Slice M2 of phase 5 (`docs/MODULES.md` §3.1 as amended by §9, §2.2's envelope, §8's M2 row). The module is `modules/crop_farm/1/`:
program, params schema, envelope and palette rules. This file fixes the measurement's bars **before the first candidate is measured**:
git history shows this file's first commit before any `artifacts/m2/<step>.json` that judges a bar, and before `measured.json` carries a
result. Results never move a bar. A bar that turns out to be wrongly stated is recorded as a FAIL and a design change, never quietly relaxed.
If a bar fails because of a vanilla rule, the slice stops and reports it (it goes to Noah).

- **How:** `node tools/module-measure.mjs <step>` launches its own client (`tools/run-e2e-client.sh`, ports 8594/8595) in a fresh flat world.
  - It builds V towns (Architect's `flat_village`, pads only, no irrigation).
  - It installs the catalog entry at $0 by its key, through Steward's own `ModuleInstalls` (`/steward dev module`).
  - It grows the towns by irrigated crop_farm lots: `planRevision` grow lots from Steward's `CropFarmLot`, with `irrigate: true` and
    `field {cells, crops}` (S-M5-2, S-M5-4).
  - For each lot it makes a keyed variant from the catalog install, with the lot's IRR cells (`architect_mc:lotIrrigation.lotCells`)
    reserved, and realises the growth.
  - It drives the world through the DevBridge:
    - vanilla commands; the game is frozen and advanced with `/tick sprint`, since M0's SE showed sprinting doesn't change growth;
    - Steward's `cells` read (block, age, moisture, block light) and `walk` (a fake player's collision walk);
    - Architect's PT hooks (`dev.pt.breaks` and `dev.pt.uses`: a fake player's `destroyBlock` and `useItemOn`, never `setBlock`);
    - stockpile, land-check, quote, revision and items hooks.
- **Results:** `artifacts/m2/<step>.json` (local evidence), then `modules/crop_farm/1/measured.json`, written only by
  `tools/module-catalog.mjs record` from passing runs over the same program and module.json.
- **Conventions (M0's):** game ticks, 24,000 a day; `random_tick_speed 3`, `mob_griefing true`, clear weather, frozen time (noon for growth,
  midnight, 18000, for light and mobs); mob spawning off except the summoned mobs of item 4; every field inside the player's simulation
  distance while it is measured.

## The envelope (§2.2)

- **Branches.** Every field size pair, width and depth each one of 9, 18 or 27, so 9 pairs, times the 4 crops (wheat, carrots, potatoes,
  beetroots): 36 branches.
- **Facings.** All four. A `flat_village` laid east-west (`orientation: ew`) fronts its growth lots north and south; one laid north-south
  (`ns`) fronts them east and west. The measurement records each farm's front and judges the cover: every facing has at least 9 farms, and
  each crop is measured on both street orientations.
- **Chunk borders.** Each farm records whether its box crosses a chunk border in x and in z. Bar: at least one farm of every size pair crosses
  in both. Every lot wider or deeper than 16 crosses on that axis whatever its placement.
- **Styles.** rustic, fortress and desert (§9). rustic is measured in full (items 1-7). Items 1, 2 and 4 also run on fortress and desert
  over all 9 size pairs, since the lamp and fence roles change with the Style. One farm built with a too-dim lamp (soul lantern) is
  measured for item 2 with its fallback lanterns.
- **The checker** over every branch x Style: `tools/module-catalog.mjs offline` and `ingame` (M1's), clean.

## The bars

### 1. Hydration (§9 answer 1)

The program writes every farmland cell of its field as `farmland[moisture=7]`.
- **1a.** On every farm, every one of the program's farmland cells reads `moisture=7` 24,000 ticks after the growth is PLACED, at random tick
  speed 3, with its crops growing.
- **1b.** On every farm of item 3, every farmland cell reads `moisture=7` at every harvest of every run.
- **Recorded, not judged:**
  - the cells below 7 at the moment of PLACED: the lot is placed before the fluids stage, so a random tick in between dries a cell one step;
  - the samples every 4,000 ticks in between.

### 2. Light: mob safety (§9 answer 2)

26.3 spawns a monster only where the block light is at most the dimension's `monsterSpawnBlockLightLimit`, 0 in the overworld
(`Monster.isDarkEnoughToSpawn`, read from the 26.3 jar with `javap`).
- **2.** At midnight, every cell at floor + 1 of the lot (frame y 1) that holds air or a crop has **block light 1 or more**. That covers the
  crop cells, the front row's open cells and the cells over the sources; fence, gate, lamp, chest, composter and water cells are left out.
  - Read with `level.getBrightness(BLOCK, pos)` on every farm: every branch in rustic, all 9 size pairs in fortress and in desert, and the
    dim-lamp farm.
- **Recorded:** the lowest block light per farm, and the share of crop cells at 9 or more (vanilla's growth threshold, which matters only
  under a roof).

### 3. Output rate (§3.1 item 3)

For every crop x size branch, rustic, 5 runs, each 3 cycles.
- **One run:** every crop cell planted at age 0 (by the program for run 1; by the player, `useItemOn`, for the next runs). Wait T, the probe's
  median time to a mature majority: wheat 60,060, carrots 60,060, potatoes 56,056, beetroot 35,035 ticks. Then the player breaks every mature
  crop (`destroyBlock`), the drops are counted from the ground, and the player replants each broken cell. Repeat 3 times. At the end of the
  run every crop left is broken (not counted) and the field is replanted at age 0.
- **The net rate:** a crop's item counted (wheat, carrot, potato, beetroot), minus one per replant when the item is its own planting item
  (carrots, potatoes), over 3 T, per crop cell a day. The offer quotes this rate.
- **3a. The band.** Each run's net rate per crop cell a day lies inside the band below.
  - The band is predicted from the probe's own per-cell maturity times and yields (`tools/lib/crop-farm-bars.mjs`, deterministic). It is
    the 99% band of 4,000 simulated fields of that branch's crop count, widened 15% each way.
  - The probe field had a centre source and every cell planted; ours has a well of posts.
- **3b. Usefulness (G3's bar, 24 a day per 80 crops: 0.3 per crop cell a day).**
  - *Pending the manager's answer (sent before any measurement). Proposed:* judged on G3's own basis, harvested as each crop ripens.
  - That basis: `n × (mean drops per crop − replant) / (mean t_cell / 24,000)`, from the per-cell maturity times each farm records in
    cycle 1 of every run (each crop cell's age read every 1,000 ticks).
  - Under §3.1's fixed-T cycle, wheat is predicted at 0.248 per crop a day, under 0.3. Carrots, potatoes and beetroot clear 0.3 either way.
- **3c. The control.** The mean over 5 runs is at least 1.5 times the predicted unwatered rate below. That is the probe's unwatered field,
  2.08-2.34 times slower, under the same loop.
- **Recorded:** gross drops, the replant stock, the seeds, the poisonous potatoes, and the cells that matured per cycle.

The bands (net items per crop cell a day; one run; `node tools/module-measure.mjs bands`):

| Branch (crops) | Predicted per crop a day | Band of one run | Usefulness | Unwatered (predicted) |
|---|---|---|---|---|
| wheat:9x9 (76) | 0.248 | 0.189-0.312 | 0.3 | 0.115 |
| wheat:9x18 (152) | 0.248 | 0.196-0.304 | 0.3 | 0.115 |
| wheat:9x27 (228) | 0.248 | 0.199-0.301 | 0.3 | 0.115 |
| wheat:18x9 (152) | 0.248 | 0.196-0.304 | 0.3 | 0.115 |
| wheat:18x18 (304) | 0.248 | 0.201-0.299 | 0.3 | 0.115 |
| wheat:18x27 (456) | 0.248 | 0.202-0.296 | 0.3 | 0.115 |
| wheat:27x9 (228) | 0.248 | 0.199-0.301 | 0.3 | 0.115 |
| wheat:27x18 (456) | 0.248 | 0.202-0.296 | 0.3 | 0.115 |
| wheat:27x27 (684) | 0.248 | 0.204-0.294 | 0.3 | 0.115 |
| carrots:9x9 (76) | 0.663 | 0.507-0.843 | 0.3 | 0.317 |
| carrots:9x18 (152) | 0.663 | 0.521-0.822 | 0.3 | 0.318 |
| carrots:9x27 (228) | 0.663 | 0.527-0.815 | 0.3 | 0.318 |
| carrots:18x9 (152) | 0.663 | 0.521-0.822 | 0.3 | 0.318 |
| carrots:18x18 (304) | 0.663 | 0.533-0.806 | 0.3 | 0.318 |
| carrots:18x27 (456) | 0.663 | 0.537-0.803 | 0.3 | 0.318 |
| carrots:27x9 (228) | 0.663 | 0.527-0.815 | 0.3 | 0.318 |
| carrots:27x18 (456) | 0.663 | 0.537-0.803 | 0.3 | 0.318 |
| carrots:27x27 (684) | 0.662 | 0.541-0.800 | 0.3 | 0.318 |
| potatoes:9x9 (76) | 0.703 | 0.507-0.906 | 0.3 | 0.282 |
| potatoes:9x18 (152) | 0.703 | 0.519-0.885 | 0.3 | 0.282 |
| potatoes:9x27 (228) | 0.702 | 0.525-0.872 | 0.3 | 0.282 |
| potatoes:18x9 (152) | 0.703 | 0.519-0.885 | 0.3 | 0.282 |
| potatoes:18x18 (304) | 0.702 | 0.530-0.868 | 0.3 | 0.282 |
| potatoes:18x27 (456) | 0.703 | 0.535-0.861 | 0.3 | 0.282 |
| potatoes:27x9 (228) | 0.702 | 0.525-0.872 | 0.3 | 0.282 |
| potatoes:27x18 (456) | 0.703 | 0.535-0.861 | 0.3 | 0.282 |
| potatoes:27x27 (684) | 0.703 | 0.536-0.855 | 0.3 | 0.282 |
| beetroots:9x9 (76) | 0.411 | 0.312-0.525 | 0.3 | 0.176 |
| beetroots:9x18 (152) | 0.412 | 0.323-0.511 | 0.3 | 0.176 |
| beetroots:9x27 (228) | 0.411 | 0.327-0.504 | 0.3 | 0.177 |
| beetroots:18x9 (152) | 0.412 | 0.323-0.511 | 0.3 | 0.176 |
| beetroots:18x18 (304) | 0.411 | 0.330-0.499 | 0.3 | 0.176 |
| beetroots:18x27 (456) | 0.412 | 0.334-0.495 | 0.3 | 0.176 |
| beetroots:27x9 (228) | 0.411 | 0.327-0.504 | 0.3 | 0.177 |
| beetroots:27x18 (456) | 0.412 | 0.334-0.495 | 0.3 | 0.176 |
| beetroots:27x27 (684) | 0.412 | 0.337-0.490 | 0.3 | 0.176 |

### 4. Exclusion (§3.1 item 4)

On every farm of the exclusion world: 9 size pairs x 3 Styles, crops spread, all four facings.
- **The mobs.** Outside the closed fence, 2 zombies, 2 villagers and 2 cows per farm, summoned 3 cells outside the fence on its sides and
  back. These are TR's tramplers and the mobs a fence must keep out (M0's TR1 and TR3). Difficulty easy, midnight, mob spawning off,
  `mob_griefing true`, 12,000 ticks (10 in-game minutes).
- **4a.** No summoned mob is inside the fence (u 1..W-2, v 1..D-3 of the lot's frame) at any of the samples, taken every 1,000 ticks.
- **4b.** Every farmland cell is still farmland, and every crop cell still holds its crop.
- **4c.** A player gets in and out through the gate. A fake player walks with vanilla's collision (`Entity.move`) from the front row
  through the gate cell into the field:
  - with the gate closed it doesn't get in;
  - the player opens the gate (`useItemOn`), walks in, then walks out;
  - checked on every farm.
- **Recorded, not judged: spiders.** Spiders climb walls, fences included. One spider is summoned beside each of 3 farms set apart for it,
  and whether it got in and what it trampled is recorded. If a spider gets in, it is a limit of vanilla's fence, named on the offer.

### 5. Restarts (§3.1 item 5)

On every farm of item 3, in run 1, after the first harvest, at half a cycle:
- **5a.** Every crop cell's block and age, and every farmland cell's moisture, are read. The world is saved and left, then opened again.
  Every one is the same.
- **5b.** The player goes 4,000 blocks away until the farms' chunks are unloaded (they read as unloaded), 2,000 ticks pass there, and the
  player comes back. Every crop's age is the same, and every farmland cell still reads `moisture=7`.

### 6. Working the field (§3.1 item 6, under S-M5-4) and 7. Survival (§3.1 item 7, §6)

In a survival world (`/architect survival on`), towns grown from the owner's stockpile by crop farms covering all 9 size pairs and all 4
crops, rustic. Fed from the stockpile until PLACED.
- **7a. The bill.** The stockpile's consumption over the growth equals `Regions.quote` of the plan with its entries (QT), item for item. It
  includes the IRR shells.
- **6. Working the field.**
  - The crops are grown T, every mature crop is harvested by the player (`destroyBlock`, drops taken away), and some harvested cells are
    replanted by the player through `useItemOn` with each of the 4 crops. One harvested cell on each farm gets a player's cobblestone with a
    player's torch on it.
  - The world is saved, left and opened again. Then:
  - **6a.** Every crop and planting PT recorded before the restart is still recorded after it.
  - **6b. Land check (LC).** The farm's lot row has changed 0. playerPlaced counts only the player's non-crop blocks: the cobblestone and the
    torch.
  - **6c. Standing.** The standing score of the farm's site counts the worked field as standing. The non-crop player blocks are the only
    mismatches.
  - **6d. Undo.** `undoRevision` removes the growth: not blocked, with the sources drained first.
- **7b. Refunds per §6's table.**
  - The stockpile is credited exactly the consumption, less one construction seed for every cell the player harvested.
  - Every player replant gives back its planting item exactly once, as the player's: dropped at the site, never credited.
  - The cobblestone and the torch come back as the player's.
  - Items in = items out:

    ```
    consumed = credited + harvested cells' seeds
    planted by the player = dropped planting items
    ```

  - The world is saved, left and opened again: nothing is given twice (the ledger, its stock and the items on the ground are unchanged).
- **The save rule (IG's open issue, JR not in 0.63.0).** A kill or restart case saves first (`/save-all flush`).
- **Spending code.** M2 changes no billing or undo code in Steward; it measures Architect's. If that stops being true, the change gets a GPT
  second opinion.

## Results

Recorded by `tools/module-catalog.mjs record` into `modules/crop_farm/1/measured.json` once every step passes; the verdict table is added
here after the measurement.
