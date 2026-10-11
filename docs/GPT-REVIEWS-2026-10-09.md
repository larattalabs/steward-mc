# GPT-6.1 Sol reviews, 2026-10-09

Three independent reviews at `88e100f` (codex exec, effort max): code correctness, plan and architecture, boundaries and robustness. Advisory. The findings
below were checked against the code; "confirmed" means read in the source (or reproduced by the reviewer's probe), not only reported.

## Hardening slice (fix before phase 3)

**Fixed, 2026-10-09** (commits 5915db1, 32055ac and c87754c). A GPT fix-verification pass then found gaps; they are fixed in the commit after them:
- approving massings was not counted as spending when the build could not be saved;
- quitting during a rollback could drop the checkpoint (the session now ends at SERVER_STOPPING);
- a temporarily refused update could slip past the claim check, and an approved update is now checked again before it is applied;
- a cancel resumed after a restart could stay stuck, and a finished batch's sites are now logged first;
- cancels are always sent rather than skipped on a cache miss;
- raise ordering: an older failure is ignored, and a failed resume keeps the raised budget;
- two lots that fit nowhere still held up the rest;
- a claim without a dimension loaded;
- dev commands bypassed the host-only rule.

Still open (nit): after the 10-minute description timeout, a second description can run alongside a stuck first one. 144 Java tests pass.

| # | Finding | Where | Status |
|---|---------|-------|--------|
| H1 | Redirected lot stays in `decided`: its new massing can never be approved or redirected again (survives restart) | `SettlementRunner` ApproveGroup | fixed |
| H2 | Budget is not a hard cap: bible always asks $3 (`BIBLE_HIGH*1.5`) whatever the budget; a raise extends the group to the whole new total, not total minus bible; a $0 group can be requested | `Pipeline.cardApproved`, `BudgetRaised`, `requestGroup` | fixed |
| H3 | Cancel during the bible never cancels it; cancel before the group ack loses the id; the slot is freed (and the save dropped) before Architect stops, so a replacement build can start and the old batch's placement log is lost | `Pipeline.step` Cancel, runner | fixed |
| H4 | Start uses the player's level, not the settlement's dimension (Nether start builds in the Nether at overworld coords) | `Actions.start` | fixed |
| H5 | Pending describe/runner callbacks survive leaving a world and write into the next one loaded | `Actions` card futures, runner closures | fixed |
| H6 | Two describes run (and pay) at once; the older can overwrite the newer card, or change the card of a build already started | `Actions.describe` | fixed, except: after the 10-minute timeout a second description can overlap a stuck first one (open) |
| H7 | No settlement owner: on LAN a guest can describe/start (spend host budget), expand, undo, apply updates | `Actions`, `StewardCommands` | fixed |
| H8 | Restored `BIBLE_RUNNING` only waits for an event that may already have been delivered; reread `Bibles.job` in resync | `ResyncRules` | fixed |
| H9 | Budget raise committed locally before extend/resume succeed; failure unhandled | runner `ExtendAndResumeGroup` | fixed |
| H10 | Updates: no check that the delta box stays inside the claim; refresh runs recursively from inline completions and retries failures | `Updates.refresh/apply` | fixed |
| H11 | Layout retries `left.get(0)` forever: one unplaceable lot blocks every later one (probe: blocked landmark → 0 lots) | `VillageLayout.plan` | fixed |
| H12 | Persistence: failed saves still mutate memory; spending continues after a corrupt/unsaveable builds file; failed settlement log then deletes the checkpoint (undo lost); incomplete nested records load | `Settlements`, `BuildStore`, `SettlementStore` | fixed |
| H13 | Update preview ghosts never cleared; e2e update check captures the log offset too late; e2e placement accepts 0 buildings | client, `tools/e2e.mjs` | fixed |

Later, not this slice: street feasibility scored before spending (layout dry-runs the road); caller operation ids from Architect so an interrupted ack
is reconciled without a second paid job.

## Plan review: points for Noah to decide

Noah's decision (2026-10-09): **2, 3 and 4 adopted; 1 declined** — phase 3 keeps its full scope (change requests, proposals, the steward
walking and talking, districts), not narrowed to one loop first. The hardening slice above comes first either way.

