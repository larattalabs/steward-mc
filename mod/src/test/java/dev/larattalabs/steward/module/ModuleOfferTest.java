package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.steward.module.ModuleOffer.Path;
import dev.larattalabs.steward.module.ModuleOffer.Town;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** docs/MODULES.md 4.1 "Eligibility", "Seeds and the stockpile", 4.3, 4.4 (slice M3): the offer's decision, on M1's fixture_plot. */
class ModuleOfferTest {
	static final ModuleCatalog CAT = ModuleCatalog.dir(ModuleCatalogTest.FIXTURES, true);
	/** The fixture was measured on Architect 0.62.0: judged here as the dev source judges it (fixtures allowed, a measuring gate). */
	static final ModuleGate.Env DEV = new ModuleGate.Env("26.3", "0.63.0", Set.of(ModuleGate.FEATURE), true, true);
	static final ModuleKind K = ModuleKind.FixturePlot.INSTANCE;
	static final JsonObject BOUND = JsonParser.parseString("{\"minecraft:dirt\": 120, \"minecraft:wheat_seeds\": 81, \"minecraft:oak_fence\": 40}").getAsJsonObject();

	static ModuleGate.Verdict ok() {
		var v = ModuleGate.offer(CAT, K.id(), DEV);
		assertTrue(v.ok(), v.message());
		return v;
	}

	static Town town(Path p, Map<String, Integer> seeds) {
		return new Town(p, null, true, 100, false, false, seeds, Map.of());
	}

	static ModuleOffer.Decision decide(Town t, JsonObject asked) {
		return ModuleOffer.decide(K, ok(), t, asked, (e, params) -> BOUND);
	}

	@Test
	void aRegionTownGetsTheCropMostCarriedAtTheLargestSizeThoseSeedsPlant() {
		var d = decide(town(Path.REGION, Map.of("minecraft:wheat_seeds", 16, "minecraft:beetroot_seeds", 30)), null);
		assertTrue(d.ok(), d.why());
		assertEquals("beetroots", d.crop());
		// 30 seeds plant the 5-wide field (25 cells), not the 9-wide (81)
		assertEquals(5, d.order().params().get("side").getAsInt());
		assertNull(d.order().bound(), "a town built at once is billed nothing");
		assertEquals("fixture_plot", d.order().moduleId());
		assertEquals(CAT.newest(K.id()).orElseThrow().programSha(), d.order().programSha());
		var big = decide(town(Path.REGION, Map.of("minecraft:wheat_seeds", 81)), null);
		assertEquals(9, big.order().params().get("side").getAsInt());
		assertEquals("wheat", big.crop());
	}

	@Test
	void aTownBuiltFromMaterialsCountsItsStockpileAndHoldsTheMeasuredBound() {
		var t = new Town(Path.REGION_MATERIALS, null, true, 100, false, false, Map.of("minecraft:wheat_seeds", 20), Map.of("minecraft:wheat_seeds", 70));
		var d = decide(t, null);
		assertTrue(d.ok(), d.why());
		assertEquals(9, d.order().params().get("side").getAsInt(), "20 carried + 70 in the stockpile plant 81 cells");
		assertEquals(BOUND, d.order().bound());
		String words = ModuleOffer.why(K, d, true, false);
		assertTrue(words.contains("81 wheat seeds from the stockpile"), words);
		assertTrue(words.contains("at most"), words);
		// no bound measured for those params: not offered (there is nothing to approve)
		var none = ModuleOffer.decide(K, ok(), t, null, (e, p) -> null);
		assertFalse(none.ok());
		assertTrue(none.why().contains("bound"), none.why());
	}

	@Test
	void aTownStillBeingBuiltADistrictOrNoGrowthPathIsNotOffered() {
		var building = new Town(Path.REGION_MATERIALS, null, false, 40, false, false, Map.of("minecraft:wheat_seeds", 81), Map.of());
		assertTrue(decide(building, null).why().contains("40%"));
		var district = new Town(Path.REGION, null, true, 100, true, false, Map.of("minecraft:wheat_seeds", 81), Map.of());
		assertTrue(decide(district, null).why().contains("district"));
		var none = new Town(Path.NONE, "its street runs north-south", true, 100, false, false, Map.of("minecraft:wheat_seeds", 81), Map.of());
		assertEquals("its street runs north-south", decide(none, null).why());
	}

	@Test
	void anAdoptedStreetTownTakesADryModuleButNotAWateredOne() {
		assertTrue(decide(town(Path.ADOPT, Map.of("minecraft:wheat_seeds", 81)), null).ok(), "the fixture has no water: slice B grows it");
		var wet = new Wet();
		var d = ModuleOffer.decide(wet, ok(), town(Path.ADOPT, Map.of("minecraft:wheat_seeds", 81)), null, (e, p) -> BOUND);
		assertFalse(d.ok());
		assertTrue(d.why().contains("watered"), d.why());
		var frozen = new Town(Path.REGION, null, true, 100, false, true, Map.of("minecraft:wheat_seeds", 81), Map.of());
		assertTrue(ModuleOffer.decide(wet, ok(), frozen, null, (e, p) -> BOUND).why().contains("freeze"));
	}

