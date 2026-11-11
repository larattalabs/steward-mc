#!/usr/bin/env node
// Steward's nightly full e2e (Noah, 2026-11-10; AGENTS.md DEV MODE: full stub and free runs are nightly). At 23:00 the LaunchAgent
// dev.larattalabs.steward-nightly runs this: fetch origin, move the nightly's own worktree (../_work/steward-nightly) to a detached
// origin/main, build the mod there (compile, unit tests, jar), then `node tools/e2e.mjs free` and `node tools/e2e.mjs stub` there, and write
// the night's results to this checkout's artifacts/nightly/<date>/ (free/stub summary.json, logs, SUMMARY.md). $0: the e2e never spends and
// refuses while the claude-login opt-in exists. No notification of any kind.
//
//   node tools/nightly.mjs              the night's run (what the LaunchAgent starts)
//   node tools/nightly.mjs --dry        the checks only: says whether tonight would run or be skipped and why; changes nothing
//   node tools/nightly.mjs --prepare    the worktree step only (fetch, create or move it, link .gradle-home/node_modules); no build, no e2e
//   node tools/nightly.mjs --install    writes ~/Library/LaunchAgents/dev.larattalabs.steward-nightly.plist (23:00) and loads it
//   node tools/nightly.mjs --uninstall  unloads and removes it
//
// It never kills a process it didn't start and never touches another checkout's tree:
//   - one nightly at a time: artifacts/nightly/nightly.lock (pid); a live holder -> exit 0 ("already active");
//   - the night is skipped (exit 0, SUMMARY.md says why) if the e2e ports (8590/8591) are taken, if any Steward client runs (a dev client
//     on mod/run, or record-plans), or if a claude-login opt-in exists in this checkout or the worktree;
//   - the main checkout is only fetched, never checked out or pulled; the worktree is moved only when clean, else the night is skipped;
//   - a hard limit of 2 h 45 min for the whole night: past it, the e2e's own process group (started here) is stopped and the night is a TIMEOUT.
// It runs beside Architect's 02:00 nightly without clashing: other ports (Architect's gates use 8890-8905), no gate slot, and it ends by about
// 01:30 on a normal night (free about 7 min, stub about 2 h).
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MAIN = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const WT = path.resolve(MAIN, '..', '_work', 'steward-nightly');
const OUT_ROOT = path.join(MAIN, 'artifacts', 'nightly');
const LOCK = path.join(OUT_ROOT, 'nightly.lock');
const LABEL = 'dev.larattalabs.steward-nightly';
const PLIST = path.join(os.homedir(), 'Library', 'LaunchAgents', `${LABEL}.plist`);
const LOG = path.join(os.homedir(), 'Library', 'Logs', 'steward-nightly.log');
const PORTS = [8590, 8591];
const LIMIT_MS = (2 * 60 + 45) * 60_000;
const KEEP_DAYS = 21;
const JAVA_HOME = '/opt/homebrew/opt/openjdk@25';

const args = process.argv.slice(2);
const log = (s) => console.log(`[${new Date().toISOString()}] nightly: ${s}`);

if (args.includes('--install')) install();
else if (args.includes('--uninstall')) uninstall();
else if (args.includes('--prepare')) console.log(JSON.stringify(prepareWorktree()));
else await night(args.includes('--dry'));

// ------------------------------------------------------------------ the night

