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
  over all 9 size pairs, since the lamp and fence roles change with the Style. The fallback lantern needs no farm of its own: all three
  Styles' lamp role is `minecraft:lantern`, the fallback block itself, so every farm measures its light (amended before any measurement:
  the first draft named a soul-lantern farm). *This narrowed the measurement, it did not make it stricter (the gate-verifier's flag): the
  substitution itself, a lamp under light 14 replaced by the lantern and said, is checked offline over the kit (`module-catalog.mjs
  offline`, the `dimLamp` probe), not by a farm in game; the block that results is measured on every farm.*
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
  - Read with `level.getBrightness(BLOCK, pos)` on every farm: every branch in rustic, all 9 size pairs in fortress and in desert (each with the
    fallback lantern, above).
- **Recorded:** the lowest block light per farm, and the share of crop cells at 9 or more (vanilla's growth threshold, which matters only
  under a roof).

### 3. Output rate (§3.1 item 3)

For every crop x size branch, rustic, 5 runs, each 3 cycles.
- **One run:** every crop cell planted at age 0 (by the program for run 1; by the player, `useItemOn`, for the next runs). Wait T, the probe's
  median time to a mature majority: wheat 60,060, carrots 60,060, potatoes 56,056, beetroot 35,035 ticks. Then the player breaks every mature
  crop (`destroyBlock`), the drops are counted from the ground, and the player replants each broken cell. Repeat 3 times. At the end of the
  run every crop left is broken (not counted) and the field is replanted at age 0.
- **The crops counted are the cells actually planted.** The well gives up 5 of every 81 field cells (a source and its 4 sides), and the port
  (the composter and the chest just inside the gate) 2 more, so a 9x9 has 74 crops, an 18x18 302 and a 27x27 682. Every per-day rate here, and the rate the offer quotes, is computed over those planted cells
  (the `n` of each branch below), never over the field's area.
- **The net rate:** a crop's item counted (wheat, carrot, potato, beetroot), minus one per replant when the item is its own planting item
  (carrots, potatoes), over 3 T, per crop cell a day. The offer quotes this rate.
- **3a. The band.** Each run's net rate per crop cell a day lies inside the band below.
  - The band is predicted from the probe's own per-cell maturity times and yields (`tools/lib/crop-farm-bars.mjs`, deterministic). It is
    the 99% band of 4,000 simulated fields of that branch's crop count, widened 15% each way.
  - The probe field had a centre source and every cell planted; ours has a well of posts.
- **3b. Usefulness** (G3's bar, 24 a day per 80 crops: 0.3 per crop cell a day). **Noah's decision, 2026-11-12, option (a)** (relayed by
  the Steward manager):
  - It is judged on G3's own basis, harvested as each crop ripens: `n × (mean drops per crop − replant) / (mean t_cell / 24,000)`, from the
    maturity times of each run's first planting. Each crop cell's age is read every 1,000 ticks through all 3 cycles. A cell keeps its first
    planting until it is harvested mature, so a cell not mature at T is followed into cycles 2 and 3. A cell still immature at 3T counts as
    4T, which lowers the rate.
  - *Judge amended after the first measurement, made stricter* (2026-11-13). The first judge read ages in cycle 1 only and counted a cell
    not mature at T as maturing at T. That shortens the mean t_cell and **overstates** the rate; this doc had said it understated it.
    About half of wheat's cells are such cells. The review that found it was the advisor, before the gate-verifier. All four field steps
    were measured again under the amended judge. The first evidence is kept locally in `artifacts/m2-recorded` as the record of what
    changed.
  - The band (3a) and the rate the offer quotes stay §3.1's fixed-cycle protocol rate, the conservative number. Wheat's is predicted at 0.248
    per crop a day, about 20 a day per 80 crops, under G3's 24 on that basis; carrots, potatoes and beetroot clear 0.3 either way.
- **3c. The control.** The mean over 5 runs is at least 1.5 times the predicted unwatered rate below. That is the probe's unwatered field,
  2.08-2.34 times slower, under the same loop.
- **Recorded:** gross drops, the replant stock, the seeds, the poisonous potatoes, and the cells that matured per cycle.

The bands (net items per crop cell a day; one run; `node tools/module-measure.mjs bands`). *Re-cut once for the second candidate's crop
counts (the port moved inside the fence, below): the same model and widening, only n changed. Before it, one 1-run harness trial of the first
candidate (beetroot) had run, never recorded: its 9 rates were 0.386-0.418, inside the old and the new bands alike.*

| Branch (crops) | Predicted per crop a day | Band of one run | Usefulness | Unwatered (predicted) |
|---|---|---|---|---|
| wheat:9x9 (74) | 0.248 | 0.191-0.313 | 0.3 | 0.115 |
| wheat:9x18 (150) | 0.248 | 0.195-0.305 | 0.3 | 0.115 |
| wheat:9x27 (226) | 0.248 | 0.199-0.302 | 0.3 | 0.115 |
| wheat:18x9 (150) | 0.248 | 0.195-0.305 | 0.3 | 0.115 |
| wheat:18x18 (302) | 0.248 | 0.201-0.299 | 0.3 | 0.115 |
| wheat:18x27 (454) | 0.248 | 0.202-0.297 | 0.3 | 0.115 |
| wheat:27x9 (226) | 0.248 | 0.199-0.302 | 0.3 | 0.115 |
| wheat:27x18 (454) | 0.248 | 0.202-0.297 | 0.3 | 0.115 |
| wheat:27x27 (682) | 0.248 | 0.204-0.294 | 0.3 | 0.115 |
| carrots:9x9 (74) | 0.663 | 0.508-0.847 | 0.3 | 0.318 |
| carrots:9x18 (150) | 0.663 | 0.523-0.821 | 0.3 | 0.318 |
| carrots:9x27 (226) | 0.663 | 0.530-0.813 | 0.3 | 0.318 |
| carrots:18x9 (150) | 0.663 | 0.523-0.821 | 0.3 | 0.318 |
| carrots:18x18 (302) | 0.663 | 0.534-0.809 | 0.3 | 0.318 |
| carrots:18x27 (454) | 0.663 | 0.537-0.802 | 0.3 | 0.318 |
| carrots:27x9 (226) | 0.663 | 0.530-0.813 | 0.3 | 0.318 |
| carrots:27x18 (454) | 0.663 | 0.537-0.802 | 0.3 | 0.318 |
| carrots:27x27 (682) | 0.663 | 0.541-0.799 | 0.3 | 0.318 |
| potatoes:9x9 (74) | 0.703 | 0.508-0.910 | 0.3 | 0.282 |
| potatoes:9x18 (150) | 0.702 | 0.520-0.886 | 0.3 | 0.282 |
| potatoes:9x27 (226) | 0.703 | 0.526-0.873 | 0.3 | 0.283 |
| potatoes:18x9 (150) | 0.702 | 0.520-0.886 | 0.3 | 0.282 |
| potatoes:18x18 (302) | 0.703 | 0.530-0.871 | 0.3 | 0.282 |
| potatoes:18x27 (454) | 0.702 | 0.534-0.862 | 0.3 | 0.282 |
| potatoes:27x9 (226) | 0.703 | 0.526-0.873 | 0.3 | 0.283 |
| potatoes:27x18 (454) | 0.702 | 0.534-0.862 | 0.3 | 0.282 |
| potatoes:27x27 (682) | 0.702 | 0.536-0.857 | 0.3 | 0.282 |
| beetroots:9x9 (74) | 0.411 | 0.309-0.525 | 0.3 | 0.177 |
| beetroots:9x18 (150) | 0.412 | 0.322-0.511 | 0.3 | 0.177 |
| beetroots:9x27 (226) | 0.412 | 0.326-0.504 | 0.3 | 0.177 |
| beetroots:18x9 (150) | 0.412 | 0.322-0.511 | 0.3 | 0.177 |
| beetroots:18x18 (302) | 0.412 | 0.330-0.501 | 0.3 | 0.177 |
| beetroots:18x27 (454) | 0.412 | 0.334-0.497 | 0.3 | 0.177 |
| beetroots:27x9 (226) | 0.412 | 0.326-0.504 | 0.3 | 0.177 |
| beetroots:27x18 (454) | 0.412 | 0.334-0.497 | 0.3 | 0.177 |
| beetroots:27x27 (682) | 0.412 | 0.337-0.491 | 0.3 | 0.177 |

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

## Candidates

- **Candidate 1** (program sha `b0824ea4…`, d9045b2): the composter and the chest stood in the front row, outside the fence, against it.
  Its first skin run (rustic) failed 4a and 4b: zombies and villagers got into 5 of 9 fields and trampled 24 cells. The fence ring was whole
  (every ring cell a fence, checked) and no mob was inside when summoned: they climbed the composter (full height) or the chest (0.875) and
  stepped over the 1.5-high fence. A design fault, not a vanilla rule: a fence keeps mobs out only where nothing beside it is a step.
- **Candidate 2:** the port just inside the gate (field row v = D-3, either side of the gate column, on path). 2 crop cells fewer per farm.
  Its skin rustic shakedown passed 53/53. Its survival shakedown read 2 standing mismatches on every farm as built: the dirt path under the
  composter and the chest, turned to dirt by vanilla (a dirt path under a solid block). Not volatile, so standing counts it.
- **Candidate 3:** the port on the foundation role instead of a path. The layout and the crop counts are candidate 2's.

## Results

**Re-certified on Architect 0.64.1 (2026-11-13, the manager's ask after main's c9d3273 pin).** The pins moved to kit/checker 0.59.0
(239e3dd), which changes module.json's sha, so every step was measured again with the program unchanged (`c406df05…`, moduleSha
`ad0decb0…`). The steps were offline 115/115, ingame 116/116, survival 63/63, bounds 111/111, skin rustic/fortress/desert 53/53 each, border
23/23, field wheat/carrots/potatoes/beetroots 62/62 each (5 runs, amended 3b judge) and cover 11/11. They ran at 239e3dd, ports 8594/8595, $0.
`measured.json` now certifies Architect 0.64.1 only. Its 36 bounds are identical to 0.63.0's. Each quoted rate is within 3% of
0.63.0's (for example wheat 9x9 18 a day, 27x27 180; carrots 27x27 484; potatoes 27x27 492; beetroot 27x27 282). M1's certification rule is
unchanged. The tables below are the 0.63.0 measurement, kept for its per-run detail; `artifacts/m2/` holds the 0.64.1 evidence.

Measured on 0.63.0, 2026-11-13, on candidate 3 (program sha `c406df05…`, moduleSha `dc7b1751…`). The steps ran at heads 834480e to e46d693 (the field steps last, under the amended 3b judge); the
program and module.json were unchanged throughout, since every step stamps their sha256. The runs used Minecraft 26.3, Architect 0.63.0 and
kit/checker 0.58.0, on ports 8594/8595, at $0. `measured.json` was written by `tools/module-catalog.mjs record` (result pass).
**Every bar passes.**

| Step | Result | Time |
|---|---|---|
| `field --crop wheat` | PASS 62/62 (5 runs; amended 3b judge) | 45 min |
| `field --crop carrots` | PASS 62/62 (5 runs; amended 3b judge) | 44 min |
| `field --crop potatoes` | PASS 62/62 (5 runs; amended 3b judge) | 44 min |
| `field --crop beetroots` | PASS 62/62 (5 runs; amended 3b judge) | 33 min |
| `skin --bible rustic`, `fortress`, `desert` | PASS 53/53 each | 6-12 min |
| `survival` | PASS 63/63 | 6 min |
| `bounds` | PASS 111/111 (108 quotes) | 8 min |
| `border` | PASS 23/23 | 3 min |
| `cover` | PASS 11/11 | - |
| `module-catalog offline` / `ingame` (M1's checker over 36 branches x 3 Styles) | PASS 115/115, 116/116 | 9 min |

- **1. Hydration.** 1a: every farmland cell is at 7 after 24,000 ticks on every farm. That is 2,718 cells per crop on the 9 rustic
  farms, plus every skin and border farm. 1b: 7 at every one of the 15 harvests per crop. Recorded: 11-21 cells per 2,718 read 6 at PLACED
  (the lot is placed before the fluids stage); all were at 7 by 24,000 ticks, the bar (most by the first 4,000-tick sample).
- **2. Light.** On every farm, every open floor+1 cell has block light ≥ 1. The lowest is 6, and 78-100% of crop cells are at 9 or more.
  All three Styles' lamp role is the fallback lantern.
- **3. Output.** Every run sits inside its band. The protocol rate, which the offer quotes, is the mean of 5 runs times the planted cells:

| Branch | Crops | Front | Runs (per crop a day) | Band | Mean (quoted) | Ripen basis, 3b (cells immature at 3T) |
|---|---|---|---|---|---|---|
| wheat 9x9 | 74 | north | 0.241-0.254 | 0.191-0.313 | 0.249 (18 a day) | 0.373 (30 per 80; 0 at 4T) |
| wheat 18x18 | 302 | south | 0.255-0.266 | 0.201-0.299 | 0.259 (78 a day) | 0.396 (32 per 80; 0 at 4T) |
| wheat 27x27 | 682 | south | 0.262-0.269 | 0.204-0.294 | 0.265 (180 a day) | 0.404 (32 per 80; 0 at 4T) |
| wheat 9x27 | 226 | north | 0.252-0.266 | 0.199-0.302 | 0.258 (58 a day) | 0.385 (31 per 80; 0 at 4T) |
| wheat 27x9 | 226 | north | 0.255-0.264 | 0.199-0.302 | 0.259 (58 a day) | 0.391 (31 per 80; 2 at 4T) |
| wheat 9x18 | 150 | west | 0.250-0.265 | 0.195-0.305 | 0.253 (37 a day) | 0.384 (31 per 80; 0 at 4T) |
| wheat 18x9 | 150 | east | 0.247-0.262 | 0.195-0.305 | 0.253 (38 a day) | 0.395 (32 per 80; 1 at 4T) |
| wheat 18x27 | 454 | east | 0.259-0.272 | 0.202-0.297 | 0.265 (120 a day) | 0.403 (32 per 80; 1 at 4T) |
| wheat 27x18 | 454 | west | 0.261-0.264 | 0.202-0.297 | 0.262 (119 a day) | 0.401 (32 per 80; 2 at 4T) |
| carrots 9x9 | 74 | north | 0.621-0.733 | 0.508-0.847 | 0.670 (49 a day) | 0.994 (80 per 80; 0 at 4T) |
| carrots 18x18 | 302 | south | 0.669-0.729 | 0.534-0.809 | 0.700 (211 a day) | 1.061 (85 per 80; 0 at 4T) |
| carrots 27x27 | 682 | south | 0.695-0.724 | 0.541-0.799 | 0.708 (483 a day) | 1.089 (87 per 80; 0 at 4T) |
| carrots 9x27 | 226 | north | 0.678-0.714 | 0.530-0.813 | 0.694 (156 a day) | 1.068 (85 per 80; 0 at 4T) |
| carrots 27x9 | 226 | north | 0.677-0.708 | 0.530-0.813 | 0.691 (156 a day) | 1.036 (83 per 80; 0 at 4T) |
| carrots 9x18 | 150 | west | 0.664-0.710 | 0.523-0.821 | 0.687 (103 a day) | 1.021 (82 per 80; 0 at 4T) |
| carrots 18x9 | 150 | east | 0.676-0.715 | 0.523-0.821 | 0.691 (103 a day) | 1.062 (85 per 80; 0 at 4T) |
| carrots 18x27 | 454 | east | 0.707-0.727 | 0.537-0.802 | 0.714 (323 a day) | 1.071 (86 per 80; 0 at 4T) |
| carrots 27x18 | 454 | west | 0.711-0.746 | 0.537-0.802 | 0.722 (327 a day) | 1.097 (88 per 80; 4 at 4T) |
| potatoes 9x9 | 74 | north | 0.613-0.708 | 0.508-0.910 | 0.668 (49 a day) | 1.013 (81 per 80; 1 at 4T) |
| potatoes 18x18 | 302 | south | 0.678-0.710 | 0.530-0.871 | 0.692 (209 a day) | 1.061 (85 per 80; 2 at 4T) |
| potatoes 27x27 | 682 | south | 0.704-0.735 | 0.536-0.857 | 0.718 (489 a day) | 1.083 (87 per 80; 1 at 4T) |
| potatoes 9x27 | 226 | north | 0.674-0.726 | 0.526-0.873 | 0.697 (157 a day) | 1.051 (84 per 80; 0 at 4T) |
| potatoes 27x9 | 226 | north | 0.649-0.714 | 0.526-0.873 | 0.673 (152 a day) | 1.039 (83 per 80; 2 at 4T) |
| potatoes 9x18 | 150 | west | 0.652-0.700 | 0.520-0.886 | 0.677 (101 a day) | 1.017 (81 per 80; 1 at 4T) |
| potatoes 18x9 | 150 | east | 0.664-0.725 | 0.520-0.886 | 0.700 (105 a day) | 1.070 (86 per 80; 0 at 4T) |
| potatoes 18x27 | 454 | east | 0.679-0.741 | 0.534-0.862 | 0.703 (319 a day) | 1.074 (86 per 80; 2 at 4T) |
| potatoes 27x18 | 454 | west | 0.688-0.723 | 0.534-0.862 | 0.709 (321 a day) | 1.068 (85 per 80; 3 at 4T) |
| beetroots 9x9 | 74 | north | 0.380-0.404 | 0.309-0.525 | 0.391 (28 a day) | 0.552 (44 per 80; 5 at 4T) |
| beetroots 18x18 | 302 | south | 0.392-0.418 | 0.330-0.501 | 0.409 (123 a day) | 0.605 (48 per 80; 25 at 4T) |
| beetroots 27x27 | 682 | south | 0.404-0.421 | 0.337-0.491 | 0.416 (283 a day) | 0.616 (49 per 80; 50 at 4T) |
| beetroots 9x27 | 226 | north | 0.390-0.416 | 0.326-0.504 | 0.404 (91 a day) | 0.606 (48 per 80; 20 at 4T) |
| beetroots 27x9 | 226 | north | 0.388-0.405 | 0.326-0.504 | 0.396 (89 a day) | 0.577 (46 per 80; 24 at 4T) |
| beetroots 9x18 | 150 | west | 0.382-0.414 | 0.322-0.511 | 0.395 (59 a day) | 0.588 (47 per 80; 12 at 4T) |
| beetroots 18x9 | 150 | east | 0.381-0.406 | 0.322-0.511 | 0.396 (59 a day) | 0.599 (48 per 80; 6 at 4T) |
| beetroots 18x27 | 454 | east | 0.405-0.430 | 0.334-0.497 | 0.416 (189 a day) | 0.616 (49 per 80; 34 at 4T) |
| beetroots 27x18 | 454 | west | 0.396-0.424 | 0.334-0.497 | 0.409 (185 a day) | 0.605 (48 per 80; 27 at 4T) |

  - 3b: every branch clears 0.3 per crop a day on G3's basis. Wheat, the closest, is at 0.373-0.404 (30-32 per 80 crops; the probe's
    G3 gave 32.9), with each run's first planting followed through 3T.
  - 3c: every mean is at least 1.5 times the probe's unwatered rate.
- **4. Exclusion.** Across 27 farms (9 sizes x 3 Styles) plus the 2 border farms:
  - 4a: 0 mob sightings inside the fence over 12,000 ticks, with 6 zombies, villagers and cows per farm;
  - 4b: 0 cells changed;
  - 4c: every gate works: shut, a walking player stays out; open, in and out.
  - Recorded: spiders beside 9 farms, 0 inside, 0 trampled.
- **5. Restarts.** In each crop's run 1 and on the border farms:
  - 5a, a save/leave/open: 0 crop ages or moisture values differ;
  - 5b, chunks unloaded for 2,000 ticks: 0 differ.
- **6 and 7. Survival** (9 farms covering all 9 sizes and all 4 crops):
  - 7a: each growth consumed exactly its QT quote (4,477, 2,890 and 4,481 items).
  - 6a: the player's records survive a restart.
  - 6b: LC changed 0 with playerPlaced 2 (the cobblestone and the torch); the parts are unchanged.
  - 6c: standing is 1 as built, and loses only the cobblestone's cell when worked.
  - 6d: undo removes, with no blockers.
  - 7b: credited = consumed less one seed per harvested cell. Each player replant's planting item came back once as cargo, along with the
    cobblestone and the torch. Nothing was given twice after a restart.
- **The envelope.**
  - Fronts: north 21, south 16, west 14, east 14.
  - Every crop ran on both street orientations.
  - Every size pair has a farm crossing a chunk corner. The 9x9 and 27x9 cases are from `border`.
- **For M3** (`measured.json`):
  - `rates` `[{params, perDay, item}]`: the quoted protocol rate, whole, over the planted cells.
  - `bounds` `[{params, bom}]`: the whole growth's quoted bill (the lot, the IRR shells, and the growth's street and lane on a V town's
    first frontage slot), per item the maximum over the 3 Styles. A placement whose lanes cost more quotes over it, so M3 holds the build
    and asks again.
