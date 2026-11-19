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
import { acquire as acquireSlot, release as releaseSlot } from './lib/slot-queue.mjs';
import { repairSidecarData } from './lib/ram-tmp.mjs';
import { checkCommand, savedOnDisk } from './lib/save-barrier.mjs';
import { t4Gate, custodyAtHalt, custodyClear, reUndoAnswer, FKC0_HOOK } from './lib/t4-rows.mjs';
import { overflowGrew } from './lib/overflow.mjs';

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
  for (const p of [PORT - 1, PORT]) if (await listening(p)) throw new Error(`port ${p} is in use (another client?): refusing to launch; use --attach to drive it`);
  if (fs.existsSync(path.join(GAME, 'architect', 'devbridge.token'))) fs.rmSync(path.join(GAME, 'architect', 'devbridge.token'));
  // a sidecar-data link left dangling by a reboot (an earlier e2e's RAM-disk dir) stops Architect's sidecar from starting (LINK-SIDECAR)
  repairSidecarData(GAME);
  if (fs.existsSync(path.join(GAME, 'architect', 'sidecar-data', 'secrets.json'))) throw new Error('the claude-login opt-in exists: refusing');
  worldName = world ?? `Steward M4 ${label} ${new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')}`;
  exited = false;
  clientMode = mode;
  // one slot from Architect's machine-wide queue (slice SQ, tools/lib/slot-queue.mjs), held by this driver until the client quits;
  // a relaunch after a kill point's halt keeps the slot it already holds (the halted client never went through stopClient)
  if (!slot) slot = await acquireSlot(`module-measure-hall ${label ?? 'relaunch'}`, { root: ROOT });
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
let slot = null;
async function stopClient() {
  // a kept client keeps its slot until this driver ends (the exit handler frees it)
  if (flag('--attach') || flag('--keep-client')) return;
  try {
    if (!dev || !launched || exited) return;
    const r = await dev.request('dev.quit');
    if (r.ok) await dev.waitClosed(180_000);
  } finally {
    // done for good: the slot goes back, also when the client died before its bridge answered
    if (slot?.ticket) releaseSlot(slot.ticket);
    slot = null;
  }
}
const call = (type, payload = {}, timeoutMs = 120_000) => dev.call(type, payload, { timeoutMs });
const req = (type, payload = {}, timeoutMs = 120_000) => dev.request(type, payload, { timeoutMs });
// (SAVEB) an unknown or unparsable command throws (the bridge's ok only says the call did not throw); allowFail for one expected to
const cmd = async (c, { allowFail = false } = {}) => checkCommand(await req('dev.command', { cmd: c }), { cmd: c, allowFail });
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
async function growHalls(region, specs, { survival = false, realise = true, want = null } = {}) {
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
  // (bar 1, 5th run) want: the front each spec's lot must face; a plan facing otherwise is not realised (plans are free until realised)
  const faces = (pl) => !want || specs.every((sp) => { const l = (pl.lots ?? []).find((x) => x.id === sp.id); return l && lotFrame(l).front === want[sp.id]; });
  let wrong = null;
  for (const pr of ['frontage', 'extend']) {
    p = await req('dev.rv.plan', { region, program: 'flat_village', params: { grow: { lots, prefer: pr } }, opKey: `steward.m4${run36}.${specs[0].id}.${pr}.${region}.plan` }, 900_000);
    if (p.ok && p.planId && faces(p)) break;
    if (p.ok && p.planId) { wrong = (p.lots ?? []).map((l) => `${l.id} ${lotFrame(l).front}`).join(', '); p = null; continue; }
    log(`  grow (${pr}) refused: ${String(p.refused ?? p.error).slice(0, 300)}`);
  }
  if (!p?.planId) return { refused: p?.refused ?? p?.error ?? `faces ${wrong}, not ${json(want)}`, wrongFront: !!wrong };
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

/**
 * (evidence, MEASURE_EVIDENCE_DIR) copies the world's Architect state files (stockpiles.json, architect-sites.json, architect-queue.json and each
 * architect-regions/<id>/region.json) to <dir>/<tag>/, keeping their paths: what a restart reads and what it left.
 */
function snapshotWorld(tag) {
  const dir = process.env.MEASURE_EVIDENCE_DIR;
  if (!dir || !worldName) return;
  const w = path.join(GAME, 'saves', worldName), out = path.join(dir, tag);
  const files = [];
  const walk = (d) => { for (const e of fs.existsSync(d) ? fs.readdirSync(d, { withFileTypes: true }) : []) { const f = path.join(d, e.name); if (e.isDirectory()) walk(f); else if (/^(stockpiles\.json|architect-sites\.json|architect-queue\.json)$/.test(e.name) || (e.name === 'region.json' && f.includes(`${path.sep}architect-regions${path.sep}`))) files.push(f); } };
  walk(w);
  for (const f of files) { const t = path.join(out, path.relative(w, f)); fs.mkdirSync(path.dirname(t), { recursive: true }); fs.copyFileSync(f, t); }
  log(`evidence ${tag}: ${files.length} file(s) from ${w} to ${out}`);
}

async function reopen(pos, evidence = null) {
  // dev.world.leave saves on stop (/save-all is dedicated-only: Unknown on this integrated server)
  await call('dev.world.leave', {}, 300_000);
  await sleep(2000);
  if (evidence !== null) snapshotWorld(`${evidence}before`);
  await call('dev.world.open', { name: worldName }, 300_000);
  await dev.waitInWorld({ timeoutMs: 600_000 });
  if (evidence !== null) snapshotWorld(`${evidence}after`);
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
        // (CHECK-079) Architect 0.79.0: every give-back enters the stock, so refundOverflow never grows over a refund
        out.cancel.overflowGrew = overflowGrew(mid.ledger, after.ledger);
        check(out.cancel.partial && u.removed === true && same(back, consumed) && !Object.keys(minus(back, consumed)).some((k) => minus(back, consumed)[k] > 0) && !out.cancel.overflowGrew,
          `7 cancel mid-build rid ${g.rid}: consumed ${tot(consumed)} of the quote's ${tot(g.quote?.bom)} items before the undo (removed ${u.removed}, ${out.cancel.state}); refunded exactly that (${same(back, consumed) ? 'equal' : `off by ${json(minus(back, consumed))}`})${out.cancel.overflowGrew ? `; ${out.cancel.overflowGrew}` : '; none to overflow'}`, out.cancel);
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
      // (CHECK-079) Architect 0.79.0: every give-back enters the stock, so refundOverflow never grows over a refund
      g.undo = { removed: u.removed ?? null, blockers: u.blockers ?? [], error: u.error ?? null, back, drops, overflowGrew: overflowGrew(before.ledger, after.ledger) };
      const want = g.wantBack ?? g.consumed;
      check(u.removed === true && same(back, want) && Object.keys(drops).length === 0 && !g.undo.overflowGrew, `7 refund rid ${g.rid}: removed ${u.removed}; credited (with overflow) = consumed${g.mined ? ' less the chest the player mined' : ''} (${same(back, want) ? 'equal' : `off by ${json(minus(back, want))}`}); dropped ${json(drops)}${g.undo.overflowGrew ? `; ${g.undo.overflowGrew}` : '; none to overflow'}`, g.undo);
    }
    const ledger1 = await pile();
    await reopen([x0 - 20, z0 - 20, gy + 2], '');
    await sleep(6000);
    const ledger2 = await pile();
    const stray = (await mod({ op: 'takeItems', box: [x0 - 300, gy - 4, z0 - 300, x0 + 600, gy + 60, z0 + 600] })).items;
    // what moved, kept with the check (a failure says which ledger members and items changed, not only that they did)
    // every ledger member and stock item compared exactly (Architect 0.79.0 fixed DR2-DOUBLE: no empty pendingCredit record goes at a start)
    const moved = {};
    for (const k of new Set([...Object.keys(ledger1.ledger ?? {}), ...Object.keys(ledger2.ledger ?? {})])) {
      if (json(ledger1.ledger?.[k]) !== json(ledger2.ledger?.[k])) moved[k] = { before: ledger1.ledger?.[k] ?? null, after: ledger2.ledger?.[k] ?? null };
    }
    const stockMoved = minus(ledger2.stock, ledger1.stock);
    check(json(ledger2.ledger) === json(ledger1.ledger) && json(ledger2.stock) === json(ledger1.stock) && Object.keys(stray).length === 0, `7 after a restart: nothing given twice (the ledger and stock unchanged; items on the ground ${json(stray)})${Object.keys(moved).length || Object.keys(stockMoved).length ? `; changed: ledger ${Object.keys(moved).join(', ') || 'none'}, stock ${json(stockMoved)}` : ''}`, { moved, stockMoved });
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
/** A hall's five plain kinds: domain items it doesn't filter, from its own offset (mixAt), so the halls together use every domain item. */
function plainOf(h, domain) {
  if (Array.isArray(h.plain) && h.plain.length) return h.plain;
  const own = new Set(h.filterIds);
  const rest = domain.map((k) => `minecraft:${k}`).filter((i) => !own.has(i));
  const at = (h.mixAt ?? 0) % rest.length;
  return Array.from({ length: 5 }, (_, i) => rest[(at + i) % rest.length]);
}
function mixOf(h, domain) {
  const plain = plainOf(h, domain);
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
/**
 * (GPT review 8) Every drained item counted: per hall, everything in it before = everything taken out of its outputs + everything left, and
 * the hall settled before the limit. {byHall: {id: {settled, conserved, taken, off}}}.
 */
async function drain(halls, limit = 60_000) {
  const before = new Map(), taken = new Map(halls.map((h) => [h.id, {}]));
  for (const h of halls) before.set(h.id, sumAll(await sortState(h)));
  let settled = false;
  for (let t = 0; t < limit; t += 1000) {
    let busy = false;
    for (const h of halls) { const s = await sortState(h); if (totalOf(s.input) || s.line.some((m) => totalOf(m)) || !dSettled(h, s)) busy = true; }
    if (!busy) { settled = true; break; }
    for (const h of halls) taken.set(h.id, plus(taken.get(h.id), await clearOutputs(h)));
    await advance(1000);
  }
  for (const h of halls) taken.set(h.id, plus(taken.get(h.id), await clearOutputs(h)));
  const byHall = {};
  for (const h of halls) {
    const left = sumAll(await sortState(h));
    const off = minus(plus(taken.get(h.id), left), before.get(h.id));
    byHall[h.id] = { settled, conserved: !Object.keys(off).length, taken: taken.get(h.id), off };
  }
  return byHall;
}
/** The player empties a hall's outputs (each slot chest, each D, the overflow): what was taken. */
async function clearOutputs(h) {
  let taken = {};
  for (const c of h.sort) {
    taken = plus(taken, (await mod({ op: 'clearContainers', box: [...c.chest, ...c.chest] })).taken);
    taken = plus(taken, (await mod({ op: 'clearContainers', box: [...c.D, ...c.D] })).taken);
  }
  return plus(taken, (await mod({ op: 'clearContainers', box: [...h.overflow, ...h.overflow] })).taken);
}

async function sortStep() {
  if (MODULE.version < 2) throw new Error('sort is storage_hall@2\'s step: --version 2');
  await startClient('Sort');
  const out = { growths: [], halls: {}, runs: [], coverage: {} };
  try {
    await rules();
    await install();
    const domain = MODULE_JSON.params.item1.options.filter((o) => o !== 'none');
    // (bar 1) each sorting branch in rustic in all four facings, with its own items rotating through the domain; fortress and desert for 4
    // and 16 (sorting and light). Each hall also its own five plain kinds (mixAt), so every domain item is a non-filter kind somewhere
    let at = 0;
    const pick = (n) => { const it = Array.from({ length: n }, (_, i) => domain[(at + i) % domain.length]); at = (at + n) % domain.length; return it; };
    const FRONTS = ['north', 'east', 'south', 'west'];
    const specs = [];
    for (const slots of [4, 8, 12, 16]) FRONTS.forEach((f, k) => specs.push({ id: `s${slots}_${f[0]}`, slots, bulk: [2, 8, 0, 4][k], bible: 'rustic', items: pick(slots), front: f }));
    for (const slots of [4, 16]) for (const bible of ['fortress', 'desert']) specs.push({ id: `s${slots}_${bible.slice(0, 3)}`, slots, bulk: 4, bible, items: pick(slots), front: null });
    // (12.1) each hall's five plain kinds chosen so the halls together use every domain item as a non-filter kind: the uncovered ones first
    // (never the hall's own filters), then the domain in order; asserted before any hall is placed, so a gap can't pass silently
    const uncovered = new Set(domain);
    for (const sp of specs) {
      const own = new Set(sp.items);
      const pl = [...uncovered].filter((k) => !own.has(k)).slice(0, 5);
      for (const k of domain) if (pl.length < 5 && !own.has(k) && !pl.includes(k)) pl.push(k);
      pl.forEach((k) => uncovered.delete(k));
      sp.plain = pl.map((k) => `minecraft:${k}`);
    }
    if (process.env.SORT_QUICK !== '1') check(uncovered.size === 0, `12.1 coverage planned: every domain item a non-filter kind in some hall's mix before the run (${uncovered.size ? `uncovered ${[...uncovered].join(', ')}` : 'all 48'})`);
    // SORT_QUICK=1: a probe of the run's machinery (two halls, one run each), never evidence: its coverage checks fail by design
    if (process.env.SORT_QUICK === '1') specs.splice(0, specs.length, ...specs.filter((sp) => sp.id === 's4_n' || sp.id === 's4_e'));
    // the towns: the first four as before (one per street orientation, two at shifted claims for chunk-border crossings); more as the
    // facings need them. An ew street's lots face north or south, an ns street's east or west
    const sites = [[0, 0, 'ew'], [256, 0, 'ns'], [9, 505, 'ew'], [263, 511, 'ns'], [0, 1024, 'ew'], [256, 1024, 'ns'], [512, 0, 'ew'], [512, 512, 'ns'], [512, 1024, 'ew'], [768, 0, 'ns'], [768, 512, 'ew'], [768, 1024, 'ns']];
    const towns = [];
    const townAt = async (i) => {
      if (!towns[i]) { const [x, z, o] = sites[i]; towns[i] = await town(x, z, o === 'ns' ? { w: 176, d: 224, orientation: 'ns' } : { orientation: 'ew' }); towns[i].orientation = o; }
      return towns[i];
    };
    for (let i = 0; i < 4; i++) await townAt(i);
    const halls = [];
    for (const sp of specs) {
      const axis = sp.front === null ? null : (sp.front === 'north' || sp.front === 'south' ? 'ew' : 'ns');
      let placed = false;
      for (let i = 0; i < sites.length && !placed; i++) {
        if (axis && sites[i][2] !== axis) continue;
        const t = await townAt(i);
        await standAt(t.cx, t.cz, t.gy + 30);
        const g = await growHalls(t.region, [sp], { want: sp.front ? { [sp.id]: sp.front } : null });
        if (g.refused) { log(`  ${sp.id} in ${t.region}: ${String(g.refused).slice(0, 200)}`); continue; }
        const w = await rvWait(t.region, g.rid);
        check(w.r.state === 'PLACED', `growth ${sp.id} in ${t.region} (front ${g.halls[0].frame.front}): rid ${g.rid} ${w.r.state}`);
        await hallCells(t.region, g.halls);
        for (const h of g.halls) { h.region = t.region; h.rid = g.rid; h.position = g.position; h.town = t; h.plain = sp.plain; }
        out.growths.push({ region: t.region, rid: g.rid, position: g.position, halls: g.halls.map((h) => h.id) });
        halls.push(...g.halls);
        placed = true;
      }
      check(placed, `12.5 bar 1 placed ${sp.id} (${sp.bible}, ${sp.slots} slots${sp.front ? `, front ${sp.front}` : ''})`);
    }
    const towns_ = towns.filter(Boolean);
    // coverage: every domain item a filter at least once
    const used = new Set(halls.flatMap((h) => h.items));
    out.coverage = { filters: [...used].sort(), missing: domain.filter((k) => !used.has(k)) };
    check(out.coverage.missing.length === 0, `12.1 coverage: every one of the ${domain.length} domain items is a filter in some hall (${out.coverage.missing.length ? `missing ${out.coverage.missing.join(', ')}` : 'all'})`);
    // (bar 1) each sorting branch in all four facings, in rustic
    for (const slots of [4, 8, 12, 16]) {
      const fr = new Set(halls.filter((h) => h.slots === slots && h.bible === 'rustic').map((h) => h.frame.front));
      check(FRONTS.every((f) => fr.has(f)), `12.5 bar 1 facings ${slots} slots (rustic): ${FRONTS.filter((f) => fr.has(f)).join(', ')}${FRONTS.some((f) => !fr.has(f)) ? `; missing ${FRONTS.filter((f) => !fr.has(f)).join(', ')}` : ''}`);
    }
    // (12.1, GPT review 4) every domain item a plain (non-filter) kind in some hall's mix
    const plainUsed = new Set(halls.flatMap((h) => plainOf(h, domain)));
    const plainMissing = domain.filter((k) => !plainUsed.has(`minecraft:${k}`));
    out.coverage.plainMissing = plainMissing;
    check(plainMissing.length === 0, `12.1 coverage: every domain item a non-filter kind in some hall's mix (${plainMissing.length ? `missing ${plainMissing.join(', ')}` : 'all'})`);
    // the line's axis and the control arm's axis per hall: a crossing along the line (x in the template) and one through a control arm (z)
    const along = halls.filter((h) => { const xs = h.sort.map((c) => Math.floor((h.frame.front === 'north' || h.frame.front === 'south' ? c.line[0] : c.line[2]) / 16)); return new Set(xs).size > 1; }).map((h) => h.id);
    const arm = halls.filter((h) => h.sort.some((c) => { const a = h.frame.front === 'north' || h.frame.front === 'south' ? [c.line[2], c.line[2] + (h.frame.front === 'south' ? -4 : 4)] : [c.line[0], c.line[0] + (h.frame.front === 'east' ? 4 : -4)]; return Math.floor(a[0] / 16) !== Math.floor(a[1] / 16); })).map((h) => h.id);
    out.crossings = { along, arm };
    check(along.length > 0 && arm.length > 0, `12.5/12.7 S7 chunk borders: halls crossing along their line ${json(along)}, through a tile's control arm ${json(arm)}`);
    // ---- 6 shape: standing; F exactly 41 + 4 (the fill); the lever unlocked; the slot, input and overflow chests single; every chest opens
    await freeze(); // (bar 6's access) nothing moves between the test's put and the player's take
    for (const t of towns_) {
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
        // (bar 6, access [1]) a survival player opens and empties every chest through its menu: the input, the overflow, each slot chest, each
        // bulk half (both rows). One stack put in, the chest opened as a player opens it, everything taken out through the menu
        const chests = [['input', h.input], ['overflow', h.overflow], ...h.sort.map((c, u) => [`slot ${u + 1}`, c.chest]), ...h.pairs.flatMap((pr, i) => pr.map((c, k) => [`bulk ${i + 1}${k ? 'b' : 'a'}`, c]))];
        const shut = [];
        for (const [name, c] of chests) {
          await mod({ op: 'putItems', at: c, stacks: [{ slot: 0, id: 'minecraft:cobblestone', count: 7 }] });
          const r = (await mod({ op: 'playerOpen', cells: [c] })).cells?.[0] ?? {};
          if (!(r.opened && r.took?.['minecraft:cobblestone'] === 7 && Object.keys(r.took).length === 1 && r.left === 0)) {
            shut.push(`${name} ${json({ opened: r.opened, took: r.took, left: r.left, result: r.result })}`);
            await mod({ op: 'clearContainers', box: [...c, ...c] }); // the test's stack never joins a sorting run
          }
        }
        check(!shut.length, `12.5 bar 6 access ${h.id}: a player opens and empties all ${chests.length} chests through the menu${shut.length ? `; not: ${shut.slice(0, 3).join('; ')}` : ''}`);
      }
    }
    // ---- 4 light
    await cmd('/time set 18000');
    for (const t of towns_) {
      await standAt(t.cx, t.cz, t.gy + 30);
      for (const h of halls.filter((x) => x.region === t.region)) {
        const r = await readCells(h.light);
        const air = r.filter((c) => c && (c[0] === 'minecraft:air' || c[0] === 'minecraft:cave_air'));
        const dark = air.filter((c) => c[3] < 1).length;
        check(dark === 0 && air.length > 0, `12.5 bar 6 light ${h.id} (${h.bible}): ${air.length} air cells inside and on the front row, the darkest ${Math.min(...air.map((c) => c[3]))}`);
      }
    }
    await cmd('/time set 6000');
    // ---- 1, 2: sorting, 20 runs a rustic branch (5 in each facing's hall; 3 of the others), by town (its halls run together)
    await freeze();
    // a rustic hall runs at least 5 times and enough for bar 4's 10,000 items through it
    const RUNS = (h) => (process.env.SORT_QUICK === '1' ? 1 : h.bible === 'rustic' ? Math.max(5, Math.ceil(10_000 / mixOf(h, domain).reduce((a, st) => a + st.count, 0))) : 3);
    for (const t of towns_) {
      await standAt(t.cx, t.cz, t.gy + 30);
      const th = halls.filter((x) => x.region === t.region);
      for (let r = 0; r < Math.max(0, ...th.map(RUNS)); r++) {
        const runHalls = th.filter((h) => r < RUNS(h));
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
      const want = RUNS(h);
      check(mine.length >= want && mine.every((x) => x.sorted), `12.5 bar 1 sorting ${h.id}: ${mine.filter((x) => x.sorted).length}/${mine.length} runs 100% (${want} needed)${mine.find((x) => !x.sorted) ? `: ${mine.find((x) => !x.sorted).why.slice(0, 3).join('; ')}` : ''}`);
      const ips = mine.map((x) => x.itemsPerSecond).filter(Boolean);
      const mean = ips.reduce((a, b) => a + b, 0) / (ips.length || 1);
      out.halls[h.id].itemsPerSecond = mean;
      check(true, `12.5 bar 2 throughput ${h.id} (report): ${mean.toFixed(3)} items a second over ${ips.length} runs (bar 2.241-2.739: ${mean >= 2.241 && mean <= 2.739 ? 'within' : 'OUTSIDE: flag'})`);
    }
    // (bar 1) 20 runs a rustic branch, over its four facings, every one 100%
    for (const slots of [4, 8, 12, 16]) {
      const mine = all.filter((x) => halls.find((h) => h.id === x.id && h.slots === slots && h.bible === 'rustic'));
      check(mine.length >= 20 && mine.every((x) => x.sorted), `12.5 bar 1 sorting the ${slots}-slot branch (rustic, four facings): ${mine.filter((x) => x.sorted).length}/${mine.length} runs 100% (20 needed)`);
    }
    // ---- 4: the filters hold after the runs (> 10,000 items through each branch)
    for (const h of halls) {
      await standAt(h.town.cx, h.town.cz, h.town.gy + 30);
      const s = await sortState(h);
      const thru = all.filter((x) => x.id === h.id).reduce((a, x) => a + x.items, 0);
      check(h.filterIds.every((f, u) => fExact(s.F[u], f)) && (h.bible !== 'rustic' || thru >= 10_000), `12.5 bar 4 the filters hold ${h.id}: after ${thru} items every F exactly 41 + 4${h.bible === 'rustic' ? ' (10,000 needed)' : ''}`);
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
    const dr1 = await drain(sat);
    for (const h of sat) check(dr1[h.id].settled && dr1[h.id].conserved, `12.5 bar 3 saturation drain ${h.id}: the backlog settled, every item counted (${totalOf(dr1[h.id].taken)} taken; off ${json(dr1[h.id].off)})`, dr1[h.id]);
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
    const dr2 = await drain(sat);
    for (const h of sat) check(dr2[h.id].settled && dr2[h.id].conserved, `12.5 bar 3 overflow drain ${h.id}: the backlog settled, every item counted (${totalOf(dr2[h.id].taken)} taken; off ${json(dr2[h.id].off)})`, dr2[h.id]);
    const rec2 = await sortRun(sat, domain);
    for (const h of sat) check(rec2[h.id].sorted, `12.5 bar 3 overflow recovery ${h.id}: emptied, 100% again (${rec2[h.id].why.slice(0, 2).join('; ')})`);
    for (const h of sat) await clearOutputs(h);
    // ---- 5: restart mid-sort; unload mid-sort (every chunk); a partial unload (one side of a crossing ticking, the other not)
    for (const [label, how] of [['restart', 'restart'], ['unload', 'unload'], ['partial', 'partial']]) {
      // restart and unload in towns[1] (or the first town holding halls); the partial in the town of a hall crossing a chunk border
      const crossing = halls.filter((h) => along.includes(h.id) || arm.includes(h.id));
      const t = label === 'partial' ? (crossing[0]?.town ?? towns[2]) : (halls.some((x) => x.region === towns[1].region) ? towns[1] : (halls[0]?.town ?? towns[1]));
      await standAt(t.cx, t.cz, t.gy + 30);
      let th = halls.filter((x) => x.region === t.region);
      if (label === 'partial') th = th.filter((h) => along.includes(h.id) || arm.includes(h.id)).slice(0, 2);
      if (!th.length) { check(false, `12.5 bar 5 ${label}: no hall to run it on`); continue; }
      const r = await sortMidway(th, domain, how, t);
      if (how !== 'restart') {
        const u = r[th[0].id].unload;
        if (how === 'unload') check(u.unloadedAll && u.ticks >= 600, `12.5 bar 5 unload: asserted: all ${u.unforced} probes' chunks unloaded, ${u.ticks} game ticks passed so`, u);
        else check(u.forcedLoaded && u.ticks >= 600, `12.5 bar 5 partial: asserted: the forced side loaded for ${u.ticks} game ticks with the player away; the other side recorded (${json((u.other ?? []).map((x) => x.loaded))} loaded: a forced chunk's neighbours block-tick in vanilla)`, u);
      }
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
  const exp = new Map(), fedAll = new Map(), before = new Map(), rest = new Map();
  for (const h of halls) {
    const mix = mixOf(h, domain);
    before.set(h.id, await sortState(h));
    // (GPT review) the whole mix in acknowledged batches of 27: the first before the interruption, the rest after it, each once the input
    // is empty; the run is judged on the whole mix (the renamed stack and every filter's stacks included)
    const batches = [];
    for (let i = 0; i < mix.length; i += 27) batches.push(mix.slice(i, i + 27));
    const b = batches.shift();
    await mod({ op: 'putItems', at: h.input, stacks: b.map((st, j) => ({ slot: j, ...st })) });
    const f = {};
    for (const st of b) f[keyOf(st)] = (f[keyOf(st)] ?? 0) + st.count;
    fedAll.set(h.id, f);
    rest.set(h.id, batches);
  }
  await advance(400);
  const mid = new Map();
  for (const h of halls) mid.set(h.id, await sortState(h));
  const unload = {};
  if (how === 'restart') {
    await cmd('/tick unfreeze');
    await reopen([t.cx, t.cz, t.gy + 30]);
    await freeze();
  } else if (how === 'unload' || how === 'partial') {
    const forced = [];
    if (how === 'partial') for (const h of halls) { const c = h.sort[0].line; forced.push(c); await cmd(`/forceload add ${c[0]} ${c[2]}`); }
    await cmd('/tick unfreeze');
    await standAt(t.cx + 6000, t.cz + 6000, 120);
    // (bar 5, asserted) every F of every hall is a probe; the forced line hopper's chunk must stay loaded (partial), every other probe unload
    const probes = halls.flatMap((h) => h.sort.map((c) => c.F));
    const forcedChunk = new Set(forced.map((c) => `${c[0] >> 4},${c[2] >> 4}`));
    const isForced = (c) => forcedChunk.has(`${c[0] >> 4},${c[2] >> 4}`);
    let r = [];
    for (let i = 0; i < 180; i++) {
      await sleep(1000);
      r = await readCells(probes);
      const away = probes.filter((c, k) => !isForced(c) && r[k] !== null).length;
      if (away === 0) break;
    }
    const t0 = (await mod({ op: 'cells', cells: [] })).gameTime;
    for (let i = 0; i < 600 && (await mod({ op: 'cells', cells: [] })).gameTime - t0 < 600; i++) await sleep(1000);
    const t1 = (await mod({ op: 'cells', cells: [] })).gameTime;
    const r2 = await readCells([...probes, ...forced]);
    const unforced = probes.filter((c) => !isForced(c));
    unload.unloadedAll = unforced.length > 0 && unforced.every((c) => r2[probes.indexOf(c)] === null) && probes.filter((c, k) => !isForced(c) && r[k] !== null).length === 0;
    // (partial) a forced chunk's neighbours stay loaded and block-ticking in vanilla, so the far side's state is recorded, not judged
    if (how === 'partial') unload.other = await Promise.all(unforced.slice(0, 4).map(async (c) => ({ at: c, loaded: r2[probes.indexOf(c)] !== null, tickets: await call('dev.stockpile.tickets', { pos: c }, 10_000).catch((e) => String(e)) })));
    unload.forcedLoaded = how !== 'partial' || (forced.length > 0 && forced.every((c, k) => r2[probes.length + k] !== null));
    unload.ticks = t1 - t0;
    unload.probes = probes.length;
    unload.unforced = unforced.length;
    for (const c of forced) await cmd(`/forceload remove ${c[0]} ${c[2]}`);
    await standAt(t.cx, t.cz, t.gy + 30);
    const all = halls.flatMap((h) => h.sort.map((c) => c.F));
    for (let i = 0; i < 120 && (await readCells(all)).some((c) => c === null); i++) await sleep(1000);
    await freeze();
  }
  // the rest of each mix, a batch whenever the input is empty
  for (let k = 0; k < 120 && halls.some((h) => rest.get(h.id).length); k++) {
    for (const h of halls) {
      if (!rest.get(h.id).length) continue;
      if (totalOf((await sortState(h)).input)) continue;
      const b = rest.get(h.id).shift();
      const a = await mod({ op: 'putItems', at: h.input, stacks: b.map((st, j) => ({ slot: j, ...st })) });
      if (!a.ok) throw new Error(`put into ${h.id}: ${a.error}`);
      const f = fedAll.get(h.id);
      for (const st of b) f[keyOf(st)] = (f[keyOf(st)] ?? 0) + st.count;
    }
    await advance(1000);
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
    if (!same(f, (() => { const m = {}; for (const st of mixOf(h, domain)) m[keyOf(st)] = (m[keyOf(st)] ?? 0) + st.count; return m; })())) why.push('not the whole mix fed');
    out[h.id] = { sorted: why.length === 0, why, mid: totalOf(sumAll(mid.get(h.id))), unload };
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
    // (GPT review 5) the wait is the fill's own block (fill_blocked), and the contaminant is all still there: the player's 64 cobblestone in the hall
    // (the leak), or the 1 dirt in F 0; then taken back whole, and gone from the hall
    const wt = fk2.siteWait ?? fk2.wait ?? null;
    const contaminant = out.leak ? { 'minecraft:cobblestone': 64 } : { 'minecraft:dirt': 1 };
    const sB = sumAll(await sortState(h));
    const intact = Object.entries(contaminant).every(([k, n]) => (sB[k] ?? 0) === n) && (out.leak ? totalOf(sB) === 64 : true);
    check(fA.ok && fk2.phase !== 'UNLOCKED' && wt?.blocked === 'fill_blocked' && intact && fillCharged.length === 0,
      `B2 an F contaminated before the fill: the fill waits fill_blocked (FK ${fk2.phase}; ${json(wt)}), the contaminant all there (${json(contaminant)}: ${intact}), no filter item charged past the structure (${json(fillCharged)})`, { ...out.blocked, before: sB });
    // the player takes their dirt back: the fill runs once
    const t0 = await mod({ op: 'clearContainers', box: [...h.sort[0].F, ...h.sort[0].F] });
    let taken = t0.taken ?? {};
    if (out.leak) {
      for (const c of h.sort) for (const at of [c.line, c.D]) taken = plus(taken, (await mod({ op: 'clearContainers', box: [...at, ...at] })).taken);
      for (const c of h.sort) taken = plus(taken, (await mod({ op: 'clearContainers', box: [...c.chest, ...c.chest] })).taken);
      taken = plus(taken, (await mod({ op: 'clearContainers', box: [...h.overflow, ...h.overflow] })).taken);
      taken = plus(taken, (await mod({ op: 'clearContainers', box: [...h.input, ...h.input] })).taken);
    }
    const sA = sumAll(await sortState(h));
    const backC = Object.fromEntries(Object.keys(contaminant).map((k) => [k, taken[k] ?? 0]));
    // (fix check 5) after the leak the hall holds none of the cobblestone (no slot sorts it); the 1 dirt was in F 0 alone, and the fill that resumes
    // may legitimately write 41 dirt into dirt's own F, so only F 0 is judged then
    const left = out.leak ? (sA['minecraft:cobblestone'] ?? 0) : ((await sortState(h)).F[0]['minecraft:dirt'] ?? 0);
    check(same(backC, contaminant) && left === 0, `B2 the contaminant taken back whole (${json(backC)}), none left (${out.leak ? 'in the hall' : 'in F 0'})`, { taken, after: sA });
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
    // (CHECK-079) Architect 0.79.0: every give-back enters the stock, so refundOverflow never grows over a refund
    out.undo = { removed: u.removed ?? null, error: u.error ?? u.refused ?? null, back, want, drops, slabs0, slabs1, fk: { custody: fk4.custody ?? null, heldProvided: fk4.heldProvided ?? null }, overflowGrew: overflowGrew(before.ledger, after.ledger) };
    check(u.removed === true && same(back, want) && !out.undo.overflowGrew, `8 refund: removed ${u.removed}; credited (with overflow) = consumed less the 8 stone and 41 redstone taken and the mined chest (${same(back, want) ? 'equal' : `off by ${json(minus(back, want))}`}): the structure's own stone and redstone still refunded${out.undo.overflowGrew ? `; ${out.undo.overflowGrew}` : '; none to overflow'}`, out.undo);
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
/**
 * The saved ledger once it passes {@code ok}, through the save barrier (SAVEB: `dev.stockpile.save` on Architect 0.75+, else its periodic save turned
 * on and the file read every 2 s for at most 3 minutes); the last read if never. The periodic save stays on: the callers turn it off before the halt.
 */
async function savedAs(ok) {
  return savedOnDisk(dev, savedLedger, ok);
}
async function haltWait(point, from) {
  for (let i = 0; i < 300 && !exited; i++) await sleep(1000);
  if (!exited) return false;
  dev = null;
  const f = path.join(ART, 'client.log');
  const tail = fs.existsSync(f) ? fs.readFileSync(f, 'utf8').slice(from) : '';
  // WorldJournal.kill says "kill point P reached", the in-save points (FK3, CS1) "Stockpiles: kill point P: ..."
  return new RegExp(`kill point ${point.replace('+', '\\+')}( reached|:)`).test(tail);
}
/** The client launched again into the same world after a halt (nothing saved since the halt's baseline). */
async function restartWorld(pos) {
  await sleep(4000);
  // the halted JVM's sidecar may hold its port a while after the halt: both ports free first (at most 60 s), else startClient refuses
  for (let i = 0; i < 60 && ((await listening(PORT - 1)) || (await listening(PORT))); i++) await sleep(1000);
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
    // filter items the structure never uses, so the town's blocks can be fed with the fill's items held back (as Architect's FK gate does)
    const items = ['flint', 'bone', 'string', 'gunpowder'];
    const FILL = Object.fromEntries(items.map((k) => [`minecraft:${k}`, 41]));
    const only = (m) => Object.fromEntries(Object.entries(m ?? {}).filter(([k]) => k in FILL));
    const grown = [];
    // ---- FK1-FK4: the fill halted at each point; after the restart, it runs once: charged once against the saved state
    for (const point of ['FK1', 'FK2', 'FK3', 'FK4']) {
      const sp = { id: `sk_${point.toLowerCase()}`, slots: 4, bulk: 0, bible: 'rustic', items };
      const g = await growHalls(region, [sp], { survival: true });
      if (!check(!g.refused, `${point}: the hall grown: ${g.refused ? String(g.refused).slice(0, 200) : `rid ${g.rid}`}`)) continue;
      const give = async (fill) => {
        const ps = await pile();
        const need = {};
        for (const [it, n] of Object.entries(ps.outstanding ?? {})) { if ((it in FILL) !== fill) continue; const lack = n - (ps.stock?.[it] ?? 0); if (lack > 0) need[it] = lack; }
        if (!Object.keys(need).length) return {};
        return (await req('dev.crate.insert', { site: `stockpile:${OWNER}`, items: need, exact: true }, 120_000)).accepted ?? {};
      };
      // the lot waits for its road first: the town's blocks fed (never the fill's items) until the region names the hall's site
      let site = null;
      for (let i = 0; i < 360 && !site; i++) {
        await give(false);
        site = (await call('dev.region.state', { region }, 60_000)).view.lots.find((l) => l.id === sp.id)?.siteId ?? null;
        if (!site) await sleep(2500);
      }
      if (!check(!!site, `${point}: the hall's site named while it builds (${site})`)) continue;
      // (as Architect's FK gate) the town's blocks fed, the fill's items held back, until every cell of the hall stands; then the site paused,
      // the fill's items delivered (a paused target still takes them), the baseline saved, the point armed, then resumed: its next tick fills
      let t = null;
      for (let i = 0; i < 400; i++) {
        await give(false);
        const ps = await pile();
        t = ps.targets?.[site];
        const others = Object.entries(ps.targets ?? {}).filter(([k]) => k !== site);
        if (t && t.queued > 0 && t.built === t.queued && others.every(([, x]) => (x.built ?? 0) >= (x.queued ?? 0))) break;
        await sleep(2500);
      }
      if (!check(t && t.built === t.queued, `${point}: the town's blocks fed, the fill's items held back: every cell of the hall stands (${t?.built}/${t?.queued})`)) continue;
      const paused = await call('dev.stockpile.pause', { owner: OWNER, site, paused: true }, 60_000).then((r) => (r?.refused ? String(r.refused) : true), (e) => String(e));
      const f0 = await fkState(site);
      if (!check(paused === true && !f0.error && f0.phase !== 'FILLING' && f0.phase !== 'FILLED' && f0.phase !== 'UNLOCKED', `${point}: the site paused before its fill (${paused === true ? 'paused' : paused}; FK ${f0.error ?? f0.phase ?? 'not begun'})`)) continue;
      const acc = await give(true);
      check(same(only(acc), FILL), `${point}: the fill's items delivered while it is paused (${json(acc)})`);
      // the baseline on disk before the point is armed. /save-all is a dedicated-server command: on this integrated server it is "Unknown"
      // (save-probe, 2026-11-16), so the ledger reaches disk by Architect's periodic save: waited for until the saved ledger holds the fill's
      // items the live one does, then the periodic save turned off, so nothing is saved between the baseline and the halt
      const live = only((await pile()).ledger?.delivered);
      const B = await savedAs((L) => same(only(L?.delivered), live));
      if (!check(same(only(B?.delivered), live), `${point}: the baseline saved with the fill's items in the stockpile (${json(only(B?.delivered))})`)) continue;
      await call('dev.stockpile.ps', { noPeriodic: true }, 30_000).catch(() => null);
      const from = killFrom();
      await call('dev.journal.killAt', { point }, 10_000);
      call('dev.stockpile.pause', { owner: OWNER, site, paused: false }, 60_000).catch(() => null);
      const down = await haltWait(point, from);
      check(down, `${point}: the kill point halted the server${!down && !dev ? ' (FAIL: the client exited, but not at this kill point: unattributed; relaunched for the cases after it)' : ''}`);
      if (!down) { if (!dev) await restartWorld(home); else await call('dev.journal.killAt', { point: null }, 10_000).catch(() => null); continue; }
      const S = savedLedger();
      const marker = (S?.filled ?? []).some((k) => k.startsWith(`site:${site}@`));
      await restartWorld(home);
      // the baseline was saved with the site paused, and the halt lost the resume: resumed again
      await call('dev.stockpile.pause', { owner: OWNER, site, paused: false }, 60_000).catch(() => null);
      // (GPT review 1) after the restart only the fill's items are fed, never the structure's: a restart that re-owed or re-charged a block shows
      let f = null, refed = {};
      for (let i = 0; i < 360; i++) {
        f = await fkState(site);
        if (f.phase === 'UNLOCKED') break;
        refed = plus(refed, await give(true));
        await sleep(2500);
      }
      const L2 = (await pile()).ledger;
      const charged = only(minus(L2.consumed, S.consumed));
      // every item consumed since the pre-crash baseline, structure included: the fill exactly once, nothing else
      const sinceB = minus(L2.consumed, B.consumed);
      const owedAfter = Object.fromEntries(Object.entries((await pile()).outstanding ?? {}).filter(([k, n]) => !(k in FILL) && n > 0));
      // (fix check 1) the fill's items in = out: the saved stock + what was fed again = the stock now + what was charged since the saved state
      const stockS = only(minus(plus(S.delivered, S.returned), plus(S.consumed, plus(S.withdrawn, S.dropped))));
      const stockNow = only((await pile()).stock);
      check(same(plus(stockS, refed), plus(stockNow, only(minus(L2.consumed, S.consumed)))), `9 ${point}: the fill's items in = out: saved stock ${json(stockS)} + fed again ${json(refed)} = stock now ${json(stockNow)} + charged`, { stockS, refed, stockNow });
      check(same(sinceB, FILL) && !Object.keys(owedAfter).length, `9 ${point}: consumed since the pre-crash baseline, every item: the fill once (${json(minus(sinceB, FILL))} off; re-fed ${json(refed)}; structure owed after the restart ${json(owedAfter)})`, { sinceB, refed, owedAfter });
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
    // (T4S-PRE) Architect T4c's removal transactions (feature removal_finished): the same aliases (change 1), custody in the txn entry with the dev view
    // keeping its names (change 2), the re-undo answered replayed while the result cache holds it (change 4). Without the feature: as before
    const t4g = t4Gate(await mod({ op: 'env' }).catch(() => null));
    const t4 = t4g.run;
    check(true, `9 T4 rows: ${t4 ? 'on (removal_finished)' : `as before (${t4g.why})`}`);
    for (const c of cases) {
      const L0 = (await pile()).ledger;
      await cmd('/kill @e[type=item]');
      // the baseline on disk before the point is armed (Architect's periodic save; /save-all is dedicated-only): the saved ledger read until it is the live one
      const liveL = (await pile()).ledger;
      const books = (l) => json(['delivered', 'consumed', 'returned', 'withdrawn', 'dropped'].map((k) => Object.entries(l?.[k] ?? {}).sort()));
      const B = await savedAs((L) => books(L) === books(liveL));
      if (!check(books(B) === books(liveL), `${c.point}: the baseline saved before the removal`)) continue;
      await call('dev.stockpile.ps', { noPeriodic: true }, 30_000).catch(() => null);
      // what this growth consumed: its quote (consumed = quote, bar 8)
      const consumed = c.g.quote?.bom ?? {};
      const from = killFrom();
      await call('dev.journal.killAt', { point: c.point }, 10_000);
      call('dev.rv.undo', { region, toPosition: c.g.position - 1 }, 600_000).catch(() => null);
      const down = await haltWait(c.point, from);
      check(down, `${c.point}: the kill point halted the server at removal${!down && !dev ? ' (FAIL: the client exited, but not at this kill point: unattributed; relaunched for the cases after it)' : ''}`);
      if (!down) { if (!dev) await restartWorld(home); else await call('dev.journal.killAt', { point: null }, 10_000).catch(() => null); continue; }
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
      // (CHECK-079) Architect 0.79.0: every give-back enters the stock, so refundOverflow never grows over a refund
      const res = { point: c.point, savedCustody, state: rs, back: totalOf(back), consumed: totalOf(consumed), diff: minus(back, consumed), custody: F.custody ?? null, heldProvided: F.heldProvided ?? null, drops, slabs, overflowGrew: overflowGrew(B, F) };
      out.removal.push(res);
      const atHalt = custodyAtHalt(S, t4, totalOf);
      if (c.point === 'FKC1') check(!atHalt.check || atHalt.ok, `9 ${c.point}: the custody (${json(savedCustody)}) was on disk at the halt${atHalt.check ? '' : ` (${atHalt.note})`}`, res);
      res.custodyLeft = custodyClear(F).left;
      check(rs === 'UNDONE' && same(back, consumed) && !res.overflowGrew && custodyClear(F).ok && !Object.keys(drops).length && (slabs.player ?? 0) === 0 && (slabs.entities ?? 0) === 0,
        `9 ${c.point}: after the restart the removal ends once (${rs}): back = consumed item for item (${totalOf(back)} = ${totalOf(consumed)}; off ${json(res.diff)}); no custody, provided custody or pending refund left; nothing dropped; no slab loose${res.overflowGrew ? `; ${res.overflowGrew}` : '; none to overflow'}`, res);
      // (MEASURE-M4 12.7 B3, GPT review 2) the unchanged second retry, then a second restart: nothing more comes back, nothing is left pending
      const retry = await call('dev.rv.undo', { region, toPosition: c.g.position - 1 }, 600_000).catch((e) => ({ error: String(e) }));
      await sleep(4000);
      await reopen(home);
      await sleep(4000);
      const F2 = (await pile()).ledger;
      const again = minus(plus(F2.returned, F2.refundOverflow), plus(F.returned, F.refundOverflow));
      const drops2 = (await mod({ op: 'takeItems', box: [x0 - 300, gy - 4, z0 - 300, x0 + 600, gy + 60, z0 + 600] })).items;
      res.retry = { answer: refusal(retry), again, drops: drops2, overflowGrew: overflowGrew(F, F2) };
      // (fix check 2) the retry ran and was the expected no-op: Architect's own refusal (NOT_ALLOWED: the head is already below it; a refusal comes
      // back as the call's error), removing nothing; a transport error (a timeout, a closed bridge) carries no NOT_ALLOWED and fails
      // (T4S-PRE, T4 change 4) on a txn removal the unchanged re-ask may instead be answered with the original result, replayed (nothing more back)
      const retried = reUndoAnswer(retry, t4);
      res.retry.how = retried.how;
      check(retried.ok && !Object.keys(again).length && !res.retry.overflowGrew && !Object.keys(drops2).length && custodyClear(F2).ok && json(F2.consumed) === json(F.consumed),
        `9 ${c.point}: an unchanged second undo (${retried.how}) and a second restart: nothing more back (${json(again)}), nothing dropped, nothing pending (${refusal(retry)})${res.retry.overflowGrew ? `; ${res.retry.overflowGrew}` : ''}`, res.retry);
    }
    // ---- FKC0 (Architect's LC, S-M5-13): a removal failing after its custody take, a restart, a retry. Runs once the hook exists.
    const rest = [...grown].reverse().slice(removalPoints.length);
    const hook = await call(FKC0_HOOK, { probe: true }, 10_000).then(() => true).catch(() => false);
    // (T4S-PRE, T4 change 3) T4c ships the hook: on an Architect with removal_finished its absence is a failure, not a deferral
    if (!hook && t4) {
      out.fkc0 = `the hook ${FKC0_HOOK} is missing on an Architect with removal_finished (T4 change 3 ships it in T4c)`;
      check(false, `9 FKC0: ${out.fkc0}`);
    } else if (!hook) {
      // Noah, 2026-11-15 ("Certify now, name the gap"): @2 certifies before Architect's LC; FKC0 runs at the pin that carries LC3 (LC was reverted at v0.73.0; LC2 became LC3, the seal design)
      out.fkc0 = 'deferred: the hook dev.removal.failAfterCustody is not in this Architect (S-M5-13, slice LC); the offer names the gap';
      check(true, `9 FKC0: ${out.fkc0}`);
    } else if (rest.length) {
      const c = rest[0];
      const L0 = (await pile()).ledger;
      await call('dev.removal.failAfterCustody', { site: c.site }, 10_000);
      const u = await call('dev.rv.undo', { region, toPosition: c.g.position - 1 }, 600_000).catch((e) => ({ error: String(e) }));
      await sleep(4000);
      const s1 = await sortState(c.g.halls[0]).catch(() => null);
      // (T4S-PRE, T4 change 3) a txn removal aborted after its custody take is compensated before it retires: nothing left in the custody view
      if (t4) {
        const LF = (await pile()).ledger;
        check(u.removed !== true && custodyClear(LF).ok, `9 FKC0 (T4): the aborted removal took nothing down (${refusal(u)}) and left no custody (${json(custodyClear(LF).left)})`);
      }
      // reopen's dev.world.leave saves on stop (SAVEB: /save-all is dedicated-only, and cmd now throws on it)
      await reopen(home);
      const u2 = await call('dev.rv.undo', { region, toPosition: c.g.position - 1 }, 600_000).catch((e) => ({ error: String(e) }));
      await sleep(6000);
      const F = (await pile()).ledger;
      const back = minus(plus(F.returned, F.refundOverflow), plus(L0.returned, L0.refundOverflow));
      // (CHECK-079) Architect 0.79.0: every give-back enters the stock, so refundOverflow never grows over a refund
      const res = { first: refusal(u), afterFail: s1 ? totalOf(sumAll(s1)) : null, retry: refusal(u2), back: totalOf(back), consumed: totalOf(c.g.quote?.bom ?? {}), overflowGrew: overflowGrew(L0, F) };
      out.fkc0 = res;
      check(u2.removed === true && same(back, c.g.quote?.bom ?? {}) && !res.overflowGrew, `9 FKC0: the removal failed after its custody take (${refusal(u)}), its contents written back (${res.afterFail} items), restarted and retried: removed, back = consumed once (${res.back} = ${res.consumed})${res.overflowGrew ? `; ${res.overflowGrew}` : ''}`, res);
    }
    // ---- (bar 9, 5th run) a cancel before the fill: the hall's blocks built, the fill's items held back, paused, then undone. No filter item
    // written, charged or refunded; the structure back exactly; no slab anywhere
    {
      const sp = { id: 'sk_cancel', slots: 4, bulk: 0, bible: 'rustic', items };
      const feedOnly = async (fill) => {
        const ps = await pile();
        const need = {};
        for (const [it, n] of Object.entries(ps.outstanding ?? {})) { if ((it in FILL) !== fill) continue; const lack = n - (ps.stock?.[it] ?? 0); if (lack > 0) need[it] = lack; }
        if (Object.keys(need).length) await req('dev.crate.insert', { site: `stockpile:${OWNER}`, items: need, exact: true }, 120_000);
      };
      // the earlier cases' refunds put the fill's items back in stock: taken out first (to the player, then cleared), so nothing can fill
      const inStock = only((await pile()).stock);
      if (Object.keys(inStock).length) await call('dev.stockpile.withdraw', { owner: OWNER, items: inStock }, 60_000).catch(() => null);
      await cmd('/clear @p');
      check(!Object.keys(only((await pile()).stock)).length, `9 cancel: the fill's items out of the stock first (${json(inStock)} withdrawn)`);
      const Ls = (await pile()).ledger;
      const g = await growHalls(region, [sp], { survival: true });
      if (check(!g.refused, `9 cancel: the hall grown: ${g.refused ? String(g.refused).slice(0, 200) : `rid ${g.rid}`}`)) {
        let site = null, t = null;
        for (let i = 0; i < 400; i++) {
          await feedOnly(false);
          site = site ?? (await call('dev.region.state', { region }, 60_000)).view.lots.find((l) => l.id === sp.id)?.siteId ?? null;
          t = site ? (await pile()).targets?.[site] : null;
          if (t && t.queued > 0 && t.built === t.queued) break;
          await sleep(2500);
        }
        const L0 = (await pile()).ledger;
        const f0 = site ? await fkState(site) : { error: 'no site' };
        // (fix check 5) real work stood: queued cells, all built, and the four F hoppers there and empty before the undo
        await hallCells(region, g.halls);
        const fs0 = (await sortState(g.halls[0])).F;
        const fEmpty = fs0.length === 4 && fs0.every((m) => !totalOf(m));
        check(t && t.queued > 0 && t.built === t.queued && fEmpty && !f0.error && f0.phase !== 'FILLING' && f0.phase !== 'FILLED' && f0.phase !== 'UNLOCKED', `9 cancel: every cell of the hall stands (${t?.built}/${t?.queued}), its four F empty (${fEmpty}), its fill not begun (FK ${f0.error ?? f0.phase ?? 'not begun'})`);
        const u = await call('dev.rv.undo', { region, toPosition: g.position - 1 }, 600_000).catch((e) => ({ error: String(e) }));
        let rs = null;
        for (let i = 0; i < 300; i++) { rs = (await call('dev.rv.state', { region }, 60_000)).revisions.find((x) => x.rid === g.rid)?.state; if (rs === 'UNDONE') break; await sleep(1000); }
        await sleep(4000);
        const L1 = (await pile()).ledger;
        // what the growth charged (its structure, never the fill) and what came back at the undo
        const consumed = minus(L1.consumed, Ls.consumed);
        const back = minus(plus(L1.returned, L1.refundOverflow), plus(L0.returned, L0.refundOverflow));
        const drops = (await mod({ op: 'takeItems', box: [x0 - 300, gy - 4, z0 - 300, x0 + 600, gy + 60, z0 + 600] })).items;
        const slabs = await call('dev.fk.slabs', { pos: home }, 10_000).catch(() => ({}));
        const struct = minus(L0.consumed, Ls.consumed);
        // (CHECK-079) Architect 0.79.0: every give-back enters the stock, so refundOverflow never grows over a refund
        const res = { state: rs, undo: refusal(u), fillCharged: only(consumed), fillBack: only(back), back, struct, drops, slabs, custody: L1.custody ?? null, pending: L1.pendingCredit ?? null, overflowGrew: overflowGrew(L0, L1) };
        out.cancel = res;
        check(rs === 'UNDONE' && totalOf(struct) > 0 && !res.overflowGrew && !Object.keys(only(consumed)).length && !Object.keys(only(back)).length && same(back, struct) && !Object.keys(drops).length
          && !Object.keys(L1.custody ?? {}).length && !Object.keys(L1.pendingCredit ?? {}).length && (slabs.player ?? 0) === 0 && (slabs.entities ?? 0) === 0,
          `9 cancel before the fill: ${rs}; no filter item charged (${json(only(consumed))}) or refunded (${json(only(back))}); the structure back exactly (${totalOf(back)} = ${totalOf(struct)}; off ${json(minus(back, struct))}); nothing dropped, no custody or pending refund, no slab${res.overflowGrew ? `; ${res.overflowGrew}` : ''}`, res);
      }
    }
  } finally {
    // (a relaunch that failed leaves no client: its own error is the step's, not a null one here)
    if (dev) await call('dev.journal.killAt', { point: null }, 10_000).catch(() => null);
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}


// ------------------------------------------------------------------ save-probe (M4b, the manager's (a)): does /save-all put a delivery on disk?
async function saveProbe() {
  const out = { cases: [] };
  try {
    const T = await survivalWorld('SaveProbe', 90000, 90000);
    const { region } = T;
    const logAt = () => { const f = path.join(ART, 'client.log'); return fs.existsSync(f) ? fs.statSync(f).size : 0; };
    const logSince = (from) => { const f = path.join(ART, 'client.log'); return fs.existsSync(f) ? fs.readFileSync(f, 'utf8').slice(from) : ''; };
    const probe = async (label, item) => {
      const from = logAt();
      const acc = (await req('dev.crate.insert', { site: `stockpile:${OWNER}`, items: { [item]: 5 }, exact: true }, 120_000)).accepted ?? {};
      const live = (await pile()).ledger?.delivered?.[item] ?? 0;
      const answer = await req('dev.command', { cmd: '/save-all flush' }, 120_000);
      let saved = 0;
      for (let i = 0; i < 15 && saved !== live; i++) { await sleep(1000); saved = savedLedger()?.delivered?.[item] ?? 0; }
      const lines = logSince(from).split('\n').filter((l) => /SS save|Stockpiles: saved|Saved the game|Saving the game|world save/.test(l)).map((l) => l.slice(0, 200));
      const r = { label, item, accepted: acc, live, saved, answer, lines };
      out.cases.push(r);
      log(`  ${label}: ${json(r).slice(0, 1200)}`);
      return r;
    };
    await probe('no target paused', 'minecraft:flint');
    // a hall grown, its blocks fed (flint, bone, string, gunpowder held back) until its cells stand, then paused, as sort-crash does
    const items = ['flint', 'bone', 'string', 'gunpowder'];
    const FILL = Object.fromEntries(items.map((k) => [`minecraft:${k}`, 41]));
    const sp = { id: 'sp_probe', slots: 4, bulk: 0, bible: 'rustic', items };
    const g = await growHalls(region, [sp], { survival: true });
    const give = async () => {
      const ps = await pile();
      const need = {};
      for (const [it, n] of Object.entries(ps.outstanding ?? {})) { if (it in FILL) continue; const lack = n - (ps.stock?.[it] ?? 0); if (lack > 0) need[it] = lack; }
      if (Object.keys(need).length) await req('dev.crate.insert', { site: `stockpile:${OWNER}`, items: need, exact: true }, 120_000);
    };
    let site = null, t = null;
    for (let i = 0; i < 400; i++) {
      await give();
      site = site ?? (await call('dev.region.state', { region }, 60_000)).view.lots.find((l) => l.id === sp.id)?.siteId ?? null;
      const ps = await pile();
      t = site ? ps.targets?.[site] : null;
      if (t && t.queued > 0 && t.built === t.queued) break;
      await sleep(2500);
    }
    log(`  the hall ${g.refused ? `refused: ${g.refused}` : `grown, site ${site}, ${t?.built}/${t?.queued} cells`}`);
    const pr = await call('dev.stockpile.pause', { owner: OWNER, site, paused: true }, 60_000).catch((e) => ({ error: String(e) }));
    log(`  paused: ${json(pr).slice(0, 300)}`);
    await probe('its site paused', 'minecraft:bone');
    await call('dev.stockpile.ps', { noPeriodic: true }, 30_000).catch(() => null);
    await probe('its site paused, noPeriodic on', 'minecraft:string');
    await call('dev.stockpile.ps', { noPeriodic: false }, 30_000).catch(() => null);
    await call('dev.stockpile.pause', { owner: OWNER, site, paused: false }, 60_000).catch(() => null);
    await probe('resumed', 'minecraft:gunpowder');
    for (const c of out.cases) check(c.saved === c.live, `save-probe ${c.label}: delivered ${c.live} live, ${c.saved} on disk after /save-all flush (${c.lines.length} save lines)`);
  } finally {
    await stopClient().catch(() => null);
  }
  return out;
}


// ------------------------------------------------------------------ sort-plain (storage_hall@2): 12.1's non-filter coverage, completed
/**
 * (M4b, the manager's (a)) The domain items the sort step's evidence names as never a non-filter kind (artifacts/m4b/sort.json
 * coverage.plainMissing), each through a rustic hall as a non-filter kind: one 4-slot hall, its five plain kinds those items first, 3 runs, each
 * 100%. {covered: the items its 100% runs carried as non-filter kinds}.
 */
async function sortPlain() {
  if (MODULE.version < 2) throw new Error('sort-plain is storage_hall@2\'s step: --version 2');
  const prev = fs.existsSync(path.join(ART, 'sort.json')) ? JSON.parse(fs.readFileSync(path.join(ART, 'sort.json'), 'utf8')) : null;
  const missing = prev?.data?.coverage?.plainMissing ?? [];
  const out = { missing, covered: [], runs: [] };
  await startClient('SortPlain');
  try {
    await rules();
    await install();
    const domain = MODULE_JSON.params.item1.options.filter((o) => o !== 'none');
    check(missing.length > 0 && missing.length <= 5, `sort-plain: the sort step's evidence names ${missing.length} domain item(s) never a non-filter kind (${missing.join(', ')})`);
    const items = domain.filter((k) => !missing.includes(k)).slice(0, 4);
    const plain = [...missing, ...domain.filter((k) => !missing.includes(k) && !items.includes(k))].slice(0, 5).map((k) => `minecraft:${k}`);
    const t = await town(1280, 0, { orientation: 'ew' });
    await standAt(t.cx, t.cz, t.gy + 30);
    const sp = { id: 's4_plain', slots: 4, bulk: 0, bible: 'rustic', items, plain };
    const g = await growHalls(t.region, [sp]);
    if (!check(!g.refused, `sort-plain: the hall grown: ${g.refused ? String(g.refused).slice(0, 200) : `rid ${g.rid}`}`)) return out;
    const w = await rvWait(t.region, g.rid);
    check(w.r.state === 'PLACED', `sort-plain: growth rid ${g.rid} ${w.r.state}`);
    await hallCells(t.region, g.halls);
    const h = g.halls[0];
    h.plain = plain;
    await freeze();
    const s0 = await sortState(h);
    check(h.filterIds.every((f, u) => fExact(s0.F[u], f)), `sort-plain: every F exactly 41 + 4 before the runs`);
    let all = true;
    for (let r = 0; r < 3; r++) {
      const res = await sortRun([h], domain);
      out.runs.push(res[h.id]);
      all = all && res[h.id].sorted;
      check(res[h.id].sorted, `12.1/12.5 bar 1 sort-plain run ${r + 1}: ${missing.join(', ')} as non-filter kinds, 100% (${res[h.id].why.slice(0, 2).join('; ')})`, res[h.id]);
      await clearOutputs(h);
    }
    const s1 = await sortState(h);
    check(h.filterIds.every((f, u) => fExact(s1.F[u], f)), `sort-plain: every F exactly 41 + 4 after the runs`);
    out.covered = all ? plain.map((k) => k.replace(/^minecraft:/, '')) : [];
    check(missing.every((k) => out.covered.includes(k)), `12.1 coverage completed: ${missing.join(', ')} sorted as a non-filter kind in 3 runs of 100%`);
    await cmd('/tick unfreeze');
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

const steps = { hall, survival, bounds, sort: sortStep, 'sort-survival': sortSurvival, 'sort-cargo': sortCargo, 'sort-crash': sortCrash, 'save-probe': saveProbe, 'sort-plain': sortPlain };
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
