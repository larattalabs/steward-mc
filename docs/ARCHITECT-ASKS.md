# Asks for Architect (from Steward)

Steward (sibling mod, see PLAN.md) is generative-first and depends on Architect for generation and placement. These
are the Architect-side changes it needs. They land in `architect-mc` first, through the Architect session, and each
should be useful on its own for Architect's players. Order is by what Steward phase 1 needs.

| # | Ask | Why | Notes |
|---|---|---|---|
| A1 | **Style bible**: a first-class artifact (JSON + prose) generated once from a prompt and fed to every design job | Many separately generated buildings must read as one place | Versioned; stored with the library entry; cacheable prompt prefix |
| A2 | **Hierarchical / parallel jobs**: a parent job spawns N building jobs, shared bible, per-job model tier (Opus landmark, Sonnet ordinary), aggregate progress and cost | Wall time near one design for 8-20 buildings | Rate-limit aware; partial results usable |
| A3 | **Massing pass**: a cheap coarse volume design shown as a ghost before the detail pass; "approve or redirect" | Fixes "wanted something else" cheaply | Detail pass takes the approved massing as input |
| A4 | **Critique loop**: render, Claude reviews its own iso/top/front images (and neighbours'), revise, bounded rounds | Quality without a human per building | Same pattern as AgentCraft's design-critic |
| A5 | **Macro kit + macro checker**: carve, platform, pillar, bridge, stair, cavern, noise, ring, terrace, lot, road, utility; checker for reachability, headroom, support, fluids, dark spawn, size cap | Rift, sky city, crater, castle are layout programs | Terrain operators: natural blocks only, snapshotted, block count shown |
| A6 | **Delta apply**: diff the new build of a source against what is placed; ghost shows added/removed/changed; apply only the delta | Evolving a settlement without a full rebuild | Builds on `Reconcile` |
| A7 | **Batch placement API** for the mod: queue many placements, spread over ticks near the player, chunk-load aware, one undo group | City scale | Public Java API surface that Steward calls |
| A8 | **Public API / extension points**: library, jobs, ghost and sites callable from another mod; a stable sidecar protocol version | Sibling mod must not copy Architect | Version-negotiated; documented in CONTRACT |
| A9 | Phase 3 (survival sites, crate, ledger) as already planned | Supplied/Hardcore difficulty for Steward | No change, just the order |

Not asked of Architect: the concept card, site survey, steward entity, proactive triggers, permission/difficulty,
inbox/HUD/hub, modules, villagers, animals. These stay in Steward.

Coordination: Architect owns its repo. Steward does not edit it. Proposed changes go to the Architect session as a message;
it decides ordering and records them in `architect-mc/docs/PLAN.md`.
