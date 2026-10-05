package dev.larattalabs.steward.model;

import java.util.Set;

/**
 * The player's progression, computed in code from advancements so the model is handed facts and never guesses. Advancement ids are
 * vanilla's; a tier is reached when its marker advancement is done (highest reached wins).
 */
public enum Tier {
	WOOD(null),
	STONE("minecraft:story/mine_stone"),
	IRON("minecraft:story/smelt_iron"),
	DIAMOND("minecraft:story/mine_diamond"),
	NETHER("minecraft:story/enter_the_nether"),
	END("minecraft:story/enter_the_end"),
	ELYTRA("minecraft:end/elytra");

	private final String marker;

	Tier(String marker) {
		this.marker = marker;
	}

	public static Tier fromAdvancements(Set<String> done) {
		Tier best = WOOD;
		for (Tier t : values()) {
			if (t.marker != null && done.contains(t.marker)) best = t;
		}
		return best;
	}

	/** Whether this tier unlocks content that needs {@code needed}. */
	public boolean atLeast(Tier needed) {
		return ordinal() >= needed.ordinal();
	}
}
