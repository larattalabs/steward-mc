package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.model.Progress;
import dev.larattalabs.steward.module.ModuleOffer.Path;
import dev.larattalabs.steward.module.ModuleOffer.Town;
import dev.larattalabs.steward.service.HallCargo;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** storage_hall@2, the sorting hall (docs/MEASURE-M4.md 12; slice M4b): layout, the item rule, the gate's domains, the bound and the offer. */
class StorageHall2Test {
	static final ModuleCatalog LIVE = StorageHallTest.LIVE;
	static final ModuleGate.Env MEASURING = StorageHallTest.MEASURING;
	static final ModuleKind K = ModuleKind.StorageHall.INSTANCE;

	static ModuleCatalog.Entry e2() {
		return LIVE.get("storage_hall", 2).orElseThrow();
	}

	static JsonObject p(int slots, int bulk, String... items) {
		JsonObject o = new JsonObject();
		o.addProperty("slots", slots);
		o.addProperty("bulk", bulk);
		for (int i = 0; i < items.length; i++) o.addProperty("item" + (i + 1), items[i]);
		return o;
	}

	@Test
	void theSortingLayoutIsTheTilePerSlotWithTypedCells() {
		for (int s : List.of(4, 8, 12, 16)) {
			var l = StorageHallLot.layout(s, 8);
			assertEquals(Math.max(s + 3, 11), l.w());
			assertEquals(13, l.d());
			Set<String> seen = new HashSet<>();
			Map<StorageHallLot.Kind, Integer> n = new HashMap<>();
			for (var c : l.cells()) {
				assertTrue(seen.add(c.at()[0] + "," + c.at()[1] + "," + c.at()[2]), "no cell twice");
				n.merge(c.kind(), 1, Integer::sum);
			}
			assertEquals(1, n.get(StorageHallLot.Kind.INPUT));
			assertEquals(1, n.get(StorageHallLot.Kind.OVERFLOW));
			for (var k : List.of(StorageHallLot.Kind.LINE, StorageHallLot.Kind.FILTER, StorageHallLot.Kind.OUTPUT, StorageHallLot.Kind.SLOT)) assertEquals(s, n.get(k), k.name());
			assertEquals(16, n.get(StorageHallLot.Kind.BULK), "8 double chests: 16 halves");
			for (var c : l.cells()) if (c.kind() == StorageHallLot.Kind.FILTER) assertArrayEquals(new int[] {1 + c.slot(), 3, 6}, c.at(), "F over D, under the line");
		}
	}

	@Test
	void theDomainIsTheProgramsOwnAndTheItemRuleHolds() throws Exception {
		// module.json's item enums are none + StorageHallLot.FILTERABLE, the program's own list
		var mod = JsonParser.parseString(Files.readString(ModuleCatalogTest.FIXTURES.resolve("../../../modules/storage_hall/2/module.json").normalize())).getAsJsonObject();
		for (int i = 1; i <= 16; i++) {
			List<String> opts = new java.util.ArrayList<>();
			mod.getAsJsonObject("params").getAsJsonObject("item" + i).getAsJsonArray("options").forEach(x -> opts.add(x.getAsString()));
			assertEquals("none", opts.get(0));
			assertEquals(StorageHallLot.FILTERABLE, opts.subList(1, opts.size()), "item" + i);
		}
		assertTrue(StorageHallLot.items(p(4, 2, "cobblestone", "iron_ingot", "wheat", "oak_log")).ok());
		assertEquals(List.of("minecraft:cobblestone", "minecraft:iron_ingot", "minecraft:wheat", "minecraft:oak_log"), StorageHallLot.items(p(4, 2, "cobblestone", "iron_ingot", "wheat", "oak_log")).items());
		assertTrue(StorageHallLot.items(p(4, 2, "cobblestone", "iron_ingot", "cobblestone", "oak_log")).refused().contains("repeats item1"));
		assertTrue(StorageHallLot.items(p(4, 2, "cobblestone", "iron_ingot", "wheat")).refused().contains("item4 must be a filter item"));
		assertTrue(StorageHallLot.items(p(4, 2, "cobblestone", "iron_ingot", "wheat", "oak_log", "coal")).refused().contains("item5 must be none"));
		assertTrue(StorageHallLot.items(p(4, 2, "cobblestone", "iron_ingot", "wheat", "diamond_sword")).refused().contains("must be a filter item"));
		assertTrue(StorageHallLot.items(p(0, 2)).ok(), "the plain hall: every item none");
	}

