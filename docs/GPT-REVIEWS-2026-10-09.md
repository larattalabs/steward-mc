# GPT-6.1 Sol reviews, 2026-10-09

Three independent reviews at `88e100f` (codex exec, effort max): code correctness, plan and architecture, boundaries and robustness. Advisory. The findings
below were checked against the code; "confirmed" means read in the source (or reproduced by the reviewer's probe), not only reported.

## Hardening slice (fix before phase 3)

**All fixed, 2026-10-09** (commits 5915db1, 32055ac and the e2e commit after them). 140+ Java tests pass, and the free e2e check passes all 11 checks.

| # | Finding | Where | Status |
|---|---------|-------|--------|
| H1 | Redirected lot stays in `decided`: its new massing can never be approved or redirected again (survives restart) | `SettlementRunner` ApproveGroup | fixed |
| H2 | Budget is not a hard cap: bible always asks $3 (`BIBLE_HIGH*1.5`) whatever the budget; a raise extends the group to the whole new total, not total minus bible; a $0 group can be requested | `Pipeline.cardApproved`, `BudgetRaised`, `requestGroup` | fixed |
| H3 | Cancel during the bible never cancels it; cancel before the group ack loses the id; the slot is freed (and the save dropped) before Architect stops, so a replacement build can start and the old batch's placement log is lost | `Pipeline.step` Cancel, runner | fixed |
| H4 | Start uses the player's level, not the settlement's dimension (Nether start builds in the Nether at overworld coords) | `Actions.start` | fixed |
| H5 | Pending describe/runner callbacks survive leaving a world and write into the next one loaded | `Actions` card futures, runner closures | fixed |
| H6 | Two describes run (and pay) at once; the older can overwrite the newer card, or change the card of a build already started | `Actions.describe` | fixed |
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
