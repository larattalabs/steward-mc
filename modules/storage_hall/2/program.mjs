// Steward module catalog: `storage_hall@2`, chests that sort (docs/MODULES.md section 3.2; slice M4b; the design and bars: docs/MEASURE-M4.md
// section 12). Steward's own kit program, run by Architect as a given-program design at $0 (S-M5-1), with filled containers (FK) and
// module-provided fill items (FK-b).
//
// Two shapes, one program:
// - the plain hall (`slots = 0`): @1's 11 x 7 hall exactly (an input chest by the door, `bulk` double chests along the back wall);
// - the sorting hall (`slots` 4, 8, 12 or 16): M0's measured sorter tile (docs/PROBE-M0.md "ST", tools/module-probe.mjs buildHall) once per
//   slot along one hopper line, each filter hopper filled once by FK with 41 of its filter item (billed) and one module-provided
//   petrified oak slab in each of slots 1-4 (FK-b: unbilled, never refunded, never takeable), the line locked by a lever until the fill.
//
// The lot: front to the south, x across, z from the back, y from the floor. Template rows: 0 a foundation layer (frame y -1), 1 the floor
// (ground level), 2-5 the walls, 6 the roof as the roof role's bottom slab (no mob spawns on a bottom slab).
//
// The sorting hall (W = max(slots + 3, 11), D = 13; the line at z = 6, b = row 2):
//   z = 0          the back wall (roles)
//   z = 1 .. 5     the circuit zone (slot u at x = 1 + u): the tile's comparator, dust, repeater, torch and stones; every other cell of
//                  x 0 .. slots + 1 is fixed minecraft:stone or air (never a role), so no role block touches a mechanism (§12.2)
//   z = 6          the line: per slot a line hopper (row 4, facing east), the filter hopper F (row 3, facing north, into the comparator)
//                  and the output hopper D (row 2, facing south); the input chest on the first line hopper (row 5); the overflow chest at
//                  the line's end (x = slots + 1, row 4) with air over it, so it opens; the lever (the FK lock) at (1, 4, 5), on the
//                  fixed-stone west wall
//   z = 7          the slot chests (row 2, single)
//   z = 8 .. 9     the aisle
//   z = 10         the bulk row (facing north, either side of the door)
//   z = 11         the front wall, the door at x = gate = floor(W / 2)
//   z = 12         the front row: path
//
// Role provenance (`bp.roleCells`) and the palette rules are @1's (module.json `palette`): the lamp role at light 14 or more, else the
// plain lantern (said); no gravity on floor, foundation, wall and frame; wall and foundation full blocks. Every block the sorter's working
// depends on is fixed (stone, hoppers, comparators, dust, repeaters, torches, the lever), so no Style changes the circuit.
import { Blueprint, PALETTES } from '../lib/kit.mjs';
import { BLOCKS } from '../lib/blocks.mjs';

export const id = 'storage_hall';

/** The filter domain (§12.1): plain, 64-stacking items a settlement stores in bulk, as enum keys (the item is `minecraft:<key>`). */
export const FILTERABLE = [
  'cobblestone', 'cobbled_deepslate', 'stone', 'deepslate', 'dirt', 'sand', 'red_sand', 'gravel', 'andesite', 'diorite', 'granite', 'tuff',
  'calcite', 'netherrack', 'blackstone', 'basalt', 'end_stone', 'clay_ball', 'flint',
  'oak_log', 'spruce_log', 'birch_log', 'jungle_log', 'acacia_log', 'dark_oak_log', 'mangrove_log', 'cherry_log',
  'coal', 'charcoal', 'iron_ingot', 'gold_ingot', 'copper_ingot', 'raw_iron', 'raw_gold', 'raw_copper', 'redstone', 'lapis_lazuli', 'quartz',
  'diamond', 'emerald',
  'wheat', 'wheat_seeds', 'beetroot_seeds', 'sugar_cane', 'bone', 'string', 'gunpowder', 'rotten_flesh',
];
export const SLOT_MAX = 16;
const ITEM = { type: 'enum', options: ['none', ...FILTERABLE], default: 'none' };