	@Test
	void theGateTakesDomainItemsUnderTheRuleAndNothingElse() {
		var e = e2();
		assertEquals(16, e.envelope().domains().size());
		var ok = ModuleGate.params(e, p(4, 2, "cobblestone", "iron_ingot", "wheat", "oak_log"));
		assertTrue(ok.ok(), ok.refused());
		assertEquals("oak_log", ok.values().get("item4").getAsString());
		assertEquals("none", ok.values().get("item5").getAsString());
		assertFalse(ModuleGate.params(e, p(4, 2, "cobblestone", "cobblestone", "wheat", "oak_log")).ok(), "a repeat");
		assertFalse(ModuleGate.params(e, p(4, 2, "cobblestone", "iron_ingot", "wheat")).ok(), "a slot with no item");
		assertFalse(ModuleGate.params(e, p(5, 2, "cobblestone", "iron_ingot", "wheat", "oak_log", "coal")).ok(), "an unmeasured slot count");
		assertFalse(ModuleGate.params(e, p(4, 2, "cobblestone", "iron_ingot", "wheat", "oak_log", "coal")).ok(), "an item past the slots");
		var plain = ModuleGate.params(e, p(0, 3));
		assertTrue(plain.ok(), plain.refused());
	}

	@Test
	void theBoundIsTheStructurePlus41PerItem() {
		JsonObject raw = JsonParser.parseString("{\"fill\":{\"params\":[\"item1\",\"item2\",\"item3\",\"item4\",\"item5\",\"item6\",\"item7\",\"item8\",\"item9\",\"item10\",\"item11\",\"item12\",\"item13\",\"item14\",\"item15\",\"item16\"],\"count\":41},"
			+ "\"bounds\":[{\"params\":{\"bulk\":2,\"slots\":4},\"bom\":{\"minecraft:stone\":300,\"minecraft:hopper\":12,\"minecraft:redstone\":12}}]}").getAsJsonObject();
		var m = new ModuleCatalog.Measured("", "", "", "", List.of(), null, null, false, ModuleCatalog.BASIS_FULL, null, raw);
		var b = ModuleBound.of(m, p(4, 2, "stone", "redstone", "wheat", "oak_log"));
		assertNotNull(b);
		assertEquals(341, b.get("minecraft:stone").getAsInt(), "the structure's stone and the filter's 41 share an item");
		assertEquals(53, b.get("minecraft:redstone").getAsInt());
		assertEquals(41, b.get("minecraft:wheat").getAsInt());
		assertEquals(12, b.get("minecraft:hopper").getAsInt());
		assertFalse(b.has(StorageHallLot.FILLER), "the fillers are provided: never billed");
		assertNull(ModuleBound.of(m, p(8, 2, "stone", "redstone", "wheat", "oak_log", "coal", "flint", "bone", "string")), "no structure bound measured for 8 slots");
		JsonObject broken = raw.deepCopy();
		broken.getAsJsonObject("fill").addProperty("count", 0);
		assertNull(ModuleBound.of(new ModuleCatalog.Measured("", "", "", "", List.of(), null, null, false, ModuleCatalog.BASIS_FULL, null, broken), p(4, 2, "stone", "redstone", "wheat", "oak_log")), "an unreadable fill: no bound");
		assertEquals(Map.of("minecraft:stone", 41, "minecraft:wheat", 41, "minecraft:redstone", 41, "minecraft:oak_log", 41), StorageHallLot.fillBill(p(4, 2, "stone", "redstone", "wheat", "oak_log")));
	}

	static Map<String, Integer> held(Object... kv) {
		Map<String, Integer> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put("minecraft:" + kv[i], (Integer) kv[i + 1]);
		for (int i = 0; i < 12; i++) m.put("minecraft:junk_" + i, 1);
		return m;
	}

