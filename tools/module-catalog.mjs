#!/usr/bin/env node
// Phase 5 slice M1 (docs/MODULES.md 2.2 and 8): the module catalog's check, $0, no Claude.
//
//   node tools/module-catalog.mjs offline   every catalog entry's program on Architect's kit at the pinned version (gradle.properties
//                                           architect_version -> tag v<ver>, archived from ../architect-mc into artifacts/m1/, never its
//                                           HEAD): the sha pinned in module.json and measured.json, the params schema equal to the program's
//                                           own, every branch of the envelope re-skinned by each of its bibles and checked by the kit's
//                                           checker (no error); the palette constraints (a too-dim lamp -> the fallback lantern, said; a
//                                           gravity block on a core role -> refused); the lot's reserved cells left open.
//   node tools/module-catalog.mjs ingame    the same entries through Steward's own code in a dev client (tools/run-e2e-client.sh on
//                                           8592/8593, a fresh flat creative world, Architect's real helper with no login): the gate,
//                                           the catalog entry installed at $0 by its key (Designs.fromProgram) and the key replayed, every
//                                           branch x bible as a keyed Designs.variant (installed = the checker passed), each library entry's
//                                           givenProgram.sha = the catalog's, params outside the envelope refused before Architect is
//                                           asked, Designs.variantByKey finding a variant (an unknown key: none).
//   node tools/module-catalog.mjs record    writes the two steps' results into each fixture entry's measured.json `checks`.
//   --catalog <dir>     the catalog root (default fixtures/modules/catalog; the live one is modules/)
//   --attach            use a client already running on STEWARD_DEV_PORT (default 8593); --keep-client leaves it running
//
// Evidence: artifacts/m1/<step>.json (gitignored) and stdout ("ok"/"FAIL" per check). Exit code 1 when a check fails.
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
process.env.ARCHITECT_GAME_DIR = GAME; // the DevBridge token is found there
const PORT = Number(process.env.STEWARD_DEV_PORT ?? 8593);
const ART = path.join(ROOT, 'artifacts', 'm1');
fs.mkdirSync(ART, { recursive: true });

const argv = process.argv.slice(2);
const step = argv[0];
const flag = (f) => argv.includes(f);
const opt = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
const CATALOG = path.resolve(ROOT, opt('--catalog', 'fixtures/modules/catalog'));
const sha256 = (s) => crypto.createHash('sha256').update(s, 'utf8').digest('hex');
const log = (m) => console.log(m);
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
/** What a run checked, to tie its evidence to these exact files: the program's and module.json's sha256 (raw bytes). */
const stamp = (e) => ({ programSha: fileSha(path.join(e.dir, 'program.mjs')), moduleJsonSha: fileSha(path.join(e.dir, 'module.json')) });
function entries() {
  const idx = JSON.parse(fs.readFileSync(path.join(CATALOG, 'catalog.json'), 'utf8'));
  return idx.modules.map(({ id, version }) => {
    const dir = path.join(CATALOG, id, String(version));
    const read = (f) => fs.readFileSync(path.join(dir, f), 'utf8');
    return { id, version, dir, source: read('program.mjs'), module: JSON.parse(read('module.json')), measured: fs.existsSync(path.join(dir, 'measured.json')) ? JSON.parse(read('measured.json')) : null };
  });
}
/** Every branch: the cartesian product of the envelope's branches, in declaration order. */
function combinations(branches) {
  let out = [{}];
  for (const [k, vs] of Object.entries(branches)) out = out.flatMap((m) => vs.map((v) => ({ ...m, [k]: v })));
  return out;
}
/** Params outside the envelope: the first int param's first value inside its schema's range that no branch lists (fixture_plot: side 6). */
function outsideParams(m) {
  for (const [k, vs] of Object.entries(m.envelope.branches)) {
    const p = m.params[k];
    if (p?.type !== 'int') continue;
    for (let v = p.min ?? 0; v <= (p.max ?? 64); v++) if (!vs.includes(v)) return { [k]: v };
  }
  return { [Object.keys(m.envelope.branches)[0]]: '__outside__' };
}
const label = (c) => Object.entries(c).map(([k, v]) => `${k}=${v}`).join(',');

