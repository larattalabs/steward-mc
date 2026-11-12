#!/usr/bin/env node
// Phase 5 slice M0 (docs/PROBE-M0.md): the vanilla rules the crop farm and the storage hall rely on, measured on the running 26.3 server.
// $0, no Claude: it launches its own client through tools/run-e2e-client.sh on 8592/8593 in a fresh flat creative world, drives it with
// vanilla commands through the DevBridge, writes fixtures/modules/probe-m0.json and prints one line per bar ("ok"/"FAIL").
//
//   node tools/module-probe.mjs                 every probe, in order (SE first: no sprinting until SE has passed)
//   node tools/module-probe.mjs --only H,CS     only these probes (SE is run first unless --assume-sprint)
//   node tools/module-probe.mjs --judge         re-score the committed results against the bars, no client
//   --attach            use a client already running on STEWARD_DEV_PORT (default 8593) instead of launching one
//   --assume-sprint     trust the committed SE result instead of running SE again (a subset rerun)
//   --keep-client       leave the client running at the end
//   --runs-g N / --runs-st N   fewer growth / sorter runs (a quick look; the committed result needs the full counts)
import { spawn, execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAIN_CHECKOUT = path.resolve(execFileSync('git', ['-C', ROOT, 'rev-parse', '--git-common-dir']).toString().trim(), '..');
const ARCH = process.env.ARCHITECT_CHECKOUT ?? path.resolve(MAIN_CHECKOUT, '..', 'architect-mc');
const GAME = path.join(ROOT, 'mod', 'run');
process.env.ARCHITECT_GAME_DIR = GAME;
const PORT = Number(process.env.STEWARD_DEV_PORT ?? 8593);
const OUT_JSON = process.argv.includes('--out') ? path.resolve(process.argv[process.argv.indexOf('--out') + 1]) : path.join(ROOT, 'fixtures', 'modules', 'probe-m0.json');
const ART = path.join(ROOT, 'artifacts', 'm0');
fs.mkdirSync(ART, { recursive: true });
fs.mkdirSync(path.dirname(OUT_JSON), { recursive: true });

const argv = process.argv.slice(2);
const flag = (f) => argv.includes(f);
const opt = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
const ONLY = opt('--only') ? opt('--only').split(',').map((s) => s.trim().toUpperCase()) : null;
const RUNS_G = Number(opt('--runs-g', 5));
const RUNS_ST = Number(opt('--runs-st', 20));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (m) => console.log(m);

// ------------------------------------------------------------------ the bars (docs/PROBE-M0.md), as pure judges over the raw results

const CROPS = {
  wheat: { block: 'wheat', max: 7, item: 'minecraft:wheat', plantIsItem: false },
  carrots: { block: 'carrots', max: 7, item: 'minecraft:carrot', plantIsItem: true },
  potatoes: { block: 'potatoes', max: 7, item: 'minecraft:potato', plantIsItem: true },
  beetroots: { block: 'beetroots', max: 3, item: 'minecraft:beetroot', plantIsItem: false },
};
const median = (a) => { const s = [...a].sort((x, y) => x - y); const n = s.length; return n ? (n % 2 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2) : null; };
const mean = (a) => (a.length ? a.reduce((x, y) => x + y, 0) / a.length : null);
const sd = (a) => { const m = mean(a); return a.length > 1 ? Math.sqrt(a.reduce((s, x) => s + (x - m) ** 2, 0) / (a.length - 1)) : 0; };
const csExpect = (n) => (n + 4 === 0 ? 0 : Math.floor(1 + (14 * (n + 4)) / 320));

const JUDGES = {
  SE(r) {
    const c = [];
    const g = r.growth;
    if (g) {
      const mr = mean(g.real), ms = mean(g.sprint);
      const se = Math.sqrt(sd(g.real) ** 2 / g.real.length + sd(g.sprint) ** 2 / g.sprint.length);
      c.push({ id: 'SE.growth', bar: 'sprinted mean stages/cell within ±20% and 3 SE of real time', value: { real: mr, sprint: ms, se, ticksReal: g.ticksReal, ticksSprint: g.ticksSprint }, pass: Math.abs(ms - mr) <= 0.2 * mr && Math.abs(ms - mr) <= 3 * se });
    }
    const h = r.hoppers;
    if (h) {
      const same = JSON.stringify(h.real.counts) === JSON.stringify(h.sprint.counts);
      const rr = h.real.items / h.real.ticks, rs = h.sprint.items / h.sprint.ticks;
      c.push({ id: 'SE.hoppers', bar: 'per-chest counts identical, items per game tick within 2%', value: { realPerTick: rr, sprintPerTick: rs, sameCounts: same }, pass: same && Math.abs(rs - rr) <= 0.02 * rr });
    }
    return c;
  },
  H(r) {
    const c = [];
    const runs = r.runs ?? [];
    c.push({ id: 'H1', bar: 'every one of 80 cells moisture=7 within 4,800 ticks, in each of 5 runs', value: runs.map((x) => ({ moistBy4800: x.moistBy4800, of: x.cells, lastTick: x.lastTick })), pass: runs.length >= 5 && runs.every((x) => x.moistBy4800 === x.cells && x.cells === 80) });
    c.push({ id: 'H2', bar: 'the distance-5 ring is moisture=0 on every cell at 4,800 ticks, every run', value: runs.map((x) => `${x.ringMoist}/${x.ringCells}`), pass: runs.length >= 5 && runs.every((x) => x.ringMoist === 0) });
    if (r.h3) c.push({ id: 'H3', bar: 'a moist plot with water stays 7 at every sample for 24,000 ticks; without water every cell < 7 by 24,000', value: r.h3, pass: r.h3.wetDrops === 0 && r.h3.dryStill7 === 0 });
    if (r.h4) c.push({ id: 'H4', bar: 'recorded', value: r.h4, pass: null });
    return c;
  },
  G(r) {
    const c = [];
    c.push({ id: 'G0', bar: 'age max+1 refused; max is 7/7/7/3', value: r.g0, pass: !!r.g0 && Object.entries(CROPS).every(([k, v]) => r.g0[k]?.refused === true && r.g0[k]?.maxSeen <= v.max) });
    for (const [k, v] of Object.entries(CROPS)) {
      const runs = (r.runs ?? []).map((x) => x[k]).filter(Boolean);
      const Tw = runs.map((x) => x.watered.T ?? 150000), Tu = runs.map((x) => x.unwatered.T ?? 150000);
      c.push({ id: `G1.${k}`, bar: 'every run reaches T within 150,000 ticks (band recorded)', value: { runs: runs.length, T: Tw, min: Math.min(...Tw), median: median(Tw), max: Math.max(...Tw) }, pass: runs.length >= 5 && runs.every((x) => x.watered.T !== null) });
      c.push({ id: `G2.${k}`, bar: 'unwatered median T >= 1.5x watered median T', value: { watered: median(Tw), unwatered: median(Tu), ratio: median(Tu) / median(Tw) }, pass: runs.length >= 5 && median(Tu) >= 1.5 * median(Tw) });
      const drops = runs.reduce((s, x) => s + (x.watered.harvest?.items?.[v.item] ?? 0), 0), broken = runs.reduce((s, x) => s + (x.watered.harvest?.broken ?? 0), 0);
      const y = broken ? drops / broken : 0, net = y - (v.plantIsItem ? 1 : 0);
      const tc = runs.flatMap((x) => x.watered.tCells ?? []), t = mean(tc);
      const rate = t ? (80 * net) / (t / 24000) : 0;
      c.push({ id: `G3.${k}`, bar: 'net rate of a 9x9 watered field (80 crops) >= 24 items a day', value: { yPerCrop: y, netPerCrop: net, meanTCell: t, perDay: rate, broken }, pass: runs.length >= 5 && rate >= 24 });
    }
    if (r.g4) {
      const l8 = r.g4.crops.filter((x) => x.light === 8), l9 = r.g4.crops.filter((x) => x.light === 9);
      c.push({ id: 'G4.falloff', bar: 'the light reading matches a lantern\'s 15 - taxicab distance', value: r.g4.falloff, pass: r.g4.falloff.every((x) => x.measured === 15 - x.d) });
      c.push({ id: 'G4', bar: 'light-8 crops gain 0 stages; >= 80% of light-9 crops gain >= 1', value: { l8: l8.length, l8grew: l8.filter((x) => x.gain > 0).length, l9: l9.length, l9grew: l9.filter((x) => x.gain > 0).length }, pass: l8.length >= 4 && l9.length >= 4 && l8.every((x) => x.gain === 0) && l9.filter((x) => x.gain > 0).length >= 0.8 * l9.length });
    }
    if (r.g5) c.push({ id: 'G5', bar: 'recorded', value: r.g5, pass: null });
    if (r.g6) c.push({ id: 'G6', bar: 'recorded', value: r.g6, pass: null });
    return c;
  },
  TR(r) {
    const k = (n) => r.cases?.[n];
    const f = (n) => (k(n) ? `${k(n).trampled}/${k(n).of}` : 'not run');
    return [
      { id: 'TR1', bar: 'cow 10/10 with mob_griefing true, 0/10 with false', value: { griefing: f('cow'), noGriefing: f('cow_nogrief') }, pass: k('cow')?.trampled === 10 && k('cow_nogrief')?.trampled === 0 },
      { id: 'TR2', bar: 'chicken 0/10', value: f('chicken'), pass: k('chicken')?.trampled === 0 && k('chicken')?.of === 10 },
      { id: 'TR3', bar: 'zombie 10/10, villager 10/10 (mob_griefing true)', value: { zombie: f('zombie'), villager: f('villager') }, pass: k('zombie')?.trampled === 10 && k('villager')?.trampled === 10 },
      { id: 'TR4', bar: 'survival player dropped 2 blocks 10/10; walking 0 cells', value: { drop: f('player'), walkChanged: r.walk?.changed, walkCells: r.walk?.cells }, pass: k('player')?.trampled === 10 && r.walk?.changed === 0 },
    ];
  },
  HR(r) {
    const tpi = (r.runs ?? []).map((x) => x.ticksPerItem);
    return [{ id: 'HR', bar: '8.0 ticks per item ±5% (3 runs)', value: { ticksPerItem: tpi, itemsPerSecond: tpi.map((x) => 20 / x) }, pass: tpi.length >= 3 && tpi.every((x) => Math.abs(x - 8) <= 0.4) }];
  },
  HL(r) {
    return [
      { id: 'HL1', bar: '0 items reach D or the chest in 400 ticks; torch removed: first item in the chest within 20 ticks', value: r.hl1, pass: r.hl1?.moved === 0 && r.hl1?.firstAfterUnlock !== null && r.hl1?.firstAfterUnlock <= 20 },
      { id: 'HL2', bar: 'as HL1, locked by a strongly powered block', value: r.hl2, pass: r.hl2?.moved === 0 && r.hl2?.firstAfterUnlock !== null && r.hl2?.firstAfterUnlock <= 20 },
      { id: 'HL3', bar: 'a locked hopper receives, passes none on', value: r.hl3, pass: r.hl3?.received > 0 && r.hl3?.passed === 0 },
      { id: 'HL4', bar: 'recorded', value: r.hl4, pass: null },
    ];
  },
  CS(r) {
    const bad = (r.table ?? []).filter((x) => x.signal !== csExpect(x.n));
    return [
      { id: 'CS', bar: 'n in 0..64 (+4 fillers) gives floor(1 + 14(n+4)/320): 41->2, 42->3, 64->3; empty 0', value: { mismatches: bad, at41: r.table?.find((x) => x.n === 41)?.signal, at42: r.table?.find((x) => x.n === 42)?.signal, at64: r.table?.find((x) => x.n === 64)?.signal, empty: r.empty }, pass: (r.table ?? []).length === 65 && bad.length === 0 && r.empty === 0 },
      { id: 'CS.16', bar: 'recorded', value: r.table16, pass: null },
    ];
  },
  FC(r) {
    return [{ id: 'FC', bar: 'no item entity; source + chain + sink = fed + the sink\'s stone, exactly', value: r, pass: r.entities === 0 && r.total === r.expected }];
  },
  ST(r) {
    const c = [];
    const runs = r.runs ?? [];
    const halls = runs.flatMap((x) => x.halls);
    const bad = halls.filter((h) => !h.sorted);
    c.push({ id: 'ST1', bar: '20 runs x 4 halls: 100% sorted, input and line empty, items conserved, no item entity', value: { runs: runs.length, hallRuns: halls.length, failures: bad.map((h) => ({ run: h.run, hall: h.hall, why: h.why })) }, pass: runs.length >= 20 && bad.length === 0 });
    const hr = r.hrItemsPerSecond;
    const ips = halls.map((h) => h.itemsPerSecond).filter((x) => x);
    c.push({ id: 'ST2', bar: 'throughput within 10% of HR\'s rate', value: { hr, min: Math.min(...ips), median: median(ips), max: Math.max(...ips) }, pass: !!hr && ips.length > 0 && ips.every((x) => Math.abs(x - hr) <= 0.1 * hr) });
    c.push({ id: 'ST3', bar: 'after the 20 runs every F holds exactly 41 + 4 fillers', value: r.st3, pass: !!r.st3 && r.st3.every((h) => h.exact) });
    c.push({ id: 'ST4', bar: 'saturation: no wrong item, conserved, others 100%, F <= 3; recovery: all 100%, F back to 41', value: r.st4, pass: !!r.st4 && r.st4.every((h) => h.saturation.ok && h.recovery.ok) });
    c.push({ id: 'ST5', bar: 'recorded (the filler on the line); compared with §3.2\'s "the filler goes to overflow"', value: r.st5, pass: null });
    c.push({ id: 'ST6', bar: 'every slot chest is type=single and holds only its slot\'s item', value: r.st6, pass: !!r.st6 && r.st6.every((h) => h.ok) });
    return c;
  },
};
const ORDER = ['SE', 'CS', 'HL', 'HR', 'FC', 'H', 'G', 'TR', 'ST'];

function judgeAll(data) {
  const table = [];
  for (const id of ORDER) {
    const r = data.probes?.[id];
    if (!r) continue;
    for (const ch of JUDGES[id](r.raw)) table.push(ch);
  }
  return table;
}
function printTable(table) {
  for (const ch of table) log(`${ch.pass === null ? 'rec ' : ch.pass ? 'ok  ' : 'FAIL'} ${ch.id}: ${ch.bar} -> ${JSON.stringify(ch.value)}`);
}

const loadData = () => (fs.existsSync(OUT_JSON) ? JSON.parse(fs.readFileSync(OUT_JSON, 'utf8')) : { probes: {} });

if (flag('--judge')) {
  const data = loadData();
  const t = judgeAll(data);
  printTable(t);
  const fails = t.filter((x) => x.pass === false).length;
  log(`judged ${t.length} bars from ${path.relative(ROOT, OUT_JSON)}: ${fails} FAIL`);
  process.exit(fails ? 1 : 0);
}

// ------------------------------------------------------------------ the client and the bridge

const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);
const { decodeArwd } = await import(pathToFileURL(path.join(ARCH, 'kit', 'lib', 'region', 'vworld.mjs')).href);

