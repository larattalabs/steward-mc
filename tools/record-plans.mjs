// Records the plan Claude composes (docs/COMPOSITION.md, plan v0) for each strange ask's recorded card (fixtures/strange-asks/cards/<id>.json) into
// fixtures/strange-asks/plans/<id>.json, checks each against Architect's plan/0, and writes the round's report for Architect
// (artifacts/strange-asks/plans-report.md: what doesn't fit, the kinds asked for, the parts that wait).
//
// A PAID run: it uses Noah's claude login through the sidecar's opt-in (mod/run/architect/sidecar-data/secrets.json), which this script creates for the
// run and removes afterwards, whatever happens. Run it only with Noah's approval and a spend cap: node tools/record-plans.mjs <capUsd>. It launches its own
// dev client from a scrubbed environment (no API key reaches it) on the dev ports (8490/8491), in a flat creative world "Steward Cards", and refuses to
// start while another Steward client uses mod/run. Asks that have a card already are skipped; it stops before the cap.
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ARCH = process.env.ARCHITECT_CHECKOUT ?? path.resolve(ROOT, '..', 'architect-mc');
const GAME = path.join(ROOT, 'mod', 'run');
const OPT_IN = path.join(GAME, 'architect', 'sidecar-data', 'secrets.json');
const OUT_DIR = path.join(GAME, 'steward-cards');
const CARDS = path.join(ROOT, 'fixtures', 'strange-asks', 'cards');
const PLANS = path.join(ROOT, 'fixtures', 'strange-asks', 'plans');
const PORT = 8491;
// the DevBridge token is in this game dir (Architect's client library looks in its own repo's run dir otherwise)
process.env.ARCHITECT_GAME_DIR = GAME;
const cap = Number(process.argv[2]);
if (!(cap > 0 && cap <= 100)) {
  console.error('usage: node tools/record-plans.mjs <capUsd> (the spend cap Noah approved)');
  process.exit(2);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);

try {
  execFileSync('pgrep', ['-f', 'steward-mc/mod.*KnotClient']);
  console.error('a Steward client already runs on mod/run; quit it first');
  process.exit(3);
} catch {
  // none
}

const { checkAnswer, partsOf, catalogue } = await import(pathToFileURL(path.join(ROOT, 'tools', 'lib', 'plan-check.mjs')).href);
const asks = JSON.parse(fs.readFileSync(path.join(ROOT, 'fixtures', 'strange-asks', 'asks.json'), 'utf8')).asks;
fs.mkdirSync(PLANS, { recursive: true });
const todo = asks.filter((a) => fs.existsSync(path.join(CARDS, `${a.id}.json`)) && !fs.existsSync(path.join(PLANS, `${a.id}.json`)));
console.log(`${todo.length} of ${asks.length} asks to record, cap $${cap}`);
if (!todo.length) process.exit(0);

