# Steward: agent guide

Steward is a Fabric mod (Minecraft 26.3, Java 25). You found a settlement with the Founding Stone, describe it in your own words, and a steward NPC designs,
builds and evolves it with Claude. Steward depends on Architect (`../architect-mc`), which does the design jobs, fitting, placement, undo and versions, and on
lab-ui (`../lab-ui`), the shared screen kit and world UI. It has these parts:

- `mod/`: Java.
  - `pipeline/`: the build as a pure state machine (`Pipeline`, `ResyncRules`).
  - `service/`: the runner and the actions behind every screen and command (`SettlementRunner`, `Actions`, `Updates`, `Revert`, `Undo`).
  - `model/`, `layout/`, `gateway/`: settlements and their change log, the street layout, the requests to Architect.
  - `entity/`: the steward mob. `view/`: what the screens show.
  - `src/client/`: screens, HUD, renderer.
- `schema/`, `prompts/`: the concept card's schema and prompt. `fixtures/`: test data, including real save files (`fixtures/saves/`).
- `modules/`: the functional modules (`crop_farm`, `storage_hall`) and their certificates (`measured.json`).
- `tools/e2e.mjs`: the $0 end-to-end check (its flow registry is in the file, the flow choice in `tools/lib/e2e-flows.mjs`). `tools/nightly.mjs`: the
  nightly. `tools/module-*.mjs`: module measurement and the catalog.
- `assets-src/`: the art pipeline (NPC skins; ported from AgentCraft, see its NOTICE).

## Where things are decided

- `docs/PLAN.md`: the plan, its Decisions table (newest last), "Review and order", "Reviews (2026-10-09)" and the phases. Read the status line first.
- `../LEFTOVERS.md` (outside the repo), "Steward": the live slice queue and leftovers, kept by the manager and the parent session.
- `docs/ARCHITECT-ASKS.md`: what Steward asked of Architect, round by round, with Architect's answers. Architect's side is in `../architect-mc/docs/PLAN.md`,
  and the shared status in `../architect-mc/docs/STEWARD-SYNC.md`.
- `docs/MODULES.md`, `docs/MEASURE-M2.md`, `docs/MEASURE-M4.md`: phase 5's design and the modules' measurements.
- `docs/GPT-REVIEWS-2026-10-09.md`, `docs/STEWARD-REVIEW.md`, `docs/A7-SETTLEMENTS-REVIEW.md`: reviews and what came of them.
- `mod/DEV.md`: how things were run and checked, with results (dev client, paid runs, the e2e check, the UI).
- `docs/archive/AGENTS-HISTORY.md`: the dated text of earlier rules, replaced by the ones below.

## Process

- **Sessions** (Noah, 2026-11-11):
  - The Steward session is the manager. It runs the queue, e2e ports, merges, Architect RCs and pins, the nightly window, and paid-spend asks.
  - Each slice is built in its own visible session on Opus, in its own `_work` worktree. A slice session never merges or pushes main; it
    reports to the manager.
  - The manager sends every question, decision and end point for Noah to the parent session (`local_a69a2186-cbdd-40c1-bdf5-afdbf5a330cc`).
    Slice briefs go there too, to be put up as cards there, not proposed here.
  - Noah's answers relayed from the parent session, quoted as his words, are his decisions.
- Phase 5 is in slices M0-M5, then 5b's M7-M9 (`docs/MODULES.md` §8); earlier phases are in `docs/PLAN.md`, "Phases". Each step ships on its own with
  unit tests, checked in game as its tier says (speed rules 2-3), committed in coherent pieces.
- Architect changes are asked for, never made here: write the ask into `docs/ARCHITECT-ASKS.md` and send it to the Architect session. Steward never edits
  `../architect-mc` or `../lab-ui`.
- Branch work happens in a worktree under `../_work/` (`git worktree add ../_work/steward-<name> <branch>` from this checkout), never in `/tmp` or the
  scratchpad: a reboot wipes those. A worktree has no `.gradle-home`, so its builds and e2e runs set `GRADLE_USER_HOME` to this checkout's.