let dev = null, child = null;
const WORLD_NAME = `Steward M0 Probe ${new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')}`;
async function startClient() {
  if (flag('--attach')) { dev = await DevClient.connect({ port: PORT, timeoutMs: 30_000 }); await dev.waitInWorld({ timeoutMs: 60_000 }); return; }
  if (fs.existsSync(path.join(GAME, 'architect', 'sidecar-data', 'secrets.json'))) throw new Error('the claude-login opt-in exists: refusing');
  const out = fs.openSync(path.join(ART, 'client.log'), 'a');
  child = spawn(path.join(ROOT, 'tools', 'run-e2e-client.sh'), [], {
    stdio: ['ignore', out, out],
    env: { ...process.env, STEWARD_PORT: String(PORT - 1), STEWARD_DEV_PORT: String(PORT), ARCHITECT_AUTOWORLD_NAME: WORLD_NAME, E2E_STUB: '0', E2E_NODE_DIR: path.dirname(process.execPath), GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? path.join(MAIN_CHECKOUT, '.gradle-home') },
  });
  log(`client launched (launcher pid ${child.pid}), world "${WORLD_NAME}"`);
  dev = await DevClient.connect({ port: PORT, timeoutMs: 600_000 });
  await dev.waitInWorld({ timeoutMs: 600_000 });
}
async function stopClient() {
  if (!dev || flag('--attach') || flag('--keep-client')) return;
  const r = await dev.request('dev.quit');
  if (r.ok) await dev.waitClosed(120_000);
}