export const params = {
  slots: { type: 'int', min: 0, max: 16, default: 0, label: 'Sorting slots' },
  bulk: { type: 'int', min: 0, max: 8, default: 2, label: 'Double chests' },
  ...Object.fromEntries(Array.from({ length: SLOT_MAX }, (_, i) => [`item${i + 1}`, { ...ITEM, label: `Slot ${i + 1}` }])),
};

const SLOTS = [0, 4, 8, 12, 16];
const BULK_MAX = 8;
const LAMP_MIN = 14;
const LAMP_FALLBACK = 'minecraft:lantern';
// the roof needs no rule: the kit refuses a roof block with no slab variant, and no gravity block has one
const NO_GRAVITY = ['floor', 'foundation', 'wall', 'frame'];
const NON_CONDUCTIVE = [];
// roles that carry a block needing a sturdy top: the wall (the standing lamps) and the foundation (the door)
const FULL_BLOCK = ['wall', 'foundation'];
/** F's fill (M0's: signal 2 at rest, 3 at the 42nd item; PROBE-M0 CS). */
export const FILTER_COUNT = 41;
export const FILLER = 'minecraft:petrified_oak_slab';
export const FILLERS = 4;
const STONE = 'minecraft:stone';

/** The block light a block gives in its default state (kit/lib/blocks.mjs `L`). */
function lightOf(block) {
  const b = BLOCKS[block];
  if (!b || b.L === undefined) return 0;
  if (typeof b.L === 'number') return b.L;
  const key = b.L.by.map((p) => b.defaults[p]).join(',');
  return b.L.v[key] ?? 0;
}

/** The lamp to use and every constraint a role breaks. Pure. */
export function paletteCheck(roles) {
  const broken = [];
  for (const r of NO_GRAVITY) if (roles[r] && BLOCKS[roles[r]]?.g) broken.push(`${r} '${roles[r]}' is a gravity block`);
  for (const r of NON_CONDUCTIVE) if (roles[r] && BLOCKS[roles[r]]?.R) broken.push(`${r} '${roles[r]}' conducts redstone`);
  for (const r of FULL_BLOCK) if (roles[r] && BLOCKS[roles[r]] && BLOCKS[roles[r]].collision !== 'full') broken.push(`${r} '${roles[r]}' is not a full block`);
  const lamp = roles.light && lightOf(roles.light) >= LAMP_MIN ? roles.light : LAMP_FALLBACK;
  return { lamp, fallback: lamp !== roles.light, broken };
}

/** The block each role key writes in this palette (`pc`: paletteCheck's result, for the lamp). Pure; tools/lib/style-bounds.mjs reads it. */
export function roleBlocks(p, pc = paletteCheck(p.roles ?? {})) {
  return { foundation: p.foundation, floor: p.floor, wall: p.wall, frame: p.frame, light: pc.lamp, path: p.path, 'roof.slab': p.roofSlab, 'wood.door': p.door };
}

/**
 * The filter items of these params, in slot order (`minecraft:` ids), or a throw naming what breaks the dependent rule (§12.1): items 1 to
 * `slots` are distinct domain keys, every later one `none`. Pure; Steward's StorageHallLot.items is the same rule.
 */
export function filterItems(slots, values = {}) {
  const out = [];
  for (let i = 1; i <= SLOT_MAX; i++) {
    const v = values[`item${i}`] ?? 'none';
    if (i <= slots) {
      if (v === 'none' || !FILTERABLE.includes(v)) throw new Error(`storage_hall@2: item${i} must be a filter item (slot ${i} of ${slots}), not '${v}'`);
      const at = out.indexOf(`minecraft:${v}`);
      if (at >= 0) throw new Error(`storage_hall@2: item${i} repeats item${at + 1} ('${v}')`);
      out.push(`minecraft:${v}`);
    } else if (v !== 'none') throw new Error(`storage_hall@2: item${i} must be none (the hall has ${slots} slots)`);
  }
  return out;
}

