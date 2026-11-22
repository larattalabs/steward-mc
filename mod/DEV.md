# Building Steward

Same toolchain as Architect (Java 25, Gradle 9.7.1, Fabric Loom 1.18.2, Minecraft 26.3).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
export GRADLE_USER_HOME=$PWD/../.gradle-home   # an APFS clone of Architect's cache: cp -c -R ../architect-mc/.gradle-home .gradle-home
./gradlew test --offline
```

First build without a cache needs network (Minecraft download and decompile). `.gradle-home/` is gitignored.

**In a `_work` worktree** (it has neither `.gradle-home` nor `node_modules`): builds and e2e runs set `GRADLE_USER_HOME` to the main checkout's
`.gradle-home`, and `npm test` needs the main checkout's `node_modules`, linked once from the worktree's root (`.gitignore` covers the link):

```sh
ln -s ../../steward-mc/node_modules node_modules
```

## Temp files on the RAM disk (2026-11-14)
`tools/e2e.mjs` and `tools/nightly.mjs` point TMPDIR at `/Volumes/MCTmp/tmp/steward` when that RAM disk exists. Their clients and builds inherit it. Noah approved the disk: a 24 GB volume made at login by the LaunchAgent `com.laratta.mctmp-ramdisk`, whose contents go at a reboot (`tools/lib/ram-tmp.mjs`).
- Without the volume, or with `STEWARD_RAM_TMP=0`, TMPDIR stays as it was.
- The game's run dirs (`mod/run`) stay on disk: the e2e reads its world, logs and screenshots there, and a failed night's world is kept for diagnosis.
- **The sidecar's data** (`mod/run/architect/sidecar-data`) is on the RAM disk too (11-14). The machine audit measured about 3 GB written per worktree per 30 min there. e2e's preflight makes it a symlink to `/Volumes/MCTmp/tmp/steward/sidecar-<hash>`, one per checkout (each run dir has its own), emptied at each run's start when it is the run's to empty, and again at its end ("Temp dirs" below).
  - An on-disk directory found there is renamed to `sidecar-data.ssd`, never deleted.
  - Without the volume, a link left dangling by a reboot becomes an empty directory again (`ramSidecarData`).
  - A dev client in the same checkout follows the link. The opt-in `secrets.json` check reads through it.

## Architect dependency
`build.gradle` depends on `dev.larattalabs:architect_mc:${architect_version}` (gradle.properties) from GitHub Packages, the released artifact.
Maven Local never supplies it (nor lab-ui): an Architect slice keeps main's `mod_version`, so a local publish would shadow the release under its
coordinates. A release candidate is checked with `ARCHITECT_JAR` (or `-Parchitect.jar`), for dev and e2e only.

The pin sets the lower end of `fabric.mod.json`'s `architect_mc` range; its upper end is `<1.0.0`, below the next major API (Noah, 2026-11-13).
At runtime `ArchitectGateway.check()` reads the loaded Architect's API version (`apiVersion()` once Architect has it, else the loaded `ArchitectApi.VERSION`
by reflection) and reports a major other than 1, or a minor below the pinned API's (taken from the `ArchitectApi.VERSION` it compiled against), as not ok:
the startup log says so and `/steward status` fails. It does not stop Steward; Fabric's range is what refuses to load.

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

It refuses to start while the claude-login opt-in exists or another Steward client uses `mod/run`. Each run installs its own test entries
(`e2e_stub_<tag>`, `..._ui`), so a new version always differs from the placed one; the fixed `e2e_stub` is written only where its bytes differ, never removed. Results go to `artifacts/e2e/summary.json`, with each named flow's seconds, the 1-minute load at its start, its checks and failures (`flows`), and the seconds before the first flow (`sanitySeconds`; slice ET, 2026-11-14). On a failed update it prints Steward's `update check` log
line (action, reason, write box).

**A fresh world every run (2026-11-12).** The world used to be kept between runs. The main checkout's had grown to 559 settlements and 9.4 GB, and
`/steward status` got empty replies.
- **What happens** (since E2E-DISK-2, 11-21). Each run's world is its own, `Steward E2E <tag>` (the run's tag: time, pid, random), created by
  the client's autoworld; the restarts inside the run reopen it. Nothing is moved or deleted at the start (the old `(last)` rotation is gone);
  the run deletes its world at its end when it passed ("Disk" below). The dev world and Architect's game-level `mod/run/architect` (library,
  `api-*.json`) are never touched.
- **`--keep-world`** opens the fixed `Steward E2E` (or `ARCHITECT_AUTOWORLD_NAME`) instead, to look at a run's leftovers; it is never deleted.
- **The client check** looks only for this checkout's own client (`<checkout>/mod/...KnotClient`). A worktree's run is no longer refused when the main
  checkout's client is up, and its own leftover client is caught.
- **Load.** The slot queue's load gate (below) decides when the client starts. Steward's own load wait (`E2E_LOAD_MAX`, default 72;
  `E2E_LOAD_WAIT_MIN`, 15) runs only with `E2E_SLOTS=off`.
- **A slot from Architect's queue** (slice SQ, 2026-11-16; Noah, 11-15: "Steward joins the queue"). See "The slot queue" below.
- **Another checkout's ports:** set `STEWARD_PORT` / `STEWARD_DEV_PORT` (e.g. 8598/8599).

`node tools/e2e.mjs stub` runs the whole paid flow at $0 on Architect's helper in sim mode (v0.12.2: `ARCHITECT_SIDECAR_BACKEND=sim`, notional measured
costs, never Claude). Job answers are scripted with `/steward dev sim_answer card|talk <json>` (`ext["architect:simAnswer"]`, checked against the job's schema).
It covers:
- (0.13.0) a bible request whose acknowledgement is dropped (`dev.api.dropAck`), the game restarted before its id is saved: found by its operation key and picked up;
- describing through the card job (the stilt village fixture), whose card says "Not yet: Buildings over water…" before Start;
- a 7-building build with a restart at the massings: two fisher's huts copy the first hut's design ($0, Architect 0b), and the S lots run the SMALL pass;
- the bible kept;
- seeds carried: the rules propose a farm, and the wording job (scripted) names it "A reed-thatched granary on stilts";
- a talk job asking for a library, accepted and built in the reused bible;
- a change request asked in talk, accepted, designed by `versionOf` as the library's next version, updated to v2, reverted to v1;
- a district proposed in talk and founded;
- a building in the district laying its street and the link road;
- undo.

First passed 2026-10-10: **PASS 20 checks**. Latest: PASS 22 on 0.12.3 (with the card's limits). On Architect's 0a RC (v0.13.0, 2026-10-10): stub 24 checks with the change request noted (0a has no versionOf), and free 32 of 33, the board check its only miss; PASS 22 on Architect's versionOf RC (`ARCHITECT_JAR=.../architect_mc-0.14.0-rc-versionOf-695299f.jar node tools/e2e.mjs stub`).

`ARCHITECT_JAR` (or `-Parchitect.jar`) builds and runs Steward against a local Architect jar, for the release candidates Architect asks Steward to check. Dev and e2e only; the build warns UNRELEASED. The run found two bugs, both fixed:
- **The survey limit:** a radius-128 claim was surveyed as 257 x 257, past Architect's 256 x 256 full-resolution limit, so the survey came back at one column in four and the layout refused it. Builds now survey 255 x 255 at most (`ClaimRules.SURVEY_RADIUS`).
- **A silent stall:** the exception was swallowed by the survey's future, so the build stopped without a word. The callback now says why it stopped.

Sim limitation, fixed in Architect v0.12.3: an S lot (11 x 9) always failed in the sim, because its stand-in massing was the kit cabin. The stub run uses S buildings again.

Since then: the save format, the settlement screen and building panel, revert, the steward entity (walking to an updated building, sitting on a stair, sleeping in
a bed, a migrated old mannequin), its nameplate with the "!" and its speech bubble, each with a screenshot in `artifacts/shots/`.

Then:
- the in-world interface: the look-at label, survey mode, a labelled massing ghost, the ledger and the board;
- proposals: `/steward dev describe` gives the settlement a sample card; the player carries seeds; within a minute a farm is proposed, shown in the inbox, then declined and remembered;
- districts (3f): a protected area across the west road's way refuses that district by name; the east district is founded with its road ($0), seen in survey mode, and undone (the road, then the district); a district proposal accepted founds one;
- talking (3b): "what are you doing?" answered for $0; made-up talk answers (`/steward dev talk_answer`, the `fixtures/talk` answers) put a library and a harbour district in the inbox and note a change to the tavern; the talk box with "hello" typed into it and answered.

Last run: 2026-10-11: **PASS 34 checks** on Architect 0.15.0 (a building placed into a protected area is refused PROTECTED by Architect). Before: **PASS 33 checks**, twice in a row: the Founding Stone's recipe added, and the board check fixed. The board stood at the edge of reach, about 4.4 blocks away, so its right-click missed about half the time; it is a block closer now. That was not the whole cause: it missed once more from 3.4 blocks, with the steward behind the player. It is still intermittent and under watch. The board itself works by hand and in most runs.

### The slot queue (slice SQ, 2026-11-16, $0)

With 6 in-game runs plus builds, load reached 83-142 and runs stalled. Steward's in-game clients now take their slots from Architect's
machine-wide queue (`../architect-mc/docs/GATES.md`, "Outside callers (Steward)"). That queue allows 6 runs at once. It has the load gate
(max1 72, settle 120 s), serves tickets in FIFO order, and keeps a turbo run exclusive. Steward keeps no count or start gap of its own.

- **How.** `tools/lib/slot-queue.mjs` calls Architect's CLI: `node $ARCHITECT_DIR/tools/slot.mjs acquire --who steward --worktree <checkout>
  --label <flow or step> --pid <driver pid>`, then `release --ticket <t>`. `ARCHITECT_DIR` defaults to `ARCHITECT_CHECKOUT`, else
  `../architect-mc`. That checkout must be at v0.74.0 or later, the first release with `tools/slot.mjs`.
  - A missing CLI is an error that names the fix.
  - `E2E_SLOTS=off` takes no slot (unit tests and odd cases), and says so loudly. It brings back Steward's own load wait and the 60 s stagger.
- **What takes a slot.**
  - `e2e.mjs`: one slot per client launch, taken after the preflight. It is held across the run's client restarts and released when the
    client is done for good.
  - `--clients` leads: each client's slot is held by the lead and handed to the client's run (`E2E_SLOT_TICKET`).
  - `module-measure.mjs` and `module-measure-hall.mjs` take one per `startClient`, and `module-probe.mjs` and `module-catalog.mjs ingame`
    take one too. Each is released at `stopClient`; with `--keep-client`, when the driver ends. `--attach` takes none.
  - `nightly.mjs`: one per e2e step (free, stub), held by the nightly and handed to the step's e2e.
- **One acquire at a time per process** (11-16). Before Architect v0.75.0, a ticket is `t-<ms>-<holder pid>`, so two acquires by one holder
  in the same millisecond (`--clients 2`) got one id, and `release --ticket` was ambiguous. Two CLIs spawned 2 ms apart still read the same
  millisecond, so a process's acquires run in turn, each at least 2 ms after the previous ticket arrived. A ticket the process already holds
  is refused (`SLOT_DUP_TICKET`), not released.
- **The holder** is the driver's node pid. A release runs on every way out: the end, a throw, `process.exit`, SIGINT, SIGTERM and SIGHUP. A
  SIGKILL leaves it to the queue's dead-pid pruning.
- **A signal** is `tools/lib/shutdown.mjs`'s (batch-074 review): it first stops what the driver started, by pid and bounded. A single driver
  quits its client and stops its JVM (the DevBridge port's listener) and launcher. A `--clients` lead forwards the signal to its child drivers,
  waits up to 90 s, then SIGKILLs them and the client pids each printed (`e2e-client: jvm <pid> launcher <pid>`). Only then are the slots
  released and the sidecar data removed, and it exits 128 + n. A second signal while shutting down is ignored.
- **Waiting.** `E2E_SLOT_WAIT_MIN` sets the wait (default 15, capped at 15). The CLI's waiting lines are printed. A slot that doesn't come
  within the wait is not a FAIL:
  - the e2e prints `NOT RUN: waited N min for a slot (load L, turbo T)`, exits 3, and records `slot: { timedOut, waitedMin, load, turbo,
    holders, reason }` in summary.json;
  - a nightly step that waited is listed as not run, and the night is `WAITED`.
  - Load alone never times it out: the load gate starts anyway at its cap.
- **Kept:** the claude-login opt-in refusal and the check for a client already on this run dir (`mod/run`). The nightly no longer skips
  just because another Steward client runs; the queue handles that.
- **See it:** `node $ARCHITECT_DIR/tools/slot.mjs status --json` lists holders (`who: steward`, the worktree, the label, the pid), the
  queue, the load and turbo.

### Temp dirs: the run's sidecar dir removed, strays swept (slice TMPSWEEP, 2026-11-16, $0)

Each run dir's sidecar dir on the RAM disk (`/Volumes/MCTmp/tmp/steward/sidecar-<hash of the run dir>`, 2-6 GB) used to stay until a reboot.
Every worktree and `--clients` run dir has its own, so by 11-16 sixteen of them had filled the 24 GB disk and other runs failed with ENOSPC.

- **Owner-marked.** e2e's preflight (`ramSidecarData(..., { fresh, own })`) writes Architect's marker into the dir: `.arch-owner`, a file
  holding `{pid, startMs, createdAt}` for the e2e driver (startMs is its Node timeOrigin), plus `tool` and `gameDir`. The format and the liveness
  rule (the pid runs, with the same start time) match Architect's slice TS (`../architect-mc/tools/lib/arch-tmp.mjs`, `tmp-sweep.mjs`).
  `tools/lib/tmp-owner.mjs` has its own copy: Architect's modules move TMPDIR to its own RAM dir on import.
- **Removed when the driver is done for good.** That happens at the end of e2e's `finally`, after the client quits, and in an exit hook that
  covers a throw, an unhandled rejection, `process.exit` (the slot timeout's exit 3), SIGINT, SIGTERM and SIGHUP (after the client is stopped,
  above). The start-of-run sweep re-reads each marker and re-checks its owner by pid just before removing a dir (batch-074 review). Client relaunches
  (`restartClient`, the restart flows) happen in the same process, so the dir and its data survive them.
  - Only the exact path this run made is removed, and only while its marker still names this process. A later run on the same run dir
    takes the marker over.
  - When the `mod/run/architect/sidecar-data` symlink points at exactly that dir, the dir is made again, empty and unmarked, and the link
    stays. A dev client started later then still writes to the RAM disk. Without the link it would write to a real on-disk dir, which the
    next run would rename to `sidecar-data.ssd` and keep.
  - If something (the sidecar, the client's grandchild) writes into the dir during removal, the dir is marked again for the sweep.
  - Each outcome is printed: `tmp: removed|kept|not-mine|gone|failed <dir>`.
- **The sweep.** At the start of each e2e run, `sweepStale` looks at the direct children of `/Volumes/MCTmp/tmp/steward` named
  `sidecar-*`. It removes those that carry a valid marker whose owner is gone, which are leftovers from a SIGKILL or a crash.
  - It uses no age floor for an unkept dir: a dead owner is decisive here, unlike Architect's 6 h default, which would have kept every dir
    that filled the disk. Kept dirs and empty fallbacks have their own bounds (E2E-DISK-4, below).
  - It never touches a live owner's dir, a symlink or a plain file, nor an unmarked dir other than an empty fallback (below).
  - Architect's `tools/tmp-sweep.mjs` CLI lists only the direct children of `/Volumes/MCTmp/tmp` and takes no root, so it never sees these.
  - Dirs from before this slice have no marker, and the sweep leaves them. A checkout's next run empties its own. Removing the rest takes a
    one-time manual cleanup.
- **On disk** (`STEWARD_RAM_TMP=0`, or no volume): the same cleanup applies to `mod/run/architect/sidecar-data` only when this run made it, or
  when an earlier run had marked it and that owner is gone (it is emptied first). An existing directory that isn't marked, such as a dev
  client's data, is never removed.
- **`STEWARD_KEEP_TMP=1`** keeps the dir, its marker saying `kept` from the start (so a SIGKILL keeps it too). Since E2E-DISK-3 a later run
  never empties it. Since E2E-DISK-4 the sweep bounds kept dirs, so repeated kept runs (2-6 GB each) can't fill the RAM disk. A kept dir whose
  owner is gone goes once untouched for 24 h (`STEWARD_KEEP_TMP_HOURS`), and while all kept dirs total more than 8 GiB
  (`STEWARD_KEEP_TMP_CAP_GIB`; live owners' count but are never taken) the dead owners' go oldest first. 0 turns either bound off. Each removal
  prints `tmp-sweep: removed <dir>: <why>`; a total still past the cap (live runs only) is said too.
- **A fresh run empties only what it owns** (E2E-DISK-3): the game dir's `sidecar-<hash>` is taken when it is absent, empty and unmarked
  (what a run leaves), or marked by an owner provably gone (then emptied). Kept data or unmarked data (a dev client's) is left as it is and the
  run starts in a new `sidecar-<hash>-XXXXXX` (mkdtemp) beside it. A dir held by another live run (say in its slot wait on the same game dir),
  whether the game dir's own or the one its link points at, kept or not, refuses the run in preflight, before anything is emptied or relinked.
- **Fallback dirs don't pile up** (E2E-DISK-4): at its end a run removes the `sidecar-<hash>-XXXXXX` it owns (not made again empty) and points
  the link back at the game dir's `sidecar-<hash>`, where it pointed before. The sweep also removes empty unmarked fallbacks untouched for 10 min
  (by rmdir, so one written into meanwhile stays) and a dead owner's kept fallback holding only its marker.
- **Module answer files** (E2E-DISK-4): `tools/module-measure*.mjs` and `tools/module-catalog.mjs` claim each `steward-modules/<label>.json`
  exclusively before the request (`claimAnswerFile`, as e2e does), under a pid-and-start-time tag, and drop the placeholder on a failure.

### Disk: cleanup after a run, the free-space guard (slice E2E-DISK, 2026-11-21, $0)

Minecraft/_work grew from 89 GB to about 977 GB (Steward about 684 GB), mostly e2e worlds left in slice and batch worktrees. `tools/lib/disk.mjs`.

- **Cleanup after a run.** Each e2e driver (a `--clients` run's children each on their own run dir) ends by deleting what it made, and only what
  it can prove is its own (slice E2E-DISK-2, 11-21: every path it deletes is named for this run and owner-marked):
  - this run's world, `<run dir>/saves/Steward E2E <tag>` (200-470 MB). The tag is the run's own (`runTag`: base36 time, pid, random), the
    name goes to the client in its launch env only (an explicit `ARCHITECT_AUTOWORLD_NAME` of that shape, in any case or spacing since
    E2E-DISK-3, is refused, with or without `--keep-world`: the disk ignores case). Once the client is in
    the world the driver writes `.steward-e2e-owner.json` ({run, pid}, exclusive) into it, only when this run's client created it (fix round,
    GPT P1 #2): absent (lstat) when the driver launched its first client, a real dir (a symlink is refused) born after that launch, and this
    run's client log since then says `AutoWorld: creating world '<name>'`. Just before the delete the marker is read again: no marker or
    another run's, and it is not touched (`disk: not deleted ...`). A marker that couldn't be written fails the run (`world owner`), so the
    world is kept.
  - its temp blueprint dirs (`steward-e2e-v2-*`, `steward-e2e-ui-*`, `steward-e2e-stub-*` in TMPDIR; mkdtemp, unique).
  - a `--clients` lead's lane dirs, `mod/run-c<k>-<tag>` (client 1 stays on `mod/run`). Each is made at its use with `mkdir` without `-p` and
    the owner marker (`claimRunDir`); a dir already there isn't this run's and the lane doesn't run on it. The lead hands its owner to the
    child (`E2E_LANE_OWNER`), and a driver on any `run-c<k>-<tag>` refuses to start unless the dir's marker is that owner (`laneUse`; fix
    round, GPT P1 #1): one started by hand there makes and deletes nothing. Since E2E-DISK-3 it judges the game dir by its real path
    (symlinks resolved, a tail not made yet kept) and in any case, any worktree's `mod`: an alias or case variant of a lane is that lane, and a
    dir inside a lane is always refused. The lead deletes a lane dir
    (`DELETE_LANE_DIRS`, on again) when every client on it exited 0, failed nothing, kept nothing and failed no delete, and its marker still names this run;
    otherwise it is kept and named. Only names with this run's tag are ever items, so an old plain `mod/run-c<k>` or another run's dir is
    never deleted. A fresh lane dir costs a configuration-cache miss and Architect's sidecar copy (~21 MB) on its first launch.
  - A driver whose slot never came (a slot wait) never launched, so it deletes no world; its temp blueprint dirs still go.
  - The world is **kept** when a check failed, the run threw, no checks ran, or a signal stopped it; also while a client JVM of this run still
    runs on the run dir. summary.json's `cleanup` lists `deleted`, `kept` (path, why, client), `failed` and `note`, a quoted `rm -rf` line to
    delete what was kept; the run prints `disk: deleted|kept|not deleted ...` lines. The nightly copies kept paths and the note into SUMMARY.md.
    Kept worlds now pile up one per failed run (each named, with its note), no longer capped at two.
  - Never touched: `--keep-world`'s or an explicitly named (`ARCHITECT_AUTOWORLD_NAME`) world (listed as kept), the worlds from before
    E2E-DISK-2 (`Steward E2E`, `Steward E2E (last)`: the start names them, `disk: N earlier e2e world(s) ... left as they are`; delete them
    by hand), other runs' worlds and lane dirs, the dev world, the run dir's `architect/` (the fixed `e2e_stub` entry is rewritten file by
    file only where it differs), `config`, `logs`, `mods`, `options.txt`, `mod/run` itself, `artifacts/` (the evidence). A path outside its
    own root is never deleted, and a symlink is unlinked, never followed.
  - The sidecar data stays TMPSWEEP's ("Temp dirs" above): removed at every end, failed or not, since keeping 2-6 GB per failed run on the
    24 GB RAM disk brought back ENOSPC. `STEWARD_KEEP_TMP=1` keeps it for a look.
  - `/steward dev module` answer files (`<run dir>/steward-modules/e2e_<tag>_<n>.json`, read and removed by the driver): since E2E-DISK-3 the
    driver claims the name first with an empty placeholder made exclusively (`claimAnswerFile`, `wx`), which the mod's writer (ModuleDev,
    Java, not exclusive itself) writes over; a name already there fails that request. The module tools (`module-measure*.mjs`,
    `module-catalog.mjs`) still name theirs by pid and a counter, unclaimed.
  - The sidecar dir's gaps E2E-DISK-2 named (emptied unowned before the slot; `STEWARD_KEEP_TMP` data swept) are closed in "Temp dirs" above.
- **The free-space guard.** `e2e.mjs` (not `--plan`, not a `--clients` child: its lead checked) and `nightly.mjs` refuse to start when the
  volume holding the worktree has less than **`E2E_MIN_FREE_GIB`** free (GiB, default **150**; statfs, no disk walk). The refusal says how much
  is free, writes summary.json with `refused`, and exits **5** (2 is usage, 3 a slot wait, 4 the slot CLI's own); the nightly writes a `NOSPACE` SUMMARY.md, and `--dry` prints the
  check. Override for one run: `E2E_MIN_FREE_GIB=100 node tools/e2e.mjs free`; `E2E_MIN_FREE_GIB=0` turns the guard off. A statfs error
  starts the run anyway, with a warning.

### Faster runs: two clients, skipping unchanged flows (slice EF, 2026-11-15, $0)

Without these flags a run is as before: one client on `mod/run`, every chosen flow run.

- **`--clients 2 --ports 8596,8598`** runs the chosen flows (`--only` as usual) on two clients at once.
  - Each client has its own port pair (the sidecar port given, the DevBridge port one above), its own run dir (client 1 `mod/run`, client k
    `mod/run-c<k>-<tag>`, new each run, through `STEWARD_RUN_DIR` in build.gradle) with its own RAM-disk sidecar link, and its own evidence dir
    (`artifacts/e2e/c<k>/`: its client.log and summary).
  - Each client is a child `e2e.mjs ... --flows a,b` that runs the sanity set and then exactly its flows, in that order. (Since E2E-QUEUE
    the clients take units from a shared queue instead: "The work queue" below.) Its lines come prefixed
    `[c1]`/`[c2]`. Each client starts once its slot comes, and the queue's settle (120 s) spaces them. `--stagger s` adds a gap of Steward's
    own (by default only with `E2E_SLOTS=off`: 60 s).
  - Flows are packed in groups, longest first, each onto the client with the least recorded seconds. A group holds a flow and what it `needs`,
    plus flows whose code shares a block that writes fixed names under `artifacts/e2e` (the snapshot files: the gate-mech family, for one). A group with
    a flow that has no recorded time goes after every timed one. The times come from `artifacts/e2e/summary.json`, then
    `artifacts/e2e/flow-times.json` (every run's, newest kept), then `E2E_TIMES` (another summary).
  - One `artifacts/e2e/summary.json` merges both: each result and flow carries `client`, `clients[]` has each one's ports, flows, packed and real
    seconds and exit. The end lines give each client's wall time and `FAIL [c2] <check>: why` for each failure.
  - The slot rule (slice SQ): one slot per client from Architect's queue. If client k's slot doesn't come within its wait, it runs after c1
    on c1's slot, run dir and ports, and so does every client after it. The run says so (`clients: waited N min for a slot ...: falling
    back to one client`), and `clients[]` records `borrowed` and `waited`.
  - The client check in preflight now refuses only a client of this checkout whose working dir is this run's game dir.
- **`--skip-unchanged`** skips a flow whose inputs haven't changed since its last PASS (`artifacts/e2e/passes.json`, per mode, local only, written
  after every run that isn't a client of a `--clients` run).
  - A flow's inputs: its **code block** (the top-level blocks of e2e.mjs its body reaches, comments left out, with a `tools/lib` import standing for
    its whole module, and the blocks of the flows it needs), the **fixtures** that code names (a name made at run time hashes its whole directory),
    the **jar** (`mod/build/libs`), the **Architect pin** (`architect_version`, an `ARCHITECT_JAR`'s sha, `lab_ui_version`) and the **mod
    sources** (`mod/src/main`, `mod/src/client`, the Gradle files).
  - A strict flow (bill, refund, undo, restart or survival in its name or tags) skips only on an exact match: block, fixtures, jar and pin. A
    light flow skips on its block, its fixtures and the mod sources.
  - It prints `skip <flow>: why` and `runs <flow>: what changed`. An unchanged flow that a running flow needs still runs. The sanity set always
    runs. The summary lists the skipped flows under `unchanged`.
- **`--plan`** prints the skips and the packing and starts nothing.

#### Speed sweep (slice E2E-SPEED, 2026-11-21; the cuts are in `docs/SPEED-CUTS.md`)

The slowest flows in b093's full 2-client stub run (433 checks, wall 7134 s for 11345 s of flows). Lanes came out c1 7073 s, c2 4388 s,
packed blind (see below).

| Flow | s | Where the time went (run 1's per-check `t`) |
|---|---|---|
| wall-lots | 725 | 600 s: the undo's REMOVED wait ran to its bound |
| supplied-growth | 720 | 3 restarts; builds from materials |
| drawbridge | 691 | 600 s: REMOVED wait at its bound |
| gate-mech-lots | 677 | 600 s: REMOVED wait at its bound |
| gate-mech | 650 | 600 s: REMOVED wait at its bound |
| gate-mech-swing | 643 | 600 s: REMOVED wait at its bound |
| wall-offer | 453 | 3 x 120 s REMOVED waits at their bound |
| module-survival | 448 | about 330 s in the report-only waiting round |
| revision | 336 | 300 s: REMOVED wait at its bound |
| survival-district | 304 | builds and refunds |

- **Lanes by time.** `flowSeconds()` reads `tools/flow-times-baseline.json` first, then this worktree's summary.json, `flow-times.json` and
  `E2E_TIMES`; later sources win per flow.
  - Run 1 (`--full`, load up to 85): lanes c1 6708 s and c2 6777 s, wall 6900 s, against b093's 7073 s and 4388 s.
  - Refresh the baseline after a full run that adds flows or changes them a lot: copy each flow's `seconds` from that run's
    `artifacts/e2e/flow-times.json` into its `seconds` map.
- **REMOVED waits.** A removed region is answered `{missing: true}`. It now reads as REMOVED, and a wait past its bound fails with TIMEOUT
  (`tools/lib/region-wait.mjs`).
  - In run 3, under load 86, the four gate-mech flows took 186 s, 370 s, 214 s and 500 s, against 650, 677, 643 and 691 s.
- **The log over midnight.** `logSince` reads bytes and reads a rolled-over log whole. Before, every log wait across 00:00 stalled to its
  bound: run 2's supplied-growth and module-hall-survival, and batch 094's supplied growth cancel.
- **Per-check `t`.** Each check row in summary.json carries `t`, its seconds into the flow, so the next sweep can see the phases.
- **`--full`** runs the tier left out of batch runs (now: module-survival's report-only waiting round). The nightly runs it as well.


#### Three clients, the fast set, the 2-client nightly, the RAM-disk floor (slice E2E-SPEED-2, 2026-11-22, $0)

Noah, 11-22: "1+2+3: all speed items", then items 5 and 6.

- **`--clients 3 --ports a,b,c --stagger 120`.** Nothing in the lanes was 2-only: packing, one slot per client (acquired in turn; the queue's
  120 s settle spaces them), lane dirs and the summary merge are per lane. `--plan` now prints the projected wall: each lane's start (the stagger,
  else 120 s), about 4 min of launch, sanity set and restart (`LANE_OVERHEAD_S`), then its flows. On the baseline a full stub projects about
  **82 min on 2 lanes and 59 min on 3** (9177 s of flows; the composed and gate-mech families pack as groups). A lane whose slot doesn't come
  still falls back to running after c1, and every lane after it too.
- **`--fast`** (stub only; not with `--full` or `--flows`): the sanity set, one flow per risk area (`FAST_FLOWS`, `tools/lib/e2e-flows.mjs`, by
  exact name), the flows `--only` names (the batch's words: names, tags or words) and the flows **touched by file** since `--touched-base <ref>`
  (default: the merge base with `main`; `none` turns it off), each with what it needs. Touched by file: a block of `tools/e2e.mjs` or a
  `tools/lib` module the flow's code reaches changed, or one of its fixtures (`touchedByFiles`). A changed block more than half the flows reach
  (the harness: launch, restarts, the shared libs) touches none by itself and is listed as harness code: every flow runs it, so the sanity and
  fast sets cover it. Mod (Java) changes map to no flow: name those flows with `--only`. A base git can't read fails the run. For
  **light-only batches**; a strict batch never uses it. summary.json says `tier: 'fast'`. The set alone projects about 11 min on 3 clients.
  `docs/SPEED-CUTS.md` has the row.
- **The nightly's stub runs on 2 clients**: `e2e.mjs stub --clients 2 --ports 8590,8600 --stagger 120` with `E2E_NIGHTLY=1`, c1 on 8590/8591,
  c2 on 8600/8601 (reserved for it). The night is skipped when any of the four is taken. `node tools/nightly.mjs --dry` prints the argv, the ports
  and the fit from `stub --plan` (about 112 of 165 min). At its limit the night's SIGKILL now follows its SIGTERM by 180 s (was 30 s), past a
  lead's own stop bound, so its clients quit and their RAM dirs go. `composed-sculpt-crash-carve` has an estimated baseline time (400 s, marked
  under `estimates`) so the composed group packs by time.
- **The RAM disk's floor** (`waitTmpFloor`, `tools/lib/disk.mjs`): each client (single driver, every `--clients` child, the nightly's) whose
  sidecar data is on the RAM disk waits after its slot and before its first launch (not its relaunches) until `/Volumes/MCTmp` has
  `E2E_TMP_FLOOR_GIB` free (default 6), re-checking every 15 s, for `E2E_TMP_WAIT_MIN` at most (default and cap 15). Past the bound, or when the
  free space can't be read, the start FAILS as the check `ram floor`, naming the free space and the floor. `E2E_TMP_FLOOR_GIB=0` turns it off.
- **A gone run's sidecar dir emptied** (`reapSidecar`, `tools/lib/ram-tmp.mjs`; item 5). A driver already empties its own at its end
  (TMPSWEEP); now a removal a straggler spoils is retried (4 tries, 3 s apart), a `--clients` lead empties each child's dir after the child
  ends (one SIGKILLed past its stop bound left 2-6 GiB until some later sweep), and the nightly does the same for its worktree's run dirs
  after each step. Only after proving the dir is that run's: the game dir's `sidecar-data` is a symlink to a real dir directly under
  realpath(`/Volumes/MCTmp/tmp/steward`) named `sidecar-<hex>[-XXXXXX]`; its marker names this game dir (real path, any case) and, for a lead,
  the child's pid; it isn't kept; its owner is gone; no client JVM runs on the game dir; and the marker is unchanged just before the removal.
  The dir is made again empty, so the link stays valid. `STEWARD_KEEP_TMP=1` keeps everything. A failed run's small evidence (`.log`, `.json`,
  `.txt` under 1 MiB, 16 MiB in all; never `node_modules`, `blobs`, a file named or holding a token, secret, key or password, links never followed) is copied first into its kept world,
  `saves/Steward E2E <tag>/steward-sidecar-evidence/`, which the kept world's delete note covers.
  - The 11-22 incident (38 MiB free, 19 GiB under `tmp/steward`) was mostly live runs at once: on a normal end every dir was already empty
    (95 empty `sidecar-*` dirs, one live 2.4 GiB). The floor is what keeps concurrent runs from filling it.

#### The work queue (slice E2E-QUEUE, 2026-11-22, $0)

Lanes packed up front came out lopsided: per-flow times swing about 40% with load and order (b096 predicted 4659 s and 4658 s, ran c1
6563 s against c2 3183 s; survival-mech took 441 s on a 132 s baseline). Now a `--clients` run's clients take work from one queue.

- **Units** are groupFlows' groups (a flow with what it `needs`, flows sharing a snapshot block such as the composed and gate-mech families),
  each run whole, in order, on one client. The queue holds them **longest first** by recorded seconds (`orderUnits`, `tools/lib/e2e-queue.mjs`),
  untimed units last.
- **One child per client, kept up across units.** Each child is `e2e.mjs <mode> --queue` (it replaces `--flows`): its slot, RAM floor, launch
  and sanity set as before, then it asks the lead for a unit over Node's IPC channel, runs it, says it is done (with that unit's rows) and asks
  again, until the lead answers `end`; then the restart check and its exit. A child per unit would pay about 4 min of launch, sanity set and
  restart per unit. The lead hands out units from one event loop, so no unit is handed out twice.
- **Bounded, and nothing skipped silently** (`laneSession`): a child that asks for nothing within 40 min of its spawn, a unit past 4x its
  recorded seconds (at least 20 min; an untimed one 60 min), or a child not ended 20 min after its last unit is stopped by pid (driver, JVM,
  launcher). Its running unit FAILs as `queue <flow>` per flow (and drops those flows' PASS), and the rest go on to the other clients. A child
  that ends mid-unit fails that unit the same way. Units left when every client is gone FAIL as never run. A unit said done whose flows have no
  row in the child's summary FAILs. A child killed before writing its summary is merged from the rows it sent with each finished unit.
- A further client whose turn comes with the queue empty isn't started (`clients[]` says `idle`). A lane whose slot doesn't come still runs
  after c1 on c1's slot, if units are left by then.
- **`--plan`** prints the queue and a greedy simulation (each client ready after the stagger and `LANE_OVERHEAD_S`, taking the next unit when
  free). The `client cK (ports, dir): ~Ns recorded` lines the nightly's `--dry` reads are kept, with each client's simulated seconds. On the
  baseline a full stub projects about **81 min on 2 clients and 57 on 3** (9177 s of flows, 62 units).
- `summary.json`'s `clients[]` gives each client's `flows` and `units` as run (id, status, seconds), `packedSeconds` their recorded seconds.

## lab-ui

lab-ui (`dev.larattalabs:lab_ui`), the shared client UI library, is bundled jar-in-jar. `mod/settings.gradle` decides where Gradle gets it, with the same rule as
AgentCraft and Architect:
1. **A lab-ui checkout at the release.** It is used only when it is clean and its HEAD is exactly at the tag `v<lab_ui_version>`. Checkouts are looked for at
   `../../lab-ui` from `mod/` and at `~/Developer/LarattaLabs/Minecraft/lab-ui`.
2. **For lab-ui development,** `-Plab_ui.dir=<path>` or `LAB_UI_DIR=<path>` forces a checkout, with an UNRELEASED warning (don't ship that build).
   `-Plab_ui.dir=none` turns checkouts off.
3. **Otherwise the release from GitHub Packages,** fetched once into the Gradle cache, after which `--offline` works:
   `GRADLE_USER_HOME=$PWD/../.gradle-home GITHUB_TOKEN=$(gh auth token) ./gradlew build` from `mod/`.
   Never commit or export the token.

**The e2e client and the record tools take the release from the Gradle cache** (`-Plab_ui.dir=none`; `tools/run-e2e-client.sh`, `record-cards.mjs`,
`record-plans.mjs`; 2026-11-14, slice ET). Every worktree's composite build rewrites the jar in the one shared `../lab-ui` checkout, so another session's
build broke a running client's load ("ZipFile invalid LOC header", three runs on 11-13 and 11-14). To test a local lab-ui on purpose, set
`LAB_UI_DIR=<absolute path>` for the run (`LAB_UI_DIR=~/Developer/LarattaLabs/Minecraft/lab-ui node tools/e2e.mjs stub --only ...`); that run then reads that checkout's jar, so keep other
builds of it away while it runs. A plain `./gradlew build` still uses a released checkout; in a slice worktree, `-Plab_ui.dir=none` keeps it off the shared jar too.

Maven Local never supplies lab-ui, because a local publish may come from a checkout past its tag. Fetched this way on 2026-10-10: the release jar differed
from the local publish.

## Slice V spike (2026-10-11, $0)

`/steward dev v_spike <id> plan|realise|dump` (host, dev only) plans Architect's `flat_village` from the settlement's card program, realises every lot with the e2e test entry, and dumps to `run/steward-v-spike.json`. On a flat stub world, 7 cottages over a 193 x 193 claim gave:
- **Plan:** stages ground, street, lots, props; all 7 lots placed, fronts south and north along one east-west street; each lot's ext `architect_mc:village` carries its entrance, lane, slot and villager; the anchors include the bell at the square.
- **Sites:**
  - each building is its own site, itemKey `lot:cottage_1`, ext `architect_mc:lot` only;
  - the street is `road:street_w` and `road:street_e` (it breaks at the square);
  - everything is in the region's site group.
- **Region:** the region keeps Steward's ext (settlement, build). After 60 s it was still PLACING, with 6 of 7 lots placed.

## Strange asks: real cards (2026-10-12, paid, Noah approved with a $40 cap)
`node tools/record-cards.mjs 40` recorded the card Claude writes for each of the 59 strange asks (`fixtures/strange-asks/cards/`) on Noah's claude login. The script made the opt-in for the run and removed it after; the client started from a scrubbed environment.
- **Cost:** $0.96 in all ($0.013-0.032 a card). The first attempt spent nothing: the script looked for the DevBridge token in Architect's run dir, which `ARCHITECT_GAME_DIR` now fixes.
- **Schema:** every card validates (`npm test`). The recorder now keeps null fields, which the schema requires.
- **What the cards show:** read through `CardLimits`, 22 of the 55 asks not built then said what waits; the ask's words alone gave 6 (2026-10-12). Now 43 of the 47 not built do (`StrangeAsksTest.HONEST_BASELINE` 43, 2026-11-14). Claude spells out the place ("piers reaching out over the water"), which the limits catch. The fishing village's card wanted piers over the water, so that ask now needs `over_water`.
- **Still silent (33):** walls, free layouts, hard rules, time, working modules, inhabitants and relations, which `CardLimits` doesn't know about yet.

## Strange asks: plans re-recorded with the shape rules (2026-10-20, paid, Noah approved; $0.65)
The 12 asks whose plans had refused, blocked or old-shape parts were re-recorded with today's plan writer (the shape rules, the live
catalogue, material rules): `node tools/record-plans.mjs 1` ($0.54, nine plans, then stopped at its $0.50 margin) and `0.96` (three,
$0.11). The opt-in was made for each run and removed after. All 12 are valid plan/0. Scored with Architect's kit (main 7e7cd2d5) on the
asks claim, as its gap table does:
- **The 12:** refused 21 → 4, blocked 2 → 15, adjusted 2 → 8, fits 13 → 8, unsupported 9 → 8. The blocked rise is intended: rope
  bridges to huts up in trees now wait on the reserved `layout.elevated` (swamp_stilts, treetop_village, upside_down), and the
  underwater colony on its seabed. two_districts writes its districts in the supported shape (adjusted).
- **All 59:** refused 26 → 9. What's left:
  - a street or road ending on a lot its zone couldn't place (terraced_hill, redstone_gate, two_districts: `NO_ROOM`);
  - "only copper" measured over the settlement's roads and terraces, which a palette rule can't exempt (`TOLERANCE`; asked of
    Architect);
  - five palette rules in plans recorded before the material-rule prompt (desert_jungle_wall, hidden_village, mage_exiles, old_new_town,
    row_houses) (`NO_FEATURE` prose-only, `TOLERANCE`; not re-recorded).

## Strange asks: the five older palette plans re-recorded (2026-10-23, paid, Noah approved; $0.44)
`node tools/record-plans.mjs 1` with the plan writer's `over: "lots"` rule (Architect close-out A, v0.42.0): desert_jungle_wall,
hidden_village, mage_exiles, old_new_town, row_houses ($0.44; the first call uncached at $0.29). Scored with v0.42.0's kit: refused 5 -> 1,
unsupported 3 -> 1, blocked 0 -> 1.
- **Palette rules:** row_houses writes its rule `over: "lots"` and fits. The other four, whose asks were tastes ("a mossy palette", "two
  styles", "a shared stone base"), now put them in the style, as the prompt says, rather than as rules Architect refused `NO_FEATURE`.
- **mage_exiles:** its enclave is now a 48x48 ring of 12 lots, refused `NO_ROOM` ("no ring layout fits", 13 tried); a path depending on it is
  blocked. In a real compose the repair round would resize it.
- **row_houses dropped its `identical_houses` variety rule** (reserved until RC) while its summary still promises identical houses: the
  silent drop the prompt forbids. One sample, but the prompt now names variety rules among what is still written so it waits.


## Strange asks: the 8 walled asks re-recorded for gatehouse coverage (2026-11-03, paid, Noah approved; $0.60 of a $1.50 cap)
`node tools/record-plans.mjs 1.5` on claude-sonnet-5-5, Architect 0.52.0 (GC), with the plan writer's "gatehouses and towers stand on their wall"
rule (gate-coverage): border_fort, camp_to_city, castle_city, desert_jungle_wall, metropolis, redstone_gate, walled_town, war_camp ($0.60; the
first call uncached at $0.28). They're kept in `fixtures/strange-asks/plans-gc/`; `plans/` keeps the earlier recordings, which the e2e and
unit tests read (castle_city, walled_town). The opt-in was made for the run and removed after. All 8 are valid plan/0. Three earlier launches spent nothing
(a worktree has no `node_modules` or `.gradle-home`: link the main checkout's `.gradle-home` before a paid run from a worktree). Scored with
v0.52.0's kit on the asks claim:
- **Coverage:** gatehouses named on their wall (`lots`, `at: "gate"`) 4 of 11 -> 11 of 11; all 11 fit a mechanism (13x7). Every one carries
  `mech: "portcullis"`, written by Claude itself although the prompt says Steward adds it (the same result; `withGateMechanisms` leaves a
  given `mech` as it is). war_camp has no gatehouse. GC's "not on the wall" note: 1 -> 0.
- **Refusals:** walls refused 0 -> 0. Two zones refused `NO_ROOM` that weren't: metropolis's capital and walled_town's town were written
  `layout: "grid"` this time (`street` before), and no grid fits the asks claim. Rewritten as `street`, both are READY; without the wall's lots
  walled_town is still refused, so it's the layout, not the gatehouses. In a real compose the repair round would change it.
- **redstone_gate** is REQUIRED_BLOCKED before and after: its drawbridge is reserved until the mechanism, and its fortress didn't place
  the gatehouse the wall names ("which its zone did not place"); its towers stand beside the line, not on its corners.
- **camp_to_city** now writes a harbour wall with wet sections: INCOMPLETE in the kit check, which can't read the water under them without
  a world (READY before, without them).
## Strange asks: the 8 walled plans scored on Architect 0.53.1, GH's street wouldFit applied (2026-11-04, $0)
The `plans-gc` recordings checked with v0.53.1's kit on the asks claim (`checkPlanLocal`, Steward's anchors). A refused zone whose `NO_ROOM` evidence names
`alt: "street"` had its one `wouldFit` fragment applied and the plan checked again: one repair, which scores as a pass (Noah, 2026-11-04).
- **metropolis:** REQUIRED_BLOCKED, `capital` NO_ROOM (grid) -> READY after one repair; street places 13 of 35 lots, leaving out 22 (houses 5-8,
  cottages 1-6, guild halls 1-3, taverns 1-3, smithies 1-2, barracks 1-2, barns 1-2).
- **walled_town:** REQUIRED_BLOCKED, `town` NO_ROOM (grid) -> READY after one repair; street places 12 of 15, leaving out townhouse_4, smithy_1 and
  chapel_1.
- border_fort, castle_city, desert_jungle_wall and war_camp: READY as written. redstone_gate: REQUIRED_BLOCKED as before (its drawbridge waits).
  camp_to_city: INCOMPLETE as before (the harbour wall's wet sections), with `main_street` refused UNREACHABLE.
- In the mod, the composer applies such a fragment itself before any Claude repair round (`Composer.streetFixes`, $0), and the steward says which
  buildings were left out ("Left out for room (laid out on a street instead): townhouse 4, smithy 1 and chapel 1."). The e2e's `street hint` composes
  walled_town's grid plan in game.
## Strange asks: the 11-10 writer fixes proved on 8 re-recorded plans (2026-11-13, paid, Noah approved; $0.55 of a $1.50 cap)
Noah via parent session: "n cap 1.50 + compose". On main 194afd3 (Architect 0.63.0), with the plan-writer prompt's reserved wording fixed (8b6311a),
the claude login's opt-in made for each run and removed after. `node tools/record-cards.mjs 0.55` (ring_city's card, $0.03), then
`node tools/record-plans.mjs 1.47 --out fixtures/strange-asks/plans-2026-11-13` from a folder holding every plan but the 8 targets ($0.52; the first
call uncached at $0.25). The record tools start a call only while $0.50 (a job's cap) is left under their cap, so a $0.20 cap records nothing.
`plans-2026-11-13/` keeps the 8; `plans/` stays the tests' input. Scored with Architect v0.63.0's kit (`checkPlanLocal` on the asks claim, Steward's
anchors; 11-10 recording -> 11-13):
- mushroom_town: REQUIRED_BLOCKED (`shared: "green"`, SCHEMA) -> READY.
- hidden_village: REQUIRED_BLOCKED (SCHEMA, UNKNOWN_REF, the hidden room unsupported) -> READY, **but the hidden room is dropped**, with no wait
  line: 0.63.0's catalogue lists hidden_room reserved while the prompt already calls it supported. To re-record on 0.64.0 (LEFTOVERS).
- cliff_monks: REQUIRED_BLOCKED (`sea_stair` NO_CLIMB) -> READY.
- conquered_town, ruined_empire: NEEDS_REPAIR (a road into an unwalled town's gatehouse, DEPENDENCY) -> READY.
- rival_families: REQUIRED_BLOCKED (`west_estate` OVERLAP) -> READY.
- twin_villages: NEEDS_REPAIR (`inn_stop` DEPENDENCY) -> READY.
- ring_city: REQUIRED_BLOCKED (`ring_town` NO_ROOM) -> READY.

What didn't move: **sculpted_forms stays nearest.** Steward's own `CardLimits` still tells the player big forms and buildings over water wait,
which has been false since BF (0.63.0); StrangeAsksTest counts ring_city and sky_monastery as false alarms when the row moves. CardLimits needs the
7c catalogue first (LEFTOVERS). The new ring_city card has `site.size: null`, outside the card schema (S/M/L/XL or absent), so `cards/` keeps the old
card; the new one is the prompt inside `plans-2026-11-13/ring_city.json`.

## hidden_village re-recorded on 0.64.1 with the hidden-rooms prompt line (2026-11-13, paid, Noah approved; $0.25 of a $0.55 cap)
Noah via parent session: "hidden_village: cap 0.55". On main 80d3926 (Architect 0.64.1, catalogue 21: hidden_room supported; the prompt's
"Hidden rooms are secrets" line). `node tools/record-plans.mjs 0.55`, one plan, $0.2471 (the uncached first call), the opt-in made and removed.
Kept as `plans-2026-11-13/hidden_village-0.64.1.json`.
- **The room is not written, again.** The plan has the waterfall (`falls`) and the village's ten lots, no `form.hidden_room`, no wait. Claude read
  "a hidden village nobody can find, behind a waterfall" as the whole village hidden, and its summary says the village "sits in a hollow behind a
  waterfall curtain": nothing behind the falls is built. That is a silent drop dressed as a fit, against the prompt's never-drop rule.
- **Secrecy:** nothing to leak (no room part); the cabins' label "hidden dwelling" describes the village, not a room.
- **What it needs:** the never-drop rule must cover a summary that claims a part the plan doesn't write ("behind", "inside", "hidden" without the
  form), and the writer should be told that a place "behind a waterfall" is the waterfall's hollow (`form.hidden_room` with `host` the waterfall) or a
  wait. With slice PW (LEFTOVERS).
- **Two earlier tries spent $0.00:** the record tools sent their first job while Architect's helper was still running `npm ci` for its Claude Agent
  SDK, which it does at every launch ("Claude link is not available"). Both tools now wait for the launcher's "running" line first.

## M5 paid half: composed plans that name modules (2026-11-17, paid, Noah approved; $0.5487 of a $3.00 plan, $6.00 stop)
Noah in this session: "Yes, $3 plan / $6 stop" (no single describe or compose over $1.00). Branch `m5-paid` on `m5b-hall` 2fdbb06 (batch 075 not
merged yet; Architect 0.74.0), ports 9130/9131, flat dev worlds "Steward M5 Paid <n>". Each card: `node tools/compose-proof.mjs 0.70 <words>` on
Noah's claude login (the opt-in made by the script for the run and removed after it; `ls mod/run/architect/sidecar-data/` showed no
`secrets.json` after each). Then the kept plan was Started at $0 with `tools/ca2-part-proof.mjs town` on Architect's sim helper (no opt-in), which is
where plan v1 binds the module lots. The two scripts now take `STEWARD_PROOF_WORLD`, `ARCHITECT_AUTOWORLD_MODE` and `ARCHITECT_AUTOWORLD_PRESET`.
Costs are the sidecar log's `job ... done ($...)` lines. Fixtures: `fixtures/real/composed_modules_real_{1..4}.json`.

| # | card (words) | card $ | compose $ | modules named | plan v1 binds | @2 hall |
|---|---|---|---|---|---|---|
| 1 | market town on the river, storage hall that sorts | 0.0339 | 0.3013 (2 rounds) | storage_hall (16 slots, bulk 4, 16 filters) | **no**: card terrain `sculpt` (river carved), so `VPlan.applies` is false and Start took Steward's own street layout | not reached |
| 2 | farming village, working self-watering crop farm | 0.0199 | 0.0511 | crop_farm 27x27 wheat, irrigate | yes: `crop_farm_1` -> crop_farm@1 | none asked |
| 3 | walled hamlet, sorting store + automatic farm | 0.0172 | 0.0528 | storage_hall (8 slots) + crop_farm 18x18 | yes, both | yes: storage_hall@2, bulk 2, slots 1-8 = cobblestone, oak_log, coal, iron_ingot, wheat, wheat_seeds, dirt, stone (9-16 none) |
| 4 | survival homestead, self-feeding farm + sorting hall | 0.0192 | 0.0533 | storage_hall (8 slots) + crop_farm 27x27 | survival: both left out by the materials guard, said | n/a |

- **Total $0.5487** (0.3352 + 0.0710 + 0.0700 + 0.0725).
- **Every card named its modules** with `ext.steward_mc:module`; the composer's summaries say the farm is harvested by hand (S10's `does`).
- **The quote's bill limit can't be shown on this base:** `ComposedModules.MATERIALS_WAIT_FOR_LOT_CAP` is on, so in survival (card 4) both
  module lots are left out before any quote ("...can't be held to its measured limit in a town built from materials yet; it's left out for now"),
  and creative has no quote. The check waits for the guard to come off (S-M5-16).
- **Finding (not fixed here): a sculpt card skips the plan.** Card 1's kept plan names a storage hall, but `VPlan.applies` refuses `sculpt`
  terrain, so Start falls back to Steward's own street layout and designs `storage_hall_1` as an ordinary building (a paid design on a real
  Start), against MODULES.md 2 / B3 ("never designed as an ordinary building", "never falls back to the flat village"). Nothing says the module
  was dropped.
- **Nit:** card 4's hall lot says `for: "barn"` though the card asked for a storage hall.

## Slice CA2: compose on Architect CP (2026-11-15, $0, branch compose-cp off pin-071, Architect 0.71.0)
- **What:** the Composer sends `skipPick`, `composeVocab=families`, the level and the owner, and says CP's `summary`, `waits[].say` and
  `leftOut`; Steward's street pass and Overworld pre-check are gone. A waiting part is composed through `Regions.compose`
  (`ComposeJob.partRequest`, `prompts/compose-part-rules.md`); `PlanWriter` and `prompts/plan-writer.md` are gone. API before 1.63.0: nothing
  is composed, said ($0). COMPOSITION sections 11 and 13.
- **Ran:** Gradle full suite 574/574, npm test 73/73. `stub --only stub,plan,card,village` PASS 95 (new checks: `compose request` skipPick
  true, composeVocab families, REGION designs unchanged (no pick); `street hint` Architect's street round inside compose, 2 rounds, "Left out
  for room: townhouse 4, smithy 1 and chapel 1"; `compose words` NO_PLAN kept with the round's own summary and "Not yet: the mine railway
  waits for CR"; `wall offer`/`wall built` through the part compose). After the review fixes and the fix check, `--only wall-offer,street-hint,compose-words` PASS 21, twice (at 71a8060 and ea7cb42).
- **GPT review** (max): 2 blockers, 2 should-fixes, 1 nit, then a fix check. Fixed: a part's compose not seen by this client for 30 s in a row
  stops (never left busy); the part key holds the town region's IR, revisions and site versions; incomplete parts said; CP's names for unfit
  parts; free street rounds not counted as repairs. Accepted (leftover): the owner now scopes the settlement compose's key, so an identical card
  re-described across the upgrade composes once more (with no owner it would only have been OP_KEY_CONFLICT, never an adoption).
- **Leftovers:** Composer's adoption of a finished design outside the client's snapshot is still CA's (nothing more is spent, but the plan
  isn't kept until a restart's reconcile); the Nether refusal is read from a constructed answer in units, not run in game; CP's `until` is a
  slice name ("waits for CR"), Architect's wording.
- **Paid proof (2026-11-15, Noah approved; $0.4636 of a $1.50 cap).** A waiting part is offered only once the town stands as a region, and a real
  town build spends dollars on its bible and massings, so Noah chose "3 runs, town at $0": a paid describe, the town built on the sim helper with
  no opt-in, then the paid part. Ports 8590/8591. The opt-in was made by the script for each paid run and removed after it; `ls -la
  mod/run/architect/sidecar-data/` showed no `secrets.json` after each. Costs are the sidecar log's `job ... done ($...)` lines.
  - **Describe, the first try ($0.2376):** `node tools/compose-proof.mjs 0.70 a little market town with a square, a well, a tavern and a smithy`, on
    default terrain. Card $0.0284, compose COMPOSED in 1 round $0.2092, kept READY. The town was then built at $0 on Steward's own street (`g1`):
    the plan path needs flat ground, and the claim's ground ran y 57-123. That world was kept as "Steward Compose Proof (CA2 hilly)".
  - **Describe again on a flat world ($0.0460):** the same words with `ARCHITECT_AUTOWORLD_PRESET=flat` (compose-proof now reads it). Card $0.0132,
    compose COMPOSED in 1 round $0.0328, READY; summary "You'll get a small market town on the flat plains beside the founding stone ...".
  - **The town at $0** (`node tools/ca2-part-proof.mjs town`, sim): the plan's 9x9 lots refused the sim's stand-in massings (the kit cabin is
    11x12x12, past the 9x7 limit), so the proof world's kept plan had its six lots set to 19x19 by hand, a sim-only change (the original is kept beside it as
    `steward-plans.json.ca2-orig`). Then: built from its plan, 6 buildings, `region:rg1`.
  - **The part ($0.1800, cap $0.80):** `node tools/ca2-part-proof.mjs part 0.80`: `pend town_wall` on connector.wall, offered ("The town wall can
    be built now"), accepted, compose COMPOSED in 1 round $0.1800, Steward's check fits, built as `region:rg2` (4 sites), no longer waits.
    `summary`: "A stone wall will be built all the way round the town. Nothing else is added."; `waits`: none, `leftOut`: []. Chat: "Fitting the
    town wall round Little Market Town as it stands..." / "A stone wall will be built all the way round the town. Nothing else is added. Raising it now
    (fitting it cost $0.18)..." / "Built the town wall. An undo takes it away and leaves the town."
  - **Total $0.4636** (0.0284 + 0.2092 + 0.0132 + 0.0328 + 0.1800).

## Slice CA: composing through Architect's Regions.compose, the paid proof (2026-11-14, paid, Noah approved; $0.70 of a $3.00 cap)
Noah via parent session: "$3.00 hard stop (Recommended)" (up to 2 composes at no more than $1.00 each, hidden_village at no more than $0.55).
Branch compose-adopt (Architect 0.67.0), ports 8600/8601, the claude login's opt-in made in the worktree's `mod/run` for each run and removed
after it (`ls mod/run/architect/sidecar-data/` showed no `secrets.json` after each). Each compose is capped by Architect (`budgetUsd`).
- **(1) hidden_village re-recorded through compose, $0.3006 (cap $0.55):** `node tools/record-plans.mjs 0.55 --out
  fixtures/strange-asks/plans-2026-11-14-ca --only hidden_village`. COMPOSED after 2 rounds (round 1 REQUIRED_BLOCKED, round 2 READY).
  **The hidden room is written:** `hollow`, a `form.hidden_room` hosted by the waterfall (`falls`, sculpted on the flat claim), entrance the
  waterfall, required; all ten card buildings have their lots (missing none). The composer's `reason` names "a smaller hollow behind it": in game
  Secrets' scrub drops that sentence (the room's id), as it should.
- **(2) one plain card, farm_village, $0.0295 (cap $1.00):** PICKED: Architect's template pick fitted `flat_village` and the composer's rounds never
  ran, so the card's buildings were bypassed. Steward keeps it as a one-zone plan and says the layout is Architect's own (S-CA-1, the skip-pick
  key, is Architect's CP).
- **(3) in game, describe -> compose, $0.367 (cap $1.00: card $0.035, compose capped at $0.90 by `STEWARD_COMPOSE_CAP_USD`, spent $0.332):**
  `node tools/compose-proof.mjs 1.00 a walled market town beside the river where I stand, with a stone bridge over the water and a secret cellar
  under the tavern`, a default-terrain creative world. NO_PLAN after 3 rounds; Steward's own re-check REQUIRED_BLOCKED (the river channel
  refused LEAK, the bridge waiting on it), kept with the bridge pending. The cellar is written (`tavern_cellar`, `form.hidden_room` in the tavern),
  never named, the steward only hints. **Two Steward bugs it found, fixed at $0 since (961e48e):** the summary came out empty (the plain summary
  named the tavern, the cellar's host, so the scrub dropped it: now the count goes in its own sentence), and "Not in the plan: 1 stone bridge" for a
  bridge the plan held as a connector (now a card building written as another part counts by its kind).
- **Total $0.6975 of $3.00** (0.300551 + 0.029508 + 0.0346 card + 0.332437; gate-verifier PASS on the cap and the opt-in).

## The repair round's catalogue text, proved in one in-game compose (2026-11-13, paid, Noah approved; $0.16)
Noah via parent session: "n cap 1.50 + compose". One compose in a flat creative dev world on Architect 0.63.0 (main f66031d's code), the claude
login's opt-in made for the run and removed after: `/steward claim`, then `/steward describe set_1 A monastery on a floating island, reached by a
long stair.` (sky_monastery's ask, chosen because its climb is refused). Card $0.019 ("Stairway Monastery", 10 buildings); plan $0.144 over three
calls (Composer caps a compose at $1.00):
- **Round 0:** REQUIRED_BLOCKED, 1 part to repair; the repair round carried `planCatalogue([zone.standard])`, 1,373 characters.
- **Round 1:** the zone part fixed; REQUIRED_BLOCKED on the long stair; the repair carried `planCatalogue([connector.stair])`, 1,326 characters.
- **Round 2:** the stair rewritten as a `connector.switchback` with landings; still unfit. REQUIRED_BLOCKED after 2 repairs, 1 unfit, nothing pending.
So the repair round uses the catalogue text for exactly the failing kinds, and the first repair fixed its part. The last one can't: no stair or
switchback climbs 40 blocks to a floating island today (sky_monastery's known gap, Architect's "climbs to a raised form"), and the steward's summary
says the stair "can make the climb" when the check says it can't. That wording is a writer fault (LEFTOVERS). The driver was a one-off scratch
script built on `tools/record-plans.mjs`'s launch (it must set `ARCHITECT_GAME_DIR` to Steward's `mod/run`, or devclient reads Architect's token).

## Strange asks: all 59 plans re-recorded with today's writer (2026-11-10, paid, Noah approved; $2.64 of a $6 cap)
`node tools/record-plans.mjs 6 --out fixtures/strange-asks/plans-2026-11-10` on main 0d39359 (Architect 0.56.0), the claude login's opt-in made for the
run and removed after. 59 of 59 recorded, every one valid by Steward's plan check, about $0.03-0.07 each (the first call uncached at $0.28), in about 25 min.
`plans/` stays the tests' input. The two runs before it spent $0: the claude CLI had no login ("found no login"); `claude` + `/login` fixed it.
- **Gates:** 7 plans have a wall with 11 gatehouses, all 11 named on their wall (`at: "gate"`) with `mech: "portcullis"`, as in the 11-03 run.
  redstone_gate (the one drawbridge ask) writes a real `connector.drawbridge` (gatehouse_1 to claim_s, no waits); before, it was a reserved part.
  No plan writes a swing gate or a wall's `gateMech`: no card asks for a door or a lever gate, and both are opt-in (2026-11-03/04).
- **Kit score** (v0.56.0 main 2d9c5b15, asks claim, Steward's anchors; old `plans/` -> new): READY 38 -> 28, REQUIRED_BLOCKED 21 -> 22, NEEDS_REPAIR
  0 -> 6, INCOMPLETE 0 -> 3; refused parts 3 -> 10. Most of the drop isn't a fault in the plans:
  - 3 zones refused NO_ROOM with `alt: "street"` (grid_city, metropolis's capital, nomad_camp's ring): `Composer.streetFixes` repairs these at $0.
  - 3 quays and harbour walls INCOMPLETE (VOLUME): the kit can't read the water without a world (camp_to_city, fishing_village, harbour).
  - 2 reserved features now written where the old plans dropped them: pirate_cove's `layout: "elevated"` (7b), row_houses' `rule.variety`.
  - 4 gained: crater_mine, one_block, terraced_hill and two_districts are READY now.
- **Writer faults to fix forward:**
  - mushroom_town: `program.shared: "green"`, outside plan/0's enum (square, well, none): MALFORMED. Steward's plan check passed it, so it misses this enum.
  - cliff_monks: a stair climb (`sea_stair`) between ends of the same height: NO_CLIMB.
  - conquered_town and ruined_empire: a gatehouse lot in a town with no wall, which the zone can't place, and the road in ends on it (DEPENDENCY).
    14 unwalled plans write a gatehouse lot (12 in the old recordings); only these two have a part that depends on it.
  - rival_families: west_estate's shore band overlaps (OVERLAP); twin_villages: inn_stop waits on a village_road that doesn't fit.

### The writer's faults, fixed forward (2026-11-10, $0)
No paid re-record: each fix is shown on the recorded plans in `plans-2026-11-10`. The prompt rules were applied by hand and scored with the kit ($0).
- **The shared space.** plan-check missed mushroom_town's `shared: "green"`. The schema gives each zone only `partBase`, and the program sits behind the
  kit's `x-dispatch`, which Ajv doesn't follow. `checkAnswer` now dispatches each zone's program itself (template, lots or districts). It flags
  mushroom_town and a second plan the score had not named, hidden_village (also "green"). It is only the program because the rest of this schema copy
  lags Architect's kit schema (the wall's `walk` and `lots`, rule `over`, lot `refill`). Those are fields the writer uses and the kit accepts, so a full
  per-kind check flagged 13 good plans. `terraces` is copied in from the kit schema: the prompt already asks for it, and old_new_town's district carries
  it. The prompt now says `shared` is square, well or none, and that a green is a square.
- **cliff_monks:** the sea stair's ends rise 0 (NO_CLIMB). Written as a `path` it scores READY. Rule: a stair, switchback or ladder only where the land
  line gives a drop.
- **conquered_town and ruined_empire:** the edge gatehouse in a town with no wall isn't placed, and the road that ends on it is blocked. 12 other
  unwalled plans place theirs, so the gatehouse is not the fault. With the road ending on the zone, both score READY. Rule: a road into a town ends on
  the zone, never on one of its lots.
- **twin_villages:** inn_stop is placed `near` village_road, but zones are placed before connectors. Placed near inland_village, it scores READY. Rule: a
  zone is placed by an anchor, the land or another zone, never by a connector.
- **rival_families:** two zones `along` the same river always overlap: each takes the whole bank, whatever its side or distance. With one along the river
  and the other `near` its anchor, it scores READY. Rule: one zone per shore or river. If two estates on one river should work, that is an Architect ask
  (not filed).
The new rules take effect for the next recorded plans; the recorded ones are unchanged.

### The first nightly's stub FAIL (2026-11-10, $0)
The 23:00 nightly on 3986daf passed free (43 checks) and failed stub (30 of 40, 91 min).
- **Root cause:** at 23:08 the stub client's Architect launcher found only mise's node shim. The shim's `--version` probe failed ("is not node 22+ (did not
  run)"), so the launcher reported node-missing, the helper never started, and every card answered "no card". The free client minutes earlier found node
  fine, so the probe failed only some of the time, under load. Fixed in f946361: the e2e client's PATH starts with the node running e2e.mjs, and Architect's
  launcher looks at PATH before the shims.
- **The last check** ("DevBridge not reachable ... after 400s") was the restored-build relaunch at 00:33. The client was starting slowly, not crashed: 4.5 min
  to load its mods against about 11 s normally, with no stall in the log, so the whole machine was slow. The 400 s wait stays; this is a chance failure.
- **SUMMARY.md** printed "undefined failed", because e2e's `failed` is a count. nightly.mjs now lists the names of the checks that aren't ok, with repeats
  counted ("gate mechanism x4").

### The 11-12 nightly's two stub FAILs (triaged 2026-11-13, $0)
The 23:00 nightly on 074ca44 (Architect 0.63.0) passed free (44 checks) and failed 2 of 159 stub checks in 106 min. Both were the test, not Steward
or Architect; fixed on `nightly-fixes` (124c51c).
- **survival mechanism refund: lightning_rod 0/1.** The rod is copper and weathered where it stood during the long run: the ledger has
  `refundOverflow: {exposed_lightning_rod: 1}` (the stockpile was full, so it dropped). Architect refunds the block as it stands. The e2e's ledger
  readers now fold weathered copper refunds under the billed item; waxed copper doesn't weather. Architect confirmed the rule (slice CU, v0.40.0:
  the refund is the standing block's item, never an unweathered one the world no longer holds).
- **gate open again: ARTIFACT_STALE, grass_block where the wall wrote dirt_path.** The flow walls the gate up with cobblestone over its path; vanilla
  turns a dirt path under a solid block to dirt on its next tick, then grass spreads. Architect's refusal was right. The flow now lays the path again
  after the wall comes down.

### Growth slice A: region towns grow through Architect's revisions (2026-11-11, $0)
An addition to a standing region town (V or plan v1, not built from materials) is planned as a revision of the town (`planRevision`, `revise` with
`grow`), realised into it under the build's keys, logged as `revision:<region>:<rid>`, and undone by `undoRevision` (docs/GROWTH.md).
- **Reviews:** GPT's design review requested changes (5 blockers, 4 should-fixes), all taken into the design. The implementation review requested
  changes (2 blockers, 5 should-fixes); its first fix pass found 1 blocker and 4 should-fixes, and the second fix pass returned PASS on 1f74469.
- **e2e stub:**
  - `--only growth,revision`: PASS 41. The town grows by a library as revision 1, under the build's own lot ids. Undone, only the revision goes,
    with 0 cells changed. The town is then undone. A dev revision goes with the town.
  - The wider set `growth,village,stub,district,revision`: 57 of 58. The miss was the growth flow's own wait, fixed by moving the player clear.
- **Gate-verifier:** PASS on all five items, its own e2e run PASS 41. `npm test` in a worktree needs `node_modules` (a symlink to the main checkout's).
- **Saves:** settlements format 6, builds format 4; a real format-5 fixture (fixtures/saves/v5).

### Growth slice B: street towns taken on as regions (2026-11-11, $0)
A street town's first addition takes it on as one Architect region (`Regions.adopt`, nothing written), then grows it by a revision (slice A).
The adoption's intent is saved in the history first (docs/GROWTH.md, slice B).
- **Reviews:** GPT's design review requested changes (3 blockers, 5 should-fixes); design v2 was approved, with two clarifications folded in. The
  implementation review requested changes (1 blocker, 3 should-fixes); the fix pass approved 1708516.
- **e2e stub:**
  - `--only stub,adopt,growth`: PASS 41.
  - `adopt`: the street town is taken on as one region with 0 cells written, and grows by revision 1. Undoing the growth leaves 0 cells changed.
    Undoing the adoption lets the town go: the old builds' 5 sites stand in no region. Undoing the founding build leaves the ground as before the
    town.
- **Unit:** 339, 0 failures.


### Copies follow their source: Architect FS (2026-11-11, $0)
Once a source's own lot stands at a newer version, its copies are re-made from that version at $0 (`Designs.followAll`), and each copy's lot is
offered as an update that always waits for the player (docs/FOLLOW.md).
- **Reviews:** GPT's design review requested changes (3 blockers, 5 should-fixes), and they were taken as listed in docs/FOLLOW.md "Review". The
  main changes: the key covers the copies with their heads, one call runs per source, a lost answer is retried in 30 s, offers are re-checked in
  `approve`, and every COPY version counts.
- **e2e stub:**
  - `--only follow`: PASS 16.
  - The flat village's two hut copies, at Full: the change asked of a copy goes to its source, and the source applies by itself. Then `followAll`:
    both copies followed, each offered as ASK, applied and reverted, and told once.
  - An earlier run's seed gave one copy `bar`. After the restart its new member set re-made it and it was refused again, $0, and not told twice.
  - The sim's SMALL stand-in (a shed) has no int param, so its `versionOf` writes nothing. The flow makes the huts M.
- **Fix pass (GPT round 2):** request changes, 1 blocker and 4 should-fixes, all taken (docs/FOLLOW.md). Then `--only follow,revision,survival`
  (which pulls in `stub`): 69 of 70 passed. The one failure was the seed: both copies followed with nothing to write. The flow now builds up to 4
  villages, as Architect's FS gate re-made families.
- **`--only follow` after it:** PASS 23. Village 1 had cabin 2 followed with nothing to write, and cabin 3 refused `bar`, told once and not offered.
  Village 2 had cabin 2 offered as ASK at Full, applied and reverted.
- **Unit:** `FollowPlannerTest` 12, all of `build` passes.

### The steward sees what changed in its town: Architect LC (2026-11-11, $0)
`/steward check <id>`, "what changed?" said to the steward, and a look every 20 minutes while a player is in the claim read `Regions.landCheck`
over the town and district regions, word it (`LandWords`) and keep one `LAND` inbox row per settlement (docs/LAND.md). Nothing is repaired or saved.
- **Review:** GPT's design review requested changes (2 blockers, 5 should-fixes), all taken (docs/LAND.md "Review"): a look with a gap is unknown,
  not zero; the damage signature is per region and part or lot, not a sum; repeated roles are told apart by their lot; the row is capped at 6 lines
  with its time; the regions are re-read before a look is kept.
- **e2e stub, `--only land`** (ports 8594/8595):
  - Run 1: 1 FAIL. The look ran but the command's late answer went to DevBridge's captured source, gone by then; it now goes to the player's chat.
  - Run 2: 3 FAILs. A 10 s reuse of the last look answered "nothing changed" right after the player broke a block; it became a 3 s "I just
    looked" limit.
  - Run 3: **PASS 14.** A flat village is looked over: "I found nothing broken or changed in what we built". A player (`dev.pt.breaks`,
    `dev.pt.uses`) breaks two smokehouse cells and puts cobblestone back in one; Architect's report reads that lot changed 1, playerPlaced 1,
    nothing else. The steward says "The smokehouse: 1 block broken or changed, 1 placed by a player." and nothing more. The inbox shows the row
    (`e2e-land-inbox.png`). "what changed?" is answered the same with no talk job. "Got it" clears it.
- **Unit:** `LandWordsTest` 14, `LandChecksTest` 7; all of `build` (372) and `npm test` (35) pass.
- **Design critic** (`e2e-land-inbox.png`): iterate. Taken: "Got it" blocks nothing, so it is the secondary button, not clay (run 4: PASS 14).
  Left for the whole inbox, not this row: the muted token's contrast on cream, the footer's "↑↓ build" label on a non-build row, spacing
  between a row's headline and its lines.

### Growth slice A2: a town built from materials grows from materials (2026-11-11, $0)
An addition to a Supplied region town (built from the stockpile, Architect RS) is planned and realised as a revision in the town's mode, watched
until built, billed from the stockpile, refused undo while its own targets build and undone exactly once built (docs/GROWTH.md, "Slice A2").
- **Reviews:** GPT's design review requested changes (3 blockers, 5 should-fixes, 2 nits; all but one should-fix taken, the shared lost-realise
  lookup left as a follow-up). Fix check 1: every finding resolved but the orphan path (1 blocker, 1 should-fix). Fix check 2: the blocker resolved;
  3 small should-fixes, fixed without a third round (the slice's limit).
- **e2e stub:**
  - `--only supplied-growth` (new flow, through Steward only): PASS 14. A Supplied town of 2 buildings fed until built; a library accepted grows it
    as revision 1 under the build's keys; undo while 1 of its 2-3 targets still builds is refused in plain words with 0 cells written; fed until the
    steward says the town is built; the growth's bills (drawn plus owed when it was put down) = what the stockpile paid; undone: revision UNDONE, the
    ground as before the growth (but the stockpile's crates), the refund equal to what was paid and nothing more 20 s on; then the town undone.
  - `--only supplied-growth,survival,realise,adopt` (every survival flow, slice A's growth and village, slice B's adopt, the sanity set): PASS 60
    in 991 s, one skip as before (the walled supplied town's dev growth, `NO_ROOM [street]`).
- **The old dev-call check's bills** (`survivalGrowth`) read `dev.rs.state` targets' `bom`, which Architect never sends (only `outstanding`), so
  it could not have passed once a walled town grew; the new flow reads the stockpile's ledger.
- **Unit:** all of `build` (380) and `npm test` (35) pass; `npm test` in a new worktree needs `npm ci --offline` first.

### Phase 5 slice M1: the module catalog (2026-11-12, $0)
The catalog format, gating, palette constraints, `steward_mc:module` bindings and BuildStore format 5 (docs/MODULES.md, "M1 as built"), checked
on the fixture module `fixture_plot@1` (2 sides x 2 crops x 3 Styles) with `tools/module-catalog.mjs`:
- **offline** (Architect's kit at v0.62.0, kit 0.57.0, archived from the tag): PASS 19. The pinned sha; module.json's params = the program's;
  all 12 branch x Style builds checker-clean (110 and 258 blocks, no warnings); a soul-lantern lamp (light 10) becomes the plain lantern and
  is said; gravel as `path` or `frame` is refused (`palette: ...`); the lot's reserved cells are left open.
- **ingame** (`tools/run-e2e-client.sh` on 8592/8593, a fresh flat creative world, the bundled 0.62.0 helper, no login): PASS 19 in 37 s.
  Minecraft 26.3, Architect 0.62.0, `givenPrograms`; the gate offers it; `Designs.fromProgram` installs it at $0 under
  `steward.catalog.fixture_plot.v1.f613be4e3299` (the entry's `givenProgram.sha` and key recorded) and the key replays to the same entry;
  12 keyed `Designs.variant`s, one per branch x Style, each installed (the checker passed) with the catalog's sha and its Style; `side 7`
  refused by Steward before Architect is asked; `variantByKey` finds a variant, an unknown key finds none.
  - The first run never reached the bridge (the tool did not set `ARCHITECT_GAME_DIR`, so no token); the second found that Architect's keys
    take no `@` (the catalog key became `...fixture_plot.v1...`).
- **GPT second opinion** (gpt-6.1-sol, max, at 4e5cdd6): 1 blocker, 7 should-fixes, all taken. The blocker: measured.json did not certify
  anything (a failed or bar-less file, or an envelope widened after it, still offered). Now a certificate needs `result: pass`, the program sha
  and module.json's `moduleSha`, on the pinned kit and checker, written only by `record` from passing runs over the same files. The others:
  bibles resolved to an exact version before the check; variants only from the entry's own install; strict binding and ext values; a dropped
  newest row blocks the module; the tool never drives or quits a client it did not start; the hook runs in a development environment only.
- **After the fixes:** offline PASS 18; ingame PASS 20 in 125 s (the plain gate: not certified, nothing offered; a measuring run installs it and
  its 12 variants, each with its bible pinned at v1 and made from the catalog install); `record`; ingame again PASS 20 in 36 s, the plain gate
  now offering it.
- **GPT fix check** (at ef6d016): 5 of 8 resolved; 3 partial and 1 new should-fix, all fixed (the slice's one fix check, no further round):
  integers in a binding or the lot ext read exactly (`1.0000000000000001`, `1e0`, `4294967297` refused); `record` checks the checker it ran
  (the kit's own, its version the kit's) against the pin; the tool takes an exclusive claim on `mod/run` before launching and fails at once if
  its client exits; a module certified for another Architect version reads as refused for the version, not a failure, so a pin bump can be
  re-certified (the versions of an unchanged certificate are kept). Ingame again: PASS 20 in 40 s; recorded.
- **Unit:** `ModuleCatalogTest`, `PaletteCheckTest`, `ModuleRefTest`, `BuildStoreFormat5Test`, SaveCompatTest's format-4 growth file
  (`fixtures/saves/v6`); all of `build` passes (402).

### S-J4 and S-kit: Steward on lab-ui's shared HUD (2026-11-13, $0)
Steward publishes its inbox into lab-ui 0.4.1's Jobs & spend panel (`inbox/InboxJobs`): a build's row is the settlement's Architect owner
(`steward_mc:settlement/<id>`), so the Architect jobs it owns fold under it, and updates and ideas get rows of their own. StewardHud's line at
(6, 6) is gone in favour of the shared needs-you pill, and Steward's toast copy and `ToastStack` in favour of lab-ui's one stack. Each toast
carries its build's row as its `ref`, so K opens the panel on that row. Steward publishes **no ledger**: Architect's ledger already lists every
Steward-owned sidecar job and credits it to `steward_mc` by owner, so entries under any other id would count the same spend twice. S-kit swaps
in lab-ui's `clayText()`, `placeholder()`, `Panels.header` with meta and `selectedRow`/`hoverRow`, plus its text classes and `UiRules`. The map's
raw hex becomes tokens (`CLAY_DARK` at 0x60, `paper.disabled`).
- **e2e** (ports 8600/8601): the new `shared-hud` flow (tags `screens inbox hud jobs`) checks:
  - Steward rows in `JobBoard.json()`;
  - the pill counting both mods;
  - exactly one `lab_ui:jobs_pill` slot in TOP_LEFT, and no `steward_mc:*` HUD element;
  - Steward's toasts in the one stack beside an Architect toast;
  - pictures `e2e-shared-hud`, `e2e-jobs-panel`.

  In stub mode it also waits for a real Architect massing (sim helper).
- **Runs:**
  - `free --only screens,inbox`: PASS 30.
  - `stub --only hud`: PASS 15 ("4 jobs need you": Steward's 2 and Architect's massings; today `{"architect_mc": 0.38}`, sim costs).
  - The first stub toast check failed because Architect's massing bar is an avoided rect, so the column ended above it and Steward's toasts
    waited under "+3 more". The check now counts the stack, not only the toasts on screen.
- **Unit:** `InboxJobsTest` (6).

### Phase 5 slice M2: crop_farm@1 measured (2026-11-13, $0)
`node tools/module-measure.mjs <step>` (ports 8594/8595 via `STEWARD_PORT`/`STEWARD_DEV_PORT`, default 8595; its own client and fresh
world per step; `--attach`, `--keep-client`). The bars and results are in docs/MEASURE-M2.md, the design in docs/MODULES.md "M2 as built".
- **Steps.**
  - `field --crop <c> [--runs 5]`: items 1, 2, 3 and 5 over the 9 sizes, on an ew and an ns town.
  - `skin --bible <b>`: items 1a, 2 and 4, with spiders recorded.
  - `survival`: items 6 and 7, in a survival-mode world.
  - `bounds`: 108 QT quotes, never realised.
  - `border`: the 9x9 and 27x9 sizes on chunk corners.
  - `cover`: the envelope's facing and border bars, no client.
  - `bands`: item 3's predicted bands, no client.
  - `look`: geometry only, never judged.
- **Then** `tools/module-catalog.mjs offline|ingame|record --catalog modules` (ingame with `STEWARD_DEV_PORT=8595`). Times: about 4 hours in
  all, the four field steps 28-41 min each.
- **Harness lessons.**
  - Random ticks run only in block-ticking chunks: every field stands within simulation distance 16, which the tool writes to options.txt.
  - `/tick freeze` is not saved, so the restart check saves with random ticks off.
  - A removal waits a settle bound after draining, so never undo frozen.
  - A survival-mode player dies from a teleport's fall and then loads no chunks: fall damage off.
  - A region bills only in a survival-mode world (`ARCHITECT_AUTOWORLD_MODE=survival`).
- **Shakedowns** (not recorded; MEASURE-M2.md "Candidates"):
  - candidate 1's composter and chest outside the fence let mobs climb over it;
  - candidate 2's path under them turned to dirt.


## P065: the Architect 0.65.0 pin's follow-ups (2026-11-14, $0)
- **pin-guard:** `ArchitectGateway.check()` reads the loaded Architect's `apiVersion()` (TJ, API 1.58.0); the reflection fallback is gone (fabric.mod.json keeps `>=0.65.0`).
- **Plan schema:** `schema/plan-0.json` is now the 0.65.0 jar's `architect_mc/schemas/plan.json` byte for byte (catalogue 9 -> 22), held so by
  `PlanWriterJobTest` (a pin whose schema moves fails until the copy is refreshed). The mod reads `Regions.planSchema()`; the inline prompt slims the
  catalogue as `plan/0#compact` does. The prompt drops its frozen-copy provenance and the "reserved layout" rule (every layout is supported).
