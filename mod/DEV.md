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
- Undo: `/steward undo <id>` (`service/Undo`) removes the newest placed project with `Sites.removeGroup` when its site group holds only that project (Architect's proven path), else its sites newest first through `Sites.remove`, as the settlement's owner (journal-backed, the land restored exactly),
  stops at the first site Architect refuses (the player's things in its box) and logs PROJECT_REMOVED; `Settlement.lastUndoable` leaves the rest of a partly undone project undoable. The street
  road is logged with its build, so undo removes it too. Refused while the settlement is being built.
- Massing approval ghosts: when massings wait for the player, the runner sends them to the client (`net/StewardNet` `steward_mc:show_layers`; the client calls Architect's
  `ArchitectClientApi.previewComposite` in the MASSING tint) placed on their lots by `gateway/MassingPlacement` (centred, set back by the approach margin, turned to face the street; a
  preview approximation, the real spot comes from `fitToLot`), lists each in chat (lot, role, size, named parts) and clears them once decided. `/steward show|hide <id>` toggles them.
  Checked in a client 2026-10-09 with the earlier run's four real massings: shown, listed, rotated toward the street, hidden.

## Phase 1 gate run (2026-10-09)
Dev client, claude login (`secrets.json` opt-in, removed afterwards), a plains point by a lake at 401,941. Flow: `/steward claim` (dev), `describe` (card $0.028, program of 10),
`start set_4 8 30` (bible, approve), 8 massings as ghosts (approve), quit and relaunch during the detail designs (restored at SERVER_STARTING, `REREAD_GROUP`, carried on), soft pause at
$26.38 of $30 (raise to $35; paused again at once, now refused below `Pipeline.minimumRaise`), placement approval, 8 buildings placed, the street refused (TOO_STEEP past the outer lot,
the run-out is gone now), `/steward undo` (`removeGroup` on g4: every stage undone; the land matches the before shot). Measured: bible $1.16, massings $1.51 (8), landmark detail $3.72,
ordinary details $2.50-4.60; meter $31.03. See docs/PLAN.md phase 1 for what is still open.
- In-game UI (2026-10-09), on Architect's client kit ported into `client/{ui,text,hud}` (panels, text field, buttons, keys; `steward_mc` sprites): `screen/KitScreen` (kit buttons with number
  keys, one text field, never pauses or blurs), `InboxScreen` (`Y`; every build in progress, left/right between them; approve the bible, approve or redirect massings with notes and toggle
  their ghosts, raise a paused budget from the minimum that clears the pause, place, cancel asks twice), `DescribeScreen` (the player's words; a failed card reopens it with the words),
  `CardScreen` (the card and its program, buildings -/+ and budget with the runner's own estimate, Start), and the HUD line top left while something waits. The server sends the inbox after
  every step, on join and after every decision (`ClientInbox` holds it; screens never change it themselves). The Founding Stone opens the describe screen; the steward opens describe, inbox or
  card (`Actions.openFor`). Dev: `/steward ui <inbox|describe|card|open|sample> <id>` (sample = a made-up inbox for screenshots). Checked in a client: every screen, keyboard driven, and a real
  describe through the screen ($0.045, `fixtures/real/smugglers_cove_program.json`).

## Rerun through the screens (2026-10-09)
Greywater Hamlet on the gate run's plains point, claude login, everything through the screens: describe screen ($0.013, a 6-building program), card screen (3
buildings by the left arrow, budget $20 from the estimate, Start by Enter), the HUD line, the inbox by `Y` for the bible, the massings (ghosts on their lots) and
placement. 3 buildings and the street placed (the street was refused on this slope before the run-out was removed). Spend $13.76: bible $1.36, the smokehouse landmark
$4.03, the net racks $4.09 (a small building at landmark price: what C1/C2 address), the cottage about $3.7, massings and critiques the rest. About 70 minutes, the
landmark designed alone first, then the other two. Fixed after: the finished message counted the street as a building.
- Placement awareness (2026-10-09, before Architect 7a's site analysis takes over): program entries carry an optional `placement` (near_water, central, edge,
  high_ground; the card screen shows it); `VillageLayout` orders central first and edge last, tries both street sides and directions, and scores each plan on the
  hints (distance to water from the survey, to the claim's centre, ground height). The survey's `natural` mask marks built ground (the player's builds, earlier
  sites) and no lot touches it. Each building's report critique names its two nearest neighbours. A live card ("a riverside mill town ...") filled the hints sensibly
  (`fixtures/real/mill_town_placement.json`).
- Claims (2026-10-09, PLAN "Claims and growth" steps 1-2): `model/ClaimRules` (pure) sizes a claim from the card when it is described (S 97, M 129, L 193, XL 257
  across), only ever growing, taking the largest size that overlaps no other settlement and naming the neighbour that stopped it. Expand (card screen button 3,
  `/steward expand <id>`) grows one step, refused past XL ("larger settlements grow by districts") or onto a neighbour. The runner surveys and lays out in the
  settlement's own claim. Checked in a client: 129 to 193 by the card's button, 257 by the command, then refused.
- Update available (2026-10-09): `service/Updates` finds a settlement's outdated buildings at world load (`Sites.outdated`, after Architect's SERVER_STARTED) and on
  `ENTRY_VERSIONED`, checks each delta (as the settlement's owner: without it Architect refuses OVERLAP_OWNED, a bug of the never-run `UpdatePlanner` found live), and by
  the permission level applies it at once (Autonomous and Full, no materials) or offers it in the inbox ("update available": per building Update / Preview, Update
  all, Skip these versions; the preview is Architect's delta ghost). The player's edits are kept. Applied updates are logged on the settlement. `/steward updates`
  looks again (Architect's dev `installVersion` does not fire ENTRY_VERSIONED). Placements now record `steward_mc:role` so updates name the building. Checked in a
  client at $0: a hand-made v2 of Greywater Hamlet's cottage (a loom and a barrel; built and checked by the kit, installed with `dev.entry.installVersion`) was
  found at world load, offered, applied from the inbox, logged; the site is at v2 with nothing left to apply.


## End-to-end check ($0)

`node tools/e2e.mjs free` launches its own client (`tools/run-e2e-client.sh`: ports 8590/8591, the flat creative world "Steward E2E", no credentials). It
runs 11 checks:
- status;
- claim and the steward NPC;
- Expand twice;
- the inbox screen;
- a kit-built building placed, then updated to a new version from the inbox, then undone;
- a build restored after a restart (with the player's real UUID put into `tools/e2e/restore-build.json`), then cancelled.

It refuses to start while the claude-login opt-in exists or another Steward client uses `mod/run`. The test entry `e2e_stub` is rewritten each run, so its
new version always differs from the placed one. Results go to `artifacts/e2e/summary.json`. On a failed update it prints Steward's `update check` log
line (action, reason, write box).

`node tools/e2e.mjs stub` runs the whole flow against Architect's stub helper; it works once Architect's slice 0a ships the stub (C4).

Since then: the save format, the settlement screen and building panel, revert, the steward entity (walking to an updated building, sitting on a stair, sleeping in
a bed, a migrated old mannequin), its nameplate with the "!" and its speech bubble, each with a screenshot in `artifacts/shots/`.

Then:
- the in-world interface: the look-at label, survey mode, a labelled massing ghost, the ledger and the board;
- proposals: `/steward dev describe` gives the settlement a sample card; the player carries seeds; within a minute a farm is proposed, shown in the inbox, then declined and remembered.

Last run: 2026-10-10: **PASS 24 checks**.

## lab-ui

lab-ui (`dev.larattalabs:lab_ui`), the shared client UI library, is bundled jar-in-jar. `mod/settings.gradle` decides where Gradle gets it, with the same rule as
AgentCraft and Architect:
1. **A lab-ui checkout at the release.** It is used only when it is clean and its HEAD is exactly at the tag `v<lab_ui_version>`. Checkouts are looked for at
   `../../lab-ui` from `mod/` and at `~/Developer/LarattaLabs/lab-ui`.
2. **For lab-ui development,** `-Plab_ui.dir=<path>` or `LAB_UI_DIR=<path>` forces a checkout, with an UNRELEASED warning (don't ship that build).
   `-Plab_ui.dir=none` turns checkouts off.
3. **Otherwise the release from GitHub Packages,** fetched once into the Gradle cache, after which `--offline` works:
   `GRADLE_USER_HOME=$PWD/../.gradle-home GITHUB_TOKEN=$(gh auth token) ./gradlew build` from `mod/`.
   Never commit or export the token.

Maven Local never supplies lab-ui, because a local publish may come from a checkout past its tag. Fetched this way on 2026-10-10: the release jar differed
from the local publish.