async function raw(c) {
  const r = await dev.request('dev.command', { cmd: c }, { timeoutMs: 120_000 });
  if (!r.ok) throw new Error(`${c}: ${r.error}`);
  return r;
}
async function cmd(c) {
  const r = await raw(c);
  const text = (r.messages ?? []).join('\n');
  if (/<--\[HERE\]|Unknown or incomplete|That position is not loaded|not accept/.test(text)) throw new Error(`${c}: ${text}`);
  return { text, res: r.result };
}
const tryCmd = async (c) => { try { return await cmd(c); } catch (e) { return { text: String(e.message), res: null, error: true }; } };
async function gameTime() { return (await cmd('/time query gametime')).res; }

let SPRINT = false;
/**
 * Advance n game ticks. The game is frozen (`/tick freeze`) for the whole run, so it moves only here and every reading happens with the game
 * stopped: `/tick step n` (20 ticks a second, "real time") until SE has passed, then `/tick sprint n` (as fast as the server can; the game is
 * frozen again when the sprint ends). -> the ticks actually advanced (a sprint may run one more than asked).
 */
async function advance(n) {
  const t0 = await gameTime();
  await cmd(SPRINT ? `/tick sprint ${n}` : `/tick step ${n}`);
  const end = Date.now() + Math.max(120_000, n * (SPRINT ? 20 : 80));
  for (;;) {
    const t = await gameTime();
    if (t - t0 >= n) {
      if (SPRINT) for (let i = 0; i < 100 && !/frozen/.test((await cmd('/tick query')).text.split('\n')[0]); i++) await sleep(20);
      return (await gameTime()) - t0;
    }
    if (Date.now() > end) { await tryCmd('/tick sprint stop'); throw new Error(`advance of ${n} stuck at ${t - t0}`); }
    await sleep(SPRINT ? (n > 2000 ? 100 : 10) : Math.min(1000, Math.max(20, ((n - (t - t0)) * 50) / 2)));
  }
}

const sb = (x, y, z, s) => cmd(`/setblock ${x} ${y} ${z} ${s}`);
const fill = (x0, y0, z0, x1, y1, z1, s) => cmd(`/fill ${x0} ${y0} ${z0} ${x1} ${y1} ${z1} ${s}`);
async function dump(x0, y0, z0, x1, y1, z1) {
  const f = path.join(ART, 'dump.arwd.gz');
  await dev.call('dev.region.dump', { box: [x0, y0, z0, x1, y1, z1], file: f }, { timeoutMs: 120_000 });
  const d = await decodeArwd(fs.readFileSync(f));
  return (x, y, z) => d.palette[d.cells[d.at(x, y, z)]];
}
const prop = (state, k) => { const m = new RegExp(`[\\[,]${k}=([^,\\]]+)`).exec(state ?? ''); return m ? m[1] : null; };

/** A container's items from `/data get block ... Items`: {"<id>" or "<id>#<name>": count}. */
function parseItems(text) {
  const i = text.indexOf('[');
  const out = {};
  if (i < 0) return out;
  let depth = 0, start = -1;
  for (let j = i; j < text.length; j++) {
    const ch = text[j];
    if (ch === '{') { if (depth === 0) start = j; depth++; } else if (ch === '}') { depth--; if (depth === 0 && start >= 0) { add(text.slice(start, j + 1)); start = -1; } }
  }
  function add(e) {
    const id = /\bid: "([^"]+)"/.exec(e)?.[1];
    const count = Number(/\bcount: (\d+)/.exec(e)?.[1] ?? 1);
    const name = /custom_name": "([^"]*)"/.exec(e)?.[1];
    const key = name !== undefined ? `${id}#${name}` : /components:/.test(e) ? `${id}#components` : id;
    out[key] = (out[key] ?? 0) + count;
  }
  return out;
}
async function items(x, y, z) {
  const r = await raw(`/data get block ${x} ${y} ${z} Items`);
  return parseItems((r.messages ?? []).join(' '));
}
const total = (m) => Object.values(m).reduce((s, n) => s + n, 0);
const merge = (...ms) => { const o = {}; for (const m of ms) for (const [k, n] of Object.entries(m)) o[k] = (o[k] ?? 0) + n; return o; };
/** A placed comparator only reads its input on a neighbour update: a block set and cleared above it. */
async function poke(x, y, z) { await sb(x, y + 1, z, 'glass'); await sb(x, y + 1, z, 'air'); }
const clearC = (x, y, z) => tryCmd(`/data remove block ${x} ${y} ${z} Items`);
async function put(x, y, z, slot, spec, n) { await cmd(`/item replace block ${x} ${y} ${z} container.${slot} with ${spec} ${n}`); }
async function dropped(x, y, z, r) { return dev.call('dev.items.near', { pos: [x, y, z], radius: r }, { timeoutMs: 20_000 }); }
const killItems = (x, y, z, r) => tryCmd(`/kill @e[type=item,x=${x - r},y=${y - r},z=${z - r},dx=${2 * r + 1},dy=${2 * r + 1},dz=${2 * r + 1}]`);
async function light(x, y, z) {
  let lo = 0, hi = 15;
  while (lo < hi) {
    const m = Math.ceil((lo + hi) / 2);
    const r = await raw(`/execute positioned ${x} ${y} ${z} if predicate {type:"minecraft:location_check",predicate:{light:{light:{min:${m}}}}}`);
    if (r.result === 1) lo = m; else hi = m - 1;
  }
  return lo;
}

// ------------------------------------------------------------------ the world

const C = { x: 1008, z: 1008 };
let B = 64; // the flat preset's grass top (checked at start)
const cell = (i, j) => ({ x: C.x + 16 * i, z: C.z + 16 * j });
async function home() {
  await cmd('/gamemode spectator');
  await cmd(`/tp @s ${C.x + 0.5} ${B + 30} ${C.z + 0.5} 0 90`);
  await dev.call('dev.waitChunks', { timeoutMs: 120_000 }, { timeoutMs: 150_000 }).catch(() => null);
}
async function rules() {
  for (const r of ['random_tick_speed 3', 'mob_griefing true', 'advance_time false', 'advance_weather false', 'spawn_mobs false', 'spawn_monsters false', 'fire_spread_radius_around_player 0']) await tryCmd(`/gamerule ${r}`);
  await cmd('/weather clear');
  await cmd('/time set 6000');
}
/** Reset a square of ground: grass at B, air above, dirt under. */
async function ground(x0, z0, x1, z1, h = 6) {
  await fill(x0, B + 1, z0, x1, B + h, z1, 'air');
  await fill(x0, B, z0, x1, B, z1, 'grass_block');
  await fill(x0, B - 1, z0, x1, B - 1, z1, 'dirt');
}
/** A 9x9 plot centred on c: farmland (moist or dry), an optional centre source, crops at age 0. */
async function plot(c, { moist, water, crop, age = 0, ring = false }) {
  const r = ring ? 5 : 4;
  await ground(c.x - r - 1, c.z - r - 1, c.x + r + 1, c.z + r + 1);
  await fill(c.x - 4, B, c.z - 4, c.x + 4, B, c.z + 4, `farmland[moisture=${moist ? 7 : 0}]`);
  if (crop) await fill(c.x - 4, B + 1, c.z - 4, c.x + 4, B + 1, c.z + 4, `${crop}[age=${age}]`);
  if (water) { await sb(c.x, B + 1, c.z, 'air'); await sb(c.x, B, c.z, 'water'); }
  if (ring) {
    for (const [x, z] of ringCells(c)) { await sb(x, B, z, 'farmland[moisture=0]'); await sb(x, B + 1, z, `${crop}[age=0]`); }
  }
}
function ringCells(c) {
  const o = [];
  for (let dx = -5; dx <= 5; dx++) for (let dz = -5; dz <= 5; dz++) if (Math.max(Math.abs(dx), Math.abs(dz)) === 5) o.push([c.x + dx, c.z + dz]);
  return o;
}
const plotCells = (c, water) => { const o = []; for (let dx = -4; dx <= 4; dx++) for (let dz = -4; dz <= 4; dz++) if (!(water && dx === 0 && dz === 0)) o.push([c.x + dx, c.z + dz]); return o; };
async function plotAges(c, block) {
  const at = await dump(c.x - 4, B + 1, c.z - 4, c.x + 4, B + 1, c.z + 4);
  const o = {};
  for (let dx = -4; dx <= 4; dx++) for (let dz = -4; dz <= 4; dz++) { const s = at(c.x + dx, B + 1, c.z + dz); o[`${c.x + dx},${c.z + dz}`] = s?.startsWith(`minecraft:${block}`) ? Number(prop(s, 'age')) : null; }
  return o;
}

