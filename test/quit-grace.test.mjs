// (E2E-QUIT-GRACE) the decision before a dev.quit: wait for pending removals (bounded), quit at once when none, warn at the bound.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { openGroups, pendingRemovals, graceBeforeQuit, readPending, QUIT_GRACE_MS } from '../tools/lib/quit-grace.mjs';

/** A fake clock: sleep moves it on, so a 60 s bound runs at once. */
const clock = () => {
  let t = 1_000_000;
  return { now: () => t, sleep: async (ms) => { t += ms; } };
};

const LOG_094 = [
  '[07:08:15] [Server thread/INFO] (architect) Removing group g5 (17 site(s))',
  '[07:08:16] [Server thread/INFO] (architect) Undo of stage fluids done of group g4: all sites restored',
].join('\n');

test('a group whose removal began and has no end line is open; done, stopped and failed end it; a stage undo is not a group removal', () => {
  assert.deepEqual(openGroups(LOG_094), ['g5']);
  assert.deepEqual(openGroups(`${LOG_094}\n[07:08:40] Removal done of group g5: all sites restored`), []);
  assert.deepEqual(openGroups('Removing group g7 (3 site(s))\nRemoval stopped of group g7: Move these out'), []);
  assert.deepEqual(openGroups('Removing group g8 (3 site(s))\nRemoving group g8 failed'), []);
  // a resumed removal (after a restart) is open again until its end
  assert.deepEqual(openGroups('Removal stopped of group g9: x\nRemoving group g9: resuming the earlier removal (2 site(s) left to restore)'), ['g9']);
  assert.deepEqual(openGroups(''), []);
});

test('pending: REMOVING regions (their group named once, as the region) and the log\'s open groups', () => {
  const regions = [{ id: 'rg6', groupId: 'g5', state: 'REMOVING' }, { id: 'rg7', groupId: 'g6', state: 'PLACED' }];
  assert.deepEqual(pendingRemovals({ regions, logText: LOG_094 }), [{ kind: 'region', id: 'rg6', group: 'g5' }]);
  assert.deepEqual(pendingRemovals({ regions: null, logText: LOG_094 }), [{ kind: 'group', id: 'g5' }]);
  assert.deepEqual(pendingRemovals({ regions: [{ id: 'rg9', groupId: 'g9', state: 'REMOVING' }], logText: '' }), [{ kind: 'region', id: 'rg9', group: 'g9' }]);
  assert.deepEqual(pendingRemovals({ regions: regions.slice(1), logText: '' }), []);
});

test('nothing pending: no wait at all', async () => {
  const c = clock();
  let reads = 0;
  const r = await graceBeforeQuit(async () => { reads++; return []; }, c);
  assert.equal(r.outcome, 'none');
  assert.equal(r.waitedMs, 0);
  assert.equal(reads, 1);
});

test('pending, then done within the bound: waits until it ends, no warning', async () => {
  const c = clock();
  let log = LOG_094;
  let reads = 0;
  const read = async () => {
    if (++reads === 4) log += '\nRemoval done of group g5: all sites restored';
    return pendingRemovals({ regions: null, logText: log });
  };
  const r = await graceBeforeQuit(read, c);
  assert.equal(r.outcome, 'done');
  assert.equal(r.waitedMs, 3000);
  assert.deepEqual(r.pending, []);
  assert.doesNotMatch(r.line, /WARNING/);
});

test('still pending at the bound: a timeout naming the group, after the whole bound and no longer', async () => {
  const c = clock();
  const read = async () => pendingRemovals({ regions: [{ id: 'rg6', groupId: 'g5', state: 'REMOVING' }], logText: LOG_094 });
  const r = await graceBeforeQuit(read, c);
  assert.equal(r.outcome, 'timeout');
  assert.equal(r.waitedMs, QUIT_GRACE_MS);
  assert.deepEqual(r.pending, ['region rg6 (group g5)']);
  assert.match(r.line, /WARNING region rg6 \(group g5\) still removing after 60\.0 s/);
  // reads failing mid-wait don't end it early or read as done: the bound still holds
  const c2 = clock();
  let n = 0;
  const flaky = async () => { if (n++ === 0) return [{ kind: 'group', id: 'g5' }]; throw new Error('bridge gone'); };
  const r2 = await graceBeforeQuit(flaky, { ...c2, ms: 10_000 });
  assert.equal(r2.outcome, 'timeout');
  assert.deepEqual(r2.pending, ['group g5']);
});

test('the first read fails (the client going): quit at once, a note, no wait', async () => {
  const c = clock();
  const r = await graceBeforeQuit(async () => { throw new Error('closed'); }, c);
  assert.equal(r.outcome, 'unreadable');
  assert.equal(r.waitedMs, 0);
});

test('e2e.mjs: quit() runs the grace and records it; the deliberate mid-removal restart opts out', () => {
  const src = fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'tools', 'e2e.mjs'), 'utf8');
  const quit = /async function quit\(\{ cut = false \} = \{\}\) \{[\s\S]*?\n\}/.exec(src)?.[0] ?? '';
  assert.match(quit, /if \(!cut\) await quitGrace\(\)/, 'quit() waits for pending removals unless told the cut is on purpose');
  assert.match(src, /await restartClient\(\{ cut: true \}\);\n\s+await cmd\('\/architect survival on'\);\n\s+await cmd\('\/gamemode creative @p'\);\n\s+\/\/ the player steps out after the restart: Architect's resumed removal/,
    'the flow that restarts mid-removal on purpose opts out');
  assert.match(src, /warnings: \[\.\.\.warnings\]/, 'summary.json carries the warnings');
});

test('region state unreachable (the list throws, times out or is refused): the log\'s open groups still decide; only a gone client reads as nothing', async () => {
  const throws = async () => { throw new Error('timeout after 10000 ms'); };
  assert.deepEqual(await readPending({ listRegions: throws, logText: LOG_094 }), [{ kind: 'group', id: 'g5' }]);
  assert.deepEqual(await readPending({ listRegions: async () => ({ ok: false, error: 'busy' }), logText: () => LOG_094 }), [{ kind: 'group', id: 'g5' }]);
  assert.deepEqual(await readPending({ listRegions: throws, logText: '' }), []);
  assert.equal(await readPending({ listRegions: throws, logText: LOG_094, clientGone: () => true }), null);
  // so a grace over an unreachable list still waits for the logged removal, and warns at the bound
  const c = clock();
  const r = await graceBeforeQuit(() => readPending({ listRegions: throws, logText: LOG_094 }), { ...c, ms: 5000 });
  assert.equal(r.outcome, 'timeout');
  assert.deepEqual(r.pending, ['group g5']);
});