// ------------------------------------------------------------------ offline: the pinned kit

async function offline() {
  const kitRoot = path.join(ART, `kit-${ARCH_VERSION}`);
  if (!fs.existsSync(path.join(kitRoot, 'kit', 'build.mjs'))) {
    fs.mkdirSync(kitRoot, { recursive: true });
    const tar = execFileSync('git', ['-C', ARCH, 'archive', `v${ARCH_VERSION}`, 'kit'], { maxBuffer: 1 << 30 });
    execFileSync('tar', ['-x', '-C', kitRoot], { input: tar });
  }
  const kit = path.join(kitRoot, 'kit');
  const { buildDesign } = await import(pathToFileURL(path.join(kit, 'build.mjs')).href);
  const { PALETTES } = await import(pathToFileURL(path.join(kit, 'lib', 'kit.mjs')).href);
  const { parseReserved, frameToTemplate } = await import(pathToFileURL(path.join(kit, 'lib', 'reserved.mjs')).href);
  const kitVersion = (/EVAL_KIT_VERSION\s*=\s*'([^']+)'/.exec(fs.readFileSync(path.join(kit, 'lib', 'realise.mjs'), 'utf8')) ?? [])[1];
  // the checker is the kit's own lib/check.mjs: its version is the kit's (no separate version exists), read from the archived kit
  const out = { architect: ARCH_VERSION, kit: kitVersion ?? null, checker: kitVersion ?? null, entries: {} };
  for (const e of entries()) {
    const key = `${e.id}@${e.version}`;
    const r = (out.entries[key] = { ...stamp(e), branches: [], probes: {} });
    check(e.module.programSha === r.programSha, `${key}: program.mjs is the program module.json pins (${r.programSha.slice(0, 12)})`);
    // the program as a design in a temp dir, its kit imports pointed at the pinned kit (as kit/test/gp.test.mjs does)
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'steward-m1-'));
    const lib = pathToFileURL(path.join(kit, 'lib')).href;
    fs.writeFileSync(path.join(dir, `${e.id}.mjs`), e.source.replaceAll("'../lib/", `'${lib}/`));
    try {
      const mod = await import(pathToFileURL(path.join(dir, `${e.id}.mjs`)).href);
      const strip = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, Object.fromEntries(Object.entries(v).filter(([f]) => f !== 'label'))]));
      check(JSON.stringify(strip(mod.params)) === JSON.stringify(strip(e.module.params)), `${key}: module.json's params schema is the program's own`, { program: mod.params, module: e.module.params });
      const build = async (values, palette, reserved) => {
        const o = fs.mkdtempSync(path.join(os.tmpdir(), 'steward-m1-out-'));
        try {
          const { bp, result } = await buildDesign(e.id, { dir, out: o, values, palette, ...(reserved ? { reserved } : {}) });
          return { ok: result.ok && result.errors.length === 0, errors: result.errors, warnings: result.warnings, bp };
        } catch (err) {
          return { ok: false, threw: String(err.message).split('\n')[0] };
        } finally {
          fs.rmSync(o, { recursive: true, force: true });
        }
      };
      for (const c of combinations(e.module.envelope.branches)) {
        for (const bible of e.module.envelope.bibles) {
          const b = await build(c, { bible });
          r.branches.push({ branch: c, bible, ok: b.ok, errors: b.errors ?? [b.threw], warnings: b.warnings?.length ?? 0, blocks: b.bp?.cells?.size ?? null });
          check(b.ok, `${key} ${label(c)} as ${bible}: the kit's checker is clean${b.ok ? ` (${b.bp.cells.size} blocks, ${b.warnings.length} warnings)` : `: ${(b.errors ?? [b.threw]).join('; ')}`}`);
        }
      }
      const pal = e.module.palette ?? {};
      const base = e.module.envelope.bibles[0];
      const roles = { ...PALETTES[base].roles };
      const first = combinations(e.module.envelope.branches)[0];
      if (pal.lamp) {
        const dim = await build(first, { bible: { id: 'm1_dim', version: 1, roles: { ...roles, [pal.lamp.role]: 'minecraft:soul_lantern' } } });
        const lamps = dim.bp ? [...dim.bp.cells.values()].map((c) => c.state?.name ?? '').filter((n2) => /lantern/.test(n2)) : [];
        r.probes.dimLamp = { ok: dim.ok, lamps: [...new Set(lamps)], said: /plain lanterns/.test(dim.bp?.description ?? '') };
        check(dim.ok && lamps.length > 0 && lamps.every((n) => n === pal.lamp.fallback) && r.probes.dimLamp.said,
          `${key}: a too-dim lamp (soul lantern, light 10) is replaced by the fallback ${pal.lamp.fallback}, and said`, r.probes.dimLamp);
      }
      for (const role of pal.noGravity ?? []) {
        const g = await build(first, { bible: { id: 'm1_gravity', version: 1, roles: { ...roles, [role]: 'minecraft:gravel' } } });
        r.probes[`gravity_${role}`] = { ok: g.ok, threw: g.threw };
        check(!g.ok && /palette: .*gravity/.test(g.threw ?? ''), `${key}: gravel as ${role} (a gravity block on a core role) is refused: ${g.threw ?? 'not refused'}`);
      }
      // the lot's reserved cells: one field cell (the farmland and the cell above), in the lot's frame, left open
      // (W = field + 2, D = field + 3: a square `side`, or crop_farm's `width` x `depth`)
      const [W, , D] = [(first.width ?? first.side) + 2, 3, (first.depth ?? first.side) + 3];
      if (Number.isInteger(W)) {
        const res = parseReserved({ front: 'south', size: [W, D], cells: [[2, -1, 2], [2, 0, 2]] });
        const rv = await build(first, { bible: base }, res);
        const t = { size: [W, 3, D], groundY: 1, front: 'south' };
        const open = rv.bp ? res.cells.every(([u, y, v]) => !rv.bp.cells.has(frameToTemplate(u, y, v, t, res).join(','))) : false;
        r.probes.reserved = { ok: rv.ok, open };
        check(rv.ok && open, `${key}: the lot's reserved cells are left open and the checker passes`, r.probes.reserved);
      }
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }
  return out;
}

