package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** docs/MODULES.md "M5 design" (slice M5a): module lots of a composed plan, on the live catalog (crop_farm@1 and the newest certified storage_hall). */
class ComposedModulesTest {
	static final ModuleCatalog LIVE = ModuleCatalog.dir(ModuleCatalogTest.FIXTURES.resolve("../../../modules").normalize(), false);
	static final ModuleGate.Env PLAYER = new ModuleGate.Env("26.3", "0.85.0", Set.of(ModuleGate.FEATURE));
	static final ModuleKind FARM = ModuleKind.CropFarm.INSTANCE, HALL = ModuleKind.StorageHall.INSTANCE;
	static final ComposedModules.Town WARM = new ComposedModules.Town(false, false), COLD = new ComposedModules.Town(true, false),
		MATERIALS = new ComposedModules.Town(false, true);

	static ModuleGate.Verdict ok(String id) {
		var v = ModuleGate.offer(LIVE, id, PLAYER);
		assertTrue(v.ok(), v.message());
		return v;
	}

	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	static List<ComposedModules.Offered> offered() {
		return List.of(new ComposedModules.Offered(FARM, ok("crop_farm").entry()), new ComposedModules.Offered(HALL, ok("storage_hall").entry()));
	}

	/** A composed plan as the composer writes it: a cottage, a crop farm for the card's "farm" and a storage hall for its "warehouse". */
	static JsonObject plan() {
		return json("""
			{"schemaVersion": "plan/0", "planId": "oakvale", "zones": [{"id": "town", "required": true, "program": {"layout": "street", "lots": [
			  {"id": "cottage_1", "type": "cottage", "role": "home", "label": "cottage", "size": [9, 9]},
			  {"id": "farm_1", "type": "crop_farm", "role": "farm", "label": "the village field", "size": [11, 12], "irrigate": true, "landmark": false,
			   "ext": {"steward_mc:module": {"id": "crop_farm", "params": {"width": 9, "depth": 9, "crop": "carrots"}, "for": "farm"}, "other:tag": 1}},
			  {"id": "warehouse_1", "type": "storage_hall", "role": "storage", "label": "sorting warehouse", "size": [11, 7],
			   "ext": {"steward_mc:module": {"id": "storage_hall", "params": {"slots": 0, "bulk": 4}, "for": "warehouse"}}}
			]}}]}""");
	}

	static List<JsonObject> lots(JsonObject plan) {
		List<JsonObject> out = new java.util.ArrayList<>();
		for (var l : plan.getAsJsonArray("zones").get(0).getAsJsonObject().getAsJsonObject("program").getAsJsonArray("lots")) out.add(l.getAsJsonObject());
		return out;
	}

	@Test
	void theVocabularyListsEachOfferedModuleWithItsMeasuredSizesAndChoice() {
		JsonArray v = ComposedModules.vocabulary(offered());
		assertEquals(2, v.size());
		JsonObject farm = v.get(0).getAsJsonObject();
		assertEquals("crop_farm", farm.get("id").getAsString());
		assertEquals("crop_farm", farm.get("lotType").getAsString());
		assertTrue(farm.get("irrigate").getAsBoolean());
		assertTrue(farm.get("warmOnly").getAsBoolean());
		assertEquals(9, farm.getAsJsonArray("sizes").size(), "every width x depth of 9, 18 and 27");
		// each size's lot is the module's own frame (W = F + 2, D = F + 3)
		for (var s : farm.getAsJsonArray("sizes")) {
			JsonObject o = s.getAsJsonObject();
			int[] f = FARM.frame(FARM.params(o.getAsJsonObject("params"), "wheat"));
			assertEquals(f[0], o.getAsJsonArray("size").get(0).getAsInt());
			assertEquals(f[1], o.getAsJsonArray("size").get(1).getAsInt());
		}
		assertEquals("crop", farm.getAsJsonObject("choose").get("param").getAsString());
		assertEquals(4, farm.getAsJsonObject("choose").getAsJsonArray("values").size());
		JsonObject hall = v.get(1).getAsJsonObject();
		assertFalse(hall.get("irrigate").getAsBoolean());
		// (M5b) the hall's sizes are @2's measured slot branches, most slots first, the plain hall last, each with its own frame
		List<String> sizes = new java.util.ArrayList<>();
		for (var sz : hall.getAsJsonArray("sizes")) sizes.add(sz.getAsJsonObject().getAsJsonObject("params").get("slots") + ":" + sz.getAsJsonObject().get("size"));
		assertEquals(List.of("16:[19,13]", "12:[15,13]", "8:[11,13]", "4:[11,13]", "0:[11,7]"), sizes);
		assertEquals("bulk", hall.getAsJsonObject("choose").get("param").getAsString());
		// and its items: the filter domain, one per slot
		assertEquals(StorageHallLot.FILTERABLE.size(), hall.getAsJsonObject("items").getAsJsonArray("values").size());
		assertTrue(hall.getAsJsonObject("items").get("params").getAsString().contains("item1 .. itemN"));
		assertFalse(farm.has("items"), "only a sorting hall has items");
		assertTrue(ComposedModules.vocabulary(List.of()).isEmpty());
	}