// ------------------------------------------------------------------ the probes

const P = {};

P.SE = async () => {
  SPRINT = false;
  const out = {};
  // growth: 4 watered wheat plots, 6,000 ticks real, then 6,000 sprinted
  const plots = [-2, -1, 0, 1].map((i) => cell(i, -1));
  const gains = async (mode) => {
    for (const p of plots) await plot(p, { moist: true, water: true, crop: 'wheat' });
    await advance(5);
    SPRINT = mode === 'sprint';
    const n = await advance(6000);
    SPRINT = false;
    const g = [];
    for (const p of plots) { const a = await plotAges(p, 'wheat'); for (const [x, z] of plotCells(p, true)) g.push(a[`${x},${z}`] ?? 0); }
    return { g, n };
  };
  const real = await gains('real');
  const spr = await gains('sprint');
  out.growth = { real: real.g, sprint: spr.g, ticksReal: real.n, ticksSprint: spr.n };
  log(`  SE growth: real mean ${mean(real.g).toFixed(3)}, sprint mean ${mean(spr.g).toFixed(3)}`);
  // hoppers: one sorting run of one hall, real then sprinted (the halls are ST's)
  await buildHalls();
  const a = await stRun(0, { halls: [HALLS[0]], sprint: false });
  const b = await stRun(1, { halls: [HALLS[0]], sprint: true });
  const h0a = a.halls[0], h0b = b.halls[0];
  out.hoppers = { real: { counts: h0a.counts, items: h0a.items, ticks: h0a.ticks }, sprint: { counts: h0b.counts, items: h0b.items, ticks: h0b.ticks } };
  return out;
};

P.CS = async () => {
  const x = C.x - 40, z = C.z - 36;
  await ground(x - 2, z - 2, x + 4, z + 2);
  await sb(x - 1, B + 1, z, 'stone');
  const setHopper = async (n, item = 'cobblestone') => {
    await sb(x, B + 1, z, 'air');
    await sb(x, B + 1, z, 'hopper[facing=west]');
    if (n > 0) await put(x, B + 1, z, 0, item, n);
    for (let s = 1; s <= 4; s++) await put(x, B + 1, z, s, 'stick[custom_name="Steward filler"]', 1);
    await sb(x + 1, B + 1, z, 'air');
    await sb(x + 1, B + 1, z, 'comparator[facing=west]');
    await poke(x + 1, B + 1, z);
    await advance(4);
    return Number(/data: (\d+)/.exec((await cmd(`/data get block ${x + 1} ${B + 1} ${z} OutputSignal`)).text)?.[1]);
  };
  const table = [];
  for (let n = 0; n <= 64; n++) table.push({ n, signal: await setHopper(n) });
  const table16 = [];
  for (let n = 1; n <= 16; n++) table16.push({ n, signal: await setHopper(n, 'ender_pearl') });
  await sb(x, B + 1, z, 'air');
  await sb(x, B + 1, z, 'hopper[facing=west]');
  await sb(x + 1, B + 1, z, 'air');
  await sb(x + 1, B + 1, z, 'comparator[facing=west]');
  await poke(x + 1, B + 1, z);
  await advance(4);
  const empty = Number(/data: (\d+)/.exec((await cmd(`/data get block ${x + 1} ${B + 1} ${z} OutputSignal`)).text)?.[1]);
  return { table, table16, empty };
};

P.HL = async () => {
  const z = C.z - 28;
  const one = async (x, kind) => {
    await ground(x - 2, z - 2, x + 3, z + 3);
    // the upper hopper: 64 items, pointing into a stone block (never pushes)
    await sb(x, B + 2, z - 1, 'stone');
    if (kind === 'torch') await sb(x, B + 1, z + 1, 'redstone_torch'); // beside D, on the grass
    if (kind === 'block') { await sb(x, B, z + 1, 'redstone_torch'); await sb(x, B + 1, z + 1, 'stone'); } // a torch under a stone beside D
    await sb(x + 1, B + 1, z, 'chest');
    await sb(x, B + 1, z, 'hopper[facing=east]');
    await sb(x, B + 2, z, 'hopper[facing=north]');
    await put(x, B + 2, z, 0, 'cobblestone', 64);
    await advance(400);
    const moved = total(await items(x, B + 1, z)) + total(await items(x + 1, B + 1, z));
    if (kind === 'torch') await sb(x, B + 1, z + 1, 'air'); else await sb(x, B, z + 1, 'dirt');
    let first = null;
    for (let t = 1; t <= 40 && first === null; t++) { await advance(1); if (total(await items(x + 1, B + 1, z)) > 0) first = t; }
    return { moved, firstAfterUnlock: first };
  };
  const hl1 = await one(C.x - 40, 'torch');
  const hl2 = await one(C.x - 34, 'block');
  // HL3: an unlocked hopper pointing down into a locked one, which points into a chest
  const x3 = C.x - 28;
  await ground(x3 - 2, z - 2, x3 + 3, z + 3);
  await sb(x3, B + 1, z + 1, 'redstone_torch');
  await sb(x3 + 1, B + 1, z, 'chest');
  await sb(x3, B + 1, z, 'hopper[facing=east]');
  await sb(x3, B + 2, z, 'hopper[facing=down]');
  await put(x3, B + 2, z, 0, 'cobblestone', 64);
  await advance(400);
  const hl3 = { received: total(await items(x3, B + 1, z)), passed: total(await items(x3 + 1, B + 1, z)) };
  // HL4: an unlocked hopper under a locked one (the upper points into stone; a wall torch beside it, not touching the lower)
  const x4 = C.x - 22;
  await ground(x4 - 2, z - 2, x4 + 3, z + 3);
  await sb(x4, B + 2, z - 1, 'stone');
  await sb(x4, B + 2, z + 2, 'stone');
  await sb(x4, B + 2, z + 1, 'redstone_wall_torch[facing=north]');
  await sb(x4 + 1, B + 1, z, 'chest');
  await sb(x4, B + 1, z, 'hopper[facing=east]');
  await sb(x4, B + 2, z, 'hopper[facing=north]');
  await put(x4, B + 2, z, 0, 'cobblestone', 64);
  await advance(400);
  const st = (await dump(x4, B + 2, z, x4, B + 2, z))(x4, B + 2, z);
  const hl4 = { upperState: st, pulledThrough: total(await items(x4, B + 1, z)) + total(await items(x4 + 1, B + 1, z)) };
  return { hl1, hl2, hl3, hl4 };
};

/** A chain of n hoppers along +x from x0, fed from a chest above the first, into a chest at the end. */
async function chain(x0, z, n) {
  await ground(x0 - 2, z - 2, x0 + n + 2, z + 2);
  await sb(x0 + n, B + 1, z, 'chest');
  for (let i = n - 1; i >= 0; i--) await sb(x0 + i, B + 1, z, 'hopper[facing=east]');
  await sb(x0, B + 2, z, 'chest');
}

