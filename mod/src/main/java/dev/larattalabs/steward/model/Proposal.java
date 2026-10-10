package dev.larattalabs.steward.model;

import org.jspecify.annotations.Nullable;

/**
 * Something the steward proposes to build (docs/PLAN.md phase 3e): one building, why now, and what prompted it. {@code key} is stable ("smithy" for the
 * smithy proposal), so a declined one is never offered again. Accepting starts an ordinary build of that building, with a budget the player approves, in
 * the town or in {@code district}. A proposal that {@code found}s a district (phase 3f) has no building: accepting it lays the district's road.
 */
public record Proposal(String key, String title, String why, ConceptCard.@Nullable Building building, long at, String trigger, @Nullable String district,
	@Nullable String found) {
	public Proposal {
		if (key == null || (building == null && found == null)) throw new com.google.gson.JsonParseException("a proposal needs a key and a building or a district");
		title = title == null ? key : title;
		why = why == null ? "" : why;
		trigger = trigger == null ? "" : trigger;
	}

	/** A building for the town. */
	public Proposal(String key, String title, String why, ConceptCard.Building building, long at, String trigger) {
		this(key, title, why, building, at, trigger, null, null);
	}

	/** The same building, to be built in district {@code districtId} (null: the town). */
	public Proposal in(@Nullable String districtId) {
		return new Proposal(key, title, why, building, at, trigger, districtId, found);
	}

	/** A new district towards {@code way} (N, E, S, W): founding it lays its road ($0); it always waits for the player. */
	public static Proposal district(String way, String why, long at) {
		return new Proposal("district_" + way, "A district to the " + DistrictRules.directionName(way), why, null, at, "the town's street is full", null, way);
	}

	public boolean foundsDistrict() {
		return found != null;
	}

	public @Nullable String type() {
		return building == null ? null : building.type();
	}
}