// ------------------------------------------------------------------ in game: Steward's own code against Architect's helper

let dev = null, child = null;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** Whether something already listens on a local port. */
const listening = (port) => new Promise((resolve) => {
  const s = net.connect({ host: '127.0.0.1', port });
  s.once('connect', () => { s.destroy(); resolve(true); });
  s.once('error', () => resolve(false));
});
let launched = false, exited = false, claimed = null;
/** An exclusive claim on mod/run for this run (O_EXCL; a claim whose pid is gone is taken over). Released at exit. */
function claim() {
  const f = path.join(GAME, 'steward-module-catalog.lock');
  fs.mkdirSync(GAME, { recursive: true });
  for (let i = 0; i < 2; i++) {
    try {
      fs.writeFileSync(f, String(process.pid), { flag: 'wx' });
      claimed = f;
      process.on('exit', () => { try { if (fs.readFileSync(f, 'utf8') === String(process.pid)) fs.rmSync(f); } catch { /* gone */ } });
      return;
    } catch (err) {
      if (err.code !== 'EEXIST') throw err;
      const pid = Number(fs.readFileSync(f, 'utf8'));
      let alive = false;
      try { process.kill(pid, 0); alive = true; } catch { /* gone */ }
      if (alive) throw new Error(`another module-catalog run (pid ${pid}) holds ${f}: refusing to launch`);
      fs.rmSync(f, { force: true });
    }
  }
  throw new Error(`could not claim ${f}`);
}
async function startClient() {
  const { DevClient } = await import(pathToFileURL(path.join(ARCH, 'tools', 'lib', 'devclient.mjs')).href);
  if (flag('--attach')) { dev = await DevClient.connect({ port: PORT, timeoutMs: 30_000 }); await dev.waitInWorld({ timeoutMs: 60_000 }); return; }
  // never drive (or quit) a client this run did not start: an exclusive claim on this game dir (two runs of this tool can't both launch),
  // then both ports must be free before the launch
  claim();
  for (const p of [PORT - 1, PORT]) if (await listening(p)) throw new Error(`port ${p} is in use (another client?): refusing to launch; use --attach to drive it`);
  if (fs.existsSync(path.join(GAME, 'architect', 'devbridge.token'))) fs.rmSync(path.join(GAME, 'architect', 'devbridge.token'));
  if (fs.existsSync(path.join(GAME, 'architect', 'sidecar-data', 'secrets.json'))) throw new Error('the claude-login opt-in exists: refusing');
  const world = `Steward M1 Catalog ${new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')}`;
  const out = fs.openSync(path.join(ART, 'client.log'), 'a');
  child = spawn(path.join(ROOT, 'tools', 'run-e2e-client.sh'), [], {
    stdio: ['ignore', out, out],
    env: { ...process.env, STEWARD_PORT: String(PORT - 1), STEWARD_DEV_PORT: String(PORT), ARCHITECT_AUTOWORLD_NAME: world, E2E_STUB: '0', E2E_NODE_DIR: path.dirname(process.execPath), GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? path.join(MAIN_CHECKOUT, '.gradle-home') },
  });
  launched = true;
  child.once('exit', () => { exited = true; });
  log(`client launched (launcher pid ${child.pid}), world "${world}", ports ${PORT - 1}/${PORT}`);
  // the token is the one this launch writes (removed above), so a client that is not ours can't be reached by it
  const died = new Promise((_, reject) => child.once('exit', (code) => reject(new Error(`the launched client exited (code ${code}) before its bridge answered: see artifacts/m1/client.log`))));
  died.catch(() => {});
  dev = await Promise.race([DevClient.connect({ port: PORT, timeoutMs: 600_000 }), died]);
  if (exited) throw new Error('the launched client exited before its bridge answered');
  await dev.waitInWorld({ timeoutMs: 600_000 });
}
async function stopClient() {
  if (!dev || !launched || exited || flag('--attach') || flag('--keep-client')) return;
  const r = await dev.request('dev.quit');
  if (r.ok) await dev.waitClosed(120_000);
}
let n = 0;
/** One `/steward dev module` request; its answer from <gameDir>/steward-modules/<label>.json. */
async function mod(req, timeoutMs = 600_000) {
  const lab = `m1_${process.pid}_${++n}`;
  const f = path.join(GAME, 'steward-modules', `${lab}.json`);
  const r = await dev.request('dev.command', { cmd: `/steward dev module ${lab} ${JSON.stringify(req)}` }, { timeoutMs: 120_000 });
  if (!r.ok) throw new Error(`module ${req.op}: ${r.error}`);
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (fs.existsSync(f)) {
      try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { /* still being written */ }
    }
    await sleep(250);
  }
  throw new Error(`module ${req.op}: no answer in ${timeoutMs / 1000} s (${(r.messages ?? []).join(' ')})`);
}
const entryFile = (id) => {
  const f = path.join(GAME, 'architect', 'library', id, `${id}.blueprint.json`);
  return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null;
};