- Decisions Noah makes go into the Decisions table as they happen. A review's findings are checked against the code before they are acted on.
- Besides the strict tier (speed rules 3 and 10), changes to spending or cancellation get an independent GPT review (the second-opinion skill),
  then a check of the fixes.

## Speed rules

The current set, shared with Architect (consolidated 2026-11-16, Noah via the parent session: "Yes, all three"). Old rule numbers are in
brackets; older docs, commits and certificates cite those (their "speed rule 4" is module re-certification, rule 11 here). The dated
origins are in `docs/archive/AGENTS-HISTORY.md`.

**Tiers and gating**

1. **Risk tiers** [1] (11-13: "yes, apply all of those").
   - **Strict:** saves and persistence, billing, refunds and stock, undo and revert, crash recovery and reconcile, and anything that can
     mint or lose items.
   - **Light:** everything else (UI, wording, tooling, docs, fixtures, $0 prompts).
   - The manager picks the tier in the brief.
2. **Light slices** [11.1, 8] (11-15: "Yes, apply all"): compile, the scoped unit tests (rule 7) and gitleaks. No in-game run of their own
   (the batch's covers them), no verifier, no GPT review. A design-critic look only for new screens.
3. **Strict slices** [11.2, 15, 17, 18] (11-15: "Yes, apply all"; 11-16: "Review per strict slice" and "Add those"):
   - **Persist before mutate:** no world, stockpile or in-memory change affecting items, billing or refunds before a durable, *confirmed*
     record. A queued save or an executor completion isn't persistence. Once Architect's TXN lands, use the transaction layer.
   - **Crash points and conservation:** crash at each save point of the operation, and after every strict scenario assert that stockpile,
     world and ledger balance (items in == items out). Write the tests failing-first; use TXN's harness once it exists.
   - Run only the in-game scenarios for the risk touched (`--only`); no full gate per slice.
   - **Early GPT review** of the slice's own diff in the background once its strict scenarios pass. Fix P1s before it joins a batch.
4. **The review-round guard** [20] (11-16: "Add those"): after a NOT SAFE GPT review, one fix round, then one final GPT pass. If that still
   finds a P1, the slice goes to Noah via the parent: no more rounds, and **never drop a slice from a batch or release without Noah's OK**.
5. **Verify review premises in code** [19] (11-16: "Add those") before designing on a reviewer's framing.
6. **Failing steps only** [2] (11-13: "yes, apply all of those"): after a failure, rerun only the failing flows (`--only`; each run prints `flow <name>` lines). At
   most **3 full runs** per slice; a 4th is the manager's call.
7. **Scoped unit suites** [8] (11-13: "yes, send that to both managers/add to agent/claude docs"): a slice runs the Gradle and npm tests
   for the code it touched, plus their direct dependents. The full `./gradlew build` and `npm test` run at the batch merge. A slice
   touching shared core (persistence, billing, the gateway) runs the full suite.
8. **Timing benches are report-only** [9] (same decision): they record the number and the load, and past their bound they flag the
   manager instead of failing the slice.

**Batches and merges**

9. **Batches of 4-6 ready slices** [11.3] (11-15: "Yes, apply all"), with one full merge suite on the combined result; bisect by slice on failure.
10. **The batch merge** [11.4, 11.5, 15] (11-15: "Yes, apply all"; 11-16: "Review per strict slice"): the full merge suite (rule 9),
    then the strict gate-verifier on fresh runs of the strict flows (never a reused PASS: no `--skip-unchanged`), then the per-batch GPT
    review over the batch's strict diffs. Merge only after all three.
11. **Architect pins skip versions** [11.6] (11-15: "Yes, apply all"): bump the Architect pin every other release, or sooner when a slice needs
    it (QD on 0.73.0, INV and LUI on 0.74.0). A release in between gets a smoke check only: the sanity set plus one field round and billing.
11b. **Module re-certification** [4, 11.6] (11-13: "yes, apply all of those"): re-certify a measured module at a new Architect pin only when its own code changes, or
    Architect's build, billing or realise paths it uses change. Otherwise a smoke carry: re-quote the bounds and run one field round plus
    billing (`tools/module-catalog.mjs record --smoke`; `docs/MEASURE-M2.md` "Re-certification").

**Capacity**

12. **Gate slots** [5, 10, 12] (11-13: "lets ensure that is 15 min max from now on, with high at least 72 cap"; 11-15: "More gate slots +
    ports"):
    - Up to **6** in-game runs at once machine-wide, at least **2 min** between starts.
    - The load gate: a start waits for the 1-minute load to fall to the threshold, which is **72** or more, for **15 min** at most. The
      e2e's defaults are `E2E_LOAD_MAX` 72 and `E2E_LOAD_WAIT_MIN` 15. Going past these bounds needs Noah.
    - Unit suites run under `nice -n 10`. Docs-only and unit-only work takes no slot.
    - The shared slot queue is Architect's `../architect-mc/tools/slot.mjs` (its `docs/GATES.md`, "Outside callers (Steward)"). Steward's
      e2e on main doesn't take a slot from it yet: it counts running Steward clients itself (`E2E_MAX_CLIENTS`, default 6), and its
      `--clients` stagger defaults to 60 s, so pass `--stagger 120`.
13. **Ports** [12]: Steward's are in "Ports" below; Architect's slice, turbo and infra ranges are never Steward's.
14. **Parallel slices** [14] (11-15: "More parallel slices"): the manager keeps 6-8 slices in progress where files and ports allow, and
    pre-briefs the next ones.
15. **Build caches** [24] (11-16: "Add those"): when lab-ui or a pinned dependency changes, refresh every worktree's build before its next run.

**Decisions and spending**

16. **Standing approvals** [13] (11-15: "Standing approvals"): on a product question with a confident recommendation, the manager proceeds
    and sends the parent a one-line note (question, choice, why); Noah can overrule. Still ask first: anything irreversible, anything
    changing what a player loses or pays, a new public surface, new budgets or raised caps, any step over $1, or anything not confident.
17. **Spending** [13, 21]. Noah said on 11-16 that spending can be very loose (subscription usage). **Pending:** rule 21's pre-approval
    isn't live in either repo's docs. Both managers' safety checks refused writing it on a relayed approval, so Noah has to type it in each
    manager's own session. **Until then:** a paid step under $1 inside an approved budget goes ahead with a one-line note to the parent,
    giving its cost; any other paid run goes as a card Noah starts himself. Never route around a session's safety check.
18. **Everything routes through the parent session** (`local_a69a2186-cbdd-40c1-bdf5-afdbf5a330cc`): Noah's questions, decisions and spend.

**Hygiene**

19. **Short as-builts** [7] (11-13: "yes, apply all of those"): a slice's as-built and report are 10-15 lines (what changed, what ran and passed, leftovers).
20. **Review sheets** for Noah [22] (11-16: "Add those"): one self-contained HTML file, with images inlined (JPEG if large).
21. **Run scripts and temp files** [23] (same) stay in the slice's own worktree (or `../_work/steward-<name>-*`), never a shared scratchpad path.
22. **The parent's stall checks** [6] (11-13: "yes, apply all of those") run every ~25 min. The 01:00-03:30 quiet window applies only when a nightly runs.
23. **Nightlies** run by default (11-16, the consolidation). Noah skips one night at a time with `artifacts/nightly/skip-<local date>`
    (`tools/nightly.mjs`; `--dry` says whether tonight would run).

**Always:** slices never merge or push main; gitleaks before any push; kill only your own processes; no Discord pings; never commit secrets.

## Architect RCs and pins (DEV MODE)

Noah, 2026-11-04 and 11-06; the history is in `docs/archive/AGENTS-HISTORY.md`.

- An Architect RC runs only the flows its change touches, plus `node tools/e2e.mjs stub --only gate,wall,survival`, aiming for 15-20 min. The sanity
  set (status, the claim, Expand, the restart) always runs too. The RC's jar comes in through `ARCHITECT_JAR` (`mod/DEV.md`).
- A pin bump to a released Architect version whose RC already passed runs only the sanity set: `node tools/e2e.mjs stub --only sanity`. A release
  that differs from that RC still gets its changed areas run.
- Full stub and free runs are nightly (rule 23), or before a pin when a release is broad; what they find is fixed forward.
- After a test-only fix or a chance failure, rerun only the failed flows (rule 6). No second full e2e pass after a merge to `main`.

## Build and test

- **Java:** from `mod/`, `JAVA_HOME=/opt/homebrew/opt/openjdk@25 GRADLE_USER_HOME=$PWD/../.gradle-home ./gradlew build --offline` (compile, unit tests, jar).
  Architect comes from GitHub Packages (a token with `read:packages`, cached in `.gradle-home` for offline builds), never Maven Local; a release candidate is
  checked with `ARCHITECT_JAR`. lab-ui comes from a clean `../lab-ui` checkout at its release tag (`mod/settings.gradle`), else GitHub Packages. The pins
  are in `mod/gradle.properties` (`architect_version`, `lab_ui_version`).
- **Tools:** `npm test` (`test/*.test.mjs`: the concept card, talk and proposal schemas against the fixtures, and the tools' own tests).
- **End to end ($0):** `node tools/e2e.mjs free`, after the jar is built.
  - `--only word,word` runs only the flows whose name or tags hold a word (and the flows they need), plus the sanity set. `--clients N` runs on N
    clients side by side, and `--skip-unchanged` skips flows unchanged since their last PASS (`mod/DEV.md` "Faster runs").
  - It checks the claim, the steward, Expand, the screens, updates, revert, undo, proposals, districts, talk, growth, modules, survival billing and
    refunds, migrated saves and more. Results go in `artifacts/e2e/summary.json`, screenshots in `artifacts/shots/`.
  - It refuses to start while the claude-login opt-in exists or another Steward client uses `mod/run`.
  - `node tools/e2e.mjs stub` runs the whole paid flow at $0 on Architect's sim helper (`mod/DEV.md`).
- **Art:** `uv run --with pillow==11.3.0 python assets-src/build.py --verify`, then `--sync` to copy into the mod.
- **Dev client:** `./gradlew runClient --offline` from `mod/`; drive it with `node ../architect-mc/tools/devcli.mjs ... --port 8491` and
  `ARCHITECT_GAME_DIR=$PWD/run` (`mod/DEV.md`).

## Ports (don't collide with other sessions)

Steward uses only 8490-8491, 8590-8601 and 9100-9149 (Noah, 2026-11-15). Within those:

- Dev client: 8490 (Architect sidecar) / 8491 (DevBridge); override with `STEWARD_PORT` / `STEWARD_DEV_PORT`.
- E2e client: 8590 / 8591 (`tools/run-e2e-client.sh`).
- Slices: 8592-8601 in pairs, assigned in the brief. `tools/module-measure.mjs` defaults to 8594/8595, `tools/module-measure-hall.mjs` to 8598/8599.
- More slice pairs: 9100-9149, assigned the same way.
- Architect's ranges (gate runners, slice blocks 8896-8939 and 9000-9099, turbo 8940-8999, infra 8912-8915): `../architect-mc/docs/GATES.md`, "Ports".

## Hard rules

- **No Anthropic API key, ever.** A paid run uses Noah's claude login (the sidecar's opt-in `run/architect/sidecar-data/secrets.json`) only for a run Noah
  approved with a spend cap; the opt-in file is created for that run and removed after it. Everything else is $0 (the e2e check, unit tests).
- **No Discord notifications.**
- **Kill only processes you started, by PID.** Never `pkill`/`killall` by pattern: other sessions run Minecraft clients.
- Never commit secrets, logs or `artifacts/` (local evidence only). Run `gitleaks git` over new commits before a push.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR descriptions carry no "Generated with Claude Code" footer.
- No `rm -rf` outside build/temp dirs, no `git reset --hard`, no force-push. Push `main` once its e2e runs pass (for an RC, the change's flows, per DEV MODE above) and `gitleaks` is clean (Noah, 2026-10-27, 2026-11-04); anything else is pushed only when Noah says so.
- Saves are migrated, never broken: a format change keeps old worlds loading (`SettlementStore` migrations, `fixtures/saves/`, `SaveCompatTest`).
- Ported code keeps its licence: files from AgentCraft keep both MIT copyright lines (`assets-src/NOTICE`).
