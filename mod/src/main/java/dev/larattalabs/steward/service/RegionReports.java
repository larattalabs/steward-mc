package dev.larattalabs.steward.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a realised region's report says to the player (Architect H): the water bodies Architect drained because they failed their containment or escaped
 * (the region's ext {@code architect_mc:fluids}, {@code [{part, body, state: settled | drained, reason, at}]}), a line each. Pure.
 */
public final class RegionReports {
	static final String FLUIDS = "architect_mc:fluids";

	private RegionReports() {
	}

	/** "The farm pond of farm_1 was drained: it leaked at 12, 64, -3." for each drained body; nothing for a region without water. */
	public static List<String> drained(@Nullable JsonObject ext) {
		List<String> out = new ArrayList<>();
		if (ext == null || !ext.has(FLUIDS) || !ext.get(FLUIDS).isJsonArray()) return out;
		for (JsonElement e : ext.getAsJsonArray(FLUIDS)) {
			if (!e.isJsonObject()) continue;
			JsonObject b = e.getAsJsonObject();
			if (!"drained".equals(str(b, "state"))) continue;
			String what = str(b, "body") != null ? str(b, "body") : str(b, "part") != null ? str(b, "part") : "a body of water";
			String why = str(b, "reason");
			String at = b.has("at") && b.get("at").isJsonArray() && b.getAsJsonArray("at").size() == 3 ? " at " + b.getAsJsonArray("at").get(0).getAsInt() + ", "
				+ b.getAsJsonArray("at").get(1).getAsInt() + ", " + b.getAsJsonArray("at").get(2).getAsInt() : "";
			out.add("The water of " + what.replace('_', ' ') + " was drained" + (why == null ? "" : ": " + why) + at + ". The rest stands.");
		}
		return out;
	}

	private static @Nullable String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}
}