async function ingame() {
  const out = { entries: {} };
  await startClient();
  try {
    const env = await mod({ op: 'env' });
    out.env = env;
    check(env.minecraft === MC_VERSION && env.architect === ARCH_VERSION && env.givenPrograms === true,
      `the game: Minecraft ${env.minecraft}, Architect ${env.architect}, givenPrograms ${env.givenPrograms} (pinned ${MC_VERSION} / ${ARCH_VERSION})`);
    const run = Date.now().toString(36);
    for (const e of entries()) {
      const key = `${e.id}@${e.version}`;
      const r = (out.entries[key] = { ...stamp(e), variants: [] });
      const base = { dir: CATALOG, id: e.id, version: e.version, measuring: true };
      // the gate as a player's game sees it (no measuring): offered exactly when measured.json certifies these files
      const plain = await mod({ op: 'gate', dir: CATALOG, id: e.id, version: e.version });
      r.plainGate = plain;
      // not certified: nothing offered; certified for other versions (a pin bump, before re-certifying): refused for the version; else offered
      if (plain.uncertified?.length) check(!plain.ok && plain.why === 'NOT_MEASURED', `${key}: not certified yet, so the plain gate offers nothing (${plain.uncertified.join('; ')})`);
      else if (e.measured?.minecraft !== env.minecraft || !(e.measured?.architect ?? []).includes(env.architect)) {
        check(!plain.ok && ['MINECRAFT', 'ARCHITECT'].includes(plain.why), `${key}: certified for other versions, so the plain gate offers nothing here (${plain.message})`);
      } else check(plain.ok, `${key}: certified, so the plain gate offers it (${plain.message})`);
      const gate = await mod({ op: 'gate', ...base });
      r.gate = gate;
      check(gate.ok && gate.problems.length === 0, `${key}: a measurement run may install it (${gate.message})`, gate.problems);
      const t0 = Date.now();
      const a = await mod({ op: 'install', ...base });
      r.install = { ...a, ms: Date.now() - t0 };
      check(a.ok && a.entryId, `${key}: installed at $0 by its key ${a.key} as ${a.entryId} (${Date.now() - t0} ms)${a.ok ? '' : `: ${a.detail} ${a.message}`}`);
      if (!a.ok) continue;
      const lib = entryFile(a.entryId) ?? {};
      check(lib.givenProgram?.sha === e.module.programSha && lib.givenProgram?.opKey === a.key,
        `${key}: the entry records the catalog's program sha and the key (givenProgram ${String(lib.givenProgram?.sha).slice(0, 12)}, ${lib.givenProgram?.opKey})`);
      const again = await mod({ op: 'install', ...base });
      check(again.ok && again.entryId === a.entryId, `${key}: the key replays: the same entry (${again.entryId})`);
      let firstKey = null, firstId = null;
      for (const c of combinations(e.module.envelope.branches)) {
        for (const bible of e.module.envelope.bibles) {
          const vkey = `steward.m1gate${run}.module.${e.id}_${Object.values(c).join('_')}_${bible}`;
          const t1 = Date.now();
          const v = await mod({ op: 'variant', ...base, params: c, bible: { id: bible, version: 0 }, key: vkey });
          const vl = v.ok ? entryFile(v.entryId) ?? {} : {};
          const shaOk = vl.givenProgram?.sha === e.module.programSha;
          const pinned = v.bible?.id === bible && v.bible?.version >= 1 && vl.bible?.id === bible && vl.bible?.version === v.bible.version;
          r.variants.push({ branch: c, bible, ok: v.ok, entryId: v.entryId, base: v.baseEntryId, detail: v.detail, ms: Date.now() - t1, sha: shaOk, sent: v.bible ?? null, bibleRecorded: vl.bible ?? null, findings: v.findings });
          check(v.ok && shaOk && pinned && v.baseEntryId === a.entryId,
            `${key} ${label(c)} as ${bible} v${v.bible?.version}: variant ${v.entryId ?? '-'} of ${v.baseEntryId} installed, checker clean (${Date.now() - t1} ms)${v.ok ? '' : `: ${v.detail} ${v.message} ${v.findings.join('; ')}`}`);
          if (v.ok && !firstKey) { firstKey = vkey; firstId = v.entryId; }
        }
      }
      const off = await mod({ op: 'variant', ...base, params: outsideParams(e.module), bible: { id: e.module.envelope.bibles[0], version: 0 }, key: `steward.m1gate${run}.module.off` });
      r.outside = off;
      check(!off.ok && off.detail === 'params', `${key}: params outside the envelope are refused before Architect is asked (${off.detail}: ${off.message})`);
      if (firstKey) {
        const k = await mod({ op: 'byKey', key: firstKey });
        const none = await mod({ op: 'byKey', key: `steward.m1gate${run}.module.never` });
        r.byKey = { k, none };
        check(k.found && k.entryId === firstId && none.found === false, `${key}: variantByKey finds ${firstKey} -> ${k.entryId}; an unknown key finds none`);
      }
    }
  } finally {
    await stopClient().catch((err) => log(`stop: ${err}`));
  }
  return out;
}

