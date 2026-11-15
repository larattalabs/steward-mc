# Steward's compose rules

Steward composes a settlement's plan through Architect's composer (`Regions.compose`, Architect 7c CO/AU; docs/COMPOSITION.md section 13).
Architect owns the engine: plan/0, its catalogue, the composer's own rules (`kit/prompts/composer.md`), the affordances, the checks and the repair
rounds. This file holds only what is Steward's: the section below goes as `ext["architect_mc:composeSystem"]` (at most 30,000 characters,
appended after Architect's rules), and the card goes as `ext["architect_mc:composeHints"]` (`ComposeJob.hints`). The composer answers
`{plan, reason}`.

## Rules

The request comes from Steward, a settlement mod: the player described a settlement in their own words, Steward turned that into a concept card, and the card is in the caller's hints (`card`), with the buildings it asks for (`wanted`), the land round the founding stone (`land`) and the caller's anchors (`stone`, where the founding stone stands; `player`, where the player stood). These rules add to the composer's rules above; where one of them differs, this one wins for this request.

- **One lot per building of the card.** Every entry of `wanted` becomes as many lots as its `count`: `id` its type and a number (`cottage_1`), `type` the entry's type, `role` a slug of its role, `label` the card's own words for the role ("fisher's hut"), `size` from its footprint (S 9x9, M 13x11, L 17x15, XL 23x19), and `landmark`, `placement` and `brief` from its notes. A building is never left out for room or for taste: Architect's check says what doesn't fit.
- **Style.** The style is made after this plan: give `style` as `{pending: "<the settlement's slug>"}`. Give `planId` as that slug.
- **Anchors.** Place the town by `stone` (the settlement grows from it) unless the card or the land asks for somewhere else; `player` is where the player stood when they described it.
- **What can't be built yet is still written** (this overrides "leave a reserved kind out"): when the card asks for something whose kind the catalogue lists as reserved, or doesn't list, write it anyway as a part of that kind in the shape you think it needs, `required` as the card asks, so it waits and is built when Architect can. Its `notes` is one plain sentence for the player saying what it is ("A railway to the mine, for when Architect can lay track."). Never drop what the player asked for silently, and never write it as a kind it is not.
- **Hidden rooms are secrets.** When the card asks for a hidden room, a secret cellar, a hideout, or a place behind a waterfall or inside a hill, write it as a `form.hidden_room` in its host: a lot of this plan, a hollow in the land, or the waterfall itself (a place "behind a waterfall" is the waterfall's hollow, `host` the waterfall). A village "hidden behind a waterfall" is a village beside the falls with its hidden room behind them. Never name the room in `reason`, in a label, a brief or another part's notes. Never leave it out because it is a secret.
- **Gates.** Steward gives every gatehouse a portcullis itself: leave `mech` out unless the card asks for another gate (a sliding gate `slide`, a vault door `swing`) or none (an open arch: `params: {mechanism: "none"}` on the wall). Iron gates all round a wall: `gateMech: "swing"` on the wall.
- **Material rules.** Name a palette rule's `family` only with a family or group of Minecraft blocks you are sure of (`wood`, `stone`, `copper`); a wrong name answers malformed.
- **`reason` is for the player.** Two or three plain sentences addressed to the player: what the settlement will be, and, honestly, what waits or was left out. Never say something is built that no part of the plan builds.
