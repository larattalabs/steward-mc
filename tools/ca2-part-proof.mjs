// Slice CA2's paid proof of a waiting part fitted through Architect's composer (Regions.compose with ComposeJob.partRequest), after
// tools/compose-proof.mjs has described and composed a settlement in "Steward Compose Proof" (Noah, 2026-11-15: three runs, $1.50 in all).
//   node tools/ca2-part-proof.mjs town          $0: Architect's helper in sim mode (no opt-in, never Claude) builds the kept plan as the town region
//   node tools/ca2-part-proof.mjs part <capUsd> PAID: a wall pended, offered and accepted; its compose is capped at <capUsd> (STEWARD_COMPOSE_CAP_USD)
// The part run creates the claude-login opt-in (mod/run/architect/sidecar-data/secrets.json) and removes it on any exit. Ports: 8590/8591 by default.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ARCH = process.env.ARCHITECT_CHECKOUT ?? path.resolve(ROOT, '..', 'architect-mc');
const GAME = path.join(ROOT, 'mod', 'run');
const OPT_IN = path.join(GAME, 'architect', 'sidecar-data', 'secrets.json');
const WORLD_NAME = process.env.STEWARD_PROOF_WORLD || 'Steward Compose Proof';
const WORLD = path.join(GAME, 'saves', WORLD_NAME);
const PORT = Number(process.env.STEWARD_DEV_PORT ?? 8591);
const SIDECAR_PORT = String(process.env.STEWARD_PORT ?? 8590);
process.env.ARCHITECT_GAME_DIR = GAME;
const phase = process.argv[2];
const cap = Number(process.argv[3]);
if (!['town', 'part'].includes(phase) || (phase === 'part' && !(cap > 0 && cap <= 1.0))) {
  console.error('usage: node tools/ca2-part-proof.mjs town | part <capUsd, at most 1.00>');
  process.exit(2);
}
if (fs.existsSync(OPT_IN)) {
  console.error(`the opt-in already exists (${OPT_IN}): another paid run? stopping`);
  process.exit(3);
}
if (!fs.existsSync(path.join(WORLD, 'steward-plans.json'))) {
  console.error(`${WORLD} holds no kept plan: run tools/compose-proof.mjs first`);
  process.exit(3);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);
const LOG = path.join(GAME, 'logs', 'latest.log');
const logText = () => (fs.existsSync(LOG) ? fs.readFileSync(LOG, 'utf8') : '');
const settlements = () => JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-settlements.json'), 'utf8')).settlements;
const plans = () => JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-plans.json'), 'utf8')).plans ?? {};

let client = null;
let dev = null;
const result = { phase, cap: phase === 'part' ? cap : 0 };
const dropOptIn = () => {
  try {
    if (fs.existsSync(OPT_IN)) fs.unlinkSync(OPT_IN);
  } catch {
    // nothing more to do
  }
};
process.on('exit', dropOptIn);
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(sig, () => {
  dropOptIn();
  if (client && client.exitCode === null) client.kill('SIGTERM');
  process.exit(130);
});
const cmd = async (c) => ((await dev.request('dev.command', { cmd: c })).messages ?? []).join(' | ');
const clearAnimals = async () => {
  await cmd('/gamerule doMobSpawning false');
  for (const t of ['pig', 'cow', 'sheep', 'chicken', 'rabbit', 'horse', 'donkey', 'mule', 'llama', 'goat', 'trader_llama', 'wandering_trader', 'fox']) await cmd(`/kill @e[type=minecraft:${t}]`);
  await cmd('/kill @e[type=minecraft:item]');
};
const closeScreens = async () => {
  for (let i = 0; i < 3; i++) {
    const st = await dev.request('dev.state');
    if (!st.screen) return;
    await dev.request('dev.key', { key: 'escape' });
    await sleep(200);
  }
};