P.HR = async () => {
  const runs = [];
  const x0 = C.x - 12, z = C.z - 28;
  for (let r = 0; r < 3; r++) {
    await chain(x0, z, 8);
    for (let s = 0; s < 5; s++) await put(x0, B + 2, z, s, 'cobblestone', 64);
    let t = 0, first = null, last = null;
    while (last === null && t < 6000) {
      t += await advance(20);
      const n = total(await items(x0 + 8, B + 1, z));
      if (n > 0 && first === null) first = t;
      if (n >= 320) last = t;
    }
    runs.push({ first, last, ticksPerItem: last !== null ? (last - first) / 319 : null });
    log(`  HR run ${r}: first ${first}, last ${last}`);
  }
  return { runs };
};

P.FC = async () => {
  const x0 = C.x + 4, z = C.z - 28;
  await chain(x0, z, 4);
  for (let s = 0; s < 27; s++) await put(x0 + 4, B + 1, z, s, 'stone', 64);
  for (let s = 0; s < 5; s++) await put(x0, B + 2, z, s, 'cobblestone', 64);
  await advance(2400);
  let sum = total(await items(x0, B + 2, z)) + total(await items(x0 + 4, B + 1, z));
  for (let i = 0; i < 4; i++) sum += total(await items(x0 + i, B + 1, z));
  const d = await dropped(x0 + 2, B + 1, z, 8);
  const chainFull = [];
  for (let i = 0; i < 4; i++) chainFull.push(total(await items(x0 + i, B + 1, z)));
  return { total: sum, expected: 320 + 27 * 64, entities: d.stacks, chainCounts: chainFull };
};

P.H = async () => {
  const c = { x: C.x, z: C.z }; // the plot straddles the chunk corner at (1008, 1008)
  const runs = [];
  for (let r = 0; r < 5; r++) {
    await plot(c, { moist: false, water: true, crop: 'wheat', ring: true });
    const first = {};
    let t = 0;
    while (t < 4800) {
      t += await advance(200);
      const at = await dump(c.x - 5, B, c.z - 5, c.x + 5, B, c.z + 5);
      for (const [x, z] of plotCells(c, true)) if (first[`${x},${z}`] === undefined && prop(at(x, B, z), 'moisture') === '7') first[`${x},${z}`] = t;
      if (t >= 4800) {
        const ring = ringCells(c).map(([x, z]) => at(x, B, z));
        runs.push({ cells: 80, moistBy4800: Object.keys(first).length, lastTick: Math.max(0, ...Object.values(first)), ringCells: ring.length, ringMoist: ring.filter((s) => prop(s, 'moisture') !== '0').length, ringStates: [...new Set(ring)], firstTicks: Object.values(first) });
      }
    }
    log(`  H run ${r}: ${runs[r].moistBy4800}/80 by 4800 (last ${runs[r].lastTick}), ring moist ${runs[r].ringMoist}`);
  }
  // H3: a moist plot with water and one without, 24,000 ticks
  const wet = cell(1, 0), dry = cell(2, 0);
  await plot(wet, { moist: true, water: true, crop: 'wheat' });
  await plot(dry, { moist: true, water: false, crop: 'wheat' });
  let wetDrops = 0, dryStill7 = 0;
  for (let t = 0; t < 24000;) {
    t += await advance(1000);
    const a = await dump(wet.x - 4, B, wet.z - 4, wet.x + 4, B, wet.z + 4);
    for (const [x, z] of plotCells(wet, true)) if (prop(a(x, B, z), 'moisture') !== '7') wetDrops++;
    if (t >= 24000) { const b = await dump(dry.x - 4, B, dry.z - 4, dry.x + 4, B, dry.z + 4); dryStill7 = plotCells(dry, false).filter(([x, z]) => prop(b(x, B, z), 'moisture') === '7').length; }
  }
  // H4: water one below the farmland's level, and one above it (contained), 4,800 ticks
  const below = cell(-1, 0), above = cell(-2, 0);
  await plot(below, { moist: false, water: false, crop: 'wheat' });
  await sb(below.x, B - 1, below.z, 'water');
  await plot(above, { moist: false, water: false, crop: 'wheat' });
  await sb(above.x, B, above.z, 'glass');
  for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) await sb(above.x + dx, B + 1, above.z + dz, 'glass');
  await sb(above.x, B + 1, above.z, 'water');
  await advance(4800);
  const cnt = async (p, skip) => { const a = await dump(p.x - 4, B, p.z - 4, p.x + 4, B, p.z + 4); return plotCells(p, skip).filter(([x, z]) => prop(a(x, B, z), 'moisture') === '7').length; };
  const h4 = { waterBelowMoist: await cnt(below, false), waterBelowCells: 81, waterAboveMoist: await cnt(above, true), waterAboveCells: 80 };
  return { runs, h3: { wetDrops, dryStill7 }, h4 };
};

