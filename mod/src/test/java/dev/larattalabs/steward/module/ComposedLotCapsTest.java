package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * (slice LIFT-S, Architect LCAP v0.78.0) A composed town's module lots from materials, each held by its own cap: what each lot's cap is (its saved
 * bound), what the whole realise's {@code maxBill} is (the rest of the bill, a terrain item with its allowance, plus the lot caps), which bill a lot cap
 * is checked against ({@code Quote.lotBill}: lot:, fill:, mech:), and the words of a put-down refusal by its detail.
 */
class ComposedLotCapsTest {
	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	static ModuleOrder order(String id, String bound) {
		return new ModuleOrder(id, 1, "c".repeat(64), json("{\"crop\": \"wheat\", \"depth\": 9, \"width\": 9}"), bound == null ? null : json(bound), null, false, false);
	}

	/** Two module lots that share an item (oak planks), a designed cottage, the town's tiles; the hall has a fill and a (made up) mechanism piece. */
	static final JsonObject PARTS = json("""
		{"tiles:x": {"minecraft:dirt": 500, "minecraft:gravel": 30}, "lot:cottage_1": {"minecraft:oak_planks": 60, "minecraft:cobblestone": 20},
		 "lot:farm_1": {"minecraft:dirt": 120, "minecraft:oak_planks": 10}, "lot:hall_1": {"minecraft:oak_planks": 90},
		 "fill:hall_1": {"minecraft:coal": 41}, "mech:hall_1": {"minecraft:iron_ingot": 2}}""");
	static final JsonObject BOM = json("""
		{"minecraft:dirt": 620, "minecraft:gravel": 30, "minecraft:oak_planks": 160, "minecraft:cobblestone": 20, "minecraft:coal": 41, "minecraft:iron_ingot": 2}""");

	static Map<String, ModuleOrder> orders() {
		Map<String, ModuleOrder> o = new LinkedHashMap<>();
		o.put("farm_1", order("crop_farm", "{\"minecraft:dirt\": 130, \"minecraft:oak_planks\": 12}"));
		o.put("hall_1", order("storage_hall", "{\"minecraft:oak_planks\": 100, \"minecraft:coal\": 41, \"minecraft:iron_ingot\": 2}"));
		return o;
	}

	@Test
	void aLotsShareIsArchitectsLotBillItsLotFillAndMechPieces() {
		assertEquals(dev.larattalabs.architect.api.Quote.LOT_PIECES, ComposedModules.LOT_PIECES, "the pieces Architect checks a lot cap against");
		for (String lot : java.util.List.of("farm_1", "hall_1", "cottage_1", "pad_9")) {
			JsonObject share = ComposedModules.share(PARTS, lot);
			Map<String, Integer> mine = new TreeMap<>();
			share.entrySet().forEach(e -> mine.put(e.getKey(), e.getValue().getAsInt()));
			assertEquals(dev.larattalabs.architect.api.Quote.lotBill(PARTS, lot), mine, lot);
		}
		assertEquals(2, ComposedModules.share(PARTS, "hall_1").get("minecraft:iron_ingot").getAsInt(), "a mechanism piece is the lot's own");
	}

	@Test
	void eachLotIsCappedAtItsBoundAndTheRealiseAtTheRestPlusTheLotCaps() {
		var caps = ComposedModules.caps(BOM, PARTS, Map.of("minecraft:dirt", 500, "minecraft:gravel", 0), orders(), Set.of("farm_1", "hall_1", "cottage_1"));
		assertNull(caps.refused(), caps.refused());
		assertEquals(Map.of("farm_1", Map.of("minecraft:dirt", 130, "minecraft:oak_planks", 12),
			"hall_1", Map.of("minecraft:oak_planks", 100, "minecraft:coal", 41, "minecraft:iron_ingot", 2)), caps.perLot());
		// the rest: dirt 620-120 = 500 (+ its allowance, 2% of 500 = 10), gravel 30 (+2: terrain listed at 0), planks 160-10-90 = 60, cobble 20; coal and
		// iron are the hall's own (the rest has none of them); then each lot cap added
		var m = caps.maxBill();
		assertEquals(500 + 10 + 130, m.get("minecraft:dirt"));
		assertEquals(30 + 2, m.get("minecraft:gravel"));
		assertEquals(60 + 12 + 100, m.get("minecraft:oak_planks"), "a shared item: the rest plus both lots' caps");
		assertEquals(20, m.get("minecraft:cobblestone"));
		assertEquals(41, m.get("minecraft:coal"));
		assertEquals(2, m.get("minecraft:iron_ingot"));
		// every item a lot cap names is in maxBill at least at that cap (an item maxBill doesn't name is capped at 0 by Architect)
		for (var lot : caps.perLot().values()) for (var c : lot.entrySet()) assertTrue(m.getOrDefault(c.getKey(), 0) >= c.getValue(), c.getKey());
		// deterministic: the same saved orders and quote give the same caps (a restart re-quotes and sends the same lot caps)
		assertEquals(caps, ComposedModules.caps(BOM, PARTS, Map.of("minecraft:dirt", 500, "minecraft:gravel", 0), orders(), Set.of("farm_1", "hall_1", "cottage_1")));
	}

