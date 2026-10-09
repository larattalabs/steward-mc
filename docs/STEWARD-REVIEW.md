# Review of Steward's own code (HEAD d0ae734) against Architect's and AgentCraft's standards

Reviewed 2026-10-09 (Opus 5.5) at Noah's request. It covers everything in `mod/src` (about 2,300 lines of main code, 88 tests, all green). The yardstick is how Architect (`architect-mc`) and AgentCraft
handle the same concerns. Nothing here needed a live run.

## Verdict
The pure core is good and matches Architect's style:
- `Pipeline` as a reducer;
- the planners, `VillageLayout` and `BudgetPolicy`, all unit-tested;
- the atomic per-world JSON store that refuses to overwrite a corrupt file.

The live layer (`SettlementRunner`, `StewardNpc`, `FoundingStone`, `StewardCommands`) was written as dev scaffolding and has not caught up with the player-facing flow it now serves. One blocker, eight
bugs, and a handful of standards gaps.

## Blocker
**B1. The Founding Stone flow cannot get past the style bible.**
- `Settlements.found` founds every settlement at `Permission.PROPOSALS`, and `/steward start` runs at `s.permission()`.
- At PROPOSALS, `Pipeline.bibleDone` goes to `AWAITING_BIBLE_APPROVAL`. Nothing in `mod/src/main` ever constructs `BibleApproved`, `MassingDecision` or `BudgetRaised`. Only `Pipeline` and the tests do.
- So `describe` → `start` spends $1.5-3 on a bible and then waits forever.
- The same dead end exists at massing approval and at the soft-budget pause ("Raise the budget to continue" has no command). The batch is also queued with `autoApprove=false`, and nothing approves its stages.
- The live run used `/steward build`, which forces `FULL`, so this path was never exercised.

