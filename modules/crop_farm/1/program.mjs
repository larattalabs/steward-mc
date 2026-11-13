// Steward module catalog: `crop_farm@1`, a watered, lit field (docs/MODULES.md section 3.1, slice M2). Steward's own kit program, run by
// Architect as a given-program design at $0 (S-M5-1) on a lot grown with `irrigate: true` (S-M5-2, IRR) and a `field` (S-M5-4, FC).
//
// The lot (IRR's frame, CONTRACT "Phase 6c slice IRR as built"): front to the south, u across, v from the back, y from the floor.
// W = width + 2, D = depth + 3; the template is exactly W x D facing south with groundY 1, so template (x, y + 1, z) = frame (u, y, v):
// row 0 is a dirt layer under the whole lot (frame y -1, where IRR's beds sit: left open), row 1 the floor (the farmland), row 2 the
// crops and the fence, row 3 the lamps.
//   v = 0            back: a fence on the foundation role, a lamp on each corner post
//   v = 1 .. D-3     the field (u = 1 .. W-2): farmland at y 0 written `moisture=7` (its settled state with water in reach; M0's H3) and the
//                    crop at age 0 over it (the core: fixed blocks, never a role). IRR puts one source in every 9x9 at its segment's centre
//                    (axisSources below is IRR's own rule); each source, its bed, its 4 farmland sides and the cell over it are the lot's
//                    irrigation cells (`reserved`) and are left open. Over each of the 4 sides stands a fence post (the well), one with a lamp
//                    on it: FC drops IRR's sides from a field, so the cell over a side holds no crop, and the lamp lights the field's middle.
//   v = D-2          front: a fence on the foundation role, the gate (on a path) at u = floor(W / 2), a lamp on each corner post and on each post beside the gate
//   v = D-1          the front row: path, a composter left of the gate and a chest right of it (the field's `item_out` port)
//
// Light (M0, docs/MODULES.md section 9): open-sky crops grow at night; the lamps are for mob safety (26.3: a monster spawns only at block
// light 0). Every open cell at floor + 1 in the lot gets block light >= 1 (measured: tools/module-measure.mjs).
//
// Palette constraints (MODULES.md 2.2, module.json `palette`): the lamp role (`light`) must give block light 14 or more, else the plain
// lantern is used and said (the one substitution); the roles touching the core (`path`, `foundation` under the fence, and `frame`, whose
// wood gives the fence) may not be gravity blocks. A bible that breaks them is refused (`palette: ...`).
import { Blueprint, PALETTES } from '../lib/kit.mjs';
import { BLOCKS } from '../lib/blocks.mjs';
import { frameToTemplate } from '../lib/reserved.mjs';

export const id = 'crop_farm';

export const params = {
  width: { type: 'int', min: 9, max: 27, default: 9, label: 'Field width' },
  depth: { type: 'int', min: 9, max: 27, default: 9, label: 'Field depth' },
  crop: { type: 'enum', options: ['wheat', 'carrots', 'potatoes', 'beetroots'], default: 'wheat', label: 'Crop' },
};

const SIDES = [9, 18, 27];
const CROPS = ['wheat', 'carrots', 'potatoes', 'beetroots'];
const LAMP_MIN = 14;
const LAMP_FALLBACK = 'minecraft:lantern';
const NO_GRAVITY = ['path', 'frame', 'foundation'];
const NON_CONDUCTIVE = [];
const SPAN = 9; // IRR: a source per 9x9 (vanilla's hydration reach 4)

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

/** IRR's sources along one side [a, b] (kit/lib/fluids/irrigate.mjs axisSources): ceil(L / 9) segments, each at its centre, ties low. */
export function axisSources(a, b) {
  const L = b - a + 1;
  const n = Math.ceil(L / SPAN);
  const out = [];
  for (let i = 0; i < n; i++) {
    const s0 = a + Math.floor((i * L) / n), s1 = a + Math.floor(((i + 1) * L) / n) - 1;
    out.push(s0 + Math.floor((s1 - s0) / 2));
  }
  return out;
}

/**
 * The farm's layout in the lot's frame, pure: {W, D, gate, sources: [[u, v]], posts: [[u, v]], lampPosts: [[u, v]], field: [[u, v]]
 * (the farmland cells: the field less the irrigation cells and the posts)}. Steward's lot spec reads `field` for FC's `field.cells`.
 */
