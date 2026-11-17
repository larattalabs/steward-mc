// Slice CA's in-game paid proof: one settlement described in a fresh dev world and composed through Architect's composer (Regions.compose), the
// product path (the card job, then Composer.compose with the stone and player anchors, Steward's $0 re-check and keep).
//
// A PAID run: it uses Noah's claude login through the sidecar's opt-in (mod/run/architect/sidecar-data/secrets.json), which this script creates for the
// run and removes afterwards, whatever happens. Run it only with Noah's approval and a spend cap:
//   node tools/compose-proof.mjs <capUsd> <words...>
// The card job's cap ($0.10) is taken off the cap first, and the compose is sent with what is left (STEWARD_COMPOSE_CAP_USD, which only lowers
// Architect's $1.00), so the run can't pass <capUsd>. Ports: STEWARD_PORT / STEWARD_DEV_PORT (default 8490/8491). It refuses to start while the opt-in exists.
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
const PORT = Number(process.env.STEWARD_DEV_PORT ?? 8491);
const SIDECAR_PORT = String(process.env.STEWARD_PORT ?? 8490);
const CARD_CAP = 0.10;
process.env.ARCHITECT_GAME_DIR = GAME;
const cap = Number(process.argv[2]);
const words = process.argv.slice(3).join(' ').trim();
if (!(cap > CARD_CAP + 0.05 && cap <= 1.0) || !words) {
  console.error('usage: node tools/compose-proof.mjs <capUsd, at most 1.00> <words...>');
  process.exit(2);
}
if (fs.existsSync(OPT_IN)) {
  console.error(`the opt-in already exists (${OPT_IN}): another paid run? stopping`);
  process.exit(3);
}
if (fs.existsSync(WORLD)) {
  console.error(`${WORLD} exists: move it aside first (a fresh world each run)`);
  process.exit(3);
}
const composeCap = Math.floor((cap - CARD_CAP) * 100) / 100;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);
const LOG = path.join(GAME, 'logs', 'latest.log');
const logText = () => (fs.existsSync(LOG) ? fs.readFileSync(LOG, 'utf8') : '');

let client = null;
let dev = null;
const result = { words, cap, composeCap };
// the opt-in goes on any exit: a signal or a crash skips `finally`
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
  process.exit(130);
});
fs.mkdirSync(path.dirname(OPT_IN), { recursive: true });
fs.writeFileSync(OPT_IN, JSON.stringify({ useClaudeLogin: true }));
try {
  fs.mkdirSync(path.join(ROOT, 'artifacts'), { recursive: true });
  const log = fs.openSync(path.join(ROOT, 'artifacts', 'compose-proof.log'), 'a');
  const env = {
    HOME: process.env.HOME, USER: process.env.USER, LOGNAME: process.env.USER, TMPDIR: process.env.TMPDIR, SHELL: '/bin/zsh', PATH: '/opt/homebrew/bin:/usr/bin:/bin',
    JAVA_HOME: '/opt/homebrew/opt/openjdk@25', GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? path.join(ROOT, '.gradle-home'), STEWARD_PORT: SIDECAR_PORT,
    STEWARD_DEV_PORT: String(PORT), STEWARD_COMPOSE_CAP_USD: composeCap.toFixed(2),
    ARCHITECT_AUTOWORLD_NAME: WORLD_NAME, ARCHITECT_AUTOWORLD_MODE: process.env.ARCHITECT_AUTOWORLD_MODE || 'creative', ARCHITECT_AUTOWORLD_PRESET: process.env.ARCHITECT_AUTOWORLD_PRESET || 'default', ARCHITECT_AUTOWORLD_CHEATS: 'true',
  };
  client = spawn('./gradlew', ['--offline', `-Plab_ui.dir=${process.env.LAB_UI_DIR || 'none'}`, ':runClient'], { cwd: path.join(ROOT, 'mod'), env, stdio: ['ignore', log, log] });
  dev = await DevClient.connect({ port: PORT, timeoutMs: 400_000 });
  await dev.waitInWorld({ timeoutMs: 400_000 });
  for (let i = 0; i < 180; i++) {
    if (/Launcher: running/.test(logText())) break;
    if (i === 179) throw new Error("Architect's helper didn't start in 6 minutes");
    await sleep(2000);
  }
  await dev.request('dev.waitChunks', { timeoutMs: 60_000 }).catch(() => {});
  await dev.request('dev.command', { cmd: '/steward claim' });
  await sleep(2000);
  const ss = JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-settlements.json'), 'utf8')).settlements;
  const id = ss.at(-1).id;
  result.settlement = id;
  const from = logText().length;
  await dev.request('dev.command', { cmd: `/steward describe ${id} ${words}` });
  const end = Date.now() + 15 * 60_000;
  let kept = null;
  let failed = null;
  while (Date.now() < end && !kept && !failed) {
    await sleep(3000);
    const t = logText().slice(from);
    kept = t.match(new RegExp(`plan for ${id}: ([A-Z_]+) after (\\d+) repair\\(s\\)[^\\n]*`));
    failed = t.match(new RegExp(`plan for ${id}: (The plan could not be composed[^\\n]*|A plan is composed only[^\\n]*|This card was composed already[^\\n]*)`));
  }
  const t = logText().slice(from);
  result.compose = t.match(new RegExp(`plan for ${id}: Architect's compose [^\\n]*`))?.[0] ?? null;
  result.kept = kept?.[0] ?? null;
  result.failed = failed?.[1] ?? (kept ? null : 'no plan in 15 minutes');
  result.chat = t.split('\n').filter((l) => /\[CHAT\]/.test(l)).slice(-12);
  result.card = t.match(/Card[^\n]*\$\d+\.\d+[^\n]*/)?.[0] ?? null;
  const plans = JSON.parse(fs.readFileSync(path.join(WORLD, 'steward-plans.json'), 'utf8')).plans ?? {};
  result.entry = plans[id] ?? null;
} catch (e) {
  result.error = String(e?.message ?? e);
} finally {
  if (fs.existsSync(OPT_IN)) fs.unlinkSync(OPT_IN);
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
