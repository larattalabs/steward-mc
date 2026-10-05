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

When Architect's GitHub Packages publish is set up, add that repository (read token: `GITHUB_TOKEN` with `packages: read` in CI, a PAT with
`read:packages` elsewhere). The API is `dev.larattalabs.architect.api`; `gateway/ArchitectGateway` is the only class that should touch it for
checks, and `gateway/ConceptCardJob`, `gateway/LotBrief` build its job and design types.

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
