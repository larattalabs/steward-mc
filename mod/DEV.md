# Building Steward

Same toolchain as Architect (Java 25, Gradle 9.7.1, Fabric Loom 1.18.2, Minecraft 26.3).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
export GRADLE_USER_HOME=$PWD/../.gradle-home   # an APFS clone of Architect's cache: cp -c -R ../architect-mc/.gradle-home .gradle-home
./gradlew test --offline
```

First build without a cache needs network (Minecraft download and decompile). `.gradle-home/` is gitignored.

## Architect dependency
`build.gradle` depends on `dev.larattalabs:architect_mc:${architect_version}` (gradle.properties, currently 0.4.0, API version 1.0.0) from mavenLocal. Build and
publish it from Architect's checkout (or any clone of main at bac28db or later):

```sh
cd ../../architect-mc/mod && ./gradlew build publishToMavenLocal      # -> ~/.m2/repository/dev/larattalabs/architect_mc/0.4.0/
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
