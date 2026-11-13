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
const logFile = path.join(ART, `${step ?? 'none'}.log`);
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
async function startClient(label) {
  const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);
  if (flag('--attach')) { dev = await DevClient.connect({ port: PORT, timeoutMs: 30_000 }); await dev.waitInWorld({ timeoutMs: 60_000 }); return; }
  claimRun();
  for (const p of [PORT - 1, PORT]) if (await listening(p)) throw new Error(`port ${p} is in use (another client?): refusing to launch; use --attach to drive it`);
  if (fs.existsSync(path.join(GAME, 'architect', 'devbridge.token'))) fs.rmSync(path.join(GAME, 'architect', 'devbridge.token'));
  if (fs.existsSync(path.join(GAME, 'architect', 'sidecar-data', 'secrets.json'))) throw new Error('the claude-login opt-in exists: refusing');
  worldName = `Steward M2 ${label} ${new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')}`;
  const out = fs.openSync(path.join(ART, 'client.log'), 'a');
  child = spawn(path.join(ROOT, 'tools', 'run-e2e-client.sh'), [], {
    stdio: ['ignore', out, out],
    env: { ...process.env, STEWARD_PORT: String(PORT - 1), STEWARD_DEV_PORT: String(PORT), ARCHITECT_AUTOWORLD_NAME: worldName, E2E_STUB: '0', E2E_NODE_DIR: path.dirname(process.execPath), GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? path.join(MAIN_CHECKOUT, '.gradle-home') },
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
  const r = await readCells(cells);
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
  for (const s of specs) lots.push((await mod({ op: 'growLot', id: s.id, width: s.width, depth: s.depth })).lot);
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
      out.farms.push(r);
      log(`  ${json(r)}`);
    }
    // more growths, for the fronts: small farms on the frontage, then on an extension
    out.fronts = g.farms.map((f) => f.lot.front);
    for (const [k, prefer] of [['d', 'frontage'], ['e', 'extend'], ['f', 'extend']]) {
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

const steps = { look, bands: bandsStep };
if (steps[step]) {
  const t0 = Date.now();
  let data, error;
  try { data = await steps[step](); } catch (e) { error = String(e?.stack ?? e); check(false, `${step}: ${String(e?.message ?? e).slice(0, 400)}`); }
  const ok = checks.every((c) => c.ok);
  const head = execFileSync('git', ['-C', ROOT, 'rev-parse', 'HEAD']).toString().trim();
  fs.writeFileSync(path.join(ART, `${step}.json`), `${JSON.stringify({ step, ok, at: new Date().toISOString(), head, ...stamp(), world: worldName, seconds: (Date.now() - t0) / 1000, spendUsd: 0, error, checks, data }, null, 2)}\n`);
  log(`${step}: ${ok ? 'PASS' : 'FAIL'}, ${checks.filter((c) => c.ok).length}/${checks.length} checks, ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  process.exit(ok ? 0 : 1);
} else {
  console.log(`usage: node tools/module-measure.mjs ${Object.keys(steps).join('|')} [--attach] [--keep-client]`);
  process.exit(2);
}
