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
import { checkCommand } from './lib/save-barrier.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAIN_CHECKOUT = path.resolve(execFileSync('git', ['-C', ROOT, 'rev-parse', '--git-common-dir']).toString().trim(), '..');
const ARCH = process.env.ARCHITECT_CHECKOUT ?? path.resolve(MAIN_CHECKOUT, '..', 'architect-mc');
const GAME = path.join(ROOT, 'mod', 'run');
process.env.ARCHITECT_GAME_DIR = GAME;
const PORT = Number(process.env.STEWARD_DEV_PORT ?? 8599);
const ART = path.join(ROOT, 'artifacts', 'm4');
fs.mkdirSync(ART, { recursive: true });

const argv = process.argv.slice(2);
const step = argv[0];
const flag = (f) => argv.includes(f);
const opt = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
const CATALOG = path.resolve(ROOT, opt('--catalog', 'modules'));
const MODULE = { id: 'storage_hall', version: 1 };
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
  worldName = `Steward M4 ${label} ${new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')}`;
  // one slot from Architect's machine-wide queue (slice SQ, tools/lib/slot-queue.mjs), held by this driver until the client quits
  slot = await acquireSlot(`module-measure-hall ${label}`, { root: ROOT });
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
async function growHalls(region, specs, { survival = false, realise = true } = {}) {
  const P = await program();
  const lots = [];
  for (const s of specs) {
    const lot = (await mod({ op: 'hallLot', id: s.id, slots: 0, bulk: s.bulk })).lot;
    const L = P.layout(0, s.bulk);
    check(lot.size[0] === L.W && lot.size[1] === L.D && lot.type === 'storage_hall', `${s.id}: StorageHallLot's lot = the program's frame (${lot.size.join('x')})`);
    // the lot ext Steward's placement adds (SettlementRunner, ModuleRef): the module's identity on the placed site, which the steward's words read
    lot.ext = { ...(lot.ext ?? {}), 'steward_mc:module': { id: MODULE.id, version: MODULE.version, programSha: stamp().programSha, params: { bulk: s.bulk, slots: 0 } } };
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
    const v = await mod({ op: 'variant', dir: CATALOG, id: MODULE.id, version: MODULE.version, measuring: true, params: { slots: 0, bulk: s.bulk },
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
    const L = P.layout(0, h.bulk);
    const F = h.frame;
    const w = (c) => F.toWorld(c[0], c[1] - 1, c[2]); // template row r = frame y r - 1
    h.L = L;
    h.input = w(L.input);
    h.pairs = L.bulk.map((b) => [w(b.lo), w(b.hi)]);
    // the inside's air cells at row 2 (z 1..D-3, x 1..W-2) and the front row's cells at row 2
    h.light = [];
    for (let z = 1; z <= L.D - 3; z++) for (let x = 1; x <= L.W - 2; x++) h.light.push(w([x, 2, z]));
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
  // dev.world.leave saves on stop (/save-all is dedicated-only: Unknown on this integrated server)
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
    if (until(st, rs)) return { waves, st, rs, ok: true };
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
  const out = { bounds: [], quotes: [] };
  try {
    const T = await survivalWorld('Bounds', 60000, 60000);
    for (const bulk of BULKS) {
      const styles = {};
      for (const bible of MODULE_JSON.envelope.bibles) {
        const id = `bd${bulk}_${bible.slice(0, 3)}`;
        const g = await growHalls(T.region, [{ id, bulk, bible }], { survival: true, realise: false });
        const q = g.quote ?? {};
        const ok = !g.refused && q.ok && !q.refused && q.survival === true && q.bom && Object.keys(q.bom).length > 0;
        check(ok, `bounds bulk ${bulk} ${bible}: quoted (survival ${q.survival}, exact ${q.exact}, ${Object.values(q.bom ?? {}).reduce((a, b) => a + b, 0)} items)${g.refused ? ` ${String(g.refused).slice(0, 200)}` : ''}`);
        if (ok) { styles[bible] = q.bom; out.quotes.push({ params: { bulk, slots: 0 }, bible, bom: q.bom, bomParts: q.bomParts, exact: q.exact, notes: q.notes }); }
      }
      if (Object.keys(styles).length === MODULE_JSON.envelope.bibles.length) {
        const bom = {};
        for (const b of Object.values(styles)) for (const [k, n] of Object.entries(b)) bom[k] = Math.max(bom[k] ?? 0, n);
        out.bounds.push({ params: { bulk, slots: 0 }, bom: Object.fromEntries(Object.entries(bom).sort()) });
      }
    }
    check(out.bounds.length === BULKS.length, `bounds: every one of the ${BULKS.length} branches has a bound over its ${MODULE_JSON.envelope.bibles.length} Styles (${out.bounds.length})`);
  } finally {
    await stopClient().catch((e) => log(`stop: ${e}`));
  }
  return out;
}

const steps = { hall, survival, bounds };
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
