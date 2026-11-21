// tools/lib/helper-wait.mjs: the wait for Architect's sim helper (CATALOG-WAIT), on a fake clock (no real sleeps).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { waitForPrograms } from '../tools/lib/helper-wait.mjs';

/** A fake clock whose sleep advances it, and a probe that plays the given answers (the last one repeats). */
function rig(answers) {
  let t = 0, calls = 0;
  const sleeps = [];
  return {
    now: () => t,
    sleep: async (ms) => { sleeps.push(ms); t += ms; },
    probe: async () => answers[Math.min(calls++, answers.length - 1)],
    get calls() { return calls; },
    sleeps,
  };
}
const NOT_UP = { up: false, given: false, detail: 'link connecting' };
const UP_NONE = { up: true, given: false, detail: 'link synced' };
const READY = { up: true, given: true, detail: 'link synced' };

test('retries until the helper gives programs', async () => {
  const r = rig([NOT_UP, NOT_UP, NOT_UP, READY]);
  const got = await waitForPrograms({ probe: r.probe, now: r.now, sleep: r.sleep });
  assert.equal(got, READY);
  assert.equal(r.calls, 4);
  assert.deepEqual(r.sleeps, [5000, 5000, 5000]);
});

test('ready at once: no wait', async () => {
  const r = rig([READY]);
  await waitForPrograms({ probe: r.probe, now: r.now, sleep: r.sleep });
  assert.equal(r.calls, 1);
  assert.equal(r.sleeps.length, 0);
});

test('a link that syncs a read before its programs land still passes', async () => {
  const r = rig([NOT_UP, UP_NONE, READY]);
  assert.equal(await waitForPrograms({ probe: r.probe, now: r.now, sleep: r.sleep }), READY);
});

test('a helper that never comes up times out after 5 minutes with a clear message', async () => {
  const r = rig([NOT_UP]);
  await assert.rejects(waitForPrograms({ probe: r.probe, now: r.now, sleep: r.sleep }),
    /did not come up in 300 s \(givenPrograms false, link connecting\): is Architect's first-start npm ci still running/);
  assert.equal(r.now(), 300_000);
  assert.equal(r.calls, 61);
});

test('a helper that is up but gives no programs fails, not waiting out the timeout', async () => {
  const r = rig([NOT_UP, UP_NONE]);
  await assert.rejects(waitForPrograms({ probe: r.probe, now: r.now, sleep: r.sleep }),
    /up \(link synced\) but reports givenPrograms false: this Architect build or helper gives no programs/);
  assert.equal(r.calls, 3);
  assert.ok(r.now() < 300_000);
});

test('the timeout and interval are honoured', async () => {
  const r = rig([NOT_UP]);
  await assert.rejects(waitForPrograms({ probe: r.probe, now: r.now, sleep: r.sleep, timeoutMs: 10_000, intervalMs: 1_000 }), /in 10 s/);
  assert.equal(r.calls, 11);
});
