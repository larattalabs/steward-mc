# Copies follow their source (Architect FS)

Status: design, 2026-11-11, revised after the GPT design review (request changes: 3 blockers, 5 should-fixes; see "Review" below). Architect's side is `../architect-mc/docs/CONTRACT.md`, "Phase 6d slice FS" with
its as-built and FS-b sections (Architect v0.58.0, pinned 0.59.0). Steward's answers so far: S-FS-1..4 as proposed, S-FS-5 confirmed,
S-FS-6 yes (built in FS-b). Spend: $0 (a follow is kit work, no Claude call).

## Today

- A build's group request makes `$0` copies (`CopyPlan`: of every 3 lots of one role, type and size, the first is designed, the others
  copy it; Architect 0b). Each copy is its own library entry, `derivation {source, sourceVersion, kind: COPY, recipe}`, standing on its
  own lot (a street town's site, or a lot of a flat village's region).
- A change request (`ChangeRequests`, `DesignRequest.versionOf`) makes the source's next version. `Updates` sees the source's lot
  outdated and offers it (check, preview, apply keeping edits, `UPDATED` history, `Revert`). The copies are left as they were.
- A change asked for a copy is refused by Architect (`VERSION_REFUSED copy`), and asking for "the fisher's hut" when three stand finds
  no building (`ChangeRequests.building` needs one match).

## The rule

**Once a source's own lot in a settlement stands at a newer version than its copies were made from, the copies are re-made from that
version ($0, `Designs.followAll`), and each copy's lot is offered as an ordinary update that always waits for the player.**

1. **When (S-FS-1).** On every update scan (`Updates.refreshAll`: `ENTRY_VERSIONED`, world start, after an apply or a revert),
   before the outdated sites are listed, `Follows.scan(server, s)` runs for each settlement:
   - `standing`: for each blueprint of the settlement's standing building sites, the highest version a site of it stands at;
   - for each such blueprint `e`, `Library.followers(e)` filtered to `kind == COPY` and `sourceVersion < standing(e)`;
   - none: nothing. Else `followAll(e, standing(e), opKey)`.

   The target is the version the source **stands at**, never 0 (head). So a source version the player skipped, or reverted away from,
   never reaches the copies, and a head that moved while nothing stands at it is not followed. Both ways a source changes in Steward (a
   change request's update applied by the player, or applied by the permission level) end at this point.
2. **opKey:** `steward_mc:follow:<source entry>:v<target>:<h>`, `h` a short hash of the copies to follow with their heads
   (`copy@head`, sorted). Deterministic, so a restart mid-operation resumes Architect's saved operation and a replay returns the saved
   results ($0, nothing installed twice). Not per settlement: two settlements at the same source version share one operation. A copy
   whose head moved (`head_moved`) or a copy made later is a new member set, so a new key and a fresh operation (review 1, 5); a refused
   copy keeps its head, so the same key replays its refusal and it is not re-made. Kept per session in `ASKED`; **one `followAll` in
   flight per source**, globally.
3. **The answers, per copy:**
   - `followed`: the copy's head is now `newVersion`, `by: follow`. Its lots show in `Sites.outdated`; nothing else is done here.
     One history note per operation: "The <source>'s copies (<lots>) follow its new design (v<n>): their updates wait in the inbox."
   - `alreadyFollowed`, or `head_moved` (another follow got there first; a copy changes only by following): nothing said.
   - refused (`lot_too_small`, `frame`, `check`, `bar`, `fit`, `bible_gone`, `source_gone`): the copy stays at its version, nothing is
     spent, and the player is told once, in words (`FollowPlanner.refusalText`), as a history note naming the copy's lots and said by
     the steward. **Not retried** for that source version: the key is answered, and a replay answers the same. A later source version
     is a new key and is tried again.
     - `lot_too_small` (S-FS-6, FS-b): "The <lot> stays as it is: the <source>'s new design would no longer fit its lot."
     - `frame`: "... its entrance would move." `bar`: "... it would be the same building as the <source>." Others: Architect's message.
   - The whole call failing: a typed `ArchitectRefused` (`NOT_ALLOWED`, `UNKNOWN_BLUEPRINT`, `OP_KEY_CONFLICT`) is noted once and the
     key stays answered; a lost answer (link dropped, timeout, world stopping) leaves the key un-asked and schedules a rescan in 30 s
     (review 2), which sends the same key again (resumed or replayed by Architect).
   - A lost answer whose operation installed every copy leaves nothing behind, so it is not asked again and its "follow" note is not
     written. The copies' lots are still offered (rule 5): the note is a convenience, the inbox is the offer.
4. **Told once, across restarts.** The notes are deduplicated by their exact text against the settlement's history (persisted), so a
   replay after a restart says nothing new. The text names the copy's lots and the source version, so two copies of one role, or one copy
   at v2 and v3, are told apart (review 4). A note that could not be saved is told again next time; reworded notes in a later Steward may
   be told once more. No save-format change.
5. **The lots (S-FS-2 "both": `followAll`, lots one by one).** `Updates.refresh` unchanged except for a lot whose target version is a
   COPY (`derivation.kind == COPY` on that version), however it got there (a follow, or a library revert of the entry: review 6):
   - **Always ASK**, at every permission level and in creative: the player asked to change one building, not its copies. (Applying
     follows automatically under Autonomous/Full is a one-line change if Noah wants it.)
   - The inbox line says so: "follows the <source>'s new design · v1 → v2 · <delta summary>".
   - **Offered while the source stands at that version or later** (the head's `derivation.sourceVersion` <= the source's standing
     version in this settlement). A source reverted below it hides its copies' offers; they come back if the source goes forward
     again. A source standing nowhere in the settlement does not hold them back. Checked again in `approve`, right before the apply
     (review 3): a cached offer whose source was reverted is not applied.
   - Everything else is today's path: `checkDelta` (claim, protected areas, refusals; a street town's grown copy that FS-b cannot check
     shows as a blocked delta, `COVERED`/`OVERLAP`), approve one or all, skip, preview, `applyDelta` keeping edits, `UPDATED` history,
     survival stockpile.