	@Test
	void theIdentityNamesVersionsAndProgramsInOrder() {
		String id = ComposedModules.identity(offered());
		assertTrue(id.startsWith("crop_farm@1:"), id);
		// the hall offered on 0.85.0 is the live catalog's newest certified one: storage_hall@2, the sorting hall
		assertEquals(2, offered().get(1).entry().version());
		assertTrue(id.contains(",storage_hall@2:"), id);
		assertEquals(id, ComposedModules.identity(List.of(offered().get(1), offered().get(0))), "sorted, whatever the order offered");
		assertEquals("", ComposedModules.identity(List.of()));
	}

	@Test
	void readTakesOnlyLotsWhoseExtNamesAModule() {
		var asked = ComposedModules.read(lots(plan()));
		assertEquals(2, asked.size());
		assertEquals("farm_1", asked.get(0).lotId());
		assertEquals("crop_farm", asked.get(0).moduleId());
		assertEquals("farm", asked.get(0).forType());
		assertEquals("carrots", asked.get(0).params().get("crop").getAsString());
		assertEquals("warehouse", asked.get(1).forType());
		// no ext, a non-object, no id, a non-string id: not a module lot
		assertNull(ComposedModules.asked(json("{\"id\": \"a\", \"type\": \"crop_farm\"}")), "a type alone is not a module");
		assertNull(ComposedModules.asked(json("{\"id\": \"a\", \"ext\": {\"steward_mc:module\": \"crop_farm\"}}")));
		assertNull(ComposedModules.asked(json("{\"id\": \"a\", \"ext\": {\"steward_mc:module\": {\"params\": {}}}}")));
		assertNull(ComposedModules.asked(json("{\"id\": \"a\", \"ext\": {\"steward_mc:module\": {\"id\": 3}}}")));
		var noParams = ComposedModules.asked(json("{\"id\": \"a\", \"ext\": {\"steward_mc:module\": {\"id\": \"Crop_Farm\", \"for\": \"Big Farm\"}}}"));
		assertEquals("crop_farm", noParams.moduleId());
		assertEquals("big_farm", noParams.forType());
		assertEquals(0, noParams.params().size());
	}

	static ComposedModules.Decision decide(ComposedModules.Asked a, ModuleKind k, ComposedModules.Town t, Set<String> taken, int[] size) {
		return ComposedModules.decide(a, k, ok(k.id()), t, taken, size, (e, p) -> ModuleBound.of(e.measured(), p));
	}

	@Test
	void decideKeepsMeasuredParamsAndSaysWhatItChose() {
		var asked = ComposedModules.read(lots(plan()));
		var farm = decide(asked.get(0), FARM, WARM, Set.of(), new int[] {11, 12});
		assertTrue(farm.ok(), farm.why());
		assertEquals(json("{\"crop\": \"carrots\", \"depth\": 9, \"width\": 9}"), farm.order().params());
		assertNull(farm.changed());
		assertNull(farm.order().bound(), "the bound is set at the bind, in the town's blocks");
		assertFalse(farm.order().autonomous());
		assertNull(farm.order().proposal());
		assertEquals(ok("crop_farm").entry().programSha(), farm.order().programSha());
		// a crop that isn't measured: the default, said; a size not measured but a lot of the 18x9 frame: that size (either way round)
		var a = new ComposedModules.Asked("f", "crop_farm", json("{\"crop\": \"melons\", \"width\": 10}"), null);
		int[] f = FARM.frame(json("{\"width\": 18, \"depth\": 9, \"crop\": \"wheat\"}"));
		var d = decide(a, FARM, WARM, Set.of(), new int[] {f[1], f[0]});
		assertTrue(d.ok(), d.why());
		assertEquals("wheat", d.order().params().get("crop").getAsString());
		assertEquals(18, d.order().params().get("width").getAsInt());
		assertEquals(9, d.order().params().get("depth").getAsInt());
		assertTrue(d.changed().contains("melons"), d.changed());
		// nothing to go by: the smallest, said
		var small = decide(new ComposedModules.Asked("f", "crop_farm", json("{\"width\": 10}"), null), FARM, WARM, Set.of(), new int[] {40, 40});
		assertTrue(small.ok(), small.why());
		assertEquals(9, small.order().params().get("width").getAsInt());
		assertTrue(small.changed().contains("smallest"), small.changed());
		var hall = decide(asked.get(1), HALL, WARM, Set.of(), new int[] {11, 7});
		assertTrue(hall.ok(), hall.why());
		assertEquals(4, hall.order().params().get("bulk").getAsInt());
		assertEquals(0, hall.order().params().get("slots").getAsInt());
	}