export function layout(width, depth) {
  const W = width + 2, D = depth + 3;
  const sources = [];
  for (const v of axisSources(1, D - 3)) for (const u of axisSources(1, W - 2)) sources.push([u, v]);
  const skip = new Set();
  const posts = [];
  const lampPosts = [];
  for (const [u, v] of sources) {
    skip.add(`${u},${v}`);
    for (const [du, dv] of [[0, -1], [-1, 0], [1, 0], [0, 1]]) {
      skip.add(`${u + du},${v + dv}`);
      posts.push([u + du, v + dv]);
    }
    lampPosts.push([u, v - 1]);
  }
  const field = [];
  for (let v = 1; v <= D - 3; v++) for (let u = 1; u <= W - 2; u++) if (!skip.has(`${u},${v}`)) field.push([u, v]);
  return { W, D, gate: Math.floor(W / 2), sources, posts, lampPosts, field };
}

export default function build({ palette = PALETTES.rustic, width = 9, depth = 9, crop = 'wheat', reserved } = {}) {
  if (!SIDES.includes(width) || !SIDES.includes(depth)) throw new Error(`crop_farm@1: field sides must be one of ${SIDES.join(', ')}`);
  if (!CROPS.includes(crop)) throw new Error(`crop_farm@1: crop must be one of ${CROPS.join(', ')}`);
  const p = palette;
  const roles = p.roles ?? { path: p.path, frame: p.frame, light: p.light, foundation: p.foundation };
  const pc = paletteCheck(roles);
  if (pc.broken.length) throw new Error(`palette: crop_farm@1 refuses this bible: ${pc.broken.join('; ')}`);
  const L = layout(width, depth);
  const { W, D, gate } = L;
  const size = [W, 4, D];
  const t = { size, groundY: 1, front: 'south' };
  const bp = new Blueprint({
    id,
    name: 'Crop Farm',
    description: `A fenced, watered ${width}x${depth} field of ${crop}, lit at night, with a composter and a chest by the gate${pc.fallback ? '; lit by plain lanterns: the style\'s lamp is too dim' : ''}.`,
    type: 'crop_farm',
    profile: ['no_floating'],
    tags: ['module', 'farm'],
    size,
    groundY: 1,
    front: 'south',
    palette: p,
    approach: false,
  });
  // the lot's reserved cells (IRR's irrigation cells, in the lot's frame) and IRR's own grid: both left open
  const keep = new Set();
  if (reserved) {
    for (const [u, y, v] of reserved.cells) {
      const c = frameToTemplate(u, y, v, t, reserved);
      if (c) keep.add(c.join(','));
    }
  }
  for (const [u, v] of L.sources) {
    keep.add(`${u},0,${v}`); // the bed
    keep.add(`${u},1,${v}`); // the source
    keep.add(`${u},2,${v}`); // the cell over it
    for (const [du, dv] of [[0, -1], [-1, 0], [1, 0], [0, 1]]) keep.add(`${u + du},1,${v + dv}`); // its farmland sides
  }
  const put = (x, y, z, block, props) => {
    if (!keep.has(`${x},${y},${z}`)) bp.set(x, y, z, block, props);
  };
  bp.part('core', () => {
    for (let x = 0; x < W; x++) for (let z = 0; z < D; z++) put(x, 0, z, 'minecraft:dirt');
    for (const [u, v] of L.field) {
      put(u, 1, v, 'minecraft:farmland', { moisture: '7' });
      put(u, 2, v, `minecraft:${crop}`, { age: '0' });
    }
    put(gate - 1, 1, D - 1, p.path);
    put(gate - 1, 2, D - 1, 'minecraft:composter');
    put(gate + 1, 1, D - 1, p.path);
    put(gate + 1, 2, D - 1, 'minecraft:chest', { facing: 'south', type: 'single' });
  });
  bp.part('shell', () => {
    for (let x = 0; x < W; x++) {
      for (let z = 0; z <= D - 2; z++) {
        if (!(x === 0 || x === W - 1 || z === 0 || z === D - 2)) continue;
        put(x, 1, z, x === gate && z === D - 2 ? p.path : roles.foundation ?? p.path);
        if (x === gate && z === D - 2) put(x, 2, z, p.fenceGate, { facing: 'south' });
        else put(x, 2, z, p.fence);
      }
    }
    for (let x = 0; x < W; x++) if (x !== gate - 1 && x !== gate + 1) put(x, 1, D - 1, p.path);
    for (const [u, v] of L.posts) put(u, 2, v, p.fence);
    for (const [u, v] of L.lampPosts) put(u, 3, v, pc.lamp);
    for (const [x, z] of [[0, 0], [W - 1, 0], [0, D - 2], [W - 1, D - 2], [gate - 1, D - 2], [gate + 1, D - 2]]) put(x, 3, z, pc.lamp);
  });
  bp.spot('entrance', gate, D - 1, 180, { y: 2 });
  bp.spot('spawn', gate, D - 1, 180, { y: 2 });
  return bp;
}
