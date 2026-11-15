package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code storage_hall@1}'s lot and containers in Java (docs/MEASURE-M4.md 1.2), the program's own {@code layout} (modules/storage_hall/1/program.mjs)
 * in the lot's frame: front to the south, x across, z from the back, y from the floor row (template row 1 = frame y 0). The measurement checks these
 * cells equal the program's on every hall. Pure.
 *
 * <p>This version builds the plain hall only ({@code slots 0}); the sorting branches wait for the filler.
 */
public final class StorageHallLot {
	private StorageHallLot() {}

	/** The slot counts this version builds. */
	public static final List<Integer> SLOTS = List.of(0);
	/** The most bulk double chests. */
	public static final int BULK_MAX = 8;

	/**
	 * The layout: the frame {@code w x d}, the door's column, the input chest and each bulk double chest's two halves (template cells
	 * {@code [x, y, z]}, {@code y} the template row).
	 */
	public record Layout(int w, int d, int gate, int[] input, List<int[][]> bulk) {
		/** Every container cell: the input chest, then each bulk pair's two halves. */
		public List<int[]> containers() {
			List<int[]> out = new ArrayList<>();
			out.add(input);
			for (int[][] b : bulk) {
				out.add(b[0]);
				out.add(b[1]);
			}
			return List.copyOf(out);
		}
	}

	/** The layout for these params; throws on a branch this version doesn't build. */
	public static Layout layout(int slots, int bulk) {
		if (!SLOTS.contains(slots)) throw new IllegalArgumentException("storage_hall@1: slots must be one of " + SLOTS + " (the sorting branches wait for the filler)");
		if (bulk < 0 || bulk > BULK_MAX) throw new IllegalArgumentException("storage_hall@1: bulk must be 0.." + BULK_MAX);
		int w = 11, d = 7, gate = w / 2;
		List<int[][]> chests = new ArrayList<>();
		for (int i = 0; i < bulk; i++) {
			int col = i % 4, row = 2 + i / 4;
			chests.add(new int[][] {{1 + 2 * col, row, 1}, {2 + 2 * col, row, 1}});
		}
		return new Layout(w, d, gate, new int[] {gate + 1, 2, d - 3}, List.copyOf(chests));
	}

	/** The grow lot ({@code {id, type: storage_hall, size: [W, D]}}): no water, no field. Steward's ext is added by the caller. */
	public static JsonObject growLot(String lotId, int slots, int bulk) {
		Layout l = layout(slots, bulk);
		JsonObject lot = new JsonObject();
		lot.addProperty("id", lotId);
		lot.addProperty("type", "storage_hall");
		JsonArray size = new JsonArray();
		size.add(l.w());
		size.add(l.d());
		lot.add("size", size);
		return lot;
	}
}