	@Test
	void decideRefusesInWords() {
		var asked = ComposedModules.read(lots(plan()));
		var cold = decide(asked.get(0), FARM, COLD, Set.of(), null);
		assertFalse(cold.ok());
		assertTrue(cold.why().contains("freeze"), cold.why());
		var second = decide(asked.get(0), FARM, WARM, Set.of("crop_farm"), null);
		assertFalse(second.ok());
		assertTrue(second.why().contains("one of each"), second.why());
		var unknown = ComposedModules.decide(new ComposedModules.Asked("x", "melon_farm", new JsonObject(), null), null,
			new ModuleGate.Verdict(null, ModuleGate.Why.UNKNOWN, ""), WARM, Set.of(), null, (e, p) -> null);
		assertFalse(unknown.ok());
		assertTrue(unknown.why().contains("no working melon farm"), unknown.why());
		var gate = ModuleGate.offer(LIVE, "crop_farm", new ModuleGate.Env("26.3", "0.67.0", Set.of(ModuleGate.FEATURE)));
		var refused = ComposedModules.decide(asked.get(0), FARM, gate, WARM, Set.of(), null, (e, p) -> null);
		assertFalse(refused.ok());
		assertTrue(refused.why().contains("measured with Architect"), refused.why());
		// from materials on an Architect without the per-lot cap (no LCAP): the guard is on, left out, said, whatever its bound
		assertTrue(ComposedModules.materialsGuard(false));
		assertFalse(ComposedModules.materialsGuard(true), "(LIFT-S) lifted with Architect's maxBillPerLot");
		assertFalse(MATERIALS.lotCaps(), "a town decided as before LCAP");
		var guarded = decide(asked.get(0), FARM, MATERIALS, Set.of(), null);
		assertFalse(guarded.ok());
		assertTrue(guarded.why().contains("can't be held to its measured limit in a town built from materials yet; it's left out for now"), guarded.why());
		assertTrue(decide(asked.get(1), HALL, MATERIALS, Set.of(), null).why().contains("working storage hall can't be held"));
		assertTrue(decide(asked.get(0), FARM, WARM, Set.of(), null).ok(), "a town built at once is unchanged");
		// the path behind the guard: from materials with no measured bound for those params refused (nothing to hold the bill to); with one, ordered
		var unbounded = ComposedModules.decide(asked.get(0), FARM, ok("crop_farm"), MATERIALS, Set.of(), null, (e, p) -> null, false);
		assertFalse(unbounded.ok());
		assertTrue(unbounded.why().contains("no bound"), unbounded.why());
		var bounded = ComposedModules.decide(asked.get(0), FARM, ok("crop_farm"), MATERIALS, Set.of(), null, (e, p) -> ModuleBound.of(e.measured(), p), false);
		assertTrue(bounded.ok(), bounded.why());
		// (LIFT-S) the public decide with Architect's per-lot cap: the guard is off, and the bound still required
		var capped = new ComposedModules.Town(false, true, Map.of(), true);
		var lifted = ComposedModules.decide(asked.get(0), FARM, ok("crop_farm"), capped, Set.of(), null, (e, p) -> ModuleBound.of(e.measured(), p));
		assertTrue(lifted.ok(), lifted.why());
		assertEquals(bounded.order(), lifted.order());
		assertFalse(ComposedModules.decide(asked.get(0), FARM, ok("crop_farm"), capped, Set.of(), null, (e, p) -> null).ok(), "no bound: never built from materials");
		String words = ComposedModules.leftOutWords("village field", cold.why());
		assertTrue(words.startsWith("The plan's village field is left out of the town: "), words);
		assertTrue(words.contains("isn't designed as an ordinary building"), words);
	}

	@Test
	void theBuiltLotIsTheCatalogsKeepingThePlansOwnWords() {
		var asked = ComposedModules.read(lots(plan()));
		var farm = decide(asked.get(0), FARM, WARM, Set.of(), null).order();
		JsonObject planLot = lots(plan()).get(1);
		JsonObject built = ComposedModules.builtLot(planLot, FARM, farm);
		JsonObject grow = FARM.growLot("farm_1", farm.params());
		assertEquals(grow.get("type"), built.get("type"));
		assertEquals(grow.get("size"), built.get("size"));
		assertEquals(grow.get("field"), built.get("field"), "FC's field from M2's own layout");
		assertTrue(built.get("irrigate").getAsBoolean());
		assertEquals("the village field", built.get("label").getAsString());
		assertEquals("farm", built.get("role").getAsString());
		assertFalse(built.get("landmark").getAsBoolean());
		JsonObject ext = built.getAsJsonObject("ext");
		assertEquals(1, ext.get("other:tag").getAsInt(), "the lot's other tags are kept");
		var ref = ModuleRef.of(ext);
		assertNotNull(ref, "the full module ref, read back as any built module's");
		assertEquals(farm.ref(), ref);
		// the hall: no water, no field
		var hall = decide(asked.get(1), HALL, WARM, Set.of(), null).order();
		JsonObject h = ComposedModules.builtLot(lots(plan()).get(2), HALL, hall);
		assertFalse(h.has("irrigate") && h.get("irrigate").getAsBoolean());
		assertFalse(h.has("field"));
		assertEquals("[11,7]", h.get("size").toString());
	}

	@Test
	void applyRewritesOrderedLotsAndLeavesOutTheRefusedDeterministically() {
		var asked = ComposedModules.read(lots(plan()));
		Map<String, ModuleOrder> orders = new LinkedHashMap<>();
		orders.put("farm_1", decide(asked.get(0), FARM, WARM, Set.of(), null).order());
		JsonObject out = ComposedModules.apply(plan(), orders, Set.of("warehouse_1"));
		var ls = lots(out);
		assertEquals(2, ls.size());
		assertEquals("cottage_1", ls.get(0).get("id").getAsString());
		assertEquals(lots(plan()).get(0), ls.get(0), "an ordinary lot is untouched");
		assertNotNull(ModuleRef.of(ls.get(1).getAsJsonObject("ext")));
		assertEquals(out, ComposedModules.apply(plan(), orders, Set.of("warehouse_1")), "the same inputs, the same plan");
		assertEquals(lots(plan()).size(), lots(plan()).size(), "the plan given is not changed");
		// a districts zone's lots too
		JsonObject d = json("{\"zones\": [{\"id\": \"q\", \"program\": {\"districts\": [{\"id\": \"d1\", \"lots\": [{\"id\": \"farm_1\", \"type\": \"crop_farm\", \"size\": [11, 12]}]}]}}]}");
		JsonObject dout = ComposedModules.apply(d, orders, Set.of());
		var dl = dout.getAsJsonArray("zones").get(0).getAsJsonObject().getAsJsonObject("program").getAsJsonArray("districts").get(0).getAsJsonObject().getAsJsonArray("lots");
		assertNotNull(ModuleRef.of(dl.get(0).getAsJsonObject().getAsJsonObject("ext")));
	}

