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
//   node tools/module-catalog.mjs record --smoke
//                                           (speed rule 4, Noah 2026-11-13) carries a real module's full measurement to a new Architect pin
//                                           on a smoke check: `basis: "smoke"`, `from: <the fully measured Architect version>`, the full
//                                           certificate kept in `carried` (its bounds and rates stay top level, bound by their canonical shas).
//                                           Refused unless ALL hold: the program is the one fully measured (programSha); module.json differs
//                                           from the fully measured one only in its pins (its moduleSha with the old pins put back is the
//                                           carried moduleSha); every branch x bible builds the same block count offline as when measured; a
//                                           fresh offline and ingame pass on these files and the pinned versions; one field round
//                                           (module-measure.mjs field --crop <c>, 5 runs) and the billing check (module-measure.mjs survival
//                                           and bounds: the 108 quotes re-taken must equal the carried bounds item for item) pass on the
//                                           pinned Architect, every check of each. A smoke certificate is never a measurement: a FULL re-measure (plain
//                                           `record` after every step) is still needed when the module's own code changes, or when an
//                                           Architect build, billing or realise path it uses changes (docs/MEASURE-M2.md "Re-certification").
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
import { styleBounds } from './lib/style-bounds.mjs';
import { moduleShaOf, smokeCarry } from './lib/smoke-carry.mjs';

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
/**
 * (M4b) Every branch with its domain params filled: a module whose envelope has `domains` (storage_hall@2's item1..item16, kind `filter`)
 * gets, per sorting branch, its own item set from the enum's options (all but `none`), rotating through the domain so the run covers every
 * domain item as a filter (MEASURE-M4 12.1); `slots` names how many. Without domains, the branches as they are.
 */
