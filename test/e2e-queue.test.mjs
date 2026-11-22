// (slice E2E-QUEUE) A --clients run's shared work queue: units longest first, a dead or stuck client's unit FAILs and the rest go on to the
// others, no unit runs twice, groups stay whole, every wait bounded.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

import { mergeFlowSeconds } from '../tools/lib/e2e-flows.mjs';
import { orderUnits, simulateQueue, unitQueue, unitBoundS, laneSession, queueFailRows } from '../tools/lib/e2e-queue.mjs';

const SRC = fs.readFileSync(new URL('../tools/e2e.mjs', import.meta.url), 'utf8');

/** A fake clock the tests move by hand. */
const clock = (t = 0) => { const c = () => t; c.add = (s) => { t += s * 1000; }; return c; };
/** A lane session wired to plain arrays: what it sent, what it stopped for. */
function session(q, client, now, opts = {}) {
  const sent = [];
  const stops = [];
  const s = laneSession(q, client, { send: (m) => sent.push(m), stop: (why) => stops.push(why), now, ...opts });
  return { s, sent, stops };
}

test('LPT: units go longest first by recorded seconds, ties in registry order, untimed units last', () => {
  const units = orderUnits([['a'], ['b', 'c'], ['d'], ['x'], ['e']], { a: 100, b: 300, c: 50, d: 400, e: 100 });
  assert.deepEqual(units.map((u) => u.flows), [['d'], ['b', 'c'], ['a'], ['e'], ['x']]);
  assert.deepEqual(units.map((u) => u.id), [1, 2, 3, 4, 5]);
  assert.deepEqual(units.map((u) => u.seconds), [400, 350, 100, 100, 0]);
  assert.equal(units[4].untimed, 1);
});

test('groups stay whole: a unit is its group, in its order, and the client is sent exactly that', () => {
  const units = orderUnits([['gate-mech', 'gate-mech-lots', 'drawbridge'], ['adopt', 'adopt-ns']], { 'gate-mech': 200, 'gate-mech-lots': 200, drawbridge: 200, adopt: 175, 'adopt-ns': 101 });
  assert.deepEqual(units[0].flows, ['gate-mech', 'gate-mech-lots', 'drawbridge']);
  assert.deepEqual(units[1].flows, ['adopt', 'adopt-ns']);
  const now = clock();
  const q = unitQueue(units, { now });
  const { s, sent } = session(q, 'c1', now);
  s.message({ type: 'next' });
  assert.deepEqual(sent[0], { type: 'unit', unit: { id: 1, flows: ['gate-mech', 'gate-mech-lots', 'drawbridge'] } });
});

test('the queue beats packing up front when a unit runs long: b096\'s shape', () => {
  // packed up front, a 3.3x unit lands on one lane while the other runs dry; a queue hands the rest to whoever is free
  const units = orderUnits([['a'], ['b'], ['c'], ['d'], ['e'], ['f']], { a: 600, b: 500, c: 400, d: 300, e: 200, f: 100 });
  const sim = simulateQueue(units, 2, { overhead: 0, stagger: 0 });
  assert.deepEqual(sim.lanes.map((l) => l.units), [[1, 4, 5], [2, 3, 6]]);
  assert.equal(sim.wall, 1100);
  // the stagger and the per-client overhead shift who is free first
  const st = simulateQueue(units, 2, { overhead: 240, stagger: 120 });
  assert.equal(st.lanes[0].units[0], 1);
  assert.equal(st.lanes[1].units[0], 2);
  // never more clients than units
  assert.equal(simulateQueue(units.slice(0, 1), 3).lanes.length, 1);
});