async function night(dry) {
  const date = localDate();
  const out = path.join(OUT_ROOT, date);
  const why = busy();
  if (why.length) {
    log(`skipped: ${why.join('; ')}`);
    if (!dry) writeSummary(out, { verdict: 'SKIPPED', why });
    return;
  }
  if (dry) {
    log(`would run: worktree ${WT} at origin/main, build, e2e free, e2e stub; results in ${out}`);
    return;
  }
  fs.mkdirSync(OUT_ROOT, { recursive: true });
  fs.writeFileSync(LOCK, String(process.pid));
  const started = Date.now();
  const deadline = started + LIMIT_MS;
  const steps = [];
  try {
    fs.mkdirSync(out, { recursive: true });
    const sha = prepareWorktree();
    if (!sha.ok) {
      writeSummary(out, { verdict: 'SKIPPED', why: [sha.why] });
      log(`skipped: ${sha.why}`);
      return;
    }
    log(`${WT} at origin/main ${sha.sha}`);
    const env = cleanEnv();
    const build = await run('build', ['./gradlew', 'build', '--offline', '-q'], path.join(WT, 'mod'), out, deadline, env);
    steps.push(build);
    if (build.code === 0) {
      for (const mode of ['free', 'stub']) {
        const r = await run(mode, [process.execPath, path.join(WT, 'tools', 'e2e.mjs'), mode], WT, out, deadline, env);
        const sum = path.join(WT, 'artifacts', 'e2e', 'summary.json');
        if (fs.existsSync(sum)) {
          fs.copyFileSync(sum, path.join(out, `${mode}-summary.json`));
          const s = JSON.parse(fs.readFileSync(sum, 'utf8'));
          // e2e's summary.json has failed as a count; the names are the results that are not ok, a repeated name once with its count
          const names = (s.results ?? []).filter((x) => !x.ok).map((x) => x.check);
          r.failed = [...new Set(names)].map((n) => { const k = names.filter((m) => m === n).length; return k > 1 ? `${n} x${k}` : n; });
          r.checks = (s.results ?? []).length;
          fs.rmSync(sum);
        }
        steps.push(r);
        if (r.timedOut) break;
      }
    }
    const timedOut = steps.some((s) => s.timedOut);
    const verdict = timedOut ? 'TIMEOUT' : steps.every((s) => s.code === 0) && steps.length === 3 ? 'PASS' : 'FAIL';
    writeSummary(out, { verdict, sha: sha.sha, steps, minutes: (Date.now() - started) / 60_000 });
    log(`${verdict}, ${((Date.now() - started) / 60_000).toFixed(1)} min; ${path.join(out, 'SUMMARY.md')}`);
    prune();
  } finally {
    fs.rmSync(LOCK, { force: true });
  }
}

/** Why tonight can't run, or [] when it can. */
function busy() {
  const why = [];
  if (fs.existsSync(LOCK)) {
    const pid = Number(fs.readFileSync(LOCK, 'utf8'));
    if (alive(pid)) why.push(`already active (pid ${pid})`);
  }
  for (const p of PORTS) {
    try {
      const holder = execFileSync('lsof', ['-nP', `-iTCP:${p}`, '-sTCP:LISTEN', '-t'], { encoding: 'utf8' }).trim();
      if (holder) why.push(`port ${p} is taken (pid ${holder.split('\n').join(',')})`);
    } catch {
      // free
    }
  }
  try {
    const pids = execFileSync('pgrep', ['-f', 'steward[^ ]*/mod.*KnotClient'], { encoding: 'utf8' }).trim();
    if (pids) why.push(`a Steward client runs (pid ${pids.split('\n').join(',')})`);
  } catch {
    // none
  }
  for (const root of [MAIN, WT]) {
    const optIn = path.join(root, 'mod', 'run', 'architect', 'sidecar-data', 'secrets.json');
    if (fs.existsSync(optIn)) why.push(`the claude-login opt-in exists (${optIn})`);
  }
  return why;
}

/** Fetch origin; create the worktree, or move it (only when clean) to a detached origin/main. */
function prepareWorktree() {
  const git = (cwd, ...a) => execFileSync('git', a, { cwd, encoding: 'utf8' }).trim();
  git(MAIN, 'fetch', '--quiet', 'origin');
  if (!fs.existsSync(WT)) git(MAIN, 'worktree', 'add', '--detach', WT, 'origin/main');
  const dirty = git(WT, 'status', '--porcelain', '--untracked-files=no');
  if (dirty) return { ok: false, why: `the worktree ${WT} has changes: ${dirty.split('\n').slice(0, 3).join(' | ')}` };
  git(WT, 'checkout', '--quiet', '--detach', 'origin/main');
  // a worktree has no .gradle-home or node_modules: link the main checkout's (untracked, gitignored)
  for (const l of ['.gradle-home', 'node_modules']) {
    const at = path.join(WT, l);
    if (!fs.existsSync(at) && fs.existsSync(path.join(MAIN, l))) fs.symlinkSync(path.join(MAIN, l), at);
  }
  return { ok: true, sha: git(WT, 'rev-parse', '--short', 'HEAD') };
}

/** No credentials of any kind; the main checkout's Gradle home. */
function cleanEnv() {
  const env = { ...process.env, JAVA_HOME, GRADLE_USER_HOME: path.join(MAIN, '.gradle-home') };
  for (const k of ['ANTHROPIC_API_KEY', 'ANTHROPIC_BASE_URL', 'CLAUDE_CODE_OAUTH_TOKEN', 'CLAUDE_CODE_OAUTH_SCOPES', 'CLAUDE_CODE_USE_BEDROCK', 'CLAUDE_CODE_USE_VERTEX', 'GITHUB_TOKEN']) delete env[k];
  return env;
}

