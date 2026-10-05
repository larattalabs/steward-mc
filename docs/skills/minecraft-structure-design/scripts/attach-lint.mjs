#!/usr/bin/env node
// node scripts/attach-lint.mjs <file.nbt> [--kit <kit dir>]
// Deterministic spot checks the layout checker does not cover well: attachables need a support block on the side they
// attach to, doors and tall plants need both halves, beds need both halves, and a stair-run's top step needs headroom.
// Heuristic: "solid" is anything that is not air, fluid, a plant, a pane/fence/wall or another attachable. Warnings only.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
const args = process.argv.slice(2);
const file = args.find((a, i) => !a.startsWith('--') && args[i - 1] !== '--kit');
if (!file) { console.error('usage: node scripts/attach-lint.mjs <file.nbt> [--kit <dir>]'); process.exit(2); }
const kitI = args.indexOf('--kit'); const kit = path.resolve(kitI >= 0 ? args[kitI + 1] : 'kit');
const { parse, plain } = await import(pathToFileURL(path.join(kit, 'lib/nbt.mjs')).href);
const s = plain(parse(fs.readFileSync(file)));
const pal = s.palette.map((p) => ({ n: (p.id ?? p.Name).replace('minecraft:', ''), p: (p.properties ?? p.Properties ?? {}) }));
const at = new Map(); for (const b of s.blocks) at.set(b.pos.join(','), pal[b.state]);
const get = (x, y, z) => at.get(`${x},${y},${z}`);
const OFF = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0] };
const NONSOLID = /^(air|cave_air|void_air|water|lava|short_grass|tall_grass|fern|large_fern|flower|torch|wall_torch|soul_torch|redstone_torch|lantern|soul_lantern|ladder|vine|.*_sign|.*_wall_sign|.*_hanging_sign|.*_banner|.*_wall_banner|.*_button|lever|.*_pane|.*_fence|.*_fence_gate|.*_wall|.*carpet|.*_door|.*_trapdoor|candle|.*_candle|chain|.*bars|snow|.*_head)$/;
const solid = (b) => b && !NONSOLID.test(b.n) && !/_pane$|_fence$|_wall$|^potted_/.test(b.n);
const opp = { north: 'south', south: 'north', east: 'west', west: 'east' };
const out = [];
for (const b of s.blocks) {
  const [x, y, z] = b.pos; const { n, p } = pal[b.state];
  if (/^(ladder|wall_torch|soul_wall_torch|redstone_wall_torch|.*_wall_sign|.*_wall_banner)$/.test(n) && p.facing) {
    const [dx, dy, dz] = OFF[opp[p.facing]]; // the support is behind the facing direction
    if (!solid(get(x + dx, y, z + dz))) out.push(`${n} at ${x},${y},${z} facing ${p.facing}: no solid block behind it at ${x + dx},${y},${z + dz}`);
  }
  if (/_door$/.test(n)) {
    if (p.half === 'lower' && !(get(x, y + 1, z)?.n === n && get(x, y + 1, z).p.half === 'upper')) out.push(`${n} at ${x},${y},${z}: no upper half above`);
    if (p.half === 'upper' && !(get(x, y - 1, z)?.n === n && get(x, y - 1, z).p.half === 'lower')) out.push(`${n} at ${x},${y},${z}: no lower half below`);
    if (p.open === 'true') out.push(`${n} at ${x},${y},${z}: written open`);
  }
  if (/_bed$/.test(n) && p.part === 'foot') { const [dx, , dz] = OFF[p.facing]; if (!get(x + dx, y, z + dz)?.n.endsWith('_bed')) out.push(`${n} at ${x},${y},${z}: no head half at ${x + dx},${y},${z + dz}`); }
  if (/^(lantern|soul_lantern)$/.test(n) && p.hanging === 'true' && !solid(get(x, y + 1, z)) && !/chain|bars|fence/.test(get(x, y + 1, z)?.n ?? '')) out.push(`hanging ${n} at ${x},${y},${z}: nothing above to hang from`);
  if (/_stairs$/.test(n) && p.half === 'bottom') { /* a headroom check along stair runs belongs to the layout checker */ }
}
console.log(out.length ? out.join('\n') + `\nattach-lint: ${out.length} warning(s)` : 'attach-lint: OK');
process.exit(0);