P.G = async () => {
  const out = { runs: [] };
  // G0: the maximum age
  out.g0 = {};
  for (const [k, v] of Object.entries(CROPS)) {
    const t = await tryCmd(`/setblock ${C.x} ${B + 1} ${C.z - 44} ${v.block}[age=${v.max + 1}]`);
    out.g0[k] = { refused: !!t.error && /not accept/.test(t.text), maxSeen: 0 };
  }
  const W = { wheat: cell(-2, 1), carrots: cell(-1, 1), potatoes: cell(0, 1), beetroots: cell(1, 1) };
  const U = { wheat: cell(-2, 2), carrots: cell(-1, 2), potatoes: cell(0, 2), beetroots: cell(1, 2) };
  await cmd('/time set 6000');
  for (let r = 0; r < RUNS_G; r++) {
    for (const k of Object.keys(CROPS)) { await plot(W[k], { moist: true, water: true, crop: CROPS[k].block }); await plot(U[k], { moist: false, water: false, crop: CROPS[k].block }); }
    const st = {};
    for (const k of Object.keys(CROPS)) st[k] = { watered: { T: null, tCells: [], done: false, firstMature: {} }, unwatered: { T: null, done: false } };
    let t = 0;
    while (t < 150000 && Object.values(st).some((s) => !s.watered.done || !s.unwatered.done)) {
      t += await advance(1000);
      for (const [k, v] of Object.entries(CROPS)) {
        const sw = st[k].watered;
        if (!sw.done) {
          const a = await plotAges(W[k], v.block);
          const cells = plotCells(W[k], true);
          let mature = 0;
          for (const [x, z] of cells) { const g = a[`${x},${z}`]; if (g !== null) out.g0[k].maxSeen = Math.max(out.g0[k].maxSeen, g); if (g === v.max) { mature++; if (sw.firstMature[`${x},${z}`] === undefined) sw.firstMature[`${x},${z}`] = t; } }
          if (sw.T === null && mature >= cells.length / 2) sw.T = t;
          if (mature >= 0.9 * cells.length || t >= 150000) {
            // harvest every mature crop, count its drops
            let broken = 0;
            for (const [x, z] of cells) if (a[`${x},${z}`] === v.max) { await cmd(`/setblock ${x} ${B + 1} ${z} air destroy`); broken++; }
            await advance(10);
            const d = await dropped(W[k].x, B + 1, W[k].z, 7);
            sw.harvest = { broken, items: d.items, at: t };
            await killItems(W[k].x, B + 1, W[k].z, 8);
            sw.tCells = Object.values(sw.firstMature);
            sw.done = true;
          }
        }
        const su = st[k].unwatered;
        if (!su.done) {
          const a = await plotAges(U[k], v.block);
          const cells = plotCells(U[k], false);
          const mature = cells.filter(([x, z]) => a[`${x},${z}`] === v.max).length;
          if (mature >= cells.length / 2) { su.T = t; su.done = true; }
          if (t >= 150000) su.done = true;
        }
      }
    }
    for (const s of Object.values(st)) delete s.watered.firstMature;
    out.runs.push(st);
    log(`  G run ${r}: ${Object.entries(st).map(([k, s]) => `${k} T ${s.watered.T} (unwatered ${s.unwatered.T}), harvest ${JSON.stringify(s.watered.harvest?.items)} from ${s.watered.harvest?.broken}`).join('; ')}`);
  }
  // G4, G5: midnight; a sealed room with one lantern, and an open-sky plot
  await cmd('/time set 18000');
  const R = { x: C.x + 40, z: C.z + 24 };
  await ground(R.x - 9, R.z - 9, R.x + 9, R.z + 9);
  await fill(R.x - 8, B, R.z - 8, R.x + 8, B + 4, R.z + 8, 'stone');
  await fill(R.x - 7, B + 1, R.z - 7, R.x + 7, B + 3, R.z + 7, 'air');
  await fill(R.x - 7, B, R.z - 7, R.x + 7, B, R.z + 7, 'farmland[moisture=7]');
  for (const [dx, dz] of [[4, 4], [-4, 4], [4, -4], [-4, -4]]) await sb(R.x + dx, B, R.z + dz, 'water');
  await sb(R.x, B, R.z, 'stone');
  await sb(R.x, B + 1, R.z, 'lantern');
  await advance(20);
  const falloff = [];
  for (let d = 1; d <= 7; d++) falloff.push({ d, measured: await light(R.x + d, B + 1, R.z) });
  const chosen = [];
  for (let dx = -7; dx <= 7; dx++) for (let dz = -7; dz <= 7; dz++) {
    const d = Math.abs(dx) + Math.abs(dz);
    if ((d !== 6 && d !== 7) || (Math.abs(dx) === 4 && Math.abs(dz) === 4)) continue;
    if (chosen.some((c) => Math.max(Math.abs(c.dx - dx), Math.abs(c.dz - dz)) <= 1)) continue;
    chosen.push({ dx, dz, d });
  }
  for (const c of chosen) { c.light = await light(R.x + c.dx, B + 1, R.z + c.dz); await sb(R.x + c.dx, B + 1, R.z + c.dz, 'wheat[age=0]'); }
  const G5 = cell(2, 3);
  await plot(G5, { moist: true, water: true, crop: 'wheat' });
  const g5light = await light(G5.x + 2, B + 1, G5.z + 2);
  await advance(24000);
  const at = await dump(R.x - 7, B + 1, R.z - 7, R.x + 7, B + 1, R.z + 7);
  out.g4 = { falloff, crops: chosen.map((c) => ({ ...c, gain: Number(prop(at(R.x + c.dx, B + 1, R.z + c.dz), 'age') ?? -1) })) };
  const a5 = await plotAges(G5, 'wheat');
  const g5 = plotCells(G5, true).map(([x, z]) => a5[`${x},${z}`] ?? 0);
  out.g5 = { lightAtMidnight: g5light, meanGain: mean(g5), grew: g5.filter((x) => x > 0).length, of: g5.length, ticks: 24000 };
  // G6: lamps, each in its own sealed box, read one cell away
  const lamps = ['lantern', 'torch', 'soul_lantern', 'soul_torch', 'sea_lantern', 'glowstone', 'shroomlight', 'end_rod', 'redstone_lamp[lit=true]', 'candle[candles=1,lit=true]', 'candle[candles=2,lit=true]', 'candle[candles=3,lit=true]', 'candle[candles=4,lit=true]', 'copper_lantern', 'copper_torch', 'jack_o_lantern', 'ochre_froglight'];
  out.g6 = {};
  const L0 = { x: C.x - 44, z: C.z + 8 };
  for (let i = 0; i < lamps.length; i++) {
    const x = L0.x, z = L0.z + 4 * i;
    await ground(x - 2, z - 2, x + 3, z + 2);
    await fill(x - 1, B + 1, z - 1, x + 2, B + 3, z + 1, 'stone');
    await fill(x, B + 2, z, x + 1, B + 2, z, 'air');
    if (lamps[i].startsWith('redstone_lamp')) await sb(x, B + 1, z, 'redstone_block');
    const t = await tryCmd(`/setblock ${x} ${B + 2} ${z} ${lamps[i]}`);
    if (t.error) { out.g6[lamps[i]] = { error: t.text.slice(0, 120) }; continue; }
    await advance(10);
    const m = await light(x + 1, B + 2, z);
    out.g6[lamps[i]] = { measuredNext: m, source: m + 1, placed: (await dump(x, B + 2, z, x, B + 2, z))(x, B + 2, z) };
  }
  await cmd('/time set 6000');
  return out;
};

P.TR = async () => {
  const z = C.z + 44, x0 = C.x - 30;
  const cells = Array.from({ length: 10 }, (_, k) => x0 + 3 * k);
  // shafts: each cell in a 3-high glass shaft, so a mob lands on its own cell (villagers walk off within ticks of spawning)
  const reset = async (shafts = true) => {
    await ground(x0 - 2, z - 2, x0 + 30, z + 2);
    await sb(x0 + 14, B, z + 2, 'water');
    for (const x of cells) {
      if (shafts) { await fill(x - 1, B + 1, z - 1, x + 1, B + 3, z + 1, 'glass'); await fill(x, B + 1, z, x, B + 3, z, 'air'); }
      await sb(x, B, z, 'farmland[moisture=7]');
      await sb(x, B + 1, z, 'wheat[age=0]');
    }
  };
  const isDirt = async () => { const a = await dump(x0, B, z, x0 + 27, B, z); return cells.filter((x) => !a(x, B, z).startsWith('minecraft:farmland')).length; }; // trampled: dirt, or grass already spread over it
  const cases = {};
  const diff = (await tryCmd('/difficulty')).text;
  await cmd('/difficulty easy');
  const mobCase = async (name, type, griefing, nbt = '{}') => {
    await cmd(`/gamerule mob_griefing ${griefing}`);
    await reset();
    for (const x of cells) await cmd(`/summon ${type} ${x + 0.5} ${B + 3} ${z + 0.5} ${nbt}`);
    await advance(30);
    cases[name] = { trampled: await isDirt(), of: 10 };
    await tryCmd(`/kill @e[type=${type},x=${x0 - 3},y=${B - 2},z=${z - 3},dx=36,dy=10,dz=6]`);
    await killItems(x0 + 14, B + 1, z, 18);
  };
  await mobCase('cow', 'cow', true);
  await mobCase('cow_nogrief', 'cow', false);
  await mobCase('chicken', 'chicken', true);
  await mobCase('zombie', 'zombie', true, '{IsBaby:0b}');
  await mobCase('villager', 'villager', true);
  await cmd('/gamerule mob_griefing true');
  await reset();
  await cmd('/gamemode survival');
  const perDrop = [];
  for (const x of cells) { await cmd(`/tp @s ${x + 0.5} ${B + 3} ${z + 0.5}`); await advance(60); perDrop.push((await dump(x, B, z, x, B, z))(x, B, z)); }
  cases.player = { trampled: await isDirt(), of: 10, perDrop };
  await reset(false);
  const walkBefore = await isDirt();
  for (let s = 0; s <= 27 * 4; s++) { await cmd(`/tp @s ${x0 + 0.5 + s * 0.25} ${B + 0.9375} ${z + 0.5}`); await advance(1); }
  await advance(20);
  const walk = { changed: (await isDirt()) - walkBefore, cells: 10 };
  cases.difficultyBefore = diff;
  if (/peaceful/i.test(diff)) await tryCmd('/difficulty peaceful');
  await home();
  return { cases, walk };
};

// ------------------------------------------------------------------ ST: the assembled sorter tile