/**
 * The hall's layout, pure. Both shapes: {W, D, H, gate, input, bulk: [{lo, hi}] (a double chest's halves; `lo` the lower-x cell), lamps}.
 * The sorting hall adds: overflow, lever, slots: [{line, F, D, chest}], tile: [[x, y, z, block, props]] (the circuit's blocks), stone: [[x, y,
 * z]] (fixed stone), keepAir: Set of 'x,y,z' (cells left open), zone: {x0, x1, z0, z1} (the fixed-core region). Cells are [x, y, z], y the
 * template row. Steward's StorageHallLot and the measurement read it.
 */
export function layout(slots, bulk) {
  if (!SLOTS.includes(slots)) throw new Error(`storage_hall@2: slots must be one of ${SLOTS.join(', ')}`);
  if (!Number.isInteger(bulk) || bulk < 0 || bulk > BULK_MAX) throw new Error(`storage_hall@2: bulk must be 0..${BULK_MAX}`);
  if (slots === 0) {
    const W = 11, D = 7, H = 7;
    const gate = Math.floor(W / 2);
    const chests = [];
    for (let i = 0; i < bulk; i++) {
      const col = i % 4, row = 2 + Math.floor(i / 4);
      chests.push({ lo: [1 + 2 * col, row, 1], hi: [2 + 2 * col, row, 1], facing: 'south' });
    }
    const lamps = [[0, 4, 2], [W - 1, 4, 2], [gate - 2, 4, D - 2], [gate + 2, 4, D - 2]];
    return { W, D, H, gate, input: [gate + 1, 2, D - 3], bulk: chests, lamps };
  }
  const s = slots, W = Math.max(s + 3, 11), D = 13, H = 7, L = 6, b = 2;
  const gate = Math.floor(W / 2);
  const tile = [];
  const stone = [];
  const keepAir = new Set();
  const slotCells = [];
  for (let u = 0; u < s; u++) {
    const x = 1 + u;
    // M0's tile (module-probe.mjs buildHall), the probe's (y, v) at (row b + y, z L + v)
    tile.push([x, b + 2, L, 'minecraft:hopper', { facing: 'east' }]);
    tile.push([x, b + 1, L, 'minecraft:hopper', { facing: 'north' }]);
    tile.push([x, b, L, 'minecraft:hopper', { facing: 'south' }]);
    tile.push([x, b, L + 1, 'minecraft:chest', { facing: 'south', type: 'single' }]);
    tile.push([x, b + 1, L - 1, 'minecraft:comparator', { facing: 'south' }]);
    tile.push([x, b + 1, L - 2, 'minecraft:redstone_wire', {}]);
    tile.push([x, b + 1, L - 3, 'minecraft:redstone_wire', {}]);
    tile.push([x, b, L - 4, 'minecraft:redstone_wire', {}]);
    tile.push([x, b - 1, L - 3, 'minecraft:repeater', { facing: 'north', delay: '1' }]);
    tile.push([x, b - 1, L - 1, 'minecraft:redstone_wall_torch', { facing: 'south' }]);
    for (const [y, z] of [[b, L - 1], [b, L - 2], [b, L - 3], [b - 1, L - 4], [b - 2, L - 3], [b - 1, L - 2]]) tile.push([x, y, z, STONE, {}]);
    slotCells.push({ line: [x, b + 2, L], F: [x, b + 1, L], D: [x, b, L], chest: [x, b, L + 1] });
    // cells the tile needs open (as M0 measured): over the low dust (its step-down link) and where the low dust points
    keepAir.add(`${x},${b + 1},${L - 4}`);
    keepAir.add(`${x},${b},${L - 5}`);
  }
  const overflow = [s + 1, b + 2, L];
  keepAir.add(`${s + 1},${b + 3},${L}`); // over the overflow chest: a conductor there would keep it shut
  const input = [1, b + 3, L];
  const lever = [1, b + 2, L - 1];
  // the fixed core: x 0 .. s + 1, z 1 .. 6, rows 0 .. 5 (the roof above): every cell the tile, the chests and the lever don't use and that
  // isn't kept open is stone
  const used = new Set([...tile.map((c) => c.slice(0, 3).join(',')), overflow.join(','), input.join(','), lever.join(',')]);
  for (let x = 0; x <= s + 1; x++) for (let z = 1; z <= L; z++) for (let y = 0; y <= 5; y++) {
    const k = `${x},${y},${z}`;
    if (used.has(k) || keepAir.has(k)) continue;
    // the line's row 5 over the first hopper is the input chest; over the others, stone closes the zone
    stone.push([x, y, z]);
  }
  // bulk at z 10, facing north: left of the door x 1 .. gate - 1, right x gate + 1 .. W - 2, in pairs; the lower row first
  const pairsRow = [];
  for (let x = 1; x + 1 <= gate - 1; x += 2) pairsRow.push(x);
  for (let x = gate + 1; x + 1 <= W - 2; x += 2) pairsRow.push(x);
  const chests = [];
  for (let i = 0; i < bulk; i++) {
    const col = i % pairsRow.length, row = 2 + Math.floor(i / pairsRow.length);
    const x = pairsRow[col];
    chests.push({ lo: [x, row, 10], hi: [x + 1, row, 10], facing: 'north' });
  }
  const lamps = [[0, 4, 8], [W - 1, 4, 2], [W - 1, 4, 8], [gate - 2, 4, D - 2], [gate + 2, 4, D - 2]];
  return { W, D, H, gate, input, overflow, lever, slots: slotCells, tile, stone, keepAir, zone: { x0: 0, x1: s + 1, z0: 1, z1: L }, bulk: chests, lamps };
}

