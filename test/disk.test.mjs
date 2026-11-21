// slice E2E-DISK: the keep-or-delete decision after a run and the free-space guard. The filesystem calls (statfs, lstat, rm, unlink) are
// injected: nothing here deletes or reads a real disk.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { minFreeGiB, existingAncestor, freeBytes, checkFreeSpace, keepReason, isInside, deleteNote, cleanupRun, mergeCleanups, laneRunDirs, addCleanup,
  DEFAULT_MIN_FREE_GIB, EXIT_LOW_DISK, claimRunDir, ownsRunDir, runItems, OWNER_FILE } from '../tools/lib/disk.mjs';

const GIB = 1024 ** 3;
const statfsOf = (gib, calls = []) => (p) => { calls.push(p); return { bavail: (gib * GIB) / 4096, bsize: 4096 }; };
const yes = () => true;

// ------------------------------------------------------------------ the guard

test('the threshold: 150 GiB by default, E2E_MIN_FREE_GIB overrides, 0 turns it off, junk falls back with a warning', () => {
  assert.equal(DEFAULT_MIN_FREE_GIB, 150);
  assert.deepEqual(minFreeGiB({}), { gib: 150 });
  assert.deepEqual(minFreeGiB({ E2E_MIN_FREE_GIB: '' }), { gib: 150 });
  assert.deepEqual(minFreeGiB({ E2E_MIN_FREE_GIB: '40' }), { gib: 40 });
  assert.deepEqual(minFreeGiB({ E2E_MIN_FREE_GIB: '0' }), { gib: 0 });
  for (const bad of ['-1', 'lots', 'NaN']) {
    const r = minFreeGiB({ E2E_MIN_FREE_GIB: bad });
    assert.equal(r.gib, 150);
    assert.match(r.warning, /not a number/);
  }
});

test('the exit code is its own: not usage (2), not a slot wait (3), not the slot CLI (4)', () => {
  assert.equal(EXIT_LOW_DISK, 5);
});

test('free space is statfs on the nearest existing dir: bavail * bsize', () => {
  const calls = [];
  const exists = (p) => p === '/a' || p === '/';
  assert.equal(freeBytes('/a/b/c', { statfs: statfsOf(10, calls), exists }), 10 * GIB);
  assert.deepEqual(calls, ['/a']);
  assert.equal(existingAncestor('/x/y', () => false), '/');
});

test('the guard refuses below the threshold, with the env var and exit code in its message', () => {
  const r = checkFreeSpace('/wt', { env: {}, statfs: statfsOf(117.3), exists: yes });
  assert.equal(r.ok, false);
  assert.equal(r.freeGiB, 117.3);
  assert.equal(r.minGiB, 150);
  assert.match(r.message, /refusing to start: 117\.3 GiB free .* below 150 GiB/);
  assert.match(r.message, /E2E_MIN_FREE_GIB=<GiB>/);
  assert.match(r.message, /Exit 5/);
});

test('the guard lets a run start at or above the threshold, and when turned down or off', () => {
  assert.equal(checkFreeSpace('/wt', { env: {}, statfs: statfsOf(150), exists: yes }).ok, true);
  assert.equal(checkFreeSpace('/wt', { env: {}, statfs: statfsOf(400), exists: yes }).ok, true);
  assert.equal(checkFreeSpace('/wt', { env: { E2E_MIN_FREE_GIB: '100' }, statfs: statfsOf(117), exists: yes }).ok, true);
  let read = false;
  const off = checkFreeSpace('/wt', { env: { E2E_MIN_FREE_GIB: '0' }, statfs: () => { read = true; return {}; }, exists: yes });
  assert.equal(off.ok, true);
  assert.equal(read, false);
  assert.match(off.message, /off/);
});

test('a statfs that fails or gives no number fails open, with a warning', () => {
  const thrown = checkFreeSpace('/wt', { env: {}, statfs: () => { throw new Error('EIO'); }, exists: yes });
  assert.equal(thrown.ok, true);
  assert.match(thrown.warnings.at(-1), /could not read free space .*EIO/);
  const junk = checkFreeSpace('/wt', { env: {}, statfs: () => ({}), exists: yes });
  assert.equal(junk.ok, true);
  assert.equal(junk.freeGiB, null);
});

// ------------------------------------------------------------------ keep or delete

test('a run is kept on any failure, deleted when it passed', () => {
  assert.equal(keepReason({ failed: 0, checks: 40 }), null);
  assert.equal(keepReason({ failed: 2, checks: 40 }), '2 check(s) failed');
  assert.equal(keepReason({ failed: 0, checks: 0 }), 'no checks ran');
  assert.equal(keepReason({ failed: 0, checks: 0, slotWait: true }), null);
  assert.equal(keepReason({ failed: 0, checks: 40, shuttingDown: true }), 'stopped by a signal');
});

test('isInside: strictly inside, never the root itself or a way out', () => {
  assert.equal(isInside('/w/mod/run/saves/Steward E2E', '/w/mod/run/saves'), true);
  assert.equal(isInside('/w/mod/run/saves', '/w/mod/run/saves'), false);
  assert.equal(isInside('/w/mod/run/saves/../config', '/w/mod/run/saves'), false);
  assert.equal(isInside('/elsewhere', '/w/mod/run/saves'), false);
});

test('the delete note quotes paths (the world name has a space)', () => {
  assert.equal(deleteNote([]), null);
  assert.equal(deleteNote(['/w/saves/Steward E2E', "/t/it's"]), `delete when done looking: rm -rf '/w/saves/Steward E2E' '/t/it'\\''s'`);
});

/** A fake filesystem: paths that exist (dirs, or symlinks), and the calls made. */
function fakeFs(dirs = [], links = []) {
  const calls = [];
  const lstat = (p) => {
    if (dirs.includes(p)) return { isSymbolicLink: () => false };
    if (links.includes(p)) return { isSymbolicLink: () => true };
    throw Object.assign(new Error('ENOENT'), { code: 'ENOENT' });
  };
  return { calls, lstat, rm: (p) => calls.push(['rm', p]), unlink: (p) => calls.push(['unlink', p]) };
}

const WORLD = { path: '/w/mod/run/saves/Steward E2E', root: '/w/mod/run/saves', what: 'the e2e world' };
const TMP = { path: '/ram/steward/steward-e2e-v2-abc', root: '/ram/steward', what: 'a temp blueprint', always: true };

