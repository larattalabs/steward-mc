#!/usr/bin/env node
// Phase 5 slice M2 (docs/MEASURE-M2.md, docs/MODULES.md 3.1 as amended by section 9): crop_farm@1 measured in a dev client, $0, no Claude.
// It launches its own client through tools/run-e2e-client.sh on 8594/8595 (STEWARD_PORT / STEWARD_DEV_PORT) in a fresh flat world, builds a
// V town (Architect's flat_village, no irrigation), installs the catalog entry at $0 by its key (Steward's own ModuleInstalls, through
// `/steward dev module`), grows the town by irrigated crop_farm lots (planRevision grow lots from Steward's CropFarmLot: `irrigate`, `field`),
// makes each lot's keyed variant from the catalog install with the lot's IRR cells reserved, realises the growth and measures.
//
//   node tools/module-measure.mjs look            the geometry: fronts, chunk borders, the reserved cells open, irrigationDropped, the field
//   node tools/module-measure.mjs bands           the probe-derived bars of item 3 (no client): tools/lib/crop-farm-bars.mjs
//   node tools/module-measure.mjs <step> ...      the measurement steps (docs/MEASURE-M2.md); `all` runs them in order
//   --attach        drive a client already on STEWARD_DEV_PORT (default 8595); --keep-client leaves the launched one running
//   --catalog <dir> the catalog root (default modules)
//
// Evidence: artifacts/m2/<step>.json (gitignored) and stdout ("ok"/"FAIL" per check). Exit code 1 when a check fails.
import { spawn, execFileSync } from 'node:child_process';
import net from 'node:net';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { CROPS, T as TCYCLE, CYCLES, DAY, USEFUL_PER_CROP_DAY, bands as barBands } from './lib/crop-farm-bars.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAIN_CHECKOUT = path.resolve(execFileSync('git', ['-C', ROOT, 'rev-parse', '--git-common-dir']).toString().trim(), '..');
const ARCH = process.env.ARCHITECT_CHECKOUT ?? path.resolve(MAIN_CHECKOUT, '..', 'architect-mc');
const GAME = path.join(ROOT, 'mod', 'run');
process.env.ARCHITECT_GAME_DIR = GAME;
const PORT = Number(process.env.STEWARD_DEV_PORT ?? 8595);
const ART = path.join(ROOT, 'artifacts', 'm2');
fs.mkdirSync(ART, { recursive: true });

const argv = process.argv.slice(2);
const step = argv[0];
const flag = (f) => argv.includes(f);
const opt = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
const CATALOG = path.resolve(ROOT, opt('--catalog', 'modules'));
const MODULE = { id: 'crop_farm', version: 1 };
const DIR = path.join(CATALOG, MODULE.id, String(MODULE.version));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const json = (o) => JSON.stringify(o);
const k3 = (c) => `${c[0]},${c[1]},${c[2]}`;
/** The evidence's name: field-<crop>, skin-<bible>, or the step. */
const LABEL = step === 'field' ? `field-${opt('--crop')}` : step === 'skin' ? `skin-${opt('--bible')}` : step;
const logFile = path.join(ART, `${LABEL ?? 'none'}.log`);
const log = (m) => { console.log(m); fs.appendFileSync(logFile, `${m}\n`); };
const checks = [];
function check(ok, what, data) {
  checks.push({ ok: !!ok, what, ...(data === undefined ? {} : { data }) });
  log(`${ok ? 'ok  ' : 'FAIL'} ${what}`);
  return ok;
}
const prop = (k) => (new RegExp(`^${k}=(.*)$`, 'm').exec(fs.readFileSync(path.join(ROOT, 'mod', 'gradle.properties'), 'utf8')) ?? [])[1]?.trim();
const ARCH_VERSION = prop('architect_version');
const MC_VERSION = prop('minecraft_version');
const fileSha = (f) => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const stamp = () => ({ programSha: fileSha(path.join(DIR, 'program.mjs')), moduleJsonSha: fileSha(path.join(DIR, 'module.json')) });
const MODULE_JSON = JSON.parse(fs.readFileSync(path.join(DIR, 'module.json'), 'utf8'));

// ------------------------------------------------------------------ the program's own layout (the program run on the pinned kit)

let programMod = null;
/** The catalog program imported on Architect's kit at the pinned tag (artifacts/m1/kit-<ver>, as module-catalog.mjs offline archives it). */
async function program() {
  if (programMod) return programMod;
  const kitRoot = path.join(ROOT, 'artifacts', 'm1', `kit-${ARCH_VERSION}`);
  if (!fs.existsSync(path.join(kitRoot, 'kit', 'build.mjs'))) {
    fs.mkdirSync(kitRoot, { recursive: true });
    const tar = execFileSync('git', ['-C', ARCH, 'archive', `v${ARCH_VERSION}`, 'kit'], { maxBuffer: 1 << 30 });
    execFileSync('tar', ['-x', '-C', kitRoot], { input: tar });
  }
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'steward-m2-'));
  const lib = pathToFileURL(path.join(kitRoot, 'kit', 'lib')).href;
  fs.writeFileSync(path.join(dir, 'crop_farm.mjs'), fs.readFileSync(path.join(DIR, 'program.mjs'), 'utf8').replaceAll("'../lib/", `'${lib}/`));
  programMod = await import(pathToFileURL(path.join(dir, 'crop_farm.mjs')).href);
  return programMod;
}

/** IRR's lot frame (kit/lib/fluids/irrigate.mjs lotFrame): {size, toWorld(u, y, v), toFrame(x, y, z)} for a plan lot {box, front, floorY}. */
export function lotFrame(lot) {
  const b = Array.isArray(lot.box) ? { minX: lot.box[0], minY: lot.box[1], minZ: lot.box[2], maxX: lot.box[3], maxY: lot.box[4], maxZ: lot.box[5] } : lot.box;
  const f = lot.front ?? 'south', fy = lot.floorY ?? b.minY;
  const W = b.maxX - b.minX + 1, D = b.maxZ - b.minZ + 1;
  const T = {
    south: { size: [W, D], to: (x, z) => [x - b.minX, z - b.minZ], from: (u, v) => [b.minX + u, b.minZ + v] },
    north: { size: [W, D], to: (x, z) => [b.maxX - x, b.maxZ - z], from: (u, v) => [b.maxX - u, b.maxZ - v] },
    east: { size: [D, W], to: (x, z) => [b.maxZ - z, x - b.minX], from: (u, v) => [b.minX + v, b.maxZ - u] },
    west: { size: [D, W], to: (x, z) => [z - b.minZ, b.maxX - x], from: (u, v) => [b.maxX - v, b.minZ + u] },
  }[f];
  return { front: f, size: T.size, floorY: fy, box: b, toFrame: (x, y, z) => { const [u, v] = T.to(x, z); return [u, y - fy, v]; }, toWorld: (u, y, v) => { const [x, z] = T.from(u, v); return [x, y + fy, z]; } };
}
const runsCells = (runs) => { const out = []; for (const [a, c, y0, y1] of runs ?? []) for (let y = y0; y <= y1; y++) out.push([a, y, c]); return out; };

// ------------------------------------------------------------------ the client

let dev = null, child = null, launched = false, exited = false;
const listening = (port) => new Promise((resolve) => {
  const s = net.connect({ host: '127.0.0.1', port });
  s.once('connect', () => { s.destroy(); resolve(true); });
  s.once('error', () => resolve(false));
});
function claimRun() {
  const f = path.join(GAME, 'steward-module-measure.lock');
  fs.mkdirSync(GAME, { recursive: true });
  for (let i = 0; i < 2; i++) {
    try {
      fs.writeFileSync(f, String(process.pid), { flag: 'wx' });
      process.on('exit', () => { try { if (fs.readFileSync(f, 'utf8') === String(process.pid)) fs.rmSync(f); } catch { /* gone */ } });
      return;
    } catch (err) {
      if (err.code !== 'EEXIST') throw err;
      const pid = Number(fs.readFileSync(f, 'utf8'));
      let alive = false;
      try { process.kill(pid, 0); alive = true; } catch { /* gone */ }
      if (alive) throw new Error(`another module-measure run (pid ${pid}) holds ${f}: refusing to launch`);
      fs.rmSync(f, { force: true });
    }
  }
  throw new Error(`could not claim ${f}`);
}
let worldName = null;
async function startClient(label, { mode = 'creative' } = {}) {
  const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);
  if (flag('--attach')) { dev = await DevClient.connect({ port: PORT, timeoutMs: 30_000 }); await dev.waitInWorld({ timeoutMs: 60_000 }); return; }
  claimRun();
  for (const p of [PORT - 1, PORT]) if (await listening(p)) throw new Error(`port ${p} is in use (another client?): refusing to launch; use --attach to drive it`);
  if (fs.existsSync(path.join(GAME, 'architect', 'devbridge.token'))) fs.rmSync(path.join(GAME, 'architect', 'devbridge.token'));
  if (fs.existsSync(path.join(GAME, 'architect', 'sidecar-data', 'secrets.json'))) throw new Error('the claude-login opt-in exists: refusing');
  // every field measured sits inside the player's simulation distance (random ticks run in block-ticking chunks only): 16 chunks
  const opts = path.join(GAME, 'options.txt');
  if (fs.existsSync(opts)) fs.writeFileSync(opts, fs.readFileSync(opts, 'utf8').replace(/^simulationDistance:\d+$/m, `simulationDistance:${SIM_DISTANCE}`));
  else fs.writeFileSync(opts, `simulationDistance:${SIM_DISTANCE}\n`);
  worldName = `Steward M2 ${label} ${new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')}`;
  const out = fs.openSync(path.join(ART, 'client.log'), 'a');
  child = spawn(path.join(ROOT, 'tools', 'run-e2e-client.sh'), [], {
    stdio: ['ignore', out, out],
    env: { ...process.env, STEWARD_PORT: String(PORT - 1), STEWARD_DEV_PORT: String(PORT), ARCHITECT_AUTOWORLD_NAME: worldName, ARCHITECT_AUTOWORLD_MODE: mode, E2E_STUB: '0', E2E_NODE_DIR: path.dirname(process.execPath), GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? path.join(MAIN_CHECKOUT, '.gradle-home') },
  });
  launched = true;
  child.once('exit', () => { exited = true; });
  log(`client launched (launcher pid ${child.pid}), world "${worldName}", ports ${PORT - 1}/${PORT}`);
  const died = new Promise((_, reject) => child.once('exit', (code) => reject(new Error(`the launched client exited (code ${code}) before its bridge answered: see artifacts/m2/client.log`))));
  died.catch(() => {});
  dev = await Promise.race([DevClient.connect({ port: PORT, timeoutMs: 900_000 }), died]);
  await dev.waitInWorld({ timeoutMs: 900_000 });
}
async function stopClient() {
  if (!dev || !launched || exited || flag('--attach') || flag('--keep-client')) return;
  const r = await dev.request('dev.quit');
  if (r.ok) await dev.waitClosed(180_000);
}
const call = (type, payload = {}, timeoutMs = 120_000) => dev.call(type, payload, { timeoutMs });
const req = (type, payload = {}, timeoutMs = 120_000) => dev.request(type, payload, { timeoutMs });
const cmd = async (c) => { const r = await req('dev.command', { cmd: c }); return r; };
let nmod = 0;
/** One `/steward dev module` request; its answer from <gameDir>/steward-modules/<label>.json. */
async function mod(r, timeoutMs = 900_000) {
  const lab = `m2_${process.pid}_${++nmod}`;
  const f = path.join(GAME, 'steward-modules', `${lab}.json`);
  const a = await req('dev.command', { cmd: `/steward dev module ${lab} ${JSON.stringify(r)}` }, 120_000);
  if (!a.ok) throw new Error(`module ${r.op}: ${a.error}`);
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (fs.existsSync(f)) {
      try { const o = JSON.parse(fs.readFileSync(f, 'utf8')); fs.rmSync(f, { force: true }); return o; } catch { /* still being written */ }
    }
    await sleep(200);
  }
  throw new Error(`module ${r.op}: no answer in ${timeoutMs / 1000} s`);
}
/** Cells read in chunks of 4096: [[block, age, moisture, blockLight] | null]. */
async function readCells(cells) {
  const out = [];
  for (let i = 0; i < cells.length; i += 4096) {
    const a = await mod({ op: 'cells', cells: cells.slice(i, i + 4096) });
    if (a.error) throw new Error(`cells: ${a.error}`);
    out.push(...a.cells);
  }
  return out;
}
async function gameTime() { return (await mod({ op: 'cells', cells: [] })).gameTime; }

