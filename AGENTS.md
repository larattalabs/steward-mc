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

- Phase 3 is in steps 3a-3f (`docs/PLAN.md`, "Order"). Each step ships on its own with unit tests and an e2e check, committed in coherent pieces.
- Architect changes are asked for, never made here: write the ask into `docs/ARCHITECT-ASKS.md` and send it to the Architect session. Steward never edits
  `../architect-mc` or `../lab-ui`.
- Decisions Noah makes go into the Decisions table as they happen. A review's findings are checked against the code before they are acted on.
- High-stakes or hard changes (spending, persistence, cancellation, migrations) get an independent GPT review (the second-opinion skill), then a check of the fixes.

## Build and test

- **Java:** from `mod/`, `JAVA_HOME=/opt/homebrew/opt/openjdk@25 GRADLE_USER_HOME=$PWD/../.gradle-home ./gradlew build --offline` (compile, unit tests, jar).
  Architect and lab-ui come from Maven Local (`./gradlew publishToMavenLocal` in each) or GitHub Packages (a token with `read:packages`).
- **Tools:** `npm test` (the concept card schema against the fixtures).
- **End to end ($0):** `node tools/e2e.mjs free`, after the jar is built. It runs its own client and checks the claim, the steward, Expand, the screens, an
  update, revert, undo, a migrated old steward and a restored build; results in `artifacts/e2e/summary.json`, screenshots in `artifacts/shots/`. It refuses
  to start while the claude-login opt-in exists or another Steward client uses `mod/run`. `node tools/e2e.mjs stub` waits for Architect slice 0a's stub.
- **Art:** `uv run --with pillow==11.3.0 python assets-src/build.py --verify`, then `--sync` to copy into the mod.
- **Dev client:** `./gradlew runClient --offline` from `mod/`; drive it with `node ../architect-mc/tools/devcli.mjs ... --port 8491` and
  `ARCHITECT_GAME_DIR=$PWD/run` (`mod/DEV.md`).

## Ports (don't collide with other sessions)

- Steward dev client: 8490 (Architect sidecar) / 8491 (DevBridge); override with `STEWARD_PORT` / `STEWARD_DEV_PORT`.
- Steward e2e client: 8590 / 8591 (`tools/run-e2e-client.sh`).
- Architect gate runners: 8890-8905. AgentCraft Foreman: 7880 and 7890-7909.

## Hard rules

- **No Anthropic API key, ever.** A paid run uses Noah's claude login (the sidecar's opt-in `run/architect/sidecar-data/secrets.json`) only for a run Noah
  approved with a spend cap; the opt-in file is created for that run and removed after it. Everything else is $0 (the e2e check, unit tests).
- **No Discord notifications.**
- **Kill only processes you started, by PID.** Never `pkill`/`killall` by pattern: other sessions run Minecraft clients.
- Never commit secrets, logs or `artifacts/` (local evidence only). Run `gitleaks git` over new commits before a push.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR descriptions carry no "Generated with Claude Code" footer.
- No `rm -rf` outside build/temp dirs, no `git reset --hard`, no force-push. Push only when Noah says so.
- Saves are migrated, never broken: a format change keeps old worlds loading (`SettlementStore` migrations, `fixtures/saves/`, `SaveCompatTest`).
- Ported code keeps its licence: files from AgentCraft keep both MIT copyright lines (`assets-src/NOTICE`).
