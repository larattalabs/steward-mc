/**
 * (M5-HARD, batch-075 GPT review P2) The undo's world audit for the survival conservation checks. The ledger's site list (Architect's sites.list(owner))
 * hides a region's ground tiles, so "no site left standing" never saw a tile, and nothing compared the world's blocks with the ground before the build:
 * an undo that refunded every unit while a tile (or any built block) still stood passed. Steward's dev ledger now lists the hidden tiles too
 * (`tiles`, each with its box) and the stockpile's crate cells; this judges, from pure data:
 *
 * - standing: every site or tile the build added (in the ledger after it was built, not in the ledger before it started), other than the stockpile's
 *   own, that the ledger after the undo still has in a state other than REMOVED;
 * - left: every cell of a block-state diff (Architect's dev.region.hash diff, against a snapshot taken before the build, the crate cells left out)
 *   inside one of those sites' or tiles' restore boxes (the template's box with its foundation, approach and the row below; the box when there is
 *   none) whose class is not one the world itself causes. Allowed, as in the growth and adopt flows' diffs: `growth` (a random tick grew or spread
 *   it) and `grazed` (a sheep ate grass). Architect's classifier also calls a crop or sapling standing where air or water was "growth", and one
 *   crop or sapling where another species stood; no random tick plants one or swaps species, so either is a built block left (`planted`) and
 *   fails. Every other class (none, gravity, unsupported, live) fails;
 * - harness: what would make the audit see nothing: a ledger without its sites, tiles or crate lists, no tile before the undo when tiles are
 *   required, a box outside the snapshot, a diff missing, cut short (its list shorter than its mismatch count) or over fewer cells than the
 *   snapshot's box less the crate cells.
 *
 * Cells outside every added box are counted (`outside`) but not failed: the founding stone and the ground the player walks on are not the build's.
 */

export const ALLOWED_CLASSES = ['growth', 'grazed'];

/** Crops and saplings: a "growth" from air or water to one of these is a planted (built) block, not a random tick's. */
const PLANTED = /^minecraft:(?:wheat|carrots|potatoes|beetroots|melon_stem|pumpkin_stem|attached_melon_stem|attached_pumpkin_stem|sweet_berry_bush|cocoa|torchflower_crop|pitcher_crop|[a-z_]+_sapling)$/;
const EMPTY = /^minecraft:(?:air|cave_air|void_air|water)$/;