if (phase === 'part') {
  fs.mkdirSync(path.dirname(OPT_IN), { recursive: true });
  fs.writeFileSync(OPT_IN, JSON.stringify({ useClaudeLogin: true }));
}
try {
  fs.mkdirSync(path.join(ROOT, 'artifacts'), { recursive: true });
  const log = fs.openSync(path.join(ROOT, 'artifacts', `ca2-${phase}.log`), 'a');
  const env = {
    HOME: process.env.HOME, USER: process.env.USER, LOGNAME: process.env.USER, TMPDIR: process.env.TMPDIR, SHELL: '/bin/zsh', PATH: '/opt/homebrew/bin:/usr/bin:/bin',
    JAVA_HOME: '/opt/homebrew/opt/openjdk@25', GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? path.join(ROOT, '.gradle-home'), STEWARD_PORT: SIDECAR_PORT,
    STEWARD_DEV_PORT: String(PORT), ARCHITECT_AUTOWORLD_NAME: WORLD_NAME, ARCHITECT_AUTOWORLD_MODE: process.env.ARCHITECT_AUTOWORLD_MODE || 'creative', ARCHITECT_AUTOWORLD_PRESET: process.env.ARCHITECT_AUTOWORLD_PRESET || 'default',
    ARCHITECT_AUTOWORLD_CHEATS: 'true',
    ...(phase === 'town' ? { ARCHITECT_SIDECAR_BACKEND: 'sim', ARCHITECT_SIM_COSTS: 'measured' } : { STEWARD_COMPOSE_CAP_USD: cap.toFixed(2) }),
  };
  client = spawn('./gradlew', ['--offline', `-Plab_ui.dir=${process.env.LAB_UI_DIR || 'none'}`, ':runClient'], { cwd: path.join(ROOT, 'mod'), env, stdio: ['ignore', log, log] });
  result.clientPid = client.pid;
  dev = await DevClient.connect({ port: PORT, timeoutMs: 400_000 });
  await dev.waitInWorld({ timeoutMs: 400_000 });
  for (let i = 0; i < 180; i++) {
    if (/Launcher: running/.test(logText())) break;
    if (i === 179) throw new Error("Architect's helper didn't start in 6 minutes");
    await sleep(2000);
  }
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 }).catch(() => {});
  await closeScreens();
  const s = settlements().at(-1);
  const id = s.id;
  result.settlement = id;
  if (phase === 'town') {
    await clearAnimals();
    const from = logText().length;
    result.start = await cmd(`/steward start ${id} 5 30`);
    await cmd('/tp @p ~ ~ ~90');
    const end = Date.now() + 20 * 60_000;
    let approvedAt = from;
    let lastClear = Date.now();
    while (Date.now() < end) {
      await sleep(2000);
      const t = logText();
      const since = t.slice(approvedAt);
      const built = t.slice(from).match(/is built(?: in part)?: (\d+) buildings? placed[^\n]*/);
      if (built) { result.built = built[0]; break; }
      const stopped = t.slice(from).match(/The build stopped[^\n]*|No dry, flat room[^\n]*|No room left[^\n]*|Survey failed[^\n]*|Cancelled [^\n]*|The design group failed[^\n]*|No building of [^\n]* was placed/);
      if (stopped) { result.stopped = stopped[0]; break; }
      if (/Style bible ready|massings? (?:are|is) ready|Approve to place|Architect holds the \S+ stage/.test(since)) {
        approvedAt = t.length;
        await cmd(`/steward approve ${id}`);
      }
      if (Date.now() - lastClear > 20_000 || /in its lot|standing on its lot/.test(since)) { await clearAnimals(); lastClear = Date.now(); }
    }
    result.layout = logText().slice(from).match(/Laying out the village[^\n]*/)?.[0] ?? null;
    const town = (settlements().find((x) => x.id === id)?.log ?? []).filter((e) => e.kind === 'PROJECT_PLACED').at(-1);
    result.town = town ? { siteGroup: town.siteGroup, sites: town.siteIds.length, text: town.text } : null;
  } else {
    const from = logText().length;
    result.pend = await cmd(`/steward dev pend ${id} connector.wall {"id":"town_wall","kind":"wall","required":true,"label":"the town wall","notes":"A stone wall round the town."}`);
    result.offered = await cmd(`/steward dev offer_parts ${id}`);
    const offer = settlements().find((x) => x.id === id)?.proposals?.open?.find((p) => p.key === 'part_town_wall');
    result.offer = offer ? { title: offer.title, why: offer.why } : null;
    if (!offer) throw new Error(`not offered: ${result.offered}`);
    await clearAnimals();
    result.accept = await cmd(`/steward proposals ${id} accept part_town_wall`);
    const end = Date.now() + 15 * 60_000;
    let wall = null;
    let composed = null;
    let failed = null;
    while (Date.now() < end && !wall && !failed) {
      await sleep(3000);
      const t = logText().slice(from);
      composed = t.match(/part town_wall of [^\n]*Architect's compose [^\n]*/)?.[0] ?? composed;
      failed = t.match(/\[CHAT\][^\n]*(?:couldn't|could not|can't be built|Not yet|no plan|failed)[^\n]*wall[^\n]*|\[CHAT\][^\n]*wall[^\n]*(?:couldn't|could not|failed|stopped)[^\n]*/i)?.[0] ?? null;
      wall = (settlements().find((x) => x.id === id)?.log ?? []).find((e) => e.kind === 'PROJECT_PLACED' && /town wall/i.test(e.text)) ?? null;
      // a part Architect adjusted is shown first: the second accept builds it as shown
      if (!wall && /adjusted|as shown|accept (?:it )?again/i.test(t) && !result.secondAccept) result.secondAccept = await cmd(`/steward proposals ${id} accept part_town_wall`);
    }
    const t = logText().slice(from);
    result.compose = composed;
    result.failed = failed ?? (wall ? null : 'no wall in 15 minutes');
    result.wall = wall ? { siteGroup: wall.siteGroup, sites: wall.siteIds.length, text: wall.text } : null;
    result.stillWaits = plans()[id]?.pending?.some((x) => x.part === 'town_wall') ?? null;
    result.chat = t.split('\n').filter((l) => /\[CHAT\]/.test(l)).slice(-12);
  }
} catch (e) {
  result.error = String(e?.message ?? e);
} finally {
  dropOptIn();
  result.optInRemoved = !fs.existsSync(OPT_IN);
  console.log(JSON.stringify(result, null, 2));
  if (dev) {
    try {
      const r = await dev.request('dev.quit');
      if (r.ok) await dev.waitClosed(120_000);
    } catch {
      // closing anyway
    }
  }
  if (client && client.exitCode === null) {
    await sleep(5000);
    if (client.exitCode === null) client.kill('SIGTERM');
  }
}