const FILTERS = ['cobblestone', 'iron_ingot', 'wheat', 'oak_planks'];
const FILLER = 'stick[custom_name="Steward filler"]';
const ROT = {
  0: (u, v) => [u, v], 90: (u, v) => [-v, u], 180: (u, v) => [-u, -v], 270: (u, v) => [v, -u],
};
const DIRS = { east: [1, 0], west: [-1, 0], south: [0, 1], north: [0, -1] };
const dirName = (dx, dz) => Object.entries(DIRS).find(([, d]) => d[0] === dx && d[1] === dz)[0];
// the east-facing hall straddles x = 1024 (a chunk border) between its slots 2 and 3: ox + 2 = 1023
const HALLS = [
  { name: 'rot0', rot: 0, o: { x: 1021, z: 966 } },
  { name: 'rot90', rot: 90, o: { x: 1042, z: 966 } },
  { name: 'rot180', rot: 180, o: { x: 988, z: 970 } },
  { name: 'rot270', rot: 270, o: { x: 1002, z: 972 } },
];
const OY = () => B + 3;
function hallAt(h) {
  const f = ROT[h.rot];
  const w = (u, y, v) => { const [dx, dz] = f(u, v); return [h.o.x + dx, OY() + y, h.o.z + dz]; };
  const face = (name) => { const [du, dv] = DIRS[name]; const [dx, dz] = f(du, dv); return dirName(dx, dz); };
  return { w, face };
}
const SLOTS = 4;
async function buildHall(h) {
  const { w, face } = hallAt(h);
  const corners = [w(-1, -2, -5), w(SLOTS + 1, 3, 2)];
  const xs = corners.map((p) => p[0]), ys = corners.map((p) => p[1]), zs = corners.map((p) => p[2]);
  await fill(Math.min(...xs), Math.min(...ys), Math.min(...zs), Math.max(...xs), Math.max(...ys), Math.max(...zs), 'air');
  const S = (u, y, v, s) => sb(...w(u, y, v), s);
  for (let u = 0; u < SLOTS; u++) {
    for (const [y, v] of [[0, -1], [0, -2], [0, -3], [-1, -4], [-2, -3], [-1, -2]]) await S(u, y, v, 'stone');
    await S(u, -1, -1, `redstone_wall_torch[facing=${face('south')}]`);
    await S(u, 0, 1, `chest[type=single,facing=${face('south')}]`);
    await S(u, 0, 0, `hopper[facing=${face('south')}]`);
    await S(u, 1, 0, `hopper[facing=${face('north')}]`);
    const F = w(u, 1, 0);
    await put(...F, 0, FILTERS[u], 41);
    for (let s = 1; s <= 4; s++) await put(...F, s, FILLER, 1);
    await S(u, 2, 0, `hopper[facing=${face('east')}]`);
    await S(u, 1, -2, 'redstone_wire');
    await S(u, 1, -3, 'redstone_wire');
    await S(u, 0, -4, 'redstone_wire');
    await S(u, -1, -3, `repeater[facing=${face('north')},delay=1]`);
    await S(u, 1, -1, `comparator[facing=${face('south')}]`);
    await poke(...w(u, 1, -1));
  }
  await S(SLOTS, 2, 0, `chest[type=single,facing=${face('south')}]`);
  await S(0, 3, 0, `chest[type=single,facing=${face('south')}]`);
}
let BUILT = false;
async function buildHalls() {
  if (BUILT) return;
  for (const h of HALLS) await buildHall(h);
  await advance(20);
  BUILT = true;
}
function mixSpecs() {
  const m = [];
  for (const f of FILTERS) for (let i = 0; i < 3; i++) m.push([f, 64]);
  for (const f of ['dirt', 'sand', 'gravel', 'andesite', 'coal']) m.push([f, 64]);
  for (let i = 0; i < 3; i++) m.push(['iron_sword', 1]);
  m.push(['ender_pearl', 16]);
  m.push(['cobblestone[custom_name="Not filtered"]', 64]);
  m.push(['stick', 64]);
  return m;
}
const key = (spec) => { const n = /custom_name="([^"]*)"/.exec(spec); return `minecraft:${spec.replace(/\[.*$/, '')}${n ? '#' + n[1] : ''}`; };
async function hallState(h) {
  const { w } = hallAt(h);
  const s = { input: await items(...w(0, 3, 0)), overflow: await items(...w(SLOTS, 2, 0)), line: [], F: [], D: [], chest: [] };
  for (let u = 0; u < SLOTS; u++) {
    s.line.push(await items(...w(u, 2, 0)));
    s.F.push(await items(...w(u, 1, 0)));
    s.D.push(await items(...w(u, 0, 0)));
    s.chest.push(await items(...w(u, 0, 1)));
  }
  return s;
}
const fExact = (f, u) => f[`minecraft:${FILTERS[u]}`] === 41 && f['minecraft:stick#Steward filler'] === 4 && Object.keys(f).length === 2;
/** One sorting run: the mix (or `feed`) into every hall's input chest, run to the end, judged per hall. */
async function stRun(run, { halls = HALLS, sprint = true, feed = null, extra = null } = {}) {
  const specs = feed ?? mixSpecs();
  const fed = {};
  for (const [s, n] of specs) fed[key(s)] = (fed[key(s)] ?? 0) + n;
  const before = {};
  for (const h of halls) before[h.name] = await hallState(h);
  for (const h of halls) { const { w } = hallAt(h); for (let i = 0; i < specs.length; i++) await put(...w(0, 3, 0), i, specs[i][0], specs[i][1]); }
  const was = SPRINT;
  SPRINT = sprint;
  let t = 0;
  const end = {};
  while (t < 20000 && Object.keys(end).length < halls.length) {
    t += await advance(200);
    for (const h of halls) {
      if (end[h.name] !== undefined) continue;
      const { w } = hallAt(h);
      let busy = total(await items(...w(0, 3, 0)));
      for (let u = 0; u < SLOTS && !busy; u++) busy += total(await items(...w(u, 2, 0)));
      if (!busy) end[h.name] = t;
    }
  }
  await advance(200); // let D finish its last pulls and pushes
  SPRINT = was;
  const out = { run, ticks: t, halls: [] };
  for (const h of halls) {
    const s = await hallState(h);
    const b = before[h.name];
    const why = [];
    // what this run added to each container (the chests start empty, F and D as they were)
    const delta = (a, z) => { const o = {}; for (const k of new Set([...Object.keys(a), ...Object.keys(z)])) { const d = (a[k] ?? 0) - (z[k] ?? 0); if (d) o[k] = d; } return o; };
    const slotGot = s.chest.map((c, u) => merge(delta(c, b.chest[u]), delta(s.D[u], b.D[u])));
    const overGot = delta(s.overflow, b.overflow);
    if (total(s.input)) why.push(`input holds ${JSON.stringify(s.input)}`);
    s.line.forEach((l, u) => { if (total(l)) why.push(`line ${u} holds ${JSON.stringify(l)}`); });
    const expect = extra ? extra(h, fed) : null;
    for (let u = 0; u < SLOTS; u++) {
      const f = `minecraft:${FILTERS[u]}`;
      for (const [k, n] of Object.entries(slotGot[u])) if (k !== f && n > 0) why.push(`slot ${u} got ${n} ${k}`);
      if (!expect && (slotGot[u][f] ?? 0) !== (fed[f] ?? 0)) why.push(`slot ${u} got ${slotGot[u][f] ?? 0} of ${fed[f] ?? 0} ${f}`);
      if (!expect && !fExact(s.F[u], u)) why.push(`F${u} holds ${JSON.stringify(s.F[u])}`);
    }
    if (!expect) for (const [k, n] of Object.entries(fed)) { if (FILTERS.some((f) => `minecraft:${f}` === k)) continue; if ((overGot[k] ?? 0) !== n) why.push(`overflow got ${overGot[k] ?? 0} of ${n} ${k}`); }
    for (const [k, n] of Object.entries(overGot)) if (FILTERS.some((f) => `minecraft:${f}` === k) && n > 0 && !expect) why.push(`overflow got ${n} ${k}`);
    const { w } = hallAt(h);
    const d = await dropped(...w(2, 1, -1), 8);
    if (d.stacks) why.push(`${d.stacks} item entities`);
    const outN = total(merge(...slotGot)) + total(overGot) + total(delta(merge(...s.F), merge(...b.F))) + total(s.input) + total(merge(...s.line)) - total(b.input) - total(merge(...b.line));
    const fedN = total(fed);
    if (outN !== fedN) why.push(`conservation: ${outN} accounted of ${fedN} fed`);
    if (expect) why.push(...expect.check(s, slotGot, overGot));
    const ticks = end[h.name] ?? null;
    out.halls.push({ run, hall: h.name, sorted: why.length === 0, why, items: fedN, ticks, itemsPerSecond: ticks ? (fedN / ticks) * 20 : null, counts: { chest: slotGot, overflow: overGot }, F: s.F, signals: await signals(h) });
  }
  // empty the slot chests and the overflow for the next run (F and D stay as they are)
  for (const h of halls) { const { w } = hallAt(h); for (let u = 0; u < SLOTS; u++) await clearC(...w(u, 0, 1)); await clearC(...w(SLOTS, 2, 0)); }
  log(`  ST run ${run}: ${out.halls.map((x) => `${x.hall} ${x.sorted ? 'sorted' : 'FAIL ' + x.why.slice(0, 3).join('; ')} ${x.itemsPerSecond?.toFixed(3)} ips`).join(' | ')}`);
  return out;
}
async function signals(h) {
  const { w } = hallAt(h);
  const o = [];
  for (let u = 0; u < SLOTS; u++) o.push(Number(/data: (\d+)/.exec((await cmd(`/data get block ${w(u, 1, -1).join(' ')} OutputSignal`)).text)?.[1]));
  return o;
}

