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
  `Difficulty`, `Permission`.
- `../fixtures` are shared with the schema tests and with `ModelTest` (test resources).
