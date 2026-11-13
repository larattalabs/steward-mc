// The e2e check's flow choice (tools/e2e.mjs), kept apart so it has unit tests (test/e2e-flows.test.mjs).

/**
 * The flows an --only run picks (all of them with no words): a word of it in the flow's name or tags, then what those need, and what that needs.
 * `list` is the flows in run order, each { name, tags, needs }; each map value is why the flow runs: '' when picked, else the flow that needs it.
 */
export function pickFlows(list, words) {
  const why = new Map();
  if (!words) for (const f of list) why.set(f.name, '');
  else for (const f of list) if (words.some((w) => `${f.name} ${f.tags}`.toLowerCase().includes(w))) why.set(f.name, '');
  const pull = (f) => {
    for (const n of f.needs ?? []) {
      if (!why.has(n)) why.set(n, f.name);
      pull(list.find((x) => x.name === n));
    }
  };
  for (const f of list) if (why.has(f.name)) pull(f);
  return why;
}
