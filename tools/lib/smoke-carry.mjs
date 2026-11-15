// Speed rule 4 (Noah, 2026-11-13; AGENTS.md "Module re-certification"): a measured module is fully re-measured at an Architect pin only when
// its own code changes, or when an Architect build, billing or realise path it uses changes. A plain version bump gets a smoke check: one field
// round plus the billing check. This is the carry rule `tools/module-catalog.mjs record --smoke` applies, pure (the tool reads the files).
//
// A smoke certificate (measured.json `basis: "smoke"`) carries the full measurement forward, never replaces it:
//   - `from`: the Architect version the full measurement ran on (a smoke of a smoke keeps the first `from`);
//   - `carried`: the full certificate as recorded, without its `bounds` and `rates`, which stay top level (what ModuleBound and ModuleOffer
//     read) and are bound to it by `carried.boundsSha` / `carried.ratesSha` (Canon.sha, as ModuleCatalog checks them);
//   - `checks.offline` / `checks.ingame`: the fresh runs on the new pin; `checks.smoke`: the billing check and the field round(s).
import crypto from 'node:crypto';

const sha256 = (s) => crypto.createHash('sha256').update(s, 'utf8').digest('hex');
/** Canon.sorted (Java): a deep copy with every object's keys sorted. */
const sortedJson = (v) => (Array.isArray(v) ? v.map(sortedJson) : v && typeof v === 'object'
  ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, sortedJson(v[k])])) : v);
/** Canon.sha (Java): sha256 of the canonical text (sorted keys, no whitespace); undefined hashes as null, as Java's JsonNull. */
export const canonSha = (v) => sha256(JSON.stringify(sortedJson(v ?? null)));
/** ModuleCatalog.moduleSha (Java): the canonical sha of module.json's certified fields (all but its title). */
export const moduleShaOf = (mod) => canonSha(Object.fromEntries(['id', 'version', 'fixture', 'programSha', 'params', 'envelope', 'palette', 'pins']
  .filter((k) => k in mod).map((k) => [k, mod[k]])));

const SMOKE_RULE = 'speed rule 4 (Noah 2026-11-13, AGENTS.md "Module re-certification"): a plain Architect version bump with the program and '
  + 'module.json unchanged but for the pins: fresh offline and ingame, the billing check (survival) and one field round; the full measurement is carried';
const stamped = (r, now, v) => r?.programSha === now.programSha && r?.moduleJsonSha === now.moduleJsonSha && r?.architect === v.architect && r?.minecraft === v.minecraft;
const summary = (r) => ({ step: r.step, at: r.at, head: r.head, world: r.world ?? null, seconds: r.seconds, runs: r.runs ?? null,
  passed: r.checks.filter((c) => c.ok).length, of: r.checks.length });
const pct = (a, b) => (b ? Math.round(((a - b) / b) * 1000) / 10 : null);

/**
 * The carry: `{why, m}`. `why` non-empty: refused (m null). Else m is the new measured.json.
 *
 * @param {object} a
 * @param {object|null} a.prev     the committed measured.json (the certificate being carried)
 * @param {object} a.mod           module.json now
 * @param {{programSha: string, moduleJsonSha: string}} a.now  the files' raw sha256s now
 * @param {string} a.gameModuleSha the moduleSha the game computed for module.json now (the in-game gate's)
 * @param {{architect: string, minecraft: string}} a.versions  the pinned versions
 * @param {object} a.offline       artifacts/m1/offline.json; a.offEntry its entry for this module
 * @param {object} a.ingame        artifacts/m1/ingame.json; a.inEntry its entry for this module
 * @param {object} a.checks        the offline and ingame summaries record writes ({offline, ingame})
 * @param {object|null} a.survival artifacts/m2/survival.json
 * @param {object[]} a.fields      every artifacts/m2/field-<crop>.json present
 */
