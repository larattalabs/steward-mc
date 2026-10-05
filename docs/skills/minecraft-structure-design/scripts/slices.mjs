#!/usr/bin/env node
// node scripts/slices.mjs <file.nbt> [--kit <kit dir>] [--y 3 | --y 0-6] [--box x0,z0,x1,z1] [--legend]
// Prints a structure template as layered ASCII: one block per character, one grid per y layer (feet row = groundY).
// North (-z) is the TOP of each grid, west (-x) the LEFT, so it reads like a map. Coordinates are template-local
// (origin = minimum corner, +x east, +y up, +z south), the same as the kit's design coordinates.
// Glyphs: '.' air; letters are assigned per block id and listed in the legend (stairs, slabs, doors, trapdoors and
// other directional blocks carry an arrow for their facing in the legend line, e.g. S = oak_stairs[facing=north]).
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const args = process.argv.slice(2);
const flag = (n) => { const i = args.indexOf(n); return i < 0 ? null : args[i + 1]; };
const file = args.find((a, i) => !a.startsWith('--') && !['--kit', '--y', '--box'].includes(args[i - 1]));
if (!file) { console.error('usage: node scripts/slices.mjs <file.nbt> [--kit <dir>] [--y N|A-B] [--box x0,z0,x1,z1]'); process.exit(2); }
const kit = path.resolve(flag('--kit') ?? 'kit');
const { parse, plain } = await import(pathToFileURL(path.join(kit, 'lib/nbt.mjs')).href);
const s = plain(parse(fs.readFileSync(file)));
const [sx, sy, sz] = s.size;
const pal = s.palette.map((p) => ({ name: (p.id ?? p.Name).replace('minecraft:', ''), props: (p.properties ?? p.Properties ?? {}) }));
const grid = new Map();
for (const b of s.blocks) grid.set(`${b.pos[0]},${b.pos[1]},${b.pos[2]}`, b.state);

const FACING = { north: '^', south: 'v', east: '>', west: '<', up: 'u', down: 'd' };
const GLYPHS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#%&*+=@?!~';
const glyphOf = new Map(); const legend = [];
const key = (p) => p.name + JSON.stringify(Object.entries(p.props).filter(([k]) => !['waterlogged', 'powered', 'lit', 'open'].includes(k) || k === 'open').sort());
function glyph(i) {
  const p = pal[i];
  if (p.name === 'air' || p.name === 'cave_air' || p.name === 'void_air') return '.';
  const k = p.name; // one glyph per block id; facing shows in the legend only when it varies
  if (!glyphOf.has(k)) { glyphOf.set(k, GLYPHS[glyphOf.size] ?? '?'); legend.push([glyphOf.get(k), k]); }
  return glyphOf.get(k);
}
const yr = flag('--y'); let [y0, y1] = [0, sy - 1];
if (yr) { const m = /^(\d+)(?:-(\d+))?$/.exec(yr); if (m) { y0 = +m[1]; y1 = m[2] ? +m[2] : y0; } }
let [x0, z0, x1, z1] = [0, 0, sx - 1, sz - 1];
if (flag('--box')) [x0, z0, x1, z1] = flag('--box').split(',').map(Number);
const hdr = '    ' + Array.from({ length: x1 - x0 + 1 }, (_, i) => ((x0 + i) % 10)).join('');
console.log(`# ${path.basename(file)}  size x=${sx} y=${sy} z=${sz}  (north = top, west = left; y 0 is the bottom row)`);
for (let y = y0; y <= y1; y++) {
  const rows = [];
  let any = false;
  for (let z = z0; z <= z1; z++) {
    let row = '';
    for (let x = x0; x <= x1; x++) { const st = grid.get(`${x},${y},${z}`); const g = st === undefined ? '.' : glyph(st); if (g !== '.') any = true; row += g; }
    rows.push(String(z).padStart(3) + ' ' + row);
  }
  console.log(`\n-- y=${y}${any ? '' : ' (empty)'} --`);
  if (any) { console.log(hdr); console.log(rows.join('\n')); }
}
console.log('\nlegend: ' + legend.map(([g, n]) => `${g}=${n}`).join('  '));
// directional detail for blocks whose facing matters, so a facing mistake is visible without a render
const dirs = new Map();
for (const b of s.blocks) {
  const p = pal[b.state]; const f = p.props.facing; if (!f) continue;
  if (!/stairs|door|trapdoor|ladder|torch|sign|banner|bed|lectern|furnace|smoker|chest|barrel|anvil|grindstone|bell|button|lever/.test(p.name)) continue;
  const k = `${p.name} facing=${f}${p.props.half ? ' half=' + p.props.half : ''}${p.props.shape && p.props.shape !== 'straight' ? ' shape=' + p.props.shape : ''}`;
  dirs.set(k, (dirs.get(k) ?? 0) + 1);
}
if (dirs.size) console.log('\ndirectional blocks (' + Object.entries(FACING).map(([k, v]) => `${v}=${k}`).join(' ') + '):\n' + [...dirs].sort().map(([k, n]) => `  ${n} x ${k}`).join('\n'));
