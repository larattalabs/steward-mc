// The strange-asks gap map: node tools/strange-asks.mjs. Prints a summary and writes artifacts/strange-asks/report.md.
import fs from 'node:fs';
import path from 'node:path';
import { load, report, gaps } from './lib/strange-asks.mjs';

const data = load();
const r = report(data);
const out = path.join(path.dirname(new URL(import.meta.url).pathname), '..', 'artifacts', 'strange-asks');
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, 'report.md'), r.text);
console.log(`${data.asks.length} asks: ${r.counts.now} built, ${r.counts.nearest} nearest version, ${r.counts.waits} wait`);
console.log('Most asks unlocked:');
for (const g of gaps(data.caps, data.asks).slice(0, 10)) console.log(`  ${String(g.needed).padStart(2)} (${g.alone} alone)  ${g.name}  [${g.comes ?? ''}]`);
console.log(`Report: ${path.join(out, 'report.md')}`);