export function smokeCarry({ prev, mod, now, gameModuleSha, versions, offline, offEntry, ingame, inEntry, checks, survival, fields }) {
  const why = [];
  const no = (m) => { why.push(m); return { why, m: null }; };
  if (!prev) return no('no measured.json to carry');
  if (prev.result !== 'pass') return no(`the certificate to carry has result '${prev.result}'`);
  if (prev.fixture || mod.fixture) return no('a fixture has no measurement to carry');
  if (prev.id !== mod.id || prev.version !== mod.version) return no('measured.json names another module');
  const smoke = prev.basis === 'smoke';
  if (prev.basis !== undefined && prev.basis !== 'full' && !smoke) return no(`unknown basis '${prev.basis}'`);
  const { bounds, rates } = prev;
  let core, from;
  if (smoke) {
    core = prev.carried;
    from = prev.from;
    if (!core || typeof core !== 'object') return no('a smoke certificate without its carried measurement');
    if (core.boundsSha !== canonSha(bounds) || core.ratesSha !== canonSha(rates)) why.push('the bounds or rates are not the carried measurement\'s');
  } else {
    const { bounds: _b, rates: _r, ...rest } = prev;
    core = { ...rest, basis: 'full', boundsSha: canonSha(bounds), ratesSha: canonSha(rates) };
    from = prev.checks?.offline?.architect;
  }
  if (core.basis !== 'full' || core.result !== 'pass') why.push('the carried certificate is not a passing full measurement');
  if (!core.checks?.measure || typeof core.checks.measure !== 'object') why.push('the carried certificate has no measurement (checks.measure)');
  if (typeof from !== 'string' || !from || !(core.architect ?? []).includes(from)) why.push(`the fully measured version '${from}' is not one the carried certificate names`);
  if (from === versions.architect) why.push(`Architect ${from} is the fully measured version itself: use plain record`);
  if (core.minecraft !== versions.minecraft) why.push(`the carried measurement ran on Minecraft ${core.minecraft}, pinned ${versions.minecraft}`);
  // the program unchanged
  if (now.programSha !== core.programSha || mod.programSha !== core.programSha) why.push('the program changed since the full measurement (programSha): a full re-measure');
  // module.json unchanged but for its pins: with the measured pins put back, it is the module.json that was measured
  if (moduleShaOf(mod) !== gameModuleSha) why.push('this tool\'s moduleSha disagrees with the game\'s (canonical form): refusing to judge the pins');
  const oldPins = { kit: core.kit, checker: core.checker };
  if (!core.kit || !core.checker || moduleShaOf({ ...mod, pins: oldPins }) !== core.moduleSha) why.push('module.json differs from the fully measured one in more than its pins: a full re-measure');
  // the fresh offline and ingame on these files and versions
  if (!offline?.ok || !offEntry) why.push('the offline step did not pass');
  if (!ingame?.ok || !inEntry) why.push('the ingame step did not pass');
  if (offline?.data?.architect !== versions.architect || offline?.data?.kit !== mod.pins?.kit || offline?.data?.checker !== mod.pins?.checker) why.push('offline ran on other versions than pinned');
  // the program builds what it built when measured: every branch x bible, the same block count
  const was = new Map((core.checks?.offline?.branches ?? []).map((b) => [JSON.stringify([b.branch, b.bible]), b.blocks]));
  const nowB = offEntry?.branches ?? [];
  const moved = nowB.filter((b) => was.get(JSON.stringify([b.branch, b.bible])) !== b.blocks);
  if (!nowB.length || nowB.length !== was.size || moved.length) why.push(`the build moved: ${moved.length} of ${nowB.length} branch x bible block counts differ from the measured ${was.size}: a full re-measure`);
  // the billing check
  if (!survival) why.push('no billing check (module-measure.mjs survival)');
  else if (!stamped(survival, now, versions)) why.push('the billing check ran on other files or versions');
  else if (!survival.ok) why.push('the billing check (survival) did not pass');
  // one field round (or more); every one on these files and versions must pass
  const mine = (fields ?? []).filter((f) => stamped(f, now, versions));
  if (!mine.length) why.push('no field round on these files and versions (module-measure.mjs field --crop <c>)');
  for (const f of mine) {
    if (!f.ok) why.push(`the field round ${f.step} did not pass`);
    if (!(f.runs >= 5)) why.push(`the field round ${f.step} has ${f.runs} runs (5 needed)`);
  }
  if (why.length) return { why, m: null };
  // the field round's rates beside the carried ones (report only: the round's own bars judge it)
  const fieldRounds = mine.map((f) => ({ ...summary(f), crop: f.data?.crop, rates: (f.data?.farms ?? []).map((farm) => {
    const J = f.data.judged?.[farm.id] ?? {};
    const [width, depth] = farm.size.split('x').map(Number);
    const old = (rates ?? []).find((r) => r.params.crop === f.data.crop && r.params.width === width && r.params.depth === depth);
    return { params: { crop: f.data.crop, depth, width }, perCropDay: J.mean, perDay: Math.floor(J.mean * J.n), carriedPerCropDay: old?.perCropDay ?? null,
      carriedPerDay: old?.perDay ?? null, changePct: old ? pct(J.mean, old.perCropDay) : null };
  }) }));
  const m = {
    id: mod.id, version: mod.version, fixture: false, programSha: now.programSha, moduleSha: gameModuleSha, minecraft: versions.minecraft,
    architect: [versions.architect], kit: offline.data.kit, checker: offline.data.checker, result: 'pass', basis: 'smoke', from,
    what: core.what, bars: core.bars, runs: core.runs,
    checks: { ...checks, smoke: { rule: SMOKE_RULE, survival: summary(survival), fields: fieldRounds } },
    carried: core, bounds, rates,
  };
  return { why, m };
}
