#!/usr/bin/env node
// Steward's end-to-end check, driven through Architect's DevBridge like Architect's gate scripts. It launches its own client (tools/run-e2e-client.sh:
// ports 8590/8591, the flat creative world "Steward E2E", no Claude), runs the steps, and prints one line per check: "ok <check>" or "FAIL <check>: why".
// Exit 0 = no FAIL and at least one ok. A summary goes to artifacts/e2e/summary.json.
//
//   node tools/e2e.mjs free     $0, no Claude: claim and steward, the save format, expand, the inbox screen, a placed building on the settlement screen and its panel,
//                               its update and undo, a build restored after a restart
//   node tools/e2e.mjs stub     the whole flow (describe, card, start, approvals, placement, a restart mid-build, undo) against Architect's stub sidecar;
//                               needs the stub to answer card jobs, bibles and design groups (Architect 6c slice 0, ask C4): until then it fails at describe
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

async function claimAndSteward() {
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
  if (file.format === 2 && file.settlements.every((x) => (x.log ?? []).every((e) => e.op))) ok('save format', `2, every entry an operation${fs.existsSync(path.join(WORLD, 'steward-settlements.json.v1.bak')) ? ', v1 backup kept' : ''}`);
  else fail('save format', `format ${file.format}`);
  let name = '';
  for (let i = 0; i < 10 && !/Steward/.test(name); i++) {
    if (i > 0) await sleep(1000);
    name = await cmd(`/data get entity @e[type=minecraft:mannequin,tag=steward_mc.settlement.${s.id},limit=1] CustomName`);
  }
  if (/Steward/.test(name)) ok('steward', 'named, tagged');
  else fail('steward', name || 'no mannequin');
  return s.id;
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
  let from = logSize();
  await cmd(`/steward dev place ${id} ${STUB}`);
  const placed = await waitLog(from, /dev placed (s\d+)/, 30_000);
  if (!placed) return fail('dev place', (await waitLog(from, /dev place refused[^\n]*/, 1) ?? ['no answer'])[0]);
  const site = placed[1];
  ok('dev place', site);
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
  const checked = await cmd('/steward updates');
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

async function restartRestore() {
  const uuid = await playerUuid();
  await quit();
  const tpl = fs.readFileSync(path.join(ROOT, 'tools', 'e2e', 'restore-build.json'), 'utf8').replaceAll('__SETTLEMENT__', 'e2e_restore').replaceAll('__GROUP__', 'grp_e2e_missing')
    .replaceAll('00000000-0000-0000-0000-000000000042', uuid);
  fs.writeFileSync(path.join(WORLD, 'steward-builds.json'), tpl);
  launch();
  await connect();
  const m = await waitLog(0, /resume e2e_restore: phase (\w+)[^\n]*-> (\w+)/, 30_000);
  if (m && m[2] === 'REREAD_GROUP' && /interrupted/.test(logSince(0))) ok('restart', `restored at ${m[1]}, re-read, reported interrupted`);
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

async function stubFlow(id) {
  // the whole flow against the stub sidecar (Architect 6c slice 0, C4); every wait is bounded
  let from = logSize();
  await cmd(`/steward describe ${id} an e2e hamlet: three cottages by a road`);
  if (!(await waitLog(from, /Saved\. Start building/, 60_000))) return fail('describe', 'no card (the stub does not answer card jobs yet: Architect C4)');
  ok('describe');
  from = logSize();
  await cmd(`/steward start ${id} 3 20`);
  for (const step of ['style bible', 'massings', 'place']) {
    const re = step === 'style bible' ? /Style bible ready/ : step === 'massings' ? /massings are ready/ : /Approve to place/;
    if (!(await waitLog(from, re, 300_000))) return fail(step, 'not reached');
    if (step === 'massings') {
      await quit();
      launch();
      await connect();
      if (await waitLog(0, new RegExp(`resume ${id}: [^\\n]*-> REREAD_GROUP`), 30_000)) ok('restart mid-build');
      else fail('restart mid-build', 'no re-read');
    }
    from = logSize();
    await cmd(`/steward approve ${id}`);
    ok(step);
  }
  if (await waitLog(from, /is built: [1-9]\d* buildings placed/, 300_000)) ok('placed');
  else fail('placed', 'no finish');
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
    await inboxScreen();
    if (mode === 'free') await placeUpdateUndo(id);
    else await stubFlow(id);
  }
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