Fix (proposed, needs Noah's call on the UX): stopgap `/steward approve|redirect|raise <id> ...` commands until the inbox exists, and a stage approval for the batch. Meanwhile `start` should refuse at OBSERVER and
PROPOSALS with a clear message rather than spend.

## Bugs
1. **Right-click on the steward talks twice.**
   - `StewardNpc`'s `UseEntityCallback` has no hand check. The server receives a main-hand and then an off-hand interact (the client returns PASS for both, because entity tags are not synced to it).
   - AgentCraft's `UiRules.agentUse` is the pattern: a pure, tested rule that acts on MAIN_HAND and swallows OFF_HAND.
2. **`StewardNpc.spawn` cannot fail.**
   - `performPrefixedCommand` with suppressed output does not throw when the summon is rejected. So `spawn` returns true and the "(the steward could not appear)" branch is dead.
   - The name is written as a JSON string (`CustomName:'{"text":...}'`). Since 1.21.5, text components in SNBT are compounds, so 26.3 probably shows the raw JSON as the name. The only screenshot is from behind and shows no name.
   - Fix: build the mannequin in Java (`EntityType.MANNEQUIN.create`, `setCustomName(Component...)`, tags, `addFreshEntity`) and return the real result.
3. **Survival is ignored when placing.** `SettlementRunner.fitAndQueue` passes `worldSurvival=false` to `BatchPlanner.build`, so a survival world would get INSTANT items. Read `WorldMode.survival(server)`.
4. **Null names.** `Pipeline` writes `card().name()` into "… is built" and "Cancelled …". The card name is optional, which produces the "null is built" seen live. Carry the settlement's name in `State`.
5. **Runners leak and can run twice.**
   - Each `SettlementRunner` registers four `SiteEvents` listeners that can never be removed.
   - `ACTIVE` holds `MinecraftServer`, `ServerLevel` and `ServerPlayer` past `SERVER_STOPPED`. Architect resets every static there.
   - A second `/steward start` on the same settlement starts a second runner that also spends.
   - The `ServerPlayer` reference goes stale on respawn.
   - Fix: one dispatcher registered at init that routes by id; one runner per settlement (refuse a second); clear on stop; resolve the player by UUID when speaking.
6. **Early events can be lost.**
   - The runner learns `bibleJobId` and `groupId` from the ack future. An event that fires before the ack completes is ignored.
   - `CardService` already handles this case (a `get(id)` after the ack); the runner should do the same with `bibles().get` and `designs().group`.
7. **Ids can be reused.** `SettlementStore.nextId` counts from `size()+1`. Once removal exists, a new settlement could take a removed one's id, and with it its Architect owner string and its tagged NPC. Keep a persisted monotonic counter.
8. **The Architect version check is too loose.**
   - `ArchitectGateway` checks only the API major version, and `fabric.mod.json` asks for `architect_mc >=0.4.0`.
   - Steward calls API 1.8.0 types (groups, batches, critique, delta), so an older Architect passes both checks and then fails with `NoSuchMethodError` mid-run.
   - Require 1.8 or later in both places.

## Standards gaps (vs Architect)
- **Command permissions.** The commands that spend money or change the world (`build`, `start`, `resume`, `describe`, `card`, `claim`) have no permission gate. Architect gates mutating commands at gamemaster
  (level 2), and AgentCraft gates its whole root. Singleplayer makes this mostly moot, but LAN-opened worlds exist.
- **Server-thread hops.** The Architect API completes its futures on the server thread, so Steward is fine today. It is only safe because of that contract, so say so in one place (`ArchitectGateway`) rather than in
  scattered comments.
- **Constants.** The claim radius 64 appears in `Settlements`, `SettlementRunner` and the `survey` command, and `/steward card` uses 128. Use one constant.
- **Docs drift.**
  - `mod/DEV.md` still says Architect 0.4.0 / API 1.0.0.
  - The `Steward` and `StewardCommands` javadocs describe the pre-stone state.
  - The DEV.md layout section has grown into a changelog.
- **Generative-first.**
  - `SettlementRunner.ARCHETYPES` is a fixed list (tavern, house, shop, …) used whatever the card says. A "hellish evil lair" still gets a tavern and a chapel.
  - The card should yield the building program (types, counts, landmarks, rough footprints). That is the next card-schema change, not a bug, but it is where the product promise currently breaks.
- **Gates.** Architect runs `tools/gate-run.mjs` chains at $0 against a stub sidecar. Steward has no equivalent: every non-unit check so far has been a hand-driven DevBridge run.
  - A `quick` chain would catch B1 and bugs 1-5 without spending: unit tests, plus a stub-sidecar client that claims, describes, starts at PROPOSALS, approves and places.

## What is fine
- The `Pipeline` reducer and its tests.
- `GroupPlanner` (landmarks before the 24-item cap, report-only critique, shared context).
- `BatchPlanner` staging and the temporary-refusal rule.
- `VillageLayout` determinism.
- `SettlementStore` atomic writes and its refusal to touch a corrupt file. Architect's pattern is to refuse mutations with "fix or move it, then restart", and Steward follows it.
- `CardService`'s ack race handling.
- `UpdatePlanner`'s permission table.
- Mannequin over a custom entity is the right call for now.

## Status (2026-10-09)
Fixed in the follow-up commit: bugs 1-8, the command permission gate, the radius constant, the stale javadocs and DEV.md, with tests for the hand rule, monotonic ids and unnamed cards (91 tests).
Bug 2's name is now set in Java, so the SNBT question is moot. Open: B1 (waiting on Noah's UX call), runner persistence, the card's building program, a `quick` gate. Not yet run in a client.

## Order of fixes
1. Bugs 1-8 and the permission gate. They are small, all in this repo, and need no usage.
2. B1, after Noah picks the stopgap UX.
3. Persist runner state per settlement. Architect's catch-up (`listGroups(owner)`, durable JOB_DONE) makes resume after a restart feasible. This becomes simple once bug 5's dispatcher exists.
4. The card's building program.
5. A Steward `quick` gate chain.
