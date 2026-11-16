#!/usr/bin/env node
// Phase 5 slice M4 (docs/MEASURE-M4.md 4.1): storage_hall@1's plain hall (slots 0) measured in a dev client, $0, no Claude. The harness is
// M2's (tools/module-measure.mjs): its own client through tools/run-e2e-client.sh on 8598/8599 (STEWARD_PORT / STEWARD_DEV_PORT) in a fresh
// flat world, V towns (Architect's flat_village), the catalog entry installed at $0 by its key (`/steward dev module`), the towns grown by
// storage_hall lots (planRevision grow lots from Steward's StorageHallLot), a keyed variant per lot, realised.
//
//   node tools/module-measure-hall.mjs hall        bars 1-6: shape, storage, restart and unload, light, undo with cargo, undo emptied (creative)
//   node tools/module-measure-hall.mjs survival    bar 7: the bill (consumed == the quote), a cancel mid-build, the refunds (survival)
//   node tools/module-measure-hall.mjs bounds      bar 7: the quote per bulk branch and measured Style (the offer's bound)
//   --attach        drive a client already on STEWARD_DEV_PORT (default 8599); --keep-client leaves the launched one running
//
// Evidence: artifacts/m4/<step>.json (gitignored) and stdout ("ok"/"FAIL" per check). Exit code 1 when a check fails.
import { spawn, execFileSync } from 'node:child_process';
import net from 'node:net';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAIN_CHECKOUT = path.resolve(execFileSync('git', ['-C', ROOT, 'rev-parse', '--git-common-dir']).toString().trim(), '..');
const ARCH = process.env.ARCHITECT_CHECKOUT ?? path.resolve(MAIN_CHECKOUT, '..', 'architect-mc');
const GAME = path.join(ROOT, 'mod', 'run');
process.env.ARCHITECT_GAME_DIR = GAME;
const PORT = Number(process.env.STEWARD_DEV_PORT ?? 8599);
const VERSION = Number((/--version[= ](\d+)/.exec(process.argv.join(' ')) ?? [])[1] ?? 1);
// (M4b) storage_hall@2's evidence apart from @1's: artifacts/m4b
const ART = path.join(ROOT, 'artifacts', VERSION >= 2 ? 'm4b' : 'm4');
fs.mkdirSync(ART, { recursive: true });

const argv = process.argv.slice(2);
const step = argv[0];
const flag = (f) => argv.includes(f);
const opt = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
const CATALOG = path.resolve(ROOT, opt('--catalog', 'modules'));
const MODULE = { id: 'storage_hall', version: VERSION };
const DIR = path.join(CATALOG, MODULE.id, String(MODULE.version));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const json = (o) => JSON.stringify(o);
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
const BULKS = MODULE_JSON.envelope.branches.bulk;

// ------------------------------------------------------------------ the program's own layout (the program run on the pinned kit)

let programMod = null;
async function program() {
  if (programMod) return programMod;
  const kitRoot = path.join(ROOT, 'artifacts', 'm1', `kit-${ARCH_VERSION}`);
  if (!fs.existsSync(path.join(kitRoot, 'kit', 'build.mjs'))) {
    fs.mkdirSync(kitRoot, { recursive: true });
    const tar = execFileSync('git', ['-C', ARCH, 'archive', `v${ARCH_VERSION}`, 'kit'], { maxBuffer: 1 << 30 });
    execFileSync('tar', ['-x', '-C', kitRoot], { input: tar });
  }
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'steward-m4-'));
  const lib = pathToFileURL(path.join(kitRoot, 'kit', 'lib')).href;
  fs.writeFileSync(path.join(dir, 'storage_hall.mjs'), fs.readFileSync(path.join(DIR, 'program.mjs'), 'utf8').replaceAll("'../lib/", `'${lib}/`));
  programMod = await import(pathToFileURL(path.join(dir, 'storage_hall.mjs')).href);
  return programMod;
}

/** IRR's lot frame (as tools/module-measure.mjs): {front, size, floorY, box, toWorld(u, y, v)} for a plan lot {box, front, floorY}. */
function lotFrame(lot) {
  const b = Array.isArray(lot.box) ? { minX: lot.box[0], minY: lot.box[1], minZ: lot.box[2], maxX: lot.box[3], maxY: lot.box[4], maxZ: lot.box[5] } : lot.box;
  const f = lot.front ?? 'south', fy = lot.floorY ?? b.minY;
  const T = {
    south: { from: (u, v) => [b.minX + u, b.minZ + v] },
    north: { from: (u, v) => [b.maxX - u, b.maxZ - v] },
    east: { from: (u, v) => [b.minX + v, b.maxZ - u] },
    west: { from: (u, v) => [b.maxX - v, b.minZ + u] },
  }[f];
  return { front: f, floorY: fy, box: b, toWorld: (u, y, v) => { const [x, z] = T.from(u, v); return [x, y + fy, z]; } };
}

// ------------------------------------------------------------------ the client (M2's)