/** One step in its own process group, its output in <out>/<name>.log; stopped (that group only) at the night's deadline. */
function run(name, argv, cwd, out, deadline, env) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    const fd = fs.openSync(path.join(out, `${name}.log`), 'a');
    const [cmd, ...a] = argv;
    const child = spawn(cmd, a, { cwd, env, stdio: ['ignore', fd, fd], detached: true });
    log(`${name}: started (pid ${child.pid})`);
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      log(`${name}: past the night's limit, stopping its process group ${child.pid}`);
      try { process.kill(-child.pid, 'SIGTERM'); } catch { /* gone */ }
      setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL'); } catch { /* gone */ } }, 30_000);
    }, Math.max(0, deadline - Date.now()));
    child.on('exit', (code) => {
      clearTimeout(timer);
      fs.closeSync(fd);
      const r = { name, code: code ?? -1, minutes: (Date.now() - t0) / 60_000, timedOut };
      log(`${name}: exit ${r.code}, ${r.minutes.toFixed(1)} min`);
      resolve(r);
    });
  });
}

function writeSummary(out, s) {
  fs.mkdirSync(out, { recursive: true });
  const lines = [`# Steward nightly ${path.basename(out)}: ${s.verdict}`, ''];
  if (s.why) lines.push(...s.why.map((w) => `- skipped: ${w}`));
  if (s.sha) lines.push(`origin/main ${s.sha}, ${s.minutes.toFixed(1)} min`, '');
  for (const st of s.steps ?? []) {
    const head = `- **${st.name}:** exit ${st.code}${st.timedOut ? ' (TIMEOUT)' : ''}, ${st.minutes.toFixed(1)} min`;
    lines.push(st.checks == null ? head : `${head}, ${st.checks} checks, ${st.failed.length} failed${st.failed.length ? ': ' + st.failed.join(', ') : ''}`);
  }
  lines.push('', `Logs: ${out}/{build,free,stub}.log`);
  fs.writeFileSync(path.join(out, 'SUMMARY.md'), lines.join('\n') + '\n');
}

/** Keep three weeks of nights. */
function prune() {
  const cut = Date.now() - KEEP_DAYS * 86_400_000;
  for (const d of fs.readdirSync(OUT_ROOT).filter((n) => /^\d{4}-\d{2}-\d{2}$/.test(n))) {
    if (new Date(`${d}T12:00:00`).getTime() < cut) fs.rmSync(path.join(OUT_ROOT, d), { recursive: true, force: true });
  }
}

function alive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function localDate() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// ------------------------------------------------------------------ the LaunchAgent

function install() {
  // mise's lts link outlives a node upgrade (the versioned path doesn't); Architect's agent uses it too
  const lts = path.join(os.homedir(), '.local', 'share', 'mise', 'installs', 'node', 'lts', 'bin', 'node');
  const nodeBin = fs.existsSync(lts) ? lts : process.execPath;
  const plist = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key><array><string>${nodeBin}</string><string>${path.join(MAIN, 'tools', 'nightly.mjs')}</string></array>
  <key>WorkingDirectory</key><string>${MAIN}</string>
  <key>EnvironmentVariables</key><dict>
    <key>HOME</key><string>${os.homedir()}</string>
    <key>JAVA_HOME</key><string>${JAVA_HOME}</string>
    <key>PATH</key><string>${path.dirname(nodeBin)}:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>StartCalendarInterval</key><dict><key>Hour</key><integer>23</integer><key>Minute</key><integer>0</integer></dict>
  <key>RunAtLoad</key><false/>
  <key>ProcessType</key><string>Background</string>
  <key>StandardOutPath</key><string>${LOG}</string>
  <key>StandardErrorPath</key><string>${LOG}</string>
</dict>
</plist>
`;
  fs.mkdirSync(path.dirname(PLIST), { recursive: true });
  try { execFileSync('launchctl', ['bootout', `gui/${process.getuid()}/${LABEL}`], { stdio: 'ignore' }); } catch { /* not loaded */ }
  fs.writeFileSync(PLIST, plist);
  execFileSync('launchctl', ['bootstrap', `gui/${process.getuid()}`, PLIST]);
  console.log(`installed ${PLIST} (23:00 daily); log ${LOG}`);
}

function uninstall() {
  try { execFileSync('launchctl', ['bootout', `gui/${process.getuid()}/${LABEL}`], { stdio: 'ignore' }); } catch { /* not loaded */ }
  fs.rmSync(PLIST, { force: true });
  console.log(`removed ${PLIST}`);
}