test('a dead client\'s running unit FAILs, is never handed out again, and the rest go on to the other clients', () => {
  const now = clock();
  const units = orderUnits([['a'], ['b'], ['c'], ['d']], { a: 400, b: 300, c: 200, d: 100 });
  const q = unitQueue(units, { now });
  const c1 = session(q, 'c1', now);
  const c2 = session(q, 'c2', now);
  c1.s.message({ type: 'next' });
  c2.s.message({ type: 'next' });
  assert.equal(q.runningOf('c1'), 1);
  assert.equal(q.runningOf('c2'), 2);
  c1.s.close(137);
  assert.equal(q.status(1), 'failed');
  assert.match(c1.s.fails[0].why, /c1 ended \(exit 137\) during unit 1/);
  assert.deepEqual(c1.s.fails[0].unit.flows, ['a']);
  // c2 carries on and takes what c1 would have
  c2.s.message({ type: 'next', done: 2 });
  c2.s.message({ type: 'next', done: 3 });
  c2.s.message({ type: 'next', done: 4 });
  assert.deepEqual(c2.sent.map((m) => m.unit?.id ?? m.type), [2, 3, 4, 'end']);
  assert.deepEqual(q.taken('c2').map((u) => [u.id, u.status]), [[2, 'done'], [3, 'done'], [4, 'done']]);
  assert.equal(q.status(1), 'failed');
  // the failure becomes a FAIL row per flow, with a flow row that drops its PASS
  const rows = queueFailRows({ client: 'c1', fails: c1.s.fails, taken: q.taken('c1'), summaryFlows: null });
  assert.deepEqual(rows.results.map((r) => [r.check, r.ok, r.client]), [['queue a', false, 'c1']]);
  assert.deepEqual(rows.flows.map((f) => [f.name, f.failed]), [['a', 1]]);
});

test('no unit runs twice: any interleaving of takes, finishes and deaths hands each unit out at most once, and every unit ends done or failed', () => {
  let seed = 7;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
  for (let round = 0; round < 200; round++) {
    const units = orderUnits(Array.from({ length: 12 }, (_, i) => [`f${i}`]), Object.fromEntries(Array.from({ length: 12 }, (_, i) => [`f${i}`, 10 + i])));
    const now = clock();
    const q = unitQueue(units, { now });
    const lanes = ['c1', 'c2', 'c3'].map((c) => ({ c, ...session(q, c, now), alive: true, cur: null }));
    const handed = [];
    for (let step = 0; step < 60; step++) {
      const l = lanes[Math.floor(rnd() * lanes.length)];
      if (!l.alive) continue;
      if (rnd() < 0.08) { l.s.close(1); l.alive = false; continue; }
      const before = l.sent.length;
      l.s.message({ type: 'next', ...(l.cur ? { done: l.cur } : {}) });
      const m = l.sent[before];
      l.cur = m?.type === 'unit' ? m.unit.id : null;
      if (m?.type === 'unit') handed.push(m.unit.id);
      if (m?.type === 'end') l.alive = false;
    }
    for (const l of lanes) if (l.alive) l.s.close(0);
    q.drain('never run');
    assert.equal(new Set(handed).size, handed.length, `round ${round}: a unit handed out twice (${handed})`);
    for (const u of units) assert.ok(['done', 'failed'].includes(q.status(u.id)), `round ${round}: unit ${u.id} is ${q.status(u.id)}`);
  }
  // and the dispatcher itself refuses a second hand-out
  const q = unitQueue(orderUnits([['a']], { a: 1 }));
  assert.equal(q.next('c1').id, 1);
  assert.throws(() => q.next('c1'), /still runs/);
  assert.equal(q.next('c2'), null);
});

test('a stuck client: past its unit\'s bound it is stopped once, the unit FAILs, and the next client takes the rest', () => {
  const now = clock();
  const units = orderUnits([['slow'], ['b']], { slow: 300, b: 100 });
  const q = unitQueue(units, { now });
  const c1 = session(q, 'c1', now);
  c1.s.message({ type: 'next' });
  const bound = unitBoundS(units[0]);
  assert.equal(bound, 20 * 60, 'at least 20 min');
  assert.equal(unitBoundS({ seconds: 600, untimed: 0 }), 2400, '4x its recorded seconds');
  now.add(bound - 1);
  c1.s.tick();
  assert.deepEqual(c1.stops, []);
  now.add(2);
  c1.s.tick();
  c1.s.tick();
  assert.equal(c1.stops.length, 1, 'stopped once');
  assert.match(c1.stops[0], /unit 1 past its 1200s bound/);
  assert.equal(q.status(1), 'failed');
  assert.match(c1.s.fails[0].why, /past its 1200s bound/);
  // its late "done" is refused, and it gets nothing more
  c1.s.message({ type: 'next', done: 1 });
  assert.equal(q.status(1), 'failed');
  c1.s.close(143);
  assert.equal(c1.s.fails.length, 1, 'the close after the stop adds nothing');
  const c2 = session(q, 'c2', now);
  c2.s.message({ type: 'next' });
  assert.equal(c2.sent[0].unit.id, 2);
});