export default function build({ palette = PALETTES.rustic, slots = 0, bulk = 2, ...items } = {}) {
  const p = palette;
  const roles = p.roles ?? { floor: p.floor, foundation: p.foundation, wall: p.wall, frame: p.frame, roof: p.roofBlock, light: p.light };
  const pc = paletteCheck(roles);
  if (pc.broken.length) throw new Error(`palette: storage_hall@2 refuses this bible: ${pc.broken.join('; ')}`);
  const filters = filterItems(slots, items);
  const L = layout(slots, bulk);
  const { W, D, H, gate } = L;
  const size = [W, H, D];
  const sorting = slots > 0;
  const bp = new Blueprint({
    id,
    name: 'Storage Hall',
    description: sorting
      ? `A sorting hall: ${slots} slots (${filters.map((f) => f.replace('minecraft:', '').replaceAll('_', ' ')).join(', ')}), an overflow chest, ${bulk} double chest${bulk === 1 ? '' : 's'}${pc.fallback ? '; lit by plain lanterns: the style\'s lamp is too dim' : ''}.`
      : `A storage hall with ${bulk} double chest${bulk === 1 ? '' : 's'} and an input chest by the door${pc.fallback ? '; lit by plain lanterns: the style\'s lamp is too dim' : ''}.`,
    type: 'storage_hall',
    profile: ['door', 'no_floating'],
    tags: ['module', 'storage'],
    size,
    groundY: 1,
    front: 'south',
    palette: p,
    approach: false,
  });
  const lamps = new Set(L.lamps.map((c) => c.join(',')));
  const roleCells = new Map();
  const put = (role, x, y, z, block, props) => { bp.set(x, y, z, block, props); roleCells.set(`${x},${y},${z}`, role); };
  const inZone = (x, y, z) => sorting && x >= L.zone.x0 && x <= L.zone.x1 && z >= L.zone.z0 && z <= L.zone.z1 && y <= 5;
  bp.part('core', () => {
    for (const { lo, hi, facing } of L.bulk) {
      // a double chest's `left` half is the one whose partner lies clockwise of its facing (ChestBlock.getConnectedDirection): facing south,
      // its partner is to the west (the higher-x cell is left); facing north, to the east (the lower-x cell is left)
      const [left, right] = facing === 'south' ? [hi, lo] : [lo, hi];
      bp.set(...left, 'minecraft:chest', { facing, type: 'left' });
      bp.set(...right, 'minecraft:chest', { facing, type: 'right' });
    }
    if (!sorting) {
      bp.set(...L.input, 'minecraft:chest', { facing: 'west', type: 'single' });
      return;
    }
    for (const [x, y, z, block, props] of L.tile) bp.set(x, y, z, block, props);
    for (const [x, y, z] of L.stone) bp.set(x, y, z, STONE);
    bp.set(...L.input, 'minecraft:chest', { facing: 'south', type: 'single' });
    bp.set(...L.overflow, 'minecraft:chest', { facing: 'south', type: 'single' });
    // the lock's template state is its final, unlocked one (FK); FK writes it powered while built
    bp.set(...L.lever, 'minecraft:lever', { face: 'wall', facing: 'east', powered: 'false' });
  });
  bp.part('shell', () => {
    for (let x = 0; x < W; x++) for (let z = 0; z < D; z++) if (!inZone(x, 0, z)) put('foundation', x, 0, z, p.foundation);
    for (let x = 0; x < W; x++) for (let z = 0; z <= D - 2; z++) {
      const edge = x === 0 || x === W - 1 || z === 0 || z === D - 2;
      if (!inZone(x, 1, z)) {
        if (edge) put('foundation', x, 1, z, p.foundation);
        else put('floor', x, 1, z, p.floor);
      }
      if (!edge) continue;
      const corner = (x === 0 || x === W - 1) && (z === 0 || z === D - 2);
      for (let y = 2; y <= 5; y++) {
        if (inZone(x, y, z)) continue; // the fixed core's own wall cells (stone)
        if (x === gate && z === D - 2 && (y === 2 || y === 3)) continue; // the door
        const k = `${x},${y},${z}`;
        if (lamps.has(k)) { bp.lantern(x, y, z, false, pc.lamp); roleCells.set(k, 'light'); }
        else if (corner) put('frame', x, y, z, p.frame);
        else put('wall', x, y, z, p.wall);
      }
    }
    bp.door(gate, 2, D - 2, 'south');
    roleCells.set(`${gate},2,${D - 2}`, 'wood.door');
    roleCells.set(`${gate},3,${D - 2}`, 'wood.door');
    for (let x = 0; x < W; x++) put('path', x, 1, D - 1, p.path);
    for (let x = 0; x < W; x++) for (let z = 0; z <= D - 2; z++) put('roof.slab', x, 6, z, p.roofSlab, { type: 'bottom' });
  });
  if (sorting) {
    // the tile's air mask (§12.7 S7): no write may reach a cell M0 measured open
    for (const k of L.keepAir) {
      const [x, y, z] = k.split(',').map(Number);
      if (bp.cells.has(`${x + bp.ox},${y + bp.oy},${z + bp.oz}`)) throw new Error(`storage_hall@2: a write reached the tile's open cell ${k}`);
    }
    // FK: each filter hopper filled once after the core stands: 41 of its item (billed), a provided petrified slab in each of slots 1-4 (FK-b)
    bp.fillContainers({
      containers: L.slots.map((c, i) => ({
        at: c.F,
        items: [{ slot: 0, id: filters[i], count: FILTER_COUNT }, ...Array.from({ length: FILLERS }, (_, k) => ({ slot: k + 1, id: FILLER, count: 1, provided: true }))],
      })),
      locks: [{ at: L.lever, locked: { powered: 'true' } }],
    });
    bp.port('overflow', 'item_out', ...L.overflow, 'south');
  }
  bp.port('in', 'item_in', ...L.input, 'south');
  bp.spot('entrance', gate, D - 1, 180, { y: 2 });
  bp.spot('spawn', gate, D - 1, 180, { y: 2 });
  bp.roleCells = roleCells;
  return bp;
}