	@Test
	void aRefusedVerdictIsNeverOffered() {
		var live = ModuleGate.offer(CAT, K.id(), new ModuleGate.Env("26.3", "0.63.0", Set.of(ModuleGate.FEATURE)));
		assertFalse(live.ok(), "a fixture is never a player's module");
		var d = ModuleOffer.decide(K, live, town(Path.REGION, Map.of("minecraft:wheat_seeds", 81)), null);
		assertFalse(d.ok());
		assertTrue(d.why().contains("fixture"), d.why());
	}

	@Test
	void paramsAskedForAreKeptWhenMeasured() {
		var asked = JsonParser.parseString("{\"side\": 9, \"crop\": \"beetroots\"}").getAsJsonObject();
		var d = decide(town(Path.REGION, Map.of("minecraft:wheat_seeds", 10)), asked);
		assertTrue(d.ok(), d.why());
		assertEquals(9, d.order().params().get("side").getAsInt());
		assertEquals("beetroots", d.order().params().get("crop").getAsString());
		// a size never measured falls back to what the seeds plant
		var odd = decide(town(Path.REGION, Map.of("minecraft:wheat_seeds", 10)), JsonParser.parseString("{\"side\": 7}").getAsJsonObject());
		assertEquals(5, odd.order().params().get("side").getAsInt());
	}

	@Test
	void theRateComesFromTheMeasurementForThoseParams() {
		var raw = JsonParser.parseString("{\"rates\": [{\"params\": {\"side\": 9, \"crop\": \"wheat\"}, \"perDay\": 40, \"item\": \"minecraft:wheat\"}]}").getAsJsonObject();
		var m = new ModuleCatalog.Measured("", "", "", "", List.of(), null, null, false, raw);
		assertEquals("about 40 wheat a day once it's going", ModuleOffer.rate(m, JsonParser.parseString("{\"crop\": \"wheat\", \"side\": 9}").getAsJsonObject()));
		assertNull(ModuleOffer.rate(m, JsonParser.parseString("{\"crop\": \"wheat\", \"side\": 5}").getAsJsonObject()));
	}

	@Test
	void theLiveCropFarmIsOfferedToAPlayerOnItsMeasuredVersionsWithItsBound() {
		var live = ModuleCatalog.dir(ModuleCatalogTest.FIXTURES.resolve("../../../modules").normalize(), false);
		var player = new ModuleGate.Env("26.3", "0.65.0", Set.of(ModuleGate.FEATURE));
		var v = ModuleGate.offer(live, "crop_farm", player);
		assertTrue(v.ok(), v.message());
		assertFalse(ModuleGate.offer(live, "crop_farm", new ModuleGate.Env("26.3", "0.64.1", Set.of(ModuleGate.FEATURE))).ok(), "only on its measured Architect");
		var c = ModuleKind.CropFarm.INSTANCE;
		var t = new Town(Path.REGION_MATERIALS, null, true, 100, false, false, Map.of("minecraft:carrot", 40), Map.of("minecraft:carrot", 300));
		var d = ModuleOffer.decide(c, v, t, null);
		assertTrue(d.ok(), d.why());
		assertEquals("carrots", d.crop());
		assertNotNull(d.order().bound(), "the measured bound for those params");
		assertTrue(d.order().bound().has("minecraft:dirt"));
		assertNotNull(d.order().rate(), "the measured rate");
		assertTrue(d.order().rate().contains("carrot"), d.order().rate());
		// 340 carrots plant 18x18 at most? the largest size whose field those plant
		assertTrue(c.cells(d.order().params()) <= 340);
		// in an adopted street town: never (IRR can't water it, S-M5-8)
		assertFalse(ModuleOffer.decide(c, v, new Town(Path.ADOPT, null, true, 100, false, false, Map.of("minecraft:wheat_seeds", 80), Map.of()), null).ok());
	}

	/** A watered, warm-only kind like crop_farm, on the fixture's catalog entry. */
	static final class Wet implements ModuleKind {
		@Override public String id() { return K.id(); }
		@Override public String title() { return "a watered field"; }
		@Override public String role() { return "crop farm"; }
		@Override public String lotType() { return "crop_farm"; }
		@Override public boolean irrigate() { return true; }
		@Override public boolean warmOnly() { return true; }
		@Override public Set<String> decorative() { return Set.of("farm"); }
		@Override public Map<String, String> seeds() { return K.seeds(); }
		@Override public String cropParam() { return K.cropParam(); }
		@Override public List<JsonObject> sizes() { return K.sizes(); }
		@Override public int cells(JsonObject params) { return K.cells(params); }
		@Override public int[] frame(JsonObject params) { return K.frame(params); }
		@Override public JsonObject growLot(String lotId, JsonObject params) { return K.growLot(lotId, params); }
	}
}
