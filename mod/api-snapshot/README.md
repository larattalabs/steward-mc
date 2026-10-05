# Architect API snapshot (temporary, compile-only)

Copied on 2026-10-05 from Architect's in-progress work (an agent worktree of `larattalabs/architect-mc`, package
`dev.larattalabs.architect.api`, version string `1.0.0` in the source) so Steward can compile before Architect's artifact exists.
It follows `architect-mc/docs/CONTRACT.md` "Phase 4a contract: public API" at commit b869adc, but the source was uncommitted
work in progress, so signatures may differ from the final API.

- Compile-only: never shipped, never on the runtime classpath. At runtime Steward uses the real Architect mod (`depends`).
- `ArchitectApi.get()` is patched to throw (the real one calls Architect's internal `ApiImpl`).
- Delete this directory and the `apiSnapshot` source set in `build.gradle` and add
  `modImplementation "dev.larattalabs:architect_mc:<version>"` once Architect publishes (GitHub Packages) or from mavenLocal.
- Do not edit these files; re-copy from Architect, or from the published sources jar.