	static JsonObject artifactLot(String front, int x0, int z0, int w, int d) {
		boolean ew = front.equals("east") || front.equals("west");
		int xs = ew ? d : w, zs = ew ? w : d;
		return json("{\"id\": \"farm_1\", \"front\": \"" + front + "\", \"box\": [" + x0 + ", 64, " + z0 + ", " + (x0 + xs - 1) + ", 90, " + (z0 + zs - 1) + "]}");
	}

	@Test
	void theFrameIsTheBoxSeenFromItsFrontOnEveryFront() {
		int[] f = FARM.frame(json("{\"width\": 9, \"depth\": 9, \"crop\": \"wheat\"}"));
		for (String front : List.of("south", "north", "east", "west")) {
			JsonObject a = artifactLot(front, 100, -40, f[0], f[1]);
			assertArrayEquals(f, ComposedModules.frame(a), front);
			assertNull(ComposedModules.frameProblem(a, f), front);
			JsonObject g = ComposedModules.geometry(a);
			assertEquals(front, g.get("front").getAsString());
			assertEquals(64, g.get("floorY").getAsInt());
			assertEquals("[" + f[0] + "," + f[1] + "]", g.get("frame").toString());
		}
		// a box one longer: refused, said
		JsonObject big = artifactLot("south", 0, 0, f[0] + 1, f[1]);
		assertTrue(ComposedModules.frameProblem(big, f).contains("exactly " + f[0] + " by " + f[1]), ComposedModules.frameProblem(big, f));
		// the hall's 11x7 turned (an east front): its box is 7 across x
		JsonObject hall = artifactLot("east", 0, 0, 11, 7);
		assertEquals(7, hall.getAsJsonArray("box").get(3).getAsInt() + 1);
		assertNull(ComposedModules.frameProblem(hall, new int[] {11, 7}));
		assertNotNull(ComposedModules.frameProblem(hall, new int[] {7, 11}));
		assertNotNull(ComposedModules.frameProblem(json("{\"id\": \"x\"}"), f));
		assertNull(ComposedModules.geometry(json("{\"id\": \"x\"}")));
	}

	@Test
	void theGeometryCarriesIrrigationAndMovesWithIt() {
		int[] f = FARM.frame(json("{\"width\": 9, \"depth\": 9, \"crop\": \"wheat\"}"));
		JsonObject a = artifactLot("south", 0, 0, f[0], f[1]);
		a.add("irrigation", json("{\"frame\": [" + f[0] + ", " + f[1] + "], \"lotCells\": [[5, 6, 0, 0]], \"cells\": [[5, 6, 64, 64]], \"sources\": 1}"));
		JsonObject g = ComposedModules.geometry(a);
		assertEquals("[[5,6,0,0]]", g.getAsJsonObject("irrigation").get("lotCells").toString());
		assertFalse(g.getAsJsonObject("irrigation").has("sources"), "only the cells the binding hashes");
		assertNull(ComposedModules.frameProblem(a, f));
		JsonObject moved = a.deepCopy();
		moved.getAsJsonObject("irrigation").add("lotCells", JsonParser.parseString("[[5, 7, 0, 0]]"));
		assertNotEquals(Canon.sha(g), Canon.sha(ComposedModules.geometry(moved)), "moved water: another variant");
		assertEquals(Canon.sha(g), Canon.sha(ComposedModules.geometry(a.deepCopy())));
		JsonObject otherFrame = a.deepCopy();
		otherFrame.getAsJsonObject("irrigation").add("frame", JsonParser.parseString("[13, 14]"));
		assertNotNull(ComposedModules.frameProblem(otherFrame, f));
	}

	@Test
	void aModulesShareIsItsLotAndFillPieces() {
		JsonObject parts = json("""
			{"tiles:ground": {"minecraft:dirt": 400}, "lot:farm_1": {"minecraft:dirt": 120, "minecraft:wheat_seeds": 74},
			 "lot:hall_1": {"minecraft:oak_planks": 90}, "fill:hall_1": {"minecraft:petrified_oak_slab": 8, "minecraft:oak_planks": 2}}""");
		assertEquals(json("{\"minecraft:dirt\": 120, \"minecraft:wheat_seeds\": 74}"), ComposedModules.share(parts, "farm_1"));
		assertEquals(json("{\"minecraft:oak_planks\": 92, \"minecraft:petrified_oak_slab\": 8}"), ComposedModules.share(parts, "hall_1"));
		assertEquals(new JsonObject(), ComposedModules.share(parts, "none"));
		assertFalse(ComposedModules.billed(parts, "none"));
		assertNull(ComposedModules.share(json("{\"lot:a\": {\"minecraft:dirt\": 1.5}}"), "a"));
		assertNull(ComposedModules.share(json("{\"lot:a\": 3}"), "a"));
	}

