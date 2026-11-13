package dev.larattalabs.steward.module;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** docs/MODULES.md 4.2 (slice M3): how the fixture module is offered and asked of Architect; the id stays a parameter. */
class ModuleKindTest {
	static final ModuleKind K = ModuleKind.FixturePlot.INSTANCE;

	@Test
	void theRuleKeyNamesTheModule() {
		assertEquals("module.fixture_plot", K.rule());
		assertEquals("fixture_plot", ModuleKind.idOf("module.fixture_plot"));
		assertNull(ModuleKind.idOf("farm"));
		assertNull(ModuleKind.idOf("module."));
		assertSame(K, ModuleKind.of("fixture_plot").orElseThrow());
		assertSame(ModuleKind.CropFarm.INSTANCE, ModuleKind.of("crop_farm").orElseThrow());
		assertTrue(ModuleKind.of("storage_hall").isEmpty(), "M4's");
	}

	@Test
	void theGrowLotIsTheExactFrame() {
		var p = JsonParser.parseString("{\"side\": 9, \"crop\": \"wheat\"}").getAsJsonObject();
		assertArrayEquals(new int[] {11, 12}, K.frame(p), "W = side + 2, D = side + 3");
		var lot = K.growLot("gb1_1", p);
		assertEquals("{\"id\":\"gb1_1\",\"size\":[11,12],\"type\":\"module_fixture\"}", Canon.text(lot));
		assertFalse(lot.has("irrigate"), "the fixture has no water");
		assertEquals(81, K.cells(p));
	}

	@Test
	void theCropFarmIsM2sLotAndCountsItsOwnField() {
		var c = ModuleKind.CropFarm.INSTANCE;
		var p = JsonParser.parseString("{\"width\": 9, \"depth\": 9, \"crop\": \"carrots\"}").getAsJsonObject();
		assertEquals(74, c.cells(p), "CropFarmLot's field: the source, its posts and the port left out");
		assertEquals(CropFarmLot.layout(9, 9).field().size(), c.cells(p));
		assertArrayEquals(new int[] {11, 12}, c.frame(p));
		var lot = c.growLot("gb1_1", p);
		assertEquals(CropFarmLot.growLot("gb1_1", 9, 9), lot);
		assertTrue(lot.get("irrigate").getAsBoolean());
		assertEquals("crop_farm", lot.get("type").getAsString());
		assertEquals("carrots", c.seeds().get("minecraft:carrot"), "carrots are planted from the crop itself");
		assertTrue(c.irrigate() && c.warmOnly());
		// the sizes, most cells first: 27x27 first, 9x9 last; the next smaller of 18x18 is 27x9
		var sizes = c.sizes();
		assertEquals(9, sizes.size());
		assertEquals("{\"depth\":27,\"width\":27}", Canon.text(sizes.get(0)));
		assertEquals("{\"depth\":9,\"width\":9}", Canon.text(sizes.get(8)));
		var next = c.smaller(JsonParser.parseString("{\"width\": 18, \"depth\": 18, \"crop\": \"wheat\"}").getAsJsonObject()).orElseThrow();
		assertEquals(27, next.get("width").getAsInt());
		assertEquals(9, next.get("depth").getAsInt());
		assertEquals("wheat", next.get("crop").getAsString());
	}

	@Test
	void aSmallerSizeIsTheNextOfTheDiscreteSet() {
		var p = JsonParser.parseString("{\"side\": 9, \"crop\": \"beetroots\"}").getAsJsonObject();
		var s = K.smaller(p).orElseThrow();
		assertEquals(5, s.get("side").getAsInt());
		assertEquals("beetroots", s.get("crop").getAsString(), "the crop is kept");
		assertTrue(K.smaller(s).isEmpty(), "5 is the smallest");
		assertTrue(K.smaller(JsonParser.parseString("{\"side\": 7, \"crop\": \"wheat\"}").getAsJsonObject()).isEmpty());
	}
}