let dev = null, child = null, launched = false, exited = false, clientMode = 'creative';
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
async function startClient(label, { mode = 'creative', world = null } = {}) {
  const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);
  if (flag('--attach')) { dev = await DevClient.connect({ port: PORT, timeoutMs: 30_000 }); await dev.waitInWorld({ timeoutMs: 60_000 }); return; }
  // a relaunch after a kill point's JVM halt (world: the same world, as the halt left it) keeps this run's claim
  if (!world) claimRun();
  // the load gate (AGENTS.md speed rules): wait at most E2E_LOAD_WAIT_MIN (15) minutes for the 1-minute load to fall under E2E_LOAD_MAX (72)
  const loadMax = Number(process.env.E2E_LOAD_MAX ?? 72), waitMin = Math.min(15, Number(process.env.E2E_LOAD_WAIT_MIN ?? 15));
  for (const end = Date.now() + waitMin * 60_000; os.loadavg()[0] >= loadMax && Date.now() < end;) await sleep(30_000);
  log(`load ${os.loadavg()[0].toFixed(0)} at the start (gate ${loadMax}, waited at most ${waitMin} min)`);
  for (const p of [PORT - 1, PORT]) if (await listening(p)) throw new Error(`port ${p} is in use (another client?): refusing to launch; use --attach to drive it`);
  if (fs.existsSync(path.join(GAME, 'architect', 'devbridge.token'))) fs.rmSync(path.join(GAME, 'architect', 'devbridge.token'));
  if (fs.existsSync(path.join(GAME, 'architect', 'sidecar-data', 'secrets.json'))) throw new Error('the claude-login opt-in exists: refusing');
  worldName = world ?? `Steward M4 ${label} ${new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')}`;
  exited = false;
  clientMode = mode;
  const out = fs.openSync(path.join(ART, 'client.log'), 'a');
  child = spawn(path.join(ROOT, 'tools', 'run-e2e-client.sh'), [], {
    stdio: ['ignore', out, out],
    env: { ...process.env, STEWARD_PORT: String(PORT - 1), STEWARD_DEV_PORT: String(PORT), ARCHITECT_AUTOWORLD_NAME: worldName, ARCHITECT_AUTOWORLD_MODE: mode, E2E_STUB: '0', E2E_NODE_DIR: path.dirname(process.execPath), GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? path.join(MAIN_CHECKOUT, '.gradle-home') },
  });
  launched = true;
  child.once('exit', () => { exited = true; });
  log(`client launched (launcher pid ${child.pid}), world "${worldName}", ports ${PORT - 1}/${PORT}`);
  const died = new Promise((_, reject) => child.once('exit', (code) => reject(new Error(`the launched client exited (code ${code}) before its bridge answered: see artifacts/m4/client.log`))));
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
const cmd = async (c) => req('dev.command', { cmd: c });
let nmod = 0;
async function mod(r, timeoutMs = 900_000) {
  const lab = `m4_${process.pid}_${++nmod}`;
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
async function readCells(cells) {
  const out = [];
  for (let i = 0; i < cells.length; i += 4096) {
    const a = await mod({ op: 'cells', cells: cells.slice(i, i + 4096) });
    if (a.error) throw new Error(`cells: ${a.error}`);
    out.push(...a.cells);
  }
  return out;
}

// ------------------------------------------------------------------ the world, the towns and the halls

const OWNER = 'steward_mc:measure';
async function rules() {
  for (const r of ['random_tick_speed 3', 'mob_griefing true', 'advance_time false', 'advance_weather false', 'spawn_mobs false', 'spawn_monsters false', 'fire_spread_radius_around_player 0']) await cmd(`/gamerule ${r}`);
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
async function standAt(x, z, y) {
  await cmd(`/tp @p ${x} ${y} ${z}`);
  await call('dev.waitChunks', { timeoutMs: 120_000 }, 180_000).catch(() => null);
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
const TOWN_LOTS = (n) => [{ id: 'hall_1', type: 'hall', size: [13, 11], landmark: true }, ...Array.from({ length: n - 1 }, (_, i) => ({ id: `cottage_${i + 1}`, type: 'cottage', size: [9, 9] }))];
async function town(cx, cz, { w = 224, d = 176, lots = 4, orientation = 'ew' } = {}) {
  await standAt(cx + w / 2, cz + d / 2, 0);
  const gy = await groundY(cx + w / 2, cz + d / 2);
  await standAt(cx + w / 2, cz + d / 2, gy + 30);
  const claim = [cx, cz, cx + w - 1, cz + d - 1];
  const p = await call('dev.region.plan', { program: 'flat_village', claim, params: { lots: TOWN_LOTS(lots), orientation }, surveyLoad: 'bounded:256', check: false, owner: OWNER }, 900_000);
  if (p.refused) throw new Error(`town plan refused: ${p.refused}`);
  const r = await call('dev.region.realise', { planId: p.planId, load: 'bounded:512', lots: {} }, 300_000);
  if (r.refused) throw new Error(`town realise refused: ${json(r.refused)}`);
  const st = await waitRegion(r.region);
  check(st.view.state === 'PLACED', `a V town ${r.region} (flat_village ${orientation}, ${lots} pads) at ${claim.join(',')}: ${st.view.state}`);
  return { region: r.region, claim, gy, cx: cx + w / 2, cz: cz + d / 2 };
}
let installed = null;
async function install() {
  if (installed) return installed;
  // the helper reports its features (givenPrograms) once it is up: wait for it, at most 5 minutes
  for (let i = 0; i < 60; i++) {
    const env = await mod({ op: 'env' });
    if (env.givenPrograms === true) break;
    await sleep(5000);
  }
  const a = await mod({ op: 'install', dir: CATALOG, id: MODULE.id, version: MODULE.version, measuring: true });
  check(a.ok && a.entryId, `${MODULE.id}@${MODULE.version} installed at $0 by its key ${a.key} as ${a.entryId}${a.ok ? '' : `: ${a.detail} ${a.message}`}`);
  if (!a.ok) throw new Error('install failed');
  installed = a;
  return a;
}
const run36 = Date.now().toString(36);
/**
 * Grows `region` by halls `specs` [{id, bulk, bible}] in one revision: Steward's grow lots (StorageHallLot, checked against the program's
 * frame), a keyed variant per lot, realised. Returns {rid, planId, position, halls: [{...spec, lot, frame, entryId}], quote?, refused?}.
 */
/** A hall spec's params as the program and Steward take them: {slots, bulk} and, for @2, item1..item16 (its `items`, then none). */
function paramsOf(s) {
  const o = { slots: s.slots ?? 0, bulk: s.bulk };
  if (MODULE.version >= 2) for (let i = 0; i < 16; i++) o[`item${i + 1}`] = s.items?.[i] ?? 'none';
  return o;
}
async function growHalls(region, specs, { survival = false, realise = true } = {}) {
  const P = await program();
  const lots = [];
  for (const s of specs) {
    const lot = (await mod({ op: 'hallLot', id: s.id, slots: s.slots ?? 0, bulk: s.bulk })).lot;
    const L = P.layout(s.slots ?? 0, s.bulk);
    check(lot.size[0] === L.W && lot.size[1] === L.D && lot.type === 'storage_hall', `${s.id}: StorageHallLot's lot = the program's frame (${lot.size.join('x')})`);
    // the lot ext Steward's placement adds (SettlementRunner, ModuleRef): the module's identity on the placed site, which the steward's words read
    lot.ext = { ...(lot.ext ?? {}), 'steward_mc:module': { id: MODULE.id, version: MODULE.version, programSha: stamp().programSha, params: paramsOf(s) } };
    lots.push(lot);
  }
  let p = null;
  for (const pr of ['frontage', 'extend']) {
    p = await req('dev.rv.plan', { region, program: 'flat_village', params: { grow: { lots, prefer: pr } }, opKey: `steward.m4${run36}.${specs[0].id}.plan` }, 900_000);
    if (p.ok && p.planId) break;
    log(`  grow (${pr}) refused: ${String(p.refused ?? p.error).slice(0, 300)}`);
  }
  if (!p?.planId) return { refused: p?.refused ?? p?.error };
  const halls = [];
  const ents = {};
  for (const s of specs) {
    const lot = (p.lots ?? []).find((l) => l.id === s.id);
    if (!lot) return { refused: `no lot ${s.id}` };
    const v = await mod({ op: 'variant', dir: CATALOG, id: MODULE.id, version: MODULE.version, measuring: true, params: paramsOf(s),
      bible: { id: s.bible, version: 0 }, key: `steward.m4${run36}.module.${s.id}` });
    if (!v.ok) return { refused: `variant of ${s.id}: ${v.detail} ${v.message} ${json(v.findings)}` };
    ents[s.id] = v.entryId;
    halls.push({ ...s, lot, frame: lotFrame(lot), entryId: v.entryId });
  }
  const quote = survival ? await req('dev.region.quote', { planId: p.planId, lotEntries: ents }, 300_000) : null;
  if (!realise) return { planId: p.planId, halls, quote, plan: p, ents };
  const r = await req('dev.region.realise', { planId: p.planId, opKey: `steward.m4${run36}.${specs[0].id}.realise`, lots: ents }, 300_000);
  if (!r.ok || r.refused) return { refused: `realise: ${r.refused ?? r.error}` };
  return { rid: p.revisionOf.rid, planId: p.planId, position: p.revisionOf.position, halls, quote, plan: p };
}
/** After PLACED: each hall's site, its containers in world cells, its box, its light cells, its chunk-border crossings. */
async function hallCells(region, halls) {
  const P = await program();
  const st = await call('dev.region.state', { region }, 60_000);
  for (const h of halls) {
    const lv = st.view.lots.find((l) => l.id === h.id);
    h.siteId = lv?.siteId ?? null;
    h.state = lv?.state ?? null;
    const L = P.layout(h.slots ?? 0, h.bulk);
    const F = h.frame;
    const w = (c) => F.toWorld(c[0], c[1] - 1, c[2]); // template row r = frame y r - 1
    h.L = L;
    h.input = w(L.input);
    h.pairs = L.bulk.map((b) => [w(b.lo), w(b.hi)]);
    // the inside's air cells at row 2 (z 1..D-3, x 1..W-2) and the front row's cells at row 2
    h.light = [];
    // (a sorting hall's circuit zone is closed fixed stone with one-high gaps: out of the light bar, MEASURE-M4 12.2)
    const zoned = (x, z) => L.zone && x >= L.zone.x0 && x <= L.zone.x1 && z >= L.zone.z0 && z <= L.zone.z1;
    for (let z = 1; z <= L.D - 3; z++) for (let x = 1; x <= L.W - 2; x++) if (!zoned(x, z)) h.light.push(w([x, 2, z]));
    if (L.slots) {
      h.sort = L.slots.map((c) => ({ line: w(c.line), F: w(c.F), D: w(c.D), chest: w(c.chest) }));
      h.overflow = w(L.overflow);
      h.lever = w(L.lever);
      h.filterIds = (h.items ?? []).map((k) => `minecraft:${k}`);
    }
    for (let x = 0; x < L.W; x++) h.light.push(w([x, 2, L.D - 1]));
    const b = F.box;
    h.boxArr = [b.minX, F.floorY - 1, b.minZ, b.maxX, F.floorY + 6, b.maxZ];
    h.chunkBorder = { x: Math.floor(b.minX / 16) !== Math.floor(b.maxX / 16), z: Math.floor(b.minZ / 16) !== Math.floor(b.maxZ / 16) };
  }
  return halls;
}
const containersIn = async (box) => mod({ op: 'containers', box });
const CW = { north: 'east', east: 'south', south: 'west', west: 'north' };
const DXZ = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] };
const stateProp = (s, k) => (new RegExp(`[\\[,]${k}=([a-z_]+)`).exec(s) ?? [])[1];
/** Bar 1's container reading: every pair a double chest as vanilla pairs them, the input chest single; {ok, why}. */
async function shapeOf(h) {
  const r = await containersIn(h.boxArr);
  const by = new Map(r.containers.map((c) => [c.at.join(','), c]));
  const why = [];
  const inp = by.get(h.input.join(','));
  if (!inp || inp.block !== 'minecraft:chest' || stateProp(inp.state, 'type') !== 'single') why.push(`input ${h.input} ${inp?.state ?? 'missing'}`);
  for (const [a, b] of h.pairs) {
    const ca = by.get(a.join(',')), cb = by.get(b.join(','));
    if (!ca || !cb) { why.push(`pair ${a}/${b} missing`); continue; }
    // the LEFT half's partner lies clockwise of its facing (ChestBlock.getConnectedDirection)
    let ok = false;
    for (const [L, R] of [[ca, cb], [cb, ca]]) {
      const f = stateProp(L.state, 'facing');
      if (stateProp(L.state, 'type') !== 'left' || stateProp(R.state, 'type') !== 'right' || stateProp(R.state, 'facing') !== f) continue;
      const d = DXZ[CW[f]];
      if (L.at[0] + d[0] === R.at[0] && L.at[2] + d[1] === R.at[2] && L.at[1] === R.at[1]) ok = true;
    }
    if (!ok) why.push(`pair ${a}/${b} not a double chest: ${ca.state} / ${cb.state}`);
  }
  if (r.containers.length !== 1 + 2 * h.pairs.length) why.push(`${r.containers.length} containers in the box, ${1 + 2 * h.pairs.length} expected`);
  return { ok: why.length === 0, why };
}
/** Bar 2's mix, one container's worth (13 slots: 8 plain 64-stacks, 3 unstackables, a 16-stack, a component stack). */
const MIX = [
  ...['cobblestone', 'oak_planks', 'iron_ingot', 'wheat', 'dirt', 'sand', 'coal', 'andesite'].map((id, i) => ({ slot: i, id: `minecraft:${id}`, count: 64 })),
  ...[8, 9, 10].map((slot) => ({ slot, id: 'minecraft:iron_sword', count: 1 })),
  { slot: 11, id: 'minecraft:ender_pearl', count: 16 },
  { slot: 12, id: 'minecraft:cobblestone', count: 64, name: 'Not filtered' },
];
const mixKey = (s) => `${s.id}${s.name ? `#${s.name}` : ''}`;
/** Every container cell of a hall, world coordinates. */
const cellsOf = (h) => [h.input, ...h.pairs.flat()];
/** The hall's containers as {cell: [[slot, key, count]]}. */
async function snapshot(h) {
  const r = await containersIn(h.boxArr);
  return { unloaded: r.unloaded, by: Object.fromEntries(r.containers.map((c) => [c.at.join(','), c.slots])), totals: r.totals };
}
const expectedSlots = () => MIX.map((s) => [s.slot, mixKey(s), s.count]);
const sameSnap = (a, b) => json(Object.entries(a.by).sort()) === json(Object.entries(b.by).sort());
const same = (a, b) => { for (const x of new Set([...Object.keys(a ?? {}), ...Object.keys(b ?? {})])) if ((a?.[x] ?? 0) !== (b?.[x] ?? 0)) return false; return true; };
const minus = (a, b) => { const o = {}; for (const x of new Set([...Object.keys(a ?? {}), ...Object.keys(b ?? {})])) { const d = (a?.[x] ?? 0) - (b?.[x] ?? 0); if (d) o[x] = d; } return o; };
const plus = (a, b) => { const o = { ...(a ?? {}) }; for (const [k, v] of Object.entries(b ?? {})) o[k] = (o[k] ?? 0) + v; for (const k of Object.keys(o)) if (!o[k]) delete o[k]; return o; };
const land = (region) => call('dev.region.landCheck', { region }, 900_000);
const lotRow = (rep, id) => (rep.lots ?? []).find((l) => l.lot === id) ?? null;

async function reopen(pos) {
  await cmd('/save-all flush');
  await call('dev.world.leave', {}, 300_000);
  await sleep(2000);
  await call('dev.world.open', { name: worldName }, 300_000);
  await dev.waitInWorld({ timeoutMs: 600_000 });
  await rules();
  await standAt(...pos);
}

// ------------------------------------------------------------------ hall: bars 1-6

/** An undo answer refused because a container holds cargo (Architect's removal blockers, or its refusal naming them), never for another reason. */
const refusal = (u) => `removed ${u.removed}; blockers ${json((u.blockers ?? []).slice(0, 2))}; ${String(u.error ?? u.refused ?? u.message ?? '').slice(0, 160)}`;
const refusedForCargo = (u) => u.removed !== true && /Move these out of \S+ first[^;]*(chest|barrel|hopper) at|container|cargo/i.test(`${json(u.blockers ?? [])} ${u.error ?? ''} ${u.refused ?? ''} ${u.message ?? ''}`);

async function hall() {
  await startClient('Hall');
  const out = { towns: [], halls: {}, growths: [] };
  try {
    await rules();
    await install();
    // two towns, one per street orientation (ew fronts lots north and south, ns east and west); every bulk branch in rustic, and fortress and
    // desert halls of the largest and smallest bulk; a third and fourth town at shifted claims for chunk-border crossings in both axes
    const towns = [await town(0, 0, { orientation: 'ew' }), await town(256, 0, { w: 176, d: 224, orientation: 'ns' }), await town(8, 504, { orientation: 'ew' }), await town(264, 509, { w: 176, d: 224, orientation: 'ns' })];
    out.towns = towns.map((t) => ({ region: t.region, claim: t.claim }));
    const specs = [
      ...BULKS.map((b) => ({ id: `h${b}_rus`, bulk: b, bible: 'rustic' })),
      ...[0, 8].flatMap((b) => [{ id: `h${b}_for`, bulk: b, bible: 'fortress' }, { id: `h${b}_des`, bulk: b, bible: 'desert' }]),
      ...[2, 5].map((b, i) => ({ id: `h${b}_rus_b${i}`, bulk: b, bible: 'rustic' })),
      ...[3, 6].map((b, i) => ({ id: `h${b}_rus_c${i}`, bulk: b, bible: 'rustic' })),
    ];
    // spread over the towns in revisions of up to 3 lots
    const groups = [[0, 1, 2], [3, 4], [5, 6, 7], [8, 9], [10, 11], [12, 13], [14], [15], [16], [17]];
    const townOf = [0, 0, 1, 1, 0, 1, 2, 3, 2, 3];
    const halls = [];
    for (const [gi, idx] of groups.entries()) {
      const t = towns[townOf[gi]];
      const sp = idx.map((i) => specs[i]).filter(Boolean);
      if (!sp.length) continue;
      await standAt(t.cx, t.cz, t.gy + 30);
      const g = await growHalls(t.region, sp);
      if (!check(!g.refused, `growth ${sp.map((s) => s.id).join(', ')} in ${t.region}: ${g.refused ? String(g.refused).slice(0, 300) : `rid ${g.rid}`}`)) continue;
      const w = await rvWait(t.region, g.rid);
      check(w.r.state === 'PLACED', `growth rid ${g.rid}: ${w.r.state}`);
      await hallCells(t.region, g.halls);
      for (const h of g.halls) { h.region = t.region; h.rid = g.rid; h.position = g.position; h.town = t; }
      out.growths.push({ region: t.region, rid: g.rid, position: g.position, halls: g.halls.map((h) => h.id) });
      halls.push(...g.halls);
    }
    // ---- 1: shape
    for (const t of towns) {
      await standAt(t.cx, t.cz, t.gy + 30);
      const rep = await land(t.region);
      for (const h of halls.filter((x) => x.region === t.region)) {
        const row = lotRow(rep, h.id);
        const sh = await shapeOf(h);
        out.halls[h.id] = { bulk: h.bulk, bible: h.bible, front: h.frame.front, chunkBorder: h.chunkBorder, box: h.boxArr, built: row };
        check(row?.changed === 0 && row?.standing === 1 && sh.ok, `1 shape ${h.id} (${h.bible}, bulk ${h.bulk}, front ${h.frame.front}): standing ${row?.standing}, changed ${row?.changed}; ${sh.ok ? `${h.pairs.length} double chests paired, the input chest single` : sh.why.join('; ')}`);
      }
    }
    const fronts = new Set(halls.map((h) => h.frame.front));
    check(['north', 'east', 'south', 'west'].every((f) => fronts.has(f)), `4.3 facings: every one covered (${[...fronts].join(', ')})`);
    const bx = halls.filter((h) => h.chunkBorder.x).map((h) => h.id), bz = halls.filter((h) => h.chunkBorder.z).map((h) => h.id), both = halls.filter((h) => h.chunkBorder.x && h.chunkBorder.z).map((h) => h.id);
    check(bx.length > 0 && bz.length > 0, `4.3 chunk borders: halls crossing in x ${json(bx)}, in z ${json(bz)}, in both ${json(both)}`);
    check(new Set(halls.filter((h) => h.bible === 'rustic').map((h) => h.bulk)).size === BULKS.length, `4.3 branches: every bulk ${json(BULKS)} placed in rustic`);
    // ---- 2: storage conserves
    const fed = {};
    for (const t of towns) {
      await standAt(t.cx, t.cz, t.gy + 30);
      for (const h of halls.filter((x) => x.region === t.region)) {
        let ok = true;
        for (const c of cellsOf(h)) { const a = await mod({ op: 'putItems', at: c, stacks: MIX }); ok = ok && a.ok; }
        const s = await snapshot(h);
        const want = expectedSlots();
        const exact = cellsOf(h).every((c) => json(s.by[c.join(',')]) === json(want));
        fed[h.id] = s;
        check(ok && exact, `2 storage ${h.id}: the mix in each of its ${cellsOf(h).length} containers reads back slot by slot`);
      }
    }
    // ---- 4: light at midnight (mob safety)
    await cmd('/time set 18000');
    for (const t of towns) {
      await standAt(t.cx, t.cz, t.gy + 30);
      for (const h of halls.filter((x) => x.region === t.region)) {
        const r = await readCells(h.light);
        const air = r.map((c, i) => [c, h.light[i]]).filter(([c]) => c && (c[0] === 'minecraft:air' || c[0] === 'minecraft:cave_air'));
        const dark = air.filter(([c]) => c[3] < 1).map(([, p]) => p);
        out.halls[h.id].light = { cells: air.length, min: Math.min(...air.map(([c]) => c[3])), dark };
        check(dark.length === 0 && air.length > 0, `4 light ${h.id} (${h.bible}): ${air.length} air cells inside and on the front row, the darkest ${out.halls[h.id].light.min}`);
      }
    }
    await cmd('/time set 6000');
    // ---- 3: a restart, then an unload
    const home = [towns[0].cx, towns[0].cz, towns[0].gy + 30];
    await reopen(home);
    for (const t of towns) {
      await standAt(t.cx, t.cz, t.gy + 30);
      const rep = await land(t.region);
      for (const h of halls.filter((x) => x.region === t.region)) {
        const s = await snapshot(h);
        const ents = await mod({ op: 'takeItems', box: h.boxArr });
        const row = lotRow(rep, h.id);
        check(sameSnap(s, fed[h.id]) && ents.entities === 0 && row?.standing === 1, `3 restart ${h.id}: every container as before (${sameSnap(s, fed[h.id])}), ${ents.entities} item entities, standing ${row?.standing}`);
      }
    }
    await standAt(home[0] + 6000, home[1] + 6000, 120);
    await cmd('/tick unfreeze');
    // the halls' chunks really unload: one probe per chunk any hall's box touches (a cell of an unloaded chunk reads null), then 600 game ticks
    // pass with them unloaded (the game time read from the world, not the wall clock)
    const probes = [];
    const seen = new Set();
    for (const h of halls) {
      const [x0, y0, z0, x1, , z1] = h.boxArr;
      for (let cx = Math.floor(x0 / 16); cx <= Math.floor(x1 / 16); cx++) for (let cz = Math.floor(z0 / 16); cz <= Math.floor(z1 / 16); cz++) {
        if (seen.has(`${cx},${cz}`)) continue;
        seen.add(`${cx},${cz}`);
        probes.push([Math.max(x0, Math.min(x1, cx * 16 + 8)), y0 + 2, Math.max(z0, Math.min(z1, cz * 16 + 8))]);
      }
    }
    let loaded = probes.length;
    for (let i = 0; i < 180 && loaded > 0; i++) { await sleep(1000); loaded = (await readCells(probes)).filter((c) => c !== null).length; }
    check(loaded === 0, `3 unload: every chunk the halls' boxes touch unloaded once the player is 6,000 blocks away (${probes.length - loaded}/${probes.length} chunks)`);
    const t0 = (await mod({ op: 'cells', cells: [] })).gameTime;
    let t1 = t0;
    for (let i = 0; i < 600 && t1 - t0 < 600; i++) { await sleep(1000); t1 = (await mod({ op: 'cells', cells: [] })).gameTime; }
    check(t1 - t0 >= 600, `3 unload: ${t1 - t0} game ticks passed with the halls unloaded`);
    for (const t of towns) {
      await standAt(t.cx, t.cz, t.gy + 30);
      const rep = await land(t.region);
      for (const h of halls.filter((x) => x.region === t.region)) {
        const s = await snapshot(h);
        const ents = await mod({ op: 'takeItems', box: h.boxArr });
        const row = lotRow(rep, h.id);
        check(sameSnap(s, fed[h.id]) && ents.entities === 0 && row?.standing === 1, `3 unload ${h.id}: every container as before after its chunks unloaded and loaded (${sameSnap(s, fed[h.id])}), ${ents.entities} item entities, standing ${row?.standing}`);
      }
    }
    // ---- 5 and 6: undo each growth, newest first: refused while its halls hold items, then emptied and undone. The player watches as a spectator
    // (it still loads the chunks, but stands in no site: a player in a site's box refuses the undo as OCCUPIED, run 2's first finding)
    await cmd('/gamemode spectator @p');
    const gone = new Set();
    for (const g of [...out.growths].reverse()) {
      const t = towns.find((x) => x.region === g.region);
      await standAt(t.cx, t.cz, t.gy + 30);
      const gh = halls.filter((h) => h.rid === g.rid && h.region === g.region);
      const before = {};
      for (const h of gh) before[h.id] = await snapshot(h);
      const rep0 = await land(g.region);
      const u = await call('dev.rv.undo', { region: g.region, toPosition: g.position - 1 }, 3_600_000).catch((e) => ({ error: String(e) }));
      await sleep(3000);
      // the steward's words from Architect's refusal (Steward's HallCargo, as Growths says them): only the sites it names
      const words = (await mod({ op: 'hallCargo', owner: OWNER, region: g.region, message: `${u.error ?? ''} ${u.refused ?? ''} ${u.message ?? ''} ${(u.blockers ?? []).join(', ')}` })).words;
      let unchanged = true;
      for (const h of gh) unchanged = unchanged && sameSnap(await snapshot(h), before[h.id]);
      const rep1 = await land(g.region);
      const standingSame = gh.every((h) => lotRow(rep0, h.id)?.standing === lotRow(rep1, h.id)?.standing);
      // the words count this growth's halls only (the sites the refusal names), a double chest once: the input chest and each pair
      let items = 0, cont = 0;
      for (const h of gh) { items += Object.values(before[h.id].totals).reduce((x, y) => x + y, 0); cont += 1 + h.pairs.length; }
      g.cargo = { removed: u.removed ?? null, blockers: u.blockers ?? [], error: u.error ?? u.refused ?? null, words };
      check(refusedForCargo(u) && unchanged && standingSame, `5 undo with cargo rid ${g.rid} (${gh.map((h) => h.id).join(', ')}): refused for cargo (${refusal(u)}); the containers and standing unchanged`);
      check(typeof words === 'string' && words.includes(`${items.toLocaleString('en-US')} items in ${cont} chests`), `5 the steward's words: "${words}" (this growth's halls hold ${items} in ${cont} chests)`);
      for (const h of gh) await mod({ op: 'clearContainers', box: h.boxArr });
      // cargo in isolation, one container class at a time (the input chest; a bulk pair's either half, lower and upper row): each alone refuses
      // the undo, and is still there after
      const classes = [];
      for (const h of gh) {
        classes.push([h, 'input', h.input]);
        if (h.pairs[0]) classes.push([h, 'bulk lower, west half', h.pairs[0][0]], [h, 'bulk lower, east half', h.pairs[0][1]]);
        if (h.pairs[4]) classes.push([h, 'bulk upper, west half', h.pairs[4][0]], [h, 'bulk upper, east half', h.pairs[4][1]]);
      }
      for (const [h, what, cell] of classes) {
        await mod({ op: 'putItems', at: cell, stacks: [{ slot: 26, id: 'minecraft:cobblestone', count: 1 }] });
        const ui = await call('dev.rv.undo', { region: g.region, toPosition: g.position - 1 }, 3_600_000).catch((e) => ({ error: String(e) }));
        await sleep(2000);
        const still = (await snapshot(h)).by[cell.join(',')];
        check(refusedForCargo(ui) && json(still) === json([[26, 'minecraft:cobblestone', 1]]), `5 one item alone in ${h.id}'s ${what} ${cell}: the undo refused for cargo (${refusal(ui)}), the item still there`);
        await mod({ op: 'clearContainers', box: h.boxArr });
      }
      const u2 = await call('dev.rv.undo', { region: g.region, toPosition: g.position - 1 }, 3_600_000).catch((e) => ({ error: String(e) }));
      await sleep(3000);
      let drops = 0;
      for (const h of gh) drops += (await mod({ op: 'takeItems', box: [h.boxArr[0] - 2, h.boxArr[1] - 2, h.boxArr[2] - 2, h.boxArr[3] + 2, h.boxArr[4] + 4, h.boxArr[5] + 2] })).entities;
      const rv = await call('dev.rv.state', { region: g.region }, 60_000);
      const rs = rv.revisions.find((x) => x.rid === g.rid)?.state;
      g.emptied = { removed: u2.removed ?? null, blockers: u2.blockers ?? [], state: rs, drops };
      for (const h of gh) gone.add(h.id);
      check(u2.removed === true && drops === 0, `6 undo emptied rid ${g.rid}: removed ${u2.removed} (${rs}), blockers ${json(u2.blockers ?? [])}, ${drops} item entities`);
    }
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

// ------------------------------------------------------------------ survival: bar 7

const pile = async () => (await call('dev.stockpile.state', { owner: OWNER }, 60_000)).stockpile ?? {};
async function feedUntil(region, until, { timeoutMs = 3_600_000, every = 2500, share = 1 } = {}) {
  const end = Date.now() + timeoutMs;
  let waves = 0, st = null, rs = null;
  while (Date.now() < end) {
    st = await call('dev.region.state', { region }, 60_000);
    rs = await call('dev.rs.state', { region }, 120_000).catch(() => null);
    if (await until(st, rs)) return { waves, st, rs, ok: true };
    if (['FAILED', 'PARTIAL'].includes(st.view.state)) break;
    const ps = await pile();
    const need = {};
    for (const [it, n] of Object.entries(ps.outstanding ?? {})) { const lack = Math.floor((n - (ps.stock?.[it] ?? 0)) * share); if (lack > 0) need[it] = lack; }
    if (Object.keys(need).length) { await req('dev.crate.insert', { site: `stockpile:${OWNER}`, items: need, exact: true }, 120_000); waves++; }
    await sleep(every);
  }
  return { waves, st, rs, ok: false };
}
async function survivalWorld(label, x0, z0) {
  await startClient(label, { mode: 'survival' });
  await cmd('/gamerule fall_damage false');
  await cmd('/effect give @p minecraft:resistance infinite 4 true');
  await cmd('/effect give @p minecraft:slow_falling infinite 0 true');
  await cmd('/effect give @p minecraft:saturation infinite 0 true');
  await call('dev.survival.set', { on: true });
  await rules();
  await standAt(x0 + 96, z0 - 30, 100);
  const gy = await groundY(x0 + 96, z0 - 30);
  await standAt(x0 + 96, z0 - 30, gy + 1);
  const crates = Array.from({ length: 8 }, (_, q) => [x0 - 6 + 2 * q, gy + 1, z0 - 6]);
  const cr = await req('dev.stockpile.create', { owner: OWNER, crates }, 120_000);
  check(cr.ok && !cr.refused, `a stockpile of 8 crates for ${OWNER}: ${json(cr.refused ?? cr.error ?? 'made')}`);
  const claim = [x0, z0, x0 + 191, z0 + 159];
  const p0 = await call('dev.region.plan', { program: 'flat_village', claim, params: { lots: TOWN_LOTS(2), orientation: 'ew' }, surveyLoad: 'bounded:256', check: false, owner: OWNER }, 900_000);
  const r0 = await call('dev.region.realise', { planId: p0.planId, load: 'bounded:512', lots: {} }, 300_000);
  const f0 = await feedUntil(r0.region, (st, rs) => st.view.state === 'PLACED' && (rs?.build ? rs.build.done : true));
  check(f0.ok, `the town ${r0.region} built in survival from the stockpile: ${f0.st?.view?.state} (${f0.waves} waves)`);
  await install();
  return { region: r0.region, gy, x0, z0 };
}

async function survival() {
  const out = { growths: [] };
  try {
    const T = await survivalWorld('Survival', 50000, 50000);
    const { region, gy, x0, z0 } = T;
    const groups = [[0, 1, 2], [3, 4, 5], [6, 7, 8]].map((g) => g.map((i) => BULKS[i]).filter((b) => b !== undefined));
    for (const bs of groups) {
      const sp = bs.map((b) => ({ id: `sv${b}`, bulk: b, bible: 'rustic' }));
      const ps0 = await pile();
      const g = await growHalls(region, sp, { survival: true });
      if (!check(!g.refused, `survival growth ${sp.map((s) => s.id).join(', ')}: ${g.refused ? String(g.refused).slice(0, 300) : `rid ${g.rid}`}`)) continue;
      const fg = await feedUntil(region, (st, rs) => st.view.state === 'PLACED' && (rs?.build ? rs.build.done : true));
      const w = await rvWait(region, g.rid);
      const ps1 = await pile();
      const consumed = minus(ps1.ledger?.consumed, ps0.ledger?.consumed);
      const quote = g.quote?.bom ?? null;
      out.growths.push({ rid: g.rid, position: g.position, halls: sp.map((s) => s.id), quote, quoteExact: g.quote?.exact, quoteNotes: g.quote?.notes, consumed, waves: fg.waves });
      Object.defineProperty(out.growths.at(-1), 'hallsFull', { value: g.halls, enumerable: false });
      check(w.r.state === 'PLACED' && quote && same(consumed, quote), `7 bill rid ${g.rid} (${sp.map((s) => s.id).join(', ')}): ${w.r.state}; consumed = the quote item for item (${same(consumed, quote ?? {}) ? 'equal' : `consumed ${json(minus(consumed, quote ?? {}))} more than quoted`}; ${Object.values(consumed).reduce((a, b) => a + b, 0)} items; exact ${g.quote?.exact})`, { quote, consumed });
    }
    // ---- a cancel mid-build: a growth fed half its bill once, then undone while it builds; refunded exactly what it consumed
    {
      const sp = [{ id: 'svc8', bulk: 8, bible: 'rustic' }];
      const ps0 = await pile();
      const g = await growHalls(region, sp, { survival: true });
      if (check(!g.refused, `cancel growth svc8: ${g.refused ? String(g.refused).slice(0, 300) : `rid ${g.rid}`}`)) {
        // fed as the bill growths are (deliveries of what its targets lack), but half of it a wave, until it has consumed something; then undone
        // at once, mid-build
        let half = {};
        for (let i = 0; i < 90; i++) {
          const ps = await pile();
          if (Object.values(minus(ps.ledger?.consumed, ps0.ledger?.consumed)).some((n) => n > 0)) break;
          const need = {};
          for (const [it, n] of Object.entries(ps.outstanding ?? {})) { const lack = Math.ceil((n - (ps.stock?.[it] ?? 0)) / 2); if (lack > 0) need[it] = lack; }
          if (Object.keys(need).length) { await req('dev.crate.insert', { site: `stockpile:${OWNER}`, items: need, exact: true }, 120_000); half = plus(half, need); }
          await sleep(4000);
        }
        await sleep(3000);
        const mid = await pile();
        const consumedMid = minus(mid.ledger?.consumed, ps0.ledger?.consumed);
        const u = await call('dev.rv.undo', { region, toPosition: g.position - 1 }, 3_600_000).catch((e) => ({ error: String(e) }));
        await sleep(6000);
        const after = await pile();
        const consumed = minus(after.ledger?.consumed, ps0.ledger?.consumed);
        const back = plus(minus(after.ledger?.returned, mid.ledger?.returned), minus(after.ledger?.refundOverflow, mid.ledger?.refundOverflow));
        const rv = await call('dev.rv.state', { region }, 60_000);
        out.cancel = { rid: g.rid, half, consumedMid, consumed, back, removed: u.removed ?? null, error: u.error ?? null, state: rv.revisions.find((x) => x.rid === g.rid)?.state };
        const tot = (m) => Object.values(m ?? {}).reduce((a, b) => a + b, 0);
        out.cancel.partial = tot(consumedMid) > 0 && tot(consumedMid) < tot(g.quote?.bom);
        check(out.cancel.partial && u.removed === true && same(back, consumed) && !Object.keys(minus(back, consumed)).some((k) => minus(back, consumed)[k] > 0),
          `7 cancel mid-build rid ${g.rid}: consumed ${tot(consumed)} of the quote's ${tot(g.quote?.bom)} items before the undo (removed ${u.removed}, ${out.cancel.state}); refunded exactly that (${same(back, consumed) ? 'equal' : `off by ${json(minus(back, consumed))}`})`, out.cancel);
      }
    }
    // ---- a construction block the player mined (finding 3 of the design review): one bulk chest half of the newest growth's largest hall,
    // broken by a fake player (PT sees the player's break). Its refund is then consumed less that chest, and the chest drops as the player's.
    const newest = out.growths.at(-1);
    if (newest) {
      const hs = await hallCells(region, newest.hallsFull);
      const h = [...hs].sort((a, b) => b.bulk - a.bulk)[0];
      if (h?.pairs[0]) {
        await standAt(h.input[0], h.input[2] + 6, h.input[1] + 2);
        await call('dev.pt.breaks', { at: [h.pairs[0][0]], placer: 'M4Player' }, 120_000);
        await sleep(1500);
        const dropped = (await mod({ op: 'takeItems', box: [h.boxArr[0] - 2, h.boxArr[1] - 2, h.boxArr[2] - 2, h.boxArr[3] + 2, h.boxArr[4] + 2, h.boxArr[5] + 2] })).items;
        newest.mined = { hall: h.id, cell: h.pairs[0][0], dropped };
        check(dropped['minecraft:chest'] === 1, `7 mined: the player broke one bulk chest half of ${h.id} at ${h.pairs[0][0]}; it dropped ${json(dropped)} (the player's)`);
        newest.wantBack = minus(newest.consumed, { 'minecraft:chest': 1 });
      }
    }
    // ---- the undos of the built, emptied halls, newest first: refunded exactly what each consumed (less what the player mined)
    await standAt(x0 - 20, z0 - 20, gy + 2);
    for (const g of [...out.growths].reverse()) {
      const before = await pile();
      const u = await call('dev.rv.undo', { region, toPosition: g.position - 1 }, 3_600_000).catch((e) => ({ error: String(e) }));
      await sleep(6000);
      const after = await pile();
      const back = plus(minus(after.ledger?.returned, before.ledger?.returned), minus(after.ledger?.refundOverflow, before.ledger?.refundOverflow));
      const drops = (await mod({ op: 'takeItems', box: [x0 - 300, gy - 4, z0 - 300, x0 + 600, gy + 60, z0 + 600] })).items;
      g.undo = { removed: u.removed ?? null, blockers: u.blockers ?? [], error: u.error ?? null, back, drops };
      const want = g.wantBack ?? g.consumed;
      check(u.removed === true && same(back, want) && Object.keys(drops).length === 0, `7 refund rid ${g.rid}: removed ${u.removed}; credited (with overflow) = consumed${g.mined ? ' less the chest the player mined' : ''} (${same(back, want) ? 'equal' : `off by ${json(minus(back, want))}`}); dropped ${json(drops)}`, g.undo);
    }
    const ledger1 = await pile();
    await reopen([x0 - 20, z0 - 20, gy + 2]);
    await sleep(6000);
    const ledger2 = await pile();
    const stray = (await mod({ op: 'takeItems', box: [x0 - 300, gy - 4, z0 - 300, x0 + 600, gy + 60, z0 + 600] })).items;
    check(json(ledger2.ledger) === json(ledger1.ledger) && json(ledger2.stock) === json(ledger1.stock) && Object.keys(stray).length === 0, `7 after a restart: nothing given twice (the ledger and stock unchanged; items on the ground ${json(stray)})`);
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

// ------------------------------------------------------------------ bounds: the quote per branch and Style (the offer's bound)

async function bounds() {
  const out = { bounds: [], quotes: [], fills: [] };
  const SLOTSB = MODULE_JSON.envelope.branches.slots ?? [0];
  const domain = MODULE.version >= 2 ? MODULE_JSON.params.item1.options.filter((o) => o !== 'none') : [];
  // two item sets per sorting branch (12.5 bar 10): A begins with stone, redstone and dirt (the structure's own items share their entries), B elsewhere
  const setA = (n) => ['stone', 'redstone', 'dirt', ...domain.filter((k) => !['stone', 'redstone', 'dirt'].includes(k))].slice(0, n);
  const setB = (n) => domain.slice(domain.length - n);
  try {
    const T = await survivalWorld('Bounds', 60000, 60000);
    for (const slots of SLOTSB) for (const bulk of BULKS) {
      const styles = {};
      for (const bible of MODULE_JSON.envelope.bibles) {
        const sets = slots > 0 ? (bible === MODULE_JSON.envelope.bibles[0] ? [setA(slots), setB(slots)] : [setA(slots)]) : [[]];
        for (const [si, items] of sets.entries()) {
          const id = `bd${slots}_${bulk}_${bible.slice(0, 3)}${si ? 'b' : ''}`;
          const g = await growHalls(T.region, [{ id, slots, bulk, bible, items }], { survival: true, realise: false });
          const q = g.quote ?? {};
          const ok = !g.refused && q.ok && !q.refused && q.survival === true && q.bom && Object.keys(q.bom).length > 0;
          // the fill's part (fill:<lot>): 41 of each item, never a slab; the structure is the rest
          const fillKey = Object.keys(q.bomParts ?? {}).find((k) => k.startsWith('fill:'));
          const fill = fillKey ? q.bomParts[fillKey] : {};
          const wantFill = Object.fromEntries(items.map((k) => [`minecraft:${k}`, 41]));
          const slab = Object.keys(q.bom ?? {}).includes('minecraft:petrified_oak_slab');
          const fillOk = same(fill, wantFill) && !slab;
          check(ok && fillOk, `bounds ${slots} slots, bulk ${bulk}, ${bible}${si ? ' (set B)' : ''}: quoted (survival ${q.survival}, exact ${q.exact}, ${totalOf(q.bom)} items); the fill part ${json(fill)} = 41 per item${slab ? '; a SLAB was quoted' : ''}${g.refused ? ` ${String(g.refused).slice(0, 200)}` : ''}`);
          if (!ok) continue;
          const structure = minus(q.bom, fill);
          const parts = Object.fromEntries(Object.entries(q.bomParts ?? {}).filter(([k]) => !k.startsWith('fill:')));
          if (si === 0) {
            styles[bible] = structure;
            out.quotes.push({ params: { bulk, slots }, ...(MODULE.version >= 2 ? { build: paramsOf({ slots, bulk, items }) } : {}), bible, bom: structure, bomParts: parts, exact: q.exact, notes: q.notes });
          } else {
            // set B: the same structure (the items don't change the building), only the fill differs
            check(same(structure, styles[bible] ?? {}), `bounds ${slots} slots, bulk ${bulk}: set B's structure equals set A's (${same(structure, styles[bible] ?? {}) ? 'equal' : json(minus(structure, styles[bible] ?? {}))})`);
          }
          out.fills.push({ params: { bulk, slots }, bible, items, fill });
        }
      }
      if (Object.keys(styles).length === MODULE_JSON.envelope.bibles.length) {
        const bom = {};
        for (const b of Object.values(styles)) for (const [k, n] of Object.entries(b)) bom[k] = Math.max(bom[k] ?? 0, n);
        out.bounds.push({ params: { bulk, slots }, bom: Object.fromEntries(Object.entries(bom).sort()) });
      }
    }
    const n = SLOTSB.length * BULKS.length;
    check(out.bounds.length === n, `bounds: every one of the ${n} branches has a structure bound over its ${MODULE_JSON.envelope.bibles.length} Styles (${out.bounds.length})`);
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}


// ------------------------------------------------------------------ sort (storage_hall@2): MEASURE-M4 12.5 bars 1-7

const freeze = async () => cmd('/tick freeze');
/** Advance n game ticks, frozen around it (M0's /tick sprint): the game time moves exactly n. */
async function advance(n) {
  const t0 = (await mod({ op: 'cells', cells: [] })).gameTime;
  await cmd(`/tick sprint ${n}`);
  for (let i = 0; i < 6000; i++) {
    const t = (await mod({ op: 'cells', cells: [] })).gameTime;
    if (t - t0 >= n) return t - t0;
    await sleep(n > 2000 ? 250 : 50);
  }
  throw new Error(`advance ${n} stuck`);
}
/** A hall's sorting mix (12.5 bar 1): each slot's item x 3 stacks, 5 plain domain kinds it doesn't filter, 3 unstackables, a 16-stack, a renamed filter stack. */
function mixOf(h, domain) {
  const own = new Set(h.filterIds);
  const plain = domain.map((k) => `minecraft:${k}`).filter((i) => !own.has(i)).slice(0, 5);
  const st = [];
  for (const f of h.filterIds) for (let i = 0; i < 3; i++) st.push({ id: f, count: 64 });
  for (const p of plain) st.push({ id: p, count: 64 });
  for (let i = 0; i < 3; i++) st.push({ id: 'minecraft:iron_sword', count: 1 });
  st.push({ id: 'minecraft:ender_pearl', count: 16 });
  st.push({ id: h.filterIds[0], count: 64, name: 'Not filtered' });
  return st;
}
const keyOf = (st) => `${st.id}${st.name ? `#${st.name}` : ''}`;
const totalOf = (m) => Object.values(m ?? {}).reduce((a, b) => a + b, 0);
/** A hall's containers by role: {input, overflow, line[], F[], D[], chest[]} (each a key -> count map). */
async function sortState(h) {
  const r = await containersIn(h.boxArr);
  const by = new Map(r.containers.map((c) => [c.at.join(','), Object.fromEntries((c.slots ?? []).reduce((acc, [, k, n]) => { acc.set(k, (acc.get(k) ?? 0) + n); return acc; }, new Map()))]));
  const at = (c) => by.get(c.join(',')) ?? {};
  return { input: at(h.input), overflow: at(h.overflow), line: h.sort.map((c) => at(c.line)), F: h.sort.map((c) => at(c.F)), D: h.sort.map((c) => at(c.D)), chest: h.sort.map((c) => at(c.chest)), bulk: h.pairs.flat().map(at), unloaded: r.unloaded };
}
const sumAll = (s) => [s.input, s.overflow, ...s.line, ...s.F, ...s.D, ...s.chest, ...(s.bulk ?? [])].reduce((a, m) => plus(a, m), {});
/** Every D settled: empty, or holding one of its own filter item (the last one through, waiting for the next). */
const dSettled = (h, s) => s.D.every((m, u) => totalOf(m) === 0 || (totalOf(m) === 1 && (m[h.filterIds[u]] ?? 0) === 1));
const fExact = (f, item) => f[item] === 41 && f['minecraft:petrified_oak_slab'] === 4 && Object.keys(f).length === 2;
/**
 * One sorting run over halls: each hall's mix fed in acknowledged batches of at most 27 stacks (the next batch only once the input chest is
 * empty), run to the end (input, line and every D empty), judged against its own expected multiset. {byHall: {id: {sorted, why, items, ticks}}}.
 */
async function sortRun(halls, domain, { feed = null } = {}) {
  const plan = new Map(halls.map((h) => [h.id, { h, batches: [], fed: {}, t0: null, done: false, ticks: 0 }]));
  for (const p of plan.values()) {
    const mix = feed ? feed(p.h) : mixOf(p.h, domain);
    for (let i = 0; i < mix.length; i += 27) p.batches.push(mix.slice(i, i + 27));
    p.expected = {};
    for (const st of mix) p.expected[keyOf(st)] = (p.expected[keyOf(st)] ?? 0) + st.count;
    p.before = await sortState(p.h);
  }
  let t = 0;
  while ([...plan.values()].some((p) => !p.done) && t < 120_000) {
    for (const p of plan.values()) {
      if (p.done) continue;
      const s = await sortState(p.h);
      if (totalOf(s.input) === 0 && p.batches.length) {
        const b = p.batches.shift();
        const a = await mod({ op: 'putItems', at: p.h.input, stacks: b.map((st, i) => ({ slot: i, ...st })) });
        if (!a.ok) throw new Error(`put into ${p.h.id}: ${a.error}`);
        for (const st of b) p.fed[keyOf(st)] = (p.fed[keyOf(st)] ?? 0) + st.count;
        if (p.t0 === null) p.t0 = t;
      } else if (!p.batches.length && totalOf(s.input) === 0 && s.line.every((m) => totalOf(m) === 0) && dSettled(p.h, s)) {
        p.done = true;
        p.ticks = t - p.t0;
      }
    }
    await advance(1000);
    t += 1000;
  }
  const byHall = {};
  for (const p of plan.values()) {
    const s = await sortState(p.h);
    const ents = await mod({ op: 'takeItems', box: p.h.boxArr });
    const why = [];
    // the fed multiset is the mix: never derived from what landed
    if (!same(p.fed, p.expected)) why.push(`fed ${json(minus(p.fed, p.expected))} off the mix`);
    // a slot is its chest and its D: the tile leaves the last item it let through waiting in D (M0's judge counted D too)
    const gotSlots = s.chest.map((c, u) => minus(plus(c, s.D[u]), plus(p.before.chest[u], p.before.D[u])));
    const gotOver = minus(s.overflow, p.before.overflow);
    let want = { ...p.expected };
    p.h.filterIds.forEach((f, u) => {
      const w = { [f]: p.expected[f] ?? 0 };
      if (!same(gotSlots[u], w)) why.push(`slot ${u} got ${json(gotSlots[u])}, want ${json(w)}`);
      want = minus(want, w);
    });
    if (!same(gotOver, want)) why.push(`overflow got ${json(gotOver)}, want ${json(want)}`);
    if (totalOf(s.input) || s.line.some((m) => totalOf(m)) || !dSettled(p.h, s)) why.push('the input or the line is not empty, or a D holds more than one of its own item');
    p.h.filterIds.forEach((f, u) => { if (!same(s.F[u], p.before.F[u])) why.push(`F ${u} moved: ${json(p.before.F[u])} -> ${json(s.F[u])}`); });
    if (ents.entities) why.push(`${ents.entities} item entities`);
    if (!p.done) why.push('did not finish in 120,000 ticks');
    const items = totalOf(p.expected);
    byHall[p.h.id] = { sorted: why.length === 0, why, items, ticks: p.ticks, itemsPerSecond: p.ticks ? (items / p.ticks) * 20 : null };
  }
  return byHall;
}
/** Run the halls until every input, line hopper and D has settled (a backed-up line drains into emptied chests), then clear the outputs. */
async function drain(halls, limit = 60_000) {
  for (let t = 0; t < limit; t += 1000) {
    let busy = false;
    for (const h of halls) { const s = await sortState(h); if (totalOf(s.input) || s.line.some((m) => totalOf(m)) || !dSettled(h, s)) busy = true; }
    if (!busy) break;
    for (const h of halls) await clearOutputs(h);
    await advance(1000);
  }
  for (const h of halls) await clearOutputs(h);
}
async function clearOutputs(h) {
  for (const c of h.sort) { await mod({ op: 'clearContainers', box: [...c.chest, ...c.chest] }); await mod({ op: 'clearContainers', box: [...c.D, ...c.D] }); }
  await mod({ op: 'clearContainers', box: [...h.overflow, ...h.overflow] });
}

async function sortStep() {
  if (MODULE.version < 2) throw new Error('sort is storage_hall@2\'s step: --version 2');
  await startClient('Sort');
  const out = { growths: [], halls: {}, runs: [], coverage: {} };
  try {
    await rules();
    await install();
    const domain = MODULE_JSON.params.item1.options.filter((o) => o !== 'none');
    // the towns: one per street orientation, and two at shifted claims for chunk-border crossings (along the line, through a control arm)
    const towns = [await town(0, 0, { orientation: 'ew' }), await town(256, 0, { w: 176, d: 224, orientation: 'ns' }), await town(9, 505, { orientation: 'ew' }), await town(263, 511, { w: 176, d: 224, orientation: 'ns' })];
    // each sorting branch in rustic in full, with its own items rotating through the domain; fortress and desert for 4 and 16
    let at = 0;
    const pick = (n) => { const it = Array.from({ length: n }, (_, i) => domain[(at + i) % domain.length]); at = (at + n) % domain.length; return it; };
    const specs = [];
    for (const slots of [4, 8, 12, 16]) specs.push({ id: `s${slots}_rus`, slots, bulk: slots === 4 ? 2 : 8, bible: 'rustic', items: pick(slots) });
    for (const slots of [4, 16]) for (const bible of ['fortress', 'desert']) specs.push({ id: `s${slots}_${bible.slice(0, 3)}`, slots, bulk: 4, bible, items: pick(slots) });
    for (const slots of [4, 8]) specs.push({ id: `s${slots}_rus_b`, slots, bulk: 0, bible: 'rustic', items: pick(slots) });
    const groups = [[0, 1], [2], [3], [4, 5], [6, 7], [8, 9]];
    const townOf = [0, 1, 0, 1, 2, 3];
    const halls = [];
    for (const [gi, idx] of groups.entries()) {
      const t = towns[townOf[gi]];
      const sp = idx.map((i) => specs[i]);
      await standAt(t.cx, t.cz, t.gy + 30);
      const g = await growHalls(t.region, sp);
      if (!check(!g.refused, `growth ${sp.map((x) => x.id).join(', ')} in ${t.region}: ${g.refused ? String(g.refused).slice(0, 300) : `rid ${g.rid}`}`)) continue;
      const w = await rvWait(t.region, g.rid);
      check(w.r.state === 'PLACED', `growth rid ${g.rid}: ${w.r.state}`);
      await hallCells(t.region, g.halls);
      for (const h of g.halls) { h.region = t.region; h.rid = g.rid; h.position = g.position; h.town = t; }
      out.growths.push({ region: t.region, rid: g.rid, position: g.position, halls: g.halls.map((h) => h.id) });
      halls.push(...g.halls);
    }
    // coverage: every domain item a filter at least once
    const used = new Set(halls.flatMap((h) => h.items));
    out.coverage = { filters: [...used].sort(), missing: domain.filter((k) => !used.has(k)) };
    check(out.coverage.missing.length === 0, `12.1 coverage: every one of the ${domain.length} domain items is a filter in some hall (${out.coverage.missing.length ? `missing ${out.coverage.missing.join(', ')}` : 'all'})`);
    const fronts = new Set(halls.map((h) => h.frame.front));
    check(['north', 'east', 'south', 'west'].every((f) => fronts.has(f)), `12.5 facings: every one covered (${[...fronts].join(', ')})`);
    // the line's axis and the control arm's axis per hall: a crossing along the line (x in the template) and one through a control arm (z)
    const along = halls.filter((h) => { const xs = h.sort.map((c) => Math.floor((h.frame.front === 'north' || h.frame.front === 'south' ? c.line[0] : c.line[2]) / 16)); return new Set(xs).size > 1; }).map((h) => h.id);
    const arm = halls.filter((h) => h.sort.some((c) => { const a = h.frame.front === 'north' || h.frame.front === 'south' ? [c.line[2], c.line[2] + (h.frame.front === 'south' ? -4 : 4)] : [c.line[0], c.line[0] + (h.frame.front === 'east' ? 4 : -4)]; return Math.floor(a[0] / 16) !== Math.floor(a[1] / 16); })).map((h) => h.id);
    out.crossings = { along, arm };
    check(along.length > 0 && arm.length > 0, `12.5/12.7 S7 chunk borders: halls crossing along their line ${json(along)}, through a tile's control arm ${json(arm)}`);
    // ---- 6 shape: standing; F exactly 41 + 4 (the fill); the lever unlocked; the slot, input and overflow chests single; every chest opens
    for (const t of towns) {
      await standAt(t.cx, t.cz, t.gy + 30);
      const rep = await land(t.region);
      for (const h of halls.filter((x) => x.region === t.region)) {
        const row = lotRow(rep, h.id);
        const s = await sortState(h);
        const fOk = h.filterIds.every((f, u) => fExact(s.F[u], f));
        const cells = await readCells([h.lever, ...h.sort.map((c) => [c.chest[0], c.chest[1] + 1, c.chest[2]]), [h.overflow[0], h.overflow[1] + 1, h.overflow[2]]]);
        const leverOk = cells[0]?.[0] === 'minecraft:lever';
        const above = cells.slice(1).map((c) => c?.[0]);
        const opens = above.every((b) => b === 'minecraft:air' || b === 'minecraft:cave_air' || /slab$/.test(b ?? ''));
        const fk = await call('dev.fk.state', { site: h.siteId }, 30_000).catch((e) => ({ error: String(e) }));
        out.halls[h.id] = { slots: h.slots, bulk: h.bulk, bible: h.bible, items: h.items, front: h.frame.front, built: row, fk: { phase: fk.phase, locks: fk.locks } };
        // Architect's land check reads a container written empty that holds items now as changed (StillOurs.holds), so each filled F is one
        // changed cell (S-M5-18): changed is exactly the slots' F cells, each holding exactly the fill (fOk)
        check(row?.changed === h.slots && row?.standing === 1 && fOk && leverOk && opens && fk.phase === 'UNLOCKED' && (fk.locks ?? []).every((l) => /powered=false/.test(l.state ?? '')),
          `12.5 bar 6 shape ${h.id} (${h.bible}, ${h.slots} slots, front ${h.frame.front}): standing ${row?.standing}, changed ${row?.changed} (the ${h.slots} filled Fs); every F exactly 41 + 4 slabs (${fOk}); FK ${fk.phase}, the lever unlocked; nothing conductive over any chest (${opens})`);
      }
    }
    // ---- 4 light
    await cmd('/time set 18000');
    for (const t of towns) {
      await standAt(t.cx, t.cz, t.gy + 30);
      for (const h of halls.filter((x) => x.region === t.region)) {
        const r = await readCells(h.light);
        const air = r.filter((c) => c && (c[0] === 'minecraft:air' || c[0] === 'minecraft:cave_air'));
        const dark = air.filter((c) => c[3] < 1).length;
        check(dark === 0 && air.length > 0, `12.5 bar 6 light ${h.id} (${h.bible}): ${air.length} air cells inside and on the front row, the darkest ${Math.min(...air.map((c) => c[3]))}`);
      }
    }
    await cmd('/time set 6000');
    // ---- 1, 2: sorting, 20 runs of every rustic hall (3 of the others), by town (its halls run together)
    await freeze();
    for (const t of towns) {
      await standAt(t.cx, t.cz, t.gy + 30);
      const th = halls.filter((x) => x.region === t.region);
      for (let r = 0; r < 20; r++) {
        const runHalls = th.filter((h) => h.bible === 'rustic' || r < 3);
        if (!runHalls.length) break;
        const res = await sortRun(runHalls, domain);
        out.runs.push({ town: t.region, run: r, res });
        for (const h of runHalls) await clearOutputs(h);
        const bad = Object.entries(res).filter(([, x]) => !x.sorted);
        log(`  sort ${t.region} run ${r}: ${Object.entries(res).map(([id, x]) => `${id} ${x.sorted ? 'sorted' : 'FAIL ' + x.why.slice(0, 2).join('; ')} ${x.itemsPerSecond?.toFixed(3)}/s`).join(' | ')}`);
        if (bad.length) break;
      }
    }
    const all = out.runs.flatMap((r) => Object.entries(r.res).map(([id, x]) => ({ id, ...x })));
    for (const h of halls) {
      const mine = all.filter((x) => x.id === h.id);
      const want = h.bible === 'rustic' ? 20 : 3;
      check(mine.length >= want && mine.every((x) => x.sorted), `12.5 bar 1 sorting ${h.id}: ${mine.filter((x) => x.sorted).length}/${mine.length} runs 100% (${want} needed)${mine.find((x) => !x.sorted) ? `: ${mine.find((x) => !x.sorted).why.slice(0, 3).join('; ')}` : ''}`);
      const ips = mine.map((x) => x.itemsPerSecond).filter(Boolean);
      const mean = ips.reduce((a, b) => a + b, 0) / (ips.length || 1);
      out.halls[h.id].itemsPerSecond = mean;
      check(true, `12.5 bar 2 throughput ${h.id} (report): ${mean.toFixed(3)} items a second over ${ips.length} runs (bar 2.241-2.739: ${mean >= 2.241 && mean <= 2.739 ? 'within' : 'OUTSIDE: flag'})`);
    }
    // ---- 4: the filters hold after the runs (> 10,000 items through each rustic hall)
    for (const h of halls) {
      await standAt(h.town.cx, h.town.cz, h.town.gy + 30);
      const s = await sortState(h);
      const thru = all.filter((x) => x.id === h.id).reduce((a, x) => a + x.items, 0);
      check(h.filterIds.every((f, u) => fExact(s.F[u], f)), `12.5 bar 4 the filters hold ${h.id}: after ${thru} items every F exactly 41 + 4`);
    }
    // ---- 3: saturation (two neighbouring slot chests full), then recovery; a full overflow backs up, then recovers
    const sat = halls.filter((h) => h.bible === 'rustic' && h.region === towns[0].region);
    await standAt(towns[0].cx, towns[0].cz, towns[0].gy + 30);
    for (const h of sat) for (const u of [1, 2]) await mod({ op: 'putItems', at: h.sort[u].chest, stacks: Array.from({ length: 27 }, (_, i) => ({ slot: i, id: h.filterIds[u], count: 64 })) });
    const s1 = await sortRunSat(sat, domain);
    for (const h of sat) {
      const r = s1[h.id];
      check(r.ok, `12.5 bar 3 saturation ${h.id}: slots 1 and 2 full; ${r.ok ? 'no wrong item in any chest, conserved, the other slots 100%, every F 3 or less' : r.why.slice(0, 3).join('; ')}`, r);
    }
    for (const h of sat) await clearOutputs(h);
    // the line still holds the saturated slots' backlog: let it drain into the emptied chests, then a fresh mix sorts 100% and every F is 41 + 4
    await drain(sat);
    const rec = await sortRun(sat, domain);
    for (const h of sat) {
      const s = await sortState(h);
      check(rec[h.id].sorted && h.filterIds.every((f, u) => fExact(s.F[u], f)), `12.5 bar 3 recovery ${h.id}: after the chests are emptied, 100% again and every F exactly 41 + 4 (${rec[h.id].why.slice(0, 2).join('; ')})`);
    }
    for (const h of sat) await clearOutputs(h);
    // a full overflow
    for (const h of sat) await mod({ op: 'putItems', at: h.overflow, stacks: Array.from({ length: 27 }, (_, i) => ({ slot: i, id: 'minecraft:netherrack', count: 64 })) });
    const ofl = await sortRunSat(sat, domain, { overflowFull: true });
    for (const h of sat) check(ofl[h.id].ok, `12.5 bar 3 overflow full ${h.id}: ${ofl[h.id].ok ? 'the line backs up, nothing lost or misplaced' : ofl[h.id].why.slice(0, 3).join('; ')}`, ofl[h.id]);
    for (const h of sat) await clearOutputs(h);
    await drain(sat);
    const rec2 = await sortRun(sat, domain);
    for (const h of sat) check(rec2[h.id].sorted, `12.5 bar 3 overflow recovery ${h.id}: emptied, 100% again (${rec2[h.id].why.slice(0, 2).join('; ')})`);
    for (const h of sat) await clearOutputs(h);
    // ---- 5: restart mid-sort; unload mid-sort (every chunk); a partial unload (one side of a crossing ticking, the other not)
    for (const [label, how] of [['restart', 'restart'], ['unload', 'unload'], ['partial', 'partial']]) {
      const t = towns[label === 'partial' ? 2 : 1];
      await standAt(t.cx, t.cz, t.gy + 30);
      let th = halls.filter((x) => x.region === t.region);
      if (label === 'partial') th = th.filter((h) => along.includes(h.id) || arm.includes(h.id)).slice(0, 2);
      if (!th.length) { check(false, `12.5 bar 5 ${label}: no hall to run it on`); continue; }
      const r = await sortMidway(th, domain, how, t);
      for (const h of th) check(r[h.id].sorted, `12.5 bar 5 ${label} mid-sort ${h.id}: ${r[h.id].sorted ? 'nothing lost or duplicated, sorting finished 100%, the renamed stack still renamed' : r[h.id].why.slice(0, 3).join('; ')}`, r[h.id]);
      for (const h of th) await clearOutputs(h);
    }
    await cmd('/tick unfreeze');
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

/** A saturation round: a mix fed and run; judged on no wrong item, conservation, the other slots 100%, every F at most 64 of its item (signal 3 or less). */
async function sortRunSat(halls, domain, { overflowFull = false } = {}) {
  const before = new Map();
  for (const h of halls) before.set(h.id, await sortState(h));
  const fed = new Map();
  for (const h of halls) {
    const mix = mixOf(h, domain);
    const f = {};
    for (let i = 0; i < mix.length; i += 27) {
      const b = mix.slice(i, i + 27);
      // a backed-up line may leave the input part full: wait for room, at most 6,000 ticks
      for (let k = 0; k < 60 && totalOf((await sortState(h)).input) > 0; k++) await advance(100);
      const a = await mod({ op: 'putItems', at: h.input, stacks: b.map((st, j) => ({ slot: j, ...st })) });
      if (a.ok) for (const st of b) f[keyOf(st)] = (f[keyOf(st)] ?? 0) + st.count;
      else break;
    }
    fed.set(h.id, f);
  }
  await advance(overflowFull ? 6000 : 12000);
  const out = {};
  for (const h of halls) {
    const s = await sortState(h), b = before.get(h.id), why = [];
    const ents = await mod({ op: 'takeItems', box: h.boxArr });
    // conservation: everything now = before + fed
    if (!same(sumAll(s), plus(sumAll(b), fed.get(h.id)))) why.push(`not conserved: off by ${json(minus(sumAll(s), plus(sumAll(b), fed.get(h.id))))}`);
    h.filterIds.forEach((f, u) => {
      const extra = Object.keys(minus(s.chest[u], b.chest[u])).filter((k) => k !== f);
      if (extra.length) why.push(`slot ${u} got a wrong item ${extra.join(', ')}`);
      const fi = s.F[u];
      if (Object.keys(fi).some((k) => k !== f && k !== 'minecraft:petrified_oak_slab') || (fi[f] ?? 0) > 64) why.push(`F ${u} holds ${json(fi)}`);
    });
    if (!overflowFull) {
      // the unsaturated slots got all their items
      h.filterIds.forEach((f, u) => { const got = minus(plus(s.chest[u], s.D[u]), plus(b.chest[u], b.D[u]))[f] ?? 0; if (u !== 1 && u !== 2 && got !== (fed.get(h.id)[f] ?? 0)) why.push(`slot ${u} got ${got} of ${fed.get(h.id)[f]}`); });
    }
    if (ents.entities) why.push(`${ents.entities} item entities`);
    out[h.id] = { ok: why.length === 0, why };
  }
  return out;
}

/** A run interrupted midway (restart: the world saved, left and opened; unload: every chunk; partial: one chunk force-loaded, the rest away), then finished. */
async function sortMidway(halls, domain, how, t) {
  const exp = new Map(), fedAll = new Map(), before = new Map();
  for (const h of halls) {
    const mix = mixOf(h, domain);
    before.set(h.id, await sortState(h));
    // the first batch only (27 stacks), so it fits the input at once; the run is judged on that batch
    const b = mix.slice(0, 27);
    await mod({ op: 'putItems', at: h.input, stacks: b.map((st, j) => ({ slot: j, ...st })) });
    const f = {};
    for (const st of b) f[keyOf(st)] = (f[keyOf(st)] ?? 0) + st.count;
    fedAll.set(h.id, f);
  }
  await advance(400);
  const mid = new Map();
  for (const h of halls) mid.set(h.id, await sortState(h));
  if (how === 'restart') {
    await cmd('/tick unfreeze');
    await reopen([t.cx, t.cz, t.gy + 30]);
    await freeze();
  } else if (how === 'unload' || how === 'partial') {
    const forced = [];
    if (how === 'partial') for (const h of halls) { const c = h.sort[0].line; forced.push(c); await cmd(`/forceload add ${c[0]} ${c[2]}`); }
    await cmd('/tick unfreeze');
    await standAt(t.cx + 6000, t.cz + 6000, 120);
    const probes = halls.flatMap((h) => h.sort.map((c) => c.F));
    for (let i = 0; i < 180; i++) {
      await sleep(1000);
      const r = await readCells(probes);
      const loaded = r.filter((c) => c !== null).length;
      if (how === 'unload' ? loaded === 0 : loaded < probes.length) break;
    }
    const t0 = (await mod({ op: 'cells', cells: [] })).gameTime;
    for (let i = 0; i < 600 && (await mod({ op: 'cells', cells: [] })).gameTime - t0 < 600; i++) await sleep(1000);
    for (const c of forced) await cmd(`/forceload remove ${c[0]} ${c[2]}`);
    await standAt(t.cx, t.cz, t.gy + 30);
    const all = halls.flatMap((h) => h.sort.map((c) => c.F));
    for (let i = 0; i < 120 && (await readCells(all)).some((c) => c === null); i++) await sleep(1000);
    await freeze();
  }
  // run to the end: every input, line hopper and D settled (at most 60,000 ticks; 27 stacks take about 14,000)
  for (let k = 0; k < 60; k++) {
    let busy = false;
    for (const h of halls) { const s = await sortState(h); if ((Array.isArray(s.unloaded) ? s.unloaded.length : s.unloaded) || totalOf(s.input) || s.line.some((m) => totalOf(m)) || !dSettled(h, s)) busy = true; }
    if (!busy) break;
    await advance(1000);
  }
  const out = {};
  for (const h of halls) {
    const s = await sortState(h), b = before.get(h.id), f = fedAll.get(h.id), why = [];
    const ents = await mod({ op: 'takeItems', box: h.boxArr });
    if (!same(sumAll(s), plus(sumAll(b), f))) why.push(`not conserved: off by ${json(minus(sumAll(s), plus(sumAll(b), f)))}`);
    let rest = { ...f };
    h.filterIds.forEach((it, u) => {
      const got = minus(plus(s.chest[u], s.D[u]), plus(b.chest[u], b.D[u]));
      if (!same(got, { [it]: f[it] ?? 0 })) why.push(`slot ${u} got ${json(got)}`);
      rest = minus(rest, { [it]: f[it] ?? 0 });
    });
    if (!same(minus(s.overflow, b.overflow), rest)) why.push(`overflow got ${json(minus(s.overflow, b.overflow))}, want ${json(rest)}`);
    if (totalOf(s.input) || s.line.some((m) => totalOf(m)) || !dSettled(h, s)) why.push('not finished');
    if (ents.entities) why.push(`${ents.entities} item entities`);
    out[h.id] = { sorted: why.length === 0, why, mid: totalOf(sumAll(mid.get(h.id))) };
  }
  return out;
}

// ------------------------------------------------------------------ sort-survival (storage_hall@2): 12.5 bar 8, 12.7 B2

const fkState = (site) => call('dev.fk.state', { site }, 30_000).catch((e) => ({ error: String(e) }));
/** A lot's site id from the region's view (null until the region names it). */
const siteOf = async (region, lot) => (await call('dev.region.state', { region }, 60_000)).view.lots.find((l) => l.id === lot)?.siteId ?? null;
/** The player takes everything out of a hall's hoppers (each line hopper and D: the last item a slot sorted waits in D) before an undo. */
async function emptyHoppers(h) {
  for (const c of h.sort) { await mod({ op: 'clearContainers', box: [...c.line, ...c.line] }); await mod({ op: 'clearContainers', box: [...c.D, ...c.D] }); }
}
async function sortSurvival() {
  if (MODULE.version < 2) throw new Error('sort-survival is storage_hall@2\'s step: --version 2');
  const out = {};
  try {
    const T = await survivalWorld('SortSurvival', 70000, 70000);
    const { region, gy, x0, z0 } = T;
    // ---- B2: a hall grown from the stockpile and fed in half waves, its input preloaded while it builds and starves; then an F contaminated
    // before the fill
    const sp = { id: 'ss4', slots: 4, bulk: 2, bible: 'rustic', items: ['stone', 'redstone', 'dirt', 'coal'] };
    const ps0 = await pile();
    const g = await growHalls(region, [sp], { survival: true });
    if (!check(!g.refused, `survival growth ss4: ${g.refused ? String(g.refused).slice(0, 300) : `rid ${g.rid}`}`)) return out;
    const quote = g.quote?.bom ?? {};
    const fillKey = Object.keys(g.quote?.bomParts ?? {}).find((k) => k.startsWith('fill:'));
    check(fillKey && same(g.quote.bomParts[fillKey], { 'minecraft:stone': 41, 'minecraft:redstone': 41, 'minecraft:dirt': 41, 'minecraft:coal': 41 }) && !quote['minecraft:petrified_oak_slab'],
      `8 the quote's fill part is 41 of each filter item, no slab: ${json(g.quote?.bomParts?.[fillKey] ?? null)}`);
    const h = g.halls[0];
    h.region = region;
    // the lot's cells are known once the region names its site
    let site = null;
    for (let i = 0; i < 60 && !site; i++) { await sleep(2000); site = await siteOf(region, sp.id); }
    await hallCells(region, [h]);
    site = site ?? h.siteId;
    // feed half waves until the input chest stands, then preload it with 64 cobblestone; starve (no feeding) 600 ticks: nothing moves
    let preloaded = false;
    for (let i = 0; i < 200 && !preloaded; i++) {
      const c = (await readCells([h.input]))[0];
      if (c?.[0] === 'minecraft:chest') {
        // what guards the line at that moment: the lever (the lock) and line hopper 0 under the input
        const g0 = await readCells([h.lever, h.sort[0].line, h.sort[0].F]);
        out.atPreload = { lever: g0[0], line0: g0[1], F0: g0[2] };
        const a = await mod({ op: 'putItems', at: h.input, stacks: [{ slot: 0, id: 'minecraft:cobblestone', count: 64 }] });
        preloaded = !!a.ok;
        break;
      }
      const ps = await pile();
      const need = {};
      for (const [it, n] of Object.entries(ps.outstanding ?? {})) { const lack = Math.ceil((n - (ps.stock?.[it] ?? 0)) / 2); if (lack > 0) need[it] = lack; }
      if (Object.keys(need).length) await req('dev.crate.insert', { site: `stockpile:${OWNER}`, items: need, exact: true }, 120_000);
      await sleep(3000);
    }
    check(preloaded, `B2 the input chest stood part way through the build and took the player's 64 cobblestone`);
    await sleep(30_000);
    const st1 = await sortState(h);
    site = site ?? await siteOf(region, sp.id);
    check(!!site, `B2 the hall's site named while it builds (${site})`);
    const fk1 = await fkState(site);
    out.preload = { input: st1.input, line: st1.line, F: st1.F, D: st1.D, phase: fk1.phase };
    const held = st1.input['minecraft:cobblestone'] === 64 && st1.line.every((m) => !totalOf(m)) && st1.F.every((m) => !totalOf(m)) && st1.D.every((m) => !totalOf(m));
    // the known gap until Architect's FL (S-M5-17): the line takes a mid-build insert down to F 0 before the lock holds; it passes as the gap
    // only when nothing is lost (all 64 still in the hall, only cobblestone moved) and, below, nothing charged
    const leakCobble = totalOf(sumAll(st1)) === 64 && (sumAll(st1)['minecraft:cobblestone'] ?? 0) === 64;
    out.leak = !held;
    check(held || leakCobble, `B2 starving part way (FK ${fk1.phase ?? 'not begun'}, ${fk1.state}; at the preload the lever ${json(out.atPreload?.lever ?? null)}, line hopper 0 ${json(out.atPreload?.line0 ?? null)}): ${held ? 'the preloaded input stayed put, the line, every F and D empty' : `KNOWN GAP S-M5-17 (Architect FL): the insert went down the line (F 0 ${json(st1.F[0])}, D 0 ${json(st1.D[0])}), all 64 still in the hall`}`, out.preload);
    // an F contaminated before the fill (a player's dirt in F 0), then the rest fed: the fill waits fill_blocked, charging no filter item
    // (after the gap's leak F 0 is already contaminated by the player's cobblestone: that is the case)
    const fA = out.leak ? { ok: true, leak: true } : await mod({ op: 'putItems', at: h.sort[0].F, stacks: [{ slot: 0, id: 'minecraft:dirt', count: 1 }] }).catch((e) => ({ error: String(e) }));
    const fed = await feedUntil(region, async () => {
      const f = await fkState(site);
      return f.phase === 'FILLING' || f.phase === 'UNLOCKED';
    }, { timeoutMs: 900_000 });
    await sleep(8000);
    const fk2 = await fkState(site);
    const ps1 = await pile();
    const charged = minus(ps1.ledger?.consumed, ps0.ledger?.consumed);
    const fillCharged = ['stone', 'redstone', 'dirt', 'coal'].map((k) => `minecraft:${k}`).filter((k) => (charged[k] ?? 0) > (minus(quote, g.quote.bomParts[fillKey])[k] ?? 0));
    out.blocked = { putF: fA.ok ?? null, phase: fk2.phase, wait: fk2.siteWait ?? fk2.wait ?? null, fillCharged };
    check(fA.ok && fk2.phase !== 'UNLOCKED' && fillCharged.length === 0, `B2 an F contaminated before the fill: the fill waits (FK ${fk2.phase}; ${json(fk2.siteWait ?? fk2.wait ?? null)}), no filter item charged past the structure (${json(fillCharged)})`, out.blocked);
    // the player takes their dirt back: the fill runs once
    await mod({ op: 'clearContainers', box: [...h.sort[0].F, ...h.sort[0].F] });
    if (out.leak) { await emptyHoppers(h); await clearOutputs(h); await mod({ op: 'clearContainers', box: [...h.input, ...h.input] }); }
    const fd = await feedUntil(region, async (st, rs) => st.view.state === 'PLACED' && (rs?.build ? rs.build.done : true));
    const w = await rvWait(region, g.rid);
    const fk3 = await fkState(site);
    const s3 = await sortState(h);
    const ps2 = await pile();
    const consumed = minus(ps2.ledger?.consumed, ps0.ledger?.consumed);
    check(w.r.state === 'PLACED' && fk3.phase === 'UNLOCKED' && h.filterIds.every((f, u) => fExact(s3.F[u], f)) && same(consumed, quote),
      `8 then filled once: ${w.r.state}, FK ${fk3.phase}, every F exactly 41 + 4; consumed = the quote item for item (${same(consumed, quote) ? 'equal' : json(minus(consumed, quote))}; ${totalOf(consumed)} items)`, { consumed, quote });
    // the preloaded cobblestone, now let through by the unlock, is the player's: it sorts to overflow; taken back before the undo
    await sleep(15_000);
    await mod({ op: 'clearContainers', box: [...h.input, ...h.input] });
    await mod({ op: 'clearContainers', box: [...h.overflow, ...h.overflow] });
    for (const c of h.sort) await mod({ op: 'clearContainers', box: [...c.chest, ...c.chest] });
    await emptyHoppers(h);
    // ---- 8: the player takes 8 stone from F 0, empties F 1 of its 41 redstone, and mines a bulk chest half; then the undo refunds the rest
    const take0 = await call('dev.fk.container', { pos: h.sort[0].F, take: { 'minecraft:stone': 8 } }, 30_000).catch((e) => ({ error: String(e) }));
    const take1 = await call('dev.fk.container', { pos: h.sort[1].F, take: { 'minecraft:redstone': 41 } }, 30_000).catch((e) => ({ error: String(e) }));
    let mined = {};
    if (h.pairs[0]) {
      await call('dev.pt.breaks', { at: [h.pairs[0][0]], placer: 'M4Player' }, 120_000);
      await sleep(1500);
      mined = (await mod({ op: 'takeItems', box: [h.boxArr[0] - 2, h.boxArr[1] - 2, h.boxArr[2] - 2, h.boxArr[3] + 2, h.boxArr[4] + 2, h.boxArr[5] + 2] })).items;
    }
    const s4 = await sortState(h);
    out.taken = { take0: take0.holds ?? take0.error, take1: take1.holds ?? take1.error, mined, F0: s4.F[0], F1: s4.F[1] };
    check(s4.F[0]['minecraft:stone'] === 33 && !s4.F[1]['minecraft:redstone'] && mined['minecraft:chest'] === 1 && (s4.F[0]['minecraft:petrified_oak_slab'] ?? 0) === 4,
      `8 taken: 8 stone from F 0 (33 left, its 4 slabs kept), F 1's 41 redstone, and a bulk chest half mined (it dropped ${json(mined)})`, out.taken);
    const slabs0 = await call('dev.fk.slabs', { pos: h.input }, 10_000).catch(() => ({}));
    await standAt(x0 - 20, z0 - 20, gy + 2);
    const before = await pile();
    const u = await call('dev.rv.undo', { region, toPosition: g.position - 1 }, 3_600_000).catch((e) => ({ error: String(e) }));
    await sleep(8000);
    const after = await pile();
    const back = plus(minus(after.ledger?.returned, before.ledger?.returned), minus(after.ledger?.refundOverflow, before.ledger?.refundOverflow));
    const want = minus(consumed, { 'minecraft:stone': 8, 'minecraft:redstone': 41, 'minecraft:chest': 1 });
    await standAt(h.input[0], h.input[2] + 8, h.input[1] + 2);
    const drops = (await mod({ op: 'takeItems', box: [h.boxArr[0] - 4, h.boxArr[1] - 4, h.boxArr[2] - 4, h.boxArr[3] + 4, h.boxArr[4] + 6, h.boxArr[5] + 4] })).items;
    const slabs1 = await call('dev.fk.slabs', { pos: h.input }, 10_000).catch(() => ({}));
    const fk4 = await fkState(site);
    out.undo = { removed: u.removed ?? null, error: u.error ?? u.refused ?? null, back, want, drops, slabs0, slabs1, fk: { custody: fk4.custody ?? null, heldProvided: fk4.heldProvided ?? null } };
    check(u.removed === true && same(back, want), `8 refund: removed ${u.removed}; credited (with overflow) = consumed less the 8 stone and 41 redstone taken and the mined chest (${same(back, want) ? 'equal' : `off by ${json(minus(back, want))}`}): the structure's own stone and redstone still refunded`, out.undo);
    check(Object.keys(drops).length === 0 && (slabs0.player ?? 0) === 0 && (slabs1.player ?? 0) === 0 && (slabs1.entities ?? 0) === 0,
      `8 no item dropped at the undo (${json(drops)}); no petrified slab in the player's inventory or the world, before or after (${json(slabs0)} / ${json(slabs1)})`, out.undo);
    // ---- a restart, and the undo retried: nothing twice
    const L1 = await pile();
    await reopen([x0 - 20, z0 - 20, gy + 2]);
    await sleep(6000);
    const u2 = await call('dev.rv.undo', { region, toPosition: g.position - 1 }, 600_000).catch((e) => ({ error: String(e) }));
    await sleep(6000);
    const L2 = await pile();
    const stray = (await mod({ op: 'takeItems', box: [x0 - 300, gy - 4, z0 - 300, x0 + 600, gy + 60, z0 + 600] })).items;
    check(json(L2.ledger) === json(L1.ledger) && json(L2.stock) === json(L1.stock) && Object.keys(stray).length === 0,
      `8 after a restart and the undo retried (${u2.removed ?? u2.error ?? u2.refused ?? 'answered'}): nothing given twice (the ledger and stock unchanged; on the ground ${json(stray)})`);
    // ---- a second branch, fed in full: consumed = the quote (structure + 41 per item)
    const sp8 = { id: 'ss8', slots: 8, bulk: 8, bible: 'rustic', items: ['cobblestone', 'iron_ingot', 'wheat', 'oak_log', 'gravel', 'sand', 'bone', 'string'] };
    const q0 = await pile();
    const g8 = await growHalls(region, [sp8], { survival: true });
    if (check(!g8.refused, `survival growth ss8: ${g8.refused ? String(g8.refused).slice(0, 200) : `rid ${g8.rid}`}`)) {
      await feedUntil(region, (st, rs) => st.view.state === 'PLACED' && (rs?.build ? rs.build.done : true));
      const w8 = await rvWait(region, g8.rid);
      const q1 = await pile();
      const c8 = minus(q1.ledger?.consumed, q0.ledger?.consumed);
      check(w8.r.state === 'PLACED' && same(c8, g8.quote?.bom ?? {}), `8 bill ss8 (8 slots, bulk 8): consumed = the quote (${same(c8, g8.quote?.bom ?? {}) ? 'equal' : json(minus(c8, g8.quote?.bom ?? {}))}; ${totalOf(c8)} items)`);
    }
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

// ------------------------------------------------------------------ sort-cargo (storage_hall@2): 12.5 bars 7 and 11 (creative)

async function sortCargo() {
  if (MODULE.version < 2) throw new Error('sort-cargo is storage_hall@2\'s step: --version 2');
  await startClient('SortCargo');
  const out = { cases: [] };
  try {
    await rules();
    await install();
    const t = await town(0, 0, { orientation: 'ew' });
    await standAt(t.cx, t.cz, t.gy + 30);
    const sp = { id: 'sc4', slots: 4, bulk: 8, bible: 'rustic', items: ['cobblestone', 'iron_ingot', 'wheat', 'oak_log'] };
    const g = await growHalls(t.region, [sp]);
    if (!check(!g.refused, `growth sc4: ${g.refused ? String(g.refused).slice(0, 300) : `rid ${g.rid}`}`)) return out;
    await rvWait(t.region, g.rid);
    const h = g.halls[0];
    await hallCells(t.region, [h]);
    h.region = t.region;
    const before = (await mod({ op: 'hallBefore', owner: OWNER, region: t.region, rid: g.rid })).words;
    check(before === null || before === undefined, `7 the built, empty hall: Steward's pre-check finds no cargo (${before})`);
    // one item alone in each class; F's cases with the game frozen (one more of its own item would otherwise go on to D and the slot chest)
    const cases = [
      ['the input chest', h.input, 'minecraft:dirt', false],
      ['the overflow chest', h.overflow, 'minecraft:dirt', false],
      ['a slot chest', h.sort[2].chest, 'minecraft:wheat', false],
      ['a bulk lower half', h.pairs[0][0], 'minecraft:dirt', false],
      ['a bulk upper half', h.pairs[4]?.[1] ?? h.pairs[0][1], 'minecraft:dirt', false],
      ['a line hopper', h.sort[1].line, 'minecraft:dirt', true],
      ['a D hopper', h.sort[1].D, 'minecraft:dirt', true],
      ['an F with one more of its own item', h.sort[0].F, 'minecraft:cobblestone', true],
      // a full F has no free slot: another kind gets in only once its item is gone (all 41 taken), so that is the case
      ['an F emptied of its item, then one item of another kind', h.sort[3].F, 'minecraft:dirt', true, 'minecraft:oak_log'],
    ];
    for (const [what, cell, item, frozen, takeFirst] of cases) {
      if (frozen) await freeze();
      if (takeFirst) await call('dev.fk.container', { pos: cell, take: { [takeFirst]: 41 } }, 30_000).catch(() => null);
      const s0 = await sortState(h);
      const put = await call('dev.fk.container', { pos: cell, add: { [item]: 1 } }, 30_000).catch((e) => ({ error: String(e) }));
      const words = (await mod({ op: 'hallBefore', owner: OWNER, region: t.region, rid: g.rid })).words;
      const u = await call('dev.rv.undo', { region: t.region, toPosition: g.position - 1 }, 600_000).catch((e) => ({ error: String(e) }));
      await sleep(1500);
      const s1 = await sortState(h);
      const r = await call('dev.region.state', { region: t.region }, 60_000);
      const stands = r.view.lots.find((l) => l.id === sp.id)?.state;
      const kept = totalOf(sumAll(s1)) === totalOf(sumAll(s0)) + 1;
      out.cases.push({ what, put: put.holds ?? put.error, words, undo: refusal(u), stands, kept });
      check(!put.error && typeof words === 'string' && /\b1 item\b/.test(words) && u.removed !== true && kept,
        `7 one item alone in ${what}${frozen ? ' (frozen)' : ''}: Steward refuses ("${words}"); Architect's undo ${refusedForCargo(u) ? 'refused for cargo' : `answered ${refusal(u)}`}; nothing lost (the hall holds one item more than before: ${kept})`);
      if (u.removed === true) break; // the hall is gone: the cases after it can't run
      await call('dev.fk.container', { pos: cell, take: { [item]: 1 } }, 30_000).catch(() => null);
      if (takeFirst) await call('dev.fk.container', { pos: cell, add: { [takeFirst]: 41 } }, 30_000).catch(() => null);
      if (frozen) await cmd('/tick unfreeze');
      // anything the running line carried on is the test's: cleared from the hall's chests and its Ds, the fill left as it is
      await clearOutputs(h);
      for (const c of h.sort) await mod({ op: 'clearContainers', box: [...c.line, ...c.line] });
      await mod({ op: 'clearContainers', box: [...h.input, ...h.input] });
      const s2 = await sortState(h);
      check(h.filterIds.every((f, u2) => fExact(s2.F[u2], f)), `7 after ${what}: every F back to exactly 41 + 4`);
    }
    // ---- 11: a petrified slab in the input chest lands in F 0 (M0 ST5); the undo refuses it as cargo; the recovery is a break of that F, the
    // undo, and a new growth (FK-b section 6)
    await mod({ op: 'putItems', at: h.input, stacks: [{ slot: 0, id: 'minecraft:petrified_oak_slab', count: 1 }] });
    await sleep(8000);
    const s3 = await sortState(h);
    const words = (await mod({ op: 'hallBefore', owner: OWNER, region: t.region, rid: g.rid })).words;
    out.filler = { F0: s3.F[0], words };
    check(typeof words === 'string', `11 a petrified slab fed into the hall: it went to ${json(s3.F[0]['minecraft:petrified_oak_slab'] > 4 ? 'F 0' : s3)}, and the undo is refused as cargo ("${words}")`, out.filler);
    const brk = await call('dev.fk.break', { pos: h.sort[0].F }, 30_000).catch((e) => ({ error: String(e) }));
    await sleep(1500);
    const dropped = (await mod({ op: 'takeItems', box: [h.boxArr[0] - 2, h.boxArr[1] - 2, h.boxArr[2] - 2, h.boxArr[3] + 2, h.boxArr[4] + 2, h.boxArr[5] + 2] })).items;
    const slabs = await call('dev.fk.slabs', { pos: h.input }, 10_000).catch(() => ({}));
    // the rest of the hall emptied as the words ask (the slot chests, overflow, input, every line hopper and D)
    await clearOutputs(h);
    await emptyHoppers(h);
    await mod({ op: 'clearContainers', box: [...h.input, ...h.input] });
    const u = await call('dev.rv.undo', { region: t.region, toPosition: g.position - 1 }, 3_600_000).catch((e) => ({ error: String(e) }));
    await sleep(4000);
    const g2 = await growHalls(t.region, [{ ...sp, id: 'sc4b' }]);
    let refilled = null;
    if (!g2.refused) {
      await rvWait(t.region, g2.rid);
      await hallCells(t.region, g2.halls);
      const s5 = await sortState(g2.halls[0]);
      refilled = g2.halls[0].filterIds.every((f, u3) => fExact(s5.F[u3], f));
    }
    out.recovery = { break: brk, dropped, slabs, undo: refusal(u), regrown: g2.refused ?? g2.rid, refilled };
    check(!dropped['minecraft:petrified_oak_slab'] && (slabs.entities ?? 0) === 0 && (slabs.player ?? 0) === 0 && u.removed === true && refilled === true,
      `11 recovery: F 0 broken (dropped ${json(dropped)}: its filter items, no slab), the undo removed the hall (${refusal(u)}), grown again: every F filled exactly once more (${refilled})`, out.recovery);
  } finally {
    await cmd('/tick unfreeze').catch(() => null);
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

// ------------------------------------------------------------------ sort-crash (storage_hall@2): 12.5 bar 9, 12.7 B3

const savesDir = () => path.join(GAME, 'saves', worldName);
const savedLedger = () => {
  const f = path.join(savesDir(), 'architect', 'stockpiles.json');
  return fs.existsSync(f) ? (JSON.parse(fs.readFileSync(f, 'utf8')).stockpiles ?? []).find((x) => x.owner === OWNER)?.ledger ?? null : null;
};
/**
 * Waits for the armed kill point's JVM halt (Architect's killAt is Runtime.halt: the whole client exits, nothing saved): true when the
 * launched client exited and its log holds this point's own "kill point <P> reached" line written since {@code from} (any other exit is not
 * the point's). Call {@link #killFrom} just before arming.
 */
const killFrom = () => { const f = path.join(ART, 'client.log'); return fs.existsSync(f) ? fs.statSync(f).size : 0; };
async function haltWait(point, from) {
  for (let i = 0; i < 300 && !exited; i++) await sleep(1000);
  if (!exited) return false;
  dev = null;
  const f = path.join(ART, 'client.log');
  const tail = fs.existsSync(f) ? fs.readFileSync(f, 'utf8').slice(from) : '';
  return new RegExp(`kill point ${point.replace('+', '\\+')} reached`).test(tail);
}
/** The client launched again into the same world after a halt (nothing saved since the halt's baseline). */
async function restartWorld(pos) {
  await sleep(4000);
  await startClient(null, { mode: clientMode, world: worldName });
  await call('dev.survival.set', { on: true }).catch(() => null);
  await rules();
  await standAt(...pos);
  await sleep(4000);
}
async function sortCrash() {
  if (MODULE.version < 2) throw new Error('sort-crash is storage_hall@2\'s step: --version 2');
  const out = { fill: [], removal: [] };
  try {
    const T = await survivalWorld('SortCrash', 80000, 80000);
    const { region, gy, x0, z0 } = T;
    const home = [x0 + 96, z0 - 30, gy + 1];
    const items = ['stone', 'redstone', 'dirt', 'coal'];
    const FILL = Object.fromEntries(items.map((k) => [`minecraft:${k}`, 41]));
    const only = (m) => Object.fromEntries(Object.entries(m ?? {}).filter(([k]) => k in FILL));
    const grown = [];
    // ---- FK1-FK4: the fill halted at each point; after the restart, it runs once: charged once against the saved state
    for (const point of ['FK1', 'FK2', 'FK3', 'FK4']) {
      const sp = { id: `sk_${point.toLowerCase()}`, slots: 4, bulk: 0, bible: 'rustic', items };
      const g = await growHalls(region, [sp], { survival: true });
      if (!check(!g.refused, `${point}: the hall grown: ${g.refused ? String(g.refused).slice(0, 200) : `rid ${g.rid}`}`)) continue;
      // the lot waits for its road first, which is fed from the stockpile: fed until the region names the hall's site (not past it)
      let site = null;
      await feedUntil(region, (st) => !!(site = st.view.lots.find((l) => l.id === sp.id)?.siteId ?? null), { timeoutMs: 900_000 });
      if (!check(!!site, `${point}: the hall's site named while it builds (${site})`)) continue;
      // the site paused, everything it owes delivered (a paused target still takes it), the baseline saved, the point armed, then resumed
      const paused = await call('dev.stockpile.pause', { owner: OWNER, site, paused: true }, 60_000).then(() => true, (e) => String(e));
      const f0 = await fkState(site);
      if (!check(paused === true && !f0.error && f0.phase == null, `${point}: the site paused before its fill (${paused === true ? 'paused' : paused}; FK ${f0.error ?? f0.phase ?? 'not begun'})`)) continue;
      for (let i = 0; i < 20; i++) {
        const ps = await pile();
        const need = {};
        for (const [it, n] of Object.entries(ps.outstanding ?? {})) { const lack = n - (ps.stock?.[it] ?? 0); if (lack > 0) need[it] = lack; }
        if (!Object.keys(need).length) break;
        await req('dev.crate.insert', { site: `stockpile:${OWNER}`, items: need, exact: true }, 120_000);
        await sleep(1500);
      }
      await call('dev.stockpile.ps', { noPeriodic: true }, 30_000).catch(() => null);
      await cmd('/save-all flush');
      const B = savedLedger();
      const from = killFrom();
      await call('dev.journal.killAt', { point }, 10_000);
      call('dev.stockpile.pause', { owner: OWNER, site, paused: false }, 60_000).catch(() => null);
      const down = await haltWait(point, from);
      check(down, `${point}: the kill point halted the server`);
      if (!down) { await call('dev.journal.killAt', { point: null }, 10_000).catch(() => null); continue; }
      const S = savedLedger();
      const marker = (S?.filled ?? []).some((k) => k.startsWith(`site:${site}@`));
      await restartWorld(home);
      // the baseline was saved with the site paused, and the halt lost the resume: resumed again
      await call('dev.stockpile.pause', { owner: OWNER, site, paused: false }, 60_000).catch(() => null);
      await feedUntil(region, async () => (await fkState(site)).phase === 'UNLOCKED', { timeoutMs: 900_000 });
      const f = await fkState(site);
      const L2 = (await pile()).ledger;
      const charged = only(minus(L2.consumed, S.consumed));
      const want = marker ? {} : FILL;
      const holds = (f.containers ?? []).map((c) => c.holds);
      const exactly = holds.length === 4 && holds.every((m, u) => m?.[`minecraft:${items[u]}`] === 41 && m?.['minecraft:petrified_oak_slab'] === 4 && Object.keys(m).length === 2);
      const res = { point, site, marker, before: only(minus(S.consumed, B.consumed)), charged, phase: f.phase, holds };
      out.fill.push(res);
      check(f.phase === 'UNLOCKED' && exactly && same(charged, want), `9 ${point}: after the restart the fill ends once (FK ${f.phase}, every F exactly 41 + 4: ${exactly}); charged since the saved state ${json(charged)} (the commit ${marker ? 'on disk: nothing more' : 'not on disk: the fill once'})`, res);
      grown.push({ g, site, sp });
    }
    // ---- FKC1, FKC2, CS1 at removal, newest first: after the restart, what comes back = what the growth consumed, once; no custody or slab left
    const removalPoints = ['FKC1', 'FKC2', 'CS1'];
    const cases = [...grown].reverse().slice(0, removalPoints.length).map((x, i) => ({ ...x, point: removalPoints[i] }));
    for (const c of cases) {
      const L0 = (await pile()).ledger;
      await cmd('/kill @e[type=item]');
      await cmd('/save-all flush');
      const B = savedLedger();
      // what this growth consumed: its quote (consumed = quote, bar 8)
      const consumed = c.g.quote?.bom ?? {};
      const from = killFrom();
      await call('dev.journal.killAt', { point: c.point }, 10_000);
      call('dev.rv.undo', { region, toPosition: c.g.position - 1 }, 600_000).catch(() => null);
      const down = await haltWait(c.point, from);
      check(down, `${c.point}: the kill point halted the server at removal`);
      if (!down) { await call('dev.journal.killAt', { point: null }, 10_000).catch(() => null); continue; }
      const S = savedLedger();
      const savedCustody = Object.values(S?.custody ?? {}).reduce((a, m) => plus(a, m), {});
      await restartWorld(home);
      let rs = null;
      for (let i = 0; i < 300; i++) {
        rs = (await call('dev.rv.state', { region }, 60_000)).revisions.find((x) => x.rid === c.g.rid)?.state;
        if (rs === 'UNDONE') break;
        if (i === 15 && rs === 'PLACED') await call('dev.rv.undo', { region, toPosition: c.g.position - 1 }, 600_000).catch(() => null);
        await sleep(1000);
      }
      await sleep(4000);
      const F = (await pile()).ledger;
      const back = minus(plus(F.returned, F.refundOverflow), plus(B.returned, B.refundOverflow));
      const drops = (await mod({ op: 'takeItems', box: [x0 - 300, gy - 4, z0 - 300, x0 + 600, gy + 60, z0 + 600] })).items;
      const slabs = await call('dev.fk.slabs', { pos: home }, 10_000).catch(() => ({}));
      const res = { point: c.point, savedCustody, state: rs, back: totalOf(back), consumed: totalOf(consumed), diff: minus(back, consumed), custody: F.custody ?? null, heldProvided: F.heldProvided ?? null, drops, slabs };
      out.removal.push(res);
      check(c.point !== 'FKC1' || totalOf(savedCustody) > 0, `9 ${c.point}: the custody (${json(savedCustody)}) was on disk at the halt`, res);
      check(rs === 'UNDONE' && same(back, consumed) && !Object.keys(F.custody ?? {}).length && !Object.keys(F.heldProvided ?? {}).length && !Object.keys(drops).length && (slabs.player ?? 0) === 0 && (slabs.entities ?? 0) === 0,
        `9 ${c.point}: after the restart the removal ends once (${rs}): back = consumed item for item (${totalOf(back)} = ${totalOf(consumed)}; off ${json(res.diff)}); no custody or provided custody left; nothing dropped; no slab loose`, res);
    }
    // ---- FKC0 (Architect's LC, S-M5-13): a removal failing after its custody take, a restart, a retry. Runs once the hook exists.
    const rest = [...grown].reverse().slice(removalPoints.length);
    const hook = await call('dev.removal.failAfterCustody', { probe: true }, 10_000).then(() => true).catch(() => false);
    if (!hook) {
      // Noah, 2026-11-15 ("Certify now, name the gap"): @2 certifies before Architect's LC; FKC0 runs at the pin that carries LC2 (LC was reverted at v0.73.0)
      out.fkc0 = 'deferred: the hook dev.removal.failAfterCustody is not in this Architect (S-M5-13, slice LC); the offer names the gap';
      check(true, `9 FKC0: ${out.fkc0}`);
    } else if (rest.length) {
      const c = rest[0];
      const L0 = (await pile()).ledger;
      await call('dev.removal.failAfterCustody', { site: c.site }, 10_000);
      const u = await call('dev.rv.undo', { region, toPosition: c.g.position - 1 }, 600_000).catch((e) => ({ error: String(e) }));
      await sleep(4000);
      const s1 = await sortState(c.g.halls[0]).catch(() => null);
      await cmd('/save-all flush');
      await reopen(home);
      const u2 = await call('dev.rv.undo', { region, toPosition: c.g.position - 1 }, 600_000).catch((e) => ({ error: String(e) }));
      await sleep(6000);
      const F = (await pile()).ledger;
      const back = minus(plus(F.returned, F.refundOverflow), plus(L0.returned, L0.refundOverflow));
      const res = { first: refusal(u), afterFail: s1 ? totalOf(sumAll(s1)) : null, retry: refusal(u2), back: totalOf(back), consumed: totalOf(c.g.quote?.bom ?? {}) };
      out.fkc0 = res;
      check(u2.removed === true && same(back, c.g.quote?.bom ?? {}), `9 FKC0: the removal failed after its custody take (${refusal(u)}), its contents written back (${res.afterFail} items), restarted and retried: removed, back = consumed once (${res.back} = ${res.consumed})`, res);
    }
  } finally {
    await call('dev.journal.killAt', { point: null }, 10_000).catch(() => null);
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

const steps = { hall, survival, bounds, sort: sortStep, 'sort-survival': sortSurvival, 'sort-cargo': sortCargo, 'sort-crash': sortCrash };
if (steps[step]) {
  const t0 = Date.now();
  let data, error;
  try { data = await steps[step](); } catch (e) { error = String(e?.stack ?? e); check(false, `${step}: ${String(e?.message ?? e).slice(0, 400)}`); }
  const ok = checks.length > 0 && checks.every((c) => c.ok);
  const head = execFileSync('git', ['-C', ROOT, 'rev-parse', 'HEAD']).toString().trim();
  fs.writeFileSync(path.join(ART, `${step}.json`), `${JSON.stringify({ step, ok, minecraft: MC_VERSION, architect: ARCH_VERSION, at: new Date().toISOString(), head, ...stamp(), world: worldName, seconds: (Date.now() - t0) / 1000, spendUsd: 0, error, checks, data }, null, 2)}\n`);
  log(`${step}: ${ok ? 'PASS' : 'FAIL'}, ${checks.filter((c) => c.ok).length}/${checks.length} checks, ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  process.exit(ok ? 0 : 1);
} else {
  console.log(`usage: node tools/module-measure-hall.mjs ${Object.keys(steps).join('|')} [--attach] [--keep-client]`);
  process.exit(2);
}
