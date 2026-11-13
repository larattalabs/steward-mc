// The survival bound per measured Style, with the share of it each palette role writes (docs/MEASURE-M2.md "The bound per Style",
// Noah's "a" of 2026-11-13: a town in an unmeasured Style is quoted in its own blocks, so Steward re-expresses the bound through the town's
// bible). Pure over its inputs: the bounds step's quotes (artifacts/m2/bounds.json `quotes`), the pinned kit and the module's program.
//
// For each quote (one params x one Style) it writes {params, style, bom, roleParts: [{role, block, item, count}]}:
// - `bom` is the quote's whole bill as measured (every part: the lot, IRR's shells, the street and its lane);
// - `roleParts` is the share of the LOT's bill that comes from a palette role, counted over the program's own template in that Style (the
//   lot the quote billed). Role keys: `foundation`, `light` (the lamp after the program's fallback rule), `path`, and the wood set's
//   `wood.fence` / `wood.fence_gate` (the kit's wood: roles.wood, else the first wooden role, else oak).
// The street, its lane and IRR's shells are left as measured: none of their blocks is a role's (gravel, an oak fence post and a lantern on
// every measured Style).
//
// The counts are evidence, not inference: an item only roles give must be quoted in the lot exactly as often as the template writes it, and
// an item roles share with role-free cells (the path's dirt_path is billed as dirt, the same item as the dirt layer and the farmland) must be
// quoted at least that often. Anything else is a problem and the record is refused.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

/** The item a block is billed as in survival, where it is not the block's own (Architect's quote: a dirt path is dug as dirt). */
export const SURVIVAL_ITEM = { 'minecraft:dirt_path': 'minecraft:dirt', 'minecraft:farmland': 'minecraft:dirt' };
export const itemOf = (block) => SURVIVAL_ITEM[block] ?? block;
const SEEDS = { wheat: 'minecraft:wheat_seeds', carrots: 'minecraft:carrot', potatoes: 'minecraft:potato', beetroots: 'minecraft:beetroot_seeds' };

/** The role blocks of a Style for this program: role key -> block. */
export function roleBlocks(p, program) {
  const pc = program.paletteCheck(p.roles);
  return { foundation: p.roles.foundation, light: pc.lamp, path: p.path, 'wood.fence': p.fence, 'wood.fence_gate': p.fenceGate };
}

/**
 * Per-Style bounds from the bounds step's quotes. `kit` is the pinned kit's directory, `source` the program's text. Returns
 * {bounds: [...], problems: [...]}.
 */
export async function styleBounds({ quotes, kit, source, id = 'crop_farm' }) {
  const lib = pathToFileURL(path.join(kit, 'lib')).href;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'steward-style-bounds-'));
  const problems = [];
  const bounds = [];
  try {
    fs.writeFileSync(path.join(dir, `${id}.mjs`), source.replaceAll("'../lib/", `'${lib}/`));
    const program = await import(pathToFileURL(path.join(dir, `${id}.mjs`)).href);
    const { PALETTES } = await import(`${lib}/kit.mjs`);
    for (const q of quotes) {
      const where = `${q.params.width}x${q.params.depth} ${q.params.crop} ${q.bible}`;
      const p = PALETTES[q.bible];
      if (!p) { problems.push(`${where}: no built-in Style ${q.bible}`); continue; }
      const roles = roleBlocks(p, program);
      const byBlock = new Map();
      for (const [role, block] of Object.entries(roles)) {
        if (byBlock.has(block)) { problems.push(`${where}: roles ${byBlock.get(block)} and ${role} are both ${block}, so their counts can't be told apart`); }
        byBlock.set(block, role);
      }
      // the template in this Style (no reserved cells: those are field cells, never a role's)
      const bp = program.default({ palette: p, width: q.params.width, depth: q.params.depth, crop: q.params.crop });
      const count = {};
      const freeItems = new Set();
      for (const c of bp.cells.values()) {
        const name = c.state?.name ?? c.block ?? String(c);
        const role = byBlock.get(name);
        if (role) count[role] = (count[role] ?? 0) + 1;
        else freeItems.add(/^minecraft:(wheat|carrots|potatoes|beetroots)$/.test(name) ? SEEDS[q.params.crop] : itemOf(name));
      }
      const lots = Object.entries(q.bomParts ?? {}).filter(([k]) => k.startsWith('lot:'));
      if (lots.length !== 1) { problems.push(`${where}: ${lots.length} lot parts in the quote (1 expected)`); continue; }
      const lot = lots[0][1];
      const roleParts = Object.entries(roles).filter(([r]) => count[r]).map(([role, block]) => ({ role, block, item: itemOf(block), count: count[role] }));
      const perItem = {};
      for (const r of roleParts) perItem[r.item] = (perItem[r.item] ?? 0) + r.count;
      for (const [item, n] of Object.entries(perItem)) {
        const quoted = lot[item] ?? 0;
        if (freeItems.has(item) ? quoted < n : quoted !== n) {
          problems.push(`${where}: the template's roles write ${n} ${item}, the lot was quoted ${quoted}${freeItems.has(item) ? ' (fewer)' : ''}`);
        }
        if ((q.bom[item] ?? 0) < n) problems.push(`${where}: the bill has ${q.bom[item] ?? 0} ${item}, under the roles' ${n}`);
      }
      bounds.push({ params: q.params, style: q.bible, bom: Object.fromEntries(Object.entries(q.bom).sort()), roleParts });
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
  bounds.sort((a, b) => (JSON.stringify(a.params) + a.style).localeCompare(JSON.stringify(b.params) + b.style));
  return { bounds, problems };
}
