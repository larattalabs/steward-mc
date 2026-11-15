// Steward module catalog: `storage_hall@1`, chests that sort (docs/MODULES.md section 3.2, slice M4; the design and bars: docs/MEASURE-M4.md).
// Steward's own kit program, run by Architect as a given-program design at $0 (S-M5-1).
//
// This version builds the plain hall only (`slots = 0`: chests and an input chest, no redstone). The sorting branches (slots 4-16, M0's
// measured tile with its filter fill through FK's `bp.fillContainers`) wait for the filler item (MEASURE-M4.md "Order"); until then the
// program refuses any other `slots`.
//
// The lot: front to the south, x across, z from the back, y from the floor. The template is exactly W x H x D facing south with groundY 1:
// row 0 is a foundation layer under the whole lot (frame y -1), row 1 the floor (ground level), rows 2-5 the walls, row 6 the roof as the
// roof role's bottom slab (no mob spawns on a bottom slab).
//   z = 0            the back wall
//   z = 1            the bulk row: `bulk` double chests facing south, in columns x 1-2, 3-4, 5-6, 7-8, the lower row (2) first, then
//                    the upper row (3) on the lower chests (a chest opens under a chest: ChestBlock.isChestBlockedAt reads a conductor)
//   z = 2 .. D-3     the aisle
//   z = D-3          the input chest (the `item_in` port), single, just inside the door at x = gate + 1
//   z = D-2          the front wall, the door at x = gate = floor(W / 2)
//   z = D-1          the front row: path
// Lamps (the `light` role, or the plain lantern when it is under light 14) stand in the walls at row 4, on the wall cell below: they light
// the inside and, in the front wall, the front row. Glass panes in the side walls at row 3.
//
// Palette constraints (MODULES.md 2.2, module.json `palette`): the lamp as above (the one substitution, said); no gravity on floor (chests
// stand on it), foundation, wall and frame; the roof can't be one (the kit refuses a roof with no slab, and no gravity block has one). A bible that breaks them is refused (`palette: ...`).
import { Blueprint, PALETTES } from '../lib/kit.mjs';
import { BLOCKS } from '../lib/blocks.mjs';

export const id = 'storage_hall';

export const params = {
  slots: { type: 'int', min: 0, max: 16, default: 0, label: 'Sorting slots' },
  bulk: { type: 'int', min: 0, max: 8, default: 2, label: 'Double chests' },
};

const SLOTS = [0];
const BULK_MAX = 8;
const LAMP_MIN = 14;
const LAMP_FALLBACK = 'minecraft:lantern';
// the roof needs no rule: the kit refuses a roof block with no slab variant, and no gravity block has one
const NO_GRAVITY = ['floor', 'foundation', 'wall', 'frame'];
const NON_CONDUCTIVE = [];

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
  const lamp = roles.light && lightOf(roles.light) >= LAMP_MIN ? roles.light : LAMP_FALLBACK;
  return { lamp, fallback: lamp !== roles.light, broken };
}

/**
 * The hall's layout, pure: {W, D, H, gate, input: [x, y, z], bulk: [{lo: [x, y, z], hi: [x, y, z]}] (each double chest's two halves: `lo`
 * the lower-x cell), lamps: [[x, y, z]], panes: [[x, y, z]]}. Steward's lot spec and the measurement read it.
 */
export function layout(slots, bulk) {
  if (!SLOTS.includes(slots)) throw new Error(`storage_hall@1: slots must be one of ${SLOTS.join(', ')} (the sorting branches wait for the filler)`);
  if (!Number.isInteger(bulk) || bulk < 0 || bulk > BULK_MAX) throw new Error(`storage_hall@1: bulk must be 0..${BULK_MAX}`);
  const W = 11, D = 7, H = 7;
  const gate = Math.floor(W / 2);
  const chests = [];
  for (let i = 0; i < bulk; i++) {
    const col = i % 4, row = 2 + Math.floor(i / 4);
    chests.push({ lo: [1 + 2 * col, row, 1], hi: [2 + 2 * col, row, 1] });
  }
  const lamps = [[0, 4, 2], [W - 1, 4, 2], [gate - 2, 4, D - 2], [gate + 2, 4, D - 2]];
  const panes = [[0, 3, 3], [W - 1, 3, 3]];
  return { W, D, H, gate, input: [gate + 1, 2, D - 3], bulk: chests, lamps, panes };
}

export default function build({ palette = PALETTES.rustic, slots = 0, bulk = 2 } = {}) {
  const p = palette;
  const roles = p.roles ?? { floor: p.floor, foundation: p.foundation, wall: p.wall, frame: p.frame, roof: p.roofBlock, light: p.light };
  const pc = paletteCheck(roles);
  if (pc.broken.length) throw new Error(`palette: storage_hall@1 refuses this bible: ${pc.broken.join('; ')}`);
  const L = layout(slots, bulk);
  const { W, D, H, gate } = L;
  const size = [W, H, D];
  const bp = new Blueprint({
    id,
    name: 'Storage Hall',
    description: `A storage hall with ${bulk} double chest${bulk === 1 ? '' : 's'} and an input chest by the door${pc.fallback ? '; lit by plain lanterns: the style\'s lamp is too dim' : ''}.`,
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
  const panes = new Set(L.panes.map((c) => c.join(',')));
  bp.part('core', () => {
    // the containers: the bulk double chests (each half with its type, so they pair as placed) and the input chest
    for (const { lo, hi } of L.bulk) {
      // facing south, a double chest's `left` half is the one whose partner lies to its west (ChestBlock: left connects clockwise of facing)
      bp.set(...hi, 'minecraft:chest', { facing: 'south', type: 'left' });
      bp.set(...lo, 'minecraft:chest', { facing: 'south', type: 'right' });
    }
    bp.set(...L.input, 'minecraft:chest', { facing: 'west', type: 'single' });
  });
  bp.part('shell', () => {
    for (let x = 0; x < W; x++) for (let z = 0; z < D; z++) bp.set(x, 0, z, p.foundation);
    for (let x = 0; x < W; x++) for (let z = 0; z <= D - 2; z++) {
      const edge = x === 0 || x === W - 1 || z === 0 || z === D - 2;
      bp.set(x, 1, z, edge ? p.foundation : p.floor);
      if (!edge) continue;
      const corner = (x === 0 || x === W - 1) && (z === 0 || z === D - 2);
      for (let y = 2; y <= 5; y++) {
        if (x === gate && z === D - 2 && (y === 2 || y === 3)) continue; // the door
        const k = `${x},${y},${z}`;
        if (lamps.has(k)) bp.lantern(x, y, z, false, pc.lamp);
        else if (panes.has(k)) bp.set(x, y, z, p.pane);
        else bp.set(x, y, z, corner ? p.frame : p.wall);
      }
    }
    bp.door(gate, 2, D - 2, 'south');
    for (let x = 0; x < W; x++) bp.set(x, 1, D - 1, p.path);
    for (let x = 0; x < W; x++) for (let z = 0; z <= D - 2; z++) bp.set(x, 6, z, p.roofSlab, { type: 'bottom' });
  });
  bp.port('in', 'item_in', ...L.input, 'south');
  bp.spot('entrance', gate, D - 1, 180, { y: 2 });
  bp.spot('spawn', gate, D - 1, 180, { y: 2 });
  return bp;
}
