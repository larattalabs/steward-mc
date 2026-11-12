#!/usr/bin/env node
// Steward's end-to-end check, driven through Architect's DevBridge like Architect's gate scripts. It launches its own client (tools/run-e2e-client.sh:
// ports 8590/8591, the flat creative world "Steward E2E", no Claude), runs the steps, and prints one line per check: "ok <check>" or "FAIL <check>: why".
// Exit 0 = no FAIL and at least one ok. A summary goes to artifacts/e2e/summary.json.
//
//   node tools/e2e.mjs free     $0, no Claude: claim and steward, the save format, expand, the inbox screen, a placed building on the settlement screen and its panel,
//                               its update, revert and undo, proposals, a protected area and a district with its road (founded and undone), a build restored after
//                               a restart
//   node tools/e2e.mjs stub     the whole paid flow at $0 against Architect's helper in sim mode (v0.12.2, notional measured costs): describe through the card
//                               job, a 3-building build with a restart mid-build, a library asked for in talk and built in the reused bible, a district
//                               proposed in talk and founded, a building in it laying its street and link road, undo
//
// Never spends: the launcher refuses to start while the claude-login opt-in exists and strips credentials from the environment. Do not run it while a
// Steward dev client uses mod/run (they share the game dir); it refuses then.
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ARCH = process.env.ARCHITECT_CHECKOUT ?? path.resolve(ROOT, '..', 'architect-mc');
const GAME = path.join(ROOT, 'mod', 'run');
process.env.ARCHITECT_GAME_DIR = GAME;
const PORT = Number(process.env.STEWARD_DEV_PORT ?? 8591);
const WORLD = path.join(GAME, 'saves', process.env.ARCHITECT_AUTOWORLD_NAME ?? 'Steward E2E');
const OUT = path.join(ROOT, 'artifacts', 'e2e');
const LOG = path.join(GAME, 'logs', 'latest.log');
const mode = process.argv[2] ?? 'free';
// --only a,b: run only the flows whose name or tags hold one of the words, plus the sanity set (status, claim, expand, restart); an RC's run
const onlyAt = process.argv.indexOf('--only');
const only = onlyAt > 0 ? (process.argv[onlyAt + 1] ?? '').toLowerCase().split(',').map((w) => w.trim()).filter(Boolean) : null;
if (!['free', 'stub'].includes(mode) || (only && !only.length)) {
  console.error('usage: node tools/e2e.mjs free|stub [--only word,word]');
  process.exit(2);
}
const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);
const { Blueprint, PALETTES } = await import(pathToFileURL(path.join(ARCH, 'kit', 'lib', 'kit.mjs')).href);
const { writeBlueprint } = await import(pathToFileURL(path.join(ARCH, 'kit', 'lib', 'write.mjs')).href);
const { decodeArwd } = await import(pathToFileURL(path.join(ARCH, 'kit', 'lib', 'region', 'vworld.mjs')).href);

