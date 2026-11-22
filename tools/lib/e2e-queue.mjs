// (slice E2E-QUEUE) A --clients run's shared work queue: each client takes the next unit when it is free, instead of a lane packed up front.
// Units are groupFlows' groups (a flow with what it needs, the composed family, flows sharing a snapshot block), each run whole, in order, on one
// client. The lead (tools/e2e.mjs) holds one queue and one lane session per child; the child asks for a unit over Node's IPC channel, runs it and
// asks again. Pure: the clock is injected, so the tests drive it without processes (test/e2e-queue.test.mjs). mod/DEV.md "The work queue".

/**
 * The queue's units, longest first by recorded seconds (LPT: long units start early, short ones fill the ends). A unit holding a flow with no
 * recorded time goes after every timed one, in registry order. -> [{ id (1-based, queue order), flows, seconds, untimed }]; `seconds` of an
 * untimed unit is what its timed flows add up to.
 */
export function orderUnits(groups, seconds) {
  const timeOf = (g) => (g.every((n) => Number.isFinite(seconds?.[n])) ? g.reduce((s, n) => s + seconds[n], 0) : null);
  const rows = groups.map((g, i) => ({ g, i, t: timeOf(g) }));
  const timed = rows.filter((x) => x.t != null).sort((a, b) => b.t - a.t || a.i - b.i);
  const untimed = rows.filter((x) => x.t == null);
  return [...timed, ...untimed].map((x, k) => ({
    id: k + 1,
    flows: [...x.g],
    seconds: x.t ?? x.g.reduce((s, n) => s + (seconds?.[n] ?? 0), 0),
    untimed: x.t == null ? x.g.filter((n) => !Number.isFinite(seconds?.[n])).length : 0,
  }));
}

/**
 * The queue run against the recorded seconds: client k is ready (k-1) * stagger + overhead after the start (its launch, sanity set and restart),
 * then takes units in queue order, each the moment it is free (the earliest-free client, the lower one on a tie). Same lane shape as packFlows,
 * plus each lane's units and end. -> { lanes: [{ flows, seconds, untimed, units: [ids], end }], wall }.
 */
export function simulateQueue(units, clients, { overhead = 0, stagger = 0 } = {}) {
  const n = Math.max(1, Math.min(clients, units.length || 1));
  const lanes = Array.from({ length: n }, (_, i) => ({ flows: [], seconds: 0, untimed: 0, units: [], end: i * stagger + overhead }));
  for (const u of units) {
    const l = lanes.reduce((best, x) => (x.end < best.end ? x : best), lanes[0]);
    l.flows.push(...u.flows);
    l.seconds += u.seconds;
    l.untimed += u.untimed;
    l.units.push(u.id);
    l.end += u.seconds;
  }
  return { lanes, wall: Math.max(0, ...lanes.map((l) => l.end)) };
}

/**
 * How long one unit may run before the lead calls its client stuck (a backstop: the flows have bounds of their own). Per-flow times swing about
 * 40% with load and order (survival-mech 441 s on a 132 s baseline), so 4x its recorded seconds, at least `minS`; an untimed unit gets `untimedS`.
 */
export function unitBoundS(u, { factor = 4, minS = 20 * 60, untimedS = 60 * 60 } = {}) {
  if (u.untimed) return Math.max(untimedS, factor * u.seconds);
  return Math.max(minS, factor * u.seconds);
}

/**
 * The dispatcher. A unit goes queued -> running (one client) -> done | failed, once: `next` hands out the head of the queue and never a unit
 * that left it, `finish` only closes the asking client's own running unit, `dead` fails a gone client's running unit (it is never handed out
 * again), `expired` lists running units past their bound. Synchronous: the lead's one event loop is the only caller.
 */
export function unitQueue(units, { now = Date.now, boundOf = unitBoundS } = {}) {
  const queued = [...units];
  const state = new Map(units.map((u) => [u.id, { unit: u, status: 'queued', client: null, at: null, why: null }]));
  const running = new Map(); // client -> unit id
  const log = [];
  return {
    /** The next unit for `client`, or null when none is left. A client still holding a running unit gets none (its unit fails first). */
    next(client) {
      if (running.has(client)) throw new Error(`queue: ${client} asked for a unit while unit ${running.get(client)} still runs`);
      const u = queued.shift();
      if (!u) return null;
      const s = state.get(u.id);
      if (s.status !== 'queued') throw new Error(`queue: unit ${u.id} handed out twice`);
      Object.assign(s, { status: 'running', client, at: now() });
      running.set(client, u.id);
      log.push({ client, unit: u.id, what: 'take' });
      return u;
    },
    /** `client` finished unit `id`: true when that was its running unit (else nothing changes). */
    finish(client, id) {
      if (running.get(client) !== id) return false;
      running.delete(client);
      Object.assign(state.get(id), { status: 'done', seconds: Math.round((now() - state.get(id).at) / 1000) });
      log.push({ client, unit: id, what: 'done' });
      return true;
    },
    /** `client` is gone (ended, killed, stuck): its running unit fails with `why` and is returned; null when it held none. */
    dead(client, why) {
      const id = running.get(client);
      if (id == null) return null;
      running.delete(client);
      const s = state.get(id);
      Object.assign(s, { status: 'failed', why, seconds: Math.round((now() - s.at) / 1000) });
      log.push({ client, unit: id, what: 'failed' });
      return s.unit;
    },
    /** Running units past their bound: [{ client, unit, ranS, boundS }]. */
    expired() {
      const out = [];
      for (const [client, id] of running) {
        const s = state.get(id);
        const ranS = (now() - s.at) / 1000;
        const boundS = boundOf(s.unit);
        if (ranS > boundS) out.push({ client, unit: s.unit, ranS: Math.round(ranS), boundS });
      }
      return out;
    },
    /** Units no client took (every client gone first): each leaves the queue as failed with `why`. */
    drain(why) {
      const left = queued.splice(0);
      for (const u of left) Object.assign(state.get(u.id), { status: 'failed', why });
      return left;
    },
    size: () => queued.length,
    runningOf: (client) => running.get(client) ?? null,
    status: (id) => state.get(id)?.status ?? null,
    /** The units `client` took, in order, with their status. */
    taken: (client) => [...state.values()].filter((s) => s.client === client).sort((a, b) => a.at - b.at).map((s) => ({ ...s.unit, status: s.status, why: s.why, ranS: s.seconds ?? null })),
    log,
  };
}