P.ST = async () => {
  await buildHalls();
  const out = { runs: [] };
  for (let r = 0; r < RUNS_ST; r++) out.runs.push(await stRun(r));
  out.st3 = [];
  for (const h of HALLS) { const s = await hallState(h); out.st3.push({ hall: h.name, exact: s.F.every((f, u) => fExact(f, u)), F: s.F }); }
  // ST4: slots 1 and 2 saturated (chest and D full of their own items), one mix, then emptied and another mix
  out.st4 = [];
  for (const h of HALLS) {
    const { w } = hallAt(h);
    for (const u of [1, 2]) { for (let s = 0; s < 27; s++) await put(...w(u, 0, 1), s, FILTERS[u], 64); for (let s = 0; s < 5; s++) await put(...w(u, 0, 0), s, FILTERS[u], 64); }
  }
  const sat = await stRun('sat', {
    extra: () => ({
      check: (s, slotGot, overGot) => {
        const why = [];
        for (let u = 0; u < SLOTS; u++) for (const [k, n] of Object.entries(slotGot[u])) if (k !== `minecraft:${FILTERS[u]}` && n > 0) why.push(`slot ${u} got ${n} ${k}`);
        for (const u of [0, 3]) if ((slotGot[u][`minecraft:${FILTERS[u]}`] ?? 0) !== 192) why.push(`slot ${u} got ${slotGot[u][`minecraft:${FILTERS[u]}`] ?? 0} of 192`);
        for (const u of [1, 2]) { const inF = s.F[u][`minecraft:${FILTERS[u]}`] ?? 0; const got = (slotGot[u][`minecraft:${FILTERS[u]}`] ?? 0) + (inF - 41) + (overGot[`minecraft:${FILTERS[u]}`] ?? 0); if (got !== 192) why.push(`slot ${u}: ${got} of 192 accounted (chest+D ${slotGot[u][`minecraft:${FILTERS[u]}`] ?? 0}, F ${inF}, overflow ${overGot[`minecraft:${FILTERS[u]}`] ?? 0})`); }
        return why;
      },
    }),
  });
  // the saturated run: conservation and wrong items come from the generic checks; its signals must stay <= 3
  for (const hs of sat.halls) {
    const why = hs.why.filter((x) => !/^F[12] holds/.test(x));
    out.st4.push({ hall: hs.hall, saturation: { ok: why.length === 0 && hs.signals.every((x) => x <= 3), why, signals: hs.signals, F: hs.F } });
  }
  // clear slots 1 and 2 (chests), let D drain F back, then a normal mix
  for (const h of HALLS) { const { w } = hallAt(h); for (const u of [1, 2]) await clearC(...w(u, 0, 1)); }
  await advance(2000);
  for (const h of HALLS) { const { w } = hallAt(h); for (const u of [1, 2]) await clearC(...w(u, 0, 1)); }
  const rec = await stRun('recovery');
  for (const hs of rec.halls) { const e = out.st4.find((x) => x.hall === hs.hall); e.recovery = { ok: hs.sorted, why: hs.why, F: hs.F }; }
  // ST5: the filler itself on the line
  const st5 = await stRun('filler', { feed: [[FILLER, 64]], extra: () => ({ check: () => [] }) });
  out.st5 = st5.halls.map((hs) => ({ hall: hs.hall, counts: hs.counts, F: hs.F, signals: hs.signals, fIntact: hs.F.map((f, u) => fExact(f, u)) }));
  // ST6: the slot chests stayed single
  out.st6 = [];
  for (const h of HALLS) {
    const { w } = hallAt(h);
    const states = [];
    for (let u = 0; u < SLOTS; u++) { const p = w(u, 0, 1); states.push((await dump(...p, ...p))(...p)); }
    out.st6.push({ hall: h.name, ok: states.every((s) => prop(s, 'type') === 'single'), states });
  }
  out.hrItemsPerSecond = (() => { const d = loadData(); const tpi = d.probes?.HR?.raw?.runs?.map((x) => x.ticksPerItem).filter(Boolean); return tpi?.length ? 20 / mean(tpi) : null; })();
  return out;
};

// ------------------------------------------------------------------ main

const data = loadData();
const scriptSha = crypto.createHash('sha256').update(fs.readFileSync(fileURLToPath(import.meta.url))).digest('hex');
const head = execFileSync('git', ['-C', ROOT, 'rev-parse', 'HEAD']).toString().trim();
let todo = ONLY ?? ORDER;
const assume = flag('--assume-sprint');
if (!todo.includes('SE') && !assume && !flag('--no-se')) todo = ['SE', ...todo]; // --no-se: a smoke run only (real time, never the committed result)
let failed = false;
try {
  await startClient();
  const seed = (await tryCmd('/seed')).text;
  const at = await dump(C.x, 40, C.z, C.x, 100, C.z);
  for (let y = 100; y >= 40; y--) if (at(C.x, y, C.z) !== 'minecraft:air') { B = y; break; }
  log(`ground at y=${B}; ${seed}`);
  await rules();
  await home();
  await cmd('/tick freeze');
  if (flag('--sprint-smoke')) { if (!flag('--out')) throw new Error('--sprint-smoke needs --out (never the committed result)'); SPRINT = true; }
  if (assume) {
    const se = data.probes?.SE?.raw ? JUDGES.SE(data.probes.SE.raw) : [];
    if (se.length < 2 || se.some((x) => !x.pass)) throw new Error('--assume-sprint: the committed SE has not passed');
    SPRINT = true;
  }
  for (const id of ORDER.filter((x) => todo.includes(x))) {
    log(`== ${id}`);
    const t0 = Date.now();
    const gt0 = await gameTime();
    const r = await P[id]();
    const checks = JUDGES[id](r);
    data.probes[id] = { raw: r, at: new Date().toISOString(), wallSeconds: (Date.now() - t0) / 1000, gameTicks: (await gameTime()) - gt0, sprinted: id === 'SE' ? 'partly (its own comparison)' : SPRINT };
    data.meta = { minecraft: '26.3', architect: /architect_version=(.*)/.exec(fs.readFileSync(path.join(ROOT, 'mod', 'gradle.properties'), 'utf8'))?.[1], stewardHead: head, script: 'tools/module-probe.mjs', scriptSha256: scriptSha, world: flag('--attach') ? '(attached)' : WORLD_NAME, seed, ground: B, port: PORT, runsG: RUNS_G, runsSt: RUNS_ST, updated: new Date().toISOString() };
    fs.writeFileSync(OUT_JSON, `${JSON.stringify(data, null, 1)}\n`);
    printTable(checks);
    if (id === 'SE') {
      SPRINT = checks.length === 2 && checks.every((x) => x.pass);
      log(SPRINT ? '  SE passed: sprinting from here on' : '  SE did not pass: every later wait runs in real time');
    }
  }
} catch (e) {
  failed = true;
  console.error(e?.stack ?? e);
} finally {
  if (dev) await tryCmd('/tick unfreeze');
  await stopClient().catch(() => {});
}
const table = judgeAll(loadData());
log('== verdict');
printTable(table);
process.exit(failed || table.some((x) => x.pass === false) ? 1 : 0);