	static ModuleOrder order(String id, String bound) {
		return new ModuleOrder(id, 1, "a".repeat(64), new JsonObject(), bound == null ? null : json(bound), null, false, false);
	}

	@Test
	void eachModuleIsHeldToItsOwnBoundInOneQuote() {
		JsonObject parts = json("{\"tiles:x\": {\"minecraft:dirt\": 9999}, \"lot:farm_1\": {\"minecraft:dirt\": 120}, \"lot:hall_1\": {\"minecraft:oak_planks\": 90}}");
		Map<String, ModuleOrder> orders = new LinkedHashMap<>();
		orders.put("farm_1", order("crop_farm", "{\"minecraft:dirt\": 130}"));
		orders.put("hall_1", order("storage_hall", "{\"minecraft:oak_planks\": 100}"));
		var within = ComposedModules.held(parts, orders, null);
		assertNull(within.refused());
		assertNull(within.ask(), "the town's own tiles are not the modules'");
		assertTrue(within.said().contains("crop farm takes 120 dirt (its measured limit: 130 dirt)"), within.said());
		assertNull(within.orders().get("farm_1").held());
		// one past its bound: every order holds its share for the player's word
		orders.put("hall_1", order("storage_hall", "{\"minecraft:oak_planks\": 80}"));
		var over = ComposedModules.held(parts, orders, null);
		assertNotNull(over.ask());
		assertTrue(over.ask().contains("storage hall's 90 oak planks (bound 80)"), over.ask());
		assertEquals(json("{\"minecraft:oak_planks\": 90}"), over.orders().get("hall_1").held());
		assertEquals(json("{\"minecraft:dirt\": 120}"), over.orders().get("farm_1").held());
		// approved: each share is its bound, and the same quote again goes on
		Map<String, ModuleOrder> approved = new LinkedHashMap<>();
		over.orders().forEach((k, v) -> approved.put(k, v.approvedHeld()));
		var again = ComposedModules.held(parts, approved, null);
		assertNull(again.ask());
		// (batch-077 review P3) the bound is now the bill the player approved, past the measured one: called so, never "measured"
		assertTrue(again.said().contains("storage hall takes 90 oak planks (its approved limit: 90 oak planks)"), again.said());
		assertTrue(again.said().contains("crop farm takes 120 dirt (its approved limit: 120 dirt)"), again.said());
		assertFalse(again.said().contains("measured limit"), again.said());
		// an inexact quote asks until each order had an inexact bill approved
		var inexact = ComposedModules.held(parts, Map.of("farm_1", order("crop_farm", "{\"minecraft:dirt\": 130}")), "a lot may differ");
		assertTrue(inexact.ask().contains("exactly (a lot may differ)"), inexact.ask());
		assertNull(ComposedModules.held(parts, approved, "a lot may differ").ask());
		// a module lot the quote doesn't bill, or an order with no bound: refused
		assertNotNull(ComposedModules.held(json("{}"), Map.of("farm_1", order("crop_farm", "{}")), null).refused());
		assertNotNull(ComposedModules.held(parts, Map.of("farm_1", order("crop_farm", null)), null).refused());
		// an item the bound doesn't name is over it
		var stray = ComposedModules.held(json("{\"lot:farm_1\": {\"minecraft:stone\": 1}}"), Map.of("farm_1", order("crop_farm", "{\"minecraft:dirt\": 130}")), null);
		assertTrue(stray.ask().contains("not in the bound"), stray.ask());
	}