/**
 * One child's side of the queue at the lead. The child sends {type: 'next', done?: id, rows?} when ready for a unit (done: the unit it just
 * finished, rows: that unit's results and flow rows, kept in case its summary never comes); the session answers {type: 'unit', unit} or
 * {type: 'end'}. Every wait is bounded (REVIEW-PATTERNS C14): spawn to the first ask (`startS`: the RAM floor alone may take 15 min), each unit
 * (`queue.expired`), and 'end' to the child's exit (`tailS`: the restart check and the quit). Past a bound `stop(why)` is called once and the
 * failure kept; `close(code)` fails a unit still running. -> { message(m), tick(), close(code), fails, rows, stopped }.
 */
export function laneSession(queue, client, { send, stop, now = Date.now, startS = 40 * 60, tailS = 20 * 60, log = () => {} }) {
  let phase = 'starting';
  let phaseAt = now();
  const fails = [];
  const rows = new Map(); // unit id -> { results, flows } the child said at its done
  let stopped = null;
  const halt = (why) => {
    if (stopped) return;
    stopped = why;
    phase = 'stopping';
    stop(why);
  };
  const failUnit = (u, why) => fails.push({ unit: u, why });
  return {
    message(m) {
      if (!m || m.type !== 'next' || phase === 'stopping' || phase === 'closed') return;
      if (m.done != null) {
        if (!queue.finish(client, m.done)) fails.push({ unit: null, why: `${client} said it finished unit ${m.done}, which it wasn't running` });
        else if (m.rows) rows.set(m.done, m.rows);
      }
      if (queue.runningOf(client) != null) {
        const u = queue.dead(client, `${client} asked for more without finishing it`);
        failUnit(u, `${client} asked for another unit without finishing unit ${u.id}`);
      }
      if (phase === 'tail') return;
      const u = queue.next(client);
      if (u) {
        phase = 'running';
        phaseAt = now();
        log(`queue: ${client} takes unit ${u.id} (${u.flows.join(', ')}; ~${u.seconds}s${u.untimed ? `, ${u.untimed} untimed` : ''}); ${queue.size()} left`);
        send({ type: 'unit', unit: { id: u.id, flows: u.flows } });
      } else {
        phase = 'tail';
        phaseAt = now();
        log(`queue: ${client}: no units left, finishing`);
        send({ type: 'end' });
      }
    },
    tick() {
      if (stopped || phase === 'closed') return;
      const t = (now() - phaseAt) / 1000;
      if (phase === 'starting' && t > startS) {
        fails.push({ unit: null, why: `${client} asked for no unit within ${Math.round(startS / 60)} min of its start (bound)` });
        halt(`no unit asked for within ${Math.round(startS / 60)} min`);
      } else if (phase === 'running') {
        const x = queue.expired().find((e) => e.client === client);
        if (x) {
          queue.dead(client, `stuck past its bound`);
          failUnit(x.unit, `unit ${x.unit.id} ran ${x.ranS}s on ${client}, past its ${x.boundS}s bound: the client was stopped`);
          halt(`unit ${x.unit.id} past its ${x.boundS}s bound`);
        }
      } else if (phase === 'tail' && t > tailS) {
        fails.push({ unit: null, why: `${client} did not end within ${Math.round(tailS / 60)} min of its last unit (bound)` });
        halt(`not ended within ${Math.round(tailS / 60)} min of its last unit`);
      }
    },
    close(code) {
      const u = queue.dead(client, `${client} ended (exit ${code})`);
      if (u) failUnit(u, `${client} ended (exit ${code}) during unit ${u.id}: it never finished`);
      phase = 'closed';
    },
    fails,
    rows,
    get stopped() { return stopped; },
    get phase() { return phase; },
  };
}

/**
 * The result rows for a lead's queue failures, each naming its client: a failed unit gives one FAIL row per flow (the flow's name in the check,
 * so a rerun can pick it) and a flow row with failed 1 (recordRun then drops the flow's PASS). A unit the child said was done whose flows are
 * missing from its summary fails too. -> { results, flows }.
 */
export function queueFailRows({ client, fails = [], taken = [], summaryFlows = null }) {
  const results = [];
  const flows = [];
  for (const f of fails) {
    if (!f.unit) { results.push({ check: 'queue', ok: false, why: f.why, client }); continue; }
    for (const n of f.unit.flows) {
      results.push({ check: `queue ${n}`, ok: false, why: f.why, client });
      if (!summaryFlows?.some((x) => x.name === n)) flows.push({ name: n, seconds: 0, load: null, checks: 1, failed: 1, client });
    }
  }
  if (summaryFlows) {
    for (const u of taken.filter((t) => t.status === 'done')) {
      const missing = u.flows.filter((n) => !summaryFlows.some((x) => x.name === n));
      for (const n of missing) {
        results.push({ check: `queue ${n}`, ok: false, why: `unit ${u.id} was done on ${client}, but ${n} has no row in its summary`, client });
        flows.push({ name: n, seconds: 0, load: null, checks: 1, failed: 1, client });
      }
    }
  }
  return { results, flows };
}
