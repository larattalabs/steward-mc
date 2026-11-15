package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.ProposalRules;
import dev.larattalabs.steward.module.ModuleOffer.Path;
import dev.larattalabs.steward.module.ModuleOffer.Town;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** storage_hall@1's lot, sign and offer (docs/MEASURE-M4.md 1.2 and 2; slice M4): the plain hall. */
class StorageHallTest {
	static final ModuleCatalog LIVE = ModuleCatalog.dir(ModuleCatalogTest.FIXTURES.resolve("../../../modules").normalize(), false);
	/** Uncertified until its measurement: judged as a measuring run judges it. */
	static final ModuleGate.Env MEASURING = new ModuleGate.Env("26.3", "0.70.0", Set.of(ModuleGate.FEATURE), false, true);
	static final ModuleKind K = ModuleKind.StorageHall.INSTANCE;
	static final JsonObject BOUND = JsonParser.parseString("{\"minecraft:chest\": 5, \"minecraft:spruce_planks\": 60}").getAsJsonObject();

	static ModuleGate.Verdict ok() {
		var v = ModuleGate.offer(LIVE, K.id(), MEASURING);
		assertTrue(v.ok(), v.message());
		return v;
	}

	static Town town(Path p, Map<String, Integer> inv, Map<String, Integer> stock) {
		return new Town(p, null, true, 100, false, false, inv, stock);
	}

	static Map<String, Integer> stacks(int kinds, int each) {
		return stacks("item_", kinds, each);
	}

	static Map<String, Integer> stacks(String prefix, int kinds, int each) {
		Map<String, Integer> m = new HashMap<>();
		for (int i = 0; i < kinds; i++) m.put("minecraft:" + prefix + i, each);
		return m;
	}

	@Test
	void thePlainHallIsOneFrameWithAnInputChestAndItsBulkPairs() {
		for (int bulk = 0; bulk <= StorageHallLot.BULK_MAX; bulk++) {
			var l = StorageHallLot.layout(0, bulk);
			assertEquals(11, l.w());
			assertEquals(7, l.d());
			assertEquals(5, l.gate());
			assertEquals(1 + 2 * bulk, l.containers().size());
			Set<String> seen = new HashSet<>();
			for (int[] c : l.containers()) {
				assertTrue(seen.add(c[0] + "," + c[1] + "," + c[2]), "no container cell twice");
				assertTrue(c[0] >= 1 && c[0] <= l.w() - 2 && c[2] >= 1 && c[2] <= l.d() - 3, "inside the walls");
			}
			for (int[][] b : l.bulk()) {
				assertEquals(b[0][0] + 1, b[1][0], "a pair's halves side by side");
				assertEquals(b[0][1], b[1][1]);
				assertEquals(1, b[0][2], "along the back wall");
				assertTrue(b[0][1] == 2 || b[0][1] == 3, "the lower row, then the upper");
			}
			// the input chest stands alone: no chest beside it, so it never merges
			int[] in = l.input();
			for (int[] c : l.containers()) if (c != in) assertTrue(Math.abs(c[0] - in[0]) + Math.abs(c[2] - in[2]) > 1 || c[1] != in[1]);
			JsonObject lot = StorageHallLot.growLot("hall_1", 0, bulk);
			assertEquals("storage_hall", lot.get("type").getAsString());
			assertEquals(11, lot.getAsJsonArray("size").get(0).getAsInt());
			assertEquals(7, lot.getAsJsonArray("size").get(1).getAsInt());
			assertFalse(lot.has("irrigate"));
			assertFalse(lot.has("field"));
		}
		assertThrows(IllegalArgumentException.class, () -> StorageHallLot.layout(4, 2), "the sorting branches wait for the filler");
		assertThrows(IllegalArgumentException.class, () -> StorageHallLot.layout(0, 9));
	}

	@Test
	void itsSignIsTwelveKindsOfStoresNotSeeds() {
		var tier = dev.larattalabs.steward.model.Progress.Tier.values()[0];
		assertFalse(ProposalRules.moduleFires(K, new ProposalRules.Signs(tier, 64, 0, Map.of(), Map.of("minecraft:wheat_seeds", 64), 11)));
		assertTrue(ProposalRules.moduleFires(K, new ProposalRules.Signs(tier, 0, 0, Map.of(), Map.of(), 12)));
		// a farm is never signed by stores
		assertFalse(ProposalRules.moduleFires(ModuleKind.CropFarm.INSTANCE, new ProposalRules.Signs(tier, 0, 0, Map.of(), Map.of(), 40)));
		assertTrue(ModuleKind.of("storage_hall").isPresent());
		assertEquals("module.storage_hall", K.rule());
		assertTrue(K.decorative().isEmpty(), "no decorative rule is retired by declining it");
		assertTrue(K.smaller(JsonParser.parseString("{\"bulk\": 4, \"slots\": 0}").getAsJsonObject()).isEmpty(), "one frame: nothing smaller to try");
	}

