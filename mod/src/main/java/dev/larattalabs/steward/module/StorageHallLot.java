package dev.larattalabs.steward.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code storage_hall}'s lot and containers in Java (docs/MEASURE-M4.md 1.2 and 12.2), the program's own {@code layout}
 * (modules/storage_hall/2/program.mjs; @1's plain hall is its {@code slots 0}) in the lot's frame: front to the south, x across, z from the back,
 * y the template row. The measurement checks these cells equal the program's on every hall. Pure.
 */
public final class StorageHallLot {
	private StorageHallLot() {}

	/** The slot counts the program builds (@1 certified only 0). */
	public static final List<Integer> SLOTS = List.of(0, 4, 8, 12, 16);
	/** The most bulk double chests. */
	public static final int BULK_MAX = 8;
	/** The most sorting slots, and so the item params ({@code item1} .. {@code item16}). */
	public static final int SLOT_MAX = 16;
	/** F's fill: 41 of its filter item (billed), and a module-provided filler in each of slots 1-4 (FK-b). */
	public static final int FILTER_COUNT = 41;
	public static final int FILLERS = 4;
	public static final String FILLER = "minecraft:petrified_oak_slab";

	/**
	 * The filter domain (MEASURE-M4 12.1), the program's {@code FILTERABLE} keys (item {@code minecraft:<key>}); a unit test pins them equal to
	 * module.json's enum options.
	 */
	public static final List<String> FILTERABLE = List.of("cobblestone", "cobbled_deepslate", "stone", "deepslate", "dirt", "sand", "red_sand", "gravel",
		"andesite", "diorite", "granite", "tuff", "calcite", "netherrack", "blackstone", "basalt", "end_stone", "clay_ball", "flint", "oak_log", "spruce_log",
		"birch_log", "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log", "coal", "charcoal", "iron_ingot", "gold_ingot", "copper_ingot",
		"raw_iron", "raw_gold", "raw_copper", "redstone", "lapis_lazuli", "quartz", "diamond", "emerald", "wheat", "wheat_seeds", "beetroot_seeds",
		"sugar_cane", "bone", "string", "gunpowder", "rotten_flesh");

	/** What a container cell of the hall is (MEASURE-M4 12.4, 12.7 S6: only a committed F has an allowance; every other one holds nothing of ours). */
	public enum Kind { INPUT, OVERFLOW, LINE, FILTER, OUTPUT, SLOT, BULK }

	/** A container cell: its kind, its template cell, and for a slot's cells its slot index (0-based; -1 otherwise). */
	public record Cell(Kind kind, int[] at, int slot) {}

	/**
	 * The layout: the frame {@code w x d}, the door's column, and every container cell by kind (template cells {@code [x, y, z]}, {@code y} the
	 * template row).
	 */
	public record Layout(int w, int d, int gate, int slots, int[] input, List<int[][]> bulk, List<Cell> cells) {
		/** Every container cell (the kinds' cells, in order: input, overflow, then per slot line/F/D/chest, then each bulk pair's halves). */
		public List<int[]> containers() {
			List<int[]> out = new ArrayList<>();
			for (Cell c : cells) out.add(c.at());
			return List.copyOf(out);
		}
	}

	/** The layout for these params; throws on a branch the program doesn't build. */
	public static Layout layout(int slots, int bulk) {
		if (!SLOTS.contains(slots)) throw new IllegalArgumentException("storage_hall: slots must be one of " + SLOTS);
		if (bulk < 0 || bulk > BULK_MAX) throw new IllegalArgumentException("storage_hall: bulk must be 0.." + BULK_MAX);
		List<Cell> cells = new ArrayList<>();
		List<int[][]> chests = new ArrayList<>();
		if (slots == 0) {
			int w = 11, d = 7, gate = w / 2;
			int[] input = {gate + 1, 2, d - 3};
			cells.add(new Cell(Kind.INPUT, input, -1));
			for (int i = 0; i < bulk; i++) {
				int col = i % 4, row = 2 + i / 4;
				chests.add(new int[][] {{1 + 2 * col, row, 1}, {2 + 2 * col, row, 1}});
			}
			for (int[][] b : chests) {
				cells.add(new Cell(Kind.BULK, b[0], -1));
				cells.add(new Cell(Kind.BULK, b[1], -1));
			}
			return new Layout(w, d, gate, 0, input, List.copyOf(chests), List.copyOf(cells));
		}
		int s = slots, w = Math.max(s + 3, 11), d = 13, line = 6, b = 2, gate = w / 2;
		int[] input = {1, b + 3, line};
		cells.add(new Cell(Kind.INPUT, input, -1));
		cells.add(new Cell(Kind.OVERFLOW, new int[] {s + 1, b + 2, line}, -1));
		for (int u = 0; u < s; u++) {
			int x = 1 + u;
			cells.add(new Cell(Kind.LINE, new int[] {x, b + 2, line}, u));
			cells.add(new Cell(Kind.FILTER, new int[] {x, b + 1, line}, u));
			cells.add(new Cell(Kind.OUTPUT, new int[] {x, b, line}, u));
			cells.add(new Cell(Kind.SLOT, new int[] {x, b, line + 1}, u));
		}
		List<Integer> pairs = new ArrayList<>();
		for (int x = 1; x + 1 <= gate - 1; x += 2) pairs.add(x);
		for (int x = gate + 1; x + 1 <= w - 2; x += 2) pairs.add(x);
		for (int i = 0; i < bulk; i++) {
			int x = pairs.get(i % pairs.size()), row = 2 + i / pairs.size();
			chests.add(new int[][] {{x, row, 10}, {x + 1, row, 10}});
		}
		for (int[][] p : chests) {
			cells.add(new Cell(Kind.BULK, p[0], -1));
			cells.add(new Cell(Kind.BULK, p[1], -1));
		}
		return new Layout(w, d, gate, s, input, List.copyOf(chests), List.copyOf(cells));
	}

	/**
	 * The filter items of these params in slot order ({@code minecraft:} ids), or why they break the dependent rule (MEASURE-M4 12.1): items 1 to
	 * {@code slots} distinct domain keys, every later one {@code none} (absent counts as {@code none}). The program's {@code filterItems} is the same
	 * rule.
	 */
	public record Items(List<String> items, @Nullable String refused) {
		public boolean ok() {
			return refused == null;
		}
	}

	public static Items items(JsonObject params) {
		int slots = intOf(params, "slots");
		List<String> out = new ArrayList<>();
		for (int i = 1; i <= SLOT_MAX; i++) {
			JsonElement v = params.get("item" + i);
			String k = v != null && v.isJsonPrimitive() ? v.getAsString() : "none";
			if (i <= slots) {
				if (k.equals("none") || !FILTERABLE.contains(k)) return new Items(List.of(), "item" + i + " must be a filter item (slot " + i + " of " + slots + "), not '" + k + "'");
				if (out.contains("minecraft:" + k)) return new Items(List.of(), "item" + i + " repeats item" + (out.indexOf("minecraft:" + k) + 1) + " ('" + k + "')");
				out.add("minecraft:" + k);
			} else if (!k.equals("none")) return new Items(List.of(), "item" + i + " must be none (the hall has " + slots + " slots)");
		}
		return new Items(List.copyOf(out), null);
	}

	/** The fill's billed part (41 of each filter item; the fillers are provided, unbilled): item -> count. */
	public static java.util.Map<String, Integer> fillBill(JsonObject params) {
		var it = items(params);
		java.util.Map<String, Integer> out = new java.util.TreeMap<>();
		if (!it.ok()) return out;
		for (String i : it.items()) out.merge(i, FILTER_COUNT, Integer::sum);
		return out;
	}

	private static int intOf(JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
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