let client = null;
let dev = null;
let spent = 0;
fs.mkdirSync(path.dirname(OPT_IN), { recursive: true });
fs.writeFileSync(OPT_IN, JSON.stringify({ useClaudeLogin: true }));
try {
  const log = fs.openSync(path.join(ROOT, 'artifacts', 'record-cards.log'), 'a');
  const env = {
    HOME: process.env.HOME, USER: process.env.USER, LOGNAME: process.env.USER, TMPDIR: process.env.TMPDIR, SHELL: '/bin/zsh', PATH: '/opt/homebrew/bin:/usr/bin:/bin',
    JAVA_HOME: '/opt/homebrew/opt/openjdk@25', GRADLE_USER_HOME: path.join(ROOT, '.gradle-home'), STEWARD_PORT: '8490', STEWARD_DEV_PORT: String(PORT),
    ARCHITECT_AUTOWORLD_NAME: 'Steward Cards', ARCHITECT_AUTOWORLD_MODE: 'creative', ARCHITECT_AUTOWORLD_PRESET: 'flat', ARCHITECT_AUTOWORLD_CHEATS: 'true',
  };
  client = spawn('./gradlew', ['--offline', ':runClient'], { cwd: path.join(ROOT, 'mod'), env, stdio: ['ignore', log, log] });
  dev = await DevClient.connect({ port: PORT, timeoutMs: 400_000 });
  await dev.waitInWorld({ timeoutMs: 400_000 });
  for (const a of todo) {
    if (spent >= cap - 0.5) {
      console.log(`stopping: $${spent.toFixed(2)} spent, near the cap`);
      break;
    }
    const out = path.join(OUT_DIR, `${a.id}.plan.json`);
    if (fs.existsSync(out)) fs.unlinkSync(out);
    const card = JSON.parse(fs.readFileSync(path.join(CARDS, `${a.id}.json`), 'utf8')).card;
    await dev.request('dev.command', { cmd: `/steward dev record_plan ${a.id} ${JSON.stringify(card)}` });
    let got = null;
    for (let i = 0; i < 240 && !got; i++) {
      await sleep(1000);
      if (fs.existsSync(out)) got = JSON.parse(fs.readFileSync(out, 'utf8'));
    }
    if (!got) {
      console.log(`${a.id}: no answer in 240 s`);
      continue;
    }
    if (got.error) {
      console.log(`${a.id}: ${got.error}`);
      continue;
    }
    spent += got.usd ?? 0;
    const errors = checkAnswer(got.answer);
    fs.writeFileSync(path.join(PLANS, `${a.id}.json`), JSON.stringify({ prompt: a.ask, answer: got.answer, usd: got.usd, errors }, null, 2) + '\n');
    console.log(`${a.id}: $${(got.usd ?? 0).toFixed(4)} ${errors.length ? errors.length + ' problems' : 'valid'}, ${got.answer.waits?.length ?? 0} waits (total $${spent.toFixed(2)})`);
  }
  report();
} finally {
  // the opt-in goes first, whatever happened
  if (fs.existsSync(OPT_IN)) fs.unlinkSync(OPT_IN);
  console.log(`opt-in removed; spent $${spent.toFixed(2)}`);
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

/** The round's report for Architect: what doesn't fit plan/0, the kinds asked for and their catalogue state, and what waits. */
function report() {
  const rows = [];
  const kinds = {};
  const problems = {};
  for (const f of fs.readdirSync(PLANS).filter((n) => n.endsWith('.json'))) {
    const r = JSON.parse(fs.readFileSync(path.join(PLANS, f), 'utf8'));
    const parts = partsOf(r.answer?.plan);
    for (const p of parts) {
      const key = `${p.family.replace(/s$/, '')}.${p.kind}`;
      kinds[key] = (kinds[key] ?? 0) + 1;
    }
    for (const e of r.errors ?? []) problems[e.replace(/\/\d+/g, '/N')] = (problems[e.replace(/\/\d+/g, '/N')] ?? 0) + 1;
    rows.push(`| ${f.replace('.json', '')} | ${(r.errors ?? []).length ? 'invalid' : 'valid'} | ${parts.length} | ${(r.answer?.waits ?? []).map((w) => w.part).join(', ')} |`);
  }
  const lines = ['# Plan v0 round: the strange asks against plan/0', '', `${rows.length} plans.`, '', '## Kinds asked for', '', '| Kind | Parts | Catalogue |', '|---|---|---|'];
  for (const [k, n] of Object.entries(kinds).sort((a, b) => b[1] - a[1])) lines.push(`| ${k} | ${n} | ${catalogue[k] ?? 'not listed'} |`);
  lines.push('', '## What does not fit plan/0', '', '| Problem | Plans |', '|---|---|');
  for (const [k, n] of Object.entries(problems).sort((a, b) => b[1] - a[1])) lines.push(`| ${k} | ${n} |`);
  lines.push('', '## Each plan', '', '| Ask | plan/0 | Parts | Waits |', '|---|---|---|---|', ...rows);
  fs.mkdirSync(path.join(ROOT, 'artifacts', 'strange-asks'), { recursive: true });
  fs.writeFileSync(path.join(ROOT, 'artifacts', 'strange-asks', 'plans-report.md'), lines.join('\n') + '\n');
  console.log('report: artifacts/strange-asks/plans-report.md');
}