test('a passing run deletes its world and its temp inputs', () => {
  const f = fakeFs([WORLD.path, TMP.path]);
  const r = cleanupRun([WORLD, TMP], { ...f });
  assert.deepEqual(r.deleted, [WORLD.path, TMP.path]);
  assert.deepEqual(r.kept, []);
  assert.equal(r.note, null);
  assert.deepEqual(f.calls, [['rm', WORLD.path], ['rm', TMP.path]]);
});

test('a failed run keeps its world (named, with the note) and still removes temp inputs', () => {
  const f = fakeFs([WORLD.path, TMP.path]);
  const r = cleanupRun([WORLD, TMP], { ...f, keep: '1 check(s) failed' });
  assert.deepEqual(r.deleted, [TMP.path]);
  assert.deepEqual(r.kept, [{ path: WORLD.path, what: 'the e2e world', why: '1 check(s) failed' }]);
  assert.match(r.note, /rm -rf '\/w\/mod\/run\/saves\/Steward E2E'/);
  assert.deepEqual(f.calls, [['rm', TMP.path]]);
});

test('a client JVM still on the game dir holds the world even on a pass', () => {
  const f = fakeFs([WORLD.path]);
  const r = cleanupRun([WORLD], { ...f, hold: 'a client JVM (123) still runs' });
  assert.deepEqual(r.deleted, []);
  assert.equal(r.kept[0].why, 'a client JVM (123) still runs');
  assert.deepEqual(f.calls, []);
});

test('never outside its root, never through a symlink, nothing for what is gone', () => {
  const outside = { path: '/w/mod/run/config', root: '/w/mod/run/saves', what: 'x' };
  const rootItself = { path: '/w/mod/run/saves', root: '/w/mod/run/saves', what: 'x' };
  const link = { path: '/w/mod/run/saves/Linked', root: '/w/mod/run/saves', what: 'x' };
  const gone = { path: '/w/mod/run/saves/Gone', root: '/w/mod/run/saves', what: 'x' };
  const f = fakeFs([outside.path, rootItself.path], [link.path]);
  const r = cleanupRun([outside, rootItself, link, gone], { ...f });
  assert.deepEqual(f.calls, [['unlink', link.path]]);
  assert.deepEqual(r.deleted, [link.path]);
  assert.equal(r.failed.length, 2);
  assert.match(r.failed[0].why, /outside/);
});

test('a delete that throws is reported, not thrown', () => {
  const f = fakeFs([WORLD.path]);
  const r = cleanupRun([WORLD], { ...f, rm: () => { throw new Error('EBUSY'); } });
  assert.deepEqual(r.failed, [{ path: WORLD.path, why: 'EBUSY' }]);
});

test("a --clients lead merges its clients' records, each kept dir naming its client", () => {
  const r = mergeCleanups([
    { client: 'c1', cleanup: { deleted: ['/w/mod/run/saves/Steward E2E'], kept: [], failed: [] } },
    { client: 'c2', cleanup: { deleted: [], kept: [{ path: '/w/mod/run-c2/saves/Steward E2E', what: 'the e2e world', why: '1 check(s) failed' }], failed: [] } },
    { client: 'c3', cleanup: null },
  ]);
  assert.deepEqual(r.deleted, ['/w/mod/run/saves/Steward E2E']);
  assert.equal(r.kept[0].client, 'c2');
  assert.match(r.note, /run-c2\/saves\/Steward E2E/);
});

// ------------------------------------------------------------------ a --clients lead's run-c<k> dirs

const pass = { failed: 0, cleanup: { deleted: [], kept: [], failed: [] } };
// (E2E-DISK-2) lane dirs carry their lead's tag
const LT = 'lt1';
const C2 = `/w/mod/run-c2-${LT}`, C3 = `/w/mod/run-c3-${LT}`;

test('a run-c<k> the lead made goes when every lane on it passed', () => {
  const owner = { run: 'r1', pid: 1 };
  const readFile = () => JSON.stringify(owner);
  const items = laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2], lanes: [{ k: 1, gameDir: '/w/mod/run', code: 0, summary: pass }, { k: 2, gameDir: C2, code: 0, summary: pass }], owner, readFile });
  assert.deepEqual(items.map(({ verify, ...i }) => i), [{ path: C2, root: '/w/mod', what: 'a client run dir this run made' }]);
  const f = fakeFs([C2]);
  assert.deepEqual(cleanupRun(items, { ...f }).deleted, [C2]);
});

test('a run-c<k> is kept when a lane on it failed, wrote no summary, kept its world, or none ran on it', () => {
  const failedLane = { k: 2, gameDir: C2, code: 1, summary: { failed: 2 } };
  const keptWorld = { k: 3, gameDir: C3, code: 0, summary: { failed: 0, cleanup: { kept: [{ path: `${C3}/saves/Steward E2E` }] } } };
  const items = laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2, C3, `/w/mod/run-c4-${LT}`], lanes: [failedLane, keptWorld, { waited: {} }] });
  assert.deepEqual(items.map((i) => i.hold), ['client c2 exited 1', 'client c3 kept its world', 'no client ran on it']);
  const f = fakeFs([C2, C3]);
  const r = cleanupRun(items, { ...f });
  assert.deepEqual(f.calls, []);
  assert.deepEqual(r.kept.map((k) => k.path), [C2, C3]);
  assert.equal(laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2], lanes: [{ k: 2, gameDir: C2, code: 0, summary: null }] })[0].hold, 'client c2 wrote no summary');
});

test('never mod/run, never a pre-existing lane dir (not in created), never a dir not named run-c<k>-<tag> right under mod, never an old plain run-c<k>', () => {
  const lanes = [{ k: 1, gameDir: '/w/mod/run', code: 0, summary: pass }];
  assert.deepEqual(laneRunDirs({ modDir: '/w/mod', tag: LT, created: ['/w/mod/run', `${C2}/x`, `/w/other/run-c2-${LT}`, '/w/mod/src', '/w/mod/run-c2'], lanes }), []);
  assert.deepEqual(laneRunDirs({ modDir: '/w/mod', tag: LT, created: [], lanes: [{ k: 2, gameDir: C2, code: 0, summary: pass }] }), []);
});

