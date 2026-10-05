# Steward (working name)

A Fabric mod for Minecraft 26.3: found a settlement, describe it in your own words, and a Claude-driven NPC steward designs, builds and keeps evolving it. Sibling to [Architect](../architect-mc), which provides generation and placement.

Status: plan only. See [docs/PLAN.md](docs/PLAN.md) and [docs/ARCHITECT-ASKS.md](docs/ARCHITECT-ASKS.md).

## What exists so far
- `schema/concept-card.schema.json`, `prompts/concept-card.md`, `fixtures/`: the concept card and the prompt that fills it
  (a `structured` job through Architect's `job.run`). `npm install && npm test` validates the fixtures against the schema.
- `mod/`: the Fabric project scaffold (Java 25, Loom, MC 26.3), written against Architect's draft API contract via a compile-only
  snapshot of its API types. Builds and passes 8 unit tests; not runnable in a client until Architect's real mod is available. See `mod/DEV.md`.
- `docs/skills/minecraft-structure-design/`: the builder playbook and its helper scripts (handed to Architect).