// ------------------------------------------------------------------ the world, the town and the farms

const OWNER = 'steward_mc:measure';
const SIM_DISTANCE = 16;
/** Frozen time and weather, no mob spawning, random ticks at vanilla's 3 (M0's conventions). */
async function rules({ ticks = 3 } = {}) {
  for (const r of [`random_tick_speed ${ticks}`, 'mob_griefing true', 'advance_time false', 'advance_weather false', 'spawn_mobs false', 'spawn_monsters false', 'fire_spread_radius_around_player 0']) await cmd(`/gamerule ${r}`);
  await cmd('/weather clear');
  await cmd('/time set 6000');
  await cmd('/difficulty peaceful');
}
async function groundY(x, z) {
  const cells = [];
  for (let y = -64; y <= 120; y++) cells.push([x, y, z]);
  let r = await readCells(cells);
  for (let i = 0; i < 120 && r.some((c) => c === null); i++) { await sleep(500); r = await readCells(cells); }
  if (r.some((c) => c === null)) throw new Error(`groundY: the column ${x},${z} never loaded`);
  let g = -64;
  r.forEach((c, i) => { if (c && c[0] !== 'minecraft:air') g = cells[i][1]; });
  return g;
}
async function waitRegion(region, timeoutMs = 3_600_000) {
  const t0 = Date.now();
  let st;
  while (Date.now() - t0 < timeoutMs) {
    st = await call('dev.region.state', { region }, 60_000);
    if (['PLACED', 'PARTIAL', 'FAILED'].includes(st.view.state)) return st;
    await sleep(2000);
  }
  throw new Error(`region ${region} did not finish (${st?.view?.state})`);
}
async function rvWait(region, rid, timeoutMs = 3_600_000) {
  const t0 = Date.now();
  let st;
  while (Date.now() - t0 < timeoutMs) {
    st = await call('dev.rv.state', { region }, 60_000);
    const r = st.revisions.find((x) => x.rid === rid);
    if (st.busy === null && r && !['ACCEPTED', 'PLACING'].includes(r.state)) return { st, r };
    await sleep(2000);
  }
  throw new Error(`revision ${rid} of ${region} did not end (${json(st?.revisions?.map((x) => [x.rid, x.state]))})`);
}
/** A V town (flat_village, pads only, no irrigation) on a claim at (cx, cz): {region, claim, gy}. */
async function town(cx, cz, { w = 224, d = 176, lots = 6, orientation = 'ew' } = {}) {
  await cmd(`/tp @p ${cx + w / 2} 0 ${cz + d / 2}`);
  await call('dev.waitChunks', { timeoutMs: 120_000 }, 180_000).catch(() => null);
  const gy = await groundY(cx + w / 2, cz + d / 2);
  await cmd(`/tp @p ${cx + w / 2} ${gy + 30} ${cz + d / 2}`);
  const claim = [cx, cz, cx + w - 1, cz + d - 1];
  const townLots = [{ id: 'hall_1', type: 'hall', size: [13, 11], landmark: true }, ...Array.from({ length: lots - 1 }, (_, i) => ({ id: `cottage_${i + 1}`, type: 'cottage', size: [9, 9] }))];
  const p = await call('dev.region.plan', { program: 'flat_village', claim, params: { lots: townLots, orientation }, surveyLoad: 'bounded:256', check: false, owner: OWNER }, 900_000);
  if (p.refused) throw new Error(`town plan refused: ${p.refused}`);
  const r = await call('dev.region.realise', { planId: p.planId, load: 'bounded:512', lots: {} }, 300_000);
  if (r.refused) throw new Error(`town realise refused: ${json(r.refused)}`);
  const st = await waitRegion(r.region);
  check(st.view.state === 'PLACED', `a V town ${r.region} (flat_village ${orientation}, ${townLots.length} pads, no irrigation) at ${claim.join(',')}: ${st.view.state}`);
  return { region: r.region, claim, gy };
}
let installed = null;
async function install() {
  if (installed) return installed;
  const a = await mod({ op: 'install', dir: CATALOG, id: MODULE.id, version: MODULE.version, measuring: true });
  check(a.ok && a.entryId, `${MODULE.id}@${MODULE.version} installed at $0 by its key ${a.key} as ${a.entryId}${a.ok ? '' : `: ${a.detail} ${a.message}`}`);
  if (!a.ok) throw new Error('install failed');
  installed = a;
  return a;
}
let run36 = Date.now().toString(36);
/**
 * Grows `region` by crop farms `specs` [{id, width, depth, crop, bible}] in one revision: Steward's grow lots (CropFarmLot), the plan's
 * IRR record per lot, a keyed variant per lot (its IRR cells reserved), realised. Returns {rid, planId, farms: [{...spec, lot, irr, frame,
 * entryId, variant, siteId}], refused?}.
 */
async function growFarms(region, specs, { prefer = 'frontage', survival = false } = {}) {
  const lots = [];
  const P = await program();
  for (const s of specs) {
    const lot = (await mod({ op: 'growLot', id: s.id, width: s.width, depth: s.depth })).lot;
    // Steward's lot spec (Java) and the program's own layout name the same field cells
    const want = P.layout(s.width, s.depth).field.map(([u, v]) => `${u},0,${v}`).sort().join(';');
    check(lot.field.cells.map((c) => c.join(',')).sort().join(';') === want, `${s.id}: CropFarmLot's field cells = the program's layout (${lot.field.cells.length})`);
    lots.push(lot);
  }
  let p = null;
  for (const pr of [prefer, prefer === 'frontage' ? 'extend' : 'frontage']) {
    p = await req('dev.rv.plan', { region, program: 'flat_village', params: { grow: { lots, prefer: pr } }, opKey: `steward.m2${run36}.${specs[0].id}.plan` }, 900_000);
    if (p.ok && p.planId) break;
    log(`  grow (${pr}) refused: ${String(p.refused ?? p.error).slice(0, 300)}`);
  }
  if (!p?.planId) return { refused: p?.refused ?? p?.error };
  const farms = [];
  const ents = {};
  for (const s of specs) {
    const lot = (p.lots ?? []).find((l) => l.id === s.id);
    const irr = lot?.ext?.['architect_mc:lotIrrigation'];
    if (!lot || !irr) return { refused: `no lot or no irrigation record for ${s.id}: ${json(lot).slice(0, 300)}` };
    const frame = lotFrame(lot);
    const cells = runsCells(irr.lotCells);
    const reserved = { front: lot.front, size: irr.frame, cells };
    const v = await mod({ op: 'variant', dir: CATALOG, id: MODULE.id, version: MODULE.version, measuring: true, params: { width: s.width, depth: s.depth, crop: s.crop },
      bible: { id: s.bible, version: 0 }, reserved, key: `steward.m2${run36}.module.${s.id}` });
    if (!v.ok) return { refused: `variant of ${s.id}: ${v.detail} ${v.message} ${json(v.findings)}` };
    ents[s.id] = v.entryId;
    farms.push({ ...s, lot, irr, frame, entryId: v.entryId, variant: v });
  }
  const quote = survival ? await req('dev.region.quote', { planId: p.planId, lotEntries: ents }, 300_000) : null;
  const r = await req('dev.region.realise', { planId: p.planId, opKey: `steward.m2${run36}.${specs[0].id}.realise`, lots: ents }, 300_000);
  if (!r.ok || r.refused) return { refused: `realise: ${r.refused ?? r.error}` };
  return { rid: p.revisionOf.rid, planId: p.planId, position: p.revisionOf.position, farms, quote, plan: p };
}
/** After the growth is PLACED: each farm's site id, its cells by role in world coordinates. */
async function farmCells(region, farms) {
  const P = await program();
  const st = await call('dev.region.state', { region }, 60_000);
  for (const f of farms) {
    const lv = st.view.lots.find((l) => l.id === f.id);
    f.siteId = lv?.siteId ?? null;
    f.state = lv?.state ?? null;
    const L = P.layout(f.width, f.depth);
    const F = f.frame;
    f.L = L;
    f.farmland = L.field.map(([u, v]) => F.toWorld(u, 0, v));
    f.crops = L.field.map(([u, v]) => F.toWorld(u, 1, v));
    f.sources = L.sources.map(([u, v]) => F.toWorld(u, 0, v));
    f.posts = L.posts.map(([u, v]) => F.toWorld(u, 1, v));
    // every cell of the lot at floor + 1 that is not a design block: the crop cells, the front row's open cells, the cells over the sources
    const open = [];
    for (let v = 0; v < L.D; v++) for (let u = 0; u < L.W; u++) open.push(F.toWorld(u, 1, v));
    f.floor1 = open;
    const xs = [F.box.minX, F.box.maxX], zs = [F.box.minZ, F.box.maxZ];
    f.chunkBorder = { x: Math.floor(xs[0] / 16) !== Math.floor(xs[1] / 16), z: Math.floor(zs[0] / 16) !== Math.floor(zs[1] / 16) };
  }
  return farms;
}

