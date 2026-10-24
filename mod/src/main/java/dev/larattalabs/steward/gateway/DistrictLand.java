package dev.larattalabs.steward.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.larattalabs.steward.model.Land;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What Steward reads of a district region's {@code architect_mc:land} (Architect D3 section 6): the land outside the town's claim that the region took
 * ({@code outside}, the district's to own), and whether the town it was built beside is gone ({@code standingRemoved}). Steward saves only its own runs,
 * so a later land version (6d's grown parts) changes this reader, never the save. Pure.
 */
public final class DistrictLand {
	public static final String KEY = "architect_mc:land";

	private DistrictLand() {
	}

	/** The region's {@code outside} land as Steward's runs; empty when the ext has none (or none outside the town). */
	public static Optional<Land> outside(@Nullable JsonObject ext, String dimension) {
		JsonObject land = land(ext);
		if (land == null || !land.has("outside") || !land.get("outside").isJsonObject()) return Optional.empty();
		JsonObject out = land.getAsJsonObject("outside");
		if (!out.has("runs") || !out.get("runs").isJsonArray()) return Optional.empty();
		List<Land.Run> runs = new ArrayList<>();
		for (JsonElement e : out.getAsJsonArray("runs")) {
			if (!e.isJsonArray() || e.getAsJsonArray().size() != 4) continue;
			JsonArray r = e.getAsJsonArray();
			// [x0, z, x1, z]: one row's columns
			int x0 = r.get(0).getAsInt(), z = r.get(1).getAsInt(), x1 = r.get(2).getAsInt();
			if (r.get(3).getAsInt() != z) continue;
			runs.add(new Land.Run(z, Math.min(x0, x1), Math.max(x0, x1)));
		}
		return runs.isEmpty() ? Optional.empty() : Optional.of(new Land(dimension, runs));
	}

	/** When the town region this district was built beside was removed (epoch ms), or null while it stands. */
	public static @Nullable Long standingRemoved(@Nullable JsonObject ext) {
		JsonObject land = land(ext);
		if (land == null || !land.has("standingRemoved") || land.get("standingRemoved").isJsonNull()) return null;
		return land.get("standingRemoved").getAsLong();
	}

	private static @Nullable JsonObject land(@Nullable JsonObject ext) {
		return ext != null && ext.has(KEY) && ext.get(KEY).isJsonObject() ? ext.getAsJsonObject(KEY) : null;
	}
}
