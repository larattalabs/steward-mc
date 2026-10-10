package dev.larattalabs.steward.model;

import org.jspecify.annotations.Nullable;

/**
 * Something the steward proposes to build (docs/PLAN.md phase 3e): one building, why now, and what prompted it. {@code key} is stable ("smithy" for the
 * smithy proposal), so a declined one is never offered again. Accepting starts an ordinary build of that building, with a budget the player approves.
 */
public record Proposal(String key, String title, String why, ConceptCard.Building building, long at, String trigger) {
	public Proposal {
		if (key == null || building == null) throw new com.google.gson.JsonParseException("a proposal needs a key and a building");
		title = title == null ? key : title;
		why = why == null ? "" : why;
		trigger = trigger == null ? "" : trigger;
	}

	public @Nullable String type() {
		return building.type();
	}
}
