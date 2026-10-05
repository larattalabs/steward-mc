# Builder tooling research (2026-10-05)

Output of a research subagent (web search). **Not independently verified**: links, versions, licences and "supports 26.x"
claims are the agent's and must be checked before adopting anything. Last-commit dates were mostly not shown.

## Recommendations (value / effort)

| # | Tool | Use for us | Caveat to verify |
|---|---|---|---|
| 1 | [misode/mcmeta](https://github.com/misode/mcmeta) | Source of truth for block tags, POI/villager data, vanilla jigsaw pools (style references), textures (build-time input) | 26.3 coverage; asset licence (Mojang-derived), build-time input only |
| 2 | [block-model-renderer](https://github.com/ewanhowell5195/block-model-renderer) (MPL-2.0, Node) | Textured iso renders for the critique loop (A4) from a resource pack built from mcmeta assets; needs a small .nbt adapter | Last release seen Sep 2024; 26.x models unverified |
| 3 | [PrismarineJS/minecraft-data](https://github.com/PrismarineJS/minecraft-data) (MIT) | Block validation, light emission/opacity, collision shapes in the checker | Lists 1.21.10 and 26.1; 26.3 unverified, fall back to mcmeta |
| 4 | [mc-runtime-test + HeadlessMC](https://github.com/headlesshq/mc-runtime-test) (MIT) | Final in-game gate in CI/nightly: paste the .nbt, run GameTests (chunks, ticks, fluids, pathing probe) headless. Fabric GameTest docs: docs.fabricmc.net/develop/automatic-testing | Slow, full client; keep the JS checkers as the fast loop |
| 5 | [chapmanjw/minecraft-java-fabric-claude-plugin](https://github.com/chapmanjw/minecraft-java-fabric-claude-plugin) (MIT) | Closest prior art: skill tiering (survey, design, terrain, execution), orthographic silhouette check before placing | Experimental, Python, live-server dependency: take ideas, not code |
| 6 | [GDMC-HTTP mod](https://github.com/Niels-NTG/gdmc_http_interface) (MIT) | Optional: heightmap/biome query and read-back from a live dev world | 1.21.1 only; needs a 26.x build or port. We already plan our own survey API (Architect A8) |
| 7 | [deepslate](https://github.com/misode/deepslate) (MIT) | NBT I/O, noise and terrain emulation for region programs without the game, WebGL structure views | Needs WebGL (headless-gl server-side); 26.x worldgen unverified |
| 8 | Mineflayer / Mindcraft | Optional last: a bot walks the walk-graph in survival to verify | Heavy, flaky, does not beat a static reachability checker |

## Techniques to copy
- **GDMC scoring as checker rubrics:** adaptability, functionality (access, mob safety, light, food), narrative, aesthetics.
- **Gaussian-filtered heightmap** to find build spots and smooth terrain; spring/particle "water" for road paths (GDMC 2025 winner,
  CC BY-NC 4.0: read, do not copy code). Hand-authored archetypes plus a generated layout beat pure ML in GDMC.
- **LLM text as cheap narrative:** signs and books (2nd-place GDMC entry used the LLM only for in-world books).
- **Deterministic facing/attachment checks:** stair/door/trapdoor facing, torches and ladders attached, required components present (the
  main LLM failure modes in the papers the agent saw, abstract-level only).
- **Silhouette first:** render orthographic views and judge them before placing.
- **WFC for interiors and facade tiles**, not whole buildings.
- **Eval set:** MineCEraft (an arXiv paper the agent cited; verify the reference) has programmatically verifiable instruction categories; reuse the categories.
- **Prompting:** layered ASCII slices (one y-layer per block) with a legend, a stated coordinate convention (y up, +x east, +z south),
  a "self-check before done" list.

## Rejected
Mineflayer MCP servers (one block at a time, no verification), a .schem-only builder MCP, prismarine-schematic (no vanilla structure
.nbt), Amulet-Core and Python-only tools (wrong runtime), Voyager/Mindcraft as builders (weak verification).

## Proposed skill: `minecraft-structure-design`
Triggers when the agent builds, designs, extends or fixes a structure, settlement or megastructure. Bundles:
1. **SKILL.md workflow:** survey, plan footprint and coordinate convention, write the program, check, render, critique, iterate.
2. **Scripts:** `check`, `render --views iso,top,front,slices` (textured when item 2 lands), `diff` against the previous version.
3. **References:** block-safety and palette list (minecraft-data/mcmeta tags), style notes from vanilla jigsaw pools, the GDMC rubric.
4. **Rules:** layered ASCII slices in the critique prompt; self-check list; deterministic facing/attachment checks.
5. **Optional** `ingame-gate` script invoking mc-runtime-test.

## Unverified
26.3 coverage of minecraft-data, mcmeta and block-model-renderer; whether GDMC-HTTP has a 26.x build; deepslate headless use;
mcmeta asset licence; WorldEdit/FAWE on 26.x (7.4.3 reported for 26.1.x); WorldPainter, Chunky and shape grammars not researched in depth.
