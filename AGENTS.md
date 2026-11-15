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
- `tools/e2e.mjs`: the $0 end-to-end check.
- `assets-src/`: the art pipeline (NPC skins; ported from AgentCraft, see its NOTICE).

## Where things are decided

- `docs/PLAN.md`: the plan, its Decisions table (newest last), "Review and order", "Reviews (2026-10-09)" and the phases. Read the status line and the order first.
- `docs/ARCHITECT-ASKS.md`: what Steward asked of Architect, round by round, with Architect's answers. Architect's side is in `../architect-mc/docs/PLAN.md`.
- `docs/GPT-REVIEWS-2026-10-09.md`, `docs/STEWARD-REVIEW.md`, `docs/A7-SETTLEMENTS-REVIEW.md`: reviews and what came of them.
- `mod/DEV.md`: how things were run and checked, with results (dev client, paid runs, the e2e check, the UI).

## Process

- **Sessions** (Noah, 2026-11-11):
  - The Steward session is the manager. It runs the queue, e2e ports, merges, Architect RCs and pins, the nightly window, and paid-spend asks.
  - Each slice is built in its own visible session on Opus, in its own `_work` worktree. A slice session never merges or pushes main; it
    reports to the manager.
  - The manager sends every question, decision and end point for Noah to the parent session (`local_a69a2186-cbdd-40c1-bdf5-afdbf5a330cc`).
    Slice briefs go there too, to be put up as cards there, not proposed here.
  - Noah's answers relayed from the parent session, quoted as his words, are his decisions.
- Phase 5 is in slices M0-M5, then 5b's M7-M9 (`docs/MODULES.md` §8); earlier phases are in `docs/PLAN.md`, "Phases". Each step ships on its own with unit tests and an e2e check, committed in coherent pieces.
- Architect changes are asked for, never made here: write the ask into `docs/ARCHITECT-ASKS.md` and send it to the Architect session. Steward never edits
  `../architect-mc` or `../lab-ui`.
- Branch work happens in a worktree under `../_work/` (`git worktree add ../_work/steward-<name> <branch>` from this checkout), never in `/tmp` or the
  scratchpad: a reboot wipes those. A worktree has no `.gradle-home`, so its builds and e2e runs set `GRADLE_USER_HOME` to this checkout's.
- Decisions Noah makes go into the Decisions table as they happen. A review's findings are checked against the code before they are acted on.
- High-stakes or hard changes (spending, persistence, cancellation, migrations) get an independent GPT review (the second-opinion skill), then a check of the fixes.
- **Speed rules** (Noah, 2026-11-13: "yes, apply all of those"; they apply to running slices too):
  - **Risk tiers.**
    - **Strict:** saves and persistence, billing, refunds and stock, undo and revert, crash recovery and reconcile, and anything that can
      mint or lose items. These keep the gate-verifier and, where the rules ask for one, the GPT review.
    - **Light:** everything else (UI, wording, tooling, docs, fixtures, $0 prompts). Unit tests plus one targeted in-game flow, with no
      verifier and no GPT review. New screens still get a design-critic look.
    - The manager picks the tier in the brief when it's unclear.
  - **Failing steps only.** After a failure, fix it and rerun only the failing flows (`--only`). The full gate runs once at the end. Three
    full runs per slice at most; a fourth is the manager's call.
  - **Batched merges.** The manager merges 2-4 ready slices together and runs one merge suite on the combined result. If it fails, bisect by
    slice.
  - **Module re-certification.** A measured module is re-measured at an Architect pin only when its own code changes, or when the Architect
    build, billing or realise paths it uses change. A plain version bump gets a smoke check: one field round plus the billing check.
  - **Capacity.** Up to 6 in-game runs at once machine-wide, with no two starting within 2 minutes of each other (rule 12 below). Unit
    suites run under `nice -n 10`. Docs, kit-only and unit-only work take no slot.
  - **Short as-builts.** A slice's as-built and its report are 10-15 lines: what changed, what ran and passed, and the leftovers.
  - **Scoped unit suites** (Noah, 2026-11-13: "yes, send that to both managers/add to agent/claude docs"):
    - A slice runs only the Gradle and npm tests for the code it touched, plus their direct dependents.
    - The full `./gradlew build` and `npm test` run once, at the batch merge.
    - A slice that touches shared core (persistence, billing, the gateway) runs the full suite.
  - **Timing benches report only** (same decision): a perf or timing assertion becomes a report step that records the number and the load.
    Past its bound, it flags the manager instead of failing the slice.
  - **Load gate** (Noah, 2026-11-13: "lets ensure that is 15 min max from now on, with high at least 72 cap"): a load-aware start waits at most
    15 minutes, and the load threshold is 72 or more. The e2e client's defaults are `E2E_LOAD_MAX` 72 and `E2E_LOAD_WAIT_MIN` 15.
  - **Deferred verification** (Noah, 2026-11-15: "Yes, apply all"; it overrides the rules above where they conflict, and applies to running
    slices too):
    - **Light tier:** no in-game run per slice. Compile, the scoped unit tests and gitleaks, then the slice joins the next batch, whose in-game
      run covers it.
    - **Strict tier:** only the in-game scenarios for the risk the slice touches (item loss, billing, refund, undo or crash). No full gate
      per slice; the batch merge runs it.
    - **Batches** of 4-6 ready slices, with one full merge suite on the combined result. If it fails, bisect by slice.
    - **The strict gate-verifier and the GPT review** run once per Architect release or Steward batch merge, over all the strict diffs it
      holds, not once per slice.
    - **Architect pins skip versions:** bump the pin every other release. At other releases, run a smoke check only (one field round plus
      billing), unless a module's own code or Architect's realise or billing paths changed.
    - Unchanged: the targeted strict scenario on every strict slice, scoped unit tests, gitleaks, at most 3 full runs, $0 without Noah's
      approval, and slices never merge or push main. Nightlies stay skipped until Noah says otherwise.
  - **Gate capacity, standing approvals, parallel slices** (Noah, 2026-11-15: picked "More gate slots + ports", "Standing approvals" and
    "More parallel slices"; not "GPT review early"):
    - **Gate capacity:** up to 6 in-game runs at once machine-wide, 2 minutes between starts. The load-gate bounds are unchanged. Steward
      may also use ports 9100-9149 (in pairs), besides its own.
    - **Standing approvals:** a paid step under $1 inside a budget Noah already approved goes ahead, with a one-line note to the parent
      giving its cost. A product question with a confident recommendation goes ahead on it, with a one-line note (question, choice, why);
      Noah can overrule. Still asked first: anything irreversible, anything changing what a player loses or pays, a new public surface,
      new budgets or raised caps, and any step over $1.
    - **Parallel slices:** the manager keeps up to 6-8 slices in progress at once, as far as files and ports allow, and pre-briefs the next
      ones so a free slot never sits empty.
    - Unchanged: the strict tier, the per-batch GPT review and gate-verifier, $0 unless approved, and slices never merge or push main.
