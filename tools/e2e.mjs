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
if (!['free', 'stub'].includes(mode)) {
  console.error('usage: node tools/e2e.mjs free|stub');
  process.exit(2);
}
const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);
const { Blueprint, PALETTES } = await import(pathToFileURL(path.join(ARCH, 'kit', 'lib', 'kit.mjs')).href);
const { writeBlueprint } = await import(pathToFileURL(path.join(ARCH, 'kit', 'lib', 'write.mjs')).href);

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
  client = spawn(path.join(ROOT, 'tools', 'run-e2e-client.sh'), [], { stdio: ['ignore', log, log], env: { ...process.env, E2E_STUB: mode === 'stub' ? '1' : '0' } });
}

async function connect() {
  dev = await DevClient.connect({ port: PORT, timeoutMs: 400_000 });
  await dev.waitInWorld({ timeoutMs: 400_000 });
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

const STUB = 'e2e_stub';

function stubBlueprint(extraLantern) {
  const p = PALETTES.rustic;
  const bp = new Blueprint({ id: STUB, type: 'custom', size: [9, 6, 9], origin: [0, 0, 0], palette: p, interior: [1, 1, 1, 7, 4, 4] });
  bp.room([0, 0, 0, 8, 5, 5]);
  bp.door(4, 1, 5, 'south');
  bp.lantern(2, 1, 2);
  if (extraLantern) bp.lantern(6, 1, 2);
  bp.floor(3, 6, 5, 8, 0, p.path);
  bp.spot('entrance', 4, 6, 180);
  bp.name = 'E2E stub';
  return bp;
}

/** A fresh entry every run (only this script uses it): the update check installs a version that must differ from the one placed. */
function ensureStubEntry() {
  const dir = path.join(GAME, 'architect', 'library', STUB);
  fs.rmSync(dir, { recursive: true, force: true });
  writeBlueprint(stubBlueprint(false), dir);
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
  if (file.format === 3 && file.settlements.every((x) => (x.log ?? []).every((e) => e.op))) ok('save format', `3, every entry an operation${backups.length ? ', backups kept: ' + backups.join(' ') : ''}`);
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
  await dev.request('dev.key', { key: 'escape' });
}

async function placeUpdateUndo(id) {
  // the building stands 6 blocks south of the player, 9 x 9 from there (dev place, rotation none)
  const me = (await cmd('/data get entity @p Pos')).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
  const box = me ? { x0: Math.floor(Number(me[1])), z0: Math.floor(Number(me[3])) + 6 } : null;
  if (box) Object.assign(box, { x1: box.x0 + 8, z1: box.z0 + 8 });
  let from = logSize();
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
 * Drives one build through its decisions until it is built: approves the style bible, the massings and the placement as each comes (Proposals level), with
 * a restart at the massings when asked. Returns the steps seen, or null when it stalls.
 */
async function buildThrough(id, from, label, restartAtMassings = false) {
  const steps = [];
  // past Architect's 600 s wait for a blocked item (so a blocked item fails and is reported, rather than the check giving up first)
  const end = Date.now() + 720_000;
  // nothing in the lots' way: an animal in a box holds its item up for Architect's whole wait (C30)
  await clearAnimals();
  let at = from;
  while (Date.now() < end) {
    const since = logSince(at);
    const built = since.match(/is built(?: in part)?: (\d+) buildings placed/);
    if (built) return { steps, placed: Number(built[1]) };
    const stopped = since.match(/The build stopped[^\n]*|No dry, flat room[^\n]*|No room left[^\n]*|Survey failed[^\n]*|Cancelled [^\n]*|The design group failed[^\n]*/);
    if (stopped) {
      fail(label, stopped[0]);
      return null;
    }
    const m = since.match(/Style bible ready|massings are ready|Approve to place/);
    if (!m) {
      await sleep(1000);
      continue;
    }
    steps.push(m[0]);
    if (m[0] === 'massings are ready' && restartAtMassings) {
      await quit();
      launch();
      await connect();
      if (await waitLog(0, new RegExp(`resume ${id}: [^\\n]*-> REREAD_GROUP`), 30_000)) ok('restart mid-build', label);
      else fail('restart mid-build', `${label}: no re-read`);
      restartAtMassings = false;
    }
    at = logSize();
    await cmd(`/steward approve ${id}`);
  }
  fail(label, `stalled after ${steps.join(', ') || 'nothing'}`);
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
 * Architect's plan check (P0; docs/COMPOSITION.md): recorded plans checked round the player on fresh flat land, answered part by part. A plain village
 * is ready, with an artifact; the walled town's wall is unsupported, naming connector.wall. The ready one is realised from its artifact and taken up again.
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
  const wall = walled?.parts?.find((x) => x.part === 'town_wall');
  if (wall?.class === 'unsupported' && wall.block === 'connector.wall') ok('plan check unsupported', `the wall: ${wall.class} ${wall.block}; overall ${walled.overall}`);
  else fail('plan check unsupported', JSON.stringify(walled)?.slice(0, 300));
  let from = logSize();
  await cmd('/steward dev realise_plan market_town');
  const realised = await waitLog(from, /plan realised market_town (\S+)/, 120_000);
  if (!realised || /error/.test(realised[1])) return fail('plan realise', realised ? realised[0] : 'no answer');
  await sleep(15_000);
  // off the plan (it is centred on the player, and Architect waits for a player standing in what it removes), its chunks still loaded
  await cmd('/tp @p ~ ~ ~90');
  from = logSize();
  await cmd('/steward dev unrealise_plan market_town');
  const gone = await waitLog(from, /plan unrealised market_town (\S+)/, 660_000);
  if (gone && gone[1] === 'true') ok('plan realise', `${realised[1]} realised from its approved artifact, then taken up`);
  else fail('plan realise', `realised ${realised[1]}; taken up: ${gone ? gone[1] : 'no answer'}`);
}

/** Farm animals out of the way: a pig standing on a lot holds its placement up for Architect's 10-minute wait (it happened, on the V RC). */
async function clearAnimals() {
  await cmd('/gamerule doMobSpawning false');
  for (const t of ['pig', 'cow', 'sheep', 'chicken', 'rabbit', 'horse', 'donkey', 'mule', 'llama', 'goat']) await cmd(`/kill @e[type=minecraft:${t}]`);
  // and what they drop: an item on a lot refuses its placement too (raw beef and feathers held up the library)
  await cmd('/kill @e[type=minecraft:item]');
}

async function stubFlow(id) {
  // the whole paid flow against Architect's sim helper (v0.12.2, notional measured costs, never Claude); every wait is bounded
  const fixture = (f) => JSON.stringify(JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', f), 'utf8')).card ?? JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', f), 'utf8')).answer);
  // this settlement stays on Steward's own layout (additions, districts and the rest below rest on it); villageFlow builds one on Architect's flat village
  await cmd(`/steward dev layout ${id} legacy`);
  await cmd(`/steward dev sim_answer card ${fixture('stilt_village.json')}`);
  // the plan Claude composes from the card (plan v0): a walled town, whose wall waits for Architect's walls (connector.wall)
  await cmd(`/steward dev sim_answer plan ${fixture('plans/walled_town.json')}`);
  let from = logSize();
  await cmd(`/steward describe ${id} a fishing village on stilts over a swamp`);
  if (!(await waitLog(from, /Saved\. Start building/, 120_000))) return fail('describe', `no card: ${logSince(from).split('\n').filter((l) => /CHAT/.test(l)).slice(-3).join(' | ')}`);
  const card = settlements().find((x) => x.id === id);
  ok('describe', `${card?.name}: ${(card?.card?.program ?? []).length} program entries (the scripted card through the card job)`);
  // the composed plan is checked and kept, with the wall pending on its building block
  const composed = await waitLog(from, new RegExp(`plan for ${id}: ([^\n]*)`), 240_000);
  const plans = (() => { try { return JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-plans.json'), 'utf8')).plans ?? {}; } catch { return {}; } })();
  const wallWaits = plans[id]?.pending?.find((x) => x.part === 'town_wall');
  const schemaBy = (logSince(from).match(/plan writer: the schema ([^\n(]*)/) ?? [0, '?'])[1].trim();
  if (wallWaits?.block === 'connector.wall' && /Not yet: The town wall/.test(logSince(from))) ok('composition', `${plans[id].overall} after ${plans[id].rounds} repair(s); the wall waits on ${wallWaits.block}; the schema ${schemaBy}`);
  else fail('composition', `${composed ? composed[1] : 'no plan'}; ${JSON.stringify(plans[id] ?? null)?.slice(0, 200)}`);
  // what waits for Architect, on the card before anything is spent: a stilt village over a swamp stands beside the water for now
  const limit = logSince(0).match(/Not yet: (Buildings over water[^\n]*)/);
  await closeScreens();
  await cmd(`/steward ui card ${id}`);
  await sleep(1500);
  const cardShot = (await dev.request('dev.screenshot', { name: 'e2e-card-limits', frames: 5 }, { timeoutMs: 120_000 })).path ?? '';
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

// ------------------------------------------------------------------ run

const started = Date.now();
try {
  await preflight();
  ensureStubEntry();
  launch();
  await connect();
  const r = await cmd('/steward status');
  if (/ok/.test(r)) ok('status', r.split(' | ')[0]);
  else fail('status', r);
  const id = await claimAndSteward();
  if (id) {
    await expand(id);
    await stoneRecipe();
    await inboxScreen();
    if (mode === 'free') {
      await placeUpdateUndo(id);
      await stewardLife(id);
      await proposals(id);
      await districts(id);
      await playerBuilds(id);
      await talk(id);
    }
    else {
      await stubFlow(id);
      await villageFlow();
      await planCheckFlow();
      await survivalFlow();
    }
  }
  await migratedSteward();
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
fs.writeFileSync(path.join(OUT, 'summary.json'), JSON.stringify({ mode, at: new Date().toISOString(), seconds: Math.round((Date.now() - started) / 1000), failed, results }, null, 2));
console.log(failed === 0 ? `PASS ${results.length} checks` : `${failed} FAIL of ${results.length}`);
process.exit(failed === 0 && results.length > 0 ? 0 : 1);
