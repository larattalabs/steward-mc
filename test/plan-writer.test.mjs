import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

import { checkAnswer } from '../tools/lib/plan-check.mjs';

const dir = new URL('../fixtures/plans/', import.meta.url);
for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.json'))) {
  test(`plan fixture ${f} is a valid plan/0 answer`, () => {
    const { answer } = JSON.parse(fs.readFileSync(new URL(f, dir)));
    assert.deepEqual(checkAnswer(answer), []);
  });
}

test('a plan with a world coordinate or an unknown top-level member is not plan/0', () => {
  const { answer } = JSON.parse(fs.readFileSync(new URL('walled_town.json', dir)));
  assert.notDeepEqual(checkAnswer({ ...answer, plan: { ...answer.plan, origin: [100, 64, 200] } }), []);
});

test("a zone's program is checked by its own kind: a lots program's shared space is square, well or none", () => {
  const { answer } = JSON.parse(fs.readFileSync(new URL('../fixtures/strange-asks/plans-2026-11-10/mushroom_town.json', import.meta.url)));
  assert.match(checkAnswer(answer).join('\n'), /plan\/zones\/0\/program\/shared must be equal to one of the allowed values/);
  const fixed = structuredClone(answer);
  fixed.plan.zones[0].program.shared = 'square';
  assert.deepEqual(checkAnswer(fixed), []);
});
