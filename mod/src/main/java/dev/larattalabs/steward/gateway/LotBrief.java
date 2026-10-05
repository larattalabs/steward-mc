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
	/** Architect's building types (kit BUILDING_TYPES); anything else is designed as {@code custom}. */
	public static final Set<String> ARCHITECT_TYPES = Set.of("house", "cabin", "cottage", "tower", "shop", "tavern", "barn", "smithy", "chapel", "gatehouse", "custom");
	/** Architect's size cap for one design (x/z 7..96, y 6..64). */
	public static final int MIN_XZ = 7, MAX_XZ = 96, MIN_Y = 6, MAX_Y = 64;

	private LotBrief() {
	}

	/**
	 * @param maxHeight the tallest the building may be (a landmark may be tall, a house low)
	 * @param model the model for this job, or null for Architect's default
	 * @param budgetUsd a per-design hard stop, or null
	 */
	public static DesignRequest build(Settlement s, Lot lot, int maxHeight, String model, Double budgetUsd) {
		ConceptCard c = s.card();
		String type = ARCHITECT_TYPES.contains(lot.type()) ? lot.type() : "custom";
		int sx = clamp(lot.sizeX(), MIN_XZ, MAX_XZ);
		int sz = clamp(lot.sizeZ(), MIN_XZ, MAX_XZ);
		BlockSize size = new BlockSize(sx, clamp(maxHeight, MIN_Y, MAX_Y), sz);
		JsonObject ext = new JsonObject();
		ext.addProperty("steward_mc:settlement", s.id());
		ext.addProperty("steward_mc:lot", lot.id());
		ext.addProperty("steward_mc:styleVersion", s.styleVersion());
		return new DesignRequest(type, c.style().text(), null, List.of(), size, null, notes(c, lot, type), null, s.owner(), ext, model, budgetUsd, null, null);
	}

	static String notes(ConceptCard c, Lot lot, String type) {
		List<String> parts = new ArrayList<>();
		parts.add("Part of a settlement: " + c.site().text() + ", used as " + c.purpose().text() + ".");
		if (c.story() != null && c.story().text() != null && !c.story().text().isBlank()) parts.add("Backstory: " + c.story().text() + ".");
		parts.add("Its role in the settlement is a " + lot.type() + ("custom".equals(type) ? " (design it as a custom building of that kind)" : "") + ".");
		parts.add("Design it with its front facing south as usual; the mod turns the building so its entrance faces the street.");
		if (!c.avoid().isEmpty()) parts.add("Avoid: " + String.join(", ", c.avoid()) + ".");
		return String.join(" ", parts);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
