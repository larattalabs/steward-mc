# Review of Architect's phase 4a contract (commit f83553a)

Reviewed 2026-10-05 by the Steward session. Source: architect-mc/docs/CONTRACT.md, "Phase 4a contract: public API". Architect
builds the unlikely-to-change parts immediately and the jobs API and PlaceRequest shapes after this review.

## Fine as written
- `actor` rule for INSTANT in a survival-toggle world (matches PLAN: Patron never bypasses the world toggle); AUTO/CONSTRUCTION as the normal path.
- Owner and requester as guardrails, ext on sites and entries, ports; typed refusal reasons; `check()` dry run with BOM.
- Tool handlers registered globally per (owner, name) at init, so resumed jobs still reach them; `list(owner)` for results finished while away.
- Both job kinds through the Agent SDK only (login mode works), native json_schema output, built-in tools off, hard budget plus cumulative check.
- Protocol negotiated in `hello`, protocol 1 clients keep working; events on the server thread; cost with cache tokens.

## Must change before freezing
1. **Design requests have no Java API.** Steward's core call is "design a building for this lot": type, style, size cap, notes,
   plus owner, ext, model, budgetUsd (the contract adds these fields at protocol level only). `Jobs` has `structured` and `agent` only.
   Add `Designs.request(DesignRequest) -> CompletableFuture<Library.Entry>` (or a `design` job kind) with progress and cost events,
   cancel, and a `bible`/`group` field reserved for 4b.
2. **The Library is read-only.** Steward needs: `makeVariant(entryId, palette, values) -> Entry` (the free ~0.5 s re-run, used for
   re-skins), remix, delete, and setting `ext`/tags on an entry. These exist as protocol messages (variants); expose them in Java.
3. **Survey payload goes through the agent.** The contract says `Sample.toJson()` "is what `job.run` passes to the agent", but a 256x256
   sample is tens of thousands of cells: far too big for an LLM context, and above the 256 KB tool-result limit. Region programs (A5b)
   need the full survey in the **sidecar** (kit JS), not in the model. Add: (a) upload a survey to the sidecar as a blob handle
   (`job.blob.put` / `survey.id`) that kit programs read from the scratch dir; (b) the agent gets a **summary** (stats, a hillshade image
   or coarse ASCII grid) and a handle; (c) a higher or per-tool result limit for blobs.
4. **Tool-call timeout vs a paused game.** Handlers run on the server thread with a 60 s answer deadline. In singleplayer, opening the
   pause menu stops the server tick, so handlers do not run and jobs fail with "the game did not answer". Pause the timeout clock while
   the game is paused, or allow a per-tool `timeoutMs` and run read-only queries off-thread.

## Should change
5. **Survey**: synchronous `sample()` over a large box would stall the server thread, and unloaded chunks are only reported as missing.
   Steward needs to look at terrain it has not loaded (finding a crater 500 blocks away). Make it time-sliced/async, add an opt-in
   `LOAD` policy (tickets, bounded), and add `topBlock` (surface material), a slope grid, a tree/leaf mask and a `natural` mask.
6. `ArchitectApi.features()` (a `Set<String>` matching the protocol features, plus `siteGroups`, `jobGroups`, `deltaApply` as they ship)
   so Steward degrades gracefully across Architect versions instead of comparing version strings.
7. **Composite preview.** `preview()` takes one blueprint. Steward's massing and delta ghosts need many pieces at once with
   per-cell state (added, removed, changed): add `previewCells(...)` or `previewBoxes(...)` (needed by 4c/5b).
8. `Sites.list(owner)` filter, a `SITE_MOVED` event, design-job events (`DESIGN_UPDATED`/`DESIGN_DONE`) alongside `JOB_*`.
9. **Distribution.** `publishToMavenLocal` works for local development but breaks Steward's public CI. Publish the artifact (GitHub
   Packages or the Modrinth Maven) and say which version range Steward should pin.

## Not asked
No change to Mode/actor semantics, owner strings, protocol numbering, budget handling or the apitest gate. The gate should add one
case for each must-change item (a design request round trip, a variant through the API, a blob handle read by a kit script, a tool call
across a paused game).
