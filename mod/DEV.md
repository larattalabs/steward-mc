# Building Steward

Same toolchain as Architect (Java 25, Gradle 9.7.1, Fabric Loom 1.18.2, Minecraft 26.3).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
export GRADLE_USER_HOME=$PWD/../.gradle-home   # an APFS clone of Architect's cache: cp -c -R ../architect-mc/.gradle-home .gradle-home
./gradlew test --offline
```

First build without a cache needs network (Minecraft download and decompile). `.gradle-home/` is gitignored.

## Architect dependency (temporary snapshot)
Architect's artifact is not published yet, so `api-snapshot/` holds a compile-only copy of its API types (see its README) in an
`apiSnapshot` source set that is never on the runtime classpath or in the jar. The dev client can't run Steward until Architect's
real mod is on the classpath. When Architect publishes (GitHub Packages) or from `publishToMavenLocal`:
1. delete `api-snapshot/` and the `apiSnapshot` source set and its task wiring in `build.gradle`;
2. add `modImplementation "dev.larattalabs:architect_mc:<version>"` (and the GitHub Packages repository if not using mavenLocal);
3. fix whatever the final API changed in `gateway/ArchitectGateway.java` (the only class that touches the API).

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