// ------------------------------------------------------------------ record

/**
 * Writes the evidence into measured.json, only when both steps passed on these exact files (program and module.json bytes), on the pinned
 * Architect and Minecraft versions; it then certifies them: `result: "pass"` and the `moduleSha` the game computed for this module.json.
 */
function record() {
  const off = JSON.parse(fs.readFileSync(path.join(ART, 'offline.json'), 'utf8'));
  const inn = JSON.parse(fs.readFileSync(path.join(ART, 'ingame.json'), 'utf8'));
  let refused = 0;
  for (const e of entries()) {
    const key = `${e.id}@${e.version}`;
    const o = off.data?.entries?.[key], g = inn.data?.entries?.[key], now = stamp(e);
    const why = [];
    if (!off.ok) why.push('the offline step did not pass');
    if (!inn.ok) why.push('the ingame step did not pass');
    if (!o || !g) why.push('no evidence for it');
    for (const [n, r] of [['offline', o], ['ingame', g]]) {
      if (r && (r.programSha !== now.programSha || r.moduleJsonSha !== now.moduleJsonSha)) why.push(`the ${n} run checked other files`);
    }
    if (off.data?.architect !== ARCH_VERSION) why.push(`offline ran on Architect ${off.data?.architect}, pinned ${ARCH_VERSION}`);
    if (inn.data?.env?.architect !== ARCH_VERSION || inn.data?.env?.minecraft !== MC_VERSION) why.push('ingame ran on other versions');
    if (off.data?.kit !== e.module.pins?.kit) why.push(`offline ran kit ${off.data?.kit}, pinned ${e.module.pins?.kit}`);
    if (off.data?.checker !== e.module.pins?.checker) why.push(`offline ran checker ${off.data?.checker}, pinned ${e.module.pins?.checker}`);
    if (!g?.gate?.moduleSha) why.push('no moduleSha from the game');
    // a real module (not a fixture) certifies only with its measurement (docs/MEASURE-M2.md for crop_farm@1): every step passed, on these
    // exact files and the pinned versions, field steps with 5 runs or more
    const measure = e.module.fixture ? null : measurement(e, now, why);
    if (why.length) { refused++; log(`REFUSED ${key}: ${why.join('; ')}`); continue; }
    // a re-certification on a new Architect pin (same program and module.json, same Minecraft) keeps the versions the old certificate covers
    const prev = e.measured;
    const same = prev?.result === 'pass' && prev.programSha === now.programSha && prev.moduleSha === g.gate.moduleSha && prev.minecraft === MC_VERSION;
    const architect = [...new Set([...(same ? prev.architect ?? [] : []), ARCH_VERSION])];
    const m = { ...(prev ?? {}), id: e.id, version: e.version, fixture: !!e.module.fixture, programSha: now.programSha, moduleSha: g.gate.moduleSha,
      minecraft: MC_VERSION, architect, kit: off.data.kit, checker: off.data.checker, result: 'pass' };
    m.checks = {
      offline: { at: off.at, head: off.head, architect: off.data.architect, kit: off.data.kit, branches: o.branches.map(({ branch, bible, ok, blocks, warnings }) => ({ branch, bible, ok, blocks, warnings })), probes: o.probes },
      ingame: { at: inn.at, head: inn.head, env: inn.data.env, install: { key: g.install.key, ok: g.install.ok }, variants: g.variants.map(({ branch, bible, ok, sha, sent }) => ({ branch, bible, version: sent?.version ?? null, ok, sha })), outside: { refused: !g.outside?.ok, detail: g.outside?.detail }, byKey: g.byKey ? { found: g.byKey.k.found, unknownFound: g.byKey.none.found } : null },
    };
    if (measure) { m.checks.measure = measure; m.what = MEASURES[e.id]?.what; m.bars = MEASURES[e.id]?.bars; m.runs = 5; }
    fs.writeFileSync(path.join(e.dir, 'measured.json'), `${JSON.stringify(m, null, 2)}\n`);
    log(`recorded ${key}: result pass, moduleSha ${m.moduleSha.slice(0, 12)}`);
  }
  if (refused) process.exit(1);
}

