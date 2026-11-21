// slice E2E-DISK: the keep-or-delete decision after a run and the free-space guard. The filesystem calls (statfs, lstat, rm, unlink) are
// injected: nothing here deletes or reads a real disk.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { minFreeGiB, existingAncestor, freeBytes, checkFreeSpace, keepReason, isInside, deleteNote, cleanupRun, mergeCleanups, laneRunDirs, addCleanup,
  DEFAULT_MIN_FREE_GIB, EXIT_LOW_DISK } from '../tools/lib/disk.mjs';

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
  const items = laneRunDirs({ modDir: '/w/mod', created: [C2], lanes: [{ k: 1, gameDir: '/w/mod/run', code: 0, summary: pass }, { k: 2, gameDir: C2, code: 0, summary: pass }] });
  assert.deepEqual(items, [{ path: C2, root: '/w/mod', what: 'a client run dir this run made' }]);
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