1. Narrow phase 3 around the loop "describe → enjoy → request a change → come back to useful growth": one requested edit with preview + revert, then a
   small Supplied settlement with one useful module, then playtested proposals. Districts, Economy, Hardcore after evidence. Playtest 3–5 fresh players.
2. Cost and wait are the real product problem, and copies don't fix unique-building settlements (Greywater: $13.76, ~70 min for 3). Ask Architect for
   per-stage timing/cost; set targets (useful starter under $5, first usable output within 15 min).
3. Shorten Architect's critical path: a narrow flat-settlement slice (lots, entrance paths, shared space, props) ahead of the full terrain work; split
   6c slice 0; retire `VillageLayout` only behind a joint migration + evolution gate.
4. Autonomy needs a real change history (operations with sites, versions, outcomes, recovery), version revert, and demolition approval for updates that
   remove parts — before autonomous updates or proposals.
5. Evaluate prompt fulfilment: the "swamp" demo was on plains by a lake; add held-out theme/site pairs and human walkthroughs; show the bible sheet in
   the inbox approval.
6. Reuse: record each placement's source version/params/palette/transform; offer "update together / pin / make independent"; funding approval when a
   copy falls back to an original. The one-third saving is a hypothesis until measured.
7. Growth triggers need rejection memory, cooldowns, coalescing and a planning allowance; start with one trigger.
8. Claims: separate reserved territory, districts and editable areas; "natural blocks" is not player provenance — protected areas Architect enforces.
9. Release track now: clean-profile install test, API-key setup, survival-obtainable Founding Stone, save migrations, host-only enforcement.

## 3f districts and protected areas (2026-10-10)

A review of commit 18451ab (3f), checked against the code; all twelve findings held and were fixed in the next commit.