/** Each real module's measurement: its evidence steps (artifacts/m2/<step>.json) and what measured.json says it covers. */
const MEASURES = {
  crop_farm: {
    steps: ['field-wheat', 'field-carrots', 'field-potatoes', 'field-beetroots', 'skin-rustic', 'skin-fortress', 'skin-desert', 'survival'],
    what: 'docs/MEASURE-M2.md: MODULES.md 3.1 items 1-7 as amended by section 9, measured in a dev client over the envelope (36 branches x 5 runs for the output rate; all four facings; chunk borders; rustic in full, fortress and desert for light, hydration and exclusion; survival bill and refunds) by tools/module-measure.mjs.',
    bars: 'docs/MEASURE-M2.md "The bars" (committed before the first measurement)',
  },
};
/** The measurement's evidence for a real module, summarised; reasons it can't certify go into `why`. */
function measurement(e, now, why) {
  const M = MEASURES[e.id];
  if (!M) { why.push(`no measurement is defined for ${e.id}`); return null; }
  const out = {};
  for (const s of M.steps) {
    const f = path.join(ROOT, 'artifacts', 'm2', `${s}.json`);
    if (!fs.existsSync(f)) { why.push(`no measurement evidence ${s}`); continue; }
    const r = JSON.parse(fs.readFileSync(f, 'utf8'));
    if (!r.ok) why.push(`measurement ${s} did not pass`);
    if (r.programSha !== now.programSha || r.moduleJsonSha !== now.moduleJsonSha) why.push(`measurement ${s} measured other files`);
    if (r.architect !== ARCH_VERSION || r.minecraft !== MC_VERSION) why.push(`measurement ${s} ran on other versions`);
    if (s.startsWith('field-') && !(r.runs >= 5)) why.push(`measurement ${s} has ${r.runs} runs (5 needed)`);
    out[s] = { at: r.at, head: r.head, world: r.world, seconds: r.seconds, runs: r.runs ?? null, passed: r.checks.filter((c) => c.ok).length, of: r.checks.length,
      results: r.checks.map((c) => c.what) };
  }
  return out;
}

const steps = { offline, ingame };
if (step === 'record') record();
else if (steps[step]) {
  const t0 = Date.now();
  let data, error;
  try { data = await steps[step](); } catch (e) { error = String(e?.stack ?? e); check(false, `${step}: ${String(e?.message ?? e).slice(0, 300)}`); }
  const ok = checks.every((c) => c.ok);
  const head = execFileSync('git', ['-C', ROOT, 'rev-parse', 'HEAD']).toString().trim();
  fs.writeFileSync(path.join(ART, `${step}.json`), `${JSON.stringify({ step, ok, at: new Date().toISOString(), head, seconds: (Date.now() - t0) / 1000, spendUsd: 0, error, checks, data }, null, 2)}\n`);
  log(`${step}: ${ok ? 'PASS' : 'FAIL'}, ${checks.filter((c) => c.ok).length}/${checks.length} checks, ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  process.exit(ok ? 0 : 1);
} else {
  log('usage: node tools/module-catalog.mjs offline|ingame|record [--catalog <dir>] [--attach] [--keep-client]');
  process.exit(2);
}
