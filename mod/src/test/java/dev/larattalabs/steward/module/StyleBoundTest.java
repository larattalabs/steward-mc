package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The crop farm's bound in the town's own blocks (StyleBound; Noah's "a", 2026-11-13). The fixtures are real: the 0.64.1 bounds step's quotes
 * split per Style by tools/lib/style-bounds.mjs, and the kit's own fence and gate for a set of Styles (kit 0.60.0, paletteFromBible).
 */
class StyleBoundTest {
	static JsonObject res(String name) {
		try (var in = StyleBoundTest.class.getResourceAsStream("/style-bound/" + name)) {
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw new AssertionError(name, e);
		}
	}

	static Map<String, String> roles(JsonObject o) {
		Map<String, String> m = new HashMap<>();
		for (var e : o.entrySet()) m.put(e.getKey(), e.getValue().getAsString());
		return m;
	}

	static final JsonObject WOODS = res("woods-kit-0.60.0.json");
	static final JsonObject BOUNDS = res("crop-farm-bounds-0.64.1.json");

	static JsonObject params(int w, int d, String crop) {
		JsonObject p = new JsonObject();
		p.addProperty("crop", crop);
		p.addProperty("depth", d);
		p.addProperty("width", w);
		return p;
	}

	static ModuleCatalog.Measured measured(JsonArray bounds) {
		JsonObject raw = new JsonObject();
		raw.add(ModuleBound.MEMBER, bounds);
		return new ModuleCatalog.Measured("", "", "", "", List.of(), null, null, false, raw);
	}

	static Map<String, String> town(String style) {
		var r = roles(WOODS.getAsJsonObject(style).getAsJsonObject("roles"));
		// the crop farm's lamp rule (light 14 or more, else the lantern): every Style here lights with 14 or more
		return StyleBound.townItems(r, r.get("light"), b -> b);
	}

	@Test
	void theWoodPortMatchesTheKit() {
		for (var e : WOODS.entrySet()) {
			var o = e.getValue().getAsJsonObject();
			var b = StyleBound.townBlocks(roles(o.getAsJsonObject("roles")), null);
			assertEquals(o.get("fence").getAsString(), b.get("wood.fence"), e.getKey());
			assertEquals(o.get("fenceGate").getAsString(), b.get("wood.fence_gate"), e.getKey());
		}
	}

	@Test
	void blocksAreBilledAsArchitectBillsThem() {
		// Architect's special cases are the kit's own (vocab/survival.json costs at v0.65.0), every one of them
		var kit = res("survival-costs-kit-0.60.0.json").getAsJsonObject("costs");
		Map<String, String> want = new HashMap<>();
		for (var e : kit.entrySet()) want.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
		assertEquals(want, StyleBound.COSTS);
		java.util.function.UnaryOperator<String> mc = b -> b.equals("minecraft:wall_torch") ? "minecraft:torch" : b.equals("minecraft:air") ? null : b;
		assertEquals("minecraft:dirt", StyleBound.itemOf("minecraft:dirt_path", mc));
		assertEquals("minecraft:dirt", StyleBound.itemOf("farmland", mc));
		assertEquals("minecraft:dirt", StyleBound.itemOf("minecraft:grass_block", mc));
		assertEquals("minecraft:coarse_dirt", StyleBound.itemOf("minecraft:coarse_dirt", mc));
		assertEquals("minecraft:torch", StyleBound.itemOf("minecraft:wall_torch", mc), "else the block's own item, as the game has it");
		assertNull(StyleBound.itemOf("minecraft:water", mc), "a block billed as nothing");
		// a grass path is billed as dirt, so its 12 stay dirt: the whole 222, nothing named grass
		var r = new HashMap<>(roles(WOODS.getAsJsonObject("nether").getAsJsonObject("roles")));
		r.put("path", "minecraft:grass_block");
		var b = ModuleBound.of(measured(BOUNDS.getAsJsonArray("perStyle")), params(9, 9, "wheat"), StyleBound.townItems(r, r.get("light"), mc));
		assertEquals(222, b.get("minecraft:dirt").getAsInt());
		assertFalse(b.has("minecraft:grass_block"));
		// a path on nothing (water) keeps its measured item
		r.put("path", "minecraft:water");
		assertEquals(222, ModuleBound.of(measured(BOUNDS.getAsJsonArray("perStyle")), params(9, 9, "wheat"), StyleBound.townItems(r, r.get("light"), mc)).get("minecraft:dirt").getAsInt());
	}

	@Test
	void aMeasuredStyleGivesTheBoundAsBefore() {
		var m = measured(BOUNDS.getAsJsonArray("perStyle"));
		for (JsonElement el : BOUNDS.getAsJsonArray("merged")) {
			var o = el.getAsJsonObject();
			JsonObject p = o.getAsJsonObject("params");
			// no town (unknown bible): the per-item maximum of the three Styles as measured, which is what measured.json held before
			assertEquals(ModuleBound.clean(o.getAsJsonObject("bom")), ModuleBound.of(m, p), "as measured " + p);
			for (String style : List.of("rustic", "fortress", "desert")) {
				// each Style's own entry maps onto itself unchanged
				for (JsonElement s : BOUNDS.getAsJsonArray("perStyle")) {
					var so = s.getAsJsonObject();
					if (!so.get("style").getAsString().equals(style) || !Canon.sorted(so.get("params")).equals(Canon.sorted(p))) continue;
					var bill = new TreeMap<String, Integer>();
					for (var e : so.getAsJsonObject("bom").entrySet()) bill.put(e.getKey(), e.getValue().getAsInt());
					assertEquals(bill, StyleBound.mapped(bill, StyleBound.parts(so), town(style)), style + " " + p);
				}
				// a town in a measured Style is bound at least as tightly as before, and never names an item the measurement didn't
				var b = ModuleBound.of(m, p, town(style));
				assertTrue(ModuleBound.over(b, o.getAsJsonObject("bom")).isEmpty(), style + " " + p + ": " + b);
			}
		}
	}

	@Test
	void aTownInAnUnmeasuredStyleIsBoundInItsOwnBlocks() {
		var m = measured(BOUNDS.getAsJsonArray("perStyle"));
		var b = ModuleBound.of(m, params(9, 9, "wheat"), town("nether"));
		// the e2e town's quote (M3's module-survival, 2026-11-13: a crimson, blackstone and shroomlight Style with a coarse-dirt path)
		JsonObject quote = JsonParser.parseString("""
			{"minecraft:dirt": 210, "minecraft:wheat_seeds": 74, "minecraft:crimson_fence": 43, "minecraft:blackstone": 41, "minecraft:coarse_dirt": 12,
			 "minecraft:shroomlight": 7, "minecraft:chest": 1, "minecraft:composter": 1, "minecraft:crimson_fence_gate": 1}""").getAsJsonObject();
		assertEquals(List.of(), ModuleBound.over(quote, b), "within the mapped bound: " + b);
		// the lot's roles moved to the town's blocks; the street (gravel, an oak post and lanterns) and the role-free items stayed
		assertEquals(210, b.get("minecraft:dirt").getAsInt());
		assertEquals(12, b.get("minecraft:coarse_dirt").getAsInt());
		assertEquals(41, b.get("minecraft:blackstone").getAsInt());
		assertEquals(7, b.get("minecraft:shroomlight").getAsInt());
		assertEquals(1, b.get("minecraft:crimson_fence_gate").getAsInt());
		assertEquals(95, b.get("minecraft:gravel").getAsInt());
		assertEquals(2, b.get("minecraft:lantern").getAsInt());
		assertEquals(2, b.get("minecraft:oak_fence").getAsInt());
		for (String gone : List.of("minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:sandstone", "minecraft:spruce_fence", "minecraft:jungle_fence"))
			assertFalse(b.has(gone), gone);
		// a crimson fence more than the lot writes is still over
		quote.addProperty("minecraft:crimson_fence", b.get("minecraft:crimson_fence").getAsInt() + 1);
		assertEquals(1, ModuleBound.over(quote, b).size());
	}

	@Test
	void countsMoveRoleByRoleAndRolesOnOneBlockAdd() {
		Map<String, Integer> bill = new TreeMap<>(Map.of("minecraft:cobblestone", 41, "minecraft:dirt", 222, "minecraft:lantern", 9));
		var parts = List.of(new StyleBound.RolePart("foundation", "minecraft:cobblestone", "minecraft:cobblestone", 41),
			new StyleBound.RolePart("path", "minecraft:dirt_path", "minecraft:dirt", 12), new StyleBound.RolePart("light", "minecraft:lantern", "minecraft:lantern", 7));
		// a Style whose foundation and path are both mud bricks: the two roles' counts add
		var out = StyleBound.mapped(bill, parts, Map.of("foundation", "minecraft:mud_bricks", "path", "minecraft:mud_bricks", "light", "minecraft:glowstone"));
		assertEquals(Map.of("minecraft:mud_bricks", 53, "minecraft:dirt", 210, "minecraft:glowstone", 7, "minecraft:lantern", 2), out);
		// a role the town's bible doesn't name keeps its measured item
		assertEquals(bill, StyleBound.mapped(bill, parts, Map.of()));
		assertEquals(bill, StyleBound.mapped(bill, parts, null));
		// the total count is conserved
		assertEquals(bill.values().stream().mapToInt(i -> i).sum(), out.values().stream().mapToInt(i -> i).sum());
		// a bill holding fewer than its parts can't be re-expressed
		assertNull(StyleBound.mapped(Map.of("minecraft:cobblestone", 40), parts.subList(0, 1), Map.of("foundation", "minecraft:blackstone")));
	}

	@Test
	void anUnreadableEntryGivesNoBound() {
		var p = params(9, 9, "wheat");
		JsonObject e = new JsonObject();
		e.add("params", p);
		e.addProperty("style", "rustic");
		e.add("bom", JsonParser.parseString("{\"minecraft:dirt\": 10}"));
		e.add("roleParts", JsonParser.parseString("[{\"role\": \"path\", \"block\": \"minecraft:dirt_path\", \"item\": \"minecraft:dirt\"}]"));
		JsonArray a = new JsonArray();
		a.add(e);
		assertNull(ModuleBound.of(measured(a), p, town("nether")), "a role part without a count");
		e.add("roleParts", JsonParser.parseString("[{\"role\": \"path\", \"block\": \"minecraft:dirt_path\", \"item\": \"minecraft:dirt\", \"count\": 12}]"));
		assertNull(ModuleBound.of(measured(a), p, town("nether")), "more path than the bill's dirt");
		// an entry without roleParts (the dev source, a fixture) is used as it is
		e.remove("roleParts");
		assertEquals(JsonParser.parseString("{\"minecraft:dirt\": 10}"), ModuleBound.of(measured(a), p, town("nether")));
	}
}