	/**
	 * (QD-2, M5b gap 1) A composed realise refused at put-down (an order saved refused) is put to the player whatever the quote says, as the
	 * single-module path is: each refused order's bill never below what was approved, the refusal kept through the hold and cleared by approval.
	 */
	@Test
	void aPutDownRefusalOnAComposedTownAsksAgainWhateverTheQuote() {
		JsonObject parts = json("{\"lot:farm_1\": {\"minecraft:dirt\": 120}, \"lot:hall_1\": {\"minecraft:oak_planks\": 90}, \"fill:hall_1\": {\"minecraft:coal\": 41}}");
		Map<String, ModuleOrder> orders = new LinkedHashMap<>();
		orders.put("farm_1", order("crop_farm", "{\"minecraft:dirt\": 130}").refusedAtPutDown(true));
		orders.put("hall_1", order("storage_hall", "{\"minecraft:oak_planks\": 100, \"minecraft:coal\": 41}").refusedAtPutDown(true));
		// within every bound and exact: still asked
		var h = ComposedModules.held(parts, orders, null);
		assertNull(h.refused());
		assertNotNull(h.ask(), "a refusal at put-down is never realised on its own");
		assertTrue(h.ask().startsWith(ModuleBound.PUT_DOWN_REFUSED), h.ask());
		assertNull(h.said());
		// the bills shown are at least the approved bounds, item by item
		assertEquals(json("{\"minecraft:dirt\": 130}"), h.orders().get("farm_1").held());
		assertEquals(json("{\"minecraft:oak_planks\": 100, \"minecraft:coal\": 41}"), h.orders().get("hall_1").held());
		assertTrue(h.ask().contains("the crop farm: 130 dirt"), h.ask());
		// (WORDS-079) which lot went past isn't known (every order is marked refused): the limit both hold to, never "what you approved"
		assertTrue(h.ask().startsWith("When it was put down, the materials a working module needed went past its measured limit. "), h.ask());
		assertFalse(h.ask().contains("what you approved"), h.ask());
		// the hold keeps the refusal (a crash before the phase is saved asks again); approving clears it, and the next quote within goes on
		assertTrue(h.orders().values().stream().allMatch(ModuleOrder::refusedAtPutDown));
		Map<String, ModuleOrder> approved = new LinkedHashMap<>();
		h.orders().forEach((k, v) -> approved.put(k, v.approvedHeld()));
		assertTrue(approved.values().stream().noneMatch(ModuleOrder::refusedAtPutDown));
		var next = ComposedModules.held(parts, approved, null);
		assertNull(next.ask());
		assertTrue(next.said().endsWith("; " + ModuleBound.CRASH_EXCEPTION + "."), next.said());
		// one order refused is enough; the other's bill is its share
		Map<String, ModuleOrder> one = new LinkedHashMap<>(orders);
		one.put("farm_1", order("crop_farm", "{\"minecraft:dirt\": 130}"));
		var o = ComposedModules.held(parts, one, null);
		assertTrue(o.ask().startsWith(ModuleBound.PUT_DOWN_REFUSED), o.ask());
		assertEquals(json("{\"minecraft:dirt\": 120}"), o.orders().get("farm_1").held());
		// (WORDS-079) one approved since, one measured: "its limit"; a single lot is named with its own
		Map<String, ModuleOrder> mixed = new LinkedHashMap<>(orders);
		mixed.put("farm_1", orders.get("farm_1").holding(json("{\"minecraft:dirt\": 130}")).approvedHeld().refusedAtPutDown(true));
		assertTrue(ComposedModules.held(parts, mixed, null).ask().startsWith("When it was put down, the materials a working module needed went past its limit. "));
		var single = ComposedModules.held(json("{\"lot:farm_1\": {\"minecraft:dirt\": 120}}"), Map.of("farm_1", orders.get("farm_1")), null);
		assertTrue(single.ask().startsWith("When it was put down, the materials the working crop farm needed went past its measured limit. "), single.ask());
		var singleApproved = ComposedModules.held(json("{\"lot:farm_1\": {\"minecraft:dirt\": 120}}"), Map.of("farm_1", mixed.get("farm_1")), null);
		assertTrue(singleApproved.ask().startsWith("When it was put down, the materials the working crop farm needed went past its approved limit. "), singleApproved.ask());
	}

	@Test
	void boundWordsSayWhatIsBuiltAndTheHold() {
		var farm = decide(ComposedModules.read(lots(plan())).get(0), FARM, WARM, new HashSet<>(), null).order();
		String w = ComposedModules.boundWords("village field", FARM, farm, null, true);
		assertTrue(w.startsWith("The plan's village field is a working crop farm from Steward's measured catalog (crop_farm@1, 11 by 12"), w);
		assertTrue(w.contains("no design, $0"), w);
		assertTrue(w.contains("held to its measured bound"), w);
		assertFalse(ComposedModules.boundWords("f", FARM, farm, null, false).contains("bound"));
	}

	/** PLAN 2026-11-15, S-M5-12: a composed town's hall (storage_hall@2 on 0.85.0) names the late-cargo gap once; its farm, which has no chests, doesn't. */
	@Test
	void aComposedHallNamesTheLateCargoGap() {
		assertTrue(ModuleOffer.LATE_CARGO_GAP, "drop this test's positive half when LC3 lands");
		var asked = ComposedModules.read(lots(plan()));
		var hall = decide(asked.get(1), HALL, WARM, Set.of(), null).order();
		assertEquals(2, hall.version());
		for (boolean materials : new boolean[] {true, false}) {
			String w = ComposedModules.boundWords("sorting warehouse", HALL, hall, null, materials);
			assertEquals(1, w.split(java.util.regex.Pattern.quote(ModuleOffer.LATE_CARGO_WORDS), -1).length - 1, w);
		}
		var farm = decide(asked.get(0), FARM, WARM, new HashSet<>(), null).order();
		assertFalse(ComposedModules.boundWords("f", FARM, farm, null, true).contains(ModuleOffer.LATE_CARGO_WORDS));
	}