| # | Finding | Fix |
|---|---|---|
| D1 | Protected areas kept lots off but not the street; areas marked after the survey were not seen | A new street across one abandons the build with its name; at queue time the current areas drop the lots on them and any street piece across them |
| D2 | A district's street was clamped to the town's claim | `BatchPlanner.build` takes the build's own land (the district's claim) |
| D3 | Lantern posts stood outside the band the rules checked | District roads have no lanterns |
| D4 | A founding interrupted by the world closing left a road nobody recorded, and the founding flag stuck | Callbacks are session-guarded; the flag clears at shutdown; `Districts.reconcile` takes up unrecorded district roads at start |
| D5 | The link road could be lost to a restart, and was skipped on the already-logged path | Both paths link; `reconcile` lays missing links at start and every minute while the host is in the settlement |
| D6 | Founding, building and undo had no shared busy guard | `SettlementRunner.busy` covers a road being laid and an undo running |
| D7 | A failed save's road removal was not awaited | Awaited, reported truthfully, and retried by `reconcile` |
| D8 | North/south roads were clamped in over lots | They go past the street's east end, else its west end, else refuse; never clamped |
| D9 | A building count does not mean a street has room (a district's first street is one lot long) | Additions may reach past the street, which is extended to them; a land where an addition found no room counts as full for an hour |
| D10 | Link roads stopped a column short of the street | They end on the column next to it |
| D11 | Founding and layouts picked different streets when there were two | One rule, `Districts.streetOf`, everywhere |
| D12 | An undo could give up a district whose link road still stood | Only when all its roads are gone and nothing else stands in it |

**Verification, two rounds.** The first found five of the fixes partial and six new problems, among them one serious one: the start-up clean-up matched a road by its role text, so it could have taken up a building whose role read "district road". The second round fixes:
- District roads carry their own `steward_mc:infra` marker, and only road sites with it are taken up.
- Street extensions are keyed `street#N`, outside the lots' `type_n` ids, so a `street_market` lot is not a street.
- Each street piece records its line in its ext (its box also holds lanterns, so its middle is not the line).
- An undone link is remembered (`linkUndone`), and only a new build in the district lays it again.
- A district with no road and nothing standing is given up even when its roads went earlier.
- Protecting ground is refused while a build is under way.
- The minute-by-minute reconcile runs before the proposal checks.
- The settlement stays busy until a failed save's road has been taken up.
- Founding refuses a road over a standing building.

The second verification found these resolved, and one gap: a relink after a later build was not retried. Fixed by clearing the mark before the relink.

Not migrated, and agreed by the review: `Road.link` has no format-3 migration, because format 3 has not shipped and no saved file holds a link road.

## 3b talking to the steward (2026-10-10)

A review of commit 98cf6d9 found eleven problems. They were checked against the code and fixed, and the fixes were verified twice.
- **Spending:**
  - A lost acknowledgement could let a second job overlap the first. A talk job's slot is now held for up to 3 minutes, and only its own answer frees it early.
  - Giving a proposal back refunded spends by title, a bug older than talk: it could refund an unrelated autonomous build. Each autonomous acceptance now records one spend, and only that one is given back.
  - Reopening a world no longer resets the hourly limit. The limit is kept per world folder.
- **Coalescing:** a building the player asked for counts against the rules' proposals, both while it waits and after it is accepted (`asked_<id>_<type>`).
- **Intents:**
  - A named district must match one district exactly or uniquely; otherwise the steward asks which.
  - The full-town path reuses a district proposal that is waiting, and skips ways the player declined.
  - Every save result is checked before the steward confirms.
  - A malformed answer is a failed answer, never an exception.
  - Small talk matches whole sentences only, so "thanks, now make the tavern creepier" is a request.
  - The cap on open proposals applies to every path.

Accepted, as the verification agreed: the hourly count resets when the game restarts. A job that never reports frees its slot after 3 minutes, so the next question can overlap it. Each job is capped at $0.05 and is the player's own action.

## 3e wording and 3c change requests (2026-10-10)

There were no findings in the proposal wording. Change requests had four, all fixed and verified:
- **No second charge after a lost answer:** a request is given back to the inbox only on a definite refusal, one typed with a reason other than `WORLD_STOPPED`. After a lost answer it stays accepted, because the design may still come, as an update.
- **Acceptance saved first:** acceptance is saved before anything is sent, so nothing is spent if the save fails.
- **The settlement's own look:** a change carries the settlement's bible at its saved version. It is refused while the style has changed since that bible was made.
- **Outcomes kept:** the design id is logged when Architect takes the request, and the outcome is logged with its cost.

Not added: recovery of an in-flight change across a restart. Its version still installs and the updates offer it; only the outcome note is lost.

## Operation keys on Architect 0.13 (2026-10-10)

The review of commit dbc2b1a found eight problems. Two fix rounds were verified, and a third fix went in without a verification of its own:
- **Cancelling:**
  - a cancel with no id saved looks the requests up by key and stops what runs before it ends (`cancelByKeys`);
  - a cancel waits for a lookup that is out;
  - a failed lookup keeps the build saved, retries, and then says the cancel waits.
- **Lost answers in play:** a timeout or a dropped link is looked up by key instead of failing the build. Only Architect's own words count as a lost answer: "connection lost", "not connected", timeouts, `SIDECAR_UNAVAILABLE`. Two lookups per build at most; past that, the build stays saved and is reported.
- **Adoption:**
  - a keyed build takes over only a batch carrying its build tag;
  - a placement waiting to be queued looks its key up first;
  - `OP_KEY_CONFLICT` is looked up and adopted;
  - an adopted group gets its landmarks back.
- **Undo:** it ignores `WORLD_STOPPED` and is session-fenced.
- **Old keys:** Architect keeps a key 30 days after its work ends. A build older than 25 days never re-sends on an empty lookup.

The third fix closes what the second verification left: the third lost answer in a build ended it while Architect might still be charging. It was checked by the e2e only (free 33, stub 24).

Not changed: the dev-only `/steward resume` path, which players never use.
