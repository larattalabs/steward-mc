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
const C2 = '/w/mod/run-c2', C3 = '/w/mod/run-c3';

test('a run-c<k> the lead made goes when every lane on it passed', () => {
  const owner = { run: 'r1', pid: 1 };
  const readFile = () => JSON.stringify(owner);
  const items = laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [{ k: 1, gameDir: '/w/mod/run', code: 0, summary: pass }, { k: 2, gameDir: C2, code: 0, summary: pass }], owner, readFile });
  assert.deepEqual(items.map(({ verify, ...i }) => i), [{ path: C2, root: '/w/mod', what: 'a client run dir this run made' }]);
  const f = fakeFs([C2]);
  assert.deepEqual(cleanupRun(items, { ...f }).deleted, [C2]);
});

test('a run-c<k> is kept when a lane on it failed, wrote no summary, kept its world, or none ran on it', () => {
  const failedLane = { k: 2, gameDir: C2, code: 1, summary: { failed: 2 } };
  const keptWorld = { k: 3, gameDir: C3, code: 0, summary: { failed: 0, cleanup: { kept: [{ path: `${C3}/saves/Steward E2E` }] } } };
  const items = laneRunDirs({ modDir: '/w/mod', created: [C2, C3, '/w/mod/run-c4'], lanes: [failedLane, keptWorld, { waited: {} }] });
  assert.deepEqual(items.map((i) => i.hold), ['client c2 exited 1', 'client c3 kept its world', 'no client ran on it']);
  const f = fakeFs([C2, C3]);
  const r = cleanupRun(items, { ...f });
  assert.deepEqual(f.calls, []);
  assert.deepEqual(r.kept.map((k) => k.path), [C2, C3]);
  assert.equal(laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [{ k: 2, gameDir: C2, code: 0, summary: null }] })[0].hold, 'client c2 wrote no summary');
});

test('never mod/run, never a pre-existing run-c<k> (not in created), never a dir not named run-c<k> right under mod', () => {
  const lanes = [{ k: 1, gameDir: '/w/mod/run', code: 0, summary: pass }];
  assert.deepEqual(laneRunDirs({ modDir: '/w/mod', created: ['/w/mod/run', '/w/mod/run-c2/x', '/w/other/run-c2', '/w/mod/src'], lanes }), []);
  assert.deepEqual(laneRunDirs({ modDir: '/w/mod', created: [], lanes: [{ k: 2, gameDir: C2, code: 0, summary: pass }] }), []);
});

test('addCleanup joins a lead\'s own record to its clients\' and redoes the note', () => {
  const into = { deleted: ['a'], kept: [], failed: [], note: null };
  addCleanup(into, { deleted: [], kept: [{ path: C2, why: 'x' }], failed: [] });
  assert.deepEqual(into.deleted, ['a']);
  assert.match(into.note, /rm -rf '\/w\/mod\/run-c2'/);
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
  const r = cleanupRun(laneRunDirs({ modDir: '/w/mod', created, lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(r.deleted, []);
  // and were it named anyway, the marker read just before the delete refuses it
  const named = cleanupRun(laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(named.deleted, []);
  assert.match(named.failed[0].why, /not this run's/);
  assert.deepEqual(f.calls.filter((c) => c[0] === 'rm'), [], 'no recursive delete ran');
});

test('the marker is read again just before deleting: a dir whose marker changed or went is not touched; one still this run\'s goes', () => {
  const f = ownFs(['/w/mod']);
  assert.equal(claimRunDir(C2, ME, f).claimed, true);
  f.files.set(`${C2}/${OWNER_FILE}`, JSON.stringify(OTHER));
  let r = cleanupRun(laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(r.deleted, []);
  f.files.delete(`${C2}/${OWNER_FILE}`);
  r = cleanupRun(laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(r.deleted, []);
  assert.match(r.failed[0].why, /no owner marker/);
  // without an owner to check against, never deleted
  r = cleanupRun(laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [passLane(C2)] }), { ...f });
  assert.deepEqual(r.deleted, []);
  f.files.set(`${C2}/${OWNER_FILE}`, JSON.stringify(ME));
  r = cleanupRun(laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile }), { ...f });
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
  const lead = cleanupRun(laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [lane], owner: ME, readFile: f.readFile }), { ...f });
  assert.deepEqual(lead.deleted, []);
  assert.equal(lead.kept[0].why, 'client c2 kept its world');
  // the lead's own --keep-world holds the lane even when a child said nothing kept (an older child)
  const quiet = laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [passLane(C2)], owner: ME, readFile: f.readFile, keepWorld: true });
  assert.match(quiet[0].hold, /--keep-world/);
  assert.deepEqual(cleanupRun(quiet, { ...f }).deleted, []);
  // without --keep-world the world is this run's to delete on a pass
  const plain = cleanupRun(runItems({ world, saves: `${C2}/saves`, keepWorld: false, tempInputs: [], tmp: TMP.root }), { ...f });
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
  // with its slot, a passing run still deletes its own world
  assert.deepEqual(cleanupRun(runItems({ world, saves: `${C2}/saves`, slotWait: false, tempInputs: [], tmp: TMP.root }), { ...f }).deleted, [world]);
});

test('(B091 switch-off) lane run dirs are never claimed or deleted: the flag is off and e2e.mjs gates both on it', async () => {
  const disk = await import('../tools/lib/disk.mjs');
  assert.equal(disk.DELETE_LANE_DIRS, false);
  const src = (await import('node:fs')).readFileSync(new URL('../tools/e2e.mjs', import.meta.url), 'utf8');
  assert.match(src, /if \(DELETE_LANE_DIRS && !host && c\.k > 1\)/, 'the claim is gated');
  assert.match(src, /if \(DELETE_LANE_DIRS\) addCleanup\(cleanup, cleanupRun\(laneRunDirs\(/, 'the lead delete is gated');
  assert.equal((src.match(/laneRunDirs\(/g) ?? []).length, 1, 'no other caller');
  assert.equal((src.match(/claimRunDir\(/g) ?? []).length, 1, 'no other claim');
});