	@Test
	void theBulkIsTheStacksHeldAtFiftyFourADoubleChest() {
		var few = ModuleOffer.decide(K, ok(), town(Path.REGION, stacks(12, 64), Map.of()), null, (e, p) -> BOUND);
		assertTrue(few.ok(), few.why());
		assertEquals(2, few.order().params().get("bulk").getAsInt(), "12 stacks: the fewest, 2");
		assertEquals(0, few.order().params().get("slots").getAsInt());
		var many = ModuleOffer.decide(K, ok(), town(Path.REGION, stacks(6, 64 * 30), stacks("stock_", 3, 64 * 20)), null, (e, p) -> BOUND);
		assertEquals(5, many.order().params().get("bulk").getAsInt(), "6 x 30 + 3 x 20 = 240 stacks: 5 double chests");
		assertEquals(9, many.seedsHeld(), "the kinds held, for the words");
		var huge = ModuleOffer.decide(K, ok(), town(Path.REGION, stacks(20, 64 * 64), Map.of()), null, (e, p) -> BOUND);
		assertEquals(8, huge.order().params().get("bulk").getAsInt(), "at most the largest measured");
		var asked = ModuleOffer.decide(K, ok(), town(Path.REGION, stacks(12, 64), Map.of()), JsonParser.parseString("{\"bulk\": 7}").getAsJsonObject(), (e, p) -> BOUND);
		assertEquals(7, asked.order().params().get("bulk").getAsInt(), "a measured bulk asked for is kept");
		var odd = ModuleOffer.decide(K, ok(), town(Path.REGION, stacks(12, 64), Map.of()), JsonParser.parseString("{\"bulk\": 12}").getAsJsonObject(), (e, p) -> BOUND);
		assertEquals(2, odd.order().params().get("bulk").getAsInt(), "one outside the envelope is not");
	}

	@Test
	void fromMaterialsItHoldsTheMeasuredBoundAndSaysIt() {
		var t = town(Path.REGION_MATERIALS, stacks(12, 64), Map.of());
		var d = ModuleOffer.decide(K, ok(), t, null, (e, p) -> BOUND);
		assertTrue(d.ok(), d.why());
		assertEquals(BOUND, d.order().bound());
		String why = ModuleOffer.why(K, d, true, false);
		assertTrue(why.startsWith("Your stores are getting mixed: 12 kinds of items. I can raise a storage hall by the town (11 by 7): 2 double chests and an input chest by the door."), why);
		assertTrue(why.contains("chest"), why);
		assertTrue(ModuleOffer.why(K, d, false, true).contains("plain lanterns"));
		assertTrue(ModuleOffer.why(K, d, false, false).endsWith("It's free."));
		assertFalse(ModuleOffer.decide(K, ok(), t, null, (e, p) -> null).ok(), "no measured bound: not offered from materials");
	}

	@Test
	void anyTownPathAndBiomeTakesIt() {
		var frozen = new Town(Path.ADOPT, null, true, 100, false, true, stacks(12, 64), Map.of());
		assertTrue(ModuleOffer.decide(K, ok(), frozen, null, (e, p) -> BOUND).ok(), "no water: an adopted town and a cold biome take it");
		assertFalse(ModuleOffer.decide(K, ok(), new Town(Path.NONE, "a bent street", true, 100, false, false, stacks(12, 64), Map.of()), null, (e, p) -> BOUND).ok());
	}

	@Test
	void theCertifiedHallIsOfferedFromMaterialsWithItsBoundInATownsOwnBlocks() {
		var player = new ModuleGate.Env("26.3", "0.70.0", Set.of(ModuleGate.FEATURE));
		var v = ModuleGate.offer(LIVE, "storage_hall", player);
		assertTrue(v.ok(), v.message());
		assertFalse(ModuleGate.offer(LIVE, "storage_hall", new ModuleGate.Env("26.3", "0.67.0", Set.of(ModuleGate.FEATURE))).ok(), "only on its certified Architect");
		// a town in a Style none of the measured ones is: oak walls and floor, a stone-brick foundation, a dark oak roof slab, an oak door
		java.util.Map<String, String> town = new java.util.HashMap<>(java.util.Map.of("foundation", "minecraft:stone_bricks", "floor", "minecraft:oak_planks",
			"wall", "minecraft:oak_planks", "frame", "minecraft:oak_log", "light", "minecraft:lantern", "path", "minecraft:dirt", "wood.door", "minecraft:oak_door",
			"roof.slab", "minecraft:dark_oak_slab"));
		var t = new Town(Path.REGION_MATERIALS, null, true, 100, false, false, Map.of(), stacks(12, 64));
		var d = ModuleOffer.decide(K, v, t, null, (e, p) -> ModuleBound.of(e.measured(), p, town));
		assertTrue(d.ok(), d.why());
		var b = d.order().bound();
		assertTrue(b.has("minecraft:oak_planks") && b.has("minecraft:oak_door") && b.has("minecraft:chest"), b.toString());
		assertEquals(5, b.get("minecraft:chest").getAsInt(), "2 double chests and the input chest");
	}

	@Test
	void uncertifiedItIsNeverOfferedToAPlayer() {
		var player = new ModuleGate.Env("26.3", "0.70.0", Set.of(ModuleGate.FEATURE));
		assertEquals(LIVE.newest("storage_hall").map(ModuleCatalog.Entry::measured).isPresent() && ModuleGate.offer(LIVE, "storage_hall", player).ok(),
			LIVE.newest("storage_hall").get().uncertified().isEmpty(), "offered to a player exactly when measured.json certifies it");
	}
}