	@Test
	void fromTheNetherTheOfferSortsTheMostHeldFilterKinds() {
		var v = ModuleGate.offer(LIVE, K.id(), MEASURING);
		assertTrue(v.ok(), v.message());
		var stores = held("cobblestone", 900, "iron_ingot", 300, "wheat", 200, "oak_log", 150, "coal", 140, "diamond_sword", 1);
		var nether = new Town(Path.REGION, null, true, 100, false, false, stores, Map.of(), Progress.Tier.NETHER);
		var d = ModuleOffer.decide(K, v, nether, null, (e, pp) -> JsonParser.parseString("{\"minecraft:stone\":1}").getAsJsonObject());
		assertTrue(d.ok(), d.why());
		var o = d.order().params();
		assertEquals(4, o.get("slots").getAsInt(), "5 filter kinds held: the 4-slot hall");
		assertEquals("cobblestone", o.get("item1").getAsString(), "most held first");
		assertEquals("oak_log", o.get("item4").getAsString());
		assertEquals("none", o.get("item5").getAsString());
		String why = ModuleOffer.why(K, d, false, false);
		assertTrue(why.contains("4 slots for cobblestone, iron ingot, wheat, oak log"), why);
		assertTrue(why.contains("Never put petrified oak slabs in it"), why);
		// before the Nether: the plain hall
		var iron = new Town(Path.REGION, null, true, 100, false, false, stores, Map.of(), Progress.Tier.IRON);
		assertEquals(0, ModuleOffer.decide(K, v, iron, null, (e, pp) -> null).order().params().get("slots").getAsInt());
		// three filter kinds: the plain hall
		var few = new Town(Path.REGION, null, true, 100, false, false, held("cobblestone", 900, "iron_ingot", 300, "wheat", 200), Map.of(), Progress.Tier.END);
		assertEquals(0, ModuleOffer.decide(K, v, few, null, (e, pp) -> null).order().params().get("slots").getAsInt());
		// items named in talk are kept when they keep the rule
		var asked = p(4, 2, "redstone", "quartz", "string", "bone");
		var da = ModuleOffer.decide(K, v, nether, asked, (e, pp) -> null);
		assertEquals("quartz", da.order().params().get("item2").getAsString());
	}

	@Test
	void aFilterHopperIsOursOnlyHoldingItsDeclaration() {
		String f = "minecraft:cobblestone", slab = StorageHallLot.FILLER;
		assertTrue(HallCargo.filterOurs(List.of(new HallCargo.Stack(f, 41, true), new HallCargo.Stack(slab, 1, true), new HallCargo.Stack(slab, 1, true),
			new HallCargo.Stack(slab, 1, true), new HallCargo.Stack(slab, 1, true)), f), "exactly its fill");
		assertTrue(HallCargo.filterOurs(List.of(new HallCargo.Stack(f, 33, true), new HallCargo.Stack(slab, 4, true)), f), "less: some taken");
		assertTrue(HallCargo.filterOurs(List.of(), f), "empty");
		assertFalse(HallCargo.filterOurs(List.of(new HallCargo.Stack(f, 42, true)), f), "one more of its own item is cargo");
		assertFalse(HallCargo.filterOurs(List.of(new HallCargo.Stack(f, 40, true), new HallCargo.Stack(f, 2, true)), f), "the limit is the total, across slots");
		assertFalse(HallCargo.filterOurs(List.of(new HallCargo.Stack(slab, 5, true)), f), "a fifth slab is cargo");
		assertFalse(HallCargo.filterOurs(List.of(new HallCargo.Stack(f, 41, false)), f), "a renamed stack is cargo");
		assertFalse(HallCargo.filterOurs(List.of(new HallCargo.Stack("minecraft:dirt", 1, true)), f), "another item is cargo");
		// the words count only what is past the declaration
		assertEquals(1, HallCargo.filterExcess(List.of(new HallCargo.Stack(f, 42, true), new HallCargo.Stack(slab, 4, true)), f));
		assertEquals(1, HallCargo.filterExcess(List.of(new HallCargo.Stack("minecraft:dirt", 1, true), new HallCargo.Stack(slab, 4, true)), f));
		assertEquals(41, HallCargo.filterExcess(List.of(new HallCargo.Stack(f, 41, false), new HallCargo.Stack(slab, 4, true)), f), "a renamed stack is all cargo");
		assertEquals(1, HallCargo.filterExcess(List.of(new HallCargo.Stack(f, 41, true), new HallCargo.Stack(slab, 5, true)), f));
		assertEquals(0, HallCargo.filterExcess(List.of(new HallCargo.Stack(f, 30, true)), f));
	}
}