- **Release-candidate testing is DEV MODE** (Noah, 2026-11-04):
  - An Architect RC runs only the flows its change touches, plus the sanity set: `node tools/e2e.mjs stub --only gate,wall,survival`, aiming for 15-20 min.
  - Full stub and free runs are nightly, or before a pin when a release is broad; what they find is fixed forward.
  - After a test-only fix or a chance failure, rerun only the failed flows (each run prints `flow <name>` lines).
  - Skip the gate-verifier for branches that touch only tests or wording; keep it for survival billing, undo, removal and crash recovery.
  - No second full e2e pass after a merge to `main`.
  - A pin bump to a released Architect version whose RC already passed runs only the sanity set: `node tools/e2e.mjs stub --only sanity` (Noah, 2026-11-06).
    A release that differs from that RC still gets its changed areas run.

## Build and test

- **Java:** from `mod/`, `JAVA_HOME=/opt/homebrew/opt/openjdk@25 GRADLE_USER_HOME=$PWD/../.gradle-home ./gradlew build --offline` (compile, unit tests, jar).
  Architect comes from GitHub Packages (a token with `read:packages`, cached in `.gradle-home` for offline builds), never Maven Local; a release candidate is checked with `ARCHITECT_JAR`. lab-ui comes from a clean `../lab-ui` checkout at its release tag (settings.gradle), else GitHub Packages.
- **Tools:** `npm test` (the concept card and talk schemas against the fixtures).
- **End to end ($0):** `node tools/e2e.mjs free`, after the jar is built (`--only word,word`: only the flows whose name or tags hold a word, plus the sanity set). It runs its own client and checks the claim, the steward (its nameplate, bubble and "!"), Expand,
  the screens, an update (the steward walks to it), revert, undo, the steward sitting and sleeping, proposals, a protected area and a district with its road, talking to the steward, a migrated old steward and a restored build; results in `artifacts/e2e/summary.json`, screenshots in `artifacts/shots/`. It refuses
  to start while the claude-login opt-in exists or another Steward client uses `mod/run`. `node tools/e2e.mjs stub` runs the whole paid flow at $0 on Architect's sim helper (`mod/DEV.md`).
- **Art:** `uv run --with pillow==11.3.0 python assets-src/build.py --verify`, then `--sync` to copy into the mod.
- **Dev client:** `./gradlew runClient --offline` from `mod/`; drive it with `node ../architect-mc/tools/devcli.mjs ... --port 8491` and
  `ARCHITECT_GAME_DIR=$PWD/run` (`mod/DEV.md`).

## Ports (don't collide with other sessions)

- Steward dev client: 8490 (Architect sidecar) / 8491 (DevBridge); override with `STEWARD_PORT` / `STEWARD_DEV_PORT`.
- Steward e2e client: 8590 / 8591 (`tools/run-e2e-client.sh`).
- Architect's ports: see `../architect-mc/docs/GATES.md`, its port table (gate runners, slice pairs, turbo). Steward uses only 8490-8491, 8590-8601
  and 9100-9149 (Noah, 2026-11-15).

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