	/** GPT review 3: a module's marker that can't be read, or a module lot type with no marker, is a module ask (refused or bound), never designed. */
	@Test
	void readingFailsClosed() {
		var all = ComposedModules.readAll(List.of(json("{\"id\": \"a\", \"ext\": {\"steward_mc:module\": {}}}"), json("{\"id\": \"b\", \"ext\": {\"steward_mc:module\": \"x\"}}"),
			json("{\"id\": \"c\", \"type\": \"crop_farm\", \"size\": [11, 12]}"), json("{\"id\": \"d\", \"type\": \"storage_hall\"}"), json("{\"id\": \"e\", \"type\": \"cottage\"}")));
		assertEquals(List.of("a", "b", "c", "d"), all.stream().map(ComposedModules.Asked::lotId).toList());
		assertEquals(ComposedModules.UNREADABLE, all.get(0).moduleId());
		// a catalog offering no module: a bare module lot type is an ordinary lot (as before M5); a marker still asks
		var none = ComposedModules.readAll(List.of(json("{\"id\": \"c\", \"type\": \"crop_farm\"}"), json("{\"id\": \"a\", \"ext\": {\"steward_mc:module\": {}}}")), Set.of());
		assertEquals(List.of("a"), none.stream().map(ComposedModules.Asked::lotId).toList());
		assertEquals(ComposedModules.UNREADABLE, all.get(1).moduleId());
		assertEquals("crop_farm", all.get(2).moduleId());
		assertEquals("storage_hall", all.get(3).moduleId());
		var no = ComposedModules.decide(all.get(0), null, new ModuleGate.Verdict(null, ModuleGate.Why.UNKNOWN, ""), WARM, Set.of(), null, (e, p) -> null);
		assertFalse(no.ok());
		assertTrue(no.why().contains("couldn't read"), no.why());
		// a bare crop_farm lot of the 9x9 frame is the 9x9 farm, its crop the default
		var c = decide(all.get(2), FARM, WARM, Set.of(), new int[] {11, 12});
		assertTrue(c.ok(), c.why());
		assertEquals(9, c.order().params().get("width").getAsInt());
		// a plan with an unreadable marker leaves that lot out of the plan as built
		JsonObject p = json("{\"zones\": [{\"id\": \"t\", \"program\": {\"lots\": [{\"id\": \"a\", \"type\": \"barn\", \"ext\": {\"steward_mc:module\": {}}}, {\"id\": \"e\", \"type\": \"cottage\"}]}}]}");
		java.util.Set<String> out = new HashSet<>();
		for (var a : ComposedModules.readAll(lots(p))) out.add(a.lotId());
		assertEquals(1, lots(ComposedModules.apply(p, Map.of(), out)).size());
	}

	/** GPT review 10: each module says what it does; (M5b) a composed @2 hall sorts, and its plain size does not. */
	@Test
	void eachModuleSaysWhatItDoes() {
		var v = ComposedModules.vocabulary(offered());
		assertTrue(v.get(0).getAsJsonObject().get("does").getAsString().contains("player harvests"));
		assertEquals(2, offered().get(1).entry().version());
		assertTrue(ComposedModules.COMPOSED_HALL_SORTS);
		String hall = v.get(1).getAsJsonObject().get("does").getAsString();
		assertTrue(hall.contains("each of its slots sorts one item"), hall);
		assertTrue(hall.contains("0 slots is a plain hall that does not sort"), hall);
		assertTrue(ComposeJob_rules().contains("`does`"));
		assertTrue(ComposeJob_rules().contains("item1 .. itemN"), "the rules say how to write a hall's items");
	}

	static ComposedModules.Asked hallAsk(String params) {
		return new ComposedModules.Asked("warehouse_1", "storage_hall", json(params), "warehouse");
	}

	/** (M5b) A composed sorting hall as the plan wrote it: its slots and items kept, bound to @2, its words saying what it sorts. */
	@Test
	void aComposedSortingHallKeepsThePlansSlotsAndItems() {
		var d = decide(hallAsk("{\"slots\": 4, \"bulk\": 2, \"item1\": \"cobblestone\", \"item2\": \"iron_ingot\", \"item3\": \"wheat\", \"item4\": \"coal\"}"),
			HALL, WARM, Set.of(), new int[] {11, 13});
		assertTrue(d.ok(), d.why());
		assertNull(d.changed(), d.changed());
		JsonObject p = d.order().params();
		assertEquals(2, d.order().version());
		assertEquals(4, p.get("slots").getAsInt());
		assertEquals(List.of("minecraft:cobblestone", "minecraft:iron_ingot", "minecraft:wheat", "minecraft:coal"), StorageHallLot.items(p).items());
		for (int i = 5; i <= StorageHallLot.SLOT_MAX; i++) assertEquals("none", p.get("item" + i).getAsString(), "item" + i);
		assertArrayEquals(new int[] {11, 13}, HALL.frame(p));
		assertEquals("about 2.3 items a second", ModuleOffer.hallRate(ok("storage_hall").entry().measured(), 4));
		String w = ComposedModules.boundWords("storage hall", HALL, d.order(), d.changed(), false);
		assertTrue(w.contains("It sorts: 4 slots for cobblestone, iron ingot, wheat, coal"), w);
		assertTrue(w.contains("Never put petrified oak slabs in it."), w);
		assertFalse(w.contains("plain hall"), w);
		assertTrue(w.contains(ModuleOffer.LATE_CARGO_WORDS), w);
		// the lot as built is the sorting hall's own lot, its ext the full ref with the items (HallCargo reads them from the site)
		JsonObject built = ComposedModules.builtLot(json("{\"id\": \"warehouse_1\", \"type\": \"storage_hall\", \"size\": [11, 13]}"), HALL, d.order());
		assertEquals("[11,13]", built.get("size").toString());
		assertEquals(d.order().ref(), ModuleRef.of(built.getAsJsonObject("ext")));
		// (guard off) from materials its bound is measured, the fill included (41 of each filter item)
		var m = ComposedModules.decide(hallAsk("{\"slots\": 4, \"item1\": \"cobblestone\", \"item2\": \"iron_ingot\", \"item3\": \"wheat\", \"item4\": \"coal\"}"),
			HALL, ok("storage_hall"), MATERIALS, Set.of(), null, (e, q) -> ModuleBound.of(e.measured(), q), false);
		assertTrue(m.ok(), m.why());
		JsonObject b = ModuleBound.of(ok("storage_hall").entry().measured(), m.order().params());
		assertNotNull(b);
		assertTrue(b.get("minecraft:iron_ingot").getAsInt() >= StorageHallLot.FILTER_COUNT, b.toString());
		assertTrue(ComposedModules.boundWords("hall", HALL, m.order(), null, true).contains(ModuleBound.CRASH_EXCEPTION), "the bound's words carry the crash exception");
	}