export function branchSets(m) {
  const combos = combinations(m.envelope.branches);
  const doms = Object.keys(m.envelope.domains ?? {});
  if (!doms.length) return combos;
  const domain = (m.params[doms[0]].options ?? []).filter((o) => o !== 'none');
  let at = 0;
  return combos.map((c) => {
    const n = Number(c.slots ?? 0);
    const out = { ...c };
    for (let i = 0; i < n; i++) { out[doms[i]] = domain[(at + i) % domain.length]; }
    at = (at + n) % domain.length;
    return out;
  });
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
      for (const c of branchSets(e.module)) {
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
      for (const role of pal.fullBlock ?? []) {
        const g = await build(first, { bible: { id: 'm1_full', version: 1, roles: { ...roles, [role]: 'minecraft:oak_slab' } } });
        r.probes[`full_${role}`] = { ok: g.ok, threw: g.threw };
        check(!g.ok && /palette: .*not a full block/.test(g.threw ?? ''), `${key}: a slab as ${role} (a role that must be a full block) is refused: ${g.threw ?? 'not refused'}`);
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
      for (const c of branchSets(e.module)) {
        for (const bible of e.module.envelope.bibles) {
          // (the branch's own values: its domain items left out of the key, which stays short)
          const vkey = `steward.m1gate${run}.module.${e.id}_v${e.version}_${Object.entries(c).filter(([k]) => !(k in (e.module.envelope.domains ?? {}))).map(([, v]) => v).join('_')}_${bible}`;
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
async function record() {
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
    const SMOKE = flag('--smoke');
    const measure = e.module.fixture || SMOKE ? null : measurement(e, now, why);
    if (why.length) { refused++; log(`REFUSED ${key}: ${why.join('; ')}`); continue; }
    const freshChecks = () => ({
      offline: { at: off.at, head: off.head, architect: off.data.architect, kit: off.data.kit, branches: o.branches.map(({ branch, bible, ok, blocks, warnings }) => ({ branch, bible, ok, blocks, warnings })), probes: o.probes },
      ingame: { at: inn.at, head: inn.head, env: inn.data.env, install: { key: g.install.key, ok: g.install.ok }, variants: g.variants.map(({ branch, bible, ok, sha, sent }) => ({ branch, bible, version: sent?.version ?? null, ok, sha })), outside: { refused: !g.outside?.ok, detail: g.outside?.detail }, byKey: g.byKey ? { found: g.byKey.k.found, unknownFound: g.byKey.none.found } : null },
    });
    if (SMOKE) {
      // speed rule 4: carry the full measurement on a smoke check (tools/lib/smoke-carry.mjs), or refuse
      // the module's evidence directory (artifacts/m2: crop_farm, artifacts/m4: storage_hall) and its round: one field round (crop_farm), or
      // one hall round (storage_hall: module-measure-hall.mjs hall, deterministic)
      const M = measureOf(e) ?? {};
      const m2 = path.join(ROOT, 'artifacts', M.art ?? 'm2');
      const read = (f) => (fs.existsSync(path.join(m2, f)) ? JSON.parse(fs.readFileSync(path.join(m2, f), 'utf8')) : null);
      const fields = !fs.existsSync(m2) ? [] : M.round === 'hall' ? [read('hall.json')].filter(Boolean)
        : fs.readdirSync(m2).filter((f) => /^field-[a-z]+\.json$/.test(f)).map(read);
      // the bill: the 108 quotes re-taken on this pin, as per-Style bounds the way the full record derives them, to compare with the carried ones
      const rq = read('bounds.json');
      const requote = { evidence: rq, bounds: null, problems: [] };
      if (rq?.data?.quotes) {
        const sb = await styleBounds({ quotes: rq.data.quotes, kit: path.join(ART, `kit-${ARCH_VERSION}`, 'kit'), source: e.source, id: e.id });
        requote.bounds = sb.bounds; requote.problems = sb.problems;
      }
      const c = smokeCarry({ prev: e.measured, mod: e.module, now, gameModuleSha: g.gate.moduleSha, versions: { architect: ARCH_VERSION, minecraft: MC_VERSION },
        offline: off, offEntry: o, ingame: inn, inEntry: g, checks: freshChecks(), survival: read(`${M.billing ?? 'survival'}.json`), billingStep: M.billing ?? 'survival', fields, requote });
      if (c.why.length) { refused++; log(`REFUSED ${key} (smoke): ${c.why.join('; ')}`); continue; }
      fs.writeFileSync(path.join(e.dir, 'measured.json'), `${JSON.stringify(c.m, null, 2)}\n`);
      log(`recorded ${key}: result pass, basis smoke from ${c.m.from}, moduleSha ${c.m.moduleSha.slice(0, 12)}`);
      for (const f of c.m.checks.smoke.fields) for (const r of f.rates) log(`  ${r.params.crop} ${r.params.width}x${r.params.depth}: ${r.perDay} a day (carried ${r.carriedPerDay}, ${r.changePct}% per crop-day)`);
      continue;
    }
    // a re-certification on a new Architect pin (same program and module.json, same Minecraft) keeps the versions the old certificate covers
    const prev = e.measured;
    const same = prev?.result === 'pass' && prev.basis !== 'smoke' && prev.programSha === now.programSha && prev.moduleSha === g.gate.moduleSha && prev.minecraft === MC_VERSION;
    const architect = [...new Set([...(same ? prev.architect ?? [] : []), ARCH_VERSION])];
    // a full measurement replaces any smoke certificate: its carry fields go
    const { basis: _basis, from: _from, carried: _carried, ...kept } = prev ?? {};
    const m = { ...kept, basis: 'full', id: e.id, version: e.version, fixture: !!e.module.fixture, programSha: now.programSha, moduleSha: g.gate.moduleSha,
      minecraft: MC_VERSION, architect, kit: off.data.kit, checker: off.data.checker, result: 'pass' };
    m.checks = freshChecks();
    if (measure) {
      const M = measureOf(e);
      m.checks.measure = measure; m.what = M.what; m.bars = M.bars; m.runs = M.runs;
      // M3 reads these: the whole growth's bill per params (the survival bound) and the quoted net rate (the fixed-cycle protocol rate)
      const art = (s) => JSON.parse(fs.readFileSync(path.join(ROOT, 'artifacts', M.art, `${s}.json`), 'utf8')).data;
      // per measured Style, with each palette role's share of the lot (tools/lib/style-bounds.mjs): Steward re-expresses it in the town's blocks
      const sb = await styleBounds({ quotes: art('bounds').quotes, kit: path.join(ART, `kit-${ARCH_VERSION}`, 'kit'), source: e.source, id: e.id });
      const styles = new Set(e.module.envelope.bibles);
      for (const b of sb.bounds) styles.has(b.style) || sb.problems.push(`a bound for ${b.style}, not one of the envelope's Styles`);
      const per = new Map();
      for (const b of sb.bounds) per.set(JSON.stringify(b.params), [...(per.get(JSON.stringify(b.params)) ?? []), b.style]);
      if (per.size !== combinations(e.module.envelope.branches).length || [...per.values()].some((v) => v.length !== styles.size || new Set(v).size !== styles.size)) sb.problems.push(`bounds: ${per.size} params, not every one with each of the ${styles.size} Styles once`);
      if (sb.problems.length) { refused++; log(`REFUSED ${key}: the per-Style bounds: ${sb.problems.slice(0, 5).join('; ')}`); continue; }
      m.bounds = sb.bounds;
      m.rates = [];
      // (M4b) storage_hall@2: its bounds are the structure alone; the fill (41 of each item) is added per order (ModuleBound's `fill`); its rates
      // are items a second per slot count, from the sort step
      if (M.fill) m.fill = M.fill;
      if (M.hallRates) {
        const S = art('sort');
        const per = {};
        for (const [id, h] of Object.entries(S.halls ?? {})) if (h.itemsPerSecond && h.bible === 'rustic') (per[h.slots] ??= []).push(h.itemsPerSecond);
        for (const [slots, xs] of Object.entries(per)) m.rates.push({ params: { slots: Number(slots) }, perSecond: Math.round((xs.reduce((a, b) => a + b, 0) / xs.length) * 1000) / 1000, item: 'items' });
      }
      if (M.rates) for (const crop of ['wheat', 'carrots', 'potatoes', 'beetroots']) {
        const F = art(`field-${crop}`);
        for (const f of F.farms) {
          const J = F.judged[f.id];
          const [width, depth] = f.size.split('x').map(Number);
          m.rates.push({ params: { crop, depth, width }, perDay: Math.floor(J.mean * J.n), item: ITEM_OF[crop], perCropDay: J.mean, crops: J.n, ripenPerCropDay: J.ripenPerCropDay });
        }
      }
      m.rates.sort((a, b) => JSON.stringify(a.params).localeCompare(JSON.stringify(b.params)));
    }
    fs.writeFileSync(path.join(e.dir, 'measured.json'), `${JSON.stringify(m, null, 2)}\n`);
    log(`recorded ${key}: result pass, moduleSha ${m.moduleSha.slice(0, 12)}`);
  }
  if (refused) process.exit(1);
}

const ITEM_OF = { wheat: 'minecraft:wheat', carrots: 'minecraft:carrot', potatoes: 'minecraft:potato', beetroots: 'minecraft:beetroot' };
/** Each real module's measurement: its evidence steps (artifacts/m2/<step>.json) and what measured.json says it covers. */
const MEASURES = {
  crop_farm: {
    steps: ['field-wheat', 'field-carrots', 'field-potatoes', 'field-beetroots', 'skin-rustic', 'skin-fortress', 'skin-desert', 'survival', 'bounds', 'border', 'cover'],
    what: 'docs/MEASURE-M2.md: MODULES.md 3.1 items 1-7 as amended by section 9, measured in a dev client over the envelope (36 branches x 5 runs for the output rate; all four facings; chunk borders; rustic in full, fortress and desert for light, hydration and exclusion; survival bill and refunds) by tools/module-measure.mjs.',
    bars: 'docs/MEASURE-M2.md "The bars" (committed before the first measurement)',
    art: 'm2', runs: 5, rates: true, fieldRuns: 5,
  },
  storage_hall: {
    steps: ['hall', 'survival', 'bounds'],
    what: 'docs/MEASURE-M4.md 4.1: the plain hall (slots 0), every bulk branch, measured in a dev client by tools/module-measure-hall.mjs: shape, storage, restart and unload, light, undo with cargo and emptied (all four facings, chunk borders, rustic in full, fortress and desert for shape and light), the survival bill, a cancel mid-build and the refunds, and the bound per branch and Style. Deterministic: one run per step.',
    bars: 'docs/MEASURE-M4.md "4. The bars" (committed before the first measurement)',
    art: 'm4', runs: 1, rates: false, round: 'hall',
  },
  'storage_hall@2': {
    steps: ['hall', 'bounds', 'sort', 'sort-survival', 'sort-cargo', 'sort-crash', 'sort-plain'],
    // (M4b) the sort step's one coverage check, completed by sort-plain: accepted only when it is sort's only failure and sort-plain carried
    // every item it names as a non-filter kind through 100% runs
    completes: { sort: { by: 'sort-plain', check: /^12\.1 coverage: every domain item a non-filter kind/, missing: (r) => r.data?.coverage?.plainMissing ?? [], covered: (r) => r.data?.covered ?? [] } },
    what: 'docs/MEASURE-M4.md 12.5 and 12.7: the sorting hall (slots 4, 8, 12 and 16, each with its own items through the 48-item domain) and its plain branch, measured in a dev client by tools/module-measure-hall.mjs --version 2: shape, light, every chest opened and emptied by a player through its menu, sorting 20 runs a rustic branch over its four facings in acknowledged batches (10,000 items a hall; every domain item a filter and a non-filter kind), throughput, saturation and a full overflow (each drain counted), the filters holding, restart and an asserted full unload and a partial unload mid-sort (the whole mix fed across them), ingress before the lock (a mid-build insert down the line is the known gap S-M5-17: fill_blocked, nothing charged or lost), fill_blocked, the bill (structure + 41 per item) and the refunds after taking, cargo alone in every container class, the fed-slab recovery, the crash points FK1-FK4, FKC1, FKC2 and CS1 (each judged on every item against the saved state, the removals with an unchanged second undo), and a cancel before the fill (FKC0 is deferred to the pin that carries Architect LC3; late cargo is the known gap S-M5-12, named in the offer).',
    bars: 'docs/MEASURE-M4.md 12.5 and 12.7 (committed before the first measurement)',
    art: 'm4b', runs: 1, rates: false, hallRates: true, round: 'hall', billing: 'sort-survival', // (review 11) @2's billing check is sort-survival
    fill: { params: Array.from({ length: 16 }, (_, i) => `item${i + 1}`), count: 41 },
  },
};
/** A catalog entry's measurement: by `id@version` first (storage_hall@2's differs from @1's), else by id. */
function measureOf(e) {
  return MEASURES[`${e.id}@${e.version}`] ?? MEASURES[e.id];
}
/** The measurement's evidence for a real module, summarised; reasons it can't certify go into `why`. */
function measurement(e, now, why) {
  const M = measureOf(e);
  if (!M) { why.push(`no measurement is defined for ${e.id}`); return null; }
  const out = {};
  for (const s of M.steps) {
    const f = path.join(ROOT, 'artifacts', M.art, `${s}.json`);
    if (!fs.existsSync(f)) { why.push(`no measurement evidence ${s}`); continue; }
    const r = JSON.parse(fs.readFileSync(f, 'utf8'));
    const C = M.completes?.[s];
    let completedBy = null;
    if (!r.ok && C) {
      const failed = r.checks.filter((c) => !c.ok);
      const fb = path.join(ROOT, 'artifacts', M.art, `${C.by}.json`);
      const by = fs.existsSync(fb) ? JSON.parse(fs.readFileSync(fb, 'utf8')) : null;
      if (!r.error && failed.length && failed.every((c) => C.check.test(c.what)) && by?.ok && C.missing(r).length && C.missing(r).every((k) => C.covered(by).includes(k))) completedBy = C.by;
    }
    if (!r.ok && !completedBy) why.push(`measurement ${s} did not pass`);
    if (r.programSha !== now.programSha || r.moduleJsonSha !== now.moduleJsonSha) why.push(`measurement ${s} measured other files`);
    if (r.architect !== ARCH_VERSION || r.minecraft !== MC_VERSION) why.push(`measurement ${s} ran on other versions`);
    if (M.fieldRuns && s.startsWith('field-') && !(r.runs >= M.fieldRuns)) why.push(`measurement ${s} has ${r.runs} runs (${M.fieldRuns} needed)`);
    out[s] = { at: r.at, head: r.head, world: r.world, seconds: r.seconds, runs: r.runs ?? null, passed: r.checks.filter((c) => c.ok).length, of: r.checks.length,
      results: r.checks.map((c) => c.what), ...(completedBy ? { completedBy, failed: r.checks.filter((c) => !c.ok).map((c) => c.what) } : {}) };
  }
  return out;
}

const steps = { offline, ingame };
if (step === 'record') await record();
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
  log('usage: node tools/module-catalog.mjs offline|ingame|record [--smoke] [--catalog <dir>] [--attach] [--keep-client]');
  process.exit(2);
}
