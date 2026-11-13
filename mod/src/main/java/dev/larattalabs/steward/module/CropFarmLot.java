package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The lot {@code crop_farm@1} asks Architect for (docs/MODULES.md 3.1, slice M2): a {@code planRevision} grow lot
 * {@code {id, type: crop_farm, size: [W, D], irrigate: true, field: {cells, crops}}} (S-M5-2, S-M5-4), with W = width + 2 and D = depth + 3.
 * The field's cells are the program's own farmland cells (modules/crop_farm/1/program.mjs {@code layout}): the field u 1..W-2, v 1..D-3 of
 * IRR's frame, less each source of IRR's grid (one per 9x9, at its segment's centre) and its 4 sides (where the program's well posts stand).
 * The crops are all four, so a player who replants another crop stays inside FC's rule. Pure; the measurement uses it and M3 will.
 */
public final class CropFarmLot {
	private CropFarmLot() {}

	public static final List<Integer> SIDES = List.of(9, 18, 27);
	public static final List<String> CROPS = List.of("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots");
	static final int SPAN = 9;

	/** The farm's geometry in the lot's frame. */
	public record Layout(int width, int depth, int w, int d, List<int[]> sources, List<int[]> posts, List<int[]> field) {}

	/** IRR's sources along one side [a, b]: ceil(L / 9) segments, each at its centre, a tie to the low end (irrigate.mjs axisSources). */
	static List<Integer> axisSources(int a, int b) {
		int len = b - a + 1;
		int n = (len + SPAN - 1) / SPAN;
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int s0 = a + (i * len) / n, s1 = a + ((i + 1) * len) / n - 1;
			out.add(s0 + (s1 - s0) / 2);
		}
		return out;
	}

	public static Layout layout(int width, int depth) {
		if (!SIDES.contains(width) || !SIDES.contains(depth)) throw new IllegalArgumentException("crop_farm@1: field sides are 9, 18 or 27 (got " + width + " x " + depth + ")");
		int w = width + 2, d = depth + 3;
		List<int[]> sources = new ArrayList<>(), posts = new ArrayList<>(), field = new ArrayList<>();
		Set<Long> skip = new HashSet<>();
		for (int v : axisSources(1, d - 3)) {
			for (int u : axisSources(1, w - 2)) {
				sources.add(new int[] {u, v});
				skip.add(key(u, v));
				for (int[] o : new int[][] {{0, -1}, {-1, 0}, {1, 0}, {0, 1}}) {
					posts.add(new int[] {u + o[0], v + o[1]});
					skip.add(key(u + o[0], v + o[1]));
				}
			}
		}
		for (int v = 1; v <= d - 3; v++) for (int u = 1; u <= w - 2; u++) if (!skip.contains(key(u, v))) field.add(new int[] {u, v});
		return new Layout(width, depth, w, d, List.copyOf(sources), List.copyOf(posts), List.copyOf(field));
	}

	private static long key(int u, int v) {
		return ((long) u << 32) ^ (v & 0xffffffffL);
	}

	/** The grow lot for {@code planRevision}: {@code {id, type, size: [W, D], irrigate: true, field: {cells: [[u, 0, v]...], crops}}}. */
	public static JsonObject growLot(String lotId, int width, int depth) {
		Layout l = layout(width, depth);
		JsonObject lot = new JsonObject();
		lot.addProperty("id", lotId);
		lot.addProperty("type", "crop_farm");
		JsonArray size = new JsonArray();
		size.add(l.w());
		size.add(l.d());
		lot.add("size", size);
		lot.addProperty("irrigate", true);
		JsonObject field = new JsonObject();
		JsonArray cells = new JsonArray();
		for (int[] c : l.field()) {
			JsonArray a = new JsonArray();
			a.add(c[0]);
			a.add(0);
			a.add(c[1]);
			cells.add(a);
		}
		field.add("cells", cells);
		JsonArray crops = new JsonArray();
		CROPS.forEach(crops::add);
		field.add("crops", crops);
		lot.add("field", field);
		return lot;
	}
}