6. **Revert and undo.** A copy's lot reverts as any building (`Revert`, LIFO in Architect; the version it left is held back,
   `revertedFrom`). Steward's undo does not take back updates (it removes placed projects; updates are reverted per building), so
   nothing changes there: undoing the town removes its lots at whatever version they stand. The follow itself writes only library
   versions, never the world, and has no undo: a copy's older version stays in its list, and `Revert` goes back to it while Architect
   keeps it (versions no site stands at are kept within Architect's retention, then collected: as for every update today; review 7).
7. **Copies in another settlement.** Group-made entries belong to one settlement's build, but the library is shared. `followAll` would
   re-make a copy standing elsewhere too; there it is only ever offered (rule 5), never applied.

## Change requests on a copy

- `ChangeRequests.building`: several buildings match a name and they are one family (one source and its copies, by `derivation`): the
  source is the one meant ("change the fisher's hut").
- A change asked for a copy by its own id: refused before anything is spent or saved: "The <lot> is a copy of the <source>'s design: ask
  for the change on the <source>, and its copies follow." (Today Architect refuses it after the acceptance is saved.)

## Not in scope

SITE followers (7b, `NOT_ALLOWED until_7b`); VARIANT and RESKIN (never follow); a paid redesign of a copy that can't follow (N-FS-1:
it stays, $0); per-copy `follow` calls (`followAll` answers per copy).

## Tests

- **Unit** (`FollowPlannerTest`): which sources to follow (copies behind the standing version, not behind the head; VARIANT/RESKIN
  ignored; no standing source); the opKey; each refusal's words (`lot_too_small` with a synthetic `FollowResult`, as Architect could not
  make one in game); told-once dedupe; the update rule (a `by: follow` head is ASK at Full in creative; hidden while the source stands
  below its `sourceVersion`); the family pick in `building`.
- **e2e** (`stub --only follow`): a flat village (region lots) with copies; a change request on the archetype by its site id; applied;
  then `followAll` answers for every copy. Asserted per copy, since the sim's recipes are seeded by the group id (Architect's own gate
  re-made families up to 16 times): every copy answered; a followed copy with a delta is offered as ASK, applied, and reverted; a
  refused one is told once and not offered; an empty follow ("nothing to write") is fine. A restart replays the key and tells nothing new.

## Review (GPT-6.1, 2026-11-11, design)

Request changes. Taken: 1 and 5 (key by member set and heads, one call per source), 2 (a rescan after a lost answer; a lost note after a
full install accepted, the inbox still offers), 3 (re-checked in `approve`), 4 (the text names lots and version; documented), 6 (any COPY
version, not only `by: follow`), 7 (documented), 8 (the e2e sets the settlement to Full: a follow must still ask, and at least one copy
must follow with something to write; if the sim's seeds give none in a run, the flow says so and that run fails). Not taken: persisted
operation receipts (2) and structured note ids (4): the inbox, not the note, is the offer, and the text identity covers the cases found.
Service-level tests over a fake Architect for the races are left to Architect's own FS gate (its CAS, resume and frozen-set units).
