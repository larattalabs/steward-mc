# Building Steward

Same toolchain as Architect (Java 25, Gradle 9.7.1, Fabric Loom 1.18.2, Minecraft 26.3).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
export GRADLE_USER_HOME=$PWD/../.gradle-home   # an APFS clone of Architect's cache: cp -c -R ../architect-mc/.gradle-home .gradle-home
./gradlew test --offline
```

First build without a cache needs network (Minecraft download and decompile). `.gradle-home/` is gitignored.

## Architect dependency
`build.gradle` depends on `dev.larattalabs:architect_mc:${architect_version}` (gradle.properties, currently 0.11.2, API version 1.8.0; Steward refuses an API older than 1.8 and `fabric.mod.json` asks for `architect_mc >=0.11.0`) from mavenLocal. Build and
publish it from Architect's checkout (or any clone of main at bac28db or later):

```sh
cd ../../architect-mc/mod && ./gradlew build publishToMavenLocal      # -> ~/.m2/repository/dev/larattalabs/architect_mc/<version>/
```

Architect's artifact is also on GitHub Packages (`dev.larattalabs:architect_mc:0.4.0`, public); `build.gradle` has that repository after mavenLocal.
CI (`.github/workflows/ci.yml`) resolves it with the workflow's `GITHUB_TOKEN` (`packages: read`); verified green 2026-10-05, no extra access grant needed.
Outside builders need a PAT with `read:packages` (`GITHUB_ACTOR` + `GITHUB_TOKEN` env vars). The API is `dev.larattalabs.architect.api`; `gateway/ArchitectGateway` is the only class that should touch it for
checks, and `gateway/ConceptCardJob`, `gateway/LotBrief` build its job and design types.

## Dev ports
Agreed with the Architect session (it uses 7890/7891, 7990/7991, 8090/8091, 8190/8191, 8290, 8390/8391): Steward dev runs use **8490** (Architect sidecar,
`ARCHITECT_PORT`) and **8491** (DevBridge, `ARCHITECT_DEV_PORT`); 8590+ if more are needed. `runClient` sets them (override with `STEWARD_PORT` /
`STEWARD_DEV_PORT`). Architect's dev defaults (auto world, sidecar unpack, SDK install) apply to a Steward dev run too.

## Layout
- `src/main/java/.../Steward.java`: common entrypoint (logs the Architect API status).
- `gateway/ArchitectGateway`: Steward's only door to Architect.
- `model/`: pure logic with unit tests: `ConceptCard` (Gson parse of the structured job result), `Tier` (progression from advancements),
  `Difficulty`, `Permission`, `Claim` (the area a settlement may touch), `Settlement` (immutable: card, claim, separate site/style/purpose
  versions, change log with undoable site ids, owner string `steward_mc:settlement/<id>`) and `SettlementStore` (one atomic JSON file per
  world; overlapping claims refused; a corrupt file is an error, never silently emptied).
- `../fixtures` are shared with the schema tests and with `ModelTest` (test resources).
- `gateway/ConceptCardJob`: the `structured` job spec for the concept-card parse (system prompt and schema are loaded from `../prompts` and
  `../schema`, which `processResources` copies into the jar; Sonnet, low effort, 10 cent budget).
- `gateway/LotBrief`: one lot of a settlement into an Architect `DesignRequest` (type, style, size clamped to Architect's cap, owner, ext, notes).
- `layout/VillageLayout` + `Grid`: phase 1 layout, pure and deterministic: lots on both sides of one east-west street, dry and flat enough,
  inside the claim, facing the street. Larger forms are region programs (A5b).

## Smoke test (2026-10-05)
`./gradlew runClient --offline` (ports 8490/8491), then Architect's devcli with `ARCHITECT_GAME_DIR=$PWD/run` (its token is in `run/architect/devbridge.token`):
`node ../architect-mc/tools/devcli.mjs wait --port 8491`, `cmd "/steward status" --port 8491` gave "Steward: Architect API 1.1.0 ok (11 features)" and
"Claude link: up". `/steward card <text>` live run: see "Live card run" below.
- `gateway/GroupPlanner`: concept card + `VillageLayout` plan + bible id into Architect's `GroupRequest` (one item per lot keyed by the lot id; the largest
  lot is the Opus anchor, other landmarks wave 1, the rest Sonnet wave 2; massing first with `approvalUi OWNER`; 3 redirects; shared context with the settlement,
  street and neighbour lots; budget from the card or `BudgetPolicy`; lots past Architect's 24-item cap are reported, landmarks are never dropped).
- `gateway/BatchPlanner`: designed lots + `fitToLot` results into Architect's `Batch` (one item per lot keyed by the lot id, owner and group = the settlement,
  stages: landmarks first then districts of 4 from the street's middle outward, mode INSTANT only for Patron where the world allows it, never an actor);
  lots without a design or with a refused fit are skipped and named. The caller does the `Sites.fitToLot` calls with a live world.
- `BatchPlanner.build(..., includeStreet)`: the village street as an Architect road (`Batch.Item.road`) in a first stage, so approaches stop at it. Roads are instant-only in
  API 1.5.0, so in construction mode the street is left out and `Result.note()` says so.
- `pipeline/Pipeline`: the generation pipeline of one settlement as a pure reducer (`step(state, event, permission) -> {next state, commands}`): card approval, bible,
  bible approval, massing-first group, the player's massing decisions (auto-approved at Autonomous and Full), the soft-budget pause (always asks for money), usage
  holds, fit and queue, done. Terminal states ignore events. A thin adapter will turn commands into Architect calls and Architect events into events; the reducer
  makes no calls, so it is tested exhaustively (13 tests).

## Live card run (2026-10-07)
First real run of the Java path (`/steward card` -> `Jobs.run` -> `JOB_DONE` -> `CardService`) on the personal Claude login (Claude Max), no API key:
the crater prompt gave "Crater Hellmine" in about 6 s for $0.0185 (2 turns, 574 output tokens, 2962 cache-write tokens). Site custom crater (sculpt, L), style infernal,
purpose mining_outpost, no contradictions; the story came back as a one-sentence elaboration of "repurposed" (the earlier WebSocket-level run kept it to the one word).
The result is in `fixtures/real/crater_works_java_path.json` and validates against the schema.

How to repeat it (the login needs care): the dev client's sidecar uses the login only if `run/architect/sidecar-data/secrets.json` contains `{"useClaudeLogin": true}` (personal use;
delete it afterwards), and the client must start from a scrubbed environment so no API key or Claude Code session variable reaches the sidecar:
`env -i HOME="$HOME" USER="$USER" LOGNAME="$USER" TMPDIR="$TMPDIR" SHELL=/bin/zsh PATH=/opt/homebrew/bin:/usr/bin:/bin JAVA_HOME=... GRADLE_USER_HOME=... ./gradlew runClient --offline`
(without USER/LOGNAME the keychain login is not found). The published 0.8.0 jar bundles the sidecar; a jar built from a tag clone needs `npm ci && npm run build` in `sidecar/` first.
- `GroupPlanner` now gives every item a REPORT-only critique (Architect 0.9.0 `CritiqueSpec`) with up to three lot-specific criteria; `Options.withCritiqueReport(false)` turns it off.
  `BudgetPolicy.estimateWithCritiqueReports` adds $0.05-0.15 per building. The revision loop is not used (it failed Architect's gates).
- `gateway/UpdatePlanner` (Architect 0.10.0 / API 1.7.0 delta apply): turns a `checkDelta` preview into APPLY / ASK / BLOCKED / NOTHING by permission level, with the inbox text
  ("2 parts changed (+wing, roof), 44 blocks, 2 edited blocks kept, needs 140 dirt"). Survival deltas (non-empty BOM) always ask; creative upgrades auto-apply at Autonomous and Full;
  player edits are KEPT (REFUSE only at Observer). Polish failed Architect's gate, so new versions come from re-designs, not polish.
- `item/FoundingStone`, `service/Settlements`, `entity/StewardNpc`: the stone claims 129x129 around the clicked block (per-world `steward-settlements.json`, overlaps refused) and spawns the steward, a vanilla
  `minecraft:mannequin` (persistent, player-shaped, immovable, tagged `steward_mc.steward` and `steward_mc.settlement.<id>`) through the summon command. Right-clicking it (Fabric `UseEntityCallback`) reports the
  settlement and, if a build is running in this session, its pipeline status (main hand only: the off-hand repeat is swallowed, `StewardNpc.use`). The name is set in Java after the summon, and `spawn` checks the
  tagged entity exists. Checked in a client: claim, NPC visible, both survive a restart. The right-click path itself was not driven (no use-entity hook in DevBridge).
- `SettlementRunner`: one runner per settlement (`busy`), Architect events routed through one set of listeners registered at init, all runners dropped on `SERVER_STOPPED`, the player found by UUID when it speaks,
  ids from acks re-read once (an event can fire before the ack), the world's survival toggle decides the placement mode. Dev and cheat commands (`claim`, `survey`, `card`, `build`, `resume`) need permission level 2.
- Decisions (stopgap until the inbox): `Pipeline.awaiting(state)` says what the build waits for (bible, massings, budget, placement). `/steward approve <id>` answers it (at placement it approves every planned
  stage of the batch's site group with `Sites.approveStage`), `redirect <id> <lot> <notes>` sends one massing back, `raise <id> <usd>` lifts a soft-budget pause, `cancel <id>` stops the build (a running batch is
  cancelled; placed sites stay). Notifications that need a decision carry the command to type, and right-clicking the steward repeats it.
- Building program (generative-first): the concept card's `program` lists what the settlement needs in its own terms (role, an Architect preset or open snake_case type, count, footprint S-XL, landmark,
  notes). `gateway/ProgramPlanner` turns it into lots: honoured landmarks first (capped: the size's typical count, at most one per four buildings), the ordinary entries round-robin so a smaller run keeps the
  mix, larger runs repeat them, lot depth includes `LotBrief.APPROACH_MARGIN`, readable lot ids (`slag_foundry_1`). Open types go to Architect as types (API 1.2.0+), not `custom`. A card without a program
  (described before programs existed) falls back to the generic mix and the steward says so. `describe` prints the program and a ready start command with the estimate.
- Builds survive restarts: `service/BuildStore` keeps each unfinished build in `<world>/steward-builds.json` (atomic, format 1, a corrupt file is left untouched and saving stops), saved after every
  pipeline step and whenever an Architect id arrives. Builds are restored at SERVER_STARTING (before Architect's SERVER_STARTED catch-up events, whichever mod's listener runs first) and re-synced on the
  first tick after SERVER_STARTED (`server.execute` would run inline on the server thread). `pipeline/ResyncRules` decides from what Architect kept (groups, queued batches and their planned stages,
  site groups, placed sites; not finished batches): re-read the group, wait for the batch, adopt a running batch, finish from the batch or from this build's sites (every placed item carries
  `ext.steward_mc:build`, so an earlier build's sites never count), queue again, or tell the player the restart interrupted a request. A finished build logs PROJECT_PLACED with its site ids on the
  settlement; what the steward said while its player was away is delivered when they join. Settlements now load at SERVER_STARTING too.

## Live check (2026-10-09, Opus review follow-up)
Dev client, claude login, two card runs ($0.043 total), everything else $0:
- **Restart:** a builds file with one build at massing approval was restored at SERVER_STARTING (logged before Architect's placement queue loaded), re-synced on the first tick
  (`REREAD_GROUP`; the fake group was reported as interrupted), and both messages reached the player on join. `/steward cancel` dropped it from `steward-builds.json`.
- **Decision commands:** `status`, `approve`, `redirect`, `cancel` answer as designed. A refused approval used to leave the pipeline past the decision; it now forgets the decision and
  re-reads the group (only where the player decides, so automatic approvals cannot loop).
- **Card program:** the crater prompt (`fixtures/real/crater_works_program.json`, $0.028) gave a sound program but echoes the system prompt's worked example, so it proves little. An unseen
  prompt, "a quiet lakeside town of glassblowers and lantern makers" (`fixtures/real/lantern_shore_program.json`, $0.015), gave a glassworks landmark, glassblower and lantern-maker
  workshops, artisan cottages, a market, an inn and a boathouse: specific to the place, no generic chapel.
- **Steward NPC:** spawning through the summon command failed when run from inside a command (`/steward claim`): vanilla queues a nested command until the outer one ends, so the
  entity was missing when checked and was never named. The mannequin is now loaded from entity data and added in Java. Checked: named gold "Steward", tagged, the claim says it appeared.
  The right-click itself still cannot be driven from DevBridge.
- Undo: `/steward undo <id>` (`service/Undo`) removes the newest placed project's sites newest first through `Sites.remove` as the settlement's owner (journal-backed, the land restored exactly),
  stops at the first site Architect refuses (the player's things in its box) and logs PROJECT_REMOVED; `Settlement.lastUndoable` leaves the rest of a partly undone project undoable. The street
  road is logged with its build, so undo removes it too. Refused while the settlement is being built.
- Massing approval ghosts: when massings wait for the player, the runner sends them to the client (`net/StewardNet` `steward_mc:show_layers`; the client calls Architect's
  `ArchitectClientApi.previewComposite` in the MASSING tint) placed on their lots by `gateway/MassingPlacement` (centred, set back by the approach margin, turned to face the street; a
  preview approximation, the real spot comes from `fitToLot`), lists each in chat (lot, role, size, named parts) and clears them once decided. `/steward show|hide <id>` toggles them.
  Checked in a client 2026-10-09 with the earlier run's four real massings: shown, listed, rotated toward the street, hidden.
