# minecraft-structure-design

A playbook for the Architect design agent (written by the Steward session at Architect's request, 2026-10-05).

- `SKILL.md`: the playbook. Plain document, not loaded through the SDK's `settingSources` (that would bypass the design job's
  permission policy). Architect copies it into each design scratch dir and names it in BRIEF.md.
- `scripts/slices.mjs`: prints a `.nbt` as layered ASCII (one block per character, one grid per y layer, north up, legend, plus a
  listing of directional blocks with their facing). Reads with the kit's own `lib/nbt.mjs`; `--kit <dir>` if the kit is not at `./kit`.
- `scripts/attach-lint.mjs`: deterministic spot checks the layout checker does not cover (ladder, wall torch, sign and banner support;
  door and bed halves; open doors; hanging lanterns). Warnings only, heuristic about what counts as solid.

Both scripts are plain Node (>= 22), no dependencies, and ran clean on Architect's four example designs (`kit/out/*.nbt`). The lint was also
checked against a deliberately bad structure (missing door half, unsupported ladder and torch); it reported all three.

Integration notes for Architect:
- Scratch layout assumed: `kit/` (fresh copy), `skill/` (this directory). Commands in SKILL.md use `skill/scripts/...`; adjust there or
  in the copy step.
- The slice and lint scripts are candidates for `kit/tools/` if Architect would rather own them. The deterministic facing/attachment
  rules also fit the kit checker's promotion track as warnings (Architect row 5a).
- SKILL.md section 7 is only for region programs (A5b); drop it from the copy until those ship.
