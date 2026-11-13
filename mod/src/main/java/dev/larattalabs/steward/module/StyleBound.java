package dev.larattalabs.steward.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A measured bound re-expressed in the town's own blocks (Noah's "a", 2026-11-13; docs/MEASURE-M2.md "The bound per Style"). Pure.
 *
 * <p>measured.json records the bound per measured Style: {@code {params, style, bom, roleParts: [{role, block, item, count}]}}, where
 * {@code roleParts} is the share of the lot's bill a palette role writes, counted over the program's template and checked against the quote
 * when it was recorded (tools/lib/style-bounds.mjs). For a town, each Style's bill loses its role parts and gains the same counts of the town's
 * item for the same role; items no role writes (dirt, seeds, the chest, the street's gravel) stay as measured. The bound is the per-item
 * maximum over the Styles.
 *
 * <p>Counts are conserved per role, so a wrong guess of the town's block only names an item the quote won't use: the build then holds and asks
 * again, with nothing consumed. A role whose town block can't be told keeps its measured item.
 *
 * <p>Role keys: {@code foundation}, {@code light} (the lamp after the palette's fallback rule: {@link PaletteCheck}), {@code path}, and the
 * wood set's {@code wood.fence} and {@code wood.fence_gate}, from Architect's kit 0.60.0 ({@code paletteFromBible}): the wood is
 * {@code roles.wood} (a wood name or a wooden block), else the first wooden role of frame, accent, floor, wall, wall_alt and roof, else oak.
 */
public final class StyleBound {
	private StyleBound() {}

	/** One role's share of a measured Style's bill. */
	public record RolePart(String role, String block, String item, int count) {}

	/** The kit's WOODS (kit/lib/kit.mjs, 0.60.0). */
	static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "poplar", "bamboo",
		"crimson", "warped");
	private static final List<String> WOODS_BY_LENGTH = WOODS.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
	private static final Pattern WOOD_PART = Pattern.compile(
		"^(planks|log|wood|stem|hyphae|block|stairs|slab|fence|fence_gate|door|trapdoor|button|pressure_plate|sign|wall_sign|hanging_sign|wall_hanging_sign|shelf|mosaic|mosaic_stairs|mosaic_slab)$");

	/** The item a block is billed as in survival where it is not its own (Architect's quote digs a dirt path and farmland as dirt). */
	public static String itemOf(String block) {
		String b = qualify(block);
		return switch (b) {
			case "minecraft:dirt_path", "minecraft:farmland" -> "minecraft:dirt";
			default -> b;
		};
	}

	static String qualify(String block) {
		return block.contains(":") ? block : "minecraft:" + block;
	}

	/** The wood a block is made of ({@code minecraft:stripped_spruce_log} -> spruce), or null (the kit's woodFamilyOf). */
	static @Nullable String woodFamilyOf(String block) {
		String q = qualify(block);
		if (!q.startsWith("minecraft:")) return null;
		String b = q.substring("minecraft:".length()).replaceFirst("^stripped_", "");
		for (String w : WOODS_BY_LENGTH) if (b.startsWith(w + "_") && WOOD_PART.matcher(b.substring(w.length() + 1)).matches()) return w;
		return null;
	}

	private static @Nullable String woodOf(@Nullable String b) {
		if (b == null) return null;
		String s = b.startsWith("minecraft:") ? b.substring("minecraft:".length()) : b;
		return WOODS.contains(s) ? s : woodFamilyOf(b);
	}

	/** The Style's wood (the kit's paletteFromBible). */
	public static String wood(Map<String, String> roles) {
		for (String r : List.of("wood", "frame", "accent", "floor", "wall", "wall_alt", "roof")) {
			String w = woodOf(roles.get(r));
			if (w != null) return w;
		}
		return "oak";
	}

	/** The town's block for each role key, from its bible's roles and the lamp the palette check gives (null: the lamp role isn't known). */
	public static Map<String, String> townBlocks(Map<String, String> roles, @Nullable String lamp) {
		Map<String, String> out = new LinkedHashMap<>();
		if (roles.get("foundation") != null) out.put("foundation", qualify(roles.get("foundation")));
		if (lamp != null) out.put("light", qualify(lamp));
		if (roles.get("path") != null) out.put("path", qualify(roles.get("path")));
		String w = wood(roles);
		out.put("wood.fence", "minecraft:" + w + "_fence");
		out.put("wood.fence_gate", "minecraft:" + w + "_fence_gate");
		return out;
	}

	/** A measured bound entry's role parts, or null when it has none or they are unreadable (then the entry is used as measured). */
	public static @Nullable List<RolePart> parts(JsonObject entry) {
		if (!entry.has("roleParts") || !entry.get("roleParts").isJsonArray()) return null;
		List<RolePart> out = new ArrayList<>();
		for (JsonElement el : entry.getAsJsonArray("roleParts")) {
			if (!el.isJsonObject()) return null;
			JsonObject o = el.getAsJsonObject();
			String role = str(o, "role"), block = str(o, "block"), item = str(o, "item");
			Integer n = o.has("count") ? ModuleRef.exactInt(o.get("count")) : null;
			if (role == null || block == null || item == null || n == null || n < 0) return null;
			out.add(new RolePart(role, block, item, n));
		}
		return out;
	}

	/**
	 * One Style's bill in the town's blocks: each role part's count moves from its measured item to the town's item for that role. Null when the
	 * bill can't take a part out (it holds fewer of the item than the parts say): the measurement is then not one this can re-express.
	 */
	public static @Nullable Map<String, Integer> mapped(Map<String, Integer> bom, List<RolePart> parts, @Nullable Map<String, String> town) {
		Map<String, Integer> out = new TreeMap<>(bom);
		for (RolePart p : parts) {
			String to = town == null ? null : town.get(p.role());
			String item = to == null ? p.item() : itemOf(to);
			if (item.equals(p.item())) continue;
			int have = out.getOrDefault(p.item(), 0);
			if (have < p.count()) return null;
			if (have == p.count()) out.remove(p.item());
			else out.put(p.item(), have - p.count());
			out.merge(item, p.count(), Integer::sum);
		}
		return out;
	}

	/** The per-item maximum of these bills. */
	public static Map<String, Integer> max(List<Map<String, Integer>> bills) {
		Map<String, Integer> out = new TreeMap<>();
		for (var b : bills) b.forEach((k, v) -> out.merge(k, v, Math::max));
		return out;
	}

	static JsonObject json(Map<String, Integer> bom) {
		JsonObject o = new JsonObject();
		new TreeMap<>(bom).forEach(o::addProperty);
		return o;
	}

	static @Nullable String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}
}