// ------------------------------------------------------------------ look: the geometry (not a measurement)

async function look() {
  await startClient('Look');
  const out = {};
  try {
    await rules();
    if (flag('--tall')) {
      await install();
      const T2 = await town(24000, 20000, { w: 176, d: 224, orientation: 'ns' });
      const g3 = await growFarms(T2.region, [0, 1, 2].map((i) => ({ id: `farm_t${i}`, width: 9, depth: 9, crop: 'wheat', bible: 'rustic' })));
      if (!g3.refused) { const w3 = await rvWait(T2.region, g3.rid); log(`  tall town growth: ${w3.r.state}, fronts ${json(g3.farms.map((f) => f.lot.front))}`); } else log(`  tall: ${g3.refused}`);
      return out;
    }
    const T = await town(20000, 20000);
    await install();
    const specs = [
      { id: 'farm_a', width: 9, depth: 9, crop: 'wheat', bible: 'rustic' },
      { id: 'farm_b', width: 18, depth: 27, crop: 'carrots', bible: 'fortress' },
      { id: 'farm_c', width: 27, depth: 9, crop: 'beetroots', bible: 'desert' },
    ];
    const g = await growFarms(T.region, specs);
    check(!g.refused, `the growth planned and realised: ${g.refused ?? `rid ${g.rid}`}`);
    if (g.refused) return out;
    const w = await rvWait(T.region, g.rid);
    check(w.r.state === 'PLACED', `the growth's revision ${w.r.rid}: ${w.r.state}`);
    await farmCells(T.region, g.farms);
    const sites = await call('dev.sites.state', {}, 60_000);
    out.farms = [];
    for (const f of g.farms) {
      const site = [...(sites.sites ?? []), ...(sites.placed ?? [])].find((s) => s.id === f.siteId);
      const crops = await readCells(f.crops), land = await readCells(f.farmland), posts = await readCells(f.posts);
      const over = await readCells(f.sources.map(([x, y, z]) => [x, y + 1, z]));
      const src = await readCells(f.sources);
      const light = await readCells(f.floor1);
      const r = {
        id: f.id, front: f.lot.front, box: f.lot.box, floorY: f.lot.floorY, chunkBorder: f.chunkBorder, siteId: f.siteId, state: f.state,
        dropped: site?.ext?.['architect_mc:irrigationDropped'] ?? null, field: site?.ext?.['architect_mc:field'] ? 'present' : 'absent',
        crops: [crops.filter((c) => c?.[0] === CROPS[f.crop].block).length, f.crops.length], farmland7: [land.filter((c) => c?.[0] === 'minecraft:farmland' && c[2] === 7).length, f.farmland.length],
        posts: [...new Set(posts.map((c) => c?.[0]))], sourcesWater: src.filter((c) => c?.[0] === 'minecraft:water').length, overSourcesAir: over.filter((c) => c?.[0] === 'minecraft:air').length,
        minLight: Math.min(...light.filter((c) => c && c[0] !== 'minecraft:water').map((c) => c[3])),
        notSeven: land.map((c, i) => [f.farmland[i], c]).filter(([, c]) => !(c?.[0] === 'minecraft:farmland' && c[2] === 7)).slice(0, 8),
      };
      const ver = await req('dev.site.verify', { site: f.siteId, list: true, max: 20 }, 120_000);
      r.verify = json(ver).slice(0, 1500);
      out.farms.push(r);
      log(`  ${json(r)}`);
    }
    // more growths, for the fronts: small farms on the frontage, then on an extension
    out.fronts = g.farms.map((f) => f.lot.front);
    for (const [k, prefer] of flag('--fronts') ? [['d', 'frontage'], ['e', 'extend'], ['f', 'extend']] : []) {
      const sp = [0, 1, 2].map((i) => ({ id: `farm_${k}${i}`, width: 9, depth: 9, crop: 'potatoes', bible: 'rustic' }));
      const g2 = await growFarms(T.region, sp, { prefer });
      if (g2.refused) { log(`  growth ${k} (${prefer}) refused: ${String(g2.refused).slice(0, 300)}`); continue; }
      const w2 = await rvWait(T.region, g2.rid);
      log(`  growth ${k} (${prefer}): ${w2.r.state}, fronts ${json(g2.farms.map((f) => f.lot.front))}, boxes ${json(g2.farms.map((f) => f.lot.box))}`);
      out.fronts.push(...g2.farms.map((f) => f.lot.front));
    }
    check(true, `fronts seen: ${json(out.fronts)}`);
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

// ------------------------------------------------------------------ the frozen game, the harvest and the replant

const tick = {
  frozen: false,
  async freeze() { await cmd('/tick freeze'); this.frozen = true; },
  /** Advance n ticks with the game frozen around it (M0's advance): /tick sprint n, then wait until the game time moved n. */
  async advance(n) {
    const t0 = await gameTime();
    await cmd(`/tick sprint ${n}`);
    const end = Date.now() + Math.max(300_000, n * 20);
    for (;;) {
      const t = await gameTime();
      if (t - t0 >= n) {
        for (let i = 0; i < 200; i++) { const q = await cmd('/tick query'); if (/frozen/i.test(String(q?.text ?? (q?.messages ?? []).join(' ')))) break; await sleep(25); }
        return (await gameTime()) - t0;
      }
      if (Date.now() > end) { await cmd('/tick sprint stop'); throw new Error(`advance of ${n} stuck at ${t - t0}`); }
      await sleep(n > 2000 ? 250 : 25);
    }
  },
};
const PLACER = 'M2Farmer';
/** The player breaks these cells (destroyBlock: PT sees the player's break), in batches. */
async function breakCells(cells) {
  for (let i = 0; i < cells.length; i += 1024) await call('dev.pt.breaks', { at: cells.slice(i, i + 1024), placer: PLACER }, 900_000);
}
/** The player plants `item` on each farmland cell below these crop cells (useItemOn, face up). Returns how many took. */
async function plant(item, cropCells) {
  let n = 0;
  for (let i = 0; i < cropCells.length; i += 1024) {
    const ops = cropCells.slice(i, i + 1024).map(([x, y, z]) => ({ item, at: [x, y, z], on: [x, y - 1, z], face: 'up', placer: PLACER }));
    const r = await call('dev.pt.uses', { ops }, 900_000);
    n += (r.results ?? []).filter((o) => /minecraft:(wheat|carrots|potatoes|beetroots)/.test(String(o.state ?? ''))).length;
  }
  return n;
}
/** The farm's box (the lot's, floor - 1 .. floor + 3) for counting drops. */
const dropBox = (f) => [f.frame.box.minX - 1, f.frame.floorY - 1, f.frame.box.minZ - 1, f.frame.box.maxX + 1, f.frame.floorY + 4, f.frame.box.maxZ + 1];
/** Harvest one farm: the player breaks every mature crop, the drops are counted and taken away. {broken, cells, items}. */
async function harvest(f) {
  const c = CROPS[f.crop];
  const max = f.crop === 'beetroots' ? 3 : 7;
  const r = await readCells(f.crops);
  const mature = f.crops.filter((_, i) => r[i]?.[0] === c.block && r[i][1] === max);
  await breakCells(mature);
  const t = await mod({ op: 'takeItems', box: dropBox(f) });
  return { broken: mature.length, cells: mature, items: t.items };
}
/** Start a run: every crop cell broken (not counted) and planted at age 0 by the player. */
async function resetField(f) {
  const r = await readCells(f.crops);
  await breakCells(f.crops.filter((_, i) => r[i] && r[i][0] !== 'minecraft:air'));
  await mod({ op: 'takeItems', box: dropBox(f) });
  const n = await plant(CROPS[f.crop].plant, f.crops);
  return n;
}
/** Whether a cell holds a design block that is not part of the light bar (fence, gate, lamp, chest, composter, water). */
const LIGHT_CELL = (c) => c && (c[0] === 'minecraft:air' || /minecraft:(wheat|carrots|potatoes|beetroots)$/.test(c[0]));

/** Grows `T` by farms `specs` in revisions of at most 3, each PLACED. Returns the farms (farmCells done). */
async function growAll(T, specs, opts = {}) {
  const farms = [];
  for (let i = 0; i < specs.length; i += 3) {
    const g = await growFarms(T.region, specs.slice(i, i + 3), opts);
    if (!check(!g.refused, `${T.region}: grown by ${specs.slice(i, i + 3).map((s) => `${s.id} ${s.width}x${s.depth} ${s.crop} ${s.bible}`).join(', ')}: ${g.refused ? String(g.refused).slice(0, 300) : `rid ${g.rid}`}`)) continue;
    const w = await rvWait(T.region, g.rid);
    check(w.r.state === 'PLACED', `${T.region} rid ${g.rid}: ${w.r.state}`);
    await farmCells(T.region, g.farms);
    for (const f of g.farms) { f.region = T.region; f.rid = g.rid; f.town = T; }
    farms.push(...g.farms);
  }
  return farms;
}
const SIZES = [[9, 9], [18, 18], [27, 27], [9, 27], [27, 9], [9, 18], [18, 9], [18, 27], [27, 18]];
/** Two towns side by side (ew then ns), within the simulation distance of a player standing between them. */
async function twoTowns(x0, z0) {
  const A = await town(x0, z0, { w: 192, d: 160, lots: 4, orientation: 'ew' });
  const B = await town(x0 + 224, z0, { w: 160, d: 192, lots: 4, orientation: 'ns' });
  return [A, B];
}
async function standAt(x, z, y) {
  await cmd(`/tp @p ${x} ${y} ${z}`);
  await call('dev.waitChunks', { timeoutMs: 120_000 }, 180_000).catch(() => null);
}
/** Every farm cell's chunk within the simulation distance of the player's chunk (Chebyshev, chunks). */
function inSim(farms, px, pz) {
  const pcx = Math.floor(px / 16), pcz = Math.floor(pz / 16);
  let worst = 0;
  for (const f of farms) for (const [x, , z] of f.crops) worst = Math.max(worst, Math.abs(Math.floor(x / 16) - pcx), Math.abs(Math.floor(z / 16) - pcz));
  return worst;
}
/** Item 2 at midnight: per farm {min, n, under1, at9}. */
async function lightBar(farms, label) {
  await cmd('/time set 18000');
  const out = {};
  for (const f of farms) {
    const r = await readCells(f.floor1);
    const lit = r.filter(LIGHT_CELL);
    const crops = r.filter((c) => c && /(wheat|carrots|potatoes|beetroots)$/.test(c[0]));
    const under = r.map((c, i) => [f.floor1[i], c]).filter(([, c]) => LIGHT_CELL(c) && c[3] < 1);
    out[f.id] = { bible: f.bible, size: `${f.width}x${f.depth}`, front: f.lot.front, min: Math.min(...lit.map((c) => c[3])), cells: lit.length, under1: under.length, at9: crops.filter((c) => c[3] >= 9).length / Math.max(1, crops.length), lamp: f.variant?.lamp ?? null, fallback: f.variant?.lampFallback ?? null };
    check(under.length === 0 && lit.length > 0, `2 light ${label} ${f.id} (${f.width}x${f.depth} ${f.bible}, front ${f.lot.front}): every open floor+1 cell has block light >= 1 (${lit.length} cells, lowest ${out[f.id].min}; crop cells at 9+: ${(out[f.id].at9 * 100).toFixed(0)}%)`, under.slice(0, 5));
  }
  await cmd('/time set 6000');
  return out;
}
/** Every farmland cell of the farms at moisture 7: {ok, notSeven: [[cell, state]]}. */
async function moistAll(farms) {
  const bad = [];
  let n = 0;
  for (const f of farms) {
    const r = await readCells(f.farmland);
    n += r.length;
    r.forEach((c, i) => { if (!(c?.[0] === 'minecraft:farmland' && c[2] === 7)) bad.push([f.id, f.farmland[i], c]); });
  }
  return { n, bad };
}
async function agesOf(farms) {
  const out = {};
  for (const f of farms) out[f.id] = { crops: await readCells(f.crops), land: await readCells(f.farmland) };
  return out;
}
const sameAges = (a, b) => {
  let diff = 0, first = null;
  for (const id of Object.keys(a)) for (const k of ['crops', 'land']) a[id][k].forEach((c, i) => { if (json(c?.slice(0, 3)) !== json(b[id]?.[k]?.[i]?.slice(0, 3))) { diff++; first ??= [id, k, i, c, b[id]?.[k]?.[i]]; } });
  return { diff, first };
};

/** Items 1, 2, 3 and 5 for one crop over the 9 size pairs, rustic (docs/MEASURE-M2.md). */
async function field() {
  const crop = opt('--crop');
  if (!CROPS[crop]) throw new Error(`--crop must be one of ${Object.keys(CROPS).join(', ')}`);
  const RUNS = Number(opt('--runs', 5));
  await startClient(`Field ${crop}`);
  const out = { crop, runs: [], placedMoisture: null, hydration: [], light: null, restart: null, unload: null };
  try {
    await rules();
    await install();
    const [A, B] = await twoTowns(30000, 30000);
    const specs = SIZES.map(([w, d], i) => ({ id: `${crop.slice(0, 4)}_${w}x${d}`, width: w, depth: d, crop, bible: 'rustic', town: i < 5 ? 'A' : 'B' }));
    const farms = [...(await growAll(A, specs.filter((s) => s.town === 'A'))), ...(await growAll(B, specs.filter((s) => s.town === 'B')))];
    out.farms = farms.map((f) => ({ id: f.id, size: `${f.width}x${f.depth}`, front: f.lot.front, box: f.lot.box, chunkBorder: f.chunkBorder, crops: f.crops.length, siteId: f.siteId, region: f.region, entryId: f.entryId }));
    check(farms.length === 9, `${crop}: all 9 size pairs placed (${farms.length})`);
    const px = 30000 + 208, pz = 30000 + 96;
    await standAt(px, pz, A.gy + 40);
    const worst = inSim(farms, px, pz);
    check(worst <= SIM_DISTANCE - 2, `${crop}: every crop cell within ${worst} chunks of the player (simulation distance ${SIM_DISTANCE})`);
    await tick.freeze();
    out.placedMoisture = await moistAll(farms);
    log(`  ${crop}: at PLACED, ${out.placedMoisture.bad.length} of ${out.placedMoisture.n} farmland cells below 7 (recorded)`);
    // ---- item 2: light at midnight
    out.light = await lightBar(farms, crop);
    // ---- item 1: 24,000 ticks, sampled every 4,000
    for (let t = 4000; t <= 24000; t += 4000) {
      await tick.advance(4000);
      const m = await moistAll(farms);
      out.hydration.push({ t, below7: m.bad.length, n: m.n, first: m.bad.slice(0, 3) });
      log(`  ${crop}: hydration at ${t}: ${m.bad.length} of ${m.n} below 7`);
    }
    const h = out.hydration.at(-1);
    check(h.below7 === 0, `1a hydration ${crop}: every farmland cell of the 9 farms reads moisture=7 24,000 ticks after PLACED (${h.n} cells; ${h.below7} below)`, h.first);
    // ---- item 3: 5 runs x 3 cycles
    const Tc = TCYCLE[crop];
    for (let run = 1; run <= RUNS; run++) {
      const R = { run, farms: {} };
      for (const f of farms) R.farms[f.id] = { planted: await resetField(f), cycles: [], tCells: null };
      const t0 = await gameTime();
      // cycle 1: every crop's age every 1,000 ticks (its maturity time: G3's basis)
      const firstMature = Object.fromEntries(farms.map((f) => [f.id, new Array(f.crops.length).fill(null)]));
      const max = crop === 'beetroots' ? 3 : 7;
      for (let t = 1000; t <= Tc; t += 1000) {
        await tick.advance(Math.min(1000, Tc - (t - 1000)));
        const now = (await gameTime()) - t0;
        for (const f of farms) {
          const r = await readCells(f.crops);
          r.forEach((c, i) => { if (firstMature[f.id][i] === null && c?.[1] === max) firstMature[f.id][i] = now; });
        }
      }
      if ((await gameTime()) - t0 < Tc) await tick.advance(Tc - ((await gameTime()) - t0));
      for (const f of farms) R.farms[f.id].tCells = firstMature[f.id];
      for (let cyc = 1; cyc <= CYCLES; cyc++) {
        if (cyc > 1) {
          // ---- item 5, in run 1 after the first harvest, at half a cycle
          if (run === 1 && cyc === 2 && !flag('--no-restart')) {
            await tick.advance(Math.floor(Tc / 2));
            out.restart = await restartCheck(farms, A.gy);
            out.unload = await unloadCheck(farms, px, pz, A.gy);
            await tick.advance(Tc - Math.floor(Tc / 2));
          } else await tick.advance(Tc);
        }
        const m = await moistAll(farms);
        for (const f of farms) {
          const hv = await harvest(f);
          const replanted = await plant(CROPS[crop].plant, hv.cells);
          R.farms[f.id].cycles.push({ cycle: cyc, at: (await gameTime()) - t0, broken: hv.broken, items: hv.items, replanted });
        }
        R.moist = R.moist ?? [];
        R.moist.push({ cycle: cyc, below7: m.bad.length, n: m.n, first: m.bad.slice(0, 2) });
      }
      out.runs.push(R);
      fs.writeFileSync(path.join(ART, `field-${crop}.partial.json`), json(out));
      log(`  ${crop} run ${run}: ${farms.map((f) => `${f.id} ${R.farms[f.id].cycles.map((c) => c.broken).join('/')}`).join('; ')}`);
    }
    judgeField(out, farms);
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

/** Item 5a: read, save and leave, open again, read: the same. */
async function restartCheck(farms, gy) {
  // random ticks off in the save (a gamerule is saved; /tick freeze is not), so the ticks a reopened world runs before it is frozen again
  // grow nothing: the check is what the save kept
  await cmd('/gamerule random_tick_speed 0');
  const before = await agesOf(farms);
  await cmd('/save-all flush');
  const l = await call('dev.world.leave', {}, 300_000);
  await sleep(2000);
  await call('dev.world.open', { name: worldName }, 300_000);
  await dev.waitInWorld({ timeoutMs: 600_000 });
  await tick.freeze();
  await rules({ ticks: 0 });
  await tick.freeze();
  await call('dev.waitChunks', { timeoutMs: 120_000 }, 180_000).catch(() => null);
  const after = await agesOf(farms);
  const d = sameAges(before, after);
  const m = await moistAll(farms);
  await cmd('/gamerule random_tick_speed 3');
  check(d.diff === 0 && m.bad.length === 0, `5a restart: after save, leave and open, every crop's block and age and every farmland cell's moisture are the same (${d.diff} differ; ${m.bad.length} farmland below 7)`, d.first);
  return { left: l.left ?? true, diff: d.diff, first: d.first, below7: m.bad.length };
}
/** Item 5b: away until the farms' chunks unload, 2,000 ticks there, back: the same. */
async function unloadCheck(farms, px, pz, gy) {
  const before = await agesOf(farms);
  const probe = farms.map((f) => f.crops[0]);
  await standAt(px + 4000, pz, gy + 40);
  let unloaded = false;
  for (let i = 0; i < 120 && !unloaded; i++) {
    await tick.advance(100);
    const r = await readCells(probe);
    unloaded = r.every((c) => c === null);
  }
  await tick.advance(2000);
  await standAt(px, pz, gy + 40);
  for (let i = 0; i < 60; i++) { const r = await readCells(probe); if (r.every((c) => c !== null)) break; await sleep(500); }
  const after = await agesOf(farms);
  const d = sameAges(before, after);
  const m = await moistAll(farms);
  check(unloaded && d.diff === 0 && m.bad.length === 0, `5b unload: the farms' chunks unloaded (${unloaded}), 2,000 ticks away, back: every crop's age the same (${d.diff} differ), every farmland cell moisture 7 (${m.bad.length} below)`, d.first);
  return { unloaded, diff: d.diff, first: d.first, below7: m.bad.length };
}

/** The bars of items 1b, 3a, 3b and 3c over a field step's runs. */
function judgeField(out, farms) {
  const probe = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'modules', 'probe-m0.json'), 'utf8'));
  const c = CROPS[out.crop];
  const Tc = TCYCLE[out.crop];
  const moist = out.runs.flatMap((r) => r.moist ?? []);
  check(moist.length > 0 && moist.every((m) => m.below7 === 0), `1b hydration ${out.crop}: every farmland cell moisture 7 at every harvest (${moist.length} harvests; ${moist.reduce((a, m) => a + m.below7, 0)} cell-readings below)`);
  out.judged = {};
  for (const f of farms) {
    const n = f.crops.length;
    const b = barBands(probe, out.crop, n);
    const runs = out.runs.map((r) => {
      const F = r.farms[f.id];
      const got = F.cycles.reduce((a, x) => a + (x.items[c.item] ?? 0), 0);
      const broken = F.cycles.reduce((a, x) => a + x.broken, 0);
      const net = got - (c.plantIsItem ? broken : 0);
      const perCropDay = net / ((CYCLES * Tc) / DAY) / n;
      const tc = (F.tCells ?? []).filter((t) => t !== null);
      return { run: r.run, broken, got, net, perCropDay, tMatured: tc.length, tMean: tc.length ? tc.reduce((a, x) => a + x, 0) / tc.length : null, yPerCrop: broken ? got / broken : 0 };
    });
    const mean = runs.reduce((a, r) => a + r.perCropDay, 0) / runs.length;
    // G3's basis: harvested as each crop ripens, from cycle 1's maturity times (cells matured within T; the rest are counted at T, a bound)
    const allT = out.runs.flatMap((r) => (r.farms[f.id].tCells ?? []).map((t) => (t === null ? Tc : t)));
    const tMean = allT.reduce((a, x) => a + x, 0) / allT.length;
    const y = runs.reduce((a, r) => a + r.got, 0) / Math.max(1, runs.reduce((a, r) => a + r.broken, 0));
    const ripen = (y - (c.plantIsItem ? 1 : 0)) / (tMean / DAY);
    const J = { n, band: b.band, runs, mean, ripenPerCropDay: ripen, unwatered: b.unwatered.mean, useful: USEFUL_PER_CROP_DAY };
    out.judged[f.id] = J;
    check(runs.length >= 5 && runs.every((r) => r.perCropDay >= b.band[0] && r.perCropDay <= b.band[1]), `3a band ${f.id} (${n} crops, front ${f.lot.front}): every run's net rate per crop a day inside [${b.band.map((x) => x.toFixed(3)).join(', ')}]: ${runs.map((r) => r.perCropDay.toFixed(3)).join(', ')} (${runs.length} runs)`);
    check(ripen >= USEFUL_PER_CROP_DAY, `3b usefulness ${f.id}: harvested as it ripens, ${ripen.toFixed(3)} per crop a day = ${(ripen * 80).toFixed(1)} per 80 crops (bar 24); the protocol's quoted rate ${mean.toFixed(3)} = ${(mean * 80).toFixed(1)} per 80`);
    check(mean >= 1.5 * b.unwatered.mean, `3c control ${f.id}: the mean ${mean.toFixed(3)} >= 1.5 x the probe's unwatered ${b.unwatered.mean.toFixed(3)}`);
  }
}

// ------------------------------------------------------------------ skin: items 1a, 2 and 4 for one Style (and the dim lamp)

/** The fence's inside in world coordinates (u 1..W-2, v 1..D-3 of the frame, floor .. floor + 3) as a box. */
function insideBox(f) {
  const a = f.frame.toWorld(1, 0, 1), b = f.frame.toWorld(f.L.W - 2, 3, f.L.D - 3);
  return [Math.min(a[0], b[0]), a[1], Math.min(a[2], b[2]), Math.max(a[0], b[0]), b[1], Math.max(a[2], b[2])];
}
const inBox = (b, x, y, z) => x >= b[0] && x < b[3] + 1 && y >= b[1] && y < b[4] + 1 && z >= b[2] && z < b[5] + 1;
/** Where a farm's mobs are summoned: 3 cells outside the fence at the middle of its back and of each side (frame), floor + 1. */
function mobSpots(f) {
  const { W, D } = f.L;
  const m = Math.floor(W / 2), mv = Math.floor((D - 2) / 2);
  return [[m, 1, -3], [m - 2, 1, -3], [-3, 1, mv], [-3, 1, mv + 2], [W + 2, 1, mv], [W + 2, 1, mv - 2]].map(([u, y, v]) => f.frame.toWorld(u, y, v));
}
/** Item 4c: the gate. A fake player from the front row walks in (closed: it stays out), the gate opened, in, out. */
async function gateWalk(f) {
  const { gate, D } = f.L;
  const c = (u, v) => { const w = f.frame.toWorld(u, 1, v); return [w[0] + 0.5, w[1] + 0.05, w[2] + 0.5]; };
  const out = c(gate, D - 1), inn = c(gate, D - 3), g = f.frame.toWorld(gate, 1, D - 2);
  const closed = await mod({ op: 'walk', from: out, to: inn });
  const use = () => call('dev.pt.uses', { ops: [{ item: 'minecraft:stick', at: g, on: g, face: f.lot.front, placer: PLACER }] }, 120_000);
  await use();
  const walkIn = await mod({ op: 'walk', from: out, to: inn });
  const walkOut = await mod({ op: 'walk', from: inn, to: out });
  await use(); // closed again
  const closedAgain = await mod({ op: 'walk', from: out, to: inn });
  return { closed: closed.reached, in: walkIn.reached, out: walkOut.reached, closedAgain: closedAgain.reached };
}

async function skin() {
  const bible = opt('--bible');
  if (!MODULE_JSON.envelope.bibles.includes(bible)) throw new Error(`--bible must be one of ${MODULE_JSON.envelope.bibles.join(', ')}`);
  await startClient(`Skin ${bible}`);
  const out = { bible, light: null, hydration: null, exclusion: {}, gate: {}, spiders: null };
  const crops = Object.keys(CROPS);
  try {
    await rules();
    await install();
    const [A, B] = await twoTowns(40000, 40000);
    const specs = SIZES.map(([w, d], i) => ({ id: `${bible.slice(0, 4)}_${w}x${d}`, width: w, depth: d, crop: crops[i % 4], bible, town: i < 5 ? 'A' : 'B' }));
    const farms = [...(await growAll(A, specs.filter((s) => s.town === 'A'))), ...(await growAll(B, specs.filter((s) => s.town === 'B')))];
    out.farms = farms.map((f) => ({ id: f.id, size: `${f.width}x${f.depth}`, crop: f.crop, front: f.lot.front, box: f.lot.box, chunkBorder: f.chunkBorder, lamp: f.variant.lamp }));
    check(farms.length === 9, `${bible}: all 9 size pairs placed (${farms.length})`);
    // (the dim lamp: all three Styles' lamp role is minecraft:lantern, the fallback itself, so every farm here measures the fallback's light)
    const dim = [];
    check(farms.every((f) => f.variant.lamp === MODULE_JSON.palette.lamp.fallback), `${bible}: the lamp is ${farms[0]?.variant?.lamp} (the fallback ${MODULE_JSON.palette.lamp.fallback}: its light is measured on every farm)`);
    const px = 40000 + 208, pz = 40000 + 96;
    await standAt(px, pz, A.gy + 40);
    check(inSim(farms, px, pz) <= SIM_DISTANCE - 2, `${bible}: every farm inside the simulation distance`);
    await tick.freeze();
    out.light = await lightBar([...farms, ...dim], bible);
    const placed = await moistAll(farms);
    await tick.advance(24000);
    const m = await moistAll(farms);
    out.hydration = { atPlaced: placed.bad.length, at24000: m.bad.length, n: m.n };
    check(m.bad.length === 0, `1a hydration ${bible}: every farmland cell of the 9 farms moisture 7 24,000 ticks after PLACED (${m.n} cells, ${m.bad.length} below; ${placed.bad.length} below at PLACED, recorded)`, m.bad.slice(0, 3));
    // the fence ring as built: every ring cell (frame y 1) a fence, the gate's a fence gate (structural, recorded with item 4)
    out.ring = {};
    for (const f of farms) {
      const { W, D, gate } = f.L;
      const ring = [];
      for (let u = 0; u < W; u++) for (let v = 0; v <= D - 2; v++) if (u === 0 || u === W - 1 || v === 0 || v === D - 2) ring.push([u, v]);
      const r = await readCells(ring.map(([u, v]) => f.frame.toWorld(u, 1, v)));
      const bad = ring.map((c, i) => [c, r[i]]).filter(([[u, v], c]) => !(u === gate && v === D - 2 ? /fence_gate$/.test(c?.[0] ?? '') : /fence$/.test(c?.[0] ?? '')));
      out.ring[f.id] = { cells: ring.length, bad: bad.slice(0, 8), badN: bad.length };
      check(bad.length === 0, `4 fence ${f.id}: every one of ${ring.length} ring cells a fence (the gate a fence gate): ${bad.length} not (${json(bad.slice(0, 4))})`);
    }
    // ---- item 4: mobs outside the closed fences, midnight, easy, 12,000 ticks
    const before = await agesOf(farms);
    await cmd('/time set 18000');
    await cmd('/difficulty easy');
    const kinds = ['zombie', 'zombie', 'villager', 'villager', 'cow', 'cow'];
    out.spots = {};
    for (const f of farms) for (const [i, [x, y, z]] of mobSpots(f).entries()) {
      (out.spots[f.id] ??= []).push([kinds[i], x, y, z, inBox(insideBox(f), x + 0.5, y, z + 0.5), farms.filter((g) => inBox(insideBox(g), x + 0.5, y, z + 0.5)).map((g) => g.id)]);
      await cmd(`/summon minecraft:${kinds[i]} ${x + 0.5} ${y} ${z + 0.5} {PersistenceRequired:1b}`);
    }
    const t0mobs = [];
    for (const f of farms) t0mobs.push(...(await mod({ op: 'mobs', box: insideBox(f) })).mobs.map((m) => [f.id, ...m]));
    out.insideAtSummon = t0mobs;
    log(`  mobs inside right after the summon: ${t0mobs.length} ${json(t0mobs.slice(0, 4))}`);
    const whole = (f) => { const b = f.frame.box; return [b.minX - 8, f.frame.floorY - 2, b.minZ - 8, b.maxX + 8, f.frame.floorY + 6, b.maxZ + 8]; };
    const samples = [];
    for (let t = 1000; t <= 12000; t += 1000) {
      await tick.advance(1000);
      const s = { t, inside: [], near: 0 };
      for (const f of farms) {
        const ib = insideBox(f);
        const ms = (await mod({ op: 'mobs', box: whole(f) })).mobs;
        s.near += ms.length;
        for (const [type, x, y, z] of ms) if (inBox(ib, x, y, z)) s.inside.push([f.id, type, x, y, z]);
      }
      samples.push(s);
    }
    const after = await agesOf(farms);
    // 4b: every farmland cell still farmland, every crop cell still its crop (ages may grow)
    const changed = [];
    for (const f of farms) {
      after[f.id].land.forEach((c, i) => { if (c?.[0] !== 'minecraft:farmland') changed.push([f.id, f.farmland[i], c]); });
      after[f.id].crops.forEach((c, i) => { if (c?.[0] !== CROPS[f.crop].block) changed.push([f.id, f.crops[i], c, before[f.id].crops[i]]); });
    }
    const inside = samples.flatMap((x) => x.inside);
    out.exclusion = { samples: samples.map((x) => ({ t: x.t, inside: x.inside.length, near: x.near })), inside: inside.slice(0, 10), changed: changed.slice(0, 10), changedN: changed.length };
    check(inside.length === 0, `4a exclusion ${bible}: no zombie, villager or cow inside a closed fence over 12,000 ticks (9 farms x 6 mobs; ${samples.at(-1).near} mobs near the farms at the end; ${inside.length} sightings inside)`, inside.slice(0, 5));
    check(changed.length === 0, `4b exclusion ${bible}: every farmland cell still farmland and every crop cell still its crop (${changed.length} changed)`, changed.slice(0, 5));
    await cmd('/kill @e[type=!minecraft:player]');
    await cmd('/difficulty peaceful');
    // ---- 4c: the gate
    for (const f of farms) {
      const g = await gateWalk(f);
      out.gate[f.id] = g;
      check(g.closed === false && g.in === true && g.out === true && g.closedAgain === false, `4c gate ${f.id} (front ${f.lot.front}): closed, a walking player stays out; opened, in and out; closed again, out (${json(g)})`);
    }
    // ---- spiders (recorded): one beside each of 3 farms, 12,000 ticks
    if (!flag('--no-spiders')) {
      await cmd('/difficulty easy');
      const sp = farms.slice(0, 3);
      const before2 = await agesOf(sp);
      for (const f of sp) { const [x, y, z] = mobSpots(f)[0]; await cmd(`/summon minecraft:spider ${x + 0.5} ${y} ${z + 0.5} {PersistenceRequired:1b}`); }
      let seen = 0;
      for (let t = 1000; t <= 12000; t += 1000) {
        await tick.advance(1000);
        for (const f of sp) seen += (await mod({ op: 'mobs', box: insideBox(f) })).mobs.filter((x) => /spider/.test(x[0])).length;
      }
      const after2 = await agesOf(sp);
      const trampled = sp.reduce((a, f) => a + after2[f.id].land.filter((c) => c?.[0] !== 'minecraft:farmland').length, 0);
      out.spiders = { farms: sp.map((f) => f.id), sightingsInside: seen, trampled, cropsChanged: sameAges(before2, after2).diff };
      log(`  spiders (recorded): ${json(out.spiders)}`);
      await cmd('/kill @e[type=!minecraft:player]');
      await cmd('/difficulty peaceful');
    }
    await cmd('/time set 6000');
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

// ------------------------------------------------------------------ survival: items 6 and 7

const same = (a, b) => { for (const x of new Set([...Object.keys(a ?? {}), ...Object.keys(b ?? {})])) if ((a?.[x] ?? 0) !== (b?.[x] ?? 0)) return false; return true; };
const minus = (a, b) => { const o = {}; for (const x of new Set([...Object.keys(a ?? {}), ...Object.keys(b ?? {})])) { const d = (a?.[x] ?? 0) - (b?.[x] ?? 0); if (d) o[x] = d; } return o; };
const plus = (a, b) => { const o = { ...(a ?? {}) }; for (const [k, v] of Object.entries(b ?? {})) o[k] = (o[k] ?? 0) + v; for (const k of Object.keys(o)) if (!o[k]) delete o[k]; return o; };
const pile = async () => (await call('dev.stockpile.state', { owner: OWNER }, 60_000)).stockpile ?? {};
/** Feeds the stockpile what it still owes until `until(st)` (gate6crs-game feed). */
async function feedUntil(region, until, { timeoutMs = 3_600_000, every = 2500 } = {}) {
  const end = Date.now() + timeoutMs;
  let waves = 0, st = null, rs = null;
  while (Date.now() < end) {
    st = await call('dev.region.state', { region }, 60_000);
    rs = await call('dev.rs.state', { region }, 120_000).catch(() => null);
    if (until(st, rs)) return { waves, st, rs, ok: true };
    if (['FAILED', 'PARTIAL'].includes(st.view.state)) break;
    const ps = await pile();
    const need = {};
    for (const [it, n] of Object.entries(ps.outstanding ?? {})) { const lack = n - (ps.stock?.[it] ?? 0); if (lack > 0) need[it] = lack; }
    if (Object.keys(need).length) { await req('dev.crate.insert', { site: `stockpile:${OWNER}`, items: need, exact: true }, 120_000); waves++; }
    await sleep(every);
  }
  return { waves, st, rs, ok: false };
}
const land = (region) => call('dev.region.landCheck', { region }, 900_000);
const lotRow = (rep, id) => (rep.lots ?? []).find((l) => l.lot === id) ?? null;
const partsKey = (rep) => json((rep.parts ?? []).map((p) => [p.part, p.cells, p.changed, p.removed, p.playerPlaced, p.volatileChanged]));

async function survival() {
  // a survival-mode world (a region bills from the stockpile only there; FC's and IG's gates do the same), survival construction on
  await startClient('Survival', { mode: 'survival' });
  const out = { growths: [], farms: {} };
  try {
    // the player is in survival: no fall damage (the teleports would kill it, and a dead player loads no chunks)
    await cmd('/gamerule fall_damage false');
    await cmd('/effect give @p minecraft:resistance infinite 4 true');
    await cmd('/effect give @p minecraft:slow_falling infinite 0 true');
    await cmd('/effect give @p minecraft:saturation infinite 0 true');
    await call('dev.survival.set', { on: true });
    await rules({ ticks: 0 });
    const x0 = 50000, z0 = 50000;
    await standAt(x0 + 96, z0 - 30, 100);
    const gy = await groundY(x0 + 96, z0 - 30);
    await standAt(x0 + 96, z0 - 30, gy + 1);
    const crates = Array.from({ length: 8 }, (_, q) => [x0 - 6 + 2 * q, gy + 1, z0 - 6]);
    const cr = await req('dev.stockpile.create', { owner: OWNER, crates }, 120_000);
    check(cr.ok && !cr.refused, `a stockpile of 8 crates for ${OWNER}: ${json(cr.refused ?? cr.error ?? 'made')}`);
    // the town in survival (pads), fed until built
    const claim = [x0, z0, x0 + 191, z0 + 159];
    const p0 = await call('dev.region.plan', { program: 'flat_village', claim, params: { lots: [{ id: 'hall_1', type: 'hall', size: [13, 11], landmark: true }, { id: 'cottage_1', type: 'cottage', size: [9, 9] }], orientation: 'ew' }, surveyLoad: 'bounded:256', check: false, owner: OWNER }, 900_000);
    const r0 = await call('dev.region.realise', { planId: p0.planId, load: 'bounded:512', lots: {} }, 300_000);
    const region = r0.region;
    const f0 = await feedUntil(region, (st, rs) => st.view.state === 'PLACED' && (rs?.build ? rs.build.done : true));
    check(f0.ok, `the town ${region} built in survival from the stockpile: ${f0.st?.view?.state} (${f0.waves} waves)`);
    await install();
    const T = { region, gy, claim };
    const crops = Object.keys(CROPS);
    const specs = SIZES.map(([w, d], i) => ({ id: `surv_${w}x${d}`, width: w, depth: d, crop: crops[i % 4], bible: 'rustic' }));
    const farms = [];
    for (let i = 0; i < specs.length; i += 3) {
      const sp = specs.slice(i, i + 3);
      const ps0 = await pile();
      const g = await growFarms(region, sp, { survival: true });
      if (!check(!g.refused, `survival growth ${sp.map((x) => x.id).join(', ')}: ${g.refused ? String(g.refused).slice(0, 300) : `rid ${g.rid}`}`)) continue;
      const fg = await feedUntil(region, (st, rs) => st.view.state === 'PLACED' && (rs?.build ? rs.build.done : true));
      const w = await rvWait(region, g.rid);
      const ps1 = await pile();
      const consumed = minus(ps1.ledger?.consumed, ps0.ledger?.consumed);
      const quote = g.quote?.bom ?? null;
      out.growths.push({ rid: g.rid, position: g.position, farms: sp.map((x) => x.id), quote, consumed, quoteExact: g.quote?.exact, waves: fg.waves });
      check(w.r.state === 'PLACED' && quote && same(consumed, quote), `7a bill rid ${g.rid} (${sp.map((x) => x.id).join(', ')}): ${w.r.state}; consumed = the quote item for item (${same(consumed, quote ?? {}) ? 'equal' : `consumed ${json(minus(consumed, quote ?? {}))} more than quoted`}; ${Object.values(consumed).reduce((a, b) => a + b, 0)} items)`, { quote, consumed });
      await farmCells(region, g.farms);
      for (const f of g.farms) { f.region = region; f.rid = g.rid; f.position = g.position; }
      farms.push(...g.farms);
    }
    // ---- 6: grown T, harvested, replanted with each crop, a cobblestone and a torch
    const land0 = await land(region);
    await cmd(`/gamerule random_tick_speed 3`);
    await tick.freeze();
    await tick.advance(TCYCLE.wheat);
    await cmd(`/gamerule random_tick_speed 0`);
    await cmd('/tick unfreeze'); // the world runs again (random ticks off): a removal's settle bound needs ticks
    const plantOf = (c) => CROPS[c].plant;
    for (const f of farms) {
      const hv = await harvest(f);
      const cells = hv.cells;
      const rp = {};
      for (const [j, c] of crops.entries()) rp[c] = cells.slice(1 + 2 * j, 3 + 2 * j);
      let took = 0;
      for (const c of crops) took += await plant(plantOf(c), rp[c]);
      const cob = cells[0];
      let cobble = null, torch = null;
      if (cob) {
        cobble = await call('dev.pt.uses', { ops: [{ item: 'minecraft:cobblestone', at: cob, on: [cob[0], cob[1] - 1, cob[2]], face: 'up', placer: PLACER }] }, 120_000);
        torch = await call('dev.pt.uses', { ops: [{ item: 'minecraft:torch', at: [cob[0], cob[1] + 1, cob[2]], on: cob, face: 'up', placer: PLACER }] }, 120_000);
      }
      await mod({ op: 'takeItems', box: dropBox(f) });
      out.farms[f.id] = { crop: f.crop, size: `${f.width}x${f.depth}`, front: f.lot.front, harvested: hv.broken, replanted: Object.fromEntries(crops.map((c) => [c, rp[c].length])), took, cobble: cobble?.results?.[0] ?? null, torch: torch?.results?.[0] ?? null, cob };
      check(hv.broken >= 9 && took === 8 && cobble?.results?.[0]?.tracked && torch?.results?.[0]?.tracked, `6 worked ${f.id}: ${hv.broken} harvested by the player, 8 replanted (2 of each crop: ${took} took, PT records), a cobblestone and a torch on it (tracked ${!!cobble?.results?.[0]?.tracked}/${!!torch?.results?.[0]?.tracked})`);
    }
    // ---- a restart: the records hold (6a)
    await cmd('/save-all flush');
    await call('dev.world.leave', {}, 300_000);
    await sleep(2000);
    await call('dev.world.open', { name: worldName }, 300_000);
    await dev.waitInWorld({ timeoutMs: 600_000 });
    await rules({ ticks: 0 });
    await standAt(x0 + 96, z0 - 30, gy + 1);
    for (const f of farms) {
      const W = out.farms[f.id];
      const at = W.cob ? [W.cob[0], W.cob[1] + 1, W.cob[2]] : null;
      const t = at ? await call('dev.pt.at', { at }, 30_000) : null;
      W.torchAfterRestart = t?.tracked ?? null;
    }
    check(farms.every((f) => out.farms[f.id].torchAfterRestart === true), `6a after a restart: each farm's torch is still the player's (PT): ${farms.filter((f) => out.farms[f.id].torchAfterRestart === true).length}/${farms.length}`);
    // ---- 6b, 6c: land check and standing
    const land1 = await land(region);
    out.land = { before: (land0.lots ?? []).filter((l) => farms.some((f) => f.id === l.lot)), after: (land1.lots ?? []).filter((l) => farms.some((f) => f.id === l.lot)), partsSame: partsKey(land0) === partsKey(land1) };
    const P = await program();
    const { PALETTES } = await import(pathToFileURL(path.join(ROOT, 'artifacts', 'm1', `kit-${ARCH_VERSION}`, 'kit', 'lib', 'kit.mjs')).href);
    for (const f of farms) {
      const r0 = lotRow(land0, f.id), r1 = lotRow(land1, f.id);
      const blocks = P.default({ palette: PALETTES.rustic, width: f.width, depth: f.depth, crop: f.crop }).cells.size;
      check(r0?.changed === 0 && r0?.standing === 1 && r1?.changed === 0 && r1?.playerPlaced === 2, `6b land check ${f.id}: built changed ${r0?.changed} standing ${r0?.standing}; worked changed ${r1?.changed}, playerPlaced ${r1?.playerPlaced} (the cobblestone and the torch)`, { r0, r1 });
      check(r1?.standing < 1 && r1?.standing >= (blocks - 1) / blocks - 1e-9, `6c standing ${f.id}: ${r1?.standing} (worked field standing; the one cell the cobblestone took is the only mismatch of ${blocks} blocks: >= ${((blocks - 1) / blocks).toFixed(4)})`);
    }
    check(out.land.partsSame, `6b land check: the town's and the growths' parts as before the field was worked`);
    // ---- 6d, 7b: undo every growth, newest first; refunds
    const ledger0 = await pile();
    await mod({ op: 'takeItems', box: [x0 - 300, gy - 4, z0 - 300, x0 + 600, gy + 60, z0 + 600] });
    await standAt(x0 - 20, z0 - 20, gy + 2);
    const growths = [...out.growths].reverse();
    const allFarms = new Map(farms.map((f) => [f.id, f]));
    for (const g of growths) {
      const before = await pile();
      const u = await call('dev.rv.undo', { region, toPosition: g.position - 1 }, 4 * 3_600_000);
      await sleep(6000);
      const after = await pile();
      const credited = minus(after.ledger?.returned, before.ledger?.returned);
      const overflow = minus(after.ledger?.refundOverflow, before.ledger?.refundOverflow);
      // the cargo: per farm, the items dropped at it (counted and taken away)
      const cargo = {};
      for (const id of g.farms) { const f = allFarms.get(id); const b = dropBox(f); b[4] += 40; cargo[id] = (await mod({ op: 'takeItems', box: b })).items; }
      // expected: credited = consumed less one construction seed per harvested cell; cargo per farm = 2 planting items of each crop + cobblestone + torch
      let harvestedSeeds = {};
      for (const id of g.farms) { const f = allFarms.get(id); harvestedSeeds = plus(harvestedSeeds, { [CROPS[f.crop].plant]: out.farms[id].harvested }); }
      const want = minus(g.consumed, harvestedSeeds);
      const back = plus(credited, overflow);
      g.undo = { removed: u.removed, blockers: u.blockers ?? [], refused: u.refused ?? null, credited, overflow, cargo, want };
      check(u.removed === true && !(u.blockers ?? []).length, `6d undo rid ${g.rid}: removed ${u.removed}, blockers ${json(u.blockers ?? [])}${u.refused ? ` (${u.refused})` : ''}`);
      check(same(back, want), `7b refund rid ${g.rid}: credited (with overflow) = consumed less one construction seed per harvested cell (${same(back, want) ? 'equal' : `off by ${json(minus(back, want))}`})`, { back, want });
      for (const id of g.farms) {
        const wantCargo = plus(Object.fromEntries(crops.map((c) => [plantOf(c), 0])), {});
        for (const c of crops) wantCargo[plantOf(c)] = (wantCargo[plantOf(c)] ?? 0) + out.farms[id].replanted[c];
        wantCargo['minecraft:cobblestone'] = 1;
        wantCargo['minecraft:torch'] = 1;
        check(same(cargo[id], wantCargo), `7b cargo ${id}: each player replant's planting item once, the cobblestone and the torch, as the player's (dropped, never credited): ${json(cargo[id])}`, { wantCargo });
      }
    }
    const ledger1 = await pile();
    out.items = { consumedTotal: minus(ledger1.ledger?.consumed, ledger0.ledger?.consumed) };
    // a restart after the undo gives nothing twice
    await cmd('/save-all flush');
    await call('dev.world.leave', {}, 300_000);
    await sleep(2000);
    await call('dev.world.open', { name: worldName }, 300_000);
    await dev.waitInWorld({ timeoutMs: 600_000 });
    await standAt(x0 - 20, z0 - 20, gy + 2);
    await sleep(6000);
    const ledger2 = await pile();
    const stray = (await mod({ op: 'takeItems', box: [x0 - 300, gy - 4, z0 - 300, x0 + 600, gy + 60, z0 + 600] })).items;
    check(json(ledger2.ledger) === json(ledger1.ledger) && json(ledger2.stock) === json(ledger1.stock) && Object.keys(stray).length === 0, `7b after a restart: nothing given twice (the ledger and stock unchanged; items on the ground ${json(stray)})`);
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

// ------------------------------------------------------------------ border: the sizes whose placements did not straddle a chunk corner

/**
 * The 9x9 and 27x9 placements of the field and skin steps never crossed a chunk border in both x and z (a 12-deep lot often sits inside
 * one chunk row). Farms of those sizes are grown on towns at shifted claims until at least one of each straddles both, and those are
 * measured for items 1a, 2, 4 and 5 (hydration, light, exclusion and the gate, restart and unload).
 */
async function border() {
  await startClient('Border');
  const out = { tries: [], farms: [] };
  try {
    await rules();
    await install();
    const want = [[9, 9], [27, 9]];
    const got = [];
    let k = 0;
    for (const [dx, dz] of [[0, 0], [5, 7], [11, 3], [3, 13], [8, 9], [13, 5]]) {
      if (want.every(([w, d]) => got.some((f) => f.width === w && f.depth === d))) break;
      const T = await town(70000 + dx + 400 * (k % 2), 70000 + dz + 300 * Math.floor(k / 2), { w: 192, d: 160, lots: 4, orientation: k % 2 ? 'ns' : 'ew' });
      const specs = [];
      for (const [w, d] of want) for (let i = 0; i < 2; i++) specs.push({ id: `bord${k}_${w}x${d}_${i}`, width: w, depth: d, crop: Object.keys(CROPS)[(k + i) % 4], bible: MODULE_JSON.envelope.bibles[(k + i) % 3] });
      const farms = await growAll(T, specs);
      k++;
      for (const f of farms) {
        out.tries.push({ id: f.id, front: f.lot.front, box: f.lot.box, chunkBorder: f.chunkBorder });
        if (f.chunkBorder.x && f.chunkBorder.z) got.push(f);
      }
    }
    // keep only what one player position covers: the farms near the first one that straddles
    const pick = [];
    for (const [w, d] of want) { const f = got.find((g) => g.width === w && g.depth === d); if (f) pick.push(f); }
    check(want.every(([w, d]) => pick.some((f) => f.width === w && f.depth === d)), `border: a ${want.map(([w, d]) => `${w}x${d}`).join(' and a ')} farm straddling a chunk border in x and z (${pick.map((f) => `${f.id} ${json(f.lot.box)}`).join('; ')})`);
    out.farms = pick.map((f) => ({ id: f.id, size: `${f.width}x${f.depth}`, crop: f.crop, bible: f.bible, front: f.lot.front, box: f.lot.box, chunkBorder: f.chunkBorder }));
    for (const f of pick) {
      const b = f.frame.box;
      const px = Math.floor((b.minX + b.maxX) / 2), pz = Math.floor((b.minZ + b.maxZ) / 2);
      await standAt(px, pz, f.frame.floorY + 40);
      await tick.freeze();
      const lt = await lightBar([f], `border ${f.id}`);
      await tick.advance(24000);
      const m = await moistAll([f]);
      check(m.bad.length === 0, `1a hydration border ${f.id}: every farmland cell moisture 7 24,000 ticks after PLACED (${m.n}, ${m.bad.length} below)`);
      const r5a = await restartCheck([f], f.frame.floorY);
      await tick.freeze();
      const r5b = await unloadCheck([f], px, pz, f.frame.floorY);
      // item 4 on this farm
      await cmd('/time set 18000');
      await cmd('/difficulty easy');
      const kinds = ['zombie', 'zombie', 'villager', 'villager', 'cow', 'cow'];
      for (const [i, [x, y, z]] of mobSpots(f).entries()) await cmd(`/summon minecraft:${kinds[i]} ${x + 0.5} ${y} ${z + 0.5} {PersistenceRequired:1b}`);
      let inside = 0;
      for (let t = 1000; t <= 12000; t += 1000) { await tick.advance(1000); inside += (await mod({ op: 'mobs', box: insideBox(f) })).mobs.length; }
      const after = await agesOf([f]);
      const changed = after[f.id].land.filter((c) => c?.[0] !== 'minecraft:farmland').length + after[f.id].crops.filter((c) => c?.[0] !== CROPS[f.crop].block).length;
      check(inside === 0 && changed === 0, `4a/4b exclusion border ${f.id}: no mob inside over 12,000 ticks (${inside} sightings), the field unchanged (${changed})`);
      await cmd('/kill @e[type=!minecraft:player]');
      await cmd('/difficulty peaceful');
      const g = await gateWalk(f);
      check(g.closed === false && g.in && g.out && g.closedAgain === false, `4c gate border ${f.id}: ${json(g)}`);
      await cmd('/time set 6000');
      await cmd('/tick unfreeze');
      out[f.id] = { light: lt[f.id], restart: r5a, unload: r5b, gate: g };
    }
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

/** The envelope's cover bars over all the evidence (docs/MEASURE-M2.md "The envelope"): facings and chunk borders. No client. */
function cover() {
  const read = (s) => JSON.parse(fs.readFileSync(path.join(ART, `${s}.json`), 'utf8'));
  const fronts = {}, border2 = {}, orient = {};
  const now = stamp();
  for (const s of ['field-wheat', 'field-carrots', 'field-potatoes', 'field-beetroots', 'skin-rustic', 'skin-fortress', 'skin-desert', 'border']) {
    const a = read(s);
    check(a.ok && a.programSha === now.programSha && a.moduleJsonSha === now.moduleJsonSha, `${s}: passed on these files`);
    for (const f of a.data.farms) {
      fronts[f.front] = (fronts[f.front] ?? 0) + 1;
      (border2[f.size] ??= []).push(!!(f.chunkBorder?.x && f.chunkBorder?.z));
      if (s.startsWith('field-')) (orient[s] ??= new Set()).add(['north', 'south'].includes(f.front) ? 'ew' : 'ns');
    }
  }
  check(['north', 'east', 'south', 'west'].every((d) => (fronts[d] ?? 0) >= 9), `facings: every facing has at least 9 measured farms (${json(fronts)})`);
  check(Object.values(orient).length === 4 && Object.values(orient).every((o) => o.size === 2), `facings: each crop measured on both street orientations (${json(Object.fromEntries(Object.entries(orient).map(([k, v]) => [k, [...v]])))})`);
  const sizes = Object.fromEntries(Object.entries(border2).map(([k, v]) => [k, `${v.filter(Boolean).length}/${v.length}`]));
  check(SIZES.every(([w, d]) => (border2[`${w}x${d}`] ?? []).some(Boolean)), `chunk borders: at least one farm of every size pair crosses in both x and z (${json(sizes)})`);
  return { fronts, chunkBorders: sizes };
}

// ------------------------------------------------------------------ bounds: the whole growth's bill per params (M3's survival bound)

/**
 * For every branch x Style: a single-farm growth planned on a survival V town's frontage, its keyed variant, and `Regions.quote` (QT) of
 * the plan with that entry, never realised (a quote consumes and writes nothing). The survival step shows a growth consumes its quote item
 * for item. A params' bound is the per-item maximum of its three Styles' quotes: {params, bom, styles: {bible: bom}}.
 */
async function bounds() {
  await startClient('Bounds', { mode: 'survival' });
  const out = { bounds: [], quotes: [] };
  try {
    await cmd('/gamerule fall_damage false');
    await cmd('/effect give @p minecraft:resistance infinite 4 true');
    await cmd('/effect give @p minecraft:saturation infinite 0 true');
    await call('dev.survival.set', { on: true });
    await rules({ ticks: 0 });
    const x0 = 60000, z0 = 60000;
    await standAt(x0 + 96, z0 - 30, 100);
    const gy = await groundY(x0 + 96, z0 - 30);
    await standAt(x0 + 96, z0 - 30, gy + 1);
    const crates = Array.from({ length: 4 }, (_, q) => [x0 - 6 + 2 * q, gy + 1, z0 - 6]);
    await req('dev.stockpile.create', { owner: OWNER, crates }, 120_000);
    const claim = [x0, z0, x0 + 191, z0 + 159];
    const p0 = await call('dev.region.plan', { program: 'flat_village', claim, params: { lots: [{ id: 'hall_1', type: 'hall', size: [13, 11], landmark: true }, { id: 'cottage_1', type: 'cottage', size: [9, 9] }], orientation: 'ew' }, surveyLoad: 'bounded:256', check: false, owner: OWNER }, 900_000);
    const r0 = await call('dev.region.realise', { planId: p0.planId, load: 'bounded:512', lots: {} }, 300_000);
    const f0 = await feedUntil(r0.region, (st, rs) => st.view.state === 'PLACED' && (rs?.build ? rs.build.done : true));
    check(f0.ok, `the town ${r0.region} built in survival: ${f0.st?.view?.state}`);
    await install();
    const P = await program();
    for (const [w, d] of SIZES) {
      for (const crop of Object.keys(CROPS)) {
        const styles = {};
        for (const bible of MODULE_JSON.envelope.bibles) {
          const id = `bd_${w}x${d}_${crop.slice(0, 3)}_${bible.slice(0, 3)}`;
          const lot = (await mod({ op: 'growLot', id, width: w, depth: d })).lot;
          const p = await req('dev.rv.plan', { region: r0.region, program: 'flat_village', params: { grow: { lots: [lot], prefer: 'frontage' } } }, 900_000);
          if (!p.planId) { check(false, `bounds ${id}: the growth planned: ${String(p.refused ?? p.error).slice(0, 200)}`); continue; }
          const pl = p.lots.find((l) => l.id === id);
          const irr = pl.ext['architect_mc:lotIrrigation'];
          const v = await mod({ op: 'variant', dir: CATALOG, id: MODULE.id, version: MODULE.version, measuring: true, params: { width: w, depth: d, crop },
            bible: { id: bible, version: 0 }, reserved: { front: pl.front, size: irr.frame, cells: runsCells(irr.lotCells) }, key: `steward.m2${run36}.bound.${id}` });
          if (!v.ok) { check(false, `bounds ${id}: variant ${v.detail} ${v.message}`); continue; }
          const q = await req('dev.region.quote', { planId: p.planId, lotEntries: { [id]: v.entryId } }, 300_000);
          const ok = q.ok && !q.refused && q.survival === true && q.bom && Object.keys(q.bom).length > 0;
          check(ok, `bounds ${w}x${d} ${crop} ${bible}: quoted (survival ${q.survival}, exact ${q.exact}, ${Object.values(q.bom ?? {}).reduce((a, b) => a + b, 0)} items; ${P.layout(w, d).field.length} crop cells)`);
          if (ok) { styles[bible] = q.bom; out.quotes.push({ params: { crop, depth: d, width: w }, bible, bom: q.bom, bomParts: q.bomParts, exact: q.exact, notes: q.notes }); }
        }
        if (Object.keys(styles).length === MODULE_JSON.envelope.bibles.length) {
          const bom = {};
          for (const b of Object.values(styles)) for (const [k, n] of Object.entries(b)) bom[k] = Math.max(bom[k] ?? 0, n);
          out.bounds.push({ params: { crop, depth: d, width: w }, bom: Object.fromEntries(Object.entries(bom).sort()) });
        }
      }
    }
    check(out.bounds.length === 36, `bounds: every one of the 36 branches has a bound over its 3 Styles (${out.bounds.length})`);
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

// ------------------------------------------------------------------ bands

function bandsStep() {
  const probe = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'modules', 'probe-m0.json'), 'utf8'));
  const out = {};
  return (async () => {
    const P = await program();
    for (const crop of Object.keys(CROPS)) {
      for (const w of [9, 18, 27]) for (const d of [9, 18, 27]) {
        const n = P.layout(w, d).field.length;
        const b = barBands(probe, crop, n);
        out[`${crop}:${w}x${d}`] = b;
        log(`${crop} ${w}x${d} (${n} crops): predicted ${b.predicted.mean.toFixed(3)} per crop a day; band [${b.band.map((x) => x.toFixed(3)).join(', ')}]; useful ${b.useful}; unwatered ${b.unwatered.mean.toFixed(3)} (x${b.unwatered.ratio.toFixed(2)} slower)`);
      }
    }
    return out;
  })();
}

const steps = { look, bands: bandsStep, field, skin, survival, bounds, border, cover };
if (steps[step]) {
  const t0 = Date.now();
  let data, error;
  try { data = await steps[step](); } catch (e) { error = String(e?.stack ?? e); check(false, `${step}: ${String(e?.message ?? e).slice(0, 400)}`); }
  const ok = checks.every((c) => c.ok);
  const head = execFileSync('git', ['-C', ROOT, 'rev-parse', 'HEAD']).toString().trim();
  fs.writeFileSync(path.join(ART, `${LABEL}.json`), `${JSON.stringify({ step: LABEL, ok, runs: step === 'field' ? Number(opt('--runs', 5)) : undefined, minecraft: MC_VERSION, architect: ARCH_VERSION, at: new Date().toISOString(), head, ...stamp(), world: worldName, seconds: (Date.now() - t0) / 1000, spendUsd: 0, error, checks, data }, null, 2)}\n`);
  log(`${step}: ${ok ? 'PASS' : 'FAIL'}, ${checks.filter((c) => c.ok).length}/${checks.length} checks, ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  process.exit(ok ? 0 : 1);
} else {
  console.log(`usage: node tools/module-measure.mjs ${Object.keys(steps).join('|')} [--attach] [--keep-client]`);
  process.exit(2);
}
