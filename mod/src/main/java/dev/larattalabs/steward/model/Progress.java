package dev.larattalabs.steward.model;

import java.util.List;
import java.util.Set;

/**
 * A player's progression, as the steward reads it (docs/PLAN.md "Perception"): computed in code from their advancements, never guessed by Claude. Pure.
 */
public final class Progress {
	/** The tiers, in order; each needs the advancement beside it (vanilla ids). */
	public enum Tier {
		WOOD(null), STONE("minecraft:story/mine_stone"), IRON("minecraft:story/smelt_iron"), DIAMOND("minecraft:story/mine_diamond"),
		NETHER("minecraft:story/enter_the_nether"), END("minecraft:story/enter_the_end"), ELYTRA("minecraft:end/elytra");

		public final String advancement;

		Tier(String advancement) {
			this.advancement = advancement;
		}
	}

	private Progress() {
	}

	/** The highest tier whose advancement is done (tiers are checked in order, so one skipped does not stop a later one counting). */
	public static Tier tier(Set<String> done) {
		Tier t = Tier.WOOD;
		for (Tier x : Tier.values()) if (x.advancement != null && done.contains(x.advancement)) t = x;
		return t;
	}

	/** Every tier's advancement id, to look up. */
	public static List<String> advancements() {
		return java.util.Arrays.stream(Tier.values()).map(t -> t.advancement).filter(java.util.Objects::nonNull).toList();
	}
}