test('addCleanup joins a lead\'s own record to its clients\' and redoes the note', () => {
  const into = { deleted: ['a'], kept: [], failed: [], note: null };
  addCleanup(into, { deleted: [], kept: [{ path: C2, why: 'x' }], failed: [] });
  assert.deepEqual(into.deleted, ['a']);
  assert.match(into.note, /rm -rf '\/w\/mod\/run-c2-lt1'/);
});

// ------------------------------------------------------------------ batch 091 review: ownership claimed at use, --keep-world kept

/**
 * A fake filesystem with dirs and files (path -> text): mkdir without -p (EEXIST when there), writeFile honouring flag 'wx', readFile, lstat, rm.
 * Nothing real is touched.
 */
function ownFs(dirs = [], files = {}) {
  const d = new Set(dirs), f = new Map(Object.entries(files)), calls = [];
  const err = (code) => Object.assign(new Error(code), { code });
  return {
    calls, dirs: d, files: f,
    mkdir: (p, opts) => { calls.push(['mkdir', p, opts ?? null]); if (opts?.recursive) { d.add(p); return; } if (d.has(p)) throw err('EEXIST'); d.add(p); },
    writeFile: (p, text, opts) => { calls.push(['write', p, opts?.flag ?? null]); if (opts?.flag === 'wx' && f.has(p)) throw err('EEXIST'); f.set(p, String(text)); },
    readFile: (p) => { if (!f.has(p)) throw err('ENOENT'); return f.get(p); },
    lstat: (p) => { if (d.has(p)) return { isSymbolicLink: () => false }; throw err('ENOENT'); },
    rm: (p) => { calls.push(['rm', p]); d.delete(p); },
    unlink: (p) => calls.push(['unlink', p]),
  };
}

const ME = { run: 'lead-a', pid: 101 }, OTHER = { run: 'lead-b', pid: 202 };
const passLane = (gameDir) => ({ k: 2, gameDir, code: 0, summary: pass });

