package dev.larattalabs.steward.gateway;

import com.google.gson.JsonObject;
import dev.larattalabs.architect.api.BlockSize;
import dev.larattalabs.architect.api.DesignRequest;
import dev.larattalabs.steward.layout.VillageLayout.Lot;
import dev.larattalabs.steward.model.ConceptCard;
import dev.larattalabs.steward.model.Settlement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns one lot of a settlement into the fields of an Architect design request. The lot's building type and footprint come from the
 * layout; the style, purpose, story and avoid list from the concept card. Pure (no Minecraft calls).
 */
public final class LotBrief {
	/** Architect's preset building types (kit BUILDING_TYPES). Since API 1.2.0 any other snake_case type is an open type with the default checker profile. */
	public static final Set<String> ARCHITECT_TYPES = Set.of("house", "cabin", "cottage", "tower", "shop", "tavern", "barn", "smithy", "chapel", "gatehouse", "custom");
	/** Architect's size cap for one design (x/z 7..96, y 6..64). */
	public static final int MIN_XZ = 7, MAX_XZ = 96, MIN_Y = 6, MAX_Y = 64;
	/** Depth the lot keeps free in front of the building for Architect's approach (fitToLot sets the front back by it: a live run refused every lot as LOT_TOO_SMALL without it). */
	public static final int APPROACH_MARGIN = 5;
	/** The sidecar's limits (protocol.ts): a request's style 1..40 characters, its notes up to 2000, a group's name 1..60. */
	public static final int MAX_STYLE = 40, MAX_NOTES = 2000, MAX_GROUP_NAME = 60;

	private LotBrief() {
	}

	/**
	 * @param maxHeight the tallest the building may be (a landmark may be tall, a house low)
	 * @param model the model for this job, or null for Architect's default
	 * @param budgetUsd a per-design hard stop, or null
	 */
	public static DesignRequest build(Settlement s, Lot lot, int maxHeight, String model, Double budgetUsd) {
		ConceptCard c = s.card();
		String type = ARCHITECT_TYPES.contains(lot.type()) || ConceptCard.TYPE_SLUG.matcher(lot.type()).matches() ? lot.type() : "custom";
		int sx = clamp(lot.sizeX(), MIN_XZ, MAX_XZ);
		int sz = clamp(lot.sizeZ() - APPROACH_MARGIN, MIN_XZ, MAX_XZ);
		BlockSize size = new BlockSize(sx, clamp(maxHeight, MIN_Y, MAX_Y), sz);
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:lot", lot.id());
		ext.addProperty("steward_mc:styleVersion", s.styleVersion());
		return new DesignRequest(type, styleLabel(c), null, List.of(), size, null, notes(c, lot, type), null, s.owner(), ext, model, budgetUsd, null, null);
	}

	static String notes(ConceptCard c, Lot lot, String type) {
		List<String> parts = new ArrayList<>();
		// the request's style field is a short label; the card's full style words go here (the bible carries the look either way)
		parts.add("Style: " + c.style().text() + ".");
		parts.add("Part of a settlement: " + c.site().text() + ", used as " + c.purpose().text() + ".");
		if (c.story() != null && c.story().text() != null && !c.story().text().isBlank()) parts.add("Backstory: " + c.story().text() + ".");
		parts.add("Its role in the settlement: " + lot.role() + (ARCHITECT_TYPES.contains(type) ? "" : " (a building of its own kind, not a preset type)") + ".");
		if (lot.notes() != null) parts.add(lot.notes().endsWith(".") ? lot.notes() : lot.notes() + ".");
		parts.add("Design it with its front facing south as usual; the mod turns the building so its entrance faces the street.");
		if (!c.avoid().isEmpty()) parts.add("Avoid: " + String.join(", ", c.avoid()) + ".");
		return clip(String.join(" ", parts), MAX_NOTES);
	}

	/** The style as the request's short label: the player's words when they fit {@link #MAX_STYLE}, else the card's style template, else the words cut at a word boundary. */
	public static String styleLabel(ConceptCard c) {
		String words = c.style().text().strip();
		if (words.length() <= MAX_STYLE) return words;
		String t = c.style().template();
		if (t != null && !t.isBlank()) return clip(t.strip(), MAX_STYLE);
		return clip(words, MAX_STYLE);
	}

	/** Cuts at the last space (or comma) before {@code max}, never mid-word when a word boundary exists. */
	public static String clip(String s, int max) {
		if (s.length() <= max) return s;
		int cut = Math.max(s.lastIndexOf(' ', max), s.lastIndexOf(',', max));
		String out = (cut > max / 2 ? s.substring(0, cut) : s.substring(0, max)).strip();
		return out.endsWith(",") ? out.substring(0, out.length() - 1) : out;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
