// batch-074 GPT review P2: on a signal an e2e driver stops what it started (child drivers, the client JVM) first, by pid and bounded, then releases
// its slots, then removes its sidecar data, then exits 128 + n (tools/lib/shutdown.mjs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { shutdown, onStop, onFinal, _reset, stopPid, pidRuns } from '../tools/lib/shutdown.mjs';

const LIB = pathToFileURL(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'tools', 'lib')).href;
const tmp = (p) => fs.mkdtempSync(path.join(os.tmpdir(), p));

test('the order: every stop step (awaited), then release, then cleanup, then exit 128 + n; once', async () => {
  _reset();
  const ev = [];
  onStop(async (sig) => { ev.push(`stop a ${sig}`); await new Promise((r) => setTimeout(r, 40)); ev.push('stopped a'); });
  onStop(async () => { ev.push('stop b'); throw new Error('b failed'); });
  onFinal('cleanup', () => ev.push('cleanup'));
  onFinal('release', () => ev.push('release'));
  const exit = (c) => ev.push(`exit ${c}`);
  const p = shutdown('SIGTERM', { exit, log: () => {} });
  assert.equal(shutdown('SIGINT', { exit, log: () => {} }), p, 'a second signal joins the first');
  await p;
  assert.deepEqual(ev, ['stop a SIGTERM', 'stop b', 'stopped a', 'release', 'cleanup', 'exit 143']);
  _reset();
});

test('stopPid: a process that exits on the signal is "exited"; one that ignores it is SIGKILLed after the grace', async () => {
  const polite = spawn(process.execPath, ['-e', "process.on('SIGTERM', () => setTimeout(() => process.exit(0), 100)); setInterval(() => {}, 1000)"], { stdio: 'ignore' });
  const stubborn = spawn(process.execPath, ['-e', "process.on('SIGTERM', () => {}); setInterval(() => {}, 1000)"], { stdio: 'ignore' });
  await new Promise((r) => setTimeout(r, 300));
  const ex = (c) => () => c.exitCode !== null || c.signalCode !== null;
  assert.equal(await stopPid(polite.pid, { graceMs: 2000, exited: ex(polite) }), 'exited');
  assert.equal(await stopPid(stubborn.pid, { graceMs: 300, exited: ex(stubborn) }), 'killed');
  assert.equal(stubborn.signalCode, 'SIGKILL');
  assert.equal(await stopPid(stubborn.pid, { exited: ex(stubborn) }), 'gone');
});

/**
 * A lead-like driver: a slot from a fake queue (slot-queue), an owned sidecar dir (tmp-owner), two child "drivers" it started (one that quits on
 * SIGTERM, one that ignores it), and a stop step that forwards the signal to them by pid. The fake queue's release records which of the children
 * still run at that moment.
 */
function leadDriver() {
  const dir = tmp('shutdown-');
  const pidsFile = path.join(dir, 'pids.json'), calls = path.join(dir, 'calls.log'), side = path.join(dir, 'ram', 'sidecar-x');
  fs.mkdirSync(path.join(dir, 'arch', 'tools'), { recursive: true });
  fs.mkdirSync(side, { recursive: true });
  fs.writeFileSync(path.join(dir, 'arch', 'tools', 'slot.mjs'), `
import fs from 'node:fs';
const [cmd, ...argv] = process.argv.slice(2);
const runs = (p) => { try { process.kill(p, 0); return true; } catch { return false; } };
const pids = fs.existsSync(${JSON.stringify(pidsFile)}) ? JSON.parse(fs.readFileSync(${JSON.stringify(pidsFile)}, 'utf8')) : [];
fs.appendFileSync(${JSON.stringify(calls)}, JSON.stringify({ cmd, running: pids.filter(runs), sidecar: fs.existsSync(${JSON.stringify(path.join(side, 'data'))}) }) + '\\n');
if (cmd === 'acquire') console.log(JSON.stringify({ slot: 1, ticket: 't-1-' + process.pid, waitedSeconds: 0 }));
else console.log('{}');
`);
  const src = `
import fs from 'node:fs';
import { spawn } from 'node:child_process';
import { acquire } from '${LIB}/slot-queue.mjs';
import { own } from '${LIB}/tmp-owner.mjs';
import { onStop, stopPid } from '${LIB}/shutdown.mjs';
await acquire('lead', { log: () => {} });
own(${JSON.stringify(side)});
fs.writeFileSync(${JSON.stringify(path.join(side, 'data'))}, 'x');
const kids = [
  spawn(process.execPath, ['-e', "for (const s of ['SIGTERM', 'SIGINT']) process.on(s, () => setTimeout(() => process.exit(0), 200)); setInterval(() => {}, 1000)"], { stdio: 'ignore' }),
  spawn(process.execPath, ['-e', "for (const s of ['SIGTERM', 'SIGINT']) process.on(s, () => {}); setInterval(() => {}, 1000)"], { stdio: 'ignore' }),
];
fs.writeFileSync(${JSON.stringify(pidsFile)}, JSON.stringify(kids.map((k) => k.pid)));
onStop(async (sig) => {
  const r = await Promise.all(kids.map((k) => stopPid(k.pid, { sig, graceMs: 500, exited: () => k.exitCode !== null || k.signalCode !== null })));
  console.log('stopped ' + r.join(','));
});
await new Promise((r) => setTimeout(r, 300));
console.log('ready');
setInterval(() => {}, 1000);
`;
  fs.writeFileSync(path.join(dir, 'lead.mjs'), src);
  const child = spawn(process.execPath, [path.join(dir, 'lead.mjs')], { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, ARCHITECT_DIR: path.join(dir, 'arch') } });
  let out = '';
  const ready = new Promise((r) => child.stdout.on('data', (d) => { out += d; if (/ready/.test(out)) r(); }));
  const done = new Promise((r) => child.on('close', (code, signal) => r({ code, signal })));
  const read = () => fs.readFileSync(calls, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
  return { child, ready, done, out: () => out, calls: read, pids: () => JSON.parse(fs.readFileSync(pidsFile, 'utf8')), side };
}

for (const sig of ['SIGTERM', 'SIGINT']) {
  test(`${sig} to a lead: its children are stopped (forwarded, then SIGKILL past the grace) before its slot is released and its sidecar data removed`, async () => {
    const d = leadDriver();
    await d.ready;
    const pids = d.pids();
    d.child.kill(sig);
    // a second signal while it shuts down is ignored, not a second exit
    setTimeout(() => d.child.kill(sig), 100);
    const r = await d.done;
    assert.equal(r.code, sig === 'SIGINT' ? 130 : 143, d.out());
    assert.match(d.out(), /stopped exited,killed/);
    const c = d.calls();
    assert.deepEqual(c.map((x) => x.cmd), ['acquire', 'release']);
    assert.deepEqual(c[1].running, [], 'no child of the lead still ran when its slot went back');
    assert.equal(c[1].sidecar, true, 'the sidecar data was still there at the release: it goes after');
    assert.equal(fs.existsSync(d.side), false, 'the sidecar data went last');
    for (const p of pids) assert.equal(pidRuns(p), false);
  });
}