fs.mkdirSync(OUT, { recursive: true });
const results = [];
const ok = (check, note = '') => { results.push({ check, ok: true, note }); console.log(`ok ${check}${note ? ' (' + note + ')' : ''}`); };
const fail = (check, why) => { results.push({ check, ok: false, why }); console.log(`FAIL ${check}: ${why}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------ the client

let client = null;
let dev = null;

function launch() {
  const log = fs.openSync(path.join(OUT, 'client.log'), 'a');
  client = spawn(path.join(ROOT, 'tools', 'run-e2e-client.sh'), [], { stdio: ['ignore', log, log], env: { ...process.env, E2E_STUB: mode === 'stub' ? '1' : '0', E2E_NODE_DIR: path.dirname(process.execPath) } });
}

async function connect() {
  dev = await DevClient.connect({ port: PORT, timeoutMs: 400_000 });
  await dev.waitInWorld({ timeoutMs: 400_000 });
  // a new client writes a new log: the herder reads it from its start
  herdAt = 0;
  startHerder();
}

/**
 * Occupied lots never stall a run (Noah, 2026-11-04): a building waits with no time limit for an animal or a player in its box, so one watcher for the
 * whole run reads the steward's "... is in its lot. Move it out" and "someone is standing on its lot" and clears the animals (and their drops) or moves
 * the dev player just past that town's claim, where the ground stays loaded. Every flow, every restart (the game's log starts afresh: read from its
 * start again). Architect's harnesses keep the same rule (slice OC).
 */
let herdAt = 0, herding = false, herder = null;
function startHerder() {
  if (herder) return;
  herder = setInterval(() => {
    if (herding || !dev) return;
    herding = true;
    herd().catch(() => {}).finally(() => { herding = false; });
  }, 2000);
  herder.unref();
}

async function herd() {
  const all = fs.existsSync(LOG) ? fs.readFileSync(LOG, 'utf8') : '';
  if (all.length < herdAt) herdAt = 0;
  const text = all.slice(herdAt);
  herdAt = all.length;
  // the steward's words, Architect's own wait (a box held by an animal the steward hasn't named) and a stopped removal
  if (/is in its lot\. Move it out|waits \(OCCUPIED\): Not placed: in the box|Removal stopped of [^\n]*Move these out/.test(text)) await clearAnimals();
  const standing = [...text.matchAll(/\[([^\]\n]+)\] The [^\n]*? waits: someone is standing on its lot/g)].map((m) => m[1]);
  if (!standing.length && !/someone is standing on its lot/.test(text)) return;
  const all_ = settlements();
  const named = standing.map((n) => all_.find((x) => (x.card?.name ?? x.name) === n)).filter(Boolean);
  for (const st of named.length ? named : all_.slice(-1)) {
    const c = st.claim;
    if (c) await cmd(`/tp @p ${c.centerX} ~ ${c.centerZ + c.radius + 12}`);
  }
}

async function quit() {
  if (!dev) return;
  const r = await dev.request('dev.quit');
  if (r.ok) await dev.waitClosed(120_000);
  dev = null;
  await sleep(4000);
}

const logSize = () => (fs.existsSync(LOG) ? fs.statSync(LOG).size : 0);
const logSince = (from) => (fs.existsSync(LOG) ? fs.readFileSync(LOG, 'utf8').slice(from) : '');

/** Closes whatever screen is open (a focused text box takes the first Escape). */
async function closeScreens() {
  for (let i = 0; i < 3; i++) {
    const st = await dev.request('dev.state');
    if (!st.screen) return;
    await dev.request('dev.key', { key: 'escape' });
    await sleep(200);
  }
}

async function cmd(c) {
  // an animal in what an undo takes up stops the removal (Architect asks for force), so every undo starts with the land cleared
  if (/^\/steward undo\b/.test(c)) await clearAnimals();
  const r = await dev.request('dev.command', { cmd: c });
  return (r.messages ?? []).join(' | ');
}

/** Waits until the log, from {@code from}, matches {@code re}; returns the match or null. */
async function waitLog(from, re, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const m = logSince(from).match(re);
    if (m) return m;
    await sleep(1000);
  }
  return null;
}

const settlements = () => {
  const f = path.join(WORLD, 'steward-settlements.json');
  return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')).settlements : [];
};

// ------------------------------------------------------------------ a building of our own (kit-built, no Claude)

// a new entry each run: the world keeps earlier runs' settlements, and their copies of one shared entry were offered the update first (set_28's, not
// loaded, on the WE-WS RC); an earlier run's entry never gets a new version, so the inbox's only update is this run's
const STUB = `e2e_stub_${Date.now().toString(36)}`;

function stubBlueprint(extraLantern, id = STUB) {
  const p = PALETTES.rustic;
  const bp = new Blueprint({ id, type: 'custom', size: [9, 6, 9], origin: [0, 0, 0], palette: p, interior: [1, 1, 1, 7, 4, 4] });
  bp.room([0, 0, 0, 8, 5, 5]);
  bp.door(4, 1, 5, 'south');
  bp.lantern(2, 1, 2);
  if (extraLantern) bp.lantern(6, 1, 2);
  bp.floor(3, 6, 5, 8, 0, p.path);
  bp.spot('entrance', 4, 6, 180);
  bp.name = 'E2E stub';
  return bp;
}

/**
 * A fresh entry every run (only this script uses it): the update check installs a version that must differ from the one placed. The fixed
 * `e2e_stub` is written too, for the dev commands that realise it (PlanChecks.realise, VSpike).
 */
function ensureStubEntry() {
  for (const id of [STUB, 'e2e_stub']) {
    const dir = path.join(GAME, 'architect', 'library', id);
    fs.rmSync(dir, { recursive: true, force: true });
    writeBlueprint(stubBlueprint(false, id), dir);
  }
}

// ------------------------------------------------------------------ steps

async function preflight() {
  if (fs.existsSync(path.join(GAME, 'architect', 'sidecar-data', 'secrets.json'))) throw new Error('the claude-login opt-in exists (mod/run/architect/sidecar-data/secrets.json): remove it');
  try {
    execFileSync('pgrep', ['-f', 'steward-mc/mod.*KnotClient']);
    throw new Error('a Steward client already runs on mod/run; quit it first');
  } catch (e) {
    if (e.message.startsWith('a Steward client')) throw e;
  }
  ok('preflight', 'no credentials, no other client');
}

let spikesCleared = false;

async function claimAndSteward() {
  // the world is kept between runs: a region a dev spike realised would put its lots in this run's inbox
  if (!spikesCleared) {
    spikesCleared = true;
    const said = await cmd('/steward dev v_spike all remove');
    if (!/removing 0 /.test(said)) await sleep(5000);
  }
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 0 0 1 false @p`);
  // the far chunks load after the teleport: claim (and look for the steward) once they are in
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  const m = await cmd('/steward claim');
  const after = settlements();
  if (after.length !== n + 1) return fail('claim', `no new settlement (${m})`), null;
  const s = after[after.length - 1];
  ok('claim', `${s.id}, ${2 * s.claim.radius + 1} across`);
  // the settlements file is written in the current format (a v1 world migrates when loaded; its v1 file is kept as a backup)
  const file = JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-settlements.json'), 'utf8'));
  const backups = fs.readdirSync(WORLD).filter((f) => /^steward-settlements\.json\.v\d+\.bak$/.test(f)).map((f) => f.match(/v\d+/)[0]);
  // format 4 (2026-10-24): districts with kinds and land; every square district a standing square
  const squares = file.settlements.flatMap((x) => x.lands?.districts ?? []).every((d) => d.kind && d.state);
  if (file.format === 6 && squares && file.settlements.every((x) => (x.log ?? []).every((e) => e.op))) ok('save format', `6, every entry an operation, every district with its kind${backups.length ? ', backups kept: ' + backups.join(' ') : ''}`);
  else fail('save format', `format ${file.format}`);
  let name = '';
  for (let i = 0; i < 10 && !/Steward/.test(name); i++) {
    if (i > 0) await sleep(1000);
    name = await cmd(`/data get entity @e[type=steward_mc:steward,tag=steward_mc.settlement.${s.id},limit=1] CustomName`);
  }
  // a look at it, from three blocks in front
  const pos = (await cmd(`/data get entity @e[type=steward_mc:steward,tag=steward_mc.settlement.${s.id},limit=1] Pos`)).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
  let shot = '';
  if (pos) {
    const [x, y, z] = pos.slice(1).map(Number);
    // the describe screen the claim opened closes first (the first Escape leaves its text box, the second closes it)
    await dev.request('dev.key', { key: 'escape' });
    await sleep(200);
    await dev.request('dev.key', { key: 'escape' });
    await cmd(`/tp @p ${(x + 2).toFixed(1)} ${y.toFixed(1)} ${(z + 4).toFixed(1)} facing ${x.toFixed(1)} ${(y + 1.8).toFixed(1)} ${z.toFixed(1)}`);
    // a line in its speech bubble, for the picture
    await cmd(`/steward dev say ${s.id} Welcome! Tell me what this place should be, and I will draw it up.`);
    await sleep(1500);
    shot = (await dev.request('dev.screenshot', { name: 'e2e-steward', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  }
  if (/Steward/.test(name)) ok('steward', `named, tagged${shot ? ', ' + shot : ''}`);
  else fail('steward', name || 'no steward_mc:steward');
  return s.id;
}

/** The Founding Stone can be crafted in survival (the release track): the game knows its recipe. */
async function stoneRecipe() {
  // taken first: the world keeps what the player learned in an earlier run
  await cmd('/recipe take @p steward_mc:founding_stone');
  const r = await cmd('/recipe give @p steward_mc:founding_stone');
  if (/Unlocked 1 recipe|Unlocked|1 recipe/i.test(r)) ok('stone recipe', r);
  else fail('stone recipe', r || 'no answer');
}

async function expand(id) {
  const a = await cmd(`/steward expand ${id}`);
  const b = await cmd(`/steward expand ${id}`);
  const r = settlements().find((s) => s.id === id)?.claim.radius;
  if (r === 128) ok('expand', '129 -> 193 -> 257');
  else fail('expand', `radius ${r} (${a} | ${b})`);
}

async function inboxScreen() {
  await cmd('/steward ui sample e2e');
  await sleep(1500);
  const st = await dev.request('dev.state');
  const screen = JSON.stringify(st.screen ?? st);
  if (/InboxScreen/.test(screen)) ok('inbox screen');
  else fail('inbox screen', screen.slice(0, 120));
  // the sample's rows (a build held by the usage limit, an ended build, the grouped waiting parts), then the toasts once no screen is open
  const inboxShot = (await dev.request('dev.screenshot', { name: 'e2e-inbox', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  // the arrows walk the list as drawn: Stilt Swamp, Lantern Shore, Copper Ford's waiting parts, Reed Hollow (held by the usage limit), Copper Ford built
  await dev.request('dev.key', { key: 'key.keyboard.down' });
  await dev.request('dev.key', { key: 'key.keyboard.down' });
  await sleep(300);
  const partsShot = (await dev.request('dev.screenshot', { name: 'e2e-inbox-parts', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  await dev.request('dev.key', { key: 'key.keyboard.down' });
  await sleep(300);
  const limitShot = (await dev.request('dev.screenshot', { name: 'e2e-inbox-limit', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  await dev.request('dev.key', { key: 'escape' });
  await sleep(1200);
  // toasts are HUD, which a screenshot hides unless asked
  const toastShot = (await dev.request('dev.screenshot', { name: 'e2e-toasts', frames: 5, hideHud: false }, { timeoutMs: 120_000 })).path ?? '';
  ok('inbox and toast pictures', `${inboxShot} ${partsShot} ${limitShot} ${toastShot}`);
}

async function placeUpdateUndo(id) {
  // the building stands 6 blocks south of the player, 9 x 9 from there (dev place, rotation none)
  const me = (await cmd('/data get entity @p Pos')).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
  const box = me ? { x0: Math.floor(Number(me[1])), z0: Math.floor(Number(me[3])) + 6 } : null;
  if (box) Object.assign(box, { x1: box.x0 + 8, z1: box.z0 + 8 });
  let from = logSize();
  // a farm animal in the box refuses the placement (a chicken did, on the WE-WS RC)
  await clearAnimals();
  await cmd(`/steward dev place ${id} ${STUB}`);
  const placed = await waitLog(from, /dev placed (s\d+)/, 30_000);
  if (!placed) return fail('dev place', (await waitLog(from, /dev place refused[^\n]*/, 1) ?? ['no answer'])[0]);
  const site = placed[1];
  ok('dev place', site);
  // the in-world interface (3d): looking at the building outlines and labels it; survey mode (U) labels all and shows the claim's border
  if (box) {
    const mine = (await cmd('/data get entity @p Pos')).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
    const gy = mine ? Math.floor(Number(mine[2])) : 0;
    await cmd(`/tp @p ${box.x0 + 4.5} ${gy} ${box.z1 + 10} facing ${box.x0 + 4.5} ${gy + 3} ${box.z0 + 4}`);
    await sleep(3500); // the settlement's buildings reach the client every two seconds
    const lookShot = (await dev.request('dev.screenshot', { name: 'e2e-look', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
    await dev.request('dev.key', { key: 'u' });
    await cmd(`/tp @p ${box.x0 + 4.5} ${gy + 6} ${box.z1 + 20} facing ${box.x0 + 4.5} ${gy + 4} ${box.z0}`);
    await sleep(1500);
    const surveyShot = (await dev.request('dev.screenshot', { name: 'e2e-survey', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
    await dev.request('dev.key', { key: 'u' });
    if (mine) await cmd(`/tp @p ${mine[1]} ${mine[2]} ${mine[3]}`);
    // a labelled massing ghost (the build's massings carry their lot's role and id)
    await cmd(`/tp @p ${mine ? mine[1] : 0} ${mine ? mine[2] : 0} ${mine ? mine[3] : 0} facing ${mine ? mine[1] : 0} ${gy + 4} ${mine ? Number(mine[3]) - 12 : 0}`);
    await cmd(`/steward dev ghost ${STUB} fisher's stilt house · cottage_1`);
    await sleep(2500);
    const ghostShot = (await dev.request('dev.screenshot', { name: 'e2e-massing-label', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
    await cmd(`/steward dev ghost ${STUB} -`);
    ok('in-world labels', `${lookShot}, ${surveyShot}, ${ghostShot}`);
  }
  // the settlement screen (3a), opened as a player would by command, with the building just placed
  await cmd(`/steward view ${id}`);
  await sleep(1500);
  const st = JSON.stringify(await dev.request('dev.state'));
  const shot = await dev.request('dev.screenshot', { name: 'e2e-settlement', frames: 5 }, { timeoutMs: 120_000 });
  if (/SettlementScreen/.test(st)) ok('settlement screen', shot.path ?? '');
  else fail('settlement screen', st.slice(0, 160));
  await cmd(`/steward view ${id} ${site}`);
  await sleep(1500);
  const bst = JSON.stringify(await dev.request('dev.state'));
  const bshot = await dev.request('dev.screenshot', { name: 'e2e-building', frames: 5 }, { timeoutMs: 120_000 });
  if (/BuildingScreen/.test(bst)) ok('building panel', bshot.path ?? '');
  else fail('building panel', bst.slice(0, 160));
  await dev.request('dev.key', { key: 'escape' });
  // a new version of the entry, built and checked by the kit, installed as the entry's next version
  const v2 = fs.mkdtempSync(path.join(os.tmpdir(), 'steward-e2e-v2-'));
  writeBlueprint(stubBlueprint(true), v2);
  const inst = await dev.request('dev.entry.installVersion', { entry: STUB, dir: v2, by: 'design', summary: 'e2e: a second lantern' });
  if (!inst.ok) return fail('update', `installVersion: ${JSON.stringify(inst).slice(0, 160)}`);
  const stewardPos = async () => {
    const r = await cmd(`/data get entity @e[type=steward_mc:steward,tag=steward_mc.settlement.${id},limit=1] Pos`);
    const m = r.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
    return m ? m.slice(1).map(Number) : null;
  };
  const before = await stewardPos();
  const checked = await cmd('/steward updates');
  // the nameplate: an update waits, so it shows the "!" (a screenshot from in front of it)
  // ...and back where the player stood: a player beside a building stops Architect rewriting it (the revert and undo below)
  const look = async (name) => {
    const p = await stewardPos();
    const mine = (await cmd('/data get entity @p Pos')).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
    if (!p) return '';
    await cmd(`/tp @p ${(p[0] + 2).toFixed(1)} ${p[1].toFixed(1)} ${(p[2] + 4).toFixed(1)} facing ${p[0].toFixed(1)} ${(p[1] + 1.8).toFixed(1)} ${p[2].toFixed(1)}`);
    await sleep(1500);
    const shot = (await dev.request('dev.screenshot', { name, frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
    if (mine) await cmd(`/tp @p ${mine[1]} ${mine[2]} ${mine[3]}`);
    return shot;
  };
  await sleep(2500);
  const plateShot = await look('e2e-steward-plate');
  // the board while the update waits: it says what the plate's "!" says (J2: one attention for the inbox, the plate and the board)
  {
    const mine = (await cmd('/data get entity @p Pos')).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
    if (mine) {
      const [px, py, pz] = mine.slice(1).map((n) => Math.floor(Number(n)));
      await cmd(`/setblock ${px} ${py + 1} ${pz - 3} steward_mc:settlement_board[facing=south]`);
      await cmd(`/tp @p ${px + 0.5} ${py} ${pz + 0.5} facing ${px + 0.5} ${py + 1.5} ${pz - 2.6}`);
      await sleep(2500);
      await dev.request('dev.screenshot', { name: 'e2e-board-waits', frames: 5 }, { timeoutMs: 120_000 });
      await cmd(`/setblock ${px} ${py + 1} ${pz - 3} minecraft:air`);
    }
  }
  await dev.request('dev.key', { key: 'y' });
  await sleep(1500);
  // the offset first: a small update can finish within the key press
  from = logSize();
  await dev.request('dev.key', { key: '1' });
  const upd = await waitLog(from, /Updated dev building to version (\d+)/, 30_000);
  await dev.request('dev.key', { key: 'escape' });
  const hist = await dev.request('dev.site.history', { site });
  if (upd && (hist.version ?? hist.site?.version) === inst.version) ok('update', `${site} -> v${inst.version} from the inbox`);
  else fail('update', `log ${upd ? 'ok' : 'silent'}, /steward updates said "${checked}", ${(logSince(0).match(/update check [^\n]*/g) ?? ['no update check']).at(-1)}, site ${JSON.stringify(hist).slice(0, 120)}`);
  // the steward goes to look at the building it updated (3b): it ends up beside it, outside its footprint
  const gap = (p) => Math.hypot(Math.max(box.x0 - p[0], 0, p[0] - (box.x1 + 1)), Math.max(box.z0 - p[2], 0, p[2] - (box.z1 + 1)));
  let near = null;
  for (let i = 0; i < 15 && box && !near; i++) {
    await sleep(1000);
    const now = await stewardPos();
    if (now && gap(now) > 0 && gap(now) <= 3.5) near = now;
  }
  if (near) ok('steward walks', `${gap(near).toFixed(1)} blocks outside the updated building${before ? `, ${Math.hypot(near[0] - before[0], near[2] - before[2]).toFixed(1)} walked` : ''}; ${plateShot}`);
  else fail('steward walks', `not beside the building: ${JSON.stringify(await stewardPos())}, box ${JSON.stringify(box)}`);
  // the settlement board (3d): a board four blocks north of the player, facing them; its slate lists the settlement; a right-click opens it
  {
    const mine = (await cmd('/data get entity @p Pos')).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
    if (mine) {
      const [px, py, pz] = mine.slice(1).map((n) => Math.floor(Number(n)));
      await cmd(`/setblock ${px} ${py + 1} ${pz - 3} steward_mc:settlement_board[facing=south]`);
      await cmd(`/tp @p ${px + 0.5} ${py} ${pz + 0.5} facing ${px + 0.5} ${py + 1.5} ${pz - 2.6}`);
      await sleep(2500);
      const boardShot = (await dev.request('dev.screenshot', { name: 'e2e-board', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
      await dev.request('dev.key', { mapping: 'key.use' });
      await sleep(1500);
      let screen = (await dev.request('dev.state')).screen?.class ?? 'none';
      // the client's click sometimes misses its aim (about one run in two, not the steward and not the reach): then the board's use, server side
      let how = 'right-click';
      if (screen === 'none') {
        await cmd(`/steward dev use ${px} ${py + 1} ${pz - 3}`);
        await sleep(1500);
        screen = (await dev.request('dev.state')).screen?.class ?? 'none';
        how = "the board's use (the click missed its aim)";
      }
      if (/DescribeScreen|SettlementScreen/.test(screen)) ok('board', `${how} opens ${screen.replace(/.*\./, '')}; ${boardShot}`);
      else {
        // what was in the way: the steward walks about (it may stand between the player and the board)
        const sp = (await cmd(`/data get entity @e[type=steward_mc:steward,tag=steward_mc.settlement.${id},limit=1] Pos`)).match(/\[([^\]]*)\]/)?.[1];
        const me = (await cmd('/data get entity @p Pos')).match(/\[([^\]]*)\]/)?.[1];
        fail('board', `screen ${screen}; board at ${px},${py + 1},${pz - 3}, player ${me}, steward ${sp}`);
      }
      await closeScreens();
      await cmd(`/setblock ${px} ${py + 1} ${pz - 3} minecraft:air`);
    }
  }
  // the steward's ledger (3d): right-click it anywhere for the settlement's screen
  await cmd('/clear @p');
  await cmd('/give @p steward_mc:steward_ledger');
  await dev.request('dev.key', { mapping: 'key.use' });
  await sleep(1500);
  // the e2e settlement is never described, so the steward's first screen for it is the describe screen (once built and described: the settlement screen)
  const ledger = (await dev.request('dev.state')).screen?.class ?? 'none';
  if (/DescribeScreen|SettlementScreen/.test(ledger)) ok('ledger', `opens ${ledger.replace(/.*\./, '')}`);
  else fail('ledger', `screen ${ledger}`);
  await closeScreens();
  // revert (3c): back to version 1 from the building panel (its third button, asked twice)
  await cmd(`/steward view ${id} ${site}`);
  await sleep(1500);
  from = logSize();
  await dev.request('dev.key', { key: '3' });
  await sleep(300);
  await dev.request('dev.key', { key: '3' });
  const rev = await waitLog(from, /Reverted dev building to version (\d+)/, 30_000);
  await dev.request('dev.key', { key: 'escape' });
  const after = await dev.request('dev.site.history', { site });
  const v = after.version ?? after.site?.version;
  const op = settlements().find((s) => s.id === id)?.log.at(-1);
  if (rev && v === 1 && op?.kind === 'REVERTED') ok('revert', `${site} back to v1, logged ${op.op}`);
  else fail('revert', `log ${rev ? rev[0] : (logSince(from).match(/Could not revert[^\n]*/) ?? ['silent'])[0]}, site v${v}, last op ${op?.kind}`);
  from = logSize();
  await cmd(`/steward undo ${id}`);
  await sleep(8000);
  const state = await dev.request('dev.sites.state');
  const standing = JSON.stringify(state.sites ?? state).includes(`"${site}"`);
  const last = settlements().find((s) => s.id === id)?.log.at(-1);
  if (!standing && last?.kind === 'PROJECT_REMOVED') ok('undo', `${site} removed, logged`);
  else fail('undo', `standing ${standing}, last log ${last?.kind}`);
}

/** The player's UUID, from {@code /data get entity @p UUID} ("... [I; a, b, c, d]"): the restored build must be this player's, or cancel refuses it. */
async function playerUuid() {
  const r = await cmd('/data get entity @p UUID');
  const m = r.match(/\[I;\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]/);
  if (!m) throw new Error(`no player UUID (${r})`);
  const hex = m.slice(1).map((n) => (Number(n) >>> 0).toString(16).padStart(8, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** The steward's life at home (3b): it sits on a stair near its stone when idle, and lies in a bed there at night. */
async function stewardLife(id) {
  const st = settlements().find((x) => x.id === id);
  if (!st) return;
  const at = async () => {
    const r = await cmd(`/data get entity @e[type=steward_mc:steward,tag=steward_mc.settlement.${id},limit=1] Pos`);
    const m = r.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
    return m ? m.slice(1).map(Number) : null;
  };
  const cx = st.claim.centerX, cz = st.claim.centerZ;
  const ground = (await at())?.[1] ?? 0;
  const gy = Math.floor(ground);
  // a stair four blocks east of the stone, facing it
  await cmd(`/setblock ${cx + 4} ${gy} ${cz} minecraft:oak_stairs[facing=east]`);
  let p = null;
  for (let i = 0; i < 120; i++) {
    await sleep(1000);
    p = await at();
    if (p && Math.abs(p[0] - (cx + 4.5)) < 0.3 && Math.abs(p[2] - (cz + 0.5)) < 0.3 && Math.abs(p[1] - (gy + 0.5)) < 0.1) break; // on the seat, a little forward of the step
    p = null;
  }
  if (p) {
    await cmd(`/tp @p ${cx + 1.5} ${gy} ${cz + 3.5} facing ${cx + 4.5} ${gy + 1} ${cz + 0.5}`);
    await sleep(1500);
    const shot = await dev.request('dev.screenshot', { name: 'e2e-steward-sits', frames: 5 }, { timeoutMs: 120_000 });
    ok('steward sits', shot.path ?? '');
  } else fail('steward sits', `not on the stair at ${cx + 4},${gy},${cz}: ${JSON.stringify(await at())}`);
  // night: a bed five blocks west of the stone
  await cmd(`/setblock ${cx + 4} ${gy} ${cz} minecraft:air`);
  await cmd(`/setblock ${cx - 5} ${gy} ${cz} minecraft:red_bed[facing=north,part=head]`);
  await cmd(`/setblock ${cx - 5} ${gy} ${cz + 1} minecraft:red_bed[facing=north,part=foot]`);
  await cmd('/time set midnight');
  let b = null;
  for (let i = 0; i < 40; i++) {
    await sleep(1000);
    b = await at();
    if (b && Math.abs(b[0] - (cx - 4.5)) < 0.3 && Math.abs(b[2] - (cz + 1.5)) < 0.3 && b[1] > gy + 0.5) break; // on the foot half, lying towards the head
    b = null;
  }
  if (b) {
    // from above, the foot end towards the bottom of the picture: the head should lie on the pillow
    await cmd(`/tp @p ${cx - 4.5} ${gy + 5} ${cz + 3.2} facing ${cx - 4.5} ${gy} ${cz + 0.4}`);
    await sleep(1500);
    const shot = await dev.request('dev.screenshot', { name: 'e2e-steward-sleeps', frames: 5 }, { timeoutMs: 120_000 });
    ok('steward sleeps', shot.path ?? '');
  } else fail('steward sleeps', `not in the bed at ${cx - 5},${gy},${cz}: ${JSON.stringify(await at())}`);
  await cmd('/time set day');
}

/** Proposals (3e): a described settlement and a player carrying seeds: within a minute the steward proposes a farm; declined, it is remembered. */
async function proposals(id) {
  await cmd(`/steward dev describe ${id}`);
  // proposals are additions to a settlement that has something standing (a farm animal in the box refuses the placement: cleared first)
  await clearAnimals();
  await cmd(`/steward dev place ${id} ${STUB}`);
  await cmd('/give @p minecraft:wheat_seeds 16');
  let open = null;
  for (let i = 0; i < 80 && !open; i++) {
    await sleep(1000);
    const st = settlements().find((x) => x.id === id);
    open = st?.proposals?.open?.find((p) => p.key === 'farm') ?? null;
  }
  if (!open) return fail('proposal', 'no farm proposed within 80 s');
  await closeScreens();
  await dev.request('dev.key', { key: 'y' });
  await sleep(1500);
  const shot = (await dev.request('dev.screenshot', { name: 'e2e-proposal', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  await dev.request('dev.key', { key: 'escape' });
  ok('proposal', `${open.title}: ${open.why}; ${shot}`);
  await cmd(`/steward proposals ${id} decline farm`);
  await sleep(500);
  const after = settlements().find((x) => x.id === id)?.proposals;
  if (after?.declined?.includes('farm') && !after.open.some((p) => p.key === 'farm')) ok('proposal declined', 'remembered, not offered again');
  else fail('proposal declined', JSON.stringify(after));
}

/**
 * Districts and protected areas (3f): a protected area on the west road's way refuses that district by name; the east district is founded with its road
 * ($0, no Claude), shown in survey mode, and undone (the road goes, then the district, its land free again).
 */
/**
 * The player's own builds (Architect PT, 0.27): a hut put up in a corner of the claim (by command, so not tracked), claimed as the player's ("claim my
 * build"), listed, shown on the settlement screen and in survey mode, then released and taken down.
 */
/** A 7x7 plank hut (its bottom row the floor, a door on its south side) put up by command in a corner of the claim and claimed as the player's. */
async function buildHut(id, check, corner = [6, 6]) {
  const st = settlements().find((x) => x.id === id);
  if (!st) return null;
  const r = st.claim.radius, x0 = st.claim.centerX - r + corner[0], z0 = st.claim.centerZ - r + corner[1];
  await closeScreens();
  await cmd(`/tp @p ${x0 + 3} 100 ${z0 + 3}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  // on the ground (a player out of spectator mode keeps flying, and a hut built where they hover floats: Architect C34b)
  await cmd(`/spreadplayers ${x0 + 3} ${z0 + 3} 0 1 false @p`);
  await sleep(1000);
  const y = Math.floor(Number(((await cmd(`/data get entity @p Pos`)).match(/, (-?[\d.]+)d, /) ?? [0, 64])[1]));
  await cmd(`/fill ${x0} ${y} ${z0} ${x0 + 6} ${y + 4} ${z0 + 6} minecraft:oak_planks hollow`);
  await cmd(`/setblock ${x0 + 3} ${y + 1} ${z0 + 6} minecraft:oak_door[half=lower,facing=south]`);
  await cmd(`/setblock ${x0 + 3} ${y + 2} ${z0 + 6} minecraft:oak_door[half=upper,facing=south]`);
  await cmd(`/tp @p ${x0 + 3} ${y} ${z0 + 3}`);
  const claimed = await cmd(`/steward builds ${id} claim 4`);
  const bid = (claimed.match(/\((build-\d+)/) ?? [])[1];
  if (!bid) { fail(check, claimed); return null; }
  return { x0, y, z0, bid, claimed };
}

async function playerBuilds(id) {
  const hut = await buildHut(id, 'player build');
  if (!hut) return;
  const { x0, y, z0, bid, claimed } = hut;
  const listed = await cmd(`/steward builds ${id}`);
  // a house with its door (Architect C34)
  if (listed.includes(bid) && !/no door/.test(listed)) ok('player build', `${claimed.slice(0, 110)}; listed: ${listed.split('\n')[1]?.slice(0, 90)}`);
  else fail('player build', listed.slice(0, 200));
  // the settlement screen, opened from outside the hut (as a player would, by command)
  await cmd(`/tp @p ${x0 + 3} ${y} ${z0 + 14}`);
  await cmd(`/steward view ${id}`);
  await sleep(1500);
  if (!/SettlementScreen/.test(JSON.stringify(await dev.request('dev.state')))) fail('player build shown', 'the settlement screen did not open');
  // the Claim tab, where the hand-built row is (Tab moves along: Buildings, History, Claim)
  await dev.request('dev.key', { key: 'tab' });
  await dev.request('dev.key', { key: 'tab' });
  await sleep(800);
  const screenShot = (await dev.request('dev.screenshot', { name: 'e2e-player-build-screen', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  await closeScreens();
  await cmd('/gamemode spectator');
  await cmd('/weather clear');
  await cmd(`/tp @p ${x0 + 3} ${y + 12} ${z0 + 20} facing ${x0 + 3} ${y + 2} ${z0 + 3}`);
  await dev.request('dev.key', { key: 'u' });
  await sleep(2500);
  const surveyShot = (await dev.request('dev.screenshot', { name: 'e2e-player-build-survey', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  await dev.request('dev.key', { key: 'u' });
  await cmd('/gamemode creative');
  ok('player build shown', `${screenShot}, ${surveyShot}`);
  // taken on as one of the settlement's buildings (Architect PT2): no block written; an undo lets it go and the hut still stands
  await cmd(`/tp @p ${x0 + 3} ${y} ${z0 + 14}`);
  let from = logSize();
  await cmd(`/steward builds ${id} adopt ${bid}`);
  const adopted = await waitLog(from, new RegExp(`adopt ${id} ${bid}: ([^\\n]*)`), 60_000);
  const site = (adopted?.[1].match(/\((\S+), \d+ blocks\)/) ?? [])[1];
  const logged = settlements().find((x) => x.id === id)?.log.at(-1);
  const stands = site && /stands/.test(await cmd(`/steward dev site ${site}`));
  if (site && stands && logged?.kind === 'BUILD_ADOPTED') ok('player build adopted', `${adopted[1].slice(0, 120)}; history: ${logged.text}`);
  else return fail('player build adopted', `${adopted?.[1] ?? 'no answer'}; site standing ${stands}; last op ${logged?.kind}`);
  // the undo's result read from the history (the dev bridge doesn't hear a reply that comes after the command returns)
  await cmd(`/steward undo ${id}`);
  let undone = null;
  for (let i = 0; i < 60 && !undone; i++) {
    await sleep(1000);
    const last = settlements().find((x) => x.id === id)?.log.at(-1);
    if (last?.kind === 'PROJECT_REMOVED' && last.siteIds?.includes(site)) undone = last;
  }
  const siteGone = !/stands/.test(await cmd(`/steward dev site ${site}`));
  const hutStands = /passed/i.test(await cmd(`/execute if block ${x0} ${y + 2} ${z0} minecraft:oak_planks`));
  if (undone && siteGone && hutStands) ok('player build let go', `${undone.text}; ${site} gone, the hut stands`);
  else fail('player build let go', `history ${undone ? undone.text : 'no undo entry'}; site gone ${siteGone}; hut stands ${hutStands}`);
  const released = await cmd(`/steward builds ${id} release ${bid}`);
  await cmd(`/fill ${x0} ${y} ${z0} ${x0 + 6} ${y + 4} ${z0 + 6} minecraft:air`);
  if (/Released/.test(released)) ok('player build released', released);
  else fail('player build released', released);
}

async function districts(id) {
  const st = settlements().find((x) => x.id === id);
  if (!st) return;
  const cx = st.claim.centerX, cz = st.claim.centerZ;
  await closeScreens();
  // a protected area 60 blocks west of the stone, across the west road's way
  await cmd(`/tp @p ${cx - 60} 100 ${cz}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await cmd(`/tp @p ${cx - 60} ~ ${cz}`);
  await cmd(`/steward protect ${id} here 3 e2e garden`);
  await sleep(500);
  await closeScreens();
  const area = settlements().find((x) => x.id === id)?.lands?.protect?.find((a) => a.label === 'e2e garden');
  if (!area) return fail('protected area', 'not saved');
  const west = await cmd(`/steward district ${id} found W`);
  if (/protected area "e2e garden"/.test(west)) ok('protected area', `${area.id} ${area.x1 - area.x0 + 1} x ${area.z1 - area.z0 + 1}; the west district is refused: ${west}`);
  else fail('protected area', `west district: ${west}`);
  // Architect's write bounds (C17, 0.15): a building placed into a protected area is refused by Architect itself, not only kept off by Steward's layouts
  await cmd(`/steward protect ${id} here 10 e2e lawn`);
  await closeScreens();
  const lawn = settlements().find((x) => x.id === id)?.lands?.protect?.find((a) => a.label === 'e2e lawn');
  let pfrom = logSize();
  await clearAnimals();
  await cmd(`/steward dev place ${id} ${STUB}`);
  const refused = await waitLog(pfrom, /dev place refused: [^\n]*/, 20_000);
  if (refused && /PROTECTED/.test(refused[0])) ok('architect bounds', refused[0].slice(0, 120));
  else fail('architect bounds', refused ? refused[0] : (logSince(pfrom).match(/dev placed \S+/) ?? ['no answer'])[0]);
  if (lawn) await cmd(`/steward protect ${id} clear ${lawn.id}`);
  await closeScreens();
  // the east district: stand between the stone and it, so the road's way is loaded
  await cmd(`/tp @p ${cx + 90} 100 ${cz + 12}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  const east = await cmd(`/steward district ${id} found E e2e harbour`);
  let d = null;
  for (let i = 0; i < 20 && !d; i++) {
    await sleep(1000);
    d = settlements().find((x) => x.id === id)?.lands?.districts?.find((x) => x.direction === 'E') ?? null;
  }
  const founded = settlements().find((x) => x.id === id)?.log.at(-1);
  const road = d?.roads?.[0]?.siteId;
  const standing = road && /stands \(road\)/.test(await cmd(`/steward dev site ${road}`));
  if (!d || founded?.kind !== 'DISTRICT_FOUNDED' || !standing) return fail('district', `${east}; district ${JSON.stringify(d)}, last op ${founded?.kind}, road standing ${standing}`);
  ok('district', `${d.id} "${d.name}" ${2 * d.claim.radius + 1} across at ${d.claim.centerX},${d.claim.centerZ}, road ${road} (${founded.op})`);
  // survey mode: the claim, the district's square and the road, from above the town's east edge
  const gy = Number(((await cmd(`/data get entity @p Pos`)).match(/, (-?[\d.]+)d, /) ?? [0, 64])[1]);
  // a spectator does not fall: the picture is taken from above
  await cmd('/gamemode spectator');
  await cmd(`/tp @p ${cx + 100} ${Math.floor(gy) + 40} ${cz + 70} facing ${cx + 165} ${Math.floor(gy)} ${cz}`);
  await dev.request('dev.key', { key: 'u' });
  await sleep(2500);
  const shot = (await dev.request('dev.screenshot', { name: 'e2e-district', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  await dev.request('dev.key', { key: 'u' });
  await cmd('/gamemode creative');
  ok('district survey', shot);
  // undo: the founding is the newest operation, its road goes and the district with it
  await cmd(`/tp @p ${cx + 90} ${Math.floor(gy)} ${cz + 12}`);
  await cmd(`/steward undo ${id}`);
  let after = null;
  for (let i = 0; i < 20; i++) {
    await sleep(1000);
    after = settlements().find((x) => x.id === id);
    if (!(after?.lands?.districts ?? []).length) break;
  }
  const kinds = (after?.log ?? []).slice(-2).map((e) => e.kind);
  const roadLeft = !/is gone/.test(await cmd(`/steward dev site ${road}`));
  if (!(after?.lands?.districts ?? []).length && !roadLeft && kinds.join() === 'PROJECT_REMOVED,DISTRICT_REMOVED') ok('district undo', `road ${road} removed, district given up`);
  else fail('district undo', `districts ${JSON.stringify(after?.lands?.districts)}, road standing ${roadLeft}, last ops ${kinds}`);
  await cmd(`/steward protect ${id} clear ${area.id}`);
  await closeScreens();
  // where a district goes (Zones, first step): "here" takes the side nearest where the player stands; "auto" reads the ground for what the name asks
  const rv = Math.min(128, st.claim.radius);
  const placedBy = async (check, args, standAt) => {
    await cmd(`/tp @p ${standAt[0]} 100 ${standAt[1]}`);
    await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
    await cmd(`/tp @p ${standAt[0]} ~ ${standAt[1]}`);
    const before = (settlements().find((x) => x.id === id)?.lands?.districts ?? []).length;
    const from = logSize();
    const said = await cmd(`/steward district ${id} found ${args}`);
    let made = null;
    for (let i = 0; i < 30 && !made; i++) {
      await sleep(1000);
      const ds = settlements().find((x) => x.id === id)?.lands?.districts ?? [];
      if (ds.length > before) made = ds.at(-1);
    }
    const why = (logSince(from).match(/To the (north|east|south|west)[^\n]*/) ?? [''])[0];
    if (made) await cmd(`/steward undo ${id}`), await sleep(4000), await closeScreens();
    return { made, why, said };
  };
  const here = await placedBy('district here', 'here e2e south quarter', [cx, cz + rv + 20]);
  // the south side, unless an earlier check's building stands on its road's way (then the nearest side founding can take)
  if (here.made && /where you stand/.test(here.why)) ok('district here', `${here.made.name} to the ${here.made.direction}: ${here.why}`);
  else fail('district here', `${here.said}; made ${JSON.stringify(here.made)}, said "${here.why}"`);
  const auto = await placedBy('district auto', 'auto e2e lumber camp', [cx, cz]);
  if (auto.made && auto.why) ok('district auto', `${auto.made.name} to the ${auto.made.direction}: ${auto.why}`);
  else fail('district auto', `${auto.said}; made ${JSON.stringify(auto.made)}, said "${auto.why}"`);
  // the same through a proposal (the steward proposes a district when the town's street is full): accepted, it is founded and the proposal recorded
  await cmd(`/tp @p ${cx + 90} ${Math.floor(gy)} ${cz + 12}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await cmd(`/steward dev propose_district ${id} E`);
  // survey mode previews it before it is founded: its square and a "Proposed" label, from above the town's east edge
  await cmd('/gamemode spectator');
  await cmd(`/tp @p ${cx + 100} ${Math.floor(gy) + 40} ${cz + 70} facing ${cx + 165} ${Math.floor(gy)} ${cz}`);
  await sleep(1500);
  await dev.request('dev.key', { key: 'u' });
  await sleep(2500);
  const pshot = (await dev.request('dev.screenshot', { name: 'e2e-district-proposed', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  await dev.request('dev.key', { key: 'u' });
  await cmd('/gamemode creative');
  await cmd(`/tp @p ${cx + 90} ${Math.floor(gy)} ${cz + 12}`);
  ok('district preview', pshot);
  const accepted = await cmd(`/steward proposals ${id} accept district_E`);
  let viaProposal = null;
  for (let i = 0; i < 20 && !viaProposal; i++) {
    await sleep(1000);
    viaProposal = settlements().find((x) => x.id === id)?.lands?.districts?.find((x) => x.direction === 'E') ?? null;
  }
  const ps = settlements().find((x) => x.id === id)?.proposals;
  if (viaProposal && ps?.accepted?.includes('district_E') && !ps.open.some((p) => p.key === 'district_E')) ok('district proposal', `accepted: ${viaProposal.id} founded, the proposal recorded`);
  else fail('district proposal', `${accepted}; district ${JSON.stringify(viaProposal)}, proposals ${JSON.stringify(ps)}`);
  await cmd(`/steward undo ${id}`);
  await sleep(4000);
  await closeScreens();
}

/**
 * Talking to the steward (3b): a question it answers itself ($0), then made-up talk answers (no Claude) that put a building and a district in the inbox and
 * note a change; the talk box, with the conversation, in a picture.
 */
async function talk(id) {
  await closeScreens();
  const name = settlements().find((x) => x.id === id)?.name ?? id;
  let from = logSize();
  await cmd(`/steward talk ${id} what are you doing?`);
  const status = await waitLog(from, new RegExp(`\\[${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\] (I am [^\n]*)`), 10_000);
  if (status) ok('talk status', status[1]);
  else fail('talk status', logSince(from).slice(-300));
  const answer = (f) => JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'talk', f), 'utf8')).answer);
  const talkFrom = logSize();
  await cmd(`/steward dev talk_answer ${id} ${answer('library.json')}`);
  await cmd(`/steward dev talk_answer ${id} ${answer('creepier.json')}`);
  await cmd(`/steward dev talk_answer ${id} ${answer('harbour.json')}`);
  await sleep(500);
  const st = settlements().find((x) => x.id === id);
  const lib = st?.proposals?.open?.find((p) => p.key.startsWith('asked_'));
  const harbour = st?.proposals?.open?.find((p) => p.key === 'district_E');
  // the change: noted in the history (an Architect without 0b's versionOf), or, with versionOf, a change proposal; here no tavern stands, so the steward
  // asks which building (also right)
  const asked = /Which building do you mean\? I could not tell which one is the tavern/.test(logSince(talkFrom));
  const note = (st?.log ?? []).find((e) => e.kind === 'NOTE' && /Asked to change tavern/.test(e.text)) ?? (asked ? { text: 'asked which building is the tavern' } : null);
  if (lib && /library/i.test(lib.title) && harbour && /harbour/i.test(harbour.title) && note) ok('talk acts', `${lib.title} and ${harbour.title} in the inbox; "${note.text.slice(0, 60)}" noted`);
  else fail('talk acts', `building ${JSON.stringify(lib)}, district ${JSON.stringify(harbour)}, note ${JSON.stringify(note)}`);
  await cmd(`/steward ui talk ${id}`);
  await sleep(1500);
  // said in the box this time: the player's line and the answer, in the picture
  from = logSize();
  await dev.request('dev.type', { text: 'hello' });
  await dev.request('dev.key', { key: 'enter' });
  const typed = await waitLog(from, /\] Hello! Tell me what/, 10_000);
  await sleep(800);
  const screen = (await dev.request('dev.state')).screen?.class ?? 'none';
  const shot = (await dev.request('dev.screenshot', { name: 'e2e-talk', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  if (/TalkScreen/.test(screen) && typed) ok('talk box', `said "hello" in the box, answered; ${shot}`);
  else fail('talk box', `screen ${screen}`);
  await closeScreens();
  // leave the inbox as it was
  if (lib) await cmd(`/steward proposals ${id} decline ${lib.key}`);
  if (harbour) await cmd(`/steward proposals ${id} decline district_E`);
}

/** A world from before the steward entity: its mannequin stewards become steward_mc:steward when their chunks load. */
async function migratedSteward() {
  const first = settlements()[0];
  if (!first) return;
  await cmd(`/tp @p ${first.claim.centerX} 100 ${first.claim.centerZ}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  let found = '';
  for (let i = 0; i < 10 && !/Steward/.test(found); i++) {
    await sleep(1000);
    found = await cmd(`/data get entity @e[type=steward_mc:steward,tag=steward_mc.settlement.${first.id},limit=1] CustomName`);
  }
  const old = await cmd(`/data get entity @e[type=minecraft:mannequin,tag=steward_mc.settlement.${first.id},limit=1] CustomName`);
  if (/Steward/.test(found) && !/Steward/.test(old)) ok('steward migrated', `${first.id}: a steward_mc:steward, no mannequin`);
  else fail('steward migrated', `new: ${found || 'none'}; old: ${old || 'none'}`);
}

async function restartRestore() {
  const uuid = await playerUuid();
  await quit();
  const tpl = fs.readFileSync(path.join(ROOT, 'tools', 'e2e', 'restore-build.json'), 'utf8').replaceAll('__SETTLEMENT__', 'e2e_restore').replaceAll('__GROUP__', 'grp_e2e_missing')
    .replaceAll('00000000-0000-0000-0000-000000000042', uuid);
  fs.writeFileSync(path.join(WORLD, 'steward-builds.json'), tpl);
  launch();
  await connect();
  const m = await waitLog(0, /resume e2e_restore: phase (\w+)[^\n]*-> (\w+)/, 30_000);
  if (m && m[2] === 'REREAD_GROUP' && /lost track of|interrupted/.test(logSince(0))) ok('restart', `restored at ${m[1]}, re-read, reported interrupted`);
  else fail('restart', m ? m[0] : 'no resume line');
  const said = await cmd('/steward cancel e2e_restore');
  // the group is gone at Architect, so the cancel confirms at once; allow a moment for the save
  let left = -1;
  for (let i = 0; i < 5 && left !== 0; i++) {
    if (i > 0) await sleep(1000);
    left = JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-builds.json'), 'utf8')).builds.length;
  }
  if (left === 0) ok('cancel', 'the restored build is dropped');
  else fail('cancel', `${left} builds left (${said})`);
}

/**
 * A settlement on Architect's flat village (slice V, 0.16): a Patron card on flat ground plans a village (its lots, lanes, square and props), its buildings
 * are designed as ever, the village is realised as one region (its street road:street_*), logged under the region, and undone whole.
 */
async function villageFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 6000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  // the flat village from the card, not the settlement's composed plan (planBuildFlow builds from a plan)
  await cmd(`/steward dev layout ${id} village`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'V Hamlet';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little hamlet`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('village', 'no card');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  const planned = await waitLog(from, /Laying out the village[^\n]*/, 60_000);
  const built = await buildThrough(id, from, 'village');
  if (!built) return;
  const st = settlements().find((x) => x.id === id);
  const placedOp = (st?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const region = placedOp?.siteGroup;
  if (planned && /^region:/.test(region ?? '') && built.placed > 0 && placedOp.outcome === 'DONE') ok('village', `${built.steps.join(' > ')} > ${built.placed} placed on Architect's flat village (${region}, ${placedOp.siteIds.length} sites)`);
  else return fail('village', `planned ${!!planned}, history ${JSON.stringify(placedOp)?.slice(0, 160)}`);
  // its buildings carry Steward's tags (Architect 1.14 C19): the history names each by its role from the card, not Architect's lot id
  const roles = (placedOp.changes ?? []).map((c) => c.lot).filter(Boolean);
  if (roles.length === built.placed && roles.every((r) => !/_\d+$/.test(r))) ok('village tags', roles.join(', '));
  else fail('village tags', JSON.stringify(placedOp.changes ?? []).slice(0, 200));
  // its street is a town street to Steward (additions and districts work on it)
  const street = await cmd(`/steward dev site ${placedOp.siteIds.find((x) => /^r/.test(x)) ?? 'none'}`);
  if (/stands \(road\)/.test(street)) ok('village street', street);
  else fail('village street', street);
  // a picture of it
  await cmd('/gamemode spectator');
  await cmd('/time set noon');
  await cmd(`/tp @p ${st.claim.centerX - 30} 110 ${st.claim.centerZ + 40} facing ${st.claim.centerX} 64 ${st.claim.centerZ}`);
  await sleep(3000);
  const shot = (await dev.request('dev.screenshot', { name: 'e2e-village', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  await cmd('/gamemode creative');
  ok('village picture', shot);
  await cmd(`/steward undo ${id}`);
  // the region removed whole: the history says so, and the village's street is gone with its buildings
  let last = null;
  for (let i = 0; i < 60 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  const streetLeft = await cmd(`/steward dev site ${placedOp.siteIds.find((x) => /^r/.test(x)) ?? 'none'}`);
  if (last?.kind === 'PROJECT_REMOVED' && last.siteIds.length === placedOp.siteIds.length && /is gone/.test(streetLeft)) {
    ok('village undo', `${last.text} (${last.siteIds.length} sites); ${streetLeft}`);
  } else fail('village undo', `last op ${last?.kind} (${last?.siteIds?.length} of ${placedOp.siteIds.length} sites), ${streetLeft}`);
}

/**
 * (docs/LAND.md, Architect LC) The steward sees what changed in its town: a flat village is built and looked over (nothing changed); then a player
 * breaks two cells of one building and refills one with cobblestone (Architect's PT: dev.pt.breaks and dev.pt.uses, so the cobblestone carries a
 * player's record). Looked over again, the steward names that building, 1 block broken or changed and 1 placed by a player, and nothing else; the
 * inbox holds one LAND row (a screenshot); "what changed?" said to the steward answers the same, $0; "Got it" clears the row.
 */
async function landFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 63000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  await cmd(`/steward dev layout ${id} village`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Lookout Hamlet';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little hamlet to keep an eye on`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('land', 'no card');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 4 30`);
  const built = await buildThrough(id, from, 'land town');
  if (!built) return;
  const st = settlements().find((x) => x.id === id);
  const placedOp = (st?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const region = /^region:(.+)$/.exec(placedOp?.siteGroup ?? '')?.[1];
  if (!region) return fail('land', `the town is not a region: ${placedOp?.siteGroup}`);
  // stand in the town (its chunks loaded; an automatic look may run too)
  await cmd(`/tp @p ${st.claim.centerX} 120 ${st.claim.centerZ}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await sleep(2000);
  const name = st.name;
  const said = (re) => new RegExp(`\\[${name}\\] ${re}`);
  from = logSize();
  await cmd(`/steward check ${id}`);
  // an automatic look of the new town may be running: asked again once it is done
  if (await waitLog(from, /looking over the land already/, 3000)) {
    await sleep(5000);
    from = logSize();
    await cmd(`/steward check ${id}`);
  }
  const fresh = await waitLog(from, said('(I found nothing[^\\n]*|I looked over[^\\n]*|I couldn.t[^\\n]*)'), 120_000);
  if (fresh && /I found nothing broken or changed in what we built in /.test(fresh[1])) ok('land fresh', fresh[1]);
  else return fail('land fresh', `${fresh?.[1] ?? 'no answer'}; ${logSince(from).split('\n').filter((l) => /land check|\[CHAT\]/.test(l)).slice(-6).join(' / ')}`);
  // a building of the town and two of its solid cells (the roof in its middle columns)
  const b = (placedOp.changes ?? []).find((c) => c.lot && c.siteId);
  if (!b) return fail('land', `no building in ${JSON.stringify(placedOp.changes ?? []).slice(0, 160)}`);
  const solid = (await cmd(`/steward dev solid ${b.siteId}`)).match(/-?\d+,-?\d+,-?\d+/g) ?? [];
  if (solid.length < 2) return fail('land', `no two solid cells of ${b.siteId}: ${solid.join(' ')}`);
  const [c1, c2] = solid.slice(0, 2).map((t) => t.split(',').map(Number));
  const br = await dev.call('dev.pt.breaks', { at: [c1, c2], placer: 'LandAlex' }, { timeoutMs: 120_000 });
  const uses = (await dev.call('dev.pt.uses', { ops: [{ item: 'minecraft:cobblestone', at: c2, placer: 'LandAlex' }] }, { timeoutMs: 120_000 })).results ?? [];
  await sleep(2000);
  if (br.broken === 2 && uses[0]?.tracked) ok('land damage', `a player broke ${c1.join(',')} and ${c2.join(',')} of ${b.lot} (${b.siteId}) and put cobblestone back at the second`);
  else return fail('land damage', `broken ${br.broken}, use ${JSON.stringify(uses[0] ?? {}).slice(0, 160)}`);
  // Architect's own report, for the record
  await sleep(3500); // the steward's 3 s between looks
  const lc = await dev.call('dev.region.landCheck', { region }, { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
  const lotRow = (lc.lots ?? []).find((l) => l.siteId === b.siteId);
  const others = [...(lc.lots ?? []).filter((l) => l.siteId !== b.siteId && l.changed + l.playerPlaced > 0), ...(lc.parts ?? []).filter((p) => p.removed + p.changed + p.playerPlaced > 0)];
  console.log(`land: Architect's report: ${JSON.stringify({ lot: lotRow, others, unloaded: lc.unloaded, refused: lc.refused })}`);
  from = logSize();
  await cmd(`/steward check ${id}`);
  const seen = await waitLog(from, said('(I looked over[^\\n]*|I found nothing[^\\n]*|I couldn.t[^\\n]*)'), 120_000);
  await sleep(1000);
  const lines = logSince(from).split('\n').map((l) => l.match(said('(.*)'))?.[1]).filter((l) => l && !/^Let me look/.test(l));
  const role = String(b.lot).replace(/_/g, ' ');
  const want = `The ${role}: 1 block broken or changed, 1 placed by a player.`;
  if (seen && /broken or changed/.test(seen[1]) && lines.includes(want) && lines.length === 2) ok('land seen', lines.join(' / '));
  else fail('land seen', `${lines.join(' / ')} (wanted "${want}" alone; Architect: ${JSON.stringify({ lot: lotRow, others }).slice(0, 300)})`);
  // the inbox: one LAND row, needing no one
  await cmd(`/steward ui inbox ${id}`);
  await sleep(1500);
  const shot = (await dev.request('dev.screenshot', { name: 'e2e-land-inbox', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  const screen = JSON.stringify((await dev.request('dev.state')).screen ?? '');
  await closeScreens();
  if (/InboxScreen/.test(screen)) ok('land inbox', shot);
  else fail('land inbox', screen.slice(0, 120));
  // talking: "what changed?" is answered by the steward itself, no talk job
  await sleep(3500);
  from = logSize();
  await cmd(`/steward talk ${id} what changed?`);
  const talk = await waitLog(from, said('(I looked over[^\\n]*)'), 120_000);
  const job = /talk job|Let me think about that/.test(logSince(from));
  if (talk && talk[1].includes(want) && !job) ok('land talk', talk[1]);
  else fail('land talk', `${talk?.[1] ?? 'no answer'}; talk job ${job}`);
  // "Got it": the row goes
  const gone = await cmd(`/steward check ${id} dismiss`);
  const again = await cmd(`/steward check ${id} dismiss`);
  if (/Noted/.test(gone) && /Nothing to dismiss/.test(again)) ok('land dismiss', `${gone} then ${again}`);
  else fail('land dismiss', `${gone} then ${again}`);
  await cmd(`/steward undo ${id}`);
}

/**
 * (docs/GROWTH.md, slice A) A region town grows through Architect's revisions: a flat village is built, a library the steward was asked for is accepted,
 * and the addition is planned as a revision of the town (Steward's keys and tags, lots renamed to the build's own ids), realised into it, logged under
 * revision:<region>:<rid> with no street piece of Steward's beside it. Undone, only the revision goes (undoRevision): the town's ground is as before the
 * growth, cell for cell, and the town still stands; a second undo takes the town itself.
 */
async function growthFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 27000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  await cmd(`/steward dev layout ${id} village`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Growing Hamlet';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little hamlet that will grow`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('growth', 'no card');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 4 30`);
  const town = await buildThrough(id, from, 'growth town');
  if (!town) return;
  const st0 = settlements().find((x) => x.id === id);
  const founding = (st0?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const region = /^region:(.+)$/.exec(founding?.siteGroup ?? '')?.[1];
  if (!region) return fail('growth', `the town is not a region: ${founding?.siteGroup}`);
  const claim = st0.claim;
  const box = [claim.centerX - claim.radius - 48, 40, claim.centerZ - claim.radius - 48, claim.centerX + claim.radius + 48, 160, claim.centerZ + claim.radius + 48];
  const snap = path.join(ROOT, 'artifacts', 'e2e', `growth-town-${id}.snap.gz`);
  await dev.call('dev.region.hash', { box, mode: 'snap', file: snap }, { timeoutMs: 1_800_000 });
  // asked for a library in talk, accepted: an addition with no district
  await cmd(`/steward dev sim_answer talk ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'talk', 'library.json'), 'utf8')).answer)}`);
  await cmd(`/steward talk ${id} we need a library for the scholars`);
  let lib = null;
  for (let i = 0; i < 60 && !lib; i++) {
    await sleep(1000);
    lib = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key.startsWith('asked_')) ?? null;
  }
  if (!lib) return fail('growth', 'no library proposal');
  await clearAnimals();
  from = logSize();
  await cmd(`/steward proposals ${id} accept ${lib.key}`);
  const planned = await waitLog(from, /The town grows: Architect finds room[^\n]*/, 120_000);
  const grew = await buildThrough(id, from, 'growth');
  if (!grew) return;
  const st1 = settlements().find((x) => x.id === id);
  const op = (st1?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const m = /^revision:(.+):(\d+)$/.exec(op?.siteGroup ?? '');
  const v = await dev.request('dev.region.state', { region }).catch(() => null);
  const rv = v?.view ?? v ?? {};
  const rev = (rv.revisions ?? []).find((r) => String(r.rid) === m?.[2]);
  // its sites: the revision's own, under the build's tags; no street piece of Steward's (street, street#n) among them
  const sites = [];
  for (const sid of op?.siteIds ?? []) sites.push(await cmd(`/steward dev site ${sid}`));
  const streetPiece = sites.some((t) => /\bstreet(#\d+)?\b/.test(t) && !/road:street_/.test(t));
  if (planned && m && m[1] === region && rev && grew.placed >= 1 && !streetPiece && (op.siteIds ?? []).every((sid) => (rev.sites ?? []).includes(sid))) {
    ok('growth', `${grew.steps.join(' > ')} > ${grew.placed} placed as revision ${m[2]} of ${region} (${rev.state}, ${op.siteIds.length} sites, lots ${(rev.lotsAdded ?? []).join(', ')})`);
  } else return fail('growth', `planned ${!!planned}; entry ${op?.siteGroup}; revision ${JSON.stringify(rev)?.slice(0, 200)}; street piece ${streetPiece}`);
  // the lots carry the build's own ids (never a repeat of the town's library_1 or house_1)
  if ((rev.lotsAdded ?? []).length && rev.lotsAdded.every((l) => /^g[a-z0-9]+_\d+$/.test(l))) ok('growth lot ids', rev.lotsAdded.join(', '));
  else fail('growth lot ids', JSON.stringify(rev.lotsAdded));
  // the town is still the settlement's town (the founding entry names it)
  const still = (st1.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED' && e.siteGroup === `region:${region}`).length === 1;
  if (still && rv.state === 'PLACED') ok('growth town', `${region} ${rv.state}, position ${rv.position}`);
  else fail('growth town', `${rv.state}, founding entries ${still}`);
  // undone: only the revision goes, the town's ground as before the growth
  from = logSize();
  await cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ - claim.radius - 40}`);
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 300 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  const v2 = await dev.request('dev.region.state', { region }).catch(() => null);
  const after = v2?.view ?? v2 ?? {};
  const d0 = await dev.call('dev.region.hash', { box, mode: 'diff', file: snap }, { timeoutMs: 1_800_000 });
  const off = (d0.list ?? []).filter((x) => !/^(growth|grazed)$/.test(String(x.class)));
  const undoneRev = (after.revisions ?? []).find((r) => String(r.rid) === m[2]);
  if (last?.kind === 'PROJECT_REMOVED' && undoneRev?.state === 'UNDONE' && after.state === 'PLACED' && after.position === 0 && off.length === 0) {
    ok('growth undo', `${last.text}; revision ${m[2]} UNDONE, ${region} PLACED at position 0, the ground as before the growth (0 cells differ)`);
  } else fail('growth undo', `last ${last?.kind}; revision ${undoneRev?.state}; town ${after.state} at ${after.position}; ${off.length} cell(s) differ ${JSON.stringify(off.slice(0, 3))}; ${logSince(from).split('\n').filter((l) => /CHAT/.test(l)).slice(-2).join(' | ')}`);
  // then the town itself
  const v3 = await dev.request('dev.region.state', { region }).catch(() => null);
  const pre = v3?.view ?? v3 ?? {};
  // clear of the town (Architect waits for a player in a box it restores)
  await cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ - claim.radius - 40}`);
  const said = await cmd(`/steward undo ${id}`);
  // logged as the town's removal (its founding entry's sites), as villageFlow's undo
  let gone = null;
  for (let i = 0; i < 300 && !gone; i++) {
    await sleep(1000);
    const e = settlements().find((x) => x.id === id)?.log.at(-1);
    if (e?.kind === 'PROJECT_REMOVED' && (e.siteIds ?? []).length === founding.siteIds.length && founding.siteIds.every((x) => e.siteIds.includes(x))) gone = e;
  }
  if (gone) ok('growth town undo', `${gone.text}: the town itself, once its growth was undone`);
  else fail('growth town undo', `${said}; the town was ${pre.state}, busy ${pre.busy}, revisions ${JSON.stringify((pre.revisions ?? []).map((r) => [r.rid, r.state]))}`);
}

/**
 * (docs/GROWTH.md, slice B) A street town (Steward's own street and lots) is taken on as one Architect region at its first addition: the adoption
 * writes nothing, its receipt names the region, and the library grows it by a revision. Undone: the growth (undoRevision), then the adoption (un-adopt:
 * every site back in its own build, nothing removed), then the founding build as before; the ground as before the town at the end.
 */
async function adoptFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 33000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  // Steward's own street for the first build (a street town, as every settlement built before regions)
  await cmd(`/steward dev layout ${id} legacy`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Old Street Town';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} an old street town`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('adopt', 'no card');
  await closeScreens();
  await clearAnimals();
  const st0 = settlements().find((x) => x.id === id);
  const claim = st0.claim;
  const box = [claim.centerX - claim.radius - 16, 40, claim.centerZ - claim.radius - 16, claim.centerX + claim.radius + 16, 160, claim.centerZ + claim.radius + 16];
  const snap0 = path.join(ROOT, 'artifacts', 'e2e', `adopt-before-${id}.snap.gz`);
  await dev.call('dev.region.hash', { box, mode: 'snap', file: snap0 }, { timeoutMs: 1_800_000 });
  from = logSize();
  await cmd(`/steward start ${id} 4 30`);
  const town = await buildThrough(id, from, 'adopt town');
  if (!town) return;
  const founding = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  if (!founding || /^region:/.test(founding.siteGroup ?? '')) return fail('adopt', `not a street town: ${founding?.siteGroup}`);
  // the next addition may take the town on as a region
  await cmd(`/steward dev layout ${id} plan`);
  const snap1 = path.join(ROOT, 'artifacts', 'e2e', `adopt-town-${id}.snap.gz`);
  await dev.call('dev.region.hash', { box, mode: 'snap', file: snap1 }, { timeoutMs: 1_800_000 });
  await cmd(`/steward dev sim_answer talk ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'talk', 'library.json'), 'utf8')).answer)}`);
  await cmd(`/steward talk ${id} we need a library for the scholars`);
  let lib = null;
  for (let i = 0; i < 60 && !lib; i++) {
    await sleep(1000);
    lib = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key.startsWith('asked_')) ?? null;
  }
  if (!lib) return fail('adopt', 'no library proposal');
  await clearAnimals();
  from = logSize();
  await cmd(`/steward proposals ${id} accept ${lib.key}`);
  const grew = await buildThrough(id, from, 'adopt growth');
  if (!grew) return;
  const log1 = settlements().find((x) => x.id === id)?.log ?? [];
  const receipt = log1.filter((e) => e.kind === 'TOWN_ADOPTED' && /^region:/.test(e.siteGroup ?? '')).at(-1);
  const intent = log1.filter((e) => e.kind === 'TOWN_ADOPTED' && /^adopt:/.test(e.siteGroup ?? '')).at(-1);
  const region = receipt?.siteGroup?.slice('region:'.length);
  const growth = log1.filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const v = region ? await dev.request('dev.region.state', { region }).catch(() => null) : null;
  const rv = v?.view ?? v ?? {};
  const inRegion = (founding.siteIds ?? []).filter((x) => (receipt?.siteIds ?? []).includes(x)).length;
  if (intent && receipt && region && growth?.siteGroup === `revision:${region}:1` && rv.state === 'PLACED' && inRegion > 0) {
    ok('adopt', `the street town taken on as ${region} (${receipt.siteIds.length} sites, ${inRegion} of the founding build's), grown by revision 1 (${growth.siteIds.length} sites)`);
  } else return fail('adopt', `intent ${!!intent}; receipt ${receipt?.siteGroup}; growth ${growth?.siteGroup}; region ${rv.state}`);
  await cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ - claim.radius - 40}`);
  // 1: the growth
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 300 && last?.kind !== 'PROJECT_REMOVED'; i++) { await sleep(1000); last = settlements().find((x) => x.id === id)?.log.at(-1); }
  const d1 = await dev.call('dev.region.hash', { box, mode: 'diff', file: snap1 }, { timeoutMs: 1_800_000 });
  const off1 = (d1.list ?? []).filter((x) => !/^(growth|grazed)$/.test(String(x.class)));
  if (last?.kind === 'PROJECT_REMOVED' && off1.length === 0) ok('adopt growth undo', `${last.text}; the town as before its addition (0 cells differ: the adoption wrote nothing)`);
  else fail('adopt growth undo', `last ${last?.kind}; ${off1.length} cell(s) differ ${JSON.stringify(off1.slice(0, 3))}`);
  // 2: the adoption (un-adopt): every building stays, nothing removed
  const said2 = await cmd(`/steward undo ${id}`);
  let rel = null;
  for (let i = 0; i < 120 && !rel; i++) {
    await sleep(1000);
    rel = (settlements().find((x) => x.id === id)?.log ?? []).find((e) => e.kind === 'TOWN_RELEASED' && e.siteGroup === `region:${region}`) ?? null;
  }
  const sites = (await dev.request('dev.sites.state').catch(() => ({})))?.sites ?? [];
  const list = Array.isArray(sites) ? sites : Object.values(sites);
  // buildings from the sites' state (in no region now); every site, roads included (the sites' state lists buildings), by the dev site command
  const back = (founding.siteIds ?? []).map((sid) => list.find((x) => x.id === sid)).filter(Boolean);
  const standing = [];
  for (const sid of founding.siteIds ?? []) if (/stands/.test(await cmd(`/steward dev site ${sid}`))) standing.push(sid);
  const noneRemoved = !(settlements().find((x) => x.id === id)?.log ?? []).some((e) => e.kind === 'PROJECT_REMOVED' && (e.siteIds ?? []).some((x) => founding.siteIds.includes(x)));
  if (rel && standing.length === founding.siteIds.length && back.every((x) => !x.region) && noneRemoved) ok('adopt release', `${rel.text}; ${standing.length} sites standing, none in a region`);
  else fail('adopt release', `${said2}; released ${!!rel}; ${standing.length} of ${founding.siteIds.length} standing, regions ${JSON.stringify(back.map((x) => x.region))}`);
  // 3: the founding build, as before
  await cmd(`/steward undo ${id}`);
  let gone = null;
  for (let i = 0; i < 300 && !gone; i++) {
    await sleep(1000);
    const e = settlements().find((x) => x.id === id)?.log.at(-1);
    if (e?.kind === 'PROJECT_REMOVED' && founding.siteIds.every((x) => e.siteIds.includes(x))) gone = e;
  }
  const d0 = await dev.call('dev.region.hash', { box, mode: 'diff', file: snap0 }, { timeoutMs: 1_800_000 });
  const off0 = (d0.list ?? []).filter((x) => !/^(growth|grazed)$/.test(String(x.class)));
  if (gone && off0.length === 0) ok('adopt town undo', `${gone.text}; the ground as before the town (0 cells differ)`);
  else fail('adopt town undo', `removed ${!!gone}; ${off0.length} cell(s) differ ${JSON.stringify(off0.slice(0, 3))}`);
}

/**
 * A settlement built from its composed plan (plan v1, docs/COMPOSITION.md section 10): a walled town whose wall waits for Architect. At Start the plan is
 * checked again without the wall (it stays pending), the buildings are designed for the artifact's lots, the village is realised from the approved artifact
 * as one region, and an undo takes it up whole.
 */
async function planBuildFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 15000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Walled Hamlet';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'walled_town.json'), 'utf8')).answer)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little walled market town`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('plan build', 'no card');
  if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('plan build', 'no plan composed');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  const laid = await waitLog(from, /Laying out the village from its plan[^\n]*/, 60_000);
  // off the plan's lots (a player in a lot's box holds its placement, with no time limit), its chunks still loaded
  await cmd('/tp @p ~ ~ ~90');
  const without = await waitLog(from, /Built now without ([^\n]*)/, 60_000);
  const built = await buildThrough(id, from, 'plan build');
  if (!built) return;
  const st = settlements().find((x) => x.id === id);
  const placedOp = (st?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const region = placedOp?.siteGroup;
  const plans = (() => { try { return JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-plans.json'), 'utf8')).plans ?? {}; } catch { return {}; } })();
  const cellarWaits = plans[id]?.pending?.some((x) => x.part === 'secret_cellar');
  if (laid && without && /^region:/.test(region ?? '') && built.placed > 0 && cellarWaits) {
    ok('plan build', `${built.steps.join(' > ')} > ${built.placed} placed from the approved plan, its wall with it (${region}); ${without[0]}; the cellar still waits`);
  } else return fail('plan build', `laid out ${!!laid}, without ${without ? without[1] : 'none'}, region ${region}, placed ${built.placed}, cellar pending ${cellarWaits}`);
  await cmd(`/tp @p ~ ~ ~90`);
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 60 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  if (last?.kind === 'PROJECT_REMOVED' && last.siteIds.length === placedOp.siteIds.length) ok('plan build undo', `${last.text} (${last.siteIds.length} sites)`);
  else fail('plan build undo', `last op ${last?.kind} (${last?.siteIds?.length} of ${placedOp.siteIds.length} sites)`);
}

/**
 * (Architect GH; Noah, 2026-11-04) A grid that doesn't fit is refused NO_ROOM with the street layout as its wouldFit: walled_town as Claude wrote it in
 * the gatehouse round (plans-gc, layout grid) is composed, the fragment applied with no Claude round, and the player told which buildings it leaves out.
 */
async function streetHintFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 16500 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Grid Town';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  const answer = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'strange-asks', 'plans-gc', 'walled_town.json'), 'utf8')).answer;
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(answer)}`);
  const from = logSize();
  await cmd(`/steward describe ${id} a walled town laid out on a grid`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('street hint', 'no card');
  const kept = await waitLog(from, new RegExp(`plan for ${id}: ([A-Z_]+) after (\\d+) repair`), 240_000);
  await closeScreens();
  if (!kept) return fail('street hint', 'no plan composed');
  const applied = logSince(from).match(new RegExp(`plan for ${id}: \\[town\\] as street, Architect's wouldFit applied \\(left out: \\[([^\\]]*)\\]\\)`));
  const told = logSince(from).match(/\[Grid Town\] Left out for room[^\n]*/);
  const plans = (() => { try { return JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-plans.json'), 'utf8')).plans ?? {}; } catch { return {}; } })();
  const town = (() => { try { return JSON.parse(plans[id]?.plan ?? '{}').zones?.find((z) => z.id === 'town'); } catch { return null; } })();
  const leftOut = applied?.[1] ? applied[1].split(', ') : [];
  if (!applied) {
    const refused = (logSince(from).match(/Didn't fit here[^\n]*/) ?? ['no refusal said'])[0];
    return fail('street hint', `no wouldFit applied (kept ${kept[1]} after ${kept[2]} repairs; ${refused})`);
  }
  if (town?.program?.layout === 'street' && (leftOut.length ? told && leftOut.every((l) => told[0].includes(l)) : !told) && kept[2] === '0') {
    ok('street hint', `the grid refused, its street fragment applied with no Claude round: kept ${kept[1]}, ${town.program.lots?.length} lots; ${told ? told[0] : 'every lot placed'}`);
  } else fail('street hint', `kept ${kept[1]} after ${kept[2]} repairs; town layout ${town?.program?.layout}; left out [${leftOut}]; told ${told ? told[0] : 'nothing'}`);
}

/**
 * Districts in a plan (Architect D1, through plan v1): a town of two quarters, a desert side and a jungle side, each its own street of lots, built from
 * its approved plan as one region, and undone whole.
 */
async function districtsPlanFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 24000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Two Sides';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'two_sides.json'), 'utf8')).answer)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a town with a desert side and a jungle side`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('districts plan', 'no card');
  if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('districts plan', 'no plan composed');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  const laid = await waitLog(from, /Laying out the village from its plan[^\n]*/, 60_000);
  await cmd('/tp @p ~ ~ ~90');
  const built = await buildThrough(id, from, 'districts plan');
  if (!built) return;
  const placed = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const lots = (placed?.changes ?? []).map((c) => c.lot).filter(Boolean);
  // the jungle side's street runs north-south: its lots face east or west (logged as the plan's fronts), and both quarters stand
  const frontsLine = (logSince(from).match(/plan of [^\n]*fronts (\{[^\n]*\})/) ?? [])[1] ?? '';
  const eastWest = /east|west/.test(frontsLine);
  const quarters = ['trader', 'herbalist'].every((t) => lots.some((l) => l.includes(t)));
  if (laid && /^region:/.test(placed?.siteGroup ?? '') && eastWest && quarters) ok('districts plan', `${built.placed} placed in two quarters from the approved plan (${placed.siteGroup}): ${lots.join(', ')}; fronts ${frontsLine}`);
  else return fail('districts plan', `laid out ${!!laid}, region ${placed?.siteGroup}, placed ${built.placed}, lots ${lots.join(', ')}, fronts ${frontsLine || 'none'}`);
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 60 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  if (last?.kind === 'PROJECT_REMOVED' && last.siteIds.length === placed.siteIds.length) ok('districts plan undo', `${last.text} (${last.siteIds.length} sites)`);
  else fail('districts plan undo', `last op ${last?.kind} (${last?.siteIds?.length} of ${placed.siteIds.length} sites)`);
}

/**
 * Water (Architect H): a farm hamlet whose farm has its little pond (farmPond), built from its plan. The region is placed only once the pond has settled
 * (its fluids stage), and an undo drains the pond before it takes the rest up.
 */
async function farmPondFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 21000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Pond Hamlet';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'farm_pond.json'), 'utf8')).answer)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a farming hamlet with a little pond`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('farm pond', 'no card');
  if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('farm pond', 'no plan composed');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  await waitLog(from, /Laying out the village from its plan[^\n]*/, 60_000);
  await cmd('/tp @p ~ ~ ~90');
  const built = await buildThrough(id, from, 'farm pond');
  if (!built) return;
  const placed = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const rg = (placed?.siteGroup ?? '').replace(/^region:/, '');
  const fluids = logSince(from).match(new RegExp(`stage fluids[^\n]*`, 'g')) ?? [];
  if (/^rg/.test(rg) && fluids.some((l) => /placed/.test(l))) ok('farm pond', `${built.placed} placed (${placed.siteGroup}), its pond settled: ${fluids.at(-1).slice(0, 90)}`);
  else return fail('farm pond', `region ${placed?.siteGroup}; fluids stage ${fluids.length ? fluids.join(' | ').slice(0, 200) : 'none'}`);
  from = logSize();
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 90 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  const drained = /drain/i.test(logSince(from));
  if (last?.kind === 'PROJECT_REMOVED' && last.siteIds.length === placed.siteIds.length) ok('farm pond undo', `${last.text}${drained ? '; the pond drained first' : ''}`);
  else fail('farm pond undo', `last op ${last?.kind} (${last?.siteIds?.length} of ${placed.siteIds.length}); ${logSince(from).split('\n').filter((l) => /CHAT|drain|fluid/i.test(l)).slice(-3).join(' | ').slice(0, 300)}`);
}

/**
 * A material rule (Architect VOC-c): a town with no wood at all (a required rule.palette). The stub's designs are timber, so the steward's pre-check
 * (checkPalette) leaves the lots that break it as pads rather than the realise refusing the whole village; it is placed, and undone.
 */
async function paletteFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 33000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Stone Town';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'no_wood.json'), 'utf8')).answer)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a town with no wood at all`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('palette', 'no card');
  if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('palette', 'no plan composed');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  await waitLog(from, /Laying out the village from its plan[^\n]*/, 60_000);
  await cmd('/tp @p ~ ~ ~90');
  const built = await buildThrough(id, from, 'palette');
  if (!built) return;
  // the steward's pre-check (Architect's checkPalette): a design that breaks the required rule is left a pad, rather than the village refused at the realise
  const padded = logSince(from).match(/(\S+) is left a pad: its design breaks the plan's rule on wood[^\n]*/);
  const placed = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  if (padded && /^region:/.test(placed?.siteGroup ?? '')) ok('palette', `${padded[0].slice(0, 140)}; the village placed (${placed.siteGroup}, ${built.placed} buildings)`);
  else return fail('palette', `pre-check ${padded ? padded[0] : 'said nothing'}; region ${placed?.siteGroup}; placed ${built.placed}`);
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 60 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  if (last?.kind === 'PROJECT_REMOVED') ok('palette undo', last.text);
  else fail('palette undo', `last op ${last?.kind}`);
}

/**
 * Natural forms (Architect F3): a hamlet under a giant oak, with a village pond grown with coral, built from its plan through checkPlan and realiseApproved.
 * The claim is snapshotted first; after the build a leaf of the oak is set to cobblestone (a player's edit), and the undo must give back the claim exactly
 * but for that one cell, which stays the player's.
 */
async function groveFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 36000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Oak Hamlet';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'grove_pond.json'), 'utf8')).answer)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a hamlet under a great oak, with a coral pond`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('grove', 'no card');
  const composed = await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after[^\n]*`), 240_000);
  if (!composed) return fail('grove', 'no plan composed');
  const answer = logSince(from).match(new RegExp(`plan for ${id}: [^\n]*`))?.[0] ?? '';
  if (!/ 0 pending, 0 unfit/.test(answer)) return fail('grove', `the giant oak, the pond and its coral didn't all fit: ${answer.slice(0, 160)}`);
  await closeScreens();
  await clearAnimals();
  const st = settlements().find((x) => x.id === id);
  const r = st.claim.radius;
  // the e2e world is flat with its surface at y 64; a giant oak stands up to 64 over it
  const box = [st.claim.centerX - r, 40, st.claim.centerZ - r, st.claim.centerX + r, 160, st.claim.centerZ + r];
  const snap = path.join(ROOT, 'artifacts', 'e2e', `grove-${id}.snap.gz`);
  fs.mkdirSync(path.dirname(snap), { recursive: true });
  const groundBox = [box[0], 54, box[2], box[3], 64, box[5]], crownBox = [box[0], 96, box[2], box[3], box[4], box[5]];
  const snapGround = snap.replace('.snap', '-ground.snap'), snapCrown = snap.replace('.snap', '-crown.snap');
  await dev.call('dev.region.hash', { box, mode: 'snap', file: snap }, { timeoutMs: 1_800_000 });
  await dev.call('dev.region.hash', { box: groundBox, mode: 'snap', file: snapGround }, { timeoutMs: 1_800_000 });
  await dev.call('dev.region.hash', { box: crownBox, mode: 'snap', file: snapCrown }, { timeoutMs: 1_800_000 });
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  await waitLog(from, /Laying out the village from its plan[^\n]*/, 60_000);
  await cmd('/tp @p ~ ~ ~90');
  const built = await buildThrough(id, from, 'grove');
  if (!built) return;
  // animals from chunks the build loaded since the first clear: a sheep eats grass (grass to dirt) at cells the plan never wrote
  await clearAnimals();
  const placed = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  // the oak (its logs and persistent leaves), the pond's coral in the ground layer, and a leaf high in its crown: the diff lists at most 2,000 cells
  const trees = await dev.call('dev.region.logs', { box }, { timeoutMs: 300_000 });
  const ground = await dev.call('dev.region.hash', { box: groundBox, mode: 'diff', file: snapGround }, { timeoutMs: 1_800_000 });
  const coral = (ground.list ?? []).filter((m) => /coral|sea_pickle/.test(String(m.now)));
  const crown = await dev.call('dev.region.hash', { box: crownBox, mode: 'diff', file: snapCrown }, { timeoutMs: 1_800_000 });
  const leaves = (crown.list ?? []).filter((m) => /_leaves\[/.test(String(m.now)) && /persistent=true/.test(String(m.now)));
  // the oak's columns (its crown seen from above): a world's change is classed only outside them (Architect's rule: never at a cell the plan wrote)
  // (its logs: F3 writes the ground only under the logs standing on it; grass under the canopy is never written, and sheep graze there)
  const oakCols = new Set((crown.list ?? []).filter((m) => /_log\[|_log$|_wood/.test(String(m.now))).map((m) => { const [x, , z] = String(m.pos).split(',').map(Number); return `${x},${z}`; }));
  if (/^region:/.test(placed?.siteGroup ?? '') && trees.logs > 50 && trees.leavesPersistent > 100 && trees.floating === 0 && coral.length > 0 && leaves.length > 0)
    ok('grove', `${built.placed} placed (${placed.siteGroup}): the oak ${trees.logs} logs, ${trees.leavesPersistent} persistent leaves, none floating; ${coral.length} coral cells in the pond`);
  else return fail('grove', `region ${placed?.siteGroup}; logs ${trees.logs}, persistent leaves ${trees.leavesPersistent}, floating ${trees.floating}, coral ${coral.length} (ground diff ${ground.mismatches}), crown leaves ${leaves.length}`);
  // a player's edit: one of the oak's leaves turned to cobblestone (the cell the undo must leave as the player made it)
  const leaf = leaves.sort((a, b) => String(a.pos).localeCompare(String(b.pos)))[Math.floor(leaves.length / 2)];
  const [lx, ly, lz] = String(leaf.pos).split(',').map(Number);
  await cmd(`/setblock ${lx} ${ly} ${lz} minecraft:cobblestone`);
  from = logSize();
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 120 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  if (last?.kind !== 'PROJECT_REMOVED') return fail('grove undo', `last op ${last?.kind}`);
  await clearAnimals();
  await sleep(3000);
  const diff = await dev.call('dev.region.hash', { box, mode: 'diff', file: snap }, { timeoutMs: 1_800_000 });
  const mine = (diff.list ?? []).filter((m) => String(m.pos) === `${lx},${ly},${lz}`);
  const other = (diff.list ?? []).filter((m) => String(m.pos) !== `${lx},${ly},${lz}`);
  // the world's own change (Architect's classes): grass eaten or spread (class growth or grazed) at a column the oak never wrote; any other change fails
  const worlds = other.filter((m) => /^(growth|grazed)$/.test(String(m.class)) && /grass_block|dirt/.test(`${m.was}${m.now}`)
    && !oakCols.has(String(m.pos).split(',').filter((_, i) => i !== 1).join(',')));
  const unexplained = other.length - worlds.length;
  if (diff.mismatches === 1 + worlds.length && unexplained === 0 && mine.length === 1 && /cobblestone/.test(String(mine[0].now)))
    ok('grove undo', `${last.text}; the claim as it was (${box[3] - box[0] + 1}x${box[4] - box[1] + 1}x${box[5] - box[2] + 1}) but the player's cobblestone at ${lx},${ly},${lz}${worlds.length ? ` (and ${worlds.length} grass cell(s) grazed or spread away from the oak, the world's)` : ''}`);
  else fail('grove undo', `player's cell ${mine.length ? mine[0].now : 'not kept'}; ${diff.mismatches} cell(s) differ in all, e.g. ${other.filter((m) => !worlds.includes(m)).slice(0, 4).map((m) => `${m.pos} ${m.was} -> ${m.now} (${m.class}${oakCols.has(String(m.pos).split(',').filter((_, i) => i !== 1).join(',')) ? ', under the oak' : ''})`).join('; ').slice(0, 300)}`);
}

/**
 * Irrigation (Architect IRR): a working wheat farm with a source in every 9x9 of its field (irrigate), built from its plan; its design request carries the
 * field's reserved cells (sources, farmland sides, the air over them), it is placed once the sources settle, and undone with them drained first.
 */
async function irrigationFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 30000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Wheat Farm';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'working_farm.json'), 'utf8')).answer)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a working wheat farm, watered properly`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('irrigation', 'no card');
  if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('irrigation', 'no plan composed');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  await waitLog(from, /Laying out the village from its plan[^\n]*/, 60_000);
  await cmd('/tp @p ~ ~ ~90');
  const built = await buildThrough(id, from, 'irrigation');
  if (!built) return;
  const placed = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const rg = (placed?.siteGroup ?? '').replace(/^region:/, '');
  const fluids = logSince(from).match(new RegExp(`stage fluids[^\n]*`, 'g')) ?? [];
  const reservedLine = (logSince(from).match(/reserved cells for [^\n]*/) ?? [''])[0];
  if (/^rg/.test(rg) && fluids.some((l) => /placed/.test(l)) && /farm_1 \d+/.test(reservedLine)) ok('irrigation', `${built.placed} placed (${placed.siteGroup}), its field's sources settled; ${reservedLine}`);
  else return fail('irrigation', `region ${placed?.siteGroup}; ${reservedLine || 'no reserved cells'}; fluids stage ${fluids.length ? fluids.join(' | ').slice(0, 200) : 'none'}`);
  from = logSize();
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 90 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  const drained = /drain/i.test(logSince(from));
  if (last?.kind === 'PROJECT_REMOVED' && last.siteIds.length === placed.siteIds.length) ok('irrigation undo', `${last.text}${drained ? '; the sources drained first' : ''}`);
  else fail('irrigation undo', `last op ${last?.kind} (${last?.siteIds?.length} of ${placed.siteIds.length}); ${logSince(from).split('\n').filter((l) => /CHAT|drain|fluid/i.test(l)).slice(-3).join(' | ').slice(0, 300)}`);
}

/**
 * Water courses (Architect HY): a market garden with an irrigation ditch along its field (channel.ditch), built from its plan; placed once the ditch has
 * settled, and undone (the ditch drained first, the reverse of its writing).
 */
async function ditchFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 27000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Ditch Hamlet';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'ditch_hamlet.json'), 'utf8')).answer)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a market garden with a ditch along its field`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('ditch', 'no card');
  if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('ditch', 'no plan composed');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  await waitLog(from, /Laying out the village from its plan[^\n]*/, 60_000);
  await cmd('/tp @p ~ ~ ~90');
  const built = await buildThrough(id, from, 'ditch');
  if (!built) return;
  const placed = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const rg = (placed?.siteGroup ?? '').replace(/^region:/, '');
  const fluids = logSince(from).match(new RegExp(`stage fluids[^\n]*`, 'g')) ?? [];
  if (/^rg/.test(rg) && fluids.some((l) => /placed/.test(l))) ok('ditch', `${built.placed} placed (${placed.siteGroup}), its ditch settled: ${fluids.at(-1).slice(0, 90)}`);
  else return fail('ditch', `region ${placed?.siteGroup}; fluids stage ${fluids.length ? fluids.join(' | ').slice(0, 200) : 'none'}`);
  from = logSize();
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 90 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  const drained = /drain/i.test(logSince(from));
  if (last?.kind === 'PROJECT_REMOVED' && last.siteIds.length === placed.siteIds.length) ok('ditch undo', `${last.text}${drained ? '; the ditch drained first' : ''}`);
  else fail('ditch undo', `last op ${last?.kind} (${last?.siteIds?.length} of ${placed.siteIds.length}); ${logSince(from).split('\n').filter((l) => /CHAT|drain|fluid/i.test(l)).slice(-3).join(' | ').slice(0, 300)}`);
}

/**
 * A waiting part offered once Architect builds it (docs/COMPOSITION.md section 11): a market town built from its plan, then a wall kept as waiting from
 * before walls existed (as a plan kept before K1); the steward offers it, Claude fits it round the town as it stands (the scripted answer), Architect builds
 * it as a region of its own, and an undo takes the wall away and leaves the town.
 */
async function wallOfferFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 18000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Market Hamlet';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'strange-asks', 'plans', 'market_town.json'), 'utf8')).answer)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little market town`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('wall offer', 'no card');
  if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('wall offer', 'no plan composed');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  await waitLog(from, /Laying out the village from its plan[^\n]*/, 60_000);
  await cmd('/tp @p ~ ~ ~90');
  const built = await buildThrough(id, from, 'wall offer');
  if (!built) return;
  const town = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  if (!/^region:/.test(town?.siteGroup ?? '')) return fail('wall offer', `the town was not built from its plan: ${town?.siteGroup}`);
  // a wall kept as waiting from before walls existed (no shape: Claude gives it one now)
  await cmd(`/steward dev pend ${id} connector.wall {"id":"town_wall","kind":"wall","required":true,"label":"the town wall","notes":"A stone wall round the town."}`);
  const offered = await cmd(`/steward dev offer_parts ${id}`);
  const offer = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key === 'part_town_wall');
  if (!offer) return fail('wall offer', `not offered: ${offered}`);
  ok('wall offer', `${offer.title}: ${offer.why.slice(0, 90)}`);
  await cmd(`/steward dev sim_answer part ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'wall_part.json'), 'utf8')).answer)}`);
  // the world before the wall: what the wall and a district through its gate must leave behind, both gone
  const cl = settlements().find((x) => x.id === id).claim;
  const before = { box: [cl.centerX - cl.radius - 40, 40, cl.centerZ - cl.radius - 40, cl.centerX + cl.radius + 120, 140, cl.centerZ + cl.radius + 40], file: path.join(ROOT, 'artifacts', 'e2e', `before-wall-${id}.snap.gz`) };
  fs.mkdirSync(path.dirname(before.file), { recursive: true });
  await clearAnimals();
  await dev.call('dev.region.hash', { box: before.box, mode: 'snap', file: before.file }, { timeoutMs: 1_800_000 });
  await cmd(`/steward proposals ${id} accept part_town_wall`);
  let wall = null;
  for (let i = 0; i < 180 && !wall; i++) {
    await sleep(1000);
    wall = (settlements().find((x) => x.id === id)?.log ?? []).find((e) => e.kind === 'PROJECT_PLACED' && /Built the town wall/.test(e.text)) ?? null;
  }
  const plans = (() => { try { return JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-plans.json'), 'utf8')).plans ?? {}; } catch { return {}; } })();
  const stillWaits = plans[id]?.pending?.some((x) => x.part === 'town_wall');
  if (wall && /^region:/.test(wall.siteGroup) && wall.siteGroup !== town.siteGroup && !stillWaits) ok('wall built', `${wall.text}: ${wall.siteIds.length} sites in ${wall.siteGroup} (the town is ${town.siteGroup}); no longer waits`);
  else return fail('wall built', `history ${JSON.stringify(wall)?.slice(0, 160)}; still waits ${stillWaits}; ${logSince(from).split('\n').filter((l) => /CHAT/.test(l)).slice(-3).join(' | ')}`);
  const townRegion = town.siteGroup.replace(/^region:/, '');
  const wallRegion = wall.siteGroup.replace(/^region:/, '');
  // (Architect GJ, Steward's RC) a district joins the walled town through its gate; the wall undone first leaves it standing, its gate gone
  const gj = await gateJoinFlow(id, townRegion, wallRegion, before);
  if (!gj) return;
  // the wall undone first, through Architect (Steward's undo takes the latest build, here the district): the town stands, then the district goes
  await gj.afterWallUndo(async () => {
    const rm = await dev.request('dev.region.remove', { region: wallRegion, force: true }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
    const gone = await regionUntil(wallRegion, ['REMOVED'], 120_000);
    const removed = rm?.removed === true || (gone?.view?.state ?? gone?.state) === 'REMOVED';
    const townStands = /stands/.test(await cmd(`/steward dev site ${town.siteIds[0]}`));
    if (removed && townStands) ok('wall undo', `${wallRegion} removed; the town stands`);
    else fail('wall undo', `removal ${JSON.stringify(rm).slice(0, 200)}; town stands ${townStands}`);
  });
}

/**
 * (Architect GJ, Steward's RC: docs/HANDOFF-GJ.md in architect-mc) A district beside the walled town joins it through the wall's gate. `checkBeside` shows
 * `via`; the district first: planned and realised under Steward's keys and removed, the town and the wall as before it. Then the gate walled up after the
 * plan: ARTIFACT_STALE naming it, nothing written; opened, planned again and realised under `.vrealise2`; found by `realiseByKey` after a restart. Returns
 * the wall-first order: the district stands dangling with its gate gone, then goes, and the world is as before both.
 */
async function gateJoinFlow(id, town, wallRegion, before) {
  const label = 'gate join';
  const owner = `steward_mc:settlement/${id}`;
  const v = await besideAsk(id, town);
  const via = (v?.land?.parts ?? []).find((q) => q.kind === 'join')?.via ?? null;
  if (v?.fits && String(via).startsWith(`${wallRegion}:`)) ok('beside a walled town', `fits through the gate: via ${via}, join ${v.join?.kind} (${v.join?.step})`);
  else { fail('beside a walled town', JSON.stringify(v).slice(0, 1500)); return null; }
  const ext = (b) => ({ 'steward_mc:district': 'dst_gj', 'steward_mc:build': b });
  const c = settlements().find((x) => x.id === id).claim;
  const { box } = before;
  const snap = path.join(ROOT, 'artifacts', 'e2e', `gate-${id}.snap.gz`);
  const away = () => cmd(`/tp @p ${c.centerX} ~ ${c.centerZ - c.radius - 60}`);
  const stateOf = async (r) => { const s = await dev.request('dev.region.state', { region: r }).catch(() => null); return s?.view ?? s; };
  const unchanged = async (file = snap) => {
    await clearAnimals();
    const d = await dev.call('dev.region.hash', { box, mode: 'diff', file }, { timeoutMs: 1_800_000 });
    return (d.list ?? []).filter((m) => !/^(growth|grazed)$/.test(String(m.class)));
  };
  const remove = async (r) => {
    const rm = await dev.request('dev.region.remove', { region: r, force: true }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
    const gone = await regionUntil(r, ['REMOVED'], 120_000);
    return rm?.removed === true || (gone?.view?.state ?? gone?.state) === 'REMOVED';
  };
  await away();
  await clearAnimals();
  await dev.call('dev.region.hash', { box, mode: 'snap', file: snap }, { timeoutMs: 1_800_000 });
  // the district first
  const b1 = `e2egj${Date.now() % 100000}`;
  const p1 = await besideAsk(id, town, { check: false, opKey: `steward.${b1}.vplan`, ext: ext(b1) });
  if (!p1?.planId) { fail(label, `not planned: ${JSON.stringify(p1).slice(0, 260)}`); return null; }
  await away();
  await clearAnimals();
  const r1 = await dev.request('dev.region.realise', { planId: p1.planId, opKey: `steward.${b1}.vrealise`, ext: ext(b1) }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  const placed = r1?.region ? await regionUntil(r1.region, ['PLACED', 'PARTIAL', 'FAILED']) : null;
  if ((placed?.view?.state ?? placed?.state) !== 'PLACED') { fail(label, `${JSON.stringify(r1).slice(0, 240)}; ${placed?.view?.state ?? placed?.state}`); return null; }
  const joined = planJoin(p1.planId);
  if (!(await remove(r1.region))) { fail(label, `${r1.region} not removed`); return null; }
  const off = await unchanged();
  if (off.length === 0) ok('gate join, district removed first', `${r1.region} through ${joined?.via ?? via} (junction ${joined?.junction?.kind}), removed: the town and the wall as before`);
  else fail('gate join, district removed first', `${off.length} cell(s) differ, e.g. ${off.slice(0, 4).map((m) => `${m.pos} ${m.was} -> ${m.now} (${m.class})`).join('; ').slice(0, 300)}`);
  // the gate walled up after the plan (none of Steward's tags in this one's ext: it must outlive a restart, and Steward takes up a village or district region
  // no build of its holds)
  const b2 = `${b1}b`;
  const kept = { 'e2e:gate': b2 };
  const p2 = await besideAsk(id, town, { check: false, opKey: `steward.${b2}.vplan`, ext: kept });
  if (!p2?.planId) { fail('gate walled up', `not planned: ${JSON.stringify(p2).slice(0, 260)}`); return null; }
  const gt = planIr(p2.planId).reach.gate;
  const xs = gt.cells.flatMap((q) => [q[0], q[2]]), zs = gt.cells.flatMap((q) => [q[1], q[3]]);
  const [bx0, bz0, bx1, bz1] = [Math.min(...xs), Math.min(...zs), Math.max(...xs), Math.max(...zs)];
  const across = (block) => cmd(`/fill ${bx0} ${gt.y + 1} ${bz0} ${bx1} ${gt.y + 2} ${bz1} ${block}`);
  await across('minecraft:cobblestone');
  await away();
  const stale = await dev.request('dev.region.realise', { planId: p2.planId, opKey: `steward.${b2}.vrealise`, ext: kept }, { timeoutMs: 300_000 }).catch((e) => ({ refused: String(e) }));
  const staleText = typeof stale?.refused === 'string' ? stale.refused : JSON.stringify(stale?.refused ?? '');
  if (!stale?.region && /ARTIFACT_STALE/.test(staleText) && staleText.includes(`gate ${gt.id}`)) ok('gate walled up', staleText.slice(0, 160));
  else { fail('gate walled up', JSON.stringify(stale).slice(0, 260)); return null; }
  await across('minecraft:air');
  const p3 = await besideAsk(id, town, { check: false, opKey: `steward.${b2}.vplan2`, ext: kept });
  if (!p3?.planId) { fail('gate open again', `not planned: ${JSON.stringify(p3).slice(0, 260)}`); return null; }
  await away();
  await clearAnimals();
  const r3 = await dev.request('dev.region.realise', { planId: p3.planId, opKey: `steward.${b2}.vrealise2`, ext: kept }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  const district = r3?.region;
  const st3 = district ? await regionUntil(district, ['PLACED', 'PARTIAL', 'FAILED']) : null;
  if ((st3?.view?.state ?? st3?.state) !== 'PLACED') { fail('gate open again', `${JSON.stringify(r3).slice(0, 240)}; ${st3?.view?.state ?? st3?.state}`); return null; }
  await quit();
  launch();
  await connect();
  const rk = await dev.request('dev.region.realiseByKey', { owner, opKey: `steward.${b2}.vrealise2` }, { timeoutMs: 10_000 }).catch((e) => ({ error: String(e) }));
  if (rk?.region?.id === district) ok('gate open again', `${district} under steward.${b2}.vrealise2, found by its key after a restart`);
  else { fail('gate open again', `realiseByKey ${JSON.stringify(rk).slice(0, 200)}`); return null; }
  await away();
  return {
    // the wall undone first (`undoWall`, the caller's): the ring and its gate go, the district stays dangling; then the district
    async afterWallUndo(undoWall) {
      await away();
      await undoWall();
      const after = await stateOf(district);
      const e = after?.ext ?? {};
      const beside = e['architect_mc:beside'] ?? {};
      if (after?.state === 'PLACED' && beside.dangling === true && beside.gateRemoved === wallRegion && e['architect_mc:land']?.standingRemoved != null) ok('gate join, wall undone first', `${district} stands, dangling, gateRemoved ${beside.gateRemoved}`);
      else fail('gate join, wall undone first', `${after?.state}; ${JSON.stringify(beside).slice(0, 200)}; land.standingRemoved ${e['architect_mc:land']?.standingRemoved}`);
      await away();
      const gone = await remove(district);
      const diff = gone ? await unchanged(before.file) : [];
      if (gone && diff.length === 0) ok('gate join, then the district', `${district} and ${wallRegion} gone: the world before both`);
      else fail('gate join, then the district', `removed ${gone}; ${diff.length} cell(s) differ, e.g. ${diff.slice(0, 4).map((m) => `${m.pos} ${m.was} -> ${m.now} (${m.class})`).join('; ').slice(0, 300)}`);
    },
  };
}

/**
 * Drives one build through its decisions until it is built: approves the style bible, the massings and the placement as each comes (Proposals level), with
 * a restart at the massings when asked. Returns the steps seen, or null when it stalls.
 */
async function buildThrough(id, from, label, restartAtMassings = false, onStep = null, ms = 720_000) {
  const steps = [];
  let lastApprove = '';
  // past Architect's 600 s wait for a blocked item (so a blocked item fails and is reported, rather than the check giving up first)
  const end = Date.now() + ms;
  // nothing in the lots' way: an animal in a box holds its item up for Architect's whole wait (C30)
  await clearAnimals();
  let at = from;
  while (Date.now() < end) {
    // (an animal or the player in a lot: the run's herder moves them out)
    const since = logSince(at);
    const built = since.match(/is built(?: in part)?: (\d+) buildings placed/);
    if (built) return { steps, placed: Number(built[1]) };
    // every lot left a pad (a material rule's pre-check): the village's street and square stand, no building
    if (/No building of [^\n]* was placed/.test(since)) return { steps, placed: 0 };
    const stopped = since.match(/The build stopped[^\n]*|No dry, flat room[^\n]*|No room left[^\n]*|Survey failed[^\n]*|Cancelled [^\n]*|The design group failed[^\n]*/);
    if (stopped) {
      fail(label, stopped[0]);
      return null;
    }
    // (a held stage: the land changed under the plan since its check; the player's word builds it as planned)
    const m = since.match(/Style bible ready|massings are ready|Approve to place|Architect holds the \S+ stage/);
    if (!m) {
      await sleep(1000);
      continue;
    }
    steps.push(m[0]);
    if (m[0] === 'massings are ready' && restartAtMassings) {
      if (onStep) await onStep('restart-before');
      await quit();
      launch();
      await connect();
      if (onStep) await onStep('restart-after');
      if (await waitLog(0, new RegExp(`resume ${id}: [^\\n]*-> REREAD_GROUP`), 30_000)) ok('restart mid-build', label);
      else fail('restart mid-build', `${label}: no re-read`);
      restartAtMassings = false;
    }
    if (onStep) await onStep(m[0]);
    at = logSize();
    lastApprove = await cmd(`/steward approve ${id}`);
  }
  // what the build was really doing: its saved phase (the last notice is not the phase) and what the last approve answered
  let phase = 'no saved build';
  try {
    const b = JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-builds.json'), 'utf8')).builds.find((x) => x.settlementId === id);
    if (b) phase = b.state?.phase ?? 'unknown';
  } catch { /* no builds file */ }
  fail(label, `stalled after ${steps.join(', ') || 'nothing'}; saved phase ${phase}; last approve: ${lastApprove || 'none'}`);
  return null;
}

/**
 * Phase 4, Supplied (Architect 0.20's stockpile): in a world with Architect's survival toggle on, a new settlement is Supplied; its build makes the
 * settlement's stockpile, the steward names what a waiting build needs, the builds finish from what goes into the stockpile, and an undo returns to it what
 * they consumed. The toggle is turned off again whatever happens.
 */
async function survivalFlow() {
  await cmd('/architect survival on');
  try {
    const n = settlements().length;
    await cmd(`/spreadplayers ${(n + 1) * 900} 9000 0 1 false @p`);
    await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
    await cmd('/steward claim');
    const id = settlements().at(-1).id;
    await closeScreens();
    const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
    card.name = 'Supply Hamlet';
    await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
    let from = logSize();
    await cmd(`/steward describe ${id} a little hamlet`);
    if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('supplied', 'no card');
    await closeScreens();
    await clearAnimals();
    const diff = settlements().find((x) => x.id === id)?.difficulty;
    if (diff !== 'SUPPLIED') return fail('supplied', `difficulty ${diff} in a survival world`);
    ok('supplied', `${id} is Supplied in a survival world`);
    // off the lots and the street: a player in a box holds its item up (C30); near enough that the chunks stay loaded
    const claim = settlements().find((x) => x.id === id).claim;
    await cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ + 60}`);
    from = logSize();
    await cmd(`/steward start ${id} 2 30`);
    const built = await buildThrough(id, from, 'supplied build');
    if (!built) return;
    if (built.placed < 2) return fail('supplied build', `${built.placed} of 2 buildings placed`);
    const ledger = async () => {
      const r = await cmd(`/steward dev stockpile ${id}`);
      const m = r.match(/stockpile \S+ (\{.*\})/);
      return m ? JSON.parse(m[1]) : null;
    };
    const units = (o) => Object.values(o ?? {}).reduce((a, b) => a + b, 0);
    let sp = await ledger();
    if (!sp || sp.crates < 1) return fail('stockpile', 'no stockpile after the build was placed');
    ok('stockpile', `${sp.crates} crate; the build needs ${units(sp.need)} units of ${Object.keys(sp.need).length} kinds`);
    // the bill said before placing (Architect's full bill per lot, and the street's) matches what the stockpile then owes
    const bill = logSince(from).match(/From materials it needs (about )?(\d+) items[^\n]*/);
    const owed = units(sp.outstanding);
    if (bill && Math.abs(Number(bill[2]) - owed) <= Math.max(5, owed * 0.05)) ok('bill', `${bill[0].slice(0, 160)} (owed ${owed})`);
    else fail('bill', bill ? `said ${bill[2]}, owed ${owed}` : 'no bill said before placing');
    // the steward names what a waiting build needs (Architect's waiting status, after 100 starved ticks)
    const asked = await waitLog(from, /waits for materials: [^\n]*/, 90_000);
    if (asked) ok('material request', asked[0]);
    else fail('material request', 'the steward did not say what a waiting build needs');
    // fill the stockpile with what is still needed (as much as fits; the builds draw it down), until every site stands
    const owner = `steward_mc:settlement/${id}`;
    const deadline = Date.now() + 600_000;
    let done = false;
    while (Date.now() < deadline) {
      sp = await ledger();
      const sites = sp?.sites ?? [];
      done = sites.length > 0 && sites.every((x) => x.state === 'BUILT');
      if (done) break;
      if (Object.keys(sp?.need ?? {}).length) await dev.request('dev.crate.insert', { site: `stockpile:${owner}`, items: sp.need }).catch(() => null);
      await sleep(3000);
    }
    if (!done) return fail('supplied build', `not all built: ${JSON.stringify(sp?.sites?.map((x) => x.state))}, still needed ${units(sp?.need)}`);
    ok('supplied build', `${sp.sites.length} sites built from the stockpile: ${units(sp.delivered)} delivered, ${units(sp.consumed)} consumed`);
    // undo: what the builds consumed comes back into the stockpile (what does not fit is dropped and counted as overflow); off the street and lots first
    // (Architect holds a removal while a player stands in what it removes), near enough that their chunks stay loaded
    const here = settlements().find((x) => x.id === id).claim;
    await cmd(`/tp @p ${here.centerX} ~ ${here.centerZ + 60}`);
    const before = sp;
    from = logSize();
    await cmd(`/steward undo ${id}`);
    // the undo's own lines go to the command's sender after the command returned (the dev bridge has stopped listening by then): read the history
    let last = null;
    for (let i = 0; i < 300 && last?.kind !== 'PROJECT_REMOVED'; i++) {
      await sleep(2000);
      last = settlements().find((x) => x.id === id)?.log.at(-1);
    }
    const undone = last?.kind === 'PROJECT_REMOVED' ? [`Undone: ${last.text}`] : null;
    sp = await ledger();
    const off = [];
    for (const [item, used] of Object.entries(before.consumed)) {
      const back = (sp?.returned?.[item] ?? 0) + (sp?.refundOverflow?.[item] ?? 0) - (before.returned?.[item] ?? 0) - (before.refundOverflow?.[item] ?? 0);
      if (back !== used) off.push(`${item} ${back}/${used}`);
    }
    if (undone && /Undone/.test(undone[0]) && off.length === 0) ok('refund', `${undone[0]}; every consumed unit came back (${units(before.consumed)})`);
    else fail('refund', `${undone ? undone[0] : 'no undo'}; refunds off: ${off.slice(0, 8).join(', ')}`);
  } finally {
    await cmd('/architect survival off');
  }
}

/**
 * Plan v1 for a Supplied settlement (Architect RS; docs/COMPOSITION.md section 12): in a survival world a walled town's composed plan is checked in survival
 * (its bill said), its stockpile made first, and realised from the artifact in construction mode. Half of what it needs is fed, the steward names what waits
 * (a lot or a plan part, the wall), then the rest until Architect says it is done (the steward says so); undone, every consumed unit comes back.
 */
async function suppliedPlanFlow() {
  // Architect builds a region from materials only in a survival world: its toggle on and the world's default mode not creative (the player stays creative)
  await cmd('/architect survival on');
  await cmd('/defaultgamemode survival');
  await cmd('/gamemode creative @p');
  try {
    const n = settlements().length;
    await cmd(`/spreadplayers ${(n + 1) * 900} 39000 0 1 false @p`);
    await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
    await clearAnimals();
    await cmd('/steward claim');
    const id = settlements().at(-1).id;
    await closeScreens();
    await cmd(`/steward expand ${id}`);
    const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
    card.name = 'Supply Walls';
    await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
    await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'walled_town.json'), 'utf8')).answer)}`);
    let from = logSize();
    await cmd(`/steward describe ${id} a little walled market town`);
    if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('supplied plan', 'no card');
    if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('supplied plan', 'no plan composed');
    const diff = settlements().find((x) => x.id === id)?.difficulty;
    if (diff !== 'SUPPLIED') return fail('supplied plan', `difficulty ${diff} in a survival world`);
    await closeScreens();
    await clearAnimals();
    const claim = settlements().find((x) => x.id === id).claim;
    await cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ + 60}`);
    const owner = `steward_mc:settlement/${id}`;
    const ledger = async () => {
      const r = await cmd(`/steward dev stockpile ${id}`);
      const m = r.match(/stockpile \S+ (\{.*\})/);
      return m ? JSON.parse(m[1]) : null;
    };
    const units = (o) => Object.values(o ?? {}).reduce((a, b) => a + b, 0);
    const half = (o) => Object.fromEntries(Object.entries(o ?? {}).map(([k, v]) => [k, Math.max(1, Math.floor(v / 2))]).filter(([, v]) => v > 0));
    from = logSize();
    await cmd(`/steward start ${id} 3 30`);
    // the feeder: half of what is needed once the region's targets ask, then (the steward having named a wait) all of it, until the build is done
    // the feeder: half of what is needed once the region's targets ask (accepted into the stockpile); the build then pauses on the rest (the ledger still
    // owes, 20 s on); then all of it, until the build is done. The steward names a wait once per wait ([name] in the chat), before or after the half
    let stop = false, fedAt = 0, halfAccepted = 0, paused = null, inFlight = null;
    const feeder = (async () => {
      while (!stop) {
        await sleep(3000);
        const sp = await ledger().catch(() => null);
        if (!sp || !Object.keys(sp.need ?? {}).length) continue;
        if (!halfAccepted) {
          const r = await dev.request('dev.crate.insert', { site: `stockpile:${owner}`, items: half(sp.need) }).catch(() => null);
          halfAccepted = units(r?.accepted ?? {});
          if (halfAccepted) fedAt = Date.now();
          continue;
        }
        if (paused == null) {
          if (Date.now() - fedAt < 20_000) continue;
          paused = units(sp.need);
          // (Architect D3) a district beside a town still being built: in flight, refused OVERLAP_BUSY
          const rl = await dev.request('dev.region.list').catch(() => null);
          const building = (rl?.regions ?? []).find((v) => v.owner === owner && v.state === 'PLACING');
          if (building) {
            // asked without holding the feeder up: a dry beside check under load can take minutes, and the build starves while nothing is fed
            inFlight = besideAsk(id, building.id);
            // back off the lots: a player in a box holds its target up
            await cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ + 60}`);
          }
        }
        await dev.request('dev.crate.insert', { site: `stockpile:${owner}`, items: sp.need }).catch(() => null);
      }
    })();
    // (Architect close-out A) a restart at the massings: the build's plan is used again (its keyed pin re-pinned); then, before placing, a pillar on the
    // plan's land makes its artifact stale: re-checked, and realised under the same key
    let sha0 = null, pinBefore = null, pinAfter = null, staled = false;
    // the game's log starts afresh at the restart: what it said before is kept, and the new log read from its start
    let pre = '';
    const said = () => pre + logSince(from);
    const pinsOf = async (sha) => JSON.stringify((await dev.call('dev.coa.artifact', { sha }).catch(() => ({})))?.pins ?? null);
    const onStep = async (step) => {
      if (step === 'restart-before') {
        pre = logSince(from);
        sha0 = pre.match(new RegExp(`plan of ${id}: artifact ([0-9a-f]{64})`))?.[1] ?? null;
        if (sha0) pinBefore = await pinsOf(sha0);
      } else if (step === 'restart-after') {
        from = 0;
        await waitLog(0, new RegExp(`resume ${id}: plan [^\n]* used again`), 60_000);
        if (sha0) pinAfter = await pinsOf(sha0);
      } else if (step === 'Approve to place' && !staled) {
        staled = true;
        // the plan lays out round the stockpile, differently each run: pillars on each side, so one stands on its land
        for (const [dx, dz] of [[12, 0], [-12, 0], [0, 12], [0, -12], [20, 0], [-20, 0], [0, 20], [0, -20]]) {
          await cmd(`/fill ${claim.centerX + dx} 65 ${claim.centerZ + dz} ${claim.centerX + dx} 67 ${claim.centerZ + dz} minecraft:cobblestone`);
        }
      }
    };
    // built from materials as the feeder delivers them: it has taken over 12 minutes on a loaded machine
    const built = await buildThrough(id, from, 'supplied plan', true, onStep, 1_200_000);
    if (!built) { stop = true; await feeder; return; }
    const log = said();
    const bill = log.match(/From materials, the plan's ground, streets and parts need [^\n]*/);
    const placed = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
    const area = (settlements().find((x) => x.id === id)?.lands?.protect ?? []).find((a) => a.label === 'the stockpile (kept clear for plans)');
    if (!area) { stop = true; await feeder; return fail('supplied plan', 'no protected area over the stockpile before the check'); }
    if (bill && /^region:/.test(placed?.siteGroup ?? '') && /Laying out the village from its plan/.test(log)) ok('supplied plan', `${bill[0].slice(0, 150)}; placed from the plan (${placed.siteGroup}), round the stockpile's area ${area.x0},${area.z0}..${area.x1},${area.z1}`);
    else { stop = true; await feeder; return fail('supplied plan', `bill ${bill ? 'said' : 'not said'}; history ${placed?.siteGroup}; ${(log.match(/Laying out[^\n]*/) ?? ['no plan layout'])[0]}`); }
    // fully built from the stockpile: Architect's build is done, and the steward says so
    // close-out A: the keyed pin used again at the restart (its time moved), the stale artifact re-checked and realised under the same key, and every
    // keyed pin of that key ended by the realise
    const realises = [...said().matchAll(new RegExp(`realise ${id}: artifact ([0-9a-f]{64}) under key (\\S+) \\((first|after a re-check)\\)`, 'g'))];
    const retried = realises.find((r) => r[3] === 'after a re-check');
    const keys = new Set(realises.map((r) => r[2]));
    const left = await Promise.all([...new Set(realises.map((r) => r[1]))].map(async (sha) => [sha.slice(0, 8), await pinsOf(sha)]));
    // (dev.coa.artifact lists the pins' owners, not their keys or times: the use at the restart is Steward's log line, the pin there before and after)
    const usedAgain = new RegExp(`resume ${id}: plan [^\\n]* used again`).test(said());
    const pinnedLeft = left.filter(([, p]) => p && p.includes(owner));
    if (sha0 && pinBefore?.includes(owner) && pinAfter?.includes(owner) && usedAgain) ok('plan pinned across the restart', `${sha0.slice(0, 8)} pinned before and after, and used again at the resume`);
    else fail('plan pinned across the restart', `artifact ${sha0}; before ${pinBefore}; after ${pinAfter}; used again ${usedAgain}`);
    if (retried && keys.size === 1 && /\.vrealise$/.test([...keys][0]) && /land under the plan changed/.test(said())) ok('stale retry, one key', `${realises.length} realises under ${[...keys][0]}, the second after a re-check`);
    else fail('stale retry, one key', `${realises.map((r) => `${r[1].slice(0, 8)} ${r[2]} ${r[3]}`).join('; ') || 'no realise logged'}`);
    if (pinnedLeft.length === 0 && left.length) ok('pins let go', `${left.map(([s8]) => s8).join(', ')}: no pin of this settlement left once realised`);
    else fail('pins let go', `still pinned: ${pinnedLeft.map(([s8, p]) => `${s8} ${p}`).join('; ')}`);
    const stageSaid = said().match(/\] The [^\n]*? waits for [^\n]*? to be built\.[^\n]*/);
    if (stageSaid) ok('stage wait said', stageSaid[0].slice(0, 160));
    const doneSaid = await waitLog(from, new RegExp(`${'Supply Walls'} is built: everything its plan laid out stands`), 600_000);
    stop = true;
    await feeder;
    const sp = await ledger();
    const asked = said().match(/\[Supply Walls\] The [^\n]*? waits for materials: [^\n]*/);
    if (asked && halfAccepted > 0 && paused > 0) ok('supplied plan wait', `${halfAccepted} units fed, then paused owing ${paused}; ${asked[0].slice(0, 160)}`);
    else fail('supplied plan wait', `named ${asked ? 'yes' : 'no'}; half accepted ${halfAccepted}; owing 20 s after it ${paused}`);
    if (doneSaid) ok('supplied plan built', `${units(sp?.delivered)} delivered, ${units(sp?.consumed)} consumed`);
    else return fail('supplied plan built', `not done in 10 min; still needed ${units(sp?.need)}`);
    // (Architect D3) the district beside it: refused while the town was in flight; once the town is built from materials, the check fits
    const flight = inFlight ? await inFlight : null;
    if (flight && !flight.fits && /OVERLAP_BUSY/.test(`${flight.reason} ${flight.code} ${flight.message} ${flight.refused}`)) ok('beside a town in flight', String(flight.message ?? flight.refused).slice(0, 140));
    else fail('beside a town in flight', flight ? JSON.stringify(flight).slice(0, 240) : 'the town was never seen placing');
    // (Architect 6d GR) the town grows in survival under Steward's keys before it is undone
    const townRegion = (placed?.siteGroup ?? '').replace(/^region:/, '');
    if (townRegion && await growsRegions()) await survivalGrowth(id, owner, townRegion, claim, ledger);
    // undone: every unit still consumed back in the stockpile (or dropped and counted as overflow)
    await cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ + 60}`);
    const before = await ledger();
    await cmd(`/steward undo ${id}`);
    let last = null;
    for (let i = 0; i < 300 && last?.kind !== 'PROJECT_REMOVED'; i++) {
      await sleep(2000);
      last = settlements().find((x) => x.id === id)?.log.at(-1);
    }
    const after = await ledger();
    const off = [];
    for (const [item, all] of Object.entries(before?.consumed ?? {})) {
      // what is still consumed (a growth undone earlier gave its share back already)
      const used = all - (before.returned?.[item] ?? 0) - (before.refundOverflow?.[item] ?? 0);
      if (used <= 0) continue;
      const back = (after?.returned?.[item] ?? 0) + (after?.refundOverflow?.[item] ?? 0) - (before.returned?.[item] ?? 0) - (before.refundOverflow?.[item] ?? 0);
      if (back !== used) off.push(`${item} ${back}/${used}`);
    }
    if (last?.kind === 'PROJECT_REMOVED' && off.length === 0) ok('supplied plan refund', `${last.text}; every consumed unit came back (${units(before?.consumed)})`);
    else fail('supplied plan refund', `${last?.kind}; refunds off: ${off.slice(0, 8).join(', ')}`);
  } finally {
    await cmd('/defaultgamemode creative');
    await cmd('/architect survival off');
  }
}

/**
 * (Architect close-out A) A material rule over the buildings alone: "copper buildings" (`over: "lots"`), the town's streets free. Checked, the rule fits
 * (measured over everything it would be refused: the streets aren't copper); the stub's timber designs break it, so the pre-check leaves them pads.
 */
async function copperLotsFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 42000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Copper Town';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'copper_lots.json'), 'utf8')).answer)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a hamlet of copper buildings`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('copper lots', 'no card');
  const composed = await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after[^\n]*`), 240_000);
  if (composed && / 0 pending, 0 unfit/.test(composed[0])) ok('copper lots', `${composed[0].slice(0, 120)}: the rule over the buildings fits, the streets free`);
  else return fail('copper lots', composed ? composed[0] : 'no plan composed');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  await waitLog(from, /Laying out the village from its plan[^\n]*/, 60_000);
  await cmd('/tp @p ~ ~ ~90');
  const built = await buildThrough(id, from, 'copper lots');
  if (!built) return;
  const padded = logSince(from).match(/(\S+) is left a pad: its design breaks the plan's rule on copper[^\n]*/);
  if (padded) ok('copper lots pre-check', padded[0].slice(0, 140));
  else fail('copper lots pre-check', 'no lot left a pad for the copper rule');
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 60 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(1000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  if (last?.kind !== 'PROJECT_REMOVED') fail('copper lots undo', `last op ${last?.kind}`);
}

/**
 * (Architect close-out A) A flat village's plan, never realised, is discarded when its build is cancelled (not left for the sweep).
 */
async function discardFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 45000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  await cmd(`/steward dev layout ${id} village`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Discard Hamlet';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little hamlet`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('discard', 'no card');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  // the bible approved; at the massings, the build is cancelled before anything is placed
  const end = Date.now() + 300_000;
  let at = from;
  while (Date.now() < end) {
    const since = logSince(at);
    if (/massings are ready/.test(since)) break;
    if (/Style bible ready/.test(since)) { at = logSize(); await cmd(`/steward approve ${id}`); }
    await sleep(1000);
  }
  await cmd(`/steward cancel ${id}`);
  const discarded = await waitLog(from, new RegExp(`discarded the village plan (\\S+) of ${id}'s ended build (\\S+)`), 60_000);
  if (!discarded) return fail('discard', `no plan discarded after the cancel; ${logSince(from).split('\n').filter((l) => /discard|cancel/i.test(l)).slice(-3).join(' | ').slice(0, 300)}`);
  const files = await dev.call('dev.coa.planFiles', { planId: discarded[1] }).catch((e) => ({ error: String(e) }));
  if (files.discarded && !files.plan) ok('discard', `the cancelled build's village plan ${discarded[1]} discarded (its tombstone kept)`);
  else fail('discard', `plan ${discarded[1]}: ${JSON.stringify(files).slice(0, 200)}`);
  // nothing answers for its key any more: no plan by key, no check record by key (the verifier's fix, 5cd766d4)
  const owner = `steward_mc:settlement/${id}`, key = `steward.${discarded[2]}.vplan`;
  const byKey = await dev.call('dev.coa.planByKey', { owner, opKey: key }).catch((e) => ({ error: String(e) }));
  const checkByKey = await dev.request('dev.region.checkByKey', { owner, opKey: key }).catch((e) => ({ error: String(e) }));
  const noCheck = !checkByKey?.check && !checkByKey?.checkId && !checkByKey?.artifactSha;
  if (!byKey?.planId && noCheck) ok('discard by key', `${key}: no plan, no check`);
  else fail('discard by key', `${key}: planByKey ${JSON.stringify(byKey).slice(0, 120)}; checkByKey ${JSON.stringify(checkByKey).slice(0, 120)}`);
}

/**
 * (Architect D3) A district beside a settlement's town region, as Steward's district move will ask for it: the `districts` program with one plain district
 * of two house lots, Steward's lot tags, owner and keys, its request claim on the town's east side overlapping it by 8 columns. `check` asks the dry
 * `checkBeside` (nothing kept); else a real plan under `<key>.dplan`. Returns DevBridge's answer.
 */
async function besideAsk(id, townRegion, { check = true, key = null, lots = 2, opKey = null, ext = null } = {}) {
  const st = settlements().find((x) => x.id === id);
  const c = st.claim, r = c.radius;
  // the town's chunks loaded first (D3 surveys loaded chunks only)
  await cmd(`/tp @p ${c.centerX + r - 4} ~ ${c.centerZ}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  const x0 = c.centerX + r - 7;
  const spec = { districts: [{ id: 'quay', size: [48, 48], lots: Array.from({ length: lots }, (_, k) => ({ id: `quay_${k + 1}`, type: 'house', role: 'house', size: [9, 9],
    landmark: false, ext: { 'steward_mc:settlement': id, 'steward_mc:lot': `quay_${k + 1}`, 'steward_mc:role': 'house' } })) }] };
  const body = { program: 'districts', params: { spec }, claim: [x0, c.centerZ - 48, x0 + 96, c.centerZ + 48], owner: `steward_mc:settlement/${id}`, beside: townRegion };
  if (check) return dev.request('dev.region.checkBeside', body, { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
  return dev.request('dev.region.plan', { ...body, ...(key ? { opKey: `${key}.dplan` } : {}), ...(opKey ? { opKey } : {}), ...(ext ? { ext } : {}) }, { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
}

/** A plan's files in the sidecar's data (Architect's own store: GJ's reach.gate and join are read from here, as its gate driver does). */
const planDir = (planId) => path.join(GAME, 'architect', 'sidecar-data', 'regions', 'plans', planId);
const planIr = (planId) => JSON.parse(fs.readFileSync(path.join(planDir(planId), 'ir.json'), 'utf8'));
const planJoin = (planId) => { try { return JSON.parse(fs.readFileSync(path.join(planDir(planId), 'meta.json'), 'utf8')).districts?.join ?? null; } catch { return null; } };

/** Whether this Architect revises regions (6d RV): its dev bridge has the revision hooks. */
async function revisesRegions() {
  const probe = await dev.request('dev.rv.state', { region: 'none' }).catch((e) => ({ refused: String(e) }));
  // an Architect without revisions answers an unknown type (as an error, or a refusal)
  return !/unknown type|unknown method|no such/i.test(`${probe?.error ?? ''} ${probe?.refused ?? ''}`);
}

/** Whether this Architect grows regions (6d GR): its revision plan takes a growth ({@code params: {grow}}). */
async function growsRegions() {
  const h = await dev.request('dev.help').catch(() => null);
  return JSON.stringify(h ?? {}).includes('params: {grow');
}

const plusItems = (a, b) => { const o = { ...a }; for (const [k, v] of Object.entries(b ?? {})) o[k] = (o[k] ?? 0) + v; return o; };
const minusItems = (a, b) => { const o = {}; for (const [k, v] of Object.entries(a ?? {})) { const n = v - (b?.[k] ?? 0); if (n) o[k] = n; } return o; };
const sameItems = (a, b) => JSON.stringify(Object.entries(a ?? {}).filter(([, v]) => v).sort()) === JSON.stringify(Object.entries(b ?? {}).filter(([, v]) => v).sort());

/**
 * (Architect 6d GR, Steward's RC rows 2 and 3) The town Steward built from materials grows by a lot in survival, planned and realised under Steward's
 * keys and ext: while its targets wait for their items, undoing the growth is refused {@code survival_building} with nothing written; fed from the
 * settlement's stockpile, the growth's bills are what the stockpile paid, item for item; undone, what it consumed comes back once, and a second undo
 * has nothing to take.
 */
async function survivalGrowth(id, owner, region, claim, ledger) {
  const label = 'survival growth';
  const st0 = await dev.request('dev.region.state', { region }).catch(() => null);
  const program = (st0?.view ?? st0 ?? {}).program ?? 'plan_v0';
  const rs0 = await dev.request('dev.rs.state', { region }, { timeoutMs: 120_000 }).catch(() => null);
  const targets0 = new Set((rs0?.targets ?? []).map((t) => t.id));
  const sp0 = await ledger();
  const b = `e2egr${Date.now() % 100000}`;
  const ext = { 'steward_mc:settlement': id, 'steward_mc:build': b };
  // one of the town's own buildings again, so the growth has a building to build from materials (a bare pad may cost nothing)
  const placedIds = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').flatMap((e) => e.siteIds ?? []);
  const sites = (await dev.request('dev.sites.state').catch(() => ({})))?.sites ?? [];
  // the smallest of them, on a lot that holds it whichever way it is turned, with room for its approach
  const sideOf = (x) => (x?.box ? Math.max(x.box.maxX - x.box.minX + 1, x.box.maxZ - x.box.minZ + 1) : 9);
  const site = (Array.isArray(sites) ? sites : Object.values(sites)).filter((x) => placedIds.includes(x.id) && x.blueprint).sort((a, c) => sideOf(a) - sideOf(c))[0];
  const entry = site?.blueprint ?? null;
  const side = Math.max(9, sideOf(site));
  const lot = { id: `${b}_h1`, type: 'house', size: [side + 1, side + 3] };
  // on the standing streets' frontage first (Architect's default); where none is free, on an extension of a street end
  let p = null;
  for (const prefer of ['frontage', 'extend']) {
    p = await dev.request('dev.rv.plan', { region, program, params: { grow: { lots: [lot], prefer } }, opKey: `steward.${b}.vplan`, ext },
      { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
    if (p?.planId || !/NO_ROOM/.test(String(p?.refused ?? ''))) break;
  }
  // the survival town here is walled: its street ends cannot extend through the wall (GJ joins districts, not streets), and its frontage is full. Accepted with
  // Architect for the GR RC (docs/ARCHITECT-ASKS.md, 6d GR RC); reported as a skip, not a pass, until the town's own growth can leave a wall
  if (!p?.planId && /NO_ROOM[^"]*\[street\][^"]*extendable/.test(String(p?.refused ?? ''))) {
    console.log(`skip ${label} (${region} is walled: its street cannot extend through the wall; ${String(p.refused).slice(0, 160)})`);
    return;
  }
  if (!p?.planId) return fail(label, `the growth of ${region} (${program}) by a ${lot.size.join(' x ')} lot not planned: ${JSON.stringify(p).slice(0, 260)}`);
  const position = p.revisionOf?.position ?? 1;
  const r = await dev.request('dev.region.realise', { planId: p.planId, opKey: `steward.${b}.vrealise`, ext, ...(entry ? { lots: { [lot.id]: entry } } : {}) },
    { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  if (r?.refused) return fail(label, `the growth's realise refused: ${r.refused}`);
  let rs = null;
  for (let i = 0; i < 90; i++) {
    rs = await dev.request('dev.rs.state', { region }, { timeoutMs: 120_000 }).catch(() => null);
    if ((rs?.targets ?? []).some((t) => !targets0.has(t.id) && !t.done)) break;
    await sleep(2000);
  }
  const box = [claim.centerX - claim.radius - 48, 40, claim.centerZ - claim.radius - 48, claim.centerX + claim.radius + 48, 160, claim.centerZ + claim.radius + 48];
  const snap = path.join(ROOT, 'artifacts', 'e2e', `growth-${id}.snap.gz`);
  await dev.call('dev.region.hash', { box, mode: 'snap', file: snap }, { timeoutMs: 1_800_000 });
  const ub = await dev.request('dev.rv.undo', { region, toPosition: position - 1, actor: 'steward' }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  const d0 = await dev.call('dev.region.hash', { box, mode: 'diff', file: snap }, { timeoutMs: 1_800_000 });
  const off0 = (d0.list ?? []).filter((m) => !/^(growth|grazed)$/.test(String(m.class)));
  if (/survival_building/.test(String(ub?.refused ?? '')) && off0.length === 0) ok('growth undo while building', `refused ${String(ub.refused).slice(0, 120)}; nothing written`);
  else fail('growth undo while building', `answer ${JSON.stringify(ub).slice(0, 160)}; ${off0.length} cell(s) differ`);
  // fed from the stockpile until the growth is built
  let built = false;
  for (const end = Date.now() + 900_000; Date.now() < end && !built;) {
    await sleep(3000);
    const sp = await ledger().catch(() => null);
    if (sp && Object.keys(sp.need ?? {}).length) await dev.request('dev.crate.insert', { site: `stockpile:${owner}`, items: sp.need }).catch(() => null);
    rs = await dev.request('dev.rs.state', { region }, { timeoutMs: 120_000 }).catch(() => null);
    const v = await dev.request('dev.region.state', { region }).catch(() => null);
    built = !!rs?.build?.done && (v?.view ?? v ?? {}).state === 'PLACED';
  }
  const sp1 = await ledger();
  const paid = minusItems(sp1?.consumed, sp0?.consumed);
  let bills = {};
  for (const t of (rs?.targets ?? []).filter((t) => !targets0.has(t.id))) bills = plusItems(bills, t.bom);
  if (built && Object.keys(paid).length && sameItems(paid, bills)) ok(label, `${region} grew by ${b}_h1 (${entry ?? 'a pad'}) in survival under steward.${b}.vplan/.vrealise; its bills ${JSON.stringify(bills).slice(0, 160)} = what the stockpile paid`);
  else return fail(label, `entry ${entry ?? 'none found'}; built ${built}; the stockpile paid ${JSON.stringify(paid).slice(0, 160)}; its targets' bills ${JSON.stringify(bills).slice(0, 160)}`);
  const u = await dev.request('dev.rv.undo', { region, toPosition: position - 1, actor: 'steward' }, { timeoutMs: 3_600_000 }).catch((e) => ({ refused: String(e) }));
  const sp2 = await ledger();
  const back = minusItems(plusItems(sp2?.returned, sp2?.refundOverflow), plusItems(sp1?.returned, sp1?.refundOverflow));
  const u2 = await dev.request('dev.rv.undo', { region, toPosition: position - 1, actor: 'steward' }, { timeoutMs: 120_000 }).catch((e) => ({ refused: String(e) }));
  if (u?.removed !== false && !u?.refused && sameItems(back, paid) && sameItems(u?.refund ?? back, paid) && String(u2?.refused ?? '').startsWith('NOT_ALLOWED')) {
    ok('growth refund once', `undone: ${JSON.stringify(back).slice(0, 140)} back in the stockpile; a second undo refused (${String(u2.refused).slice(0, 60)})`);
  } else fail('growth refund once', `undo ${JSON.stringify(u).slice(0, 140)}; back ${JSON.stringify(back).slice(0, 120)} of ${JSON.stringify(paid).slice(0, 120)}; second ${JSON.stringify(u2).slice(0, 80)}`);
}

/**
 * (docs/GROWTH.md, slice A2) A town built from materials grows from materials, through Steward alone (no dev revision call): a Supplied town from an
 * unwalled composed plan is fed until built; a library asked in talk and accepted is planned and realised as a revision of the town under the build's
 * keys; while its targets wait for items, undo is refused in plain words with nothing written; fed again, the steward says the town is built, and the
 * revision's bills are what the stockpile paid; undone, what it consumed comes back exactly once, and the next undo takes the town with its refund.
 */
async function suppliedGrowthFlow() {
  const label = 'supplied growth';
  await cmd('/architect survival on');
  await cmd('/defaultgamemode survival');
  await cmd('/gamemode creative @p');
  // the feeder: 'all' feeds what the stockpile's targets need; 'nudge' feeds half of it every 30 s while the growth's revision is still being placed (Architect
  // can hold a revision's later items until its earlier targets are built), so it ends with targets still waiting
  let feeding = false, stop = false, nudge = null, nudgedAt = 0;
  let feeder = null;
  try {
    const n = settlements().length;
    await cmd(`/spreadplayers ${(n + 1) * 900} 66000 0 1 false @p`);
    await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
    await clearAnimals();
    await cmd('/steward claim');
    const id = settlements().at(-1).id;
    await closeScreens();
    await cmd(`/steward expand ${id}`);
    const name = 'Supply Growth';
    const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
    card.name = name;
    await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
    // an unwalled town: the walled one's street can't extend through its wall (NO_ROOM [street])
    await cmd(`/steward dev sim_answer plan ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'plans', 'no_wood.json'), 'utf8')).answer)}`);
    let from = logSize();
    await cmd(`/steward describe ${id} a little stone town that will grow`);
    if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail(label, 'no card');
    if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail(label, 'no plan composed');
    const diff = settlements().find((x) => x.id === id)?.difficulty;
    if (diff !== 'SUPPLIED') return fail(label, `difficulty ${diff} in a survival world`);
    await closeScreens();
    await clearAnimals();
    const claim = settlements().find((x) => x.id === id).claim;
    const away = async () => cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ - claim.radius - 40}`);
    await away();
    const owner = `steward_mc:settlement/${id}`;
    const ledger = async () => {
      const r = await cmd(`/steward dev stockpile ${id}`);
      const m = r.match(/stockpile \S+ (\{.*\})/);
      return m ? JSON.parse(m[1]) : null;
    };
    // the feeder: what the stockpile's targets need, while feeding is on
    feeder = (async () => {
      while (!stop) {
        await sleep(3000);
        if (nudge && Date.now() - nudgedAt > 30_000) {
          const v = await dev.request('dev.region.state', { region: nudge }).catch(() => null);
          const revs = (v?.view ?? v ?? {}).revisions ?? [];
          const head = revs.sort((x, y) => y.rid - x.rid)[0];
          if (head && /^(ACCEPTED|PLACING)$/.test(head.state)) {
            const sp = await ledger().catch(() => null);
            const half = Object.fromEntries(Object.entries(sp?.need ?? {}).map(([k, n]) => [k, Math.max(1, Math.floor(n / 2))]));
            if (Object.keys(half).length) { await dev.request('dev.crate.insert', { site: `stockpile:${owner}`, items: half }).catch(() => null); nudgedAt = Date.now(); }
          }
        }
        if (!feeding) continue;
        const sp = await ledger().catch(() => null);
        if (sp && Object.keys(sp.need ?? {}).length) await dev.request('dev.crate.insert', { site: `stockpile:${owner}`, items: sp.need }).catch(() => null);
      }
    })();
    feeding = true;
    from = logSize();
    await cmd(`/steward start ${id} 2 30`);
    const town = await buildThrough(id, from, `${label} town`, false, null, 1_200_000);
    if (!town) return;
    const builtRe = new RegExp(`${name} is built: everything its plan laid out stands`);
    if (!(await waitLog(from, builtRe, 900_000))) return fail(label, 'the town was not built from the stockpile in 15 min');
    feeding = false;
    const st0 = settlements().find((x) => x.id === id);
    const founding = (st0?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
    const region = /^region:(.+)$/.exec(founding?.siteGroup ?? '')?.[1];
    if (!region) return fail(label, `the town is not a region: ${founding?.siteGroup}`);
    ok(`${label} town`, `${region} built from the stockpile (${town.placed} buildings)`);
    const box = [claim.centerX - claim.radius - 48, 40, claim.centerZ - claim.radius - 48, claim.centerX + claim.radius + 48, 160, claim.centerZ + claim.radius + 48];
    // the ground before the growth: the growth's undo must leave it so (the stockpile's crates, whose contents the refund changes, aside)
    const snapPre = path.join(ROOT, 'artifacts', 'e2e', `supplied-growth-pre-${id}.snap.gz`);
    await dev.call('dev.region.hash', { box, mode: 'snap', file: snapPre }, { timeoutMs: 1_800_000 });
    const rs0 = await dev.request('dev.rs.state', { region }, { timeoutMs: 120_000 }).catch(() => null);
    const targets0 = new Set((rs0?.targets ?? []).map((t) => t.id));
    const sp0 = await ledger();
    // asked for a library in talk, accepted: an addition with no district, which grows the town from materials
    await cmd(`/steward dev sim_answer talk ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'talk', 'library.json'), 'utf8')).answer)}`);
    await cmd(`/steward talk ${id} we need a library for the scholars`);
    let lib = null;
    for (let i = 0; i < 60 && !lib; i++) {
      await sleep(1000);
      lib = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key.startsWith('asked_')) ?? null;
    }
    if (!lib) return fail(label, 'no library proposal');
    await clearAnimals();
    from = logSize();
    nudge = region;
    await cmd(`/steward proposals ${id} accept ${lib.key}`);
    const planned = await waitLog(from, /The town grows: Architect finds room[^\n]*/, 120_000);
    const grew = await buildThrough(id, from, label, false, null, 1_200_000);
    nudge = null;
    if (!grew) return;
    const st1 = settlements().find((x) => x.id === id);
    const op = (st1?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
    const m = /^revision:(.+):(\d+)$/.exec(op?.siteGroup ?? '');
    const view = async () => { const v = await dev.request('dev.region.state', { region }).catch(() => null); return v?.view ?? v ?? {}; };
    let rv = await view();
    const rev = (rv.revisions ?? []).find((r) => String(r.rid) === m?.[2]);
    const keyed = new RegExp(`steward\\.[^.]+\\.vgrowrealise`).test(String(rev?.opKey ?? '')) || rev?.opKey == null;
    const bill = logSince(from).match(/From materials, the addition needs [^\n]*/);
    if (planned && m && m[1] === region && rev && grew.placed >= 1 && bill) ok(label, `planned and realised from materials as revision ${m[2]} of ${region} (${rev.state}); ${bill[0].slice(0, 140)}`);
    else return fail(label, `planned ${!!planned}; entry ${op?.siteGroup}; revision ${JSON.stringify(rev)?.slice(0, 160)}; placed ${grew.placed}; bill ${bill ? 'said' : 'not said'}; keyed ${keyed}`);
    // its targets wait for items (nothing fed): undo is refused in plain words, nothing written, the entry kept
    const snap = path.join(ROOT, 'artifacts', 'e2e', `supplied-growth-${id}.snap.gz`);
    await dev.call('dev.region.hash', { box, mode: 'snap', file: snap }, { timeoutMs: 1_800_000 });
    const spW = await ledger();
    from = logSize();
    const said = await cmd(`/steward undo ${id}`);
    await sleep(5000);
    const refused = `${said}\n${logSince(from)}`.match(/still being built from the stockpile[^\n]*/);
    const d0 = await dev.call('dev.region.hash', { box, mode: 'diff', file: snap }, { timeoutMs: 1_800_000 });
    const off0 = (d0.list ?? []).filter((x) => !/^(growth|grazed)$/.test(String(x.class)));
    const kept = settlements().find((x) => x.id === id)?.log.at(-1);
    if (Object.keys(spW?.need ?? {}).length && refused && !/survival_building|NOT_ALLOWED/.test(refused[0]) && off0.length === 0 && kept?.siteGroup === op.siteGroup) {
      ok(`${label} undo while building`, `${refused[0].slice(0, 160)}; nothing written, the entry kept`);
    } else fail(`${label} undo while building`, `owing ${Object.keys(spW?.need ?? {}).length} kinds; said ${refused ? refused[0].slice(0, 120) : JSON.stringify(said).slice(0, 160)}; ${off0.length} cell(s) differ; last ${kept?.kind} ${kept?.siteGroup}`);
    // fed until the town is built again: the steward says so; the revision's targets' bills = what the stockpile paid
    from = logSize();
    feeding = true;
    const built = await waitLog(from, builtRe, 900_000);
    feeding = false;
    const sp1 = await ledger();
    const rs1 = await dev.request('dev.rs.state', { region }, { timeoutMs: 120_000 }).catch(() => null);
    const paid = minusItems(sp1?.consumed, sp0?.consumed);
    let bills = {};
    for (const t of (rs1?.targets ?? []).filter((t) => !targets0.has(t.id))) bills = plusItems(bills, t.bom);
    if (built && Object.keys(paid).length && sameItems(paid, bills)) ok(`${label} bills`, `built and said; its bills ${JSON.stringify(bills).slice(0, 160)} = what the stockpile paid`);
    else return fail(`${label} bills`, `built ${!!built}; paid ${JSON.stringify(paid).slice(0, 160)}; bills ${JSON.stringify(bills).slice(0, 160)}`);
    // undone through Steward: the revision taken up, what it consumed back once
    await away();
    from = logSize();
    await cmd(`/steward undo ${id}`);
    let last = null;
    for (let i = 0; i < 300 && last?.kind !== 'PROJECT_REMOVED'; i++) {
      await sleep(2000);
      last = settlements().find((x) => x.id === id)?.log.at(-1);
    }
    const sp2 = await ledger();
    const back = minusItems(plusItems(sp2?.returned, sp2?.refundOverflow), plusItems(sp1?.returned, sp1?.refundOverflow));
    rv = await view();
    const undoneRev = (rv.revisions ?? []).find((r) => String(r.rid) === m[2]);
    await sleep(20_000);
    const sp3 = await ledger();
    const again = minusItems(plusItems(sp3?.returned, sp3?.refundOverflow), plusItems(sp2?.returned, sp2?.refundOverflow));
    const removedOnce = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_REMOVED').length === 1;
    // the ground as before the growth, but the stockpile's crates (their contents changed with the bills and the refund)
    const area = (settlements().find((x) => x.id === id)?.lands?.protect ?? []).find((a) => a.label === 'the stockpile (kept clear for plans)');
    const inCrates = (x) => area && x.x >= area.x0 && x.x <= area.x1 && x.z >= area.z0 && x.z <= area.z1;
    const dPre = await dev.call('dev.region.hash', { box, mode: 'diff', file: snapPre }, { timeoutMs: 1_800_000 });
    const offPre = (dPre.list ?? []).filter((x) => !/^(growth|grazed)$/.test(String(x.class)) && !inCrates(x));
    if (last?.kind === 'PROJECT_REMOVED' && undoneRev?.state === 'UNDONE' && sameItems(back, paid) && !Object.keys(again).length && removedOnce && offPre.length === 0) {
      ok(`${label} refund once`, `${last.text}; revision ${m[2]} UNDONE, the ground as before the growth; ${JSON.stringify(back).slice(0, 140)} back in the stockpile, nothing more 20 s on`);
    } else fail(`${label} refund once`, `last ${last?.kind}; revision ${undoneRev?.state}; back ${JSON.stringify(back).slice(0, 120)} of ${JSON.stringify(paid).slice(0, 120)}; more ${JSON.stringify(again).slice(0, 80)}; removed entries ${removedOnce}; ${offPre.length} cell(s) differ ${JSON.stringify(offPre.slice(0, 3))}`);
    // the next undo is the town's, with the rest of its refund
    await away();
    const before = await ledger();
    await cmd(`/steward undo ${id}`);
    let gone = null;
    for (let i = 0; i < 300 && !gone; i++) {
      await sleep(2000);
      const e = settlements().find((x) => x.id === id)?.log.at(-1);
      if (e?.kind === 'PROJECT_REMOVED' && (e.siteIds ?? []).length === founding.siteIds.length && founding.siteIds.every((x) => e.siteIds.includes(x))) gone = e;
    }
    const after = await ledger();
    const offT = [];
    for (const [item, all] of Object.entries(before?.consumed ?? {})) {
      const used = all - (before.returned?.[item] ?? 0) - (before.refundOverflow?.[item] ?? 0);
      if (used <= 0) continue;
      const got = (after?.returned?.[item] ?? 0) + (after?.refundOverflow?.[item] ?? 0) - (before.returned?.[item] ?? 0) - (before.refundOverflow?.[item] ?? 0);
      if (got !== used) offT.push(`${item} ${got}/${used}`);
    }
    if (gone && offT.length === 0) ok(`${label} town undo`, `${gone.text}; every unit still consumed came back`);
    else fail(`${label} town undo`, `${gone ? gone.text : 'not undone'}; refunds off: ${offT.slice(0, 8).join(', ')}`);
  } finally {
    stop = true;
    if (feeder) await feeder;
    await cmd('/defaultgamemode creative');
    await cmd('/architect survival off');
  }
}

/**
 * (Architect 6d GR, the gr_beside flow on Steward's own district) A standing district region grows by a lot under Steward's keys and ext: realised as
 * a revision of the district (no new region), found again by its realise key; its land becomes v 2 with a grown part that takes no town land, and
 * {@code land.outside} grows, then shrinks back when the growth is undone.
 */
async function districtGrowth(id, d, region) {
  const label = 'district growth';
  const owner = `steward_mc:settlement/${id}`;
  const landOf = async () => {
    const v = await dev.request('dev.region.state', { region }).catch(() => null);
    return (v?.view ?? v ?? {}).ext?.['architect_mc:land'] ?? null;
  };
  const before = await landOf();
  const b = `e2egd${Date.now() % 100000}`;
  const ext = { 'steward_mc:district': d.id, 'steward_mc:build': b };
  const p = await dev.request('dev.rv.plan', { region, program: 'districts', params: { grow: { district: d.id, lots: [{ id: `${d.id}_${b}`, type: 'house', size: [9, 9] }] } },
    opKey: `steward.${b}.vplan`, ext }, { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
  if (!p?.planId) return fail(label, `not planned: ${JSON.stringify(p).slice(0, 220)}`);
  const rid = p.revisionOf?.rid, position = p.revisionOf?.position ?? 1;
  const r = await dev.request('dev.region.realise', { planId: p.planId, opKey: `steward.${b}.vrealise`, ext }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  const rev = rid ? await revisionUntil(region, rid, ['PLACED', 'PARTIAL', 'FAILED']) : null;
  const grown = await landOf();
  const byKey = await dev.request('dev.region.realiseByKey', { owner, opKey: `steward.${b}.vrealise` }).catch((e) => ({ refused: String(e) }));
  const part = (grown?.parts ?? []).find((x) => x.kind === 'grown' && x.revision === rid);
  const oc = (l) => l?.outside?.cells ?? -1;
  if (rev?.state === 'PLACED' && (r?.region ?? region) === region && byKey?.region?.id === region && grown?.v === 2 && part && part.insideCells === 0 && oc(grown) > oc(before)) {
    ok(label, `${region} rev ${rid} under steward.${b}.vplan/.vrealise (found again by its key); land v 2, part ${part.id} (insideCells 0), outside ${oc(before)} -> ${oc(grown)}`);
  } else return fail(label, `rev ${rev?.state}; realise ${JSON.stringify(r).slice(0, 80)}; by key ${JSON.stringify(byKey).slice(0, 80)}; land v ${grown?.v}, part ${JSON.stringify(part)?.slice(0, 80)}; outside ${oc(before)} -> ${oc(grown)}`);
  await dev.request('dev.rv.undo', { region, toPosition: position - 1, actor: 'steward' }, { timeoutMs: 900_000 }).catch(() => null);
  const undone = await revisionUntil(region, rid, ['UNDONE']);
  const shrunk = await landOf();
  if (undone?.state === 'UNDONE' && oc(shrunk) === oc(before) && !(shrunk?.parts ?? []).some((x) => x.kind === 'grown')) ok('district growth undone', `outside back to ${oc(shrunk)}, no grown part (v ${shrunk?.v})`);
  else fail('district growth undone', `rev ${undone?.state}; outside ${oc(shrunk)} (was ${oc(before)}); parts ${JSON.stringify(shrunk?.parts?.map((x) => x.kind))}`);
}

/** A revision of {@code region} (6d RV) waited on until its record reads one of {@code states} ({@code rid}: its number). */
async function revisionUntil(region, rid, states, ms = 300_000) {
  let rev = null;
  for (const end = Date.now() + ms; Date.now() < end;) {
    const v = await dev.request('dev.region.state', { region }).catch(() => null);
    rev = ((v?.view ?? v ?? {}).revisions ?? []).find((x) => x.rid === rid) ?? null;
    if (rev && states.includes(rev.state)) return rev;
    await sleep(2000);
  }
  return rev;
}

/** A region's state, polled until it is one of {@code states} (or the time runs out). */
async function regionUntil(region, states, ms = 300_000) {
  let v = null;
  for (const end = Date.now() + ms; Date.now() < end;) {
    v = await dev.request('dev.region.state', { region }).catch(() => null);
    // a removed region is no longer in the state reader: gone is REMOVED
    const st = v?.view?.state ?? v?.state ?? (states.includes('REMOVED') && (!v || v.ok === false || v.error) ? 'REMOVED' : undefined);
    if (states.includes(st)) return { ...(v ?? {}), state: st };
    await sleep(2000);
  }
  return v;
}

/**
 * Architect D3 (Steward's RC; DISTRICTS.md): a district beside a flat-village town. The dry check fits with its join; the district realised beside it
 * (its lots pads), then removed alone, leaves the town's ground as it was (hash); realised again and the town undone first, the district stands with
 * its join dangling, then goes.
 */
async function besideFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 48000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  await cmd(`/steward dev layout ${id} village`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Beside Town';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little town`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('beside', 'no card');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  const built = await buildThrough(id, from, 'beside town');
  if (!built) return;
  const townOp = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const town = (townOp?.siteGroup ?? '').replace(/^region:/, '');
  if (!/^rg/.test(town)) return fail('beside', `the town is not a region: ${townOp?.siteGroup}`);
  const v = await besideAsk(id, town);
  if (v?.fits) ok('beside check', `fits beside ${town}: join ${v.join?.kind} (${v.join?.step}), ${v.join?.cells} cells (${v.join?.insideCells} in the town); ${v.ms} ms`);
  else return fail('beside check', JSON.stringify(v).slice(0, 300));
  const c = settlements().find((x) => x.id === id).claim;
  const townBox = [c.centerX - c.radius, 40, c.centerZ - c.radius, c.centerX + c.radius, 140, c.centerZ + c.radius];
  const snap = path.join(ROOT, 'artifacts', 'e2e', `beside-${id}.snap.gz`);
  fs.mkdirSync(path.dirname(snap), { recursive: true });
  await dev.call('dev.region.hash', { box: townBox, mode: 'snap', file: snap }, { timeoutMs: 600_000 });
  // district first: planned under Steward's keys, realised (lots left pads), removed alone: the town's ground as it was
  const key = `steward.e2e.${id}`;
  const plan = await besideAsk(id, town, { check: false, key });
  if (!plan?.planId) return fail('beside plan', JSON.stringify(plan).slice(0, 300));
  const r1 = await dev.request('dev.region.realise', { planId: plan.planId, opKey: `${key}.drealise` }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  const district = r1?.region;
  const placed = district && await regionUntil(district, ['PLACED', 'PARTIAL', 'FAILED']);
  const pst = placed?.view?.state ?? placed?.state;
  if (pst === 'PLACED') ok('beside district', `${district} realised beside ${town} (plan ${plan.planId}, key ${key}.drealise)`);
  else return fail('beside district', `${JSON.stringify(r1).slice(0, 200)}; state ${pst}`);
  // off the town and the district: a player in a box holds a removal up
  await cmd(`/tp @p ${c.centerX} ~ ${c.centerZ - c.radius - 40}`);
  // (Architect D3's fix) a removal refused before it starts (not the owner) changes nothing: the district stays PLACED
  const refused = await dev.request('dev.region.remove', { region: district }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  const still = await dev.request('dev.region.state', { region: district }).catch(() => null);
  const stillState = still?.view?.state ?? still?.state;
  if (refused?.removed === false && stillState === 'PLACED') ok('a refused removal changes nothing', `${(refused.blockers ?? [])[0] ?? ''}`.slice(0, 120) + ` (still ${stillState})`);
  else fail('a refused removal changes nothing', `${JSON.stringify(refused).slice(0, 160)}; state ${stillState}`);
  const rm1 = await dev.request('dev.region.remove', { region: district, force: true }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  const gone1 = await regionUntil(district, ['REMOVED'], 120_000);
  if (!(rm1?.removed === true || (gone1?.view?.state ?? gone1?.state) === 'REMOVED')) return fail('beside, district removed first', `the district's removal: ${JSON.stringify(rm1).slice(0, 300)}; state ${gone1?.view?.state ?? gone1?.state}`);
  await clearAnimals();
  const diff1 = await dev.call('dev.region.hash', { box: townBox, mode: 'diff', file: snap }, { timeoutMs: 600_000 });
  const off1 = (diff1.list ?? []).filter((m) => !/^(growth|grazed)$/.test(String(m.class)));
  if (off1.length === 0) ok('beside, district removed first', `the town's ground as it was (${diff1.mismatches} mismatch(es), all grass grazed or spread)`);
  else fail('beside, district removed first', `${diff1.mismatches} cell(s) differ, e.g. ${off1.slice(0, 4).map((m) => `${m.pos} ${m.was} -> ${m.now} (${m.class})`).join('; ').slice(0, 300)}`);
  // town first: the district stands with its join dangling, then goes
  const plan2 = await besideAsk(id, town, { check: false, key: `${key}.2` });
  const r2 = plan2?.planId ? await dev.request('dev.region.realise', { planId: plan2.planId, opKey: `${key}.2.drealise` }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) })) : null;
  const d2 = r2?.region;
  if (!d2 || (await regionUntil(d2, ['PLACED', 'PARTIAL', 'FAILED']))?.view?.state !== 'PLACED') return fail('beside, town removed first', `no district: ${JSON.stringify(r2 ?? plan2).slice(0, 200)}`);
  await cmd(`/tp @p ${c.centerX} ~ ${c.centerZ + c.radius + 40}`);
  await cmd(`/steward undo ${id}`);
  let last = null;
  for (let i = 0; i < 150 && last?.kind !== 'PROJECT_REMOVED'; i++) {
    await sleep(2000);
    last = settlements().find((x) => x.id === id)?.log.at(-1);
  }
  const after = await dev.request('dev.region.state', { region: d2 }).catch(() => null);
  const ext = after?.view?.ext ?? after?.ext ?? {};
  const dangling = ext['architect_mc:beside']?.dangling === true || ext['architect_mc:land']?.standingRemoved != null;
  const dstate = after?.view?.state ?? after?.state;
  if (last?.kind === 'PROJECT_REMOVED' && dstate === 'PLACED' && dangling) ok('beside, town removed first', `the town undone; ${d2} stands, its join dangling`);
  else fail('beside, town removed first', `town ${last?.kind}; district ${dstate}; ext ${JSON.stringify(ext).slice(0, 200)}`);
  const rm2 = await dev.request('dev.region.remove', { region: d2, force: true }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  const gone = await regionUntil(d2, ['REMOVED'], 120_000);
  if (rm2?.removed === true || (gone?.view?.state ?? gone?.state) === 'REMOVED') ok('beside, then the district', `${d2} removed after the town`);
  else fail('beside, then the district', `${d2}: ${JSON.stringify(rm2).slice(0, 300)}; state ${gone?.view?.state ?? gone?.state}`);
}

/**
 * Steward's districts on region districts (docs/DISTRICTS.md section 9): beside a flat-village town, an east district is reserved ($0, Architect's dry
 * check), undone and reserved again; its first building (a talk proposal accepted) builds its region beside the town (its street, its join and the
 * building), saved as a standing district with its region and land; undone, the region goes and the district is given up.
 */
async function districtRegionFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 52000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward dev layout ${id} village`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Quay Town';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little town`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('district reserved', 'no card');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  if (!(await buildThrough(id, from, 'quay town'))) return;
  const town = ((settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1)?.siteGroup ?? '').replace(/^region:/, '');
  if (!/^rg/.test(town)) return fail('district reserved', `the town is not a region: ${town}`);
  const c = settlements().find((x) => x.id === id).claim;
  const reserve = async () => {
    await cmd(`/tp @p ${c.centerX + c.radius + 20} 100 ${c.centerZ}`);
    await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
    const f = logSize();
    await cmd(`/steward district ${id} found E e2e quay`);
    const said = await waitLog(f, /(Reserved e2e quay\.[^\n]*|Could not reserve[^\n]*)/, 300_000);
    const d = settlements().find((x) => x.id === id)?.lands?.districts?.find((x) => x.name === 'e2e quay') ?? null;
    return { said: said ? said[1] : 'no answer', d };
  };
  let r = await reserve();
  if (r.d?.kind === 'beside' && r.d?.state === 'reserved' && r.d?.parent === town && !(r.d?.roads ?? []).length) {
    ok('district reserved', `${r.d.id} beside ${town}, ${r.d.land?.runs?.length ?? 0} rows of land, no road: ${r.said.slice(0, 100)}`);
  } else return fail('district reserved', `${r.said}; ${JSON.stringify(r.d)?.slice(0, 200)}`);
  // the reservation is the newest operation: undone, it is given up ($0, nothing in the world)
  await cmd(`/steward undo ${id}`);
  let gone = null;
  for (let i = 0; i < 20 && !gone; i++) {
    await sleep(500);
    const st = settlements().find((x) => x.id === id);
    if (!st?.lands?.districts?.some((x) => x.id === r.d.id) && st?.log.at(-1)?.kind === 'DISTRICT_REMOVED') gone = st.log.at(-1);
  }
  if (gone) ok('reservation undone', `${gone.text} (${gone.district})`);
  else return fail('reservation undone', JSON.stringify(settlements().find((x) => x.id === id)?.lands?.districts)?.slice(0, 200));
  r = await reserve();
  if (r.d?.state !== 'reserved') return fail('district reserved again', `${r.said}; ${JSON.stringify(r.d)?.slice(0, 200)}`);
  const d = r.d;
  // its first building: asked for in talk, accepted from the inbox
  const ask = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'talk', 'library.json'), 'utf8')).answer;
  ask.building = { ...ask.building, role: "harbourmaster's office", type: 'harbour_office', footprint: 'S', placement: undefined };
  ask.district = d.name;
  ask.reply = 'A harbourmaster\'s office on the quay: it is in the inbox.';
  await cmd(`/steward dev sim_answer talk ${JSON.stringify(ask)}`);
  await cmd(`/steward talk ${id} put a harbourmaster's office in ${d.name}`);
  let office = null;
  for (let i = 0; i < 60 && !office; i++) {
    await sleep(1000);
    office = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key.startsWith('asked_') && p.district === d.id) ?? null;
  }
  if (!office) return fail('district region', 'no proposal in the district');
  await clearAnimals();
  from = logSize();
  await cmd(`/steward proposals ${id} accept ${office.key}`);
  const built = await buildThrough(id, from, 'district region');
  if (!built) return;
  const st = settlements().find((x) => x.id === id);
  const after = st?.lands?.districts?.find((x) => x.id === d.id);
  const op = st?.log?.filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const region = after?.region;
  const view = region ? await dev.request('dev.region.state', { region }).catch(() => null) : null;
  const rv = view?.view ?? view ?? {};
  const outside = rv.ext?.['architect_mc:land']?.outside?.cells ?? 0;
  // Architect's land outside the town is merged into the reservation: the saved land holds more than the reserved square whenever the join (or the
  // district) left it, and at least the square
  const cells = (l) => (l?.runs ?? []).reduce((a, r) => a + (r.x1 - r.x0 + 1), 0);
  const square = (2 * d.claim.radius + 1) ** 2;
  // every column of Architect's outside land lies in a run of the saved land
  const holds = (land, z, a, b) => (land?.runs ?? []).some((r) => r.z === z && r.x0 <= a && r.x1 >= b);
  const merged = cells(after?.land) >= square && (rv.ext?.['architect_mc:land']?.outside?.runs ?? []).every(([x0, z, x1]) => holds(after?.land, z, Math.min(x0, x1), Math.max(x0, x1)));
  if (after?.state === 'standing' && region && op?.siteGroup === `region:${region}` && rv.beside === town && merged) {
    ok('district region', `${region} beside ${town}, standing; ${built.placed} placed; its land ${cells(after.land)} columns (the square ${square}, ${outside} outside the town from Architect)`);
  } else return fail('district region', `district ${JSON.stringify(after)?.slice(0, 200)}; op ${op?.siteGroup}; region beside ${rv.beside}, state ${rv.state}`);
  // (Architect 6d RV-c/RV-d, Steward's RC) a revision of the district region: its land.outside grows with what the revision writes outside the town,
  // and shrinks back when it is undone (the readers on the head's effective IR)
  if (await revisesRegions()) {
    const landOf = async () => {
      const v = await dev.request('dev.region.state', { region }).catch(() => null);
      return (v?.view ?? v ?? {}).ext?.['architect_mc:land'] ?? null;
    };
    const before = await landOf();
    const cx = d.claim.centerX + d.claim.radius - 4, cz = d.claim.centerZ + d.claim.radius - 4;
    const knoll = { id: 'knoll', call: { fn: 'columns', cols: [cx, cz, 0, 1, 0, cx - 1, cz, 0, 1, 0, cx, cz - 1, 0, 0, 0], from: { surface: 1 }, to: { surface: 1 }, materials: ['rock'], cond: 0 } };
    const kp = await dev.request('dev.rv.plan', { region, spec: { parts: [knoll] }, opKey: `steward.e2e.${id}.dknoll` }, { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
    const krid = kp?.revisionOf?.rid;
    if (kp?.planId) await dev.request('dev.region.realise', { planId: kp.planId }, { timeoutMs: 600_000 }).catch(() => null);
    const krev = krid ? await revisionUntil(region, krid, ['PLACED', 'PARTIAL', 'FAILED']) : null;
    const grown = await landOf();
    await dev.request('dev.rv.undo', { region, toPosition: 0, actor: 'steward' }, { timeoutMs: 900_000 }).catch(() => null);
    const kundo = krid ? await revisionUntil(region, krid, ['UNDONE']) : null;
    const shrunk = await landOf();
    const oc = (l) => l?.outside?.cells ?? -1;
    if (krev?.state === 'PLACED' && kundo?.state === 'UNDONE' && oc(grown) > oc(before) && oc(shrunk) === oc(before)) {
      ok('district land follows a revision', `${region} rev ${krid}: land.outside ${oc(before)} -> ${oc(grown)} -> ${oc(shrunk)} (v ${grown?.v})`);
    } else fail('district land follows a revision', `plan ${JSON.stringify(kp).slice(0, 120)}; rev ${krev?.state}, undo ${kundo?.state}; outside ${oc(before)} -> ${oc(grown)} -> ${oc(shrunk)}`);
  }
  if (await growsRegions()) await districtGrowth(id, d, region);
  // undone: the region goes, and the district with it
  await cmd(`/tp @p ${c.centerX} ~ ${c.centerZ - c.radius - 40}`);
  await cmd(`/steward undo ${id}`);
  let given = null;
  for (let i = 0; i < 150 && !given; i++) {
    await sleep(2000);
    const s2 = settlements().find((x) => x.id === id);
    if (!s2?.lands?.districts?.some((x) => x.id === d.id)) given = s2;
  }
  // a removed region leaves the state reader: no state, or REMOVED (Steward gives the district up only once Architect says it is gone)
  const rgone = await regionUntil(region, ['REMOVED'], 60_000);
  const rstate = rgone?.view?.state ?? rgone?.state;
  if (given && (rstate === 'REMOVED' || rstate === undefined)) ok('district region undone', `${region} ${rstate === undefined ? 'gone from the state reader' : 'removed'}, ${d.name} given up`);
  else fail('district region undone', `district ${given ? 'gone' : 'kept'}; region ${rgone?.view?.state ?? rgone?.state}; last ${JSON.stringify(settlements().find((x) => x.id === id)?.log.at(-1))?.slice(0, 160)}`);
}

/**
 * A quarter (Noah's Q2; docs/DISTRICTS.md section 9): beside a flat-village town, an outpost offered in the inbox and accepted, founded and built at once
 * (its own region, apart from the town, its road the join, Architect's corridor), saved as a standing outpost owning what its region took; undone, it is
 * given up. (The e2e world is flat and dry: a shore quarter isn't checked here.)
 */
async function quarterFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 56000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward dev layout ${id} village`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Camp Town';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little town`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('quarter', 'no card');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  if (!(await buildThrough(id, from, 'camp town'))) return;
  const c = settlements().find((x) => x.id === id).claim;
  await cmd(`/tp @p ${c.centerX + c.radius + 30} 100 ${c.centerZ}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  const offered = await cmd(`/steward district ${id} quarter E outpost`);
  let prop = null;
  for (let i = 0; i < 20 && !prop; i++) {
    await sleep(500);
    prop = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key === 'quarter_outpost_E') ?? null;
  }
  if (!prop || prop.quarter?.kind !== 'outpost') return fail('quarter offered', `${offered}; ${JSON.stringify(settlements().find((x) => x.id === id)?.proposals?.open)?.slice(0, 200)}`);
  ok('quarter offered', `${prop.title}: ${prop.building?.count} ${prop.building?.role}s`);
  await clearAnimals();
  from = logSize();
  await cmd(`/steward proposals ${id} accept ${prop.key}`);
  const built = await buildThrough(id, from, 'outpost');
  if (!built) return;
  const st = settlements().find((x) => x.id === id);
  const d = st?.lands?.districts?.find((x) => x.kind === 'outpost');
  const view = d?.region ? await dev.request('dev.region.state', { region: d.region }).catch(() => null) : null;
  const rv = view?.view ?? view ?? {};
  // the corridor is keyed by its district ({district: {runs, cells, rect}})
  const corridor = Object.values(rv.ext?.['architect_mc:corridor'] ?? {}).reduce((a, x) => a + (x?.cells ?? 0), 0);
  const cells = (l) => (l?.runs ?? []).reduce((a, r) => a + (r.x1 - r.x0 + 1), 0);
  const outside = rv.ext?.['architect_mc:land']?.outside?.cells ?? -1;
  // the same columns, not only as many: every run of Architect's outside land is a saved run
  const outRuns = rv.ext?.['architect_mc:land']?.outside?.runs ?? [];
  const same = cells(d?.land) === outside && outRuns.every(([x0, z, x1]) => (d?.land?.runs ?? []).some((r) => r.z === z && r.x0 <= Math.min(x0, x1) && r.x1 >= Math.max(x0, x1)));
  if (d?.state === 'standing' && corridor > 0 && same && built.placed >= prop.building.count) {
    ok('outpost', `${d.region}: ${built.placed} placed apart from the town, its corridor ${corridor} cells; it owns exactly what its region took (${outside} columns)`);
  } else return fail('outpost', `district ${JSON.stringify(d)?.slice(0, 200)}; corridor ${corridor}; outside ${outside}, saved ${cells(d?.land)}`);
  await cmd(`/tp @p ${c.centerX} ~ ${c.centerZ - c.radius - 40}`);
  await cmd(`/steward undo ${id}`);
  let gone = false;
  for (let i = 0; i < 150 && !gone; i++) {
    await sleep(2000);
    gone = !settlements().find((x) => x.id === id)?.lands?.districts?.some((x) => x.id === d.id);
  }
  if (gone) ok('outpost undone', `${d.name} given up with its region`);
  else fail('outpost undone', JSON.stringify(settlements().find((x) => x.id === id)?.log.at(-1))?.slice(0, 160));
}

/**
 * (Architect 6d RV, Steward's RC) a revision of a town Steward built: refused with a raw block (nothing written), then a mound in a free corner planned
 * and realised as revision 1. The town reads placed again with the revision standing and its sites tagged; Steward still takes the region as its town
 * and isn't held busy; undoing the town with the revision standing takes the region away. Skipped on an Architect without revisions.
 */
async function revisionFlow() {
  if (!(await revisesRegions())) return;
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 60000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward dev layout ${id} village`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Revised Town';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a little town`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('revision', 'no card');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 5 30`);
  if (!(await buildThrough(id, from, 'revised town'))) return;
  const town = ((settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1)?.siteGroup ?? '').replace(/^region:/, '');
  if (!/^rg/.test(town)) return fail('revision', `the town is not a region: ${town}`);
  const c = settlements().find((x) => x.id === id).claim;
  const ops = (settlements().find((x) => x.id === id)?.log ?? []).length;
  // a corner of the claim the village leaves free
  const x = c.centerX + c.radius - 8, z = c.centerZ + c.radius - 8;
  const mound = { id: 'mound', call: { fn: 'columns', cols: [x, z, 0, 1, 0, x + 1, z, 0, 2, 0, x + 2, z + 1, 0, 0, 0], from: { surface: 1 }, to: { surface: 1 }, materials: ['rock'], cond: 0 } };
  const raw = await dev.request('dev.rv.plan', { region: town, spec: { parts: [{ ...mound, call: { ...mound.call, materials: ['minecraft:oak_planks'] } }] } }, { timeoutMs: 900_000 })
    .catch((e) => ({ refused: String(e) }));
  if (/NOT_ALLOWED/.test(JSON.stringify(raw))) ok('revision refused', `a raw block: ${JSON.stringify(raw).slice(0, 120)}`);
  else fail('revision refused', JSON.stringify(raw).slice(0, 200));
  const plan = await dev.request('dev.rv.plan', { region: town, spec: { parts: [mound] }, opKey: `steward.e2e.${id}.rv1` }, { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
  // the revision's number is Architect's to give (a refused plan may have taken one): read from its answer
  const rid = plan?.revisionOf?.rid;
  if (!plan?.planId || !(rid > 0)) return fail('revision planned', JSON.stringify(plan).slice(0, 400));
  const r = await dev.request('dev.region.realise', { planId: plan.planId }, { timeoutMs: 600_000 }).catch((e) => ({ refused: String(e) }));
  let st = null;
  for (let i = 0; i < 90; i++) {
    await sleep(2000);
    st = await dev.request('dev.rv.state', { region: town }).catch(() => null);
    const rev = (st?.revisions ?? st?.list ?? []).find?.((v) => v.rid === rid);
    if (rev && /PLACED|PARTIAL|FAILED/.test(rev.state)) break;
  }
  const view = await dev.request('dev.region.state', { region: town }).catch(() => null);
  const rv = view?.view ?? view ?? {};
  const rev1 = (rv.revisions ?? []).find((v) => v.rid === rid);
  const tagged = (rev1?.sites ?? []).length;
  const after = settlements().find((x2) => x2.id === id);
  if (r?.region === town || r === town || rev1) {
    if (rev1?.state === 'PLACED' && rv.state === 'PLACED' && tagged > 0 && (after?.log ?? []).length === ops) {
      ok('revision realised', `${town} rev ${rid} ${rev1.state}, ${tagged} sites tagged; the town reads ${rv.state}; Steward's history unchanged (${ops} ops)`);
    } else fail('revision realised', `rev ${JSON.stringify(rev1)?.slice(0, 160)}; town ${rv.state}; ops ${(after?.log ?? []).length} vs ${ops}`);
  } else return fail('revision realised', JSON.stringify(r).slice(0, 200));
  // (RV-c) the revision undone alone: the town as it was before it, the region placed and not busy, its rid kept
  const und = await dev.request('dev.rv.undo', { region: town, toPosition: 0, actor: 'steward' }, { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
  const back = await revisionUntil(town, rid, ['UNDONE'], 300_000);
  const tv = await dev.request('dev.region.state', { region: town }).catch(() => null);
  const t = tv?.view ?? tv ?? {};
  if (back?.state === 'UNDONE' && t.state === 'PLACED' && !t.busy && t.position === 0) ok('revision undone', `rev ${rid} ${back.state}; ${town} ${t.state} at position ${t.position}, not busy (${JSON.stringify(und).slice(0, 80)})`);
  else fail('revision undone', `rev ${JSON.stringify(back)?.slice(0, 120)}; town ${t.state} position ${t.position} busy ${t.busy}; ${JSON.stringify(und).slice(0, 120)}`);
  // a second revision, then the town undone with it standing: Steward's Regions.remove takes it all
  const plan2 = await dev.request('dev.rv.plan', { region: town, spec: { parts: [{ ...mound, id: 'mound2' }] }, opKey: `steward.e2e.${id}.rv2` }, { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
  if (plan2?.planId) {
    await dev.request('dev.region.realise', { planId: plan2.planId }, { timeoutMs: 600_000 }).catch(() => null);
    await revisionUntil(town, plan2.revisionOf?.rid, ['PLACED', 'PARTIAL', 'FAILED']);
  }
  await cmd(`/tp @p ${c.centerX} ~ ${c.centerZ - c.radius - 40}`);
  await cmd(`/steward undo ${id}`);
  const gone = await regionUntil(town, ['REMOVED'], 300_000);
  const gs = gone?.view?.state ?? gone?.state;
  const last = settlements().find((x2) => x2.id === id)?.log.at(-1);
  if ((gs === 'REMOVED' || gs === undefined) && last?.kind === 'PROJECT_REMOVED') ok('town undone with a revision', `${town} ${gs ?? 'gone'}: ${last.text}`);
  else fail('town undone with a revision', `state ${gs}; last ${JSON.stringify(last)?.slice(0, 160)}`);
}

/**
 * Drives a build as {@link buildThrough} does (approving the bible, the massings and the placement; moving animals and the player off the lots), until
 * the log from {@code from} matches {@code until}: the match, or null when the build stopped or the time ran out.
 */
async function driveUntil(id, from, until, ms) {
  let at = from;
  for (const end = Date.now() + ms; Date.now() < end;) {
    // (an animal or the player in a lot: the run's herder moves them out)
    const all = logSince(from);
    const m = all.match(until);
    if (m) return m;
    if (/The build stopped[^\n]*|The build of [^\n]* stopped[^\n]*|Cancelled [^\n]*/.test(logSince(at)) && !/Cancelled/.test(String(until))) return null;
    const step = logSince(at).match(/Style bible ready|massings are ready|Approve to place/);
    if (!step) { await sleep(1000); continue; }
    at = logSize();
    await cmd(`/steward approve ${id}`);
  }
  return null;
}

/**
 * (Architect K3, Steward's RC) castle_city's curtain wall, its corner towers and gatehouse on its line, designed to their boxes and bound. The wall lots'
 * briefs carry the sim's `sim:outerdoor`, so a wall lot's first design has a door in an outer face: the bind refuses it ("lot <id> on wall <wall>: ..."), the
 * steward redesigns it as its next version and binds again, until the town stands. A restart while the first repair is designed: picked up by its key. A
 * revision of the walled town (Architect's re-check loop over wall lots), undone; the town undone. A second town cancelled while its repair is designed:
 * cancelled once the repair stopped. Skipped on an Architect without wall lots.
 */
/**
 * (Architect GM-a) A walled town whose gatehouse has a portcullis (Steward's own lenient params.mechanism): the gate's five brief lines reach the
 * gatehouse's design word for word (the sim roofs at row 10 only when the roof-cap line is in its notes), so the bind finds the sensor's rung rather
 * than the levers-only deviation; undone, the gatehouse, the mechanism and the town leave the ground as it was.
 */
/**
 * (Architect GM-b) The K1 swing gate as Architect's gmb_k1_swing has it: a hamlet in a wall 3 thick, a player-only road through its gate with
 * mech swing (the iron door pair, a button outside, a plate and lever inside). Two of castle_city's towers stand in for its huts (the sim designs
 * towers, not houses, and none on a lot 7 deep: 13x13).
 */
function swingAnswer() {
  const answer = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'strange-asks', 'plans', 'castle_city.json'), 'utf8')).answer;
  const towers = answer.plan.zones[0].program.lots.filter((l) => /^tower_[12]$/.test(l.id)).map((l) => ({ ...l, size: [13, 13] }));
  answer.plan.zones = [{ id: 'hamlet', kind: 'standard', required: true, program: { lots: towers }, where: { near: { anchor: 'claim_center', within: 16 } } }];
  answer.plan.connectors = [
    { id: 'curtain', kind: 'wall', required: true, where: { around: { feature: { part: 'hamlet' } } }, height: 6, thickness: 3 },
    { id: 'in', kind: 'road', required: true, from: { anchor: 'claim_e' }, to: { part: 'hamlet' }, width: 3, movers: ['player'] },
    { id: 'east_gate', kind: 'gate', crossing: 'in', mech: 'swing' },
  ];
  answer.summary = 'A hamlet behind a wall with an iron gate';
  return answer;
}

/**
 * (Architect WS) The same hamlet with no gate part: its wall opts every gate in (`gateMech: "swing"`), so the road's crossing and the gates.min
 * the wall cuts on its own are all swings, each its own gateMechanisms entry (`curtain_g<n>`), bill and refund (CONTRACT §19).
 */
function wallSwingAnswer() {
  const answer = swingAnswer();
  answer.plan.connectors = answer.plan.connectors.filter((c) => c.kind !== 'gate');
  Object.assign(answer.plan.connectors.find((c) => c.id === 'curtain'), { gates: { min: 2 }, gateMech: 'swing' });
  answer.summary = 'A hamlet behind a wall whose every gate is iron';
  return answer;
}

/**
 * (Architect GM-c) A drawbridge as Steward's plan writer writes it (`kind: "drawbridge"`, params {mechanism: redstone, movable: true}): the hamlet's
 * two towers ringed by a moat 5 wide and 3 deep, and the drawbridge from the claim's east edge over it to the hamlet, a level deck of iron trapdoors
 * 2 wide, lowered at rest, a lever on each bank. No wall: a route on through a wall's auto gate leaks M2:wall and M6 on main (Architect's GM-c
 * fixture records it, with a plain beam bridge too), not the drawbridge's.
 */
function drawbridgeAnswer() {
  const answer = swingAnswer();
  answer.plan.connectors = [
    { id: 'moat', kind: 'channel', required: true, label: 'moat', where: { around: { feature: { part: 'hamlet' }, ring: [6, 16] } }, width: 5, depth: 3 },
    { id: 'drawbridge', kind: 'drawbridge', required: true, label: 'drawbridge', from: { anchor: 'claim_e' }, to: { part: 'hamlet' }, approach: 32,
      params: { mechanism: 'redstone', movable: true } },
  ];
  answer.summary = 'A hamlet behind a moat with a drawbridge';
  return answer;
}

async function gateMechFlow(form = 'lenient') {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 33000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  const c = settlements().find((x) => x.id === id).claim;
  const box = [c.centerX - c.radius - 40, 40, c.centerZ - c.radius - 40, c.centerX + c.radius + 40, 140, c.centerZ + c.radius + 40];
  const snap = path.join(ROOT, 'artifacts', 'e2e', `gm-${id}.snap.gz`);
  await dev.call('dev.region.hash', { box, mode: 'snap', file: snap }, { timeoutMs: 1_800_000 });
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = { lots: 'Portcullis Wall', swing: 'Swing Gate', drawbridge: 'Drawbridge Hamlet' }[form] ?? 'Portcullis Keep';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  // castle_city's towers and gatehouse only (the sim designs no lot 7 deep), the curtain wall's gate with a portcullis
  const answer = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'strange-asks', 'plans', 'castle_city.json'), 'utf8')).answer;
  for (const z of answer.plan.zones) if (z.program?.lots) z.program.lots = z.program.lots.filter((l) => l.type === 'tower' || l.type === 'gatehouse');
  // (no mechanism in the fixture: Steward adds the portcullis to every gatehouse a wall names, PlanWriterJob.withGateMechanisms)
  // the wall as the plan writer now writes it (K3 lots: the gatehouse at its gate, the towers at its corners), so Steward's mech is explicit
  if (form === 'lots') {
    const wall = answer.plan.connectors.find((k) => k.id === 'curtain_wall');
    const { gate_lot, corner_lots, ...rest } = wall.params;
    wall.params = rest;
    wall.lots = [{ lot: gate_lot, at: 'gate' }, ...corner_lots.map((lot) => ({ lot, at: 'corner' }))];
  }
  await cmd(`/steward dev sim_answer plan ${JSON.stringify(form === 'swing' ? swingAnswer() : form === 'drawbridge' ? drawbridgeAnswer() : answer)}`);
  let from = logSize();
  const words = { swing: 'a hamlet behind a thick wall with an iron gate', drawbridge: 'a hamlet behind a moat with a drawbridge' }[form];
  await cmd(`/steward describe ${id} ${words ?? 'a castle with a portcullis in its gatehouse'}`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('gate mechanism', `${id}: no card`);
  if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('gate mechanism', `${id}: no plan composed`);
  await closeScreens();
  await clearAnimals();
  from = logSize();
  await cmd(`/steward start ${id} 6 80`);
  const built = await driveUntil(id, from, /is built(?: in part)?: (\d+) buildings placed/, 1_200_000);
  const st = settlements().find((x) => x.id === id);
  const region = (st?.log?.filter((e) => e.kind === 'PROJECT_PLACED').at(-1)?.siteGroup ?? '').replace(/^region:/, '');
  const view = region ? await dev.request('dev.region.state', { region }).catch(() => null) : null;
  const gm = (view?.view?.ext ?? view?.record?.ext ?? {})['architect_mc:gateMechanism'] ?? null;
  if (!built || !region) return fail('gate mechanism', `built ${!!built}; region ${region || 'none'}; last ${JSON.stringify(st?.log?.at(-1))?.slice(0, 160)}`);
  // (Steward needs Architect >= 0.51.0, which has GM: no report means no mechanism was asked for)
  if (!gm) fail(`gate mechanism (${form})`, `${region}: no gateMechanism on the region (Steward asked for no mechanism?)${form === 'drawbridge' ? `; said: ${(logSince(from).match(/[^\n]*drawbridge[^\n]*/gi) ?? []).slice(-3).join(' | ').slice(0, 300)}` : ''}`);
  else if (form === 'drawbridge') {
    // (GM-c) the circuit is the IR's gateMechanisms entry {kind: drawbridge} under the connector's id, written as mech:drawbridge; the trapdoors on the region
    const g = gm.drawbridge;
    if (g?.kind === 'drawbridge' && g.cells) ok('gate mechanism (drawbridge)', `${region} drawbridge: the trapdoor deck's circuit (${Array.isArray(g.cells) ? g.cells.length : g.cells} cells)`);
    else fail('gate mechanism (drawbridge)', `drawbridge ${JSON.stringify(g ?? gm).slice(0, 200)}`);
    await drawbridgeLevers(c);
  } else if (form === 'swing') {
    // the swing has no rung: its report is {kind, wall, cells, bom}, and a fallback to the wooden door would leave no report at all
    const g = gm.east_gate;
    if (g?.kind === 'swing' && g.wall === 'curtain' && g.cells) ok('gate mechanism (swing)', `${region} east_gate: the iron swing gate in curtain (${Array.isArray(g.cells) ? g.cells.length : g.cells} cells)`);
    else fail('gate mechanism (swing)', `east_gate ${JSON.stringify(g ?? gm).slice(0, 200)}`);
  } else {
    const g = gm.gatehouse_1;
    if (g && g.roof != null && g.sensor && !g.deviation) ok(`gate mechanism (${form})`, `${region} gatehouse_1 ${g.kind ?? 'portcullis'}: the sensor at roof row ${g.roof} (the brief lines reached its design)`);
    else fail(`gate mechanism (${form})`, `gatehouse_1 ${JSON.stringify(g ?? gm).slice(0, 200)}`);
  }
  await cmd(`/tp @p ${c.centerX} ~ ${c.centerZ - c.radius - 40}`);
  await cmd(`/steward undo ${id}`);
  const gone = await regionUntil(region, ['REMOVED'], 600_000);
  const gs = gone?.view?.state ?? gone?.state;
  await clearAnimals();
  const d = await dev.call('dev.region.hash', { box, mode: 'diff', file: snap }, { timeoutMs: 1_800_000 });
  const left = (d.list ?? []).filter((m) => !/^(growth|grazed)$/.test(String(m.class)));
  if ((gs === 'REMOVED' || gs === undefined) && left.length === 0) ok(`gate mechanism undone (${form})`, `${region} ${gs ?? 'gone'}; the ground as it was`);
  else fail(`gate mechanism undone (${form})`, `state ${gs}; ${left.length} cell(s) differ: ${JSON.stringify(left.slice(0, 4)).slice(0, 200)}`);
}

/** The block states of a box, {"x,y,z": state} without air (one ARWD dump). */
async function blocksIn(b) {
  const f = path.join(ROOT, 'artifacts', 'e2e', 'blocks.arwd.gz');
  await dev.call('dev.region.dump', { box: b, file: f }, { timeoutMs: 300_000 });
  const d = await decodeArwd(fs.readFileSync(f));
  const o = {};
  for (let y = b[1]; y <= b[4]; y++) for (let z = b[2]; z <= b[5]; z++) for (let x = b[0]; x <= b[3]; x++) {
    const st = d.palette[d.cells[d.at(x, y, z)]];
    if (st !== 'minecraft:air') o[`${x},${y},${z}`] = st;
  }
  return o;
}

/**
 * (Architect LV, v0.56.0) The drawbridge's two-way levers by real use: each lever flips the deck whatever the other is set to (near XOR far). At rest both
 * levers are off and the deck is lowered (no trapdoor open). 16 uses mixing both levers in every order, then each alone; after each, the deck is raised
 * (every trapdoor open, all together) exactly when one lever is on, else lowered. Ends with both off.
 */
async function drawbridgeLevers(c) {
  const all = await blocksIn([c.centerX - c.radius - 8, 58, c.centerZ - c.radius - 8, c.centerX + c.radius + 8, 72, c.centerZ + c.radius + 8]);
  const at = (k) => k.split(',').map(Number);
  const deck = Object.keys(all).filter((k) => /^minecraft:iron_trapdoor\b/.test(all[k])).map(at);
  const levers = Object.keys(all).filter((k) => /^minecraft:lever\b/.test(all[k])).map(at);
  if (levers.length !== 2 || !deck.length) return fail('drawbridge levers', `${levers.length} lever(s), ${deck.length} trapdoor(s) on the claim`);
  const xs = [...deck, ...levers];
  const tight = [Math.min(...xs.map((q) => q[0])), Math.min(...xs.map((q) => q[1])), Math.min(...xs.map((q) => q[2])), Math.max(...xs.map((q) => q[0])), Math.max(...xs.map((q) => q[1])), Math.max(...xs.map((q) => q[2]))];
  const read = async () => {
    const o = await blocksIn(tight);
    const open = deck.filter((q) => /open=true/.test(o[q.join(',')] ?? '')).length;
    return { open, on: levers.map((q) => /powered=true/.test(o[q.join(',')] ?? '')) };
  };
  const rest = await read();
  if (rest.open !== 0 || rest.on.some(Boolean)) fail('drawbridge levers at rest', `${rest.open} of ${deck.length} trapdoors open; levers ${rest.on}`);
  else ok('drawbridge levers at rest', `both levers off (${levers.map((q) => q.join(' ')).join('; ')}), the deck lowered (0 of ${deck.length} open)`);
  // A and B: the two levers; both in every order (A B A B, B A B A), then A alone 4 times and B alone 4 times
  const seq = [0, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1];
  const on = [false, false];
  const cyc = [];
  let bad = 0;
  for (const i of seq) {
    await dev.call('dev.pt.uses', { ops: [{ item: 'minecraft:stick', at: levers[i], on: levers[i], face: 'up' }] }, { timeoutMs: 30_000 });
    on[i] = !on[i];
    await sleep(2000);
    const r = await read();
    const want = on[0] !== on[1] ? deck.length : 0;
    cyc.push(`${'AB'[i]}:${r.on.map(Number).join('')}/${r.open}`);
    if (r.open !== want || r.on[0] !== on[0] || r.on[1] !== on[1]) bad++;
  }
  if (bad === 0) ok('drawbridge levers', `${seq.length} uses, both levers in every order and each alone: raised (${deck.length} open) exactly when one lever is on, else lowered (${cyc.join(' ')})`);
  else fail('drawbridge levers', `${bad} of ${seq.length} uses wrong (lever:states/open): ${cyc.join(' ')}`);
}

/**
 * (Architect GM-a2) A gate mechanism in survival: castle_city's towers and gatehouse built from the stockpile, its curtain wall's portcullis added by
 * Steward itself (PlanWriterJob.withGateMechanisms, not the fixture). The mechanism's bill is part of the stockpile's need; its redstone parts (comparator,
 * observers, daylight detector) are held back until the steward names them, then fed; the town is said built only with the gate in, the exact bill on the region; undone,
 * every consumed unit comes back.
 */
async function survivalMechFlow(form = 'lots') {
  const wall = form === 'wall';
  const swing = form === 'swing' || wall;
  // (Architect GM-c) the drawbridge: its circuit billed and held as one bill (the trapdoors are the path part's)
  const draw = form === 'drawbridge';
  const name = wall ? 'Wall Swing Supply' : swing ? 'Swing Supply' : draw ? 'Drawbridge Supply' : 'Gate Supply';
  const gate = swing ? 'east_gate' : draw ? 'drawbridge' : 'gatehouse_1';
  await cmd('/architect survival on');
  await cmd('/defaultgamemode survival');
  await cmd('/gamemode creative @p');
  try {
    const n = settlements().length;
    await cmd(`/spreadplayers ${(n + 1) * 900} 45000 0 1 false @p`);
    await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
    await clearAnimals();
    await cmd('/steward claim');
    const id = settlements().at(-1).id;
    await closeScreens();
    await cmd(`/steward expand ${id}`);
    const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
    card.name = name;
    await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
    const answer = wall ? wallSwingAnswer() : swing ? swingAnswer() : draw ? drawbridgeAnswer() : JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'strange-asks', 'plans', 'castle_city.json'), 'utf8')).answer;
    if (!swing && !draw) for (const z of answer.plan.zones) if (z.program?.lots) z.program.lots = z.program.lots.filter((l) => l.type === 'tower' || l.type === 'gatehouse');
    await cmd(`/steward dev sim_answer plan ${JSON.stringify(answer)}`);
    let from = logSize();
    await cmd(`/steward describe ${id} ${swing ? 'a hamlet behind an iron gate, built from what we gather' : draw ? 'a hamlet behind a moat with a drawbridge, built from what we gather' : 'a castle built from what we gather'}`);
    if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('survival mechanism', 'no card');
    if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return fail('survival mechanism', 'no plan composed');
    const diff = settlements().find((x) => x.id === id)?.difficulty;
    if (diff !== 'SUPPLIED') return fail('survival mechanism', `difficulty ${diff} in a survival world`);
    await closeScreens();
    await clearAnimals();
    const claim = settlements().find((x) => x.id === id).claim;
    await cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ + claim.radius + 12}`);
    const owner = `steward_mc:settlement/${id}`;
    const ledger = async () => {
      const r = await cmd(`/steward dev stockpile ${id}`);
      const m = r.match(/stockpile \S+ (\{.*\})/);
      return m ? JSON.parse(m[1]) : null;
    };
    const units = (o) => Object.values(o ?? {}).reduce((a, b) => a + b, 0);
    // what only the gate takes, held back until the steward names it: the portcullis's redstone parts (billed crafted: GM-a2's bill), the swing's iron doors
    const RARE = swing ? /iron_door/ : draw ? /:repeater$|:redstone$|:comparator$/ : /comparator|observer|daylight_detector|sticky_piston/;
    // (Architect WE) the mechanism's bill wait said as its stage sentence in chat ("Curtain g1's swing gate waits for 2 × iron door in the stockpile")
    const named = () => logSince(from).match(new RegExp(`\\[CHAT\\] [^\\n]*?waits for [^\\n]*(?:${swing ? 'iron door' : draw ? 'redstone' : 'comparator|observer|daylight detector|sticky piston'})[^\\n]*`, 'i'));
    from = logSize();
    await cmd(`/steward start ${id} 6 80`);
    // the feeder: all the need but the rare items, until the steward names them (or 3 min of nothing else owed), then everything
    let stop = false, rareSeen = null, rareFed = false, onlyRareSince = 0;
    // what the feeder saw and did, for a failure's message
    const fed = { polls: 0, noLedger: 0, owed: 0, accepted: 0, refused: 0, lastNeed: null, crates: 0 };
    // the town's ground and wall alone owe ~10,000 items, more than the 2 crates hold while the gate's sites wait: a full stockpile gets
    // another crate beside its first (the player stands there for /steward stockpile add, then goes back to watch)
    const addCrate = async () => {
      const at = logSince(from).match(/stockpile is the \d+ crates? at (-?\d+), (-?\d+), (-?\d+)/);
      if (!at || fed.crates >= 6) return;
      const [x, y, z] = at.slice(1).map(Number);
      fed.crates++;
      await cmd(`/tp @p ${x + 2 * fed.crates + 0.5} ${y} ${z + 0.5}`);
      fed.lastCrate = (await cmd(`/steward stockpile ${id} add`)).slice(0, 120);
      await cmd(`/tp @p ${claim.centerX} ~ ${claim.centerZ + claim.radius + 12}`);
    };
    const feeder = (async () => {
      while (!stop) {
        await sleep(3000);
        const sp = await ledger().catch(() => null);
        fed.polls++;
        if (!sp) fed.noLedger++;
        const need = sp?.need ?? {};
        if (!Object.keys(need).length) continue;
        fed.owed++;
        fed.lastNeed = units(need);
        const rare = Object.fromEntries(Object.entries(need).filter(([k]) => RARE.test(k)));
        if (Object.keys(rare).length && !rareSeen) rareSeen = rare;
        const rest = rareFed ? need : Object.fromEntries(Object.entries(need).filter(([k]) => !RARE.test(k)));
        if (!rareFed && Object.keys(rare).length && !Object.keys(rest).length) {
          onlyRareSince ||= Date.now();
          if (named() || Date.now() - onlyRareSince > 180_000) rareFed = true;
        }
        if (!Object.keys(rest).length) continue;
        const r = await dev.request('dev.crate.insert', { site: `stockpile:${owner}`, items: rest }).catch((e) => ({ error: String(e) }));
        if (units(r?.accepted ?? {})) fed.accepted += units(r.accepted);
        else (fed.refused++, (fed.lastRefusal = JSON.stringify(r).slice(0, 160)), await addCrate());
      }
    })();
    const built = await buildThrough(id, from, 'survival mechanism', false, null, 1_800_000);
    const doneSaid = built ? await waitLog(from, new RegExp(`${name} is built: everything its plan laid out stands`), 900_000) : null;
    stop = true;
    await feeder;
    const st = settlements().find((x) => x.id === id);
    const region = (st?.log?.filter((e) => e.kind === 'PROJECT_PLACED').at(-1)?.siteGroup ?? '').replace(/^region:/, '');
    const view = region ? await dev.request('dev.region.state', { region }).catch(() => null) : null;
    const gms = (view?.view?.ext ?? view?.record?.ext ?? {})['architect_mc:gateMechanism'] ?? {};
    const gm = gms[gate] ?? null;
    const said = named();
    // (Architect WL, v0.53.0) the stockpile's crates at the claim centre push the castle zone aside; its wall keeps its lots (CONTRACT §18)
    if (wall) {
      // (Architect WS) every gate the wall cut is a swing of its own, billed apart: two or more curtain_g<n> entries, each with its bill
      const swings = Object.entries(gms).filter(([g, m]) => /^curtain_g\d+$/.test(g) && m?.kind === 'swing' && m?.bom);
      if (rareSeen) ok('survival wall swings need', `the stockpile owed ${JSON.stringify(rareSeen)} for the gates`);
      else fail('survival wall swings need', 'no iron doors ever owed: no swing billed');
      if (said) ok('survival wall swings named', said[0].slice(0, 180));
      else fail('survival wall swings named', `the steward never named the gates' items (fed after the 3 min fallback: ${rareFed})`);
      if (built && doneSaid && swings.length >= 2) ok('survival wall swings built', `${region}: ${swings.length} auto gates are swings (${swings.map(([g, m]) => `${g} ${JSON.stringify(m.bom).slice(0, 60)}`).join('; ')})`);
      else return fail('survival wall swings built', `built ${!!built}; done said ${!!doneSaid}; gateMechanism ${JSON.stringify(gms).slice(0, 240)}; feeder ${JSON.stringify(fed)}`);
    } else if (draw) {
      const label = 'survival drawbridge';
      if (rareSeen) ok(`${label} need`, `the stockpile owed ${JSON.stringify(rareSeen)} for the drawbridge's circuit`);
      else fail(`${label} need`, 'no repeaters or redstone ever owed: no circuit billed');
      // (GMc-2) "Redstone drawbridge waits for ...", the noun not said twice
      if (said && !/drawbridge's drawbridge/i.test(said[0])) ok(`${label} named`, said[0].slice(0, 180));
      else fail(`${label} named`, said ? `the noun said twice: ${said[0].slice(0, 180)}` : `the steward never named the circuit's items (fed after the 3 min fallback: ${rareFed})`);
      // (LV) the two-way circuit has two comparators
      if (built && doneSaid && gm?.kind === 'drawbridge' && gm.bom?.['minecraft:comparator'] === 2) ok(`${label} built`, `${region} drawbridge; bill ${JSON.stringify(gm.bom).slice(0, 140)}`);
      else return fail(`${label} built`, `built ${!!built}; done said ${!!doneSaid}; gateMechanism ${JSON.stringify(gms).slice(0, 200)}; feeder ${JSON.stringify(fed)}`);
    } else {
      const label = `survival mechanism${swing ? ' (swing)' : ''}`;
      if (rareSeen) ok(`${label} need`, `the stockpile owed ${JSON.stringify(rareSeen)} for the gate`);
      else fail(`${label} need`, `no ${swing ? 'iron doors' : 'comparators, observers or daylight detectors'} ever owed: no mechanism billed`);
      // (Architect WE) the move to the mechanism's bill wait fires one ITEM_WAITING whose waitsFor.say names the missing items
      if (said) ok(`${label} named`, said[0].slice(0, 180));
      else fail(`${label} named`, `the steward never named the gate's items (fed after the 3 min fallback: ${rareFed})`);
      if (built && doneSaid && gm?.bom) ok(`${label} built`, `${region} ${gate}: ${swing ? `the iron swing gate in ${gm.wall}` : gm.roof != null ? `sensor at ${gm.roof}` : gm.deviation}; bill ${JSON.stringify(gm.bom).slice(0, 120)}`);
      else return fail(`${label} built`, `built ${!!built}; done said ${!!doneSaid}; gateMechanism ${JSON.stringify(gm).slice(0, 160)}; feeder ${JSON.stringify(fed)}`);
    }
    const before = await ledger();
    await cmd(`/steward undo ${id}`);
    let last = null;
    for (let i = 0; i < 300 && last?.kind !== 'PROJECT_REMOVED'; i++) {
      await sleep(2000);
      last = settlements().find((x) => x.id === id)?.log.at(-1);
    }
    const after = await ledger();
    const off = [];
    for (const [item, all] of Object.entries(before?.consumed ?? {})) {
      const used = all - (before.returned?.[item] ?? 0) - (before.refundOverflow?.[item] ?? 0);
      if (used <= 0) continue;
      const back = (after?.returned?.[item] ?? 0) + (after?.refundOverflow?.[item] ?? 0) - (before.returned?.[item] ?? 0) - (before.refundOverflow?.[item] ?? 0);
      if (back !== used) off.push(`${item} ${back}/${used}`);
    }
    const rareBack = Object.keys(before?.consumed ?? {}).filter((k) => RARE.test(k));
    const refund = wall ? 'survival wall swings refund' : draw ? 'survival drawbridge refund' : `survival mechanism${swing ? ' (swing)' : ''} refund`;
    // (Architect XL, v0.56.0) the crossing's deck and abutments give back the cells the route had paid for: every item exact, dirt included
    if (last?.kind === 'PROJECT_REMOVED' && off.length === 0 && rareBack.length) ok(refund, `every consumed unit came back (${units(before?.consumed)}), ${rareBack.join(', ')} among them`);
    else fail(refund, `${last?.kind}; rare consumed ${rareBack.join(', ') || 'none'}; refunds off: ${off.slice(0, 8).join(', ')}`);
  } finally {
    await cmd('/defaultgamemode creative');
    await cmd('/architect survival off');
  }
}

async function wallLotsFlow() {
  const make = async (name) => {
    const n = settlements().length;
    await cmd(`/spreadplayers ${(n + 1) * 900} 33000 0 1 false @p`);
    await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
    await clearAnimals();
    await cmd('/steward claim');
    const id = settlements().at(-1).id;
    await closeScreens();
    await cmd(`/steward expand ${id}`);
    const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
    card.name = name;
    await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
    // the sim designs no lot 7 deep (castle_city's houses, shops, tavern): the keep and the wall lots only; sim:outerdoor in the wall lots' briefs
    // alone (a keep is a tower too: the sim builds its wall-lot stand-in for any tower whose notes carry the token)
    const answer = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'strange-asks', 'plans', 'castle_city.json'), 'utf8')).answer;
    for (const z of answer.plan.zones) {
      if (!z.program?.lots) continue;
      z.program.lots = z.program.lots.filter((l) => l.type === 'tower' || l.type === 'gatehouse')
        .map((l) => (l.id === 'keep_1' ? l : { ...l, brief: `${l.brief ?? ''} sim:outerdoor`.trim() }));
    }
    await cmd(`/steward dev sim_answer plan ${JSON.stringify(answer)}`);
    let from = logSize();
    await cmd(`/steward describe ${id} a castle city behind its curtain wall`);
    if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return { id, error: 'no card' };
    if (!(await waitLog(from, new RegExp(`plan for ${id}: [A-Z_]+ after`), 240_000))) return { id, error: 'no plan composed' };
    await closeScreens();
    await clearAnimals();
    from = logSize();
    await cmd(`/steward start ${id} 6 80`);
    const walls = await waitLog(from, new RegExp(`wall lots of ${id}: ([^\n]*)`), 120_000);
    return { id, from, walls: walls?.[1] ?? null };
  };
  const a = await make('Castle Walls');
  if (a.error) return fail('wall lots', `${a.id}: ${a.error}`);
  if (!a.walls) { console.log('skip wall lots (no wall lots: an Architect before K3)'); return; }
  // the first repair under way: a restart, then picked up by its key
  const repairing = await driveUntil(a.id, a.from, /redesigning it \(repair 1 of 2\)/, 900_000);
  if (!repairing) return fail('wall lots', `no repair asked for (${a.walls}); last ${JSON.stringify(settlements().find((x) => x.id === a.id)?.log.at(-1))?.slice(0, 160)}`);
  await quit();
  launch();
  await connect();
  const resumed = await waitLog(0, new RegExp(`resume ${a.id}: wall repair (\\S+)`), 90_000);
  const built = await driveUntil(a.id, 0, /is built(?: in part)?: (\d+) buildings placed/, 1_200_000);
  const st = settlements().find((x) => x.id === a.id);
  const placed = st?.log?.filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
  const region = (placed?.siteGroup ?? '').replace(/^region:/, '');
  const view = region ? await dev.request('dev.region.state', { region }).catch(() => null) : null;
  const lots = (view?.view ?? view ?? {}).lots ?? [];
  const wallIds = a.walls.split(', ').map((w) => w.split(' ')[0]);
  const wallPlaced = wallIds.filter((w) => lots.some((l) => l.id === w && /placed/i.test(String(l.state))));
  const said = logSince(0);
  const repaired = (said.match(/is redesigned: placing again/g) ?? []).length;
  // (the sim designs in seconds: the restart may come after the repair ended, and then the resume binds the repaired head by itself)
  if (built && wallPlaced.length === wallIds.length && repaired >= 1) {
    ok('wall lots', `${a.walls}; refused and redesigned ${repaired} time(s); ${resumed ? `a repair in flight picked up after a restart (${resumed[1]})` : 'the restart came after the repair ended'}; all ${wallIds.length} bound in ${region}`);
  } else return fail('wall lots', `built ${!!built}; resumed ${!!resumed}; wall lots placed ${wallPlaced.length}/${wallIds.length}; repaired ${repaired}; last ${JSON.stringify(st?.log?.at(-1))?.slice(0, 160)}`);
  // a revision of the walled town (its re-check loop now reads the wall lots), undone
  if (await revisesRegions()) {
    const c = st.claim;
    const cx = c.centerX + c.radius - 6, cz = c.centerZ + c.radius - 6;
    const knoll = { id: 'knoll', call: { fn: 'columns', cols: [cx, cz, 0, 1, 0], from: { surface: 1 }, to: { surface: 1 }, materials: ['rock'], cond: 0 } };
    const kp = await dev.request('dev.rv.plan', { region, spec: { parts: [knoll] }, opKey: `steward.e2e.${a.id}.wknoll` }, { timeoutMs: 900_000 }).catch((e) => ({ refused: String(e) }));
    const rid = kp?.revisionOf?.rid;
    if (kp?.planId) await dev.request('dev.region.realise', { planId: kp.planId }, { timeoutMs: 600_000 }).catch(() => null);
    const rev = rid ? await revisionUntil(region, rid, ['PLACED', 'PARTIAL', 'FAILED']) : null;
    await dev.request('dev.rv.undo', { region, toPosition: (kp?.revisionOf?.position ?? 1) - 1, actor: 'steward' }, { timeoutMs: 900_000 }).catch(() => null);
    const undone = rid ? await revisionUntil(region, rid, ['UNDONE']) : null;
    if (rev?.state === 'PLACED' && undone?.state === 'UNDONE') ok('walled town revised', `${region} rev ${rid} placed with its wall lots standing, then undone`);
    else fail('walled town revised', `plan ${JSON.stringify(kp).slice(0, 160)}; rev ${rev?.state}; undo ${undone?.state}`);
  }
  // undone: the region and its wall lots go
  await cmd(`/tp @p ${st.claim.centerX} ~ ${st.claim.centerZ - st.claim.radius - 40}`);
  await cmd(`/steward undo ${a.id}`);
  const gone = await regionUntil(region, ['REMOVED'], 600_000);
  const gs = gone?.view?.state ?? gone?.state;
  if (gs === 'REMOVED' || gs === undefined) ok('walled town undone', `${region} ${gs ?? 'gone'}`);
  else fail('walled town undone', `state ${gs}`);
  // a second town cancelled while its repair is designed: cancelled once the repair stopped, nothing of it left
  const b = await make('Castle Cancel');
  if (b.error || !b.walls) return fail('wall repair cancelled', `${b.id}: ${b.error ?? 'no wall lots'}`);
  if (!(await driveUntil(b.id, b.from, /redesigning it \(repair 1 of 2\)/, 900_000))) return fail('wall repair cancelled', 'no repair asked for');
  const cf = logSize();
  await cmd(`/steward cancel ${b.id}`);
  const cancelled = await waitLog(cf, /Cancelled [^\n]*/, 300_000);
  let left = null;
  try { left = JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-builds.json'), 'utf8')).builds.find((x) => x.settlementId === b.id) ?? null; } catch { /* none */ }
  if (cancelled && !left) ok('wall repair cancelled', cancelled[0].slice(0, 140));
  else fail('wall repair cancelled', `${cancelled ? cancelled[0].slice(0, 100) : 'not cancelled'}; build left ${left ? left.state?.phase : 'none'}`);
}

/**
 * Architect's plan check (P0; docs/COMPOSITION.md): recorded plans checked round the player on fresh flat land, answered part by part. A plain village
 * is ready, with an artifact; the walled town's hidden cellar is unsupported, naming form.hidden_room (its wall fits: K1). The ready one is realised from its artifact and taken up again.
 */
async function planCheckFlow() {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 12000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await clearAnimals();
  const plan = (f) => JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', f), 'utf8')).answer.plan;
  const checked = async (label, p) => {
    const from = logSize();
    await cmd(`/steward dev check_plan ${label} ${JSON.stringify(p)}`);
    const m = await waitLog(from, new RegExp(`plan check ${label} (\\{[^\\n]*)`), 180_000);
    return m ? JSON.parse(m[1]) : null;
  };
  const market = await checked('market_town', plan('strange-asks/plans/market_town.json'));
  if (market?.overall === 'READY' && market.artifact) ok('plan check', `market town READY ${JSON.stringify(market.classes)}, artifact ${market.artifact.slice(0, 12)}`);
  else return fail('plan check', JSON.stringify(market)?.slice(0, 300));
  const walled = await checked('walled_town', plan('plans/walled_town.json'));
  const cellar = walled?.parts?.find((x) => x.part === 'secret_cellar');
  const wall = walled?.parts?.find((x) => x.part === 'town_wall');
  if (cellar?.class === 'unsupported' && cellar.block === 'form.hidden_room' && /fits|adjusted/.test(wall?.class ?? '')) {
    ok('plan check unsupported', `the cellar: ${cellar.class} ${cellar.block}; the wall ${wall.class}; overall ${walled.overall}`);
  }
  else fail('plan check unsupported', JSON.stringify(walled)?.slice(0, 300));
  let from = logSize();
  await cmd('/steward dev realise_plan market_town');
  const realised = await waitLog(from, /plan realised market_town (\S+)/, 120_000);
  if (!realised || /error/.test(realised[1])) return fail('plan realise', realised ? realised[0] : 'no answer');
  await sleep(15_000);
  // off the plan (it is centred on the player, and Architect waits for a player standing in what it removes), its chunks still loaded
  await cmd('/tp @p ~ ~ ~90');
  // nothing on the sites (a horse a new chunk spawned stops the removal before it writes anything): cleared, and asked once more if one walked in
  let gone = null;
  for (let i = 0; i < 2 && gone?.[1] !== 'true'; i++) {
    await clearAnimals();
    from = logSize();
    await cmd('/steward dev unrealise_plan market_town');
    gone = await waitLog(from, /plan unrealised market_town (\S+)/, 660_000);
  }
  if (gone && gone[1] === 'true') ok('plan realise', `${realised[1]} realised from its approved artifact, then taken up`);
  else fail('plan realise', `realised ${realised[1]}; taken up: ${gone ? gone[1] : 'no answer'}`);
}

/** Farm animals out of the way: a pig standing on a lot holds its placement up for Architect's 10-minute wait (it happened, on the V RC). */
async function clearAnimals() {
  await cmd('/gamerule doMobSpawning false');
  for (const t of ['pig', 'cow', 'sheep', 'chicken', 'rabbit', 'horse', 'donkey', 'mule', 'llama', 'goat', 'trader_llama', 'wandering_trader', 'fox']) await cmd(`/kill @e[type=minecraft:${t}]`);
  // and what they drop: an item on a lot refuses its placement too (raw beef and feathers held up the library)
  await cmd('/kill @e[type=minecraft:item]');
}

async function stubFlow(id) {
  // the whole paid flow against Architect's sim helper (v0.12.2, notional measured costs, never Claude); every wait is bounded
  const fixture = (f) => JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', f), 'utf8')).card ?? JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', f), 'utf8')).answer);
  // this settlement stays on Steward's own layout (additions, districts and the rest below rest on it); villageFlow builds one on Architect's flat village
  await cmd(`/steward dev layout ${id} legacy`);
  await cmd(`/steward dev sim_answer card ${fixture('stilt_village.json')}`);
  // the plan Claude composes from the card (plan v0): a walled town (its wall fits, K1), whose hidden cellar waits for Architect (form.hidden_room, 7b)
  await cmd(`/steward dev sim_answer plan ${fixture('plans/walled_town.json')}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a fishing village on stilts over a swamp`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('describe', `no card: ${logSince(from).split('\n').filter((l) => /CHAT/.test(l)).slice(-3).join(' | ')}`);
  const card = settlements().find((x) => x.id === id);
  ok('describe', `${card?.name}: ${(card?.card?.program ?? []).length} program entries (the scripted card through the card job)`);
  // the composed plan is checked and kept, with the cellar pending on its building block
  // the kept plan's line (a repair round logs "round n" first)
  const composed = await waitLog(from, new RegExp(`plan for ${id}: ([A-Z_]+ after [^\n]*)`), 240_000);
  const plans = (() => { try { return JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-plans.json'), 'utf8')).plans ?? {}; } catch { return {}; } })();
  const wallWaits = plans[id]?.pending?.find((x) => x.part === 'secret_cellar');
  const schemaBy = (logSince(from).match(/plan writer: the schema ([^\n(]*)/) ?? [0, '?'])[1].trim();
  if (wallWaits?.block === 'form.hidden_room' && /Not yet: The hidden cellar/.test(logSince(from))) ok('composition', `${plans[id].overall} after ${plans[id].rounds} repair(s); the cellar waits on ${wallWaits.block}; the schema ${schemaBy}`);
  else fail('composition', `${composed ? composed[1] : 'no plan'}; ${JSON.stringify(plans[id] ?? null)?.slice(0, 200)}`);
  // what waits for Architect, on the card before anything is spent: a stilt village over a swamp stands beside the water for now
  const limit = logSince(0).match(/Not yet: (Buildings over water[^\n]*)/);
  await closeScreens();
  await cmd(`/steward ui card ${id}`);
  await sleep(1500);
  const cardShot = (await dev.request('dev.screenshot', { name: 'e2e-card-limits', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  // Enter once: the card says what starting spends, and waits for a second press (nothing starts)
  await dev.request('dev.key', { key: 'enter' });
  await sleep(500);
  const confirmShot = (await dev.request('dev.screenshot', { name: 'e2e-card-confirm', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  const started = /Approving|Designing the style bible|Starting/.test(logSince(logSize() - 4000));
  if (!started) ok('start needs two presses', `one Enter only armed it; ${confirmShot}`);
  else fail('start needs two presses', 'one Enter started the build');
  await closeScreens();
  if (limit) ok('card limits', `${limit[1].slice(0, 70)}; ${cardShot}`);
  else fail('card limits', 'no "Not yet" line for the stilt village');
  // an operation key (C9, Architect 0.13): the bible request's acknowledgement is dropped, the game stops before the id is saved, and after the restart the
  // steward finds the request by its key and picks it up (never asks, or pays, twice)
  await dev.request('dev.api.dropAck', { msgType: 'bible.request' });
  from = logSize();
  // seven buildings: with Architect 0b's copies, two of the fisher's huts copy a hut design ($0)
  await clearAnimals();
  await cmd(`/steward start ${id} 7 40`);
  if (await waitLog(from, /Designing the style bible/, 60_000)) {
    await sleep(3000);
    await quit();
    launch();
    await connect();
    const adopted = await waitLog(0, /Architect had the style bible request: picking it up|never reached Architect: asking again|The build lost track of[^\n]*/, 90_000);
    if (adopted && /picking it up/.test(adopted[0])) ok('recovered by key', 'the bible request whose ack was lost, found by its operation key after a restart');
    else fail('recovered by key', adopted ? adopted[0] : 'no word after the restart');
  } else fail('recovered by key', 'the bible was never requested');
  from = 0;
  const first = await buildThrough(id, from, 'first build', true);
  if (!first) return;
  ok('first build', `${first.steps.join(' > ')} > ${first.placed} placed`);
  // the copies (Architect 0b): the helper's log names them for this group
  const helperLog = path.join(GAME, 'architect', 'sidecar-data', 'logs', 'sidecar.log');
  const copyLines = fs.existsSync(helperLog) ? fs.readFileSync(helperLog, 'utf8').split('\n').filter((l) => / copies \S+ \(gen_/.test(l)).slice(-2) : [];
  if (copyLines.length) ok('copies', copyLines.map((l) => l.replace(/^.*group \S+: /, '')).join('; ').slice(0, 160));
  else fail('copies', 'no copy in the helper log');
  const spent = settlements().find((x) => x.id === id);
  if (spent?.bible?.id) ok('bible kept', `${spent.bible.id} v${spent.bible.version}`);
  else fail('bible kept', JSON.stringify(spent?.bible));
  // a proposal worded in the settlement's own terms (3e): seeds make the rules propose a farm, the wording job (scripted) names it
  await cmd(`/steward dev sim_answer proposal ${fixture('proposal-words/granary.json')}`);
  await cmd(`/tp @p ${spent.claim.centerX + 6} 100 ${spent.claim.centerZ + 6}`);
  await cmd('/give @p minecraft:wheat_seeds 16');
  let farm = null;
  for (let i = 0; i < 100 && !farm; i++) {
    await sleep(1000);
    farm = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key === 'farm' && /granary/i.test(p.title)) ?? null;
  }
  if (farm) ok('worded proposal', `${farm.title}: ${farm.why} (${farm.building.role})`);
  else fail('worded proposal', JSON.stringify(settlements().find((x) => x.id === id)?.proposals?.open));
  await cmd(`/clear @p`);
  if (farm) await cmd(`/steward proposals ${id} decline farm`);
  // talk through the real talk job (its answer scripted): a library proposed, accepted, built in the settlement's own style
  await cmd(`/steward dev sim_answer talk ${fixture('talk/library.json')}`);
  from = logSize();
  await cmd(`/steward talk ${id} we need a library for the scholars`);
  let lib = null;
  for (let i = 0; i < 60 && !lib; i++) {
    await sleep(1000);
    lib = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key.startsWith('asked_')) ?? null;
  }
  if (!lib) return fail('talk job', `no proposal: ${logSince(from).split('\n').filter((l) => /CHAT/.test(l)).slice(-3).join(' | ')}`);
  ok('talk job', `${lib.title} proposed (${lib.key})`);
  await clearAnimals();
  // still Steward's own street town (the dev layout is not kept over the restart above): its addition lays a street piece, and its districts below
  // are a street town's (slice B's adoption has its own flow, adoptFlow)
  await cmd(`/steward dev layout ${id} legacy`);
  from = logSize();
  await cmd(`/steward proposals ${id} accept ${lib.key}`);
  const reused = await waitLog(from, /style bible is reused/, 60_000);
  const addition = await buildThrough(id, from, 'addition');
  if (addition && reused) ok('addition', `${addition.steps.join(' > ')} > ${addition.placed} placed, the bible reused`);
  else if (addition) fail('addition', 'built, but the bible was not reused');
  // a change request (3c, with Architect 0b's versionOf: the RC jar via ARCHITECT_JAR): asked in talk, accepted, designed as the next version, applied, reverted
  {
    const libSite = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1)?.siteIds?.[0];
    const change = { reply: 'A reading tower for the library: it is in the inbox.', intent: 'change', change: { target: "scholars' library", request: 'Add a slim reading tower on its east side, with tall windows.' } };
    await cmd(`/steward dev sim_answer talk ${JSON.stringify(change)}`);
    await cmd(`/steward talk ${id} give the library a reading tower`);
    let cp = null;
    for (let i = 0; i < 60 && !cp; i++) {
      await sleep(1000);
      cp = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key.startsWith('change_')) ?? null;
    }
    const noted = (settlements().find((x) => x.id === id)?.log ?? []).find((e) => e.kind === 'NOTE' && /Asked to change/.test(e.text));
    // an Architect without 0b's versionOf (e.g. 0a's release candidate): the change is noted in the history, as it should be
    if (!cp && noted) ok('change request', `noted (this Architect has no versionOf): ${noted.text.slice(0, 60)}`);
    else if (!cp) fail('change request', 'no change proposal and no note');
    else {
      from = logSize();
      await cmd(`/steward proposals ${id} accept ${cp.key}`);
      const designed = await waitLog(from, /The change to [^\n]* is designed/, 300_000);
      await cmd(`/steward updates`);
      await sleep(3000);
      from = logSize();
      await cmd(`/steward update ${id} ${cp.changeSite}`);
      const updated = await waitLog(from, /Updated [^\n]* to version (\d+)/, 120_000);
      from = logSize();
      await cmd(`/steward revert ${id} ${cp.changeSite} 1`);
      const reverted = await waitLog(from, /Reverted [^\n]* to version 1/, 120_000);
      if (designed && updated && reverted) ok('change request', `${cp.title} (${cp.changeSite}${cp.changeSite === libSite ? ', the library' : ''}): designed, updated to v${updated[1]}, reverted to v1`);
      else fail('change request', `designed ${!!designed}, updated ${!!updated}, reverted ${!!reverted}: ${logSince(from).split('\n').filter((l) => /CHAT/.test(l)).slice(-3).join(' | ')}`);
    }
  }
  // "add a floor to my house" (Architect PT2): a claimed hut taken on, a change asked in talk and designed as its next version (the sim); the offer says
  // how many of the blocks as the player made them it changes, and asks even though it is free; then the hut is let go and released
  {
    const hut = await buildHut(id, 'adopted change', [12, 12]);
    if (hut) {
      await cmd(`/tp @p ${hut.x0 + 3} ${hut.y} ${hut.z0 + 14}`);
      from = logSize();
      await cmd(`/steward builds ${id} adopt ${hut.bid}`);
      const took = await waitLog(from, new RegExp(`adopt ${id} ${hut.bid}: [^\\n]*\\((\\S+), \\d+ blocks\\)`), 60_000);
      const role = settlements().find((x) => x.id === id)?.log.at(-1)?.text?.match(/Took on (.*) \(build-/)?.[1];
      const change = { reply: 'A second floor for your hut: it is in the inbox.', intent: 'change', change: { target: role ?? 'build', request: 'Add a second floor with a window on each side.' } };
      await cmd(`/steward dev sim_answer talk ${JSON.stringify(change)}`);
      await cmd(`/steward talk ${id} add a floor to my hut`);
      let cp = null;
      for (let i = 0; i < 60 && !cp; i++) {
        await sleep(1000);
        cp = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key.startsWith('change_') && p.changeSite === took?.[1]) ?? null;
      }
      if (!took || !cp) fail('adopted change', `adopted ${took ? took[1] : 'no'}; proposal ${cp ? cp.key : 'none'}`);
      else {
        from = logSize();
        await cmd(`/steward proposals ${id} accept ${cp.key}`);
        const designed = await waitLog(from, /The change to [^\n]* is designed/, 300_000);
        from = logSize();
        await cmd('/steward updates');
        const offer = await waitLog(from, new RegExp(`update check ${id} ${took[1]} v1 -> v\\d+: (\\w+) \\(([^\\n]*)`), 60_000);
        if (designed && offer && offer[1] === 'ASK' && /as you made it/.test(offer[2])) ok('adopted change', offer[2].slice(0, 160));
        else fail('adopted change', `designed ${!!designed}; ${offer ? offer[0].slice(0, 200) : 'no update check'}`);
      }
      // let it go (the adoption is the newest undoable) and take the hut down
      await cmd(`/steward undo ${id}`);
      for (let i = 0; i < 30 && /stands/.test(await cmd(`/steward dev site ${took?.[1] ?? 'none'}`)); i++) await sleep(1000);
      await cmd(`/steward builds ${id} release ${hut.bid}`);
      await cmd(`/fill ${hut.x0} ${hut.y} ${hut.z0} ${hut.x0 + 6} ${hut.y + 4} ${hut.z0 + 6} minecraft:air`);
    }
  }
  // a district through talk, founded; then a building in it lays its street and the link road
  await cmd(`/steward dev sim_answer talk ${fixture('talk/harbour.json')}`);
  const st = settlements().find((x) => x.id === id);
  const [cx, cz] = [st.claim.centerX, st.claim.centerZ];
  await cmd(`/tp @p ${cx + 120} 100 ${cz}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await cmd(`/steward talk ${id} we need a harbour district out east`);
  let prop = null;
  for (let i = 0; i < 60 && !prop; i++) {
    await sleep(1000);
    prop = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.found === 'E') ?? null;
  }
  if (!prop) return fail('district by talk', 'no district proposal');
  await cmd(`/steward proposals ${id} accept ${prop.key}`);
  let d = null;
  for (let i = 0; i < 20 && !d; i++) {
    await sleep(1000);
    d = settlements().find((x) => x.id === id)?.lands?.districts?.find((x) => x.direction === 'E') ?? null;
  }
  if (!d) return fail('district by talk', `not founded: ${logSince(from).split('\n').filter((l) => /CHAT/.test(l)).slice(-3).join(' | ')}`);
  ok('district by talk', `${d.name} founded with road ${d.roads[0].siteId}`);
  const inHarbour = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'talk', 'library.json'), 'utf8')).answer;
  inHarbour.building = { ...inHarbour.building, role: "harbourmaster's office", type: 'harbour_office', footprint: 'S', placement: undefined };
  inHarbour.district = d.name;
  inHarbour.reply = 'A harbourmaster\'s office in the harbour: it is in the inbox.';
  await cmd(`/steward dev sim_answer talk ${JSON.stringify(inHarbour)}`);
  await cmd(`/tp @p ${d.claim.centerX - 20} 100 ${d.claim.centerZ + 10}`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await cmd(`/steward talk ${id} put a harbourmaster's office in ${d.name}`);
  let office = null;
  for (let i = 0; i < 60 && !office; i++) {
    await sleep(1000);
    office = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key.startsWith('asked_') && p.district === d.id) ?? null;
  }
  if (!office) return fail('district build', 'no proposal in the district');
  await clearAnimals();
  from = logSize();
  await cmd(`/steward proposals ${id} accept ${office.key}`);
  const inDistrict = await buildThrough(id, from, 'district build');
  if (!inDistrict) return;
  let linked = null;
  for (let i = 0; i < 20 && !linked; i++) {
    await sleep(1000);
    linked = settlements().find((x) => x.id === id)?.lands?.districts?.find((x) => x.id === d.id && (x.roads ?? []).some((r) => r.link)) ?? null;
  }
  const log = settlements().find((x) => x.id === id)?.log ?? [];
  if (linked && log.some((e) => e.kind === 'ROAD_PLACED')) ok('district build', `${inDistrict.placed} placed in ${d.name}, its street linked by road ${linked.roads.find((r) => r.link).siteId}`);
  else fail('district build', `built, but no link road (${JSON.stringify(linked?.roads ?? d.roads)})`);
  // a picture of the district from above, in survey mode
  await cmd('/gamemode spectator');
  await cmd('/time set noon');
  // above the ground the survey found, looking down on the district and its road
  const groundY = Number((logSince(0).match(/survey: 97x97 columns[^\n]*ground y (-?\d+)/) ?? [0, 64])[1]);
  await cmd(`/tp @p ${d.claim.centerX - 75} ${groundY + 45} ${d.claim.centerZ + 65} facing ${d.claim.centerX} ${groundY} ${d.claim.centerZ}`);
  await dev.request('dev.key', { key: 'u' });
  await sleep(3000);
  const shot = (await dev.request('dev.screenshot', { name: 'e2e-stub-district', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
  await dev.request('dev.key', { key: 'u' });
  await cmd('/gamemode creative');
  ok('district picture', shot);
  // undo the newest operation (the link road)
  await cmd(`/steward undo ${id}`);
  await sleep(10_000);
  if (settlements().find((s) => s.id === id)?.log.at(-1)?.kind === 'PROJECT_REMOVED') ok('undo');
  else fail('undo', 'not logged');
}

/**
 * (docs/FOLLOW.md, Architect FS) A settlement's copies follow their source: a flat village with copies is built; a change is asked of a copy, and is
 * asked of its source instead; the source's new version is applied; then Architect re-makes the copies from that version ($0, followAll) and each
 * copy answers. A followed copy with a delta is offered (always asked), applied and reverted; a refused copy is told once and not offered; a follow
 * with nothing to write is fine (the sim's recipes are seeded by the group id, so which of these happens varies by run). A rescan tells nothing new.
 */
async function followOnce(attempt, last) {
  const n = settlements().length;
  await cmd(`/spreadplayers ${(n + 1) * 900} 39000 0 1 false @p`);
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 });
  await cmd('/steward claim');
  const id = settlements().at(-1).id;
  await closeScreens();
  await cmd(`/steward expand ${id}`);
  await cmd(`/steward dev layout ${id} village`);
  const card = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'stilt_village.json'), 'utf8')).card;
  card.name = 'Follow Hamlet';
  // the huts M: the sim's SMALL stand-in (a shed) has no int param, so its versionOf changes nothing; an M cabin's width grows by one
  for (const e of card.program) if (e.role === "fisher's hut") e.footprint = 'M';
  await cmd(`/steward dev sim_answer card ${JSON.stringify(card)}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a hamlet of fishers' huts`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('follow', 'no card');
  await closeScreens();
  await clearAnimals();
  from = logSize();
  // seven buildings: some of the fisher's huts are copies of a hut design ($0)
  await cmd(`/steward start ${id} 7 40`);
  const town = await buildThrough(id, from, 'follow town');
  if (!town) return;
  const listed = await cmd(`/steward dev copies ${id}`);
  const copies = [...listed.matchAll(/copy (\S+) (\S+) v(\d+) of (\S+) v(\d+) at (\S+)/g)].map((m) => ({ site: m[1], entry: m[2], v: Number(m[3]), source: m[4], sourceSite: m[6] }));
  const fam = copies.find((c) => c.sourceSite !== '-');
  if (!fam) return fail('follow copies', `no copy with its source standing: ${listed.slice(0, 200)}`);
  const family = copies.filter((c) => c.source === fam.source);
  ok('follow copies', `${family.map((c) => c.site).join(', ')} copy ${fam.source} at ${fam.sourceSite}`);
  // a change asked of a copy, by its site: the proposal is the source's
  const change = { reply: 'A wider hut: it is in the inbox.', intent: 'change', change: { target: fam.site, request: 'Make it a little wider.' } };
  await cmd(`/steward dev sim_answer talk ${JSON.stringify(change)}`);
  await cmd(`/steward talk ${id} make that hut a little wider`);
  let cp = null;
  for (let i = 0; i < 60 && !cp; i++) {
    await sleep(1000);
    cp = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key.startsWith('change_')) ?? null;
  }
  if (cp?.changeSite === fam.sourceSite) ok('follow change of a copy', `asked of ${fam.site}, proposed for its source ${cp.changeSite}`);
  else return fail('follow change of a copy', `proposal ${JSON.stringify(cp)?.slice(0, 160)}`);
  // Full: the source's own update applies by itself in creative; its copies' updates must still wait for the player
  const perm = await cmd(`/steward dev permission ${id} full`);
  if (!/permission FULL/.test(perm)) return fail('follow source', perm);
  from = logSize();
  await cmd(`/steward proposals ${id} accept ${cp.key}`);
  if (!(await waitLog(from, /The change to [^\n]* is designed/, 300_000))) return fail('follow source', 'the change was not designed');
  let updated = await waitLog(from, new RegExp(`Updated [^\\n]* to version (\\d+)`), 30_000);
  // asked instead (a version that takes a part away is a demolition): applied by hand
  if (!updated) {
    await cmd(`/steward update ${id} ${fam.sourceSite}`);
    updated = await waitLog(from, /Updated [^\n]* to version (\d+)/, 120_000);
  }
  if (!updated) return fail('follow source', `not updated: ${logSince(from).split('\n').filter((l) => /update check|CHAT/.test(l)).slice(-3).join(' | ')}`);
  const target = Number(updated[1]);
  // nothing followed while the source stood at v1 (designed, not yet applied): the follow comes after the update
  const all = logSince(from);
  const followAt = all.search(new RegExp(`follow ${id}: ${fam.source} v\\d+ -> copies`));
  // Architect's own line for the source's update (the server's; the chat line is the client's, printed later)
  const sourceAt = all.indexOf(`Updated site ${fam.sourceSite} (`);
  if (followAt >= 0 && (sourceAt < 0 || followAt < sourceAt)) return fail('follow source', `the copies followed before the source was updated (${followAt} < ${sourceAt})`);
  ok('follow source', `${fam.sourceSite} updated to v${target} (Full); nothing followed while it stood at v1`);
  // the copies are re-made from that version and each answers
  const asked = await waitLog(from, new RegExp(`follow ${id}: ${fam.source} v${target} -> copies \\[([^\\]]*)\\] \\(([^)]*)\\)`), 120_000);
  if (!asked) return fail('follow all', `no followAll: ${logSince(from).split('\n').filter((l) => /follow|CHAT/.test(l)).slice(-3).join(' | ')}`);
  const answers = {};
  for (let i = 0; i < 120 && Object.keys(answers).length < family.length; i++) {
    for (const m of logSince(from).matchAll(new RegExp(`follow ${id}: (\\S+) (followed|already followed|refused (\\S+)) \\(v(\\d+) -> v(\\d+)`, 'g'))) answers[m[1]] = { how: m[2], refused: m[3], to: Number(m[5]) };
    if (Object.keys(answers).length < family.length) await sleep(1000);
  }
  const missing = family.filter((c) => !answers[c.entry]);
  if (missing.length) return fail('follow all', `no answer for ${missing.map((c) => c.entry).join(', ')} (key ${asked[2]})`);
  ok('follow all', `${asked[2]}: ${family.map((c) => `${c.site} ${answers[c.entry].how}`).join('; ')}`);
  // each copy as it answered
  await sleep(3000);
  const notes = () => (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'NOTE' && /follow|stays as it is/.test(e.text)).map((e) => e.text);
  let applied = 0;
  for (const c of family) {
    const a = answers[c.entry];
    if (a.how === 'followed') {
      const check = await waitLog(from, new RegExp(`update check ${id} ${c.site} v\\d+ -> v${a.to}: (\\w+) \\(([^\\n]*)`), 60_000);
      if (!check) { fail(`follow ${c.site}`, 'no update check for the followed copy'); continue; }
      if (check[1] === 'NOTHING') { ok(`follow ${c.site}`, `followed to v${a.to}, nothing to write (its recipe pins what the source changed)`); continue; }
      if (check[1] !== 'ASK' || !/follows the /.test(check[2])) { fail(`follow ${c.site}`, `${check[1]}: ${check[2].slice(0, 160)}`); continue; }
      let f2 = logSize();
      await cmd(`/steward update ${id} ${c.site}`);
      const up = await waitLog(f2, /Updated [^\n]* to version (\d+)/, 120_000);
      f2 = logSize();
      await cmd(`/steward revert ${id} ${c.site} ${c.v}`);
      const back = await waitLog(f2, new RegExp(`Reverted [^\\n]* to version ${c.v}`), 120_000);
      const hist = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => (e.kind === 'UPDATED' || e.kind === 'REVERTED') && (e.siteIds ?? []).includes(c.site));
      if (up && back && hist.length >= 2) { applied++; ok(`follow ${c.site}`, `offered (ASK: ${check[2].slice(0, 90)}), updated to v${up[1]}, reverted to v${c.v}`); }
      else fail(`follow ${c.site}`, `updated ${!!up}, reverted ${!!back}, history ${hist.map((e) => e.kind).join(',')}`);
    } else if (a.how.startsWith('refused')) {
      const told = notes().filter((t) => t.includes('stays as it is')).length;
      const offered = logSince(from).match(new RegExp(`update check ${id} ${c.site} v\\d+ -> v\\d+: ASK`));
      if (told >= 1 && !offered) ok(`follow ${c.site}`, `refused ${a.refused}, told: ${notes().find((t) => t.includes('stays as it is')).slice(0, 120)}`);
      else fail(`follow ${c.site}`, `refused ${a.refused}; told ${told}; offered ${!!offered}`);
    } else ok(`follow ${c.site}`, a.how);
  }
  // asked again (a rescan): the key is answered, nothing new is told
  const before = notes().length;
  from = logSize();
  await cmd('/steward updates');
  await sleep(5000);
  const again = logSince(from).match(new RegExp(`follow ${id}: ${fam.source} v${target} -> copies`));
  if (!again && notes().length === before) ok('follow told once', `${before} note(s): ${notes().map((t) => t.slice(0, 60)).join(' | ')}`);
  else fail('follow told once', `asked again ${!!again}; notes ${before} -> ${notes().length}`);
  // at least one copy took the new design: which ones do varies with the group's seed (a copy whose param is the one the sim changes stays)
  if (applied > 0) ok('follow applied', `${applied} copy update(s) offered at Full, applied and reverted (village ${attempt})`);
  else if (last) fail('follow applied', `no copy followed with something to write in ${attempt} villages: ${family.map((c) => `${c.site} ${answers[c.entry].how}`).join('; ')}`);
  else console.log(`follow: village ${attempt}'s copies had nothing to write (${family.map((c) => `${c.site} ${answers[c.entry].how}`).join('; ')}); another village (a new seed)`);
  return applied;
}

/** The follow flow, on up to 4 villages: the copies' recipes are seeded by the group, so a village whose copies all pin what the sim changes is made again. */
async function followFlow() {
  for (let i = 1; i <= 4; i++) {
    const r = await followOnce(i, i === 4);
    if (r !== 0) return;
  }
}

// ------------------------------------------------------------------ run

const started = Date.now();
const skipped = [];
/** Runs a flow when no --only is given or a word of it is in the flow's name or tags (`flow` lines name each, for a rerun of what failed). */
async function flow(name, tags, fn) {
  if (only && !only.some((w) => `${name} ${tags}`.toLowerCase().includes(w))) return skipped.push(name);
  console.log(`flow ${name}`);
  await clearAnimals();
  await fn();
}
try {
  await preflight();
  ensureStubEntry();
  launch();
  await connect();
  const r = await cmd('/steward status');
  if (/ok/.test(r)) ok('status', r.split(' | ')[0]);
  else fail('status', r);
  // the sanity set, always run: status (above), the claim and its steward, Expand, the restart
  const id = await claimAndSteward();
  if (id) {
    await expand(id);
    await flow('stone-recipe', 'screens', () => stoneRecipe());
    await flow('inbox', 'screens', () => inboxScreen());
    if (mode === 'free') {
      await flow('place', 'update revert undo screens', () => placeUpdateUndo(id));
      await flow('steward-life', 'sit sleep walk', () => stewardLife(id));
      await flow('proposals', 'farm', () => proposals(id));
      await flow('districts', 'protect road layout', () => districts(id));
      await flow('player-builds', 'protect', () => playerBuilds(id));
      await flow('talk', 'screens', () => talk(id));
    }
    else {
      // stub: the first settlement's whole paid flow; the flows after it build their own
      await flow('stub', 'paid card plan wall revision undo', () => stubFlow(id));
      await flow('village', 'plan realise', () => villageFlow());
      await flow('growth', 'growth region realise undo', () => growthFlow());
      await flow('adopt', 'adopt growth region undo', () => adoptFlow());
      await flow('follow', 'copies source update revert version', () => followFlow());
      await flow('land', 'land check perception inbox talk', () => landFlow());
      await flow('plan-build', 'plan', () => planBuildFlow());
      await flow('street-hint', 'plan grid wall nofit', () => streetHintFlow());
      await flow('wall-offer', 'wall plan', () => wallOfferFlow());
      await flow('farm-pond', 'water', () => farmPondFlow());
      await flow('ditch', 'water', () => ditchFlow());
      await flow('irrigation', 'water farm', () => irrigationFlow());
      await flow('palette', 'material plan', () => paletteFlow());
      await flow('grove', 'plan', () => groveFlow());
      await flow('districts-plan', 'district plan', () => districtsPlanFlow());
      await flow('plan-check', 'plan', () => planCheckFlow());
      await flow('survival', 'stockpile bill refund growth wall', () => survivalFlow());
      await flow('supplied-plan', 'survival stockpile bill', () => suppliedPlanFlow());
      await flow('supplied-growth', 'survival stockpile bill refund growth', () => suppliedGrowthFlow());
      await flow('copper-lots', 'plan material', () => copperLotsFlow());
      await flow('discard', 'plan undo', () => discardFlow());
      await flow('beside', 'plan', () => besideFlow());
      await flow('revision', 'plan version revert', () => revisionFlow());
      await flow('district-region', 'district region', () => districtRegionFlow());
      await flow('quarter', 'district wall', () => quarterFlow());
      await flow('wall-lots', 'wall gatehouse gate', () => wallLotsFlow());
      await flow('gate-mech', 'gate wall mechanism portcullis undo', () => gateMechFlow());
      await flow('gate-mech-lots', 'gate wall mechanism portcullis undo', () => gateMechFlow('lots'));
      await flow('gate-mech-swing', 'gate wall mechanism swing undo', () => gateMechFlow('swing'));
      await flow('survival-mech', 'survival gate wall mechanism portcullis bill refund', () => survivalMechFlow());
      await flow('survival-mech-swing', 'survival gate wall mechanism swing bill refund', () => survivalMechFlow('swing'));
      await flow('survival-mech-wall', 'survival gate wall mechanism swing gatemech bill refund', () => survivalMechFlow('wall'));
      await flow('drawbridge', 'bridge drawbridge gate moat water mechanism undo catalogue', () => gateMechFlow('drawbridge'));
      await flow('survival-drawbridge', 'survival bridge drawbridge gate moat water mechanism bill refund', () => survivalMechFlow('drawbridge'));
    }
  }
  await flow('migrated-steward', 'save migration', () => migratedSteward());
  await restartRestore();
} catch (e) {
  fail('run', e.message);
} finally {
  try {
    await quit();
  } catch {
    // already gone
  }
  if (client && client.exitCode === null) client.kill();
}
const failed = results.filter((r) => !r.ok).length;
fs.writeFileSync(path.join(OUT, 'summary.json'), JSON.stringify({ mode, only, skipped, at: new Date().toISOString(), seconds: Math.round((Date.now() - started) / 1000), failed, results }, null, 2));
if (only) console.log(`only ${only.join(',')}: ${skipped.length} flows skipped`);
console.log(failed === 0 ? `PASS ${results.length} checks` : `${failed} FAIL of ${results.length}`);
process.exit(failed === 0 && results.length > 0 ? 0 : 1);