test('a lane dir is claimed at use: mkdir without -p, then the owner marker (wx); a dir already there is never claimed', () => {
  const f = ownFs(['/w/mod']);
  assert.deepEqual(claimRunDir(C2, ME, f), { claimed: true });
  assert.deepEqual(f.calls[0], ['mkdir', C2, null], 'never recursive');
  assert.deepEqual(f.calls[1], ['write', `${C2}/${OWNER_FILE}`, 'wx']);
  assert.equal(ownsRunDir(C2, ME, f), null);
  const again = claimRunDir(C2, OTHER, f);
  assert.equal(again.claimed, false);
  assert.match(again.why, /existed/);
  assert.equal(ownsRunDir(C2, ME, f), null, 'the other run never overwrote the marker');
  assert.match(ownsRunDir(C2, OTHER, f), /not this run's/);
});

test("(batch 091 P1) another run makes, uses and keeps run-c2 during this lead's slot wait: this lead's passing lane on it deletes nothing", () => {
  // at plan time run-c2 isn't there (the old check called it this lead's); during the wait the other run claims it, fails and keeps its world
  const f = ownFs(['/w/mod']);
  assert.equal(f.dirs.has(C2), false);
  assert.equal(claimRunDir(C2, OTHER, f).claimed, true);
  f.dirs.add(`${C2}/saves/Steward E2E`);
  // this lead's lane starts on it now: no claim, so nothing of it is this lead's to delete
  const mine = claimRunDir(C2, ME, f);
  assert.equal(mine.claimed, false);
  const created = mine.claimed ? [C2] : [];
  const r = cleanupRun(laneRunDirs({ modDir: '/w/mod', tag: LT, created, lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(r.deleted, []);
  // and were it named anyway, the marker read just before the delete refuses it
  const named = cleanupRun(laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(named.deleted, []);
  assert.match(named.failed[0].why, /not this run's/);
  assert.deepEqual(f.calls.filter((c) => c[0] === 'rm'), [], 'no recursive delete ran');
});

test('the marker is read again just before deleting: a dir whose marker changed or went is not touched; one still this run\'s goes', () => {
  const f = ownFs(['/w/mod']);
  assert.equal(claimRunDir(C2, ME, f).claimed, true);
  f.files.set(`${C2}/${OWNER_FILE}`, JSON.stringify(OTHER));
  let r = cleanupRun(laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(r.deleted, []);
  f.files.delete(`${C2}/${OWNER_FILE}`);
  r = cleanupRun(laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(r.deleted, []);
  assert.match(r.failed[0].why, /no owner marker/);
  // without an owner to check against, never deleted
  r = cleanupRun(laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2], lanes: [passLane(C2)] }), { ...f });
  assert.deepEqual(r.deleted, []);
  f.files.set(`${C2}/${OWNER_FILE}`, JSON.stringify(ME));
  r = cleanupRun(laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(r.deleted, [C2]);
});

test('(batch 091 P2) --keep-world: the child reports its world kept (keep-world), and the lead keeps the lane it made', () => {
  const world = `${C2}/saves/Steward E2E`;
  const f = ownFs(['/w/mod', C2, world]);
  const items = runItems({ world, saves: `${C2}/saves`, keepWorld: true, tempInputs: [TMP.path], tmp: TMP.root });
  const child = cleanupRun(items, { ...f });
  assert.deepEqual(child.kept, [{ path: world, what: 'the e2e world', why: 'keep-world' }], 'kept, said, even on a pass');
  assert.deepEqual(f.calls.filter((c) => c[0] === 'rm'), []);
  f.files.set(`${C2}/${OWNER_FILE}`, JSON.stringify(ME));
  const lane = { k: 2, gameDir: C2, code: 0, summary: { failed: 0, cleanup: child } };
  const lead = cleanupRun(laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2], lanes: [lane], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(lead.deleted, []);
  assert.equal(lead.kept[0].why, 'client c2 kept its world');
  // the lead's own --keep-world holds the lane even when a child said nothing kept (an older child)
  const quiet = laneRunDirs({ modDir: '/w/mod', tag: LT, created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile, keepWorld: true });
  assert.match(quiet[0].hold, /--keep-world/);
  assert.deepEqual(cleanupRun(quiet, { ...f }).deleted, []);
  // without --keep-world the world is this run's to delete on a pass (E2E-DISK-2: while its marker names this run)
  f.files.set(`${world}/${OWNER_FILE}`, JSON.stringify(ME));
  const plain = cleanupRun(runItems({ world, saves: `${C2}/saves`, keepWorld: false, owner: ME, readFile: f.readFile, tempInputs: [], tmp: TMP.root }), { ...f });
  assert.deepEqual(plain.deleted, [world]);
});

// Noah 11-21 via parent ("Merge with risky deletes off"): batch 091's final GPT P1s are switched off, not fixed, until E2E-DISK-2.
test('(B091 switch-off) a driver that never got its slot deletes no world, only its own temp blueprints', () => {
  const world = `${C2}/saves/Steward E2E`;
  const f = ownFs(['/w/mod', C2, world]);
  const items = runItems({ world, saves: `${C2}/saves`, keepWorld: false, slotWait: true, tempInputs: [TMP.path], tmp: TMP.root });
  assert.equal(items.some((i) => i.path === world), false, 'the world is not an item after a slot wait');
  const r = cleanupRun(items, { keep: keepReason({ failed: 0, checks: 0, slotWait: true }), ...f });
  assert.equal(r.deleted.includes(world), false);
  // with its slot, a passing run still deletes its own world (E2E-DISK-2: marked by this run)
  f.files.set(`${world}/${OWNER_FILE}`, JSON.stringify(ME));
  assert.deepEqual(cleanupRun(runItems({ world, saves: `${C2}/saves`, slotWait: false, owner: ME, readFile: f.readFile, tempInputs: [], tmp: TMP.root }), { ...f }).deleted, [world]);
});
// (E2E-DISK-2) the switch-off's source check of DELETE_LANE_DIRS = false is replaced by the ownership-path check below.

// ------------------------------------------------------------------ slice E2E-DISK-2: every delete provably this run's own (per-run names, markers)

/** A fake lstat over ownFs dirs, with birth times (ms) and symlinks. */
const statOf = (f, born = {}, links = []) => (p) => {
  if (links.includes(p)) return { isSymbolicLink: () => true, isDirectory: () => false, birthtimeMs: born[p] ?? 0 };
  if (!f.dirs.has(p)) throw Object.assign(new Error('ENOENT'), { code: 'ENOENT' });
  return { isSymbolicLink: () => false, isDirectory: () => true, birthtimeMs: born[p] ?? 0 };
};
const disk2 = await import('../tools/lib/disk.mjs');
const src2 = () => fs2.readFileSync(new URL('../tools/e2e.mjs', import.meta.url), 'utf8');
const fs2 = await import('node:fs');
const TAG_A = 'mabc123_1x0001', TAG_B = 'mabc124_2y0002';
const A = { run: TAG_A, pid: 101 }, B = { run: TAG_B, pid: 202 };

test('(E2E-DISK-2) a run tag is short and name-safe, and two runs never share one', () => {
  const t1 = disk2.runTag({ now: 1, pid: 5, rand: () => 0 }), t2 = disk2.runTag({ now: 1, pid: 6, rand: () => 0 });
  assert.match(t1, /^[a-z0-9_]+$/, 'name-safe, and a valid blueprint id part (the stub entries carry it)');
  assert.match(`e2e_stub_${disk2.runTag()}_ui`, /^[a-z0-9_]+$/);
  assert.notEqual(t1, t2, 'the pid is in it');
  assert.notEqual(disk2.runTag({ now: 1, pid: 5, rand: () => 0.5 }), t1, 'and a random part');
  assert.ok(`Steward E2E ${disk2.runTag()}`.length <= 64, "Architect's AutoWorldSpec takes at most 64 characters");
});

test('(E2E-DISK-2 b) each driver gets its own world, named for its run; --keep-world and an explicit name are fixed and protected', () => {
  const a = disk2.e2eWorld({ saves: '/w/mod/run/saves', tag: TAG_A, env: {} });
  const b = disk2.e2eWorld({ saves: '/w/mod/run/saves', tag: TAG_B, env: {} });
  assert.equal(a.name, `Steward E2E ${TAG_A}`);
  assert.equal(a.path, `/w/mod/run/saves/Steward E2E ${TAG_A}`);
  assert.equal(a.perRun, true);
  assert.equal(a.protect, null);
  assert.notEqual(a.path, b.path, 'two drivers on one game dir never share a world');
  const kw = disk2.e2eWorld({ saves: '/w/mod/run/saves', tag: TAG_A, keepWorld: true, env: {} });
  assert.deepEqual([kw.name, kw.perRun, kw.protect], ['Steward E2E', false, 'keep-world']);
  const named = disk2.e2eWorld({ saves: '/s', tag: TAG_A, env: { ARCHITECT_AUTOWORLD_NAME: ' Mine ' } });
  assert.deepEqual([named.name, named.perRun], ['Mine', false]);
  assert.match(named.protect, /ARCHITECT_AUTOWORLD_NAME/);
});

test('(E2E-DISK-2 b, d) the start never moves or deletes a world: a per-run world already there is refused; earlier worlds are only named', () => {
  const a = disk2.e2eWorld({ saves: '/w/saves', tag: TAG_A, env: {} });
  const there = new Set(['/w/saves', '/w/saves/Steward E2E', '/w/saves/Steward E2E (last)', `/w/saves/Steward E2E ${TAG_B}`]);
  const list = () => ['Steward E2E', 'Steward E2E (last)', `Steward E2E ${TAG_B}`, 'Architect Dev'];
  const r = disk2.worldAtStart(a, { exists: (p) => there.has(p), list });
  assert.equal(r.ok, true);
  assert.deepEqual(r.earlier, ['Steward E2E', 'Steward E2E (last)', `Steward E2E ${TAG_B}`], 'named, not touched');
  // B makes its world during A's slot wait: A's start and A's cleanup never name it
  there.add(a.path);
  const again = disk2.worldAtStart(a, { exists: (p) => there.has(p), list });
  assert.equal(again.ok, false);
  assert.match(again.why, /already there/);
  assert.equal(disk2.worldAtStart(disk2.e2eWorld({ saves: '/w/saves', tag: TAG_A, keepWorld: true, env: {} }), { exists: (p) => there.has(p), list }).ok, true);
});

test('(E2E-DISK-2 c) the world is marked after its client made it, and deleted only while its marker names this run', () => {
  const a = disk2.e2eWorld({ saves: '/w/saves', tag: TAG_A, env: {} });
  const f = ownFs(['/w/saves']);
  const facts = { launchedAt: 1, absentAtLaunch: true, clientLog: `AutoWorld: creating world '${a.name}' (FLAT)` };
  assert.match(disk2.markWorld(a, A, { ...f, lstat: statOf(f, { [a.path]: 5 }), ...facts }), /not there/, 'no world, no marker: said');
  f.dirs.add(a.path);
  assert.equal(disk2.markWorld(a, A, { ...f, lstat: statOf(f, { [a.path]: 5 }), ...facts }), null);
  assert.deepEqual(f.calls.at(-1), ['write', `${a.path}/${OWNER_FILE}`, 'wx']);
  const items = () => runItems({ world: a.path, saves: '/w/saves', owner: A, readFile: f.readFile, tempInputs: [], tmp: TMP.root });
  // another run's marker (or none): not touched, after the slot went back or not
  f.files.set(`${a.path}/${OWNER_FILE}`, JSON.stringify(B));
  let r = cleanupRun(items(), { ...f });
  assert.deepEqual(r.deleted, []);
  assert.match(r.failed[0].why, /not this run's/);
  f.files.delete(`${a.path}/${OWNER_FILE}`);
  assert.deepEqual(cleanupRun(items(), { ...f }).deleted, []);
  // no owner given: never deleted
  assert.deepEqual(cleanupRun(runItems({ world: a.path, saves: '/w/saves', tempInputs: [], tmp: TMP.root }), { ...f }).deleted, []);
  f.files.set(`${a.path}/${OWNER_FILE}`, JSON.stringify(A));
  r = cleanupRun(items(), { ...f });
  assert.deepEqual(r.deleted, [a.path]);
  // a fixed-name world (keep-world, or named) is protected whatever its marker says
  const p = cleanupRun(runItems({ world: '/w/saves/Mine', saves: '/w/saves', protect: 'named world', owner: A, readFile: f.readFile, tempInputs: [], tmp: TMP.root }), { ...ownFs(['/w/saves/Mine']) });
  assert.equal(p.kept[0].why, 'named world');
});

test('(E2E-DISK-2 d) the fixed e2e_stub entry is never removed: written only where its bytes differ', () => {
  const files = { 'e2e_stub.nbt': Buffer.from([1, 2]), 'e2e_stub.blueprint.json': Buffer.from('{}') };
  const same = (p) => (p.endsWith('.nbt') ? Buffer.from([1, 2]) : Buffer.from('{}'));
  assert.deepEqual(disk2.filesToWrite('/g/library/e2e_stub', files, same), []);
  assert.deepEqual(disk2.filesToWrite('/g/library/e2e_stub', files, (p) => (p.endsWith('.nbt') ? Buffer.from([9]) : Buffer.from('{}'))), ['e2e_stub.nbt']);
  assert.deepEqual(disk2.filesToWrite('/g/library/e2e_stub', files, () => { throw new Error('ENOENT'); }), Object.keys(files));
});

test('(E2E-DISK-2 a) lane dirs are per run: run-c<k>-<tag>, made exclusively; another lead\'s, or an old plain run-c<k>, is never an item', () => {
  assert.equal(disk2.laneDirName(2, TAG_A), `run-c2-${TAG_A}`);
  const CA = `/w/mod/run-c2-${TAG_A}`, CB = `/w/mod/run-c2-${TAG_B}`;
  const OLD = '/w/mod/run-c2';
  const f = ownFs(['/w/mod', OLD]);
  f.files.set(`${OLD}/${OWNER_FILE}`, JSON.stringify(A));
  assert.equal(claimRunDir(CA, A, f).claimed, true);
  assert.equal(claimRunDir(CB, B, f).claimed, true, 'the other lead has its own dir: no reuse');
  assert.equal(claimRunDir(CA, B, f).claimed, false, 'and can never claim this one');
  const lanes = [passLane(CA), { ...passLane(CB), k: 3 }, { ...passLane(OLD), k: 4 }];
  // even were the other lead's dir and an old plain run-c2 (marker or not) named, only this run's tag is an item
  const items = laneRunDirs({ modDir: '/w/mod', created: [CA, CB, OLD], lanes, owner: A, tag: TAG_A, readFile: f.readFile });
  assert.deepEqual(items.map((i) => i.path), [CA]);
  assert.deepEqual(laneRunDirs({ modDir: '/w/mod', created: [CA], lanes, owner: A, readFile: f.readFile }), [], 'no tag, no items');
  const r = cleanupRun(items, { ...f });
  assert.deepEqual(r.deleted, [CA]);
  assert.equal(f.dirs.has(CB) && f.dirs.has(OLD), true);
});

test('(E2E-DISK-2) e2e.mjs uses the ownership path: per-run world in the spawn env, marked, verified; lane dirs per run and deleted', () => {
  const src = src2();
  assert.equal(disk2.DELETE_LANE_DIRS, true);
  assert.match(src, /env: \{ \.\.\.process\.env, ARCHITECT_AUTOWORLD_NAME: E2E_WORLD\.name,/, 'the spawn env carries the per-run name');
  assert.doesNotMatch(src, /process\.env\.ARCHITECT_AUTOWORLD_NAME\s*=/, 'never set in process.env (a lead\'s children would inherit it)');
  assert.match(src, /markWorld\(E2E_WORLD, runOwner, /, 'the world is marked');
  assert.match(src, /runItems\(\{ world: WORLD, saves: path\.join\(GAME, 'saves'\), protect: E2E_WORLD\.protect, owner: runOwner, slotWait: !!slotWait,/, 'the world item is verified against this run');
  assert.match(src, /laneDirName\(k, RUN_TAG\)/, 'lane dirs are named for this run');
  assert.match(src, /laneRunDirs\(\{ modDir: path\.join\(ROOT, 'mod'\), created: leadMadeDirs, lanes: laneRuns, owner: runOwner, tag: RUN_TAG, keepWorld \}\)/);
  const block = (name) => { const i = src.indexOf(`function ${name}(`); return src.slice(i, src.indexOf('\n}\n', i)); };
  assert.doesNotMatch(block('freshWorld'), /rmSync|renameSync|unlinkSync/, 'freshWorld moves and removes nothing');
  // ensureStubEntry removes only its own mkdtemp dir; the fixed entry is written file by file, never removed
  const stub = block('ensureStubEntry');
  assert.match(stub, /const tmp = fs\.mkdtempSync\(/);
  assert.deepEqual(stub.match(/rmSync\([^,)]*/g), ['rmSync(tmp'], 'no other rm');
  assert.doesNotMatch(stub, /recursive: true, force: true \}\);\n\s*writeBlueprint/, 'no rm before a write');
  assert.match(src, /const STUB = `e2e_stub_\$\{RUN_TAG\}`/);
  for (const [call, n] of [['runItems(', 1], ['laneRunDirs(', 1], ['claimRunDir(', 1], ['markWorld(', 1]]) assert.equal(src.split(call).length - 1, n, `one ${call} caller`);
  for (const [call, n] of [['runItems(', 1], ['laneRunDirs(', 1], ['claimRunDir(', 1], ['markWorld(', 1]]) assert.equal(src.split(call).length - 1, n, `one ${call} caller`);
});

test('(E2E-DISK-2) a lane whose client could not delete something (cleanup.failed) holds its lane dir: failure keeps everything', () => {
  const CA = `/w/mod/run-c2-${TAG_A}`;
  const f = ownFs(['/w/mod', CA]);
  f.files.set(`${CA}/${OWNER_FILE}`, JSON.stringify(A));
  const lane = { k: 2, gameDir: CA, code: 0, summary: { failed: 0, cleanup: { deleted: [], kept: [], failed: [{ path: `${CA}/saves/Steward E2E ${TAG_B}`, why: "not this run's: not touched" }] } } };
  const items = laneRunDirs({ modDir: '/w/mod', created: [CA], lanes: [lane], owner: A, tag: TAG_A, readFile: f.readFile });
  assert.match(items[0].hold, /client c2 could not delete 1 path/);
  const r = cleanupRun(items, { ...f });
  assert.deepEqual(r.deleted, []);
  assert.deepEqual(f.calls.filter((c) => c[0] === 'rm'), []);
});

// ------------------------------------------------------------------ E2E-DISK-2 fix round (GPT review NOT SAFE: P1 #1, P1 #2, P2 #4)

const created = (name) => `[12:00:00] [Render thread/INFO] (architect) AutoWorld: creating world '${name}' (FLAT, seed 1, CREATIVE, cheats on)`;

test('(fix P1 #1) a driver runs on a tagged lane dir only when its marker names the lead that passed it; by hand it refuses', () => {
  const CA = `/w/mod/run-c2-${TAG_A}`;
  const f = ownFs(['/w/mod']);
  assert.equal(claimRunDir(CA, A, f).claimed, true);
  // the lead's child: E2E_LANE_OWNER is the lead's owner
  assert.equal(disk2.laneUse({ gameDir: CA, modDir: '/w/mod', laneOwner: JSON.stringify(A), readFile: f.readFile }), null);
  // driver B by hand on A's lane (no lane owner, or its own): refused
  assert.match(disk2.laneUse({ gameDir: CA, modDir: '/w/mod', laneOwner: undefined, readFile: f.readFile }), /refus/);
  assert.match(disk2.laneUse({ gameDir: CA, modDir: '/w/mod', laneOwner: JSON.stringify(B), readFile: f.readFile }), /refus/);
  assert.match(disk2.laneUse({ gameDir: CA, modDir: '/w/mod', laneOwner: 'junk', readFile: f.readFile }), /refus/);
  // a tagged lane dir with no marker: refused too
  f.files.delete(`${CA}/${OWNER_FILE}`);
  assert.match(disk2.laneUse({ gameDir: CA, modDir: '/w/mod', laneOwner: JSON.stringify(A), readFile: f.readFile }), /refus/);
  // mod/run and an old plain run-c2 aren't lane dirs of any run: no check
  assert.equal(disk2.laneUse({ gameDir: '/w/mod/run', modDir: '/w/mod', readFile: f.readFile }), null);
  assert.equal(disk2.laneUse({ gameDir: '/w/mod/run-c2', modDir: '/w/mod', readFile: f.readFile }), null);
});

test('(fix P1 #1, lifecycle) B refused on A\'s lane makes nothing there, so A\'s delete of its lane takes only A\'s', () => {
  const CA = `/w/mod/run-c2-${TAG_A}`;
  const f = ownFs(['/w/mod']);
  claimRunDir(CA, A, f);
  const items = laneRunDirs({ modDir: '/w/mod', created: [CA], lanes: [passLane(CA)], owner: A, tag: TAG_A, readFile: f.readFile });
  // B, by hand, after A's c2 passed: refused before it makes a world (the marker untouched)
  assert.notEqual(disk2.laneUse({ gameDir: CA, modDir: '/w/mod', readFile: f.readFile }), null);
  assert.equal([...f.dirs].some((d) => d.startsWith(`${CA}/saves`)), false);
  assert.deepEqual(cleanupRun(items, { ...f }).deleted, [CA]);
});

test('(fix P1 #2) a world is marked only when this run\'s client created it: absent at launch, born after, a real dir, our client said "creating"', () => {
  const a = disk2.e2eWorld({ saves: '/w/saves', tag: TAG_A, env: {} });
  const T = 1000;
  const f = ownFs(['/w/saves', a.path]);
  const mark = (o) => disk2.markWorld(a, A, { writeFile: f.writeFile, lstat: statOf(f, { [a.path]: 2000 }), launchedAt: T, absentAtLaunch: true, clientLog: created(a.name), ...o });
  assert.match(mark({ absentAtLaunch: false }), /there before/);
  assert.match(mark({ lstat: statOf(f, { [a.path]: 500 }) }), /before this run launched/);
  assert.match(mark({ clientLog: `AutoWorld: loading existing world '${a.name}'` }), /didn't create/);
  assert.match(mark({ clientLog: created(`${a.name}x`) }), /didn't create/);
  assert.match(mark({ lstat: statOf(f, {}, [a.path]) }), /symlink/);
  assert.match(mark({ lstat: statOf(ownFs(['/w/saves'])) }), /not there/);
  assert.equal(f.files.has(`${a.path}/${OWNER_FILE}`), false, 'no marker written by any refusal');
  assert.equal(mark(), null);
  assert.equal(f.files.get(`${a.path}/${OWNER_FILE}`), JSON.stringify(A));
});

test('(fix P1 #2, lifecycle) B makes A\'s world name during A\'s wait: A never marks it, so never deletes it', () => {
  const a = disk2.e2eWorld({ saves: '/w/saves', tag: TAG_A, env: {} });
  // B by an explicit name equal to A's: refused (the tag pattern can't be chosen through ARCHITECT_AUTOWORLD_NAME)
  const b = disk2.e2eWorld({ saves: '/w/saves', tag: TAG_B, env: { ARCHITECT_AUTOWORLD_NAME: a.name } });
  assert.match(b.refuse, /tag/);
  assert.match(disk2.e2eWorld({ saves: '/w/saves', tag: TAG_B, keepWorld: true, env: { ARCHITECT_AUTOWORLD_NAME: a.name } }).refuse, /tag/);
  assert.equal(disk2.e2eWorld({ saves: '/w/saves', tag: TAG_B, env: { ARCHITECT_AUTOWORLD_NAME: 'Steward E2E' } }).refuse, undefined);
  // were it made anyway (born before A's launch, or A's client only loaded it), A writes no marker and its cleanup deletes nothing
  const f = ownFs(['/w/saves', a.path]);
  const why = disk2.markWorld(a, A, { writeFile: f.writeFile, lstat: statOf(f, { [a.path]: 900 }), launchedAt: 1000, absentAtLaunch: true, clientLog: `AutoWorld: loading existing world '${a.name}'` });
  assert.notEqual(why, null);
  assert.deepEqual(cleanupRun(runItems({ world: a.path, saves: '/w/saves', owner: A, readFile: f.readFile, tempInputs: [], tmp: TMP.root }), { ...f }).deleted, []);
  // an explicitly named world is never marked
  const named = disk2.e2eWorld({ saves: '/w/saves', tag: TAG_A, env: { ARCHITECT_AUTOWORLD_NAME: 'Mine' } });
  const g = ownFs(['/w/saves', named.path]);
  assert.equal(disk2.markWorld(named, A, { writeFile: g.writeFile, lstat: statOf(g, { [named.path]: 2000 }), launchedAt: 1000, absentAtLaunch: true, clientLog: created('Mine') }), null);
  assert.equal(g.files.size, 0);
});

test('(fix P2 #4) ownership is read at the delete, not when the items are built: items first, then the marker changes or goes', () => {
  const a = disk2.e2eWorld({ saves: '/w/saves', tag: TAG_A, env: {} });
  const CA = `/w/mod/run-c2-${TAG_A}`;
  for (const after of [(f, p) => f.files.set(`${p}/${OWNER_FILE}`, JSON.stringify(B)), (f, p) => f.files.delete(`${p}/${OWNER_FILE}`)]) {
    const f = ownFs(['/w/saves', a.path, '/w/mod', CA]);
    f.files.set(`${a.path}/${OWNER_FILE}`, JSON.stringify(A));
    f.files.set(`${CA}/${OWNER_FILE}`, JSON.stringify(A));
    const world = runItems({ world: a.path, saves: '/w/saves', owner: A, readFile: f.readFile, tempInputs: [], tmp: TMP.root });
    const lane = laneRunDirs({ modDir: '/w/mod', created: [CA], lanes: [passLane(CA)], owner: A, tag: TAG_A, readFile: f.readFile });
    after(f, a.path);
    after(f, CA);
    const r = cleanupRun([...world, ...lane], { ...f });
    assert.deepEqual(r.deleted, [], 'a marker changed after the items were built stops the delete');
    assert.deepEqual(f.calls.filter((c) => c[0] === 'rm'), []);
  }
});

test('(fix) e2e.mjs: a driver checks its lane before anything, the lead passes its owner, the marker needs the launch facts, module labels carry the tag', () => {
  const src = src2();
  assert.match(src, /E2E_LANE_OWNER: JSON\.stringify\(runOwner\)/, 'the lead passes its owner to each child');
  assert.match(src, /laneUse\(\{ gameDir: GAME, modDir: path\.join\(ROOT, 'mod'\), laneOwner: process\.env\.E2E_LANE_OWNER \}\)/);
  const i = src.indexOf('laneUse({ gameDir: GAME'), j = src.indexOf('await preflight();');
  assert.ok(i > 0 && i < j, 'checked before preflight, freshWorld and the stub entries');
  assert.match(src, /markWorld\(E2E_WORLD, runOwner, \{ launchedAt: firstLaunch\.at, absentAtLaunch: firstLaunch\.absent, clientLog: clientLogSince\(firstLaunch\.logAt\) \}\)/);
  assert.match(src, /const lab = `e2e_\$\{RUN_TAG\}_\$\{\+\+nDevModule\}`;/);
});

// ------------------------------------------------------------------ E2E-DISK-3: lanes by real path and any case, per-run names in any case (real temp dirs)

const os3 = await import('node:os');
const path3 = await import('node:path');
/** A real worktree-like root: <root>/mod with A's lane run-c2-<TAG_A> claimed (real mkdir and marker). */
function realLane(tag = TAG_A, owner = A, k = 2) {
  const root = fs2.mkdtempSync(path3.join(os3.tmpdir(), 'disk3-'));
  const mod = path3.join(root, 'mod');
  fs2.mkdirSync(mod);
  const lane = path3.join(mod, `run-c${k}-${tag}`);
  assert.equal(claimRunDir(lane, owner).claimed, true);
  return { root, mod, lane };
}
/** Whether the temp volume ignores case (APFS default): a case variant then names the very same dir. */
const caseBlind = (p) => { try { return fs2.statSync(p.toUpperCase()).ino === fs2.statSync(p).ino; } catch { return false; } };

test('(E2E-DISK-3 P1) the lead\'s own child still runs on its lane; mod/run and an old plain run-c<k> still need no check', () => {
  const { mod, lane } = realLane();
  assert.equal(disk2.laneUse({ gameDir: lane, modDir: mod, laneOwner: JSON.stringify(A) }), null);
  assert.equal(disk2.laneUse({ gameDir: path3.join(mod, 'run'), modDir: mod }), null);
  assert.equal(disk2.laneUse({ gameDir: path3.join(mod, 'run-c2'), modDir: mod }), null);
});

test('(E2E-DISK-3 P1) a game dir inside a lane is refused, even with the lane\'s own owner, whether it exists yet or not', () => {
  const { mod, lane } = realLane();
  fs2.mkdirSync(path3.join(lane, 'saves'));
  for (const g of [path3.join(lane, 'saves'), path3.join(lane, 'x', 'y'), `${lane}/./saves/..`.replace(/\/\.\.$/, '/sub')]) {
    for (const owner of [undefined, JSON.stringify(A), JSON.stringify(B)]) {
      assert.match(disk2.laneUse({ gameDir: g, modDir: mod, laneOwner: owner }) ?? 'passed', /refus/, `${g} with ${owner}`);
    }
  }
});

test('(E2E-DISK-3 P1) a symlink alias of a lane (or of a dir in one, or with a tail not made yet) is the lane: refused unless it is the lead\'s child', () => {
  const { root, mod, lane } = realLane();
  const alias = path3.join(root, 'alias');
  fs2.symlinkSync(lane, alias);
  assert.match(disk2.laneUse({ gameDir: alias, modDir: mod }) ?? 'passed', /refus/);
  assert.match(disk2.laneUse({ gameDir: alias, modDir: mod, laneOwner: JSON.stringify(B) }) ?? 'passed', /refus/);
  assert.equal(disk2.laneUse({ gameDir: alias, modDir: mod, laneOwner: JSON.stringify(A) }), null, 'the same lane, by its real path');
  assert.match(disk2.laneUse({ gameDir: path3.join(alias, 'not', 'made'), modDir: mod, laneOwner: JSON.stringify(A) }) ?? 'passed', /refus/);
  // a symlinked mod dir, and a link into another worktree's lane (not under this driver's mod at all)
  const modAlias = path3.join(root, 'modalias');
  fs2.symlinkSync(mod, modAlias);
  assert.match(disk2.laneUse({ gameDir: path3.join(modAlias, path3.basename(lane)), modDir: mod }) ?? 'passed', /refus/);
  const other = realLane(TAG_B, B, 3);
  const into = path3.join(root, 'into-other');
  fs2.symlinkSync(other.lane, into);
  assert.match(disk2.laneUse({ gameDir: into, modDir: mod }) ?? 'passed', /refus/);
  assert.equal(disk2.laneUse({ gameDir: into, modDir: mod, laneOwner: JSON.stringify(B) }), null);
});

test('(E2E-DISK-3 P1) a case variant of a lane is the lane, judged by name (on a case-blind disk it is the very same dir)', () => {
  const { mod, lane } = realLane();
  const up = path3.join(mod, path3.basename(lane).toUpperCase());
  const MOD = path3.join(path3.dirname(mod), 'MOD');
  for (const g of [up, path3.join(MOD, path3.basename(lane)), path3.join(up, 'saves')]) {
    assert.match(disk2.laneUse({ gameDir: g, modDir: mod }) ?? 'passed', /refus/, g);
    assert.match(disk2.laneUse({ gameDir: g, modDir: mod, laneOwner: JSON.stringify(B) }) ?? 'passed', /refus/, g);
  }
  if (caseBlind(lane)) assert.equal(disk2.laneUse({ gameDir: up, modDir: mod, laneOwner: JSON.stringify(A) }), null, 'the lead\'s child, by another case');
});

test('(E2E-DISK-3 P1) a per-run world name is refused in any case or spacing, with or without --keep-world', () => {
  const a = disk2.e2eWorld({ saves: '/w/saves', tag: TAG_A, env: {} });
  for (const name of [a.name.toUpperCase(), a.name.toLowerCase(), `steward e2e ${TAG_A.toUpperCase()}`, `Steward  E2E\t${TAG_A}`]) {
    for (const keepWorld of [false, true]) {
      assert.match(disk2.e2eWorld({ saves: '/w/saves', tag: TAG_B, keepWorld, env: { ARCHITECT_AUTOWORLD_NAME: name } }).refuse ?? 'passed', /tag/, `${name} ${keepWorld}`);
    }
  }
  // the fixed names stay allowed, in any case
  for (const name of ['Steward E2E', 'STEWARD E2E', 'Mine']) assert.equal(disk2.e2eWorld({ saves: '/w/saves', tag: TAG_B, keepWorld: true, env: { ARCHITECT_AUTOWORLD_NAME: name } }).refuse, undefined);
});

test('(E2E-DISK-3 P1, real dirs) on this disk an upper-cased name may open another run\'s world: such a name is refused before any launch', () => {
  const saves = fs2.mkdtempSync(path3.join(os3.tmpdir(), 'disk3-saves-'));
  const a = disk2.e2eWorld({ saves, tag: TAG_A, env: {} });
  fs2.mkdirSync(a.path);
  const kw = disk2.e2eWorld({ saves, tag: TAG_B, keepWorld: true, env: { ARCHITECT_AUTOWORLD_NAME: a.name.toUpperCase() } });
  if (caseBlind(a.path)) assert.equal(fs2.statSync(kw.path).ino, fs2.statSync(a.path).ino, 'the same dir here');
  assert.match(kw.refuse ?? 'passed', /tag/);
  // the earlier e2e worlds are named in any case
  const s = disk2.worldAtStart(disk2.e2eWorld({ saves, tag: TAG_B, env: {} }), { list: () => ['steward e2e old', 'Other'] });
  assert.deepEqual(s.earlier, ['steward e2e old']);
});

test('(E2E-DISK-3 P2) a module answer file is claimed exclusively before its request: a name already there is never read as this run\'s', () => {
  const game = fs2.mkdtempSync(path3.join(os3.tmpdir(), 'disk3-game-'));
  const f = path3.join(game, 'steward-modules', 'e2e_x_1.json');
  assert.equal(disk2.claimAnswerFile(f), null);
  assert.equal(fs2.readFileSync(f, 'utf8'), '', 'an empty placeholder: not JSON, so the reader keeps polling until the mod writes over it');
  assert.match(disk2.claimAnswerFile(f) ?? 'claimed', /already there/);
  // a symlink in its place is not followed
  const g = path3.join(game, 'steward-modules', 'e2e_x_2.json');
  fs2.symlinkSync(path3.join(game, 'elsewhere.json'), g);
  assert.match(disk2.claimAnswerFile(g) ?? 'claimed', /already there/);
  assert.equal(fs2.existsSync(path3.join(game, 'elsewhere.json')), false);
  const src = src2();
  assert.match(src, /const claim = claimAnswerFile\(f\);/, 'e2e.mjs claims the answer file before the request');
});
