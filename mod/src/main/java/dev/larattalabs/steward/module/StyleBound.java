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
 * <p>Counts are conserved per role. The safety is the approved per-item ceiling itself, checked against Architect's quote before anything is
 * consumed: a wrong guess of the town's block names an item the quote won't use, and the quote's own item then holds the build, unless a
 * ceiling left for another part (the street's gravel) happens to cover it. A role whose town block has no item keeps its measured item.
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

	/**
	 * Architect's survival block-to-item special cases (kit/vocab/survival.json {@code costs} at v0.65.0, kit 0.60.0): a placed block charged as
	 * another item (a dirt path, farmland and a grass block as dirt), or as nothing (null: water, fire...). Every other block costs its own item.
	 */
	static final Map<String, @Nullable String> COSTS = new java.util.HashMap<>();
	static {
		COSTS.put("minecraft:dirt_path", "minecraft:dirt");
		COSTS.put("minecraft:farmland", "minecraft:dirt");
		COSTS.put("minecraft:grass_block", "minecraft:dirt");
		COSTS.put("minecraft:attached_melon_stem", "minecraft:melon_seeds");
		COSTS.put("minecraft:attached_pumpkin_stem", "minecraft:pumpkin_seeds");
		COSTS.put("minecraft:melon_stem", "minecraft:melon_seeds");
		COSTS.put("minecraft:pumpkin_stem", "minecraft:pumpkin_seeds");
		COSTS.put("minecraft:cave_vines_plant", "minecraft:glow_berries");
		COSTS.put("minecraft:cave_vines", "minecraft:glow_berries");
		COSTS.put("minecraft:kelp_plant", "minecraft:kelp");
		COSTS.put("minecraft:twisting_vines_plant", "minecraft:twisting_vines");
		COSTS.put("minecraft:weeping_vines_plant", "minecraft:weeping_vines");
		COSTS.put("minecraft:big_dripleaf_stem", "minecraft:big_dripleaf");
		COSTS.put("minecraft:bamboo_sapling", "minecraft:bamboo");
		COSTS.put("minecraft:tall_seagrass", "minecraft:seagrass");
		COSTS.put("minecraft:water_cauldron", "minecraft:cauldron");
		COSTS.put("minecraft:lava_cauldron", "minecraft:cauldron");
		COSTS.put("minecraft:powder_snow_cauldron", "minecraft:cauldron");
		COSTS.put("minecraft:redstone_wire", "minecraft:redstone");
		COSTS.put("minecraft:tripwire", "minecraft:string");
		COSTS.put("minecraft:fire", null);
		COSTS.put("minecraft:soul_fire", null);
		COSTS.put("minecraft:water", null);
		COSTS.put("minecraft:lava", null);
		COSTS.put("minecraft:bubble_column", null);
		COSTS.put("minecraft:moving_piston", null);
		COSTS.put("minecraft:piston_head", null);
		COSTS.put("minecraft:frosted_ice", null);
	}

	/**
	 * The item a block is billed as in survival: Architect's special case, else the block's own item ({@code asItem}: Minecraft's in game). Null:
	 * it costs nothing or has no item, so a role on it can't be re-expressed (its measured item is kept).
	 */
	public static @Nullable String itemOf(String block, java.util.function.UnaryOperator<@Nullable String> asItem) {
		String b = qualify(block);
		if (COSTS.containsKey(b)) return COSTS.get(b);
		return asItem.apply(b);
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

	/** The item the town is billed for each role key ({@link #townBlocks} through {@link #itemOf}); a role whose block has no item is left out. */
	public static Map<String, String> townItems(Map<String, String> roles, @Nullable String lamp, java.util.function.UnaryOperator<@Nullable String> asItem) {
		Map<String, String> out = new LinkedHashMap<>();
		townBlocks(roles, lamp).forEach((role, block) -> {
			String it = itemOf(block, asItem);
			if (it != null) out.put(role, it);
		});
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
	 * One Style's bill in the town's items ({@code town}: role key -> item, {@link #townItems}): each role part's count moves from its measured
	 * item to the town's item for that role. Null when the
	 * bill can't take a part out (it holds fewer of the item than the parts say): the measurement is then not one this can re-express.
	 */
	public static @Nullable Map<String, Integer> mapped(Map<String, Integer> bom, List<RolePart> parts, @Nullable Map<String, String> town) {
		Map<String, Integer> out = new TreeMap<>(bom);
		for (RolePart p : parts) {
			String to = town == null ? null : town.get(p.role());
			String item = to == null ? p.item() : to;
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
