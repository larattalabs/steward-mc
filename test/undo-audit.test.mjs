import test from 'node:test';
import assert from 'node:assert/strict';
import { judgeUndoWorld, auditWords, crateBoxes, judgedClass, addedSites, rootProbes, addedBoxes } from '../tools/lib/undo-audit.mjs';

const SNAP = [0, 40, 0, 200, 200, 200];
const start = { sites: [{ id: 'old', kind: 'building', state: 'BUILT', box: [150, 60, 150, 160, 70, 160] }], tiles: [], crateCells: [] };
const built = {
  sites: [...start.sites, { id: 's1', kind: 'building', state: 'BUILT', box: [10, 60, 10, 20, 80, 20] }, { id: 'stockpile:x', kind: 'stockpile', state: 'BUILT', box: [5, 60, 5, 5, 60, 5] }],
  tiles: [{ id: 't1', kind: 'tile', state: 'BUILT', region: 'r1', box: [30, 60, 30, 45, 70, 45] }],
  crateCells: [[5, 60, 5]],
};
const undone = { sites: [...start.sites, { id: 'stockpile:x', kind: 'stockpile', state: 'BUILT' }], tiles: [], crateCells: [[5, 60, 5], [6, 60, 5]] };
const CELLS = 201 * 161 * 201;
const clean = { cells: CELLS, mismatches: 0, classes: {}, list: [] };
const diff = (list) => ({ cells: CELLS, mismatches: list.length, classes: {}, list });

test('clean: nothing standing, no cell left', () => {
  const a = judgeUndoWorld({ start, built, undone, diff: clean, snapBox: SNAP });
  assert.deepEqual(a.harness, []);
  assert.deepEqual(a.standing, []);
  assert.deepEqual(a.left, []);
  assert.equal(a.tiles, 1);
  assert.equal(a.added, 2);
  assert.equal(auditWords(a), '');
});

test('a tile left standing fails (the sites list alone would pass)', () => {
  const u = { ...undone, tiles: [{ id: 't1', kind: 'tile', state: 'BUILT', region: 'r1' }] };
  const a = judgeUndoWorld({ start, built, undone: u, diff: clean, snapBox: SNAP });
  assert.equal(a.standing.length, 1);
  assert.equal(a.standing[0].tile, true);
  assert.match(auditWords(a), /t1 \(tile\) BUILT/);
});

test('a REMOVED tile record is gone', () => {
  const u = { ...undone, tiles: [{ id: 't1', kind: 'tile', state: 'REMOVED' }] };
  assert.deepEqual(judgeUndoWorld({ start, built, undone: u, diff: clean, snapBox: SNAP }).standing, []);
});

test('a built block left in a tile box fails; in a site box too', () => {
  const a = judgeUndoWorld({ start, built, undone, diff: diff([
    { pos: '35,61,35', was: 'minecraft:grass_block[snowy=false]', now: 'minecraft:stone_bricks', class: 'none' },
    { pos: '12,62,12', was: 'minecraft:air', now: 'minecraft:oak_planks', class: 'none' },
  ]), snapBox: SNAP });
  assert.equal(a.left.length, 2);
  assert.deepEqual(a.left.map((c) => c.site), ['t1', 's1']);
  assert.match(auditWords(a), /2 built cell\(s\) not restored/);
});

test('growth and grazed pass; gravity, unsupported and live fail', () => {
  const a = judgeUndoWorld({ start, built, undone, diff: diff([
    { pos: '35,61,35', was: 'minecraft:dirt', now: 'minecraft:grass_block[snowy=false]', class: 'growth' },
    { pos: '36,61,35', was: 'minecraft:short_grass', now: 'minecraft:air', class: 'grazed' },
    { pos: '37,61,35', was: 'minecraft:sand', now: 'minecraft:air', class: 'gravity' },
    { pos: '38,61,35', was: 'minecraft:poppy', now: 'minecraft:poppy', class: 'unsupported' },
    { pos: '39,61,35', was: 'minecraft:hopper{}', now: 'minecraft:hopper{Items:[]}', class: 'live' },
  ]), snapBox: SNAP });
  assert.deepEqual(a.left.map((c) => c.class), ['gravity', 'unsupported', 'live']);
  assert.deepEqual(a.classes, { growth: 1, grazed: 1, gravity: 1, unsupported: 1, live: 1 });
});