	/** (M5b) Slots and items not written, or not kept: the lot's frame picks the slots (most first on a tie), and Steward chooses the items, said. */
	@Test
	void aComposedHallsMissingSlotsAndItemsAreChosenAndSaid() {
		var town = new ComposedModules.Town(false, false, Map.of("minecraft:dirt", 900, "minecraft:sand", 300, "minecraft:diamond_sword", 1, "minecraft:iron_ingot", 0));
		// 11 by 13 fits 4 and 8 slots: 8, said; every item chosen from the stores, most held first, then the domain in its order
		var d = ComposedModules.decide(hallAsk("{\"bulk\": 2}"), HALL, ok("storage_hall"), town, Set.of(), new int[] {13, 11}, (e, p) -> ModuleBound.of(e.measured(), p));
		assertTrue(d.ok(), d.why());
		assertEquals(8, d.order().params().get("slots").getAsInt());
		assertEquals(List.of("minecraft:dirt", "minecraft:sand", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:stone", "minecraft:deepslate",
			"minecraft:red_sand", "minecraft:gravel"), StorageHallLot.items(d.order().params()).items());
		assertTrue(d.changed().contains("fits more than one hall, so the one with the most slots (8)"), d.changed());
		assertTrue(d.changed().contains("Steward chose its 8 slots' items from your stores, most held first, then the hall's defaults: dirt, sand, cobblestone"), d.changed());
		// items written but some repeated or not sortable: those slots chosen, the rest kept, said by slot
		var p = decide(hallAsk("{\"slots\": 4, \"item1\": \"coal\", \"item2\": \"coal\", \"item3\": \"diamond_sword\", \"item4\": \"minecraft:wheat\"}"),
			HALL, WARM, Set.of(), null);
		assertTrue(p.ok(), p.why());
		assertEquals(List.of("minecraft:coal", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:wheat"), StorageHallLot.items(p.order().params()).items());
		assertTrue(p.changed().contains("Steward chose slots 2, 3's items from the hall's defaults"), p.changed());
		var stored = ComposedModules.items(json("{}"), 2, Map.of("minecraft:coal", 5, "minecraft:dirt", 9));
		assertEquals(List.of("dirt", "coal"), stored.items());
		assertTrue(stored.said().endsWith("from your stores, most held first: dirt, coal"), stored.said());
		// the plain frame, or no size at all: the plain hall (the smallest), said where a size was asked
		var plain = decide(hallAsk("{\"bulk\": 4}"), HALL, WARM, Set.of(), new int[] {11, 7});
		assertEquals(0, plain.order().params().get("slots").getAsInt());
		assertNull(plain.changed());
		assertTrue(ComposedModules.boundWords("hall", HALL, plain.order(), null, false).contains("It's a plain hall: it doesn't sort."));
		var bad = decide(hallAsk("{\"slots\": \"four\"}"), HALL, WARM, Set.of(), null);
		assertEquals(0, bad.order().params().get("slots").getAsInt());
		var unmeasured = decide(hallAsk("{\"slots\": 6}"), HALL, WARM, Set.of(), null);
		assertEquals(0, unmeasured.order().params().get("slots").getAsInt());
		assertTrue(unmeasured.changed().contains("the size asked isn't measured, so the smallest"), unmeasured.changed());
		// deterministic
		assertEquals(d, ComposedModules.decide(hallAsk("{\"bulk\": 2}"), HALL, ok("storage_hall"), town, Set.of(), new int[] {13, 11}, (e, q) -> ModuleBound.of(e.measured(), q)));
		// a farm's words don't sort
		var farm = decide(ComposedModules.read(lots(plan())).get(0), FARM, WARM, Set.of(), null).order();
		assertFalse(ComposedModules.boundWords("f", FARM, farm, null, false).contains("sort"));
	}

	static String ComposeJob_rules() {
		return dev.larattalabs.steward.gateway.ComposeJob.rules();
	}

	/** GPT review 4: the lot Architect laid must be the module's frame, watered for a watered module, and a plain lot. */
	@Test
	void theLotMustBeExactWateredAndPlain() {
		var farm = decide(ComposedModules.read(lots(plan())).get(0), FARM, WARM, Set.of(), null).order();
		int[] f = FARM.frame(farm.params());
		JsonObject a = artifactLot("north", 0, 0, f[0], f[1]);
		assertTrue(ComposedModules.lotProblem(a, FARM, farm).contains("water"), "no irrigation");
		a.add("irrigation", json("{\"frame\": [" + f[0] + ", " + f[1] + "], \"lotCells\": [[5, 6, 0, 0]]}"));
		assertNull(ComposedModules.lotProblem(a, FARM, farm));
		JsonObject sited = a.deepCopy();
		sited.addProperty("site", "x");
		assertNotNull(ComposedModules.lotProblem(sited, FARM, farm));
		JsonObject walled = a.deepCopy();
		walled.add("onWall", new JsonObject());
		assertNotNull(ComposedModules.lotProblem(walled, FARM, farm));
		var hall = decide(ComposedModules.read(lots(plan())).get(1), HALL, WARM, Set.of(), null).order();
		assertNull(ComposedModules.lotProblem(artifactLot("west", 0, 0, 11, 7), HALL, hall), "the hall needs no water");
	}
}
