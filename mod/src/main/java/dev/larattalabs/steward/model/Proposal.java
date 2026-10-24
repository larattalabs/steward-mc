package dev.larattalabs.steward.model;

import org.jspecify.annotations.Nullable;

/**
 * Something the steward proposes to build (docs/PLAN.md phase 3e): one building, why now, and what prompted it. {@code key} is stable ("smithy" for the
 * smithy proposal), so a declined one is never offered again. Accepting starts an ordinary build of that building, with a budget the player approves, in
 * the town or in {@code district}. A proposal that {@code found}s a district (phase 3f) has no building: accepting it lays the district's road. One with a
 * {@code pendingPart} offers a part of the settlement's plan that waited for Architect and can be built now (docs/COMPOSITION.md section 11).
 */
public record Proposal(String key, String title, String why, ConceptCard.@Nullable Building building, long at, String trigger, @Nullable String district,
	@Nullable String found, @Nullable String changeSite, @Nullable String changeNotes, @Nullable String pendingPart) {
	public Proposal {
		if (key == null || (building == null && found == null && (changeSite == null || changeNotes == null) && pendingPart == null)) {
			throw new com.google.gson.JsonParseException("a proposal needs a key and a building, a district, a change or a waiting part");
		}
		title = title == null ? key : title;
		why = why == null ? "" : why;
		trigger = trigger == null ? "" : trigger;
	}

	/** A building for the town. */
	public Proposal(String key, String title, String why, ConceptCard.Building building, long at, String trigger) {
		this(key, title, why, building, at, trigger, null, null);
	}

	/** A building (in {@code district}) or a district ({@code found}): as before change requests. */
	public Proposal(String key, String title, String why, ConceptCard.@Nullable Building building, long at, String trigger, @Nullable String district,
		@Nullable String found) {
		this(key, title, why, building, at, trigger, district, found, null, null, null);
	}

	/** As above, with a change (as before waiting parts). */
	public Proposal(String key, String title, String why, ConceptCard.@Nullable Building building, long at, String trigger, @Nullable String district,
		@Nullable String found, @Nullable String changeSite, @Nullable String changeNotes) {
		this(key, title, why, building, at, trigger, district, found, changeSite, changeNotes, null);
	}

	/** A part of the settlement's plan that waited for Architect's {@code block} and can be built now: Claude fits it to what stands, Architect builds it. */
	public static Proposal part(String part, String title, String why, long at) {
		return new Proposal("part_" + part, title, why, null, at, "it can be built now", null, null, null, null, part);
	}

	public boolean offersPart() {
		return pendingPart != null;
	}

	/** A change to a standing building (phase 3c): its next version, designed from {@code notes} (Architect's {@code versionOf}); it always waits for the player. {@code buildingName} carries its article where it takes one ("the scholars' library", "noahlaratta's house"). */
	public static Proposal change(String siteId, String buildingName, String notes, String said, long at) {
		return new Proposal("change_" + Long.toString(at, 36), "Change " + buildingName, "You asked: \"" + (said.length() > 120 ? said.substring(0, 119) + "…" : said)
			+ "\"", null, at, "asked", null, null, siteId, notes);
	}

	public boolean changes() {
		return changeSite != null;
	}

	/** The same building, to be built in district {@code districtId} (null: the town). */
	public Proposal in(@Nullable String districtId) {
		return new Proposal(key, title, why, building, at, trigger, districtId, found, changeSite, changeNotes, pendingPart);
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