test('a crop or sapling where air was is planted, not growth', () => {
  assert.equal(judgedClass({ was: 'minecraft:air', now: 'minecraft:wheat[age=7]', class: 'growth' }), 'planted');
  assert.equal(judgedClass({ was: 'minecraft:air', now: 'minecraft:oak_sapling[stage=0]', class: 'growth' }), 'planted');
  assert.equal(judgedClass({ was: 'minecraft:wheat[age=2]', now: 'minecraft:wheat[age=7]', class: 'growth' }), 'growth');
  // outside every box (no roots given) only counted, so Architect's class stands; inside a box the root walk judges it (the HARNESS-T tests)
  assert.equal(judgedClass({ was: 'minecraft:air', now: 'minecraft:sugar_cane[age=0]', class: 'growth' }), 'growth');
  assert.equal(judgedClass({ pos: '1,1,1', was: 'minecraft:air', now: 'minecraft:sugar_cane[age=0]', class: 'growth' }, {}), 'unrooted');
  const a = judgeUndoWorld({ start, built, undone, diff: diff([{ pos: '12,61,12', was: 'minecraft:air', now: 'minecraft:wheat[age=3]', class: 'growth' }]), snapBox: SNAP });
  assert.equal(a.left.length, 1);
  assert.equal(a.left[0].class, 'planted');
});

test('a cell outside every added box is counted, not failed', () => {
  const a = judgeUndoWorld({ start, built, undone, diff: diff([
    { pos: '100,61,100', was: 'minecraft:air', now: 'minecraft:stone', class: 'none' },
    { pos: '155,65,155', was: 'minecraft:air', now: 'minecraft:stone', class: 'none' },
  ]), snapBox: SNAP });
  assert.deepEqual(a.left, []);
  assert.equal(a.outside.count, 2);
});

