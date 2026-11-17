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

/**
 * Each real module's smoke round, by catalog id (ModuleCatalog.SMOKE_ROUNDS): crop_farm a field round (`field-<crop>`, 5 runs or more, its farms
 * judged), storage_hall its deterministic hall step. A module not in it carries nothing by smoke.
 */
export const SMOKE_ROUNDS = Object.freeze({ crop_farm: 'field', storage_hall: 'hall', fixture_plot: 'field' }); // fixture_plot: M1's test module

const SMOKE_RULE = 'speed rule 4 (Noah 2026-11-13, AGENTS.md "Module re-certification"): a plain Architect version bump with the program and '
  + 'module.json unchanged but for the pins: fresh offline and ingame, the billing check (survival, and the bounds re-quoted equal to the carried '
  + 'ones item for item) and one field round; the full measurement is carried';
const stamped = (r, now, v) => r?.programSha === now.programSha && r?.moduleJsonSha === now.moduleJsonSha && r?.architect === v.architect && r?.minecraft === v.minecraft;
const summary = (r) => ({ step: r.step, at: r.at, head: r.head, world: r.world ?? null, seconds: r.seconds, runs: r.runs ?? null,
  programSha: r.programSha, moduleJsonSha: r.moduleJsonSha, architect: r.architect, minecraft: r.minecraft,
  passed: r.checks.filter((c) => c.ok).length, of: r.checks.length });
/** Why an evidence file is not a passing run of `step` (null: it is): the named step, ok, and every one of its checks passed. */
const unpassed = (r, step) => (r.step !== step ? `is step '${r.step}', not ${step}` : !r.ok ? 'did not pass'
  : !Array.isArray(r.checks) || !r.checks.length || r.checks.some((c) => !c?.ok) ? 'has a failed or no check' : null);
/** A bound's identity: its params and Style. */
const boundKey = (b) => JSON.stringify([canonSha(b.params), b.style]);
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
 * @param {{evidence: object|null, bounds: object[]|null, problems: string[]}} a.requote  artifacts/m2/bounds.json (the 108 quotes on the new
 *                                 pin) and the per-Style bounds the tool derived from it (tools/lib/style-bounds.mjs), or why it could not
 */
export function smokeCarry({ prev, mod, now, gameModuleSha, versions, offline, offEntry, ingame, inEntry, checks, survival, billingStep = 'survival', fields, requote }) {
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
  const bKey = (b) => JSON.stringify([canonSha(b.branch), b.bible]);
  const was = new Map((core.checks?.offline?.branches ?? []).map((b) => [bKey(b), b.blocks]));
  const nowB = offEntry?.branches ?? [];
  const moved = nowB.filter((b) => was.get(bKey(b)) !== b.blocks);
  if (!nowB.length || nowB.length !== was.size || new Set(nowB.map(bKey)).size !== nowB.length || moved.length || nowB.some((b) => !b.ok))
    why.push(`the build moved: ${moved.length} of ${nowB.length} branch x bible block counts differ from the measured ${was.size} (or one repeats or failed): a full re-measure`);
  // the billing check: survival bills each growth its quote, and the quotes are the carried bounds item for item
  if (!survival) why.push('no billing check (module-measure.mjs survival)');
  else if (!stamped(survival, now, versions)) why.push('the billing check ran on other files or versions');
  else if (unpassed(survival, billingStep)) why.push(`the billing check (${billingStep}) ${unpassed(survival, billingStep)}`);
  const rq = requote?.evidence;
  if (!rq) why.push('no bounds re-quote (module-measure.mjs bounds)');
  else if (!stamped(rq, now, versions)) why.push('the bounds re-quote ran on other files or versions');
  else if (unpassed(rq, 'bounds')) why.push(`the bounds re-quote ${unpassed(rq, 'bounds')}`);
  else if (requote.problems?.length || !Array.isArray(requote.bounds)) why.push(`the bounds re-quote: ${(requote.problems ?? []).slice(0, 3).join('; ') || 'no bounds'}`);
  else {
    const fresh = new Map(requote.bounds.map((b) => [boundKey(b), canonSha(b)]));
    const old = bounds ?? [];
    const differ = old.filter((b) => fresh.get(boundKey(b)) !== canonSha(b));
    if (!old.length || fresh.size !== requote.bounds.length || fresh.size !== old.length || differ.length)
      why.push(`the bill moved: ${differ.length} of ${old.length} carried bounds differ from the re-quote's ${requote.bounds.length}: a full re-measure`);
  }
  // one field round (or more); every one on these files and versions must pass
  // the round is the module's own (SMOKE_ROUNDS), never what the evidence calls itself: storage_hall its hall step (module-measure-hall.mjs hall:
  // shape, storage, restart, unload, light, undo), crop_farm a field round
  const round = SMOKE_ROUNDS[mod.id];
  if (!round) why.push(`no smoke round is defined for ${mod.id}: a smoke certificate carries nothing for it`);
  const mine = (fields ?? []).filter((f) => stamped(f, now, versions));
  if (!mine.length) why.push('no field round on these files and versions (module-measure.mjs field --crop <c>, or module-measure-hall.mjs hall)');
  for (const f of mine) {
    if (round === 'hall') {
      const u = unpassed(f, 'hall');
      if (u) why.push(`the hall round ${u}`);
      continue;
    }
    const u = unpassed(f, `field-${f.data?.crop}`);
    if (u) why.push(`the field round ${f.step} ${u}`);
    if (!(f.runs >= 5)) why.push(`the field round ${f.step} has ${f.runs} runs (5 needed)`);
    if (!f.data?.farms?.length || f.data.farms.some((x) => typeof f.data.judged?.[x.id]?.mean !== 'number')) why.push(`the field round ${f.step} judged no farm`);
  }
  if (why.length) return { why, m: null };
  // the field round's rates beside the carried ones (report only: the round's own bars judge it)
  const fieldRounds = mine.map((f) => round === 'hall' ? { ...summary(f), rates: [] } : ({ ...summary(f), crop: f.data?.crop, rates: (f.data?.farms ?? []).map((farm) => {
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
    checks: { ...checks, smoke: { rule: SMOKE_RULE, survival: summary(survival), bounds: { ...summary(rq), equal: bounds.length }, fields: fieldRounds } },
    carried: core, bounds, rates,
    // (M4b GPT review 11) a sorting hall's fill member (its item params and 41 a filter), which ModuleBound strips before matching a bound
    ...(prev?.fill ? { fill: prev.fill } : {}),
  };
  return { why, m };
}