/** The block id of a cell value ("minecraft:wheat[age=7]{...}" -> "minecraft:wheat"). */
export function blockOf(v) {
  return String(v ?? '').replace(/[[{].*$/s, '');
}

const isStockpile = (x) => /stockpile/i.test(`${x?.kind ?? ''} ${x?.id ?? ''}`);

/** The ledger's sites and tiles together (each tagged `tile` when from the hidden list). */
export function allSites(sp) {
  return [...(sp?.sites ?? []).map((x) => ({ ...x, tile: false })), ...(sp?.tiles ?? []).map((x) => ({ ...x, tile: true }))];
}

/** The sites and tiles in `after` that are not in `before` (the build's), the stockpile's own left out. */
export function addedSites(before, after) {
  const had = new Set(allSites(before).map((x) => x.id));
  return allSites(after).filter((x) => !had.has(x.id) && !isStockpile(x));
}

/** The crate cells of any of the ledgers, as exclude boxes [x, y, z, x, y, z] (deduplicated). */
export function crateBoxes(...ledgers) {
  const seen = new Map();
  for (const sp of ledgers) for (const c of sp?.crateCells ?? []) if (Array.isArray(c) && c.length === 3) seen.set(c.join(','), [c[0], c[1], c[2], c[0], c[1], c[2]]);
  return [...seen.values()];
}

const inside = (b, x, y, z) => x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
const within = (inner, outer) => inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] >= outer[2] && inner[3] <= outer[3] && inner[4] <= outer[4] && inner[5] <= outer[5];

/** A stem and its attached form are one species (a stem attaches when its fruit grows). */
const species = (b) => b.replace(/^minecraft:attached_/, 'minecraft:');

/**
 * A diff mismatch's class as this audit judges it: Architect's, except a crop or sapling where air or water was, or where another crop or sapling
 * species stood (carrots -> wheat), is `planted`.
 */
export function judgedClass(m) {
  const cls = String(m?.class ?? 'none');
  const was = blockOf(m?.was), now = blockOf(m?.now);
  if (cls === 'growth' && PLANTED.test(now) && (EMPTY.test(was) || species(was) !== species(now))) return 'planted';
  return cls;
}

/** Cells in a box [x0, y0, z0, x1, y1, z1]. */
const volume = (b) => (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);

/**
 * The audit. `start`: the ledger before the build started (and before the snapshot); `built`: the ledger once built, before the undo; `undone`: the
 * ledger after the undo; `diff`: dev.region.hash's diff answer over `snapBox` against the snapshot, `exclude` the single-cell boxes it left out; `requireTiles`: fail as harness when the build
 * added no tile. -> { harness: [...], standing: [...], left: [...], added, tiles, outside, classes }.
 */
export function judgeUndoWorld({ start, built, undone, diff, snapBox, exclude = [], requireTiles = true }) {
  const harness = [];
  const added = addedSites(start, built);
  const tiles = added.filter((x) => x.tile);
  for (const [name, sp] of [['before the build', start], ['once built', built], ['after the undo', undone]]) {
    // no stockpile before the build (its first crate comes with the start): nothing of the owner stood
    if (!sp) { if (name !== 'before the build') harness.push(`no ledger ${name}`); }
    else if (!Array.isArray(sp.sites) || !Array.isArray(sp.tiles) || !Array.isArray(sp.crateCells)) harness.push(`the ledger ${name} lacks its sites, tiles or crateCells (an old jar?)`);
  }
  if (requireTiles && !tiles.length) harness.push(`no tile site seen before the undo (${added.length} sites added): the tile list would prove nothing`);
  if (!added.length) harness.push('the build added no site: nothing to audit');
  const boxes = [];
  for (const x of added) {
    const b = Array.isArray(x.restoreBox) && x.restoreBox.length === 6 ? x.restoreBox : x.box;
    if (!Array.isArray(b) || b.length !== 6) { harness.push(`${x.id} has no box`); continue; }
    if (!Array.isArray(snapBox) || !within(b, snapBox)) harness.push(`${x.id}'s box ${JSON.stringify(b)} is outside the snapshot ${JSON.stringify(snapBox)}`);
    boxes.push({ id: x.id, box: b });
  }
  // standing: every site or tile the build added still there, in any state but REMOVED
  const now = new Map(allSites(undone).map((x) => [x.id, x]));
  const standing = added.map((x) => now.get(x.id)).filter((x) => x && x.state !== 'REMOVED').map((x) => ({ id: x.id, kind: x.kind, state: x.state, tile: x.tile }));
  // left: cells in the build's boxes that are not the world's doing
  const left = [], outside = { count: 0, classes: {} };
  const classes = {};
  if (!diff || typeof diff.mismatches !== 'number' || !Array.isArray(diff.list)) harness.push(`no diff answer: ${JSON.stringify(diff)?.slice(0, 160)}`);
  else if (diff.list.length < diff.mismatches) harness.push(`the diff listed ${diff.list.length} of ${diff.mismatches} mismatches (raise its limit)`);
  if (diff && Array.isArray(snapBox) && typeof diff.mismatches === 'number') {
    // every cell of the snapshot's box compared but the crate cells inside it
    const cut = new Set((exclude ?? []).filter((e) => inside(snapBox, e[0], e[1], e[2])).map((e) => `${e[0]},${e[1]},${e[2]}`)).size;
    const want = volume(snapBox) - cut;
    if (diff.cells !== want) harness.push(`the diff compared ${diff.cells} cells, not the snapshot's ${want}`);
  }
  for (const m of diff?.list ?? []) {
    const [x, y, z] = String(m.pos).split(',').map(Number);
    const cls = judgedClass(m);
    const hit = boxes.find((b) => inside(b.box, x, y, z));
    if (!hit) { outside.count++; outside.classes[cls] = (outside.classes[cls] ?? 0) + 1; continue; }
    classes[cls] = (classes[cls] ?? 0) + 1;
    if (!ALLOWED_CLASSES.includes(cls)) left.push({ pos: m.pos, was: m.was, now: m.now, class: cls, site: hit.id });
  }
  return { harness, standing, left, added: added.length, tiles: tiles.length, boxes: boxes.length, outside, classes };
}

/** The audit's words: '' when clean, else what failed. */
export function auditWords(a) {
  const out = [];
  if (a.harness.length) out.push(`harness: ${a.harness.slice(0, 4).join('; ')}`);
  if (a.standing.length) out.push(`${a.standing.length} site(s) still standing: ${a.standing.slice(0, 6).map((x) => `${x.id}${x.tile ? ' (tile)' : ''} ${x.state}`).join(', ')}`);
  if (a.left.length) out.push(`${a.left.length} built cell(s) not restored: ${a.left.slice(0, 6).map((c) => `${c.pos} ${c.was} -> ${c.now} (${c.class}, ${c.site})`).join('; ')}`);
  return out.join('; ');
}