test('harness: a truncated diff, no diff, no tiles, a box outside the snapshot', () => {
  const cut = judgeUndoWorld({ start, built, undone, diff: { cells: CELLS, mismatches: 5, classes: {}, list: [] }, snapBox: SNAP });
  assert.match(cut.harness.join(), /listed 0 of 5/);
  assert.match(judgeUndoWorld({ start, built, undone, diff: null, snapBox: SNAP }).harness.join(), /no diff answer/);
  const noTiles = judgeUndoWorld({ start, built: { ...built, tiles: [] }, undone, diff: clean, snapBox: SNAP });
  assert.match(noTiles.harness.join(), /no tile site seen/);
  assert.match(auditWords(noTiles), /^harness: /);
  assert.deepEqual(judgeUndoWorld({ start, built: { ...built, tiles: [] }, undone, diff: clean, snapBox: SNAP, requireTiles: false }).harness, []);
  const small = judgeUndoWorld({ start, built, undone, diff: clean, snapBox: [0, 40, 0, 40, 200, 40] });
  assert.match(small.harness.join(), /t1's box .* is outside the snapshot/);
  const nothing = judgeUndoWorld({ start: built, built, undone, diff: clean, snapBox: SNAP, requireTiles: false });
  assert.match(nothing.harness.join(), /added no site/);
});

test('the added sites leave out what stood before and the stockpile; crate cells deduplicated as boxes', () => {
  assert.deepEqual(addedSites(start, built).map((x) => x.id), ['s1', 't1']);
  assert.deepEqual(crateBoxes(built, undone, null), [[5, 60, 5, 5, 60, 5], [6, 60, 5, 6, 60, 5]]);
});

test('(GPT review P1) a crop of another species where one stood is planted; a stem attaching is growth', () => {
  assert.equal(judgedClass({ was: 'minecraft:carrots[age=7]', now: 'minecraft:wheat[age=7]', class: 'growth' }), 'planted');
  assert.equal(judgedClass({ was: 'minecraft:oak_sapling[stage=0]', now: 'minecraft:birch_sapling[stage=0]', class: 'growth' }), 'planted');
  assert.equal(judgedClass({ was: 'minecraft:melon_stem[age=7]', now: 'minecraft:attached_melon_stem[facing=north]', class: 'growth' }), 'growth');
});

test('(GPT review P1) a block left in the restore box (the foundation or approach) outside the template box fails', () => {
  const b = { ...built, sites: built.sites.map((x) => (x.id === 's1' ? { ...x, restoreBox: [8, 58, 8, 24, 80, 24] } : x)) };
  const a = judgeUndoWorld({ start, built: b, undone, diff: diff([{ pos: '9,59,9', was: 'minecraft:dirt', now: 'minecraft:cobblestone', class: 'none' }]), snapBox: SNAP });
  assert.equal(a.left.length, 1);
  assert.equal(a.left[0].site, 's1');
  const out = judgeUndoWorld({ start, built: { ...b, sites: b.sites.map((x) => (x.id === 's1' ? { ...x, restoreBox: [-5, 58, 8, 24, 80, 24] } : x)) }, undone, diff: clean, snapBox: SNAP });
  assert.match(out.harness.join(), /s1's box .* is outside the snapshot/);
});

test('(GPT review P2) a diff over too few cells, or a ledger without its lists, is harness; no ledger before the build is fine', () => {
  assert.match(judgeUndoWorld({ start, built, undone, diff: { cells: 0, mismatches: 0, classes: {}, list: [] }, snapBox: SNAP }).harness.join(), /compared 0 cells, not the snapshot's/);
  const ex = [[5, 60, 5, 5, 60, 5], [6, 60, 5, 6, 60, 5], [500, 60, 5, 500, 60, 5]];
  assert.deepEqual(judgeUndoWorld({ start, built, undone, diff: { ...clean, cells: CELLS - 2 }, snapBox: SNAP, exclude: ex }).harness, []);
  assert.match(judgeUndoWorld({ start, built, undone: { sites: [] }, diff: clean, snapBox: SNAP }).harness.join(), /after the undo lacks its sites, tiles or crateCells/);
  assert.match(judgeUndoWorld({ start, built: null, undone, diff: clean, snapBox: SNAP }).harness.join(), /no ledger once built/);
  assert.deepEqual(judgeUndoWorld({ start: null, built, undone, diff: clean, snapBox: SNAP }).harness, []);
});

// (HARNESS-T, the M5-HARD leftover) cane, kelp and vines pass as growth only when they grew from a root of their kind that stood before the build
const inS1 = (list, lookup) => judgeUndoWorld({ start, built, undone, diff: diff(list), snapBox: SNAP, lookup });

test('cane grown on cane that stood before the build passes; cane on bare sand next to water fails', () => {
  const grown = [{ pos: '12,62,12', was: 'minecraft:air', now: 'minecraft:sugar_cane[age=0]', class: 'growth' }];
  assert.deepEqual(rootProbes(diff(grown), addedBoxes(start, built)).map((p) => [p.pos, p.kind]), [['12,61,12', 'cane']]);
  const ok1 = inS1(grown, { '12,61,12': 'minecraft:sugar_cane' });
  assert.deepEqual(ok1.left, []);
  assert.deepEqual(ok1.classes, { growth: 1 });
  // the cell below is sand (where a farm plants cane, beside water): no random tick puts cane there
  const bad = inS1(grown, { '12,61,12': 'minecraft:sand', '13,61,12': 'minecraft:water' });
  assert.deepEqual(bad.left.map((c) => c.class), ['unrooted']);
  assert.match(auditWords(bad), /unrooted/);
  // a cell the caller could not read is no root
  assert.deepEqual(inS1(grown, {}).left.map((c) => c.class), ['unrooted']);
});

test('a two-cell cane chain roots through its listed lower cell; cane on a cell that only now holds cane fails', () => {
  const chain = [
    { pos: '12,63,12', was: 'minecraft:air', now: 'minecraft:sugar_cane[age=0]', class: 'growth' },
    { pos: '12,62,12', was: 'minecraft:air', now: 'minecraft:sugar_cane[age=0]', class: 'growth' },
  ];
  assert.deepEqual(rootProbes(diff(chain), addedBoxes(start, built)).map((p) => p.pos), ['12,61,12']);
  assert.deepEqual(inS1(chain, { '12,61,12': 'minecraft:sugar_cane' }).left, []);
  assert.equal(inS1(chain, { '12,61,12': 'minecraft:dirt' }).left.length, 2);
  // the lower cell was built (class none, air before): the cane on it has no root either
  const built2 = [chain[0], { pos: '12,62,12', was: 'minecraft:air', now: 'minecraft:sugar_cane[age=0]', class: 'none' }];
  assert.deepEqual(inS1(built2, { '12,61,12': 'minecraft:sand' }).left.map((c) => c.class).sort(), ['none', 'unrooted']);
});

test('kelp grown on kelp in water passes (the old top turned kelp_plant); kelp on a solid block in water fails', () => {
  const grown = [
    { pos: '35,63,35', was: 'minecraft:water[level=0]', now: 'minecraft:kelp[age=4]', class: 'growth' },
    { pos: '35,62,35', was: 'minecraft:kelp[age=3]', now: 'minecraft:kelp_plant', class: 'growth' },
  ];
  assert.deepEqual(rootProbes(diff(grown), addedBoxes(start, built)), []);
  assert.deepEqual(inS1(grown, {}).left, []);
  const planted = [{ pos: '35,63,35', was: 'minecraft:water[level=0]', now: 'minecraft:kelp[age=4]', class: 'growth' }];
  assert.deepEqual(inS1(planted, { '35,62,35': 'minecraft:gravel' }).left.map((c) => c.class), ['unrooted']);
});

test('vines spread from a vine beside them pass; a vine on a bare wall fails', () => {
  const spread = [{ pos: '36,64,36', was: 'minecraft:air', now: 'minecraft:vine[north=true]', class: 'growth' }];
  const probes = rootProbes(diff(spread), addedBoxes(start, built));
  assert.equal(probes.length, 6);
  assert.ok(probes.every((p) => p.kind === 'vines' && p.ids.includes('minecraft:vine')));
  assert.deepEqual(inS1(spread, { '37,64,36': 'minecraft:vine' }).left, []);
  assert.deepEqual(inS1(spread, { '36,65,36': 'minecraft:vine' }).left, []);
  // only a solid face next to it (the build's wall left): no vine to spread from
  assert.deepEqual(inS1(spread, { '36,64,35': 'minecraft:stone_bricks' }).left.map((c) => c.class), ['unrooted']);
  // a vine where a vine was (its faces changed) is the old plant's, rooted or not
  assert.deepEqual(inS1([{ pos: '36,64,36', was: 'minecraft:vine[north=true]', now: 'minecraft:vine[east=true,north=true]', class: 'growth' }], {}).left, []);
});

test('outside every box cane, kelp and vines are only counted, never probed', () => {
  const far = [{ pos: '100,62,100', was: 'minecraft:air', now: 'minecraft:sugar_cane[age=0]', class: 'growth' }];
  assert.deepEqual(rootProbes(diff(far), addedBoxes(start, built)), []);
  const a = inS1(far, {});
  assert.deepEqual(a.left, []);
  assert.deepEqual(a.outside, { count: 1, classes: { growth: 1 } });
});