test('the other waits are bounded too: spawn to the first ask, and the last unit to the exit', () => {
  const now = clock();
  const q = unitQueue(orderUnits([['a']], { a: 10 }), { now });
  const c1 = session(q, 'c1', now, { startS: 600, tailS: 300 });
  now.add(601);
  c1.s.tick();
  assert.equal(c1.stops.length, 1);
  assert.match(c1.s.fails[0].why, /asked for no unit within 10 min/);
  assert.equal(q.size(), 1, 'its start failed before it took anything: the unit stays for the others');
  const c2 = session(q, 'c2', now, { startS: 600, tailS: 300 });
  c2.s.message({ type: 'next' });
  c2.s.message({ type: 'next', done: 1 });
  assert.equal(c2.sent[1].type, 'end');
  now.add(301);
  c2.s.tick();
  assert.match(c2.s.fails[0].why, /did not end within 5 min/);
  assert.equal(c2.stops.length, 1);
});

test('zero rows is never a pass: a unit said done whose flows are missing from the summary FAILs', () => {
  const now = clock();
  const q = unitQueue(orderUnits([['a', 'b']], { a: 1, b: 1 }), { now });
  const c1 = session(q, 'c1', now);
  c1.s.message({ type: 'next' });
  c1.s.message({ type: 'next', done: 1 });
  const rows = queueFailRows({ client: 'c1', fails: [], taken: q.taken('c1'), summaryFlows: [{ name: 'a', failed: 0 }] });
  assert.deepEqual(rows.results.map((r) => r.check), ['queue b']);
  assert.deepEqual(rows.flows.map((f) => [f.name, f.failed]), [['b', 1]]);
  assert.deepEqual(queueFailRows({ client: 'c1', fails: [], taken: q.taken('c1'), summaryFlows: [{ name: 'a' }, { name: 'b' }] }).results, []);
});

test('wiring: the lead hands out units over IPC, the child asks with --queue, the nightly\'s plan line is kept', () => {
  assert.match(SRC, /'--queue'/);
  assert.match(SRC, /stdio: \['ignore', 'pipe', 'pipe', 'ipc'\]/);
  assert.match(SRC, /laneSession\(queue, client,/);
  assert.match(SRC, /queue\.drain\('never run: no client was left to take it'\)/);
  // tools/nightly.mjs dryFit reads `client cK (ports, dir): ~Ns recorded`
  assert.match(SRC, /console\.log\(`client c\$\{c\.k\} \(\$\{c\.sidecarPort\}\/\$\{c\.devPort\}, \$\{path\.relative\(ROOT, c\.gameDir\)\}\): ~\$\{c\.simulated\.seconds\}s recorded/);
  // a further client with nothing left to take isn't started
  assert.match(SRC, /if \(c\.k > 1 && queue\.size\(\) === 0\)/);
});

test('a failed unit\'s flow rows carry no time: the next run keeps their recorded seconds and LPT order', () => {
  const now = clock();
  const q = unitQueue(orderUnits([['slow']], { slow: 300 }), { now });
  const c1 = session(q, 'c1', now);
  c1.s.message({ type: 'next' });
  c1.s.close(137);
  const rows = queueFailRows({ client: 'c1', fails: c1.s.fails, taken: q.taken('c1'), summaryFlows: null });
  assert.equal(rows.flows.length, 1);
  assert.deepEqual(mergeFlowSeconds([{ seconds: { slow: 300 } }, { flows: rows.flows }]), { slow: 300 });
  assert.match(SRC, /if \(Number\.isFinite\(r\.seconds\)\) times\[r\.name\]/);
});
