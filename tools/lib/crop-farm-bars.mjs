// crop_farm@1's measurement bars that come from M0's probe (docs/MEASURE-M2.md, item 3): the band of a field's net output rate under
// §3.1's protocol (plant, wait T, harvest every mature crop, replant those, 3 cycles), predicted from the probe's own per-cell maturity
// times (fixtures/modules/probe-m0.json, G, 5 runs x 80 crops a crop). Pure and deterministic (a seeded generator), so the bars it gives
// are the same numbers every time it is run; `node tools/module-measure.mjs bands` prints them.
//
// The model: a crop cell planted at time p is mature at p + t, t drawn from the probe's watered t_cell values for that crop (all 5 runs
// pooled). The probe stopped at 90% mature, so 5 to 8 of its 80 cells a run have no t_cell: they are drawn instead from an Erlang
// (k = the crop's stages, rate fitted to the pooled mean) conditioned on t > that run's end. A cell harvested at cycle n is replanted at
// n T with a fresh draw; one left immature keeps growing. Drops per mature crop: one of the 5 runs' mean drops, drawn per simulated field.
// The unwatered control: the same loop with the probe's unwatered T in place of t (its median T ratio to the watered median scales every
// draw: the probe kept no unwatered per-cell times).

export const CROPS = {
  wheat: { block: 'minecraft:wheat', stages: 7, item: 'minecraft:wheat', plant: 'minecraft:wheat_seeds', plantIsItem: false },
  carrots: { block: 'minecraft:carrots', stages: 7, item: 'minecraft:carrot', plant: 'minecraft:carrot', plantIsItem: true },
  potatoes: { block: 'minecraft:potatoes', stages: 7, item: 'minecraft:potato', plant: 'minecraft:potato', plantIsItem: true },
  beetroots: { block: 'minecraft:beetroots', stages: 3, item: 'minecraft:beetroot', plant: 'minecraft:beetroot_seeds', plantIsItem: false },
};
/** The probe's T (median time to a mature majority, G1) per crop: the cycle length. */
export const T = { wheat: 60060, carrots: 60060, potatoes: 56056, beetroots: 35035 };
export const CYCLES = 3;
export const DAY = 24000;
/** G3's absolute usefulness bar: 24 items a day from a 9x9 field of 80 crops, i.e. 0.3 a day per crop cell (scaled by the crops a field has). */
export const USEFUL_PER_CROP_DAY = 24 / 80;

const median = (a) => { const s = [...a].sort((x, y) => x - y); const n = s.length; return n % 2 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2; };
const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
const q = (s, f) => s[Math.min(s.length - 1, Math.max(0, Math.floor(f * (s.length - 1))))];

/** A small seeded generator (mulberry32). */
function rng(seed) {
  let a = seed >>> 0;
  return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

/** The probe's per-crop growth data: {pool: [t], censored: [{after}], mean, yields: [drops per crop], unwateredRatio}. */
export function probeData(probe, crop) {
  const runs = probe.probes.G.raw.runs.map((r) => r[crop]);
  const pool = runs.flatMap((r) => r.watered.tCells);
  const censored = runs.flatMap((r) => Array.from({ length: 80 - r.watered.tCells.length }, () => ({ after: r.watered.harvest.at })));
  const c = CROPS[crop];
  const yields = runs.map((r) => (r.watered.harvest.items[c.item] ?? 0) / r.watered.harvest.broken);
  const unwateredRatio = median(runs.map((r) => r.unwatered.T ?? 150000)) / median(runs.map((r) => r.watered.T));
  return { pool, censored, mean: mean(pool), yields, unwateredRatio, n: pool.length + censored.length };
}

/** One simulated field of `n` crop cells under the protocol: {harvested, drops, net, perDay, perCropDay}. */
export function simulate(d, crop, n, rand, scale = 1) {
  const c = CROPS[crop];
  const rate = c.stages / d.mean; // Erlang rate per stage, fitted to the pooled mean
  const erlang = () => { let s = 0; for (let i = 0; i < c.stages; i++) s += -Math.log(1 - rand()) / rate; return s; };
  const draw = () => {
    const i = Math.floor(rand() * d.n);
    if (i < d.pool.length) return d.pool[i] * scale;
    const after = d.censored[i - d.pool.length].after;
    for (let k = 0; k < 10000; k++) { const t = erlang(); if (t > after) return t * scale; }
    return after * scale;
  };
  const y = d.yields[Math.floor(rand() * d.yields.length)];
  const planted = new Float64Array(n), due = new Float64Array(n);
  for (let i = 0; i < n; i++) due[i] = draw();
  let harvested = 0;
  for (let k = 1; k <= CYCLES; k++) {
    const now = k * T[crop];
    for (let i = 0; i < n; i++) if (planted[i] + due[i] <= now) { harvested++; planted[i] = now; due[i] = draw(); }
  }
  const drops = harvested * y;
  const net = drops - (c.plantIsItem ? harvested : 0);
  const perDay = net / ((CYCLES * T[crop]) / DAY);
  return { harvested, drops, net, perDay, perCropDay: perDay / n };
}

/** The bars for a field of `n` crops of `crop`: the band of one run's net rate per crop cell a day, the usefulness bar and the control. */
export function bands(probe, crop, n, sims = 4000) {
  const d = probeData(probe, crop);
  const rand = rng(0x5eed + n * 31 + Object.keys(CROPS).indexOf(crop));
  const w = Array.from({ length: sims }, () => simulate(d, crop, n, rand).perCropDay).sort((a, b) => a - b);
  const u = Array.from({ length: sims }, () => simulate(d, crop, n, rand, d.unwateredRatio).perCropDay).sort((a, b) => a - b);
  return {
    crop, n, T: T[crop], cycles: CYCLES,
    predicted: { mean: mean(w), q005: q(w, 0.005), q995: q(w, 0.995) },
    // one run passes inside the predicted 99% band widened 15% each way (the model is the probe's 9x9 with a centre source; ours has posts)
    band: [0.85 * q(w, 0.005), 1.15 * q(w, 0.995)],
    useful: USEFUL_PER_CROP_DAY,
    unwatered: { mean: mean(u), ratio: d.unwateredRatio },
  };
}
