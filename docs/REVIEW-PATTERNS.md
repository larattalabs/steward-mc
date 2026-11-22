# Recurring review findings

Noah, 2026-11-21: "make notes … to alleviate some of the issues/patterns of issues we keep finding in gpt reviews". The list below is distilled
from about 60 GPT reviews, 11-15 to 11-21, across Architect and Steward. It is a design checklist, not a new gate: no speed rule changes.

How to use it:
- **Design** (slice brief or contract): answer A1-A6 and B7-B10 in writing before building strict work.
- **Builder self-check:** walk C11-C14 for every new test before the early GPT review.
- **Reviewer brief:** paste the block "Known failure classes, check these first" (below) into every GPT review prompt.

## A. Crash safety

1. **One durable record per fact.** Name the single record that decides "did this happen?" and derive everything else from it.
   Two stores that can disagree after a crash (an item drop and its receipt, a queue file and a site record, a credit and its block) caused
   most P1s.
2. **Persist before mutate, and acknowledge the right save.** A queued save, an executor completion or an off-thread write isn't persistence.
   Chunks save before entities; player saves swallow errors.
3. **The second crash.** Recovery must itself be crash-safe and exactly-once: crash, recover, crash again.
4. **Key per operation, not per target.** Idempotency keys name the attempt, never just the site or cell.
5. **Holds and admission see everything**, including holds loaded from disk after a restart, over full footprints.
6. **Refunds track what was removed**, priced at the real item cost, not from a plan or journal guess.

## B. Scope and design

7. **Don't convert old in-flight state.** Let legacy in-flight work finish on the old path; only new work uses the new design.
8. **Listing cases never closes a hole.** Find one structural rule, or narrow the promise and document the rest as a known risk.
9. **When every round finds new edges in the same area, stop patching.** Redesign or narrow scope (speed rule 4).
10. **Old saved data is a test input** (`fixtures/saves/`, `SaveCompatTest`): every reader is checked against saves from earlier versions.

## C. Tests that prove something

11. **A test must fail when its fix is reverted.** For each failing-first row, revert the fix (or mutate the guarded line) and confirm it goes
    red again, then restore. Record it in the report ("red-again: <row> on <revert>").
12. **Assert state, not log lines.** A missing log line doesn't prove something didn't happen: read the saved record or the world, or add a
    dev-only probe.
13. **Make the bad window reachable.** If a race needs a hook, add a dev-only fault point; otherwise the in-game row never fires.
14. **Every wait has a bound.** A timeout is a FAIL, never a pass, and zero results is never a pass.

## D. Tooling and the disk

15. **Paths are hostile.** realpath, refuse symlinked or nested roots, compare case-insensitively (APFS), claim files exclusively, and
    prove ownership before deleting.
16. **Runs clean up after themselves** and refuse to start below the free-space floor.
17. **Verifiers don't reserve the whole machine** (Architect: `--no-turbo`, fixed shards; Steward: the slot queue, rule 12).

## Steward examples

- **E2E checks green while the property was false (C11, C12):**
  - B092-LEFT's refund check counted an empty `returned {}` line as a credit.
  - Its stale check took a missing log line as proof that `Composer.check` never ran.
  - B092-LEFT-2 replaced both: the saved `stockpiles.json` at the halt, and a dev-only check counter (`/steward dev checks`).
- **Unbounded waits (C14):** the sculpt stall. After a mid-carve halt, "The River waits for the River to be built" ran for 30+ minutes, on
  Architect's orphaned `placing` site (B092L-1). The flows now fail after 5 minutes with no progress.
- **Paths and case (D15):** E2E-DISK-2 to -5:
  - lane checks only looked directly under `mod/`;
  - world names had to match case;
  - the sidecar sweep wiped dirs the run didn't own;
  - `sidecar-<hash>` that wasn't a directory left a dangling link.
- **Listing cases (B8):** MODULE-TYPE. Additions, districts, quarters and talk could each propose a paid building of a catalog module's
  type. Each path got its own filter, and MODULE-TYPE-2 still found routes past them (talk's full-town routing, the rule filter starving
  later rules). Prefer one check at the point where a proposal is accepted.
- **Persist before mutate (A2):** FENCE-TESTS-3 and -4. A synchronous throw left `queueInFlight` true. quoteModules and growth refusals
  changed memory before a confirmed save.

## Known failure classes, check these first

Paste this block into GPT review prompts, after the goal and the diff to read:

```
Known failure classes from past reviews; check these first, then review openly:
1 two stores that can disagree after a crash; 2 mutation before a confirmed durable save (a queued save is not one);
3 recovery that is not itself crash-safe / exactly-once; 4 idempotency keyed per target, not per operation;
5 holds/admission blind to footprints or to state loaded after restart; 6 refunds from plans, not from what was removed;
7 converting legacy in-flight state; 8 case lists standing in for a structural rule; 10 old saves not covered;
11 tests that stay green with the fix reverted; 12 log-line absence used as proof; 13 race windows no test can reach;
14 waits without a bound, timeouts or zero results counted as pass; 15 path/symlink/case/ownership gaps in cleanup.
```