- **Re-score ($0, Architect v0.65.0's kit, the asks claim):** Architect's copy of the 59 plans: fits 67, adjusted 18, blocked 20, refused 14,
  unsupported 9 (as AQ's table). Steward's plans (five re-recorded since Architect copied them): fits 70, adjusted 19, blocked 20, refused 9,
  unsupported 8, **malformed 1**: hidden_village's `hollow` (form.hidden_room with `where`, no `host`; recorded while the kind was reserved). The
  other moves are the 11-13 re-records (palette rules into styles; mage_exiles NO_ROOM; old_new_town's stair now a path blocked on its zone).
- **Checks:** `stub --only street,plan,stock-save` PASS 102 on main d4a735a merged. Street towns (village, adopt, adopt-ns, plan build) place as
  Blueprints (Steward never sets `allowComposite`). The plan build's region ghost (`e2e-hidden-room-ghost.png`, 25,090 cells, secret parts
  `f_secret_cellar`, `f_secret_cellar_door`), the camera on the cellar's trapdoor cell from the IR: plain ground there, no trapdoor or ladder
  shaft. The cellar itself is underground, out of shot; Architect's `hr-ghost` gate checks the cells.
- **Leftover:** `plan-check.mjs` passes hidden_village (`[]`) though the kit calls its room malformed: it still follows only the program dispatch,
  not each kind's def, so the jar schema alone doesn't close that LEFTOVERS item.

**The part writer was temporary (slice CA, 2026-11-14):** since CA2 (above) a waiting part's own plan is composed by `Regions.compose`.

## P0's seven: the parts that didn't fit in P0's CI, re-scored on Architect 0.70.0 (slice PV1, 2026-11-15, $0)
`checkPlanLocal` from v0.70.0's kit (catalogue 24) on the asks claim with Steward's anchors, over the first recordings (Architect's
`asks-8703dbd` copies), `fixtures/strange-asks/plans/` (the tests' input) and `plans-2026-11-10/`. No paid re-record.
- **arcology/unused_placeholder** (a zone with no lots, NO_ROOM): still refused on the first recording; today's writer writes no lotless zone (plans/
  `tower_site` fits). Fits now. (11-10's `tower_floors` rule names a lot, not a part: MALFORMED UNKNOWN_REF, a writer fault for the leftovers.)
- **one_block/gentle_terraces** (a terrace inside its zone, OVERLAP): the writer puts terraces beside the zone; plans/ `terraces` fits, 11-10 READY. Fits now.
- **terraced_hill/terraces** (the same OVERLAP): plans/ `terraces` fits; 11-10 READY. Fits now. (plans/ `switchback_street` is blocked on an unwalled
  gatehouse, the 11-10 writer fault fixed forward.)
- **rival_families/estates** (no river on the asks claim, NO_FEATURE): written as two bank zones; plans/ READY (11-10's `west_estate` OVERLAP is the
  shore band fault already listed). Fits now.
- **underwater_domes/colony** (no ocean, NO_FEATURE): a named wait. The colony depends on `form.seabed_site`, reserved "until later" in 0.70.0, and
  the plan keeps it pending with Claude's words. Asked: S-PV1-2 (ARCHITECT-ASKS).
- **spiral_town/spiral_road** (a 3-wide street between one village's lots, UNREACHABLE): the writer writes `layout: "spiral"` (FL) instead; plans/ and
  11-10 READY. Fits now.
- **spiral_town/hill_relief** (blocked by the road): gone with it. Fits now.

## Slice PD: a plan's unbuilt district proposed in the inbox (2026-11-15, $0)
Strict tier (billing). Unit: `PlanDistrictsTest` (8), plus the scoped `PlanGrowthTest`, `SurvivalDistrictTest`, `ProposalRulesTest`, `BesideRefusalTest`,
`PendingOffersTest`, `SaveCompatTest`, `ModuleSavesTest`, `DistrictGrowthTest`, `InboxModelTest` (86 in all, 0 failed). `npm test` 74 pass.
e2e: `STEWARD_PORT=8592 STEWARD_DEV_PORT=8593 node tools/e2e.mjs stub --flows plan-district`, PASS 15 on run 3. The flow is in docs/DISTRICTS.md section 13.
- **Run 1** was a test fault. The district was added to the kept plan before Start, so the town built it. It is now added once the town is placed.
- **Run 2:** the sim's kit massing failed its checker on 11 by 11 and 9 by 9 lots of other types. The flow now uses no_wood's 13 by 14 cottages. That
  run also showed that a build which fails after its paid request keeps the acceptance and leaves the reservation unbuilt. Such a reservation is
  now offered again (`PlanDistricts.toOffer`).
- **Run 3:** not proposed before the town stood or while it was 8% built; proposed once (E). Accepted: reserved, and "From materials, Mill Ward
  needs about ..." said before the one approval. Built as rg2 with the plan's lot ids, consumed within its inexact quote. Undone with everything it
  consumed back once and nothing more 10 s on; given up and not proposed again.
- **Batch GPT review fixes (11-15), merged with batch-072b at 9e2f74f:**
  - A re-offer takes its reservation's side (`PlanDistricts.resumeWay`).
  - A district with no side to go is skipped until the town region, the plan or the districts change (`fingerprint`, `withoutNoSide`), and the
    minute's pass runs on.
  - Module lots are left out as M5 reads them (`ComposedModules.asked` over `LOT_TYPES`, failing closed).
  - Unit tests: `PlanDistrictsTest` 11; the scoped suites with ComposedModules 114, 0 failed. `npm test` 75. Rerun of `--flows plan-district`: PASS 15.
- **PD-2 (11-16, light, $0; no in-game run per slice, the next batch's run covers it):**
  - The flow composes no_wood's plan with an optional districts zone `wards` whose one district is 256 by 256. It has no candidate in the claim, so
    Architect refuses the zone NO_ROOM. The overall is NEEDS_REPAIR, with an artifact. Start leaves the zone out, and the designs come from the
    artifact's lots. The kept plan still names the ward, and not as pending; a new step, `plan district plan keeps it`, checks that. The dev
    `plan_district` step is gone (the command stays).
  - North and south: Q3 is kept (docs/DISTRICTS.md section 13, "Side"). Ground found only to the north or south is said in the proposal, and no
    side at all is said once.
  - The restart mid-build is left to SD's flow.
  - Unit: `PlanDistrictsTest` 13, `SurvivalDistrictTest` 18, `ProposalRulesTest` 8, `DistrictSiteTest` 6, `ModuleProposalRulesTest` 6, 0 failed.

### LUI: lab-ui 0.5.0 (2026-11-16, $0)
Pinned lab-ui 0.5.0 (`>=0.5.0 <0.6.0`). Dropped the unused `ClientEnv`, `ScreenPause` and `HasParent` copies (lab-ui has them now), the
disabled-primary workaround in `KitScreen` and the talk field's leading-space caret workaround. Steward's ideas rows (`#proposals`) are offers
(`JobRow.offer`): listed under Ideas on K, and on lab-ui's idle pill ("N ideas for you") while nothing waits. Kept on: the pill is muted and
never urgent, it is the only HUD sign of ideas, and `JobBoard.showIdlePill(false)` would hide Architect's offers too.
- **Unit:** `InboxJobsTest` (offer, Ideas group, idle pill text only when nothing waits); npm 74/75 (the one fail, smoke-carry's moduleSha, is
  the base's).
- **e2e** (ports 9116/9117, `STEWARD_RAM_TMP=0`, the RAM disk was full): `stub --only screens` PASS 20, `free --only talk` PASS 12. New
  dev samples `/steward ui sample-ideas|sample-empty <id>`; pictures `e2e-idle-pill-off`, `e2e-idle-pill`, `e2e-jobs-panel-ideas`.
- **design-critic:** ship, no blockers. Minor: drop " · ideas" from the idea row title, shorter idea summaries, a neutral dot for ideas.
