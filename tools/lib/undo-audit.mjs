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
 *   fails. Sugar cane, kelp and vines are "growth" to it wherever they stand; a random tick only grows them from their own kind (cane and kelp up
 *   from the cell below, vines spreading from any side), so a new cell of one is growth only when a chain of new cells of it reaches a cell that
 *   held it before the build and holds it still (HARNESS-T, the M5-HARD leftover); else it is `unrooted` and fails. A neighbour the diff does not
 *   list is unchanged, so its block now (`lookup`, read by the caller through {@link rootProbes}) is its block before the build. Every other class
 *   (none, gravity, unsupported, live) fails;
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

/** Plants a random tick grows only from their own kind: each with the block ids it is, and the sides (dx, dy, dz) its new cells grow from. */
export const ROOTED = [
  { name: 'cane', ids: ['minecraft:sugar_cane'], sides: [[0, -1, 0]] },
  { name: 'kelp', ids: ['minecraft:kelp', 'minecraft:kelp_plant'], sides: [[0, -1, 0]] },
  { name: 'vines', ids: ['minecraft:vine'], sides: [[0, 1, 0], [0, -1, 0], [1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1]] },
];

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

/** The ROOTED kind a cell value is, or null. */
export function rootedKind(v) {
  const b = blockOf(v);
  return ROOTED.find((k) => k.ids.includes(b)) ?? null;
}

const posOf = (p) => String(p).split(',').map(Number);
const step = (p, d) => { const [x, y, z] = posOf(p); return `${x + d[0]},${y + d[1]},${z + d[2]}`; };
/** A mismatch that is a new cell of a ROOTED plant: Architect's growth, its block now one, the cell not one of that kind before. */
const newRooted = (m) => {
  if (String(m?.class) !== 'growth') return null;
  const k = rootedKind(m.now);
  return k && rootedKind(m.was) !== k ? k : null;
};

/**
 * Whether the new {@code kind} cell at {@code pos} grew from a root: a side (kind.sides) that holds the kind now and held it before the build (a
 * listed cell's `was`; an unlisted one is unchanged, its block now from {@code lookup}), or a listed new cell of the kind that is rooted itself.
 * {@code byPos}: the diff's mismatches by pos. A side whose block is unknown is no root.
 */
function rooted(pos, kind, byPos, lookup, seen = new Set([pos])) {
  for (const d of kind.sides) {
    const n = step(pos, d);
    if (seen.has(n)) continue;
    seen.add(n);
    const m = byPos.get(n);
    if (m) {
      if (rootedKind(m.now) !== kind) continue;
      if (rootedKind(m.was) === kind || rooted(n, kind, byPos, lookup, seen)) return true;
    } else if (rootedKind(lookup?.[n]) === kind) return true;
  }
  return false;
}

/**
 * The unlisted cells whose blocks the audit needs (each new ROOTED cell inside {@code boxes}, and the new cells of its kind it reaches, looks at
 * its sides): [{ pos, kind, ids }], one per pos. The caller reads each (is it one of `ids`?) into the `lookup` it passes {@link judgeUndoWorld}.
 */
export function rootProbes(diff, boxes) {
  const byPos = new Map((diff?.list ?? []).map((m) => [String(m.pos), m]));
  const out = new Map();
  const walk = (pos, kind, seen) => {
    for (const d of kind.sides) {
      const n = step(pos, d);
      if (seen.has(n)) continue;
      seen.add(n);
      const m = byPos.get(n);
      if (!m) { if (!out.has(n)) out.set(n, { pos: n, kind: kind.name, ids: kind.ids }); }
      else if (rootedKind(m.now) === kind && rootedKind(m.was) !== kind) walk(n, kind, seen);
    }
  };
  for (const m of diff?.list ?? []) {
    const k = newRooted(m);
    if (!k) continue;
    const [x, y, z] = posOf(m.pos);
    if (!boxes.some((b) => inside(b, x, y, z))) continue;
    walk(String(m.pos), k, new Set([String(m.pos)]));
  }
  return [...out.values()];
}

/** The restore boxes of the sites and tiles the build added (as {@link judgeUndoWorld} audits them; a site with no box is left out). */
export function addedBoxes(start, built) {
  return addedSites(start, built).map((x) => (Array.isArray(x.restoreBox) && x.restoreBox.length === 6 ? x.restoreBox : x.box)).filter((b) => Array.isArray(b) && b.length === 6);
}

/** A stem and its attached form are one species (a stem attaches when its fruit grows). */
const species = (b) => b.replace(/^minecraft:attached_/, 'minecraft:');

/**
 * A diff mismatch's class as this audit judges it: Architect's, except a crop or sapling where air or water was, or where another crop or sapling
 * species stood (carrots -> wheat), is `planted`; and, given {@code roots}, a new cane, kelp or vine cell with no root of its kind ({@code roots.byPos}: the diff's
 * mismatches by pos; {@code roots.lookup}: unlisted cells' blocks now, by pos) is `unrooted`. Without {@code roots} (a cell outside every box, only counted) the root
 * walk is skipped.
 */
export function judgedClass(m, roots = null) {
  const cls = String(m?.class ?? 'none');
  const was = blockOf(m?.was), now = blockOf(m?.now);
  if (cls === 'growth' && PLANTED.test(now) && (EMPTY.test(was) || species(was) !== species(now))) return 'planted';
  const k = newRooted(m);
  if (k && roots && !rooted(String(m.pos), k, roots.byPos ?? new Map(), roots.lookup ?? {})) return 'unrooted';
  return cls;
}

/** Cells in a box [x0, y0, z0, x1, y1, z1]. */
const volume = (b) => (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);

/**
 * The audit. `start`: the ledger before the build started (and before the snapshot); `built`: the ledger once built, before the undo; `undone`: the
 * ledger after the undo; `diff`: dev.region.hash's diff answer over `snapBox` against the snapshot, `exclude` the single-cell boxes it left out; `requireTiles`: fail as harness when the build
 * added no tile; `lookup`: the blocks now of {@link rootProbes}' cells, by pos ({pos: block id}; a cell not in it is unknown, so no root). -> { harness: [...], standing: [...], left: [...], added, tiles, outside, classes }.
 */
export function judgeUndoWorld({ start, built, undone, diff, snapBox, exclude = [], requireTiles = true, lookup = {} }) {
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
  const byPos = new Map((diff?.list ?? []).map((m) => [String(m.pos), m]));
  for (const m of diff?.list ?? []) {
    const [x, y, z] = String(m.pos).split(',').map(Number);
    const hit = boxes.find((b) => inside(b.box, x, y, z));
    // (HARNESS-T) the root walk only where it can fail: outside every box a class is only counted
    const cls = hit ? judgedClass(m, { byPos, lookup }) : judgedClass(m);
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
