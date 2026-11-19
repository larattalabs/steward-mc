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
		return part(ext, "outside", dimension);
	}

	/** The region's {@code inside} land (its join's cells in the town region's claim: town land the district holds, which town additions keep off). */
	public static Optional<Land> inside(@Nullable JsonObject ext, String dimension) {
		return part(ext, "inside", dimension);
	}

	private static Optional<Land> part(@Nullable JsonObject ext, String key, String dimension) {
		JsonObject land = land(ext);
		if (land == null || !land.has(key) || !land.get(key).isJsonObject()) return Optional.empty();
		JsonObject out = land.getAsJsonObject(key);
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

	/**
	 * (slice DG) The region's {@code outside} land read whole, or null when it can't be trusted: no land object, no {@code outside}, its runs missing, or
	 * any run malformed (a partial read never replaces what a district owns). {@code land} null: read whole, and empty.
	 */
	public record Read(@Nullable Land land) {}

	public static @Nullable Read outsideWhole(@Nullable JsonObject ext, String dimension) {
		JsonObject land = land(ext);
		if (land == null || !land.has("outside") || !land.get("outside").isJsonObject()) return null;
		JsonObject out = land.getAsJsonObject("outside");
		if (!out.has("runs") || !out.get("runs").isJsonArray()) return null;
		List<Land.Run> runs = new ArrayList<>();
		for (JsonElement e : out.getAsJsonArray("runs")) {
			if (!e.isJsonArray() || e.getAsJsonArray().size() != 4) return null;
			JsonArray r = e.getAsJsonArray();
			// exact integers only (a 1.9 or a 1e-400 is never read as an integer): converted exactly, never through a double
			int[] v = new int[4];
			for (int i = 0; i < 4; i++) {
				JsonElement x = r.get(i);
				if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isNumber()) return null;
				try {
					v[i] = new java.math.BigDecimal(x.getAsString()).intValueExact();
				} catch (ArithmeticException | NumberFormatException bad) {
					return null;
				}
			}
			int x0 = v[0], z = v[1], x1 = v[2];
			if (v[3] != z) return null;
			runs.add(new Land.Run(z, Math.min(x0, x1), Math.max(x0, x1)));
		}
		return new Read(runs.isEmpty() ? null : new Land(dimension, runs));
	}

	/** When the town region this district was built beside was removed (epoch ms), or null while it stands. */
	public static @Nullable Long standingRemoved(@Nullable JsonObject ext) {
		JsonObject land = land(ext);
		if (land == null || !land.has("standingRemoved") || land.get("standingRemoved").isJsonNull()) return null;
		return land.get("standingRemoved").getAsLong();
	}

	// ------------------------------------------------------------------ (8bS) Architect 8b's typed RegionView.land, the ext key only when it is null

	/** The region's {@code outside} land: Architect 8b's typed {@code RegionView.land}, else its ext. */
	public static Optional<Land> outside(dev.larattalabs.architect.api.RegionView v, String dimension) {
		var l = v.land();
		return l != null ? Optional.ofNullable(land(l.outside(), dimension)) : outside(v.ext(), dimension);
	}

	/** The region's {@code inside} land: typed, else its ext. */
	public static Optional<Land> inside(dev.larattalabs.architect.api.RegionView v, String dimension) {
		var l = v.land();
		return l != null ? Optional.ofNullable(land(l.inside(), dimension)) : inside(v.ext(), dimension);
	}

	/** {@link #outsideWhole(JsonObject, String)} typed: a typed land is always read whole. */
	public static @Nullable Read outsideWhole(dev.larattalabs.architect.api.RegionView v, String dimension) {
		var l = v.land();
		return l != null ? new Read(land(l.outside(), dimension)) : outsideWhole(v.ext(), dimension);
	}

	/** {@link #standingRemoved(JsonObject)} typed. */
	public static @Nullable Long standingRemoved(dev.larattalabs.architect.api.RegionView v) {
		var l = v.land();
		return l != null ? l.standingRemoved() : standingRemoved(v.ext());
	}

	/** Typed runs as Steward's land; null for none. Pure. */
	static @Nullable Land land(List<dev.larattalabs.architect.api.RegionLand.Run> runs, String dimension) {
		if (runs == null || runs.isEmpty()) return null;
		List<Land.Run> out = new ArrayList<>();
		for (var r : runs) out.add(new Land.Run(r.z(), Math.min(r.x0(), r.x1()), Math.max(r.x0(), r.x1())));
		return new Land(dimension, out);
	}

	private static @Nullable JsonObject land(@Nullable JsonObject ext) {
		return ext != null && ext.has(KEY) && ext.get(KEY).isJsonObject() ? ext.getAsJsonObject(KEY) : null;
	}
}
