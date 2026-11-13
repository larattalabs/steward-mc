// Steward module catalog, FIXTURE module `fixture_plot@1` (docs/MODULES.md section 2.2, slice M1). Test data only: it is never offered to a
// player (module.json `fixture: true`); the real crop farm is `crop_farm@1` (slice M2). It exercises the catalog's shape: a functional core
// written in its settled state, a shell from the bible's roles, the palette constraints, the lot's reserved cells, and discrete branches.
//
//   The lot is the field plus a one-cell border and a front row (W = side + 2, D = side + 3, as crop_farm@1's geometry):
//   z = 0           back:  path under a fence, a lamp on each corner post
//   z = 1..side     the field: farmland (moisture 7) and the crop at its ripe age (the core: fixed blocks, never a role)
//   z = side + 1    front: path under a fence with a gate in the middle, a lamp on each corner post
//   z = side + 2    the front row: path, a composter beside the gate (the core's output port)
//
// Palette constraints (MODULES.md 2.2), checked here and by Steward before it asks (module.json `palette`):
//   - the lamp role (`light`) must give block light 14 or more, else the plain lantern is used and said (the one substitution);
//   - the roles that touch the core (`path`, and `frame`, whose wood gives the fence) may not be gravity blocks;
//   - roles in the circuit plane may not conduct redstone (none in this fixture: it has no circuit).
//   A bible that breaks them is refused (the program throws `palette: ...`); apart from the lamp nothing is substituted.
import { Blueprint, PALETTES } from '../lib/kit.mjs';
import { BLOCKS } from '../lib/blocks.mjs';
import { frameToTemplate } from '../lib/reserved.mjs';

export const id = 'fixture_plot';

export const params = {
  side: { type: 'int', min: 5, max: 9, default: 5, label: 'Field side' },
  crop: { type: 'enum', options: ['wheat', 'beetroots'], default: 'wheat', label: 'Crop' },
};

const RIPE = { wheat: 7, beetroots: 3 };
const LAMP_MIN = 14;
const LAMP_FALLBACK = 'minecraft:lantern';
const NO_GRAVITY = ['path', 'frame'];
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

export default function build({ palette = PALETTES.rustic, side = 5, crop = 'wheat', reserved } = {}) {
  const p = palette;
  const roles = p.roles ?? { path: p.path, frame: p.frame, light: p.light };
  const pc = paletteCheck(roles);
  if (pc.broken.length) throw new Error(`palette: fixture_plot@1 refuses this bible: ${pc.broken.join('; ')}`);
  const W = side + 2;
  const D = side + 3;
  const size = [W, 3, D];
  const gate = Math.floor(W / 2);
  const bp = new Blueprint({
    id,
    name: 'Fixture Plot',
    description: `A fenced ${side}x${side} ${crop} plot (catalog fixture)${pc.fallback ? '; lit by plain lanterns: the style\'s lamp is too dim' : ''}.`,
    type: 'module_fixture',
    profile: ['no_floating'],
    tags: ['module', 'fixture'],
    size,
    groundY: 1,
    front: 'south',
    palette: p,
    approach: false,
  });
  const keep = new Set();
  if (reserved) {
    for (const [u, y, v] of reserved.cells) {
      const t = frameToTemplate(u, y, v, { size, groundY: 1, front: 'south' }, reserved);
      if (t) keep.add(t.join(','));
    }
  }
  const put = (x, y, z, block, props) => {
    if (!keep.has(`${x},${y},${z}`)) bp.set(x, y, z, block, props);
  };
  bp.part('core', () => {
    for (let x = 1; x <= side; x++) {
      for (let z = 1; z <= side; z++) {
        put(x, 0, z, 'minecraft:farmland', { moisture: '7' });
        put(x, 1, z, `minecraft:${crop}`, { age: String(RIPE[crop]) });
      }
    }
    put(gate - 1, 0, D - 1, p.path);
    put(gate - 1, 1, D - 1, 'minecraft:composter');
  });
  bp.part('shell', () => {
    for (let x = 0; x < W; x++) {
      for (let z = 0; z <= side + 1; z++) {
        const ring = x === 0 || x === W - 1 || z === 0 || z === side + 1;
        if (!ring) continue;
        put(x, 0, z, p.path);
        if (x === gate && z === side + 1) put(x, 1, z, p.fenceGate, { facing: 'south' });
        else put(x, 1, z, p.fence);
      }
    }
    for (let x = 0; x < W; x++) if (x !== gate - 1) put(x, 0, D - 1, p.path);
    for (const [x, z] of [[0, 0], [W - 1, 0], [0, side + 1], [W - 1, side + 1]]) put(x, 2, z, pc.lamp);
  });
  bp.spot('entrance', gate, D - 1, 180);
  bp.spot('spawn', gate, D - 1, 180);
  return bp;
}
