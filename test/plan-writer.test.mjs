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