	@Test
	void anApprovedBillIsTheLotsCapAndNoTerrainMeansExactRest() {
		Map<String, ModuleOrder> o = orders();
		// the hold approved: its bill (the share, at least the bound) becomes the bound, and so the cap
		o.put("farm_1", o.get("farm_1").holding(json("{\"minecraft:dirt\": 140, \"minecraft:oak_planks\": 12}")).approvedHeld());
		var caps = ComposedModules.caps(BOM, PARTS, Map.of(), o, Set.of("farm_1", "hall_1", "cottage_1"));
		assertEquals(140, caps.perLot().get("farm_1").get("minecraft:dirt"));
		assertEquals(500 + 140, caps.maxBill().get("minecraft:dirt"), "no terrain part: the rest exactly as quoted");
	}

	@Test
	void capsThatCantBeSentRefuseAndNothingIsSent() {
		// a module lot this realise doesn't bind (a lot of an earlier revision would be unknown_lot to Architect)
		var notHere = ComposedModules.caps(BOM, PARTS, Map.of(), orders(), Set.of("farm_1", "cottage_1"));
		assertNotNull(notHere.refused());
		assertNull(notHere.perLot());
		assertTrue(notHere.refused().contains("storage hall"), notHere.refused());
		// no bound
		Map<String, ModuleOrder> o = orders();
		o.put("farm_1", order("crop_farm", null));
		assertTrue(ComposedModules.caps(BOM, PARTS, Map.of(), o, Set.of("farm_1", "hall_1")).refused().contains("no bound"));
		// a quote that isn't a bill
		assertNotNull(ComposedModules.caps(json("{\"minecraft:dirt\": -1}"), PARTS, Map.of(), orders(), Set.of("farm_1", "hall_1")).refused());
		assertNotNull(ComposedModules.caps(BOM, json("{\"lot:farm_1\": {\"minecraft:dirt\": \"x\"}}"), Map.of(), orders(), Set.of("farm_1", "hall_1")).refused());
	}

	@Test
	void aPutDownRefusalSaysWhichCapByItsDetail() {
		var lot = ComposedModules.CapRefusal.of("lot:hall_1");
		assertEquals(ComposedModules.CapRefusal.Scope.LOT, lot.scope());
		assertEquals("hall_1", lot.lot());
		String w = ComposedModules.putDownWords(lot, orders());
		assertTrue(w.contains("the materials the working storage hall needed went past its limit"), w);
		assertTrue(w.contains("Nothing was taken from the stockpile; I'll ask you again."), w);
		var unknown = ComposedModules.CapRefusal.of("unknown_lot:la");
		assertEquals(ComposedModules.CapRefusal.Scope.UNKNOWN_LOT, unknown.scope());
		String u = ComposedModules.putDownWords(unknown, orders());
		assertTrue(u.contains("Steward's mistake") && u.contains("won't ask again"), u);
		for (String region : new String[] {"", null, "something_else"}) {
			var r = ComposedModules.CapRefusal.of(region);
			assertEquals(ComposedModules.CapRefusal.Scope.REGION, r.scope());
			assertEquals("Not placed yet: the materials needed went past what you approved. Nothing was taken from the stockpile; I'll ask you again.",
				ComposedModules.putDownWords(r, orders()), "the region's cap keeps its words");
		}
	}
}
